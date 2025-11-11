package org.lokray.semantic;

import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.semantic.symbol.*;
import org.lokray.semantic.type.*;
import org.lokray.util.BuiltInTypeLoader;
import org.lokray.util.Debug;
import org.lokray.util.ErrorHandler;
import org.lokray.util.NebulaLibLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SemanticAnalyzer
{
	public Scope globalScope = new Scope(null);
	private boolean hasErrors = false;
	public Map<String, ClassSymbol> declaredClasses = new LinkedHashMap<>();
	public final Map<ParseTree, Type> resolvedTypes = new HashMap<>();
	public final Map<ParseTree, Symbol> resolvedSymbols = new HashMap<>();
	public final Map<ParseTree, Object> resolvedInfo = new HashMap<>();
	private final ErrorHandler errorHandler;

	/**
	 * Creates a new SemanticAnalyzer, loading all specified libraries.
	 *
	 * @param libraryFiles       A list of specific .neblib files to load.
	 * @param librarySearchPaths A list of directories to search for .neblib files (Not yet implemented).
	 * @param errorHandler       The error handler instance.
	 */
	public SemanticAnalyzer(List<Path> libraryFiles, List<Path> librarySearchPaths, ErrorHandler errorHandler)
	{
		this.errorHandler = errorHandler;
		// Define all primitive types first
		BuiltInTypeLoader.definePrimitives(globalScope);

		// TODO: Add logic to scan librarySearchPaths for .neblib files

		// Preload all specified library symbols into the global scope
		for (Path libFile : libraryFiles)
		{
			if (libFile != null && Files.exists(libFile))
			{
				try
				{
					NebulaLibLoader.loadLibrary(libFile, globalScope, declaredClasses);
				}
				catch (Exception e)
				{
					Debug.logWarning("Failed to load library: " + libFile.getFileName() + " | Reason: " + e.getMessage());
					e.printStackTrace();
				}
			}
		}
	}

	/**
	 * Links core types and sets up aliases.
	 * This MUST be called after Pass 1 (Discovery) and before Pass 3 (Type Check).
	 */
	public void finalizeSymbolTable()
	{
		linkNdkSymbols();
		linkSuperclasses();
		linkIntrinsicsToNdkStructs();
		linkArrayTypeToBackingStruct();
		autoImportNebulaCore(globalScope);
	}

	/**
	 * Public method for auto-importing.
	 * (This logic was moved from the constructor)
	 */
	public void autoImportNebulaCore(Scope scope)
	{
		// Automatically import all classes from 'nebula.core.*' into the given scope.
		for (Map.Entry<String, ClassSymbol> entry : declaredClasses.entrySet())
		{
			String fqn = entry.getKey();
			if (fqn.startsWith("nebula.core."))
			{
				int lastDotIndex = fqn.lastIndexOf('.');
				String simpleName = fqn.substring(lastDotIndex + 1);

				ClassSymbol classSymbol = entry.getValue();
				scope.define(new AliasSymbol(simpleName, classSymbol));
			}
		}

		// Handle the special lowercase 'string' alias
		if (declaredClasses.containsKey("nebula.core.String"))
		{
			Symbol stringSymbol = declaredClasses.get("nebula.core.String");
			scope.define(new AliasSymbol("string", stringSymbol));
		}
	}

	/**
	 * Multi-file analysis entry point.
	 * Performs discovery, member definition, type checking, and finalization
	 * across *all* parse trees (library or executable).
	 */
	public boolean analyze(List<ParseTree> trees)
	{
		Debug.logDebug("Starting semantic analysis across " + trees.size() + " file(s)...");

		// --- PASS 1: Type discovery ---
		Debug.logDebug("PASS 1: Discovering types...");
		SymbolTableBuilder discoveryVisitor = new SymbolTableBuilder(globalScope, declaredClasses, errorHandler, true);
		for (ParseTree t : trees)
		{
			discoveryVisitor.visit(t);
		}

		// Define string alias if available
		if (declaredClasses.containsKey("nebula.core.String"))
		{
			globalScope.define(new AliasSymbol("string", declaredClasses.get("nebula.core.String")));
		}

		if (errorHandler.hasErrors())
		{
			Debug.logError("Errors encountered during discovery pass.");
			return false;
		}

		// --- PASS 2: Define members ---
		Debug.logDebug("PASS 2: Defining members...");
		SymbolTableBuilder memberVisitor = new SymbolTableBuilder(globalScope, declaredClasses, errorHandler, false);
		for (ParseTree t : trees)
		{
			memberVisitor.visit(t);
		}

		if (errorHandler.hasErrors())
		{
			Debug.logError("Errors encountered during member definition pass.");
			return false;
		}

		// --- Finalize symbol table (core imports, aliases, etc.) ---
		finalizeSymbolTable();

		// --- PASS 3: Type checking ---
		Debug.logDebug("PASS 3: Type checking...");
		TypeCheckVisitor typeChecker = new TypeCheckVisitor(
				globalScope,
				declaredClasses,
				resolvedSymbols,
				resolvedTypes,
				resolvedInfo,
				errorHandler
		);

		for (ParseTree t : trees)
		{
			typeChecker.visit(t);
		}

		if (typeChecker.hasErrors() || errorHandler.hasErrors())
		{
			Debug.logError("Errors encountered during type checking.");
			return false;
		}

		Debug.logDebug("Semantic analysis completed successfully across all files.");
		return true;
	}

	private void linkNdkSymbols()
	{
		for (ClassSymbol cs : declaredClasses.values())
		{
			if (!cs.isNative())
			{
				continue;
			}

			// Link Field Types
			cs.getSymbols().values().stream()
					.filter(sym -> sym instanceof VariableSymbol)
					.map(sym -> (VariableSymbol) sym)
					.forEach(vs ->
					{
						if (vs.getType() instanceof UnresolvedType)
						{
							vs.setType(resolveTypeByName(vs.getType().getName()));
						}
					});

			// Link Method Return and Parameter Types
			for (List<MethodSymbol> overloads : cs.getMethodsByName().values())
			{
				for (MethodSymbol ms : overloads)
				{
					// 1. Link return type
					if (ms.getType() instanceof UnresolvedType)
					{
						ms.setReturnType(resolveTypeByName(ms.getType().getName()));
					}

					// 2. Link parameter types
					for (ParameterSymbol ps : ms.getParameters())
					{
						if (ps.getType() instanceof UnresolvedType)
						{
							ps.setType(resolveTypeByName(ps.getType().getName()));
						}
					}
				}
			}
		}
	}

	private Type resolveTypeByName(String name)
	{
		Optional<Symbol> primitive = globalScope.resolveLocally(name);
		if (primitive.isPresent() && primitive.get().getType() instanceof PrimitiveType)
		{
			return primitive.get().getType();
		}
		if (declaredClasses.containsKey(name))
		{
			return declaredClasses.get(name).getType();
		}
		String fqn = findFqnForSimpleName(name);
		if (fqn != null)
		{
			return declaredClasses.get(fqn).getType();
		}
		return new UnresolvedType(name);
	}

	private String findFqnForSimpleName(String simpleName)
	{
		// This logic might be imperfect if multiple namespaces have same class name
		for (String fqn : declaredClasses.keySet())
		{
			if (fqn.endsWith("." + simpleName))
			{
				return fqn;
			}
		}
		return null;
	}

	private void linkIntrinsicsToNdkStructs()
	{
		Debug.logDebug("Linking intrinsic types to NDK structs...");

		// Map compiler-known names to the canonical NDK struct name.
		Map<String, String> intrinsicToCanonical = Map.ofEntries(
				Map.entry("bool", "Bool"),
				Map.entry("char", "Char"),

				// Integers
				Map.entry("sbyte", "Int8"), // Renamed from byte
				Map.entry("short", "Int16"),
				Map.entry("int", "Int32"),
				Map.entry("long", "Int64"),
				Map.entry("int8", "Int8"),
				Map.entry("int16", "Int16"),
				Map.entry("int32", "Int32"),
				Map.entry("int64", "Int64"),

				//Unsigned integers
				Map.entry("byte", "UInt8"), // Renamed from ubyte
				Map.entry("ushort", "UInt16"),
				Map.entry("uint", "UInt32"),
				Map.entry("ulong", "UInt64"),
				Map.entry("uint8", "UInt8"),
				Map.entry("uint16", "UInt16"),
				Map.entry("uint32", "UInt32"),
				Map.entry("uint64", "UInt64"),

				Map.entry("float", "Float"),
				Map.entry("double", "Double")

		);

		for (PrimitiveType pType : BuiltInTypeLoader.getAllPrimitives())
		{
			String canonicalName = intrinsicToCanonical.get(pType.getName());
			if (canonicalName == null)
			{
				continue; // Skip types like 'void'
			}

			String fqn = "nebula.core." + canonicalName;

			Symbol symbol = declaredClasses.get(fqn);
			if (symbol instanceof StructSymbol)
			{
				pType.setBackingStruct((StructSymbol) symbol);
				//Debug.logDebug("  Linked " + pType.getName() + " -> " + fqn);
			}
		}
	}

	public Optional<Symbol> getResolvedSymbol(ParseTree node)
	{
		String nodeText = (node != null) ? node.getText() : "null";
		int nodeHash = (node != null) ? node.hashCode() : 0;
		Interval nodeInterval = (node != null) ? node.getSourceInterval() : null;
		// Use WARNING level temporarily to make sure it stands out
		Debug.logDebug("SemanticAnalyzer.getResolvedSymbol called for node: " + nodeText + " (Hash: " + nodeHash + ", Interval: " + nodeInterval + ")");

		if (node == null)
		{ /*...*/
			return Optional.empty();
		}

		// fast path: direct object-equality lookup
		Symbol s = resolvedSymbols.get(node);
		if (s != null)
		{
			Debug.logDebug("  -> Found symbol via direct lookup: " + s.getName());
			return Optional.of(s);
		}

		// fallback: match by source interval
		Interval target = node.getSourceInterval();
		if (target == null)
		{ /*...*/
			return Optional.empty();
		}

		Debug.logDebug("  -> Direct lookup failed, trying interval fallback...");
		for (Map.Entry<ParseTree, Symbol> entry : resolvedSymbols.entrySet()) // Iterate entries
		{
			ParseTree key = entry.getKey();
			Interval kint = key.getSourceInterval();
			// Debug.logDebug("    -> Comparing target " + target + " with key " + key.getText() + " interval " + kint + " (Key Hash: " + key.hashCode() + ")");
			if (kint != null && kint.equals(target))
			{
				// Debug.logDebug("    -> Found symbol via interval fallback: " + entry.getValue() + " (Matching Key Hash: " + key.hashCode() + ")");
				return Optional.ofNullable(entry.getValue());
			}
		}
		Debug.logDebug("  -> Interval fallback FAILED.");
		return Optional.empty();
	}

	public Optional<Type> getResolvedType(ParseTree node)
	{
		if (node == null)
		{
			Debug.logDebug("getResolvedType() -> Node is null, returning Optional.empty()");
			return Optional.empty();
		}

		Type t = resolvedTypes.get(node);
		if (t != null)
		{
			Debug.logDebug("getResolvedType() -> Resolved: " + t.getName() + ", returning Optional.of(" + t.getName() + ")");
			return Optional.of(t);
		}

		Interval target = node.getSourceInterval();
		if (target == null)
		{
			return Optional.empty();
		}

		for (ParseTree key : resolvedTypes.keySet())
		{
			Interval kint = key.getSourceInterval();
			if (kint != null && kint.equals(target))
			{
				return Optional.ofNullable(resolvedTypes.get(key));
			}
		}
		return Optional.empty();
	}

	public Optional<Object> getResolvedInfo(ParseTree node)
	{
		if (node == null)
		{
			return Optional.empty();
		}


		Object o = resolvedInfo.get(node);
		if (o != null)
		{
			return Optional.of(o);
		}

		// fallback by source interval (matches what you already do for symbols/types)
		Interval target = node.getSourceInterval();
		if (target == null)
		{
			return Optional.empty();
		}

		for (ParseTree key : resolvedInfo.keySet())
		{
			Interval kint = key.getSourceInterval();
			if (kint != null && kint.equals(target))
			{
				return Optional.ofNullable(resolvedInfo.get(key));
			}
		}
		return Optional.empty();
	}

	String getResolvedSymbolsList()
	{
		StringBuilder result = new StringBuilder();

		for (Symbol symbol : resolvedSymbols.values())
		{
			result.append(symbol.getName());
			result.append("\n");
		}

		return result.toString();
	}

	String getResolvedTypesList()
	{
		StringBuilder result = new StringBuilder();

		for (Symbol type : resolvedTypes.values())
		{
			result.append(type.getName());
			result.append("\n");
		}

		return result.toString();
	}

	private void linkArrayTypeToBackingStruct()
	{
		Debug.logDebug("Linking ArrayType to nebula.core.Array struct...");
		Symbol arraySymbol = declaredClasses.get("nebula.core.Array");

		if (arraySymbol instanceof StructSymbol arrayStruct)
		{
			ArrayType.setBackingStruct(arrayStruct); //
			Debug.logDebug("  -> Successfully linked ArrayType to " + arrayStruct.getFqn());
		}
		else
		{
			Debug.logWarning("  -> FAILED to link ArrayType. 'nebula.core.Array' not found or is not a StructSymbol.");
		}
	}

	private void linkSuperclasses()
	{
		Debug.logDebug("Linking superclasses...");
		// 1. Find the canonical 'Object' symbol from the NDK
		ClassSymbol objectSymbol = declaredClasses.get("nebula.core.Object");
		if (objectSymbol == null)
		{
			// This can happen if ndk.neblib isn't loaded, which is fine for the NDK build itself.
			Debug.logWarning("  -> 'nebula.core.Object' not found. Skipping superclass linking.");
			return;
		}

		// 2. Iterate all *other* classes
		for (ClassSymbol cs : declaredClasses.values())
		{
			// Don't link Object to itself.
			// Only link classes (which are not StructSymbols)
			if (cs != objectSymbol && cs.getSuperClass() == null && !(cs instanceof StructSymbol))
			{
				// If it doesn't have a superclass, set it to Object
				cs.setSuperClass(objectSymbol);
				Debug.logDebug("  -> Set " + cs.getFqn() + " superclass to nebula.core.Object");
			}
		}
	}
}
