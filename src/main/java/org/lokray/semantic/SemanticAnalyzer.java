// File: src/main/java/org/lokray/semantic/SemanticAnalyzer.java
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
	private final Scope globalScope = new Scope(null);
	private boolean hasErrors = false;
	private final Map<String, ClassSymbol> declaredClasses = new LinkedHashMap<>();
	private final Map<ParseTree, Type> resolvedTypes = new HashMap<>();
	private final Map<ParseTree, Symbol> resolvedSymbols = new HashMap<>();
	private final Map<ParseTree, Object> resolvedInfo = new HashMap<>();
	private final ErrorHandler errorHandler;

	public SemanticAnalyzer(Path ndkLib, ErrorHandler errorHandler)
	{
		this.errorHandler = errorHandler;
		// Define all primitive types first
		BuiltInTypeLoader.definePrimitives(globalScope);

		// Preload NDK library symbols into the global scope and declaredClasses map
		if (ndkLib != null && Files.exists(ndkLib))
		{
			try
			{
				NebulaLibLoader.loadLibrary(ndkLib, globalScope, declaredClasses);
				// After loading, ensure all NDK types are properly linked
				linkNdkSymbols();
				linkIntrinsicsToNdkStructs();

				// Automatically import all classes from 'nebula.core.*' into the global scope.
				for (Map.Entry<String, ClassSymbol> entry : declaredClasses.entrySet())
				{
					String fqn = entry.getKey();
					if (fqn.startsWith("nebula.core."))
					{
						// FIX: Use the last dot to find the simple class name,
						// which correctly handles classes in sub-packages (e.g., nebula.core.io.Console -> Console).
						int lastDotIndex = fqn.lastIndexOf('.');
						// The simple name is the substring after the last dot.
						String simpleName = fqn.substring(lastDotIndex + 1);

						// Skip string (assuming you only want the lowercase 'string' alias later)
						//if(simpleName.equalsIgnoreCase("string")) continue;

						// 2. Define an AliasSymbol in the global scope for the simple name pointing to the ClassSymbol.
						ClassSymbol classSymbol = entry.getValue();
						globalScope.define(new AliasSymbol(simpleName, classSymbol));
					}
				}

				// Handle the special lowercase 'string' alias separately as intended
				if (declaredClasses.containsKey("nebula.core.String"))
				{
					Symbol stringSymbol = declaredClasses.get("nebula.core.String");
					globalScope.define(new AliasSymbol("string", stringSymbol));
				}

			}
			catch (Exception e)
			{
				Debug.logWarning("Failed to load ndk library: " + e.getMessage());
				e.printStackTrace(); // Uncomment for debugging
			}
		}
	}

	public SemanticAnalyzer(ErrorHandler errorHandler)
	{
		this(null, errorHandler);
	}

	public boolean analyze(ParseTree tree)
	{
		// Pass 1 & 2: Discover all types, handle imports/aliases, and define all members.
		Debug.logDebug("\nSymbol resolution (Types, imports and aliases, and members and fields definition):");
		SymbolTableBuilder defVisitor = new SymbolTableBuilder(globalScope, declaredClasses, errorHandler, false);

		// Debugging
		Debug.logDebug("  Resolved types:");
		for (ClassSymbol type : declaredClasses.values())
		{
			Debug.logDebug("    -" + type.getName());
			// Print here all the methods defined in the class
			// Iterate over the lists of overloads (Map values)
			for (List<MethodSymbol> overloads : type.getMethodsByName().values())
			{
				for (MethodSymbol method : overloads)
				{

					Debug.logDebug("        - Method: " + method);
				}
			}
		}
		defVisitor.visit(tree);

		this.hasErrors = defVisitor.hasErrors();
		if (hasErrors)
		{
			return false;
		}

		// Pass 3: Type Checking and Resolution for method bodies and initializers
		Debug.logDebug("\nType checking...");
		TypeCheckVisitor refVisitor = new TypeCheckVisitor(globalScope, declaredClasses, resolvedSymbols, resolvedTypes, resolvedInfo, errorHandler); // Pass resolvedInfo map
		refVisitor.visit(tree);
		this.hasErrors = refVisitor.hasErrors();

		return !hasErrors;
	}

	private void linkNdkSymbols()
	{
		for (ClassSymbol cs : declaredClasses.values())
		{
			if (!cs.isNative())
			{
				continue;
			}

			// Link Field Types (This part is already correct as VariableSymbol hasn't changed its core Type logic)
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
					// 1. Link return type (This is correct)
					if (ms.getType() instanceof UnresolvedType)
					{
						ms.setReturnType(resolveTypeByName(ms.getType().getName()));
					}

					// 2. Link parameter types (NEW LOGIC)
					// Iterate through the ParameterSymbol list and update the Type object within each symbol.
					for (ParameterSymbol ps : ms.getParameters())
					{
						if (ps.getType() instanceof UnresolvedType)
						{
							// ParameterSymbol inherits setType from VariableSymbol,
							// which is used to update the Type.
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
		for (String fqn : declaredClasses.keySet())
		{
			if (fqn.endsWith("." + name))
			{
				return declaredClasses.get(fqn).getType();
			}
		}
		return new UnresolvedType(name);
	}

	private void linkIntrinsicsToNdkStructs()
	{
		Debug.logDebug("Linking intrinsic types to NDK structs...");

		// Map compiler-known names to the canonical NDK struct name.
		Map<String, String> intrinsicToCanonical = Map.ofEntries(
				Map.entry("bool", "Bool"),
				Map.entry("char", "Char"),

				// Integers
				Map.entry("byte", "Int8"),
				Map.entry("short", "Int16"),
				Map.entry("int", "Int32"),
				Map.entry("long", "Int64"),
				Map.entry("int8", "Int8"),
				Map.entry("int16", "Int16"),
				Map.entry("int32", "Int32"),
				Map.entry("int64", "Int64"),

				//Unsigned integers
				Map.entry("ubyte", "UInt8"),
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
			// --- START NEW LOGGING ---
			// Uncomment below for VERY verbose logging if needed
			// Debug.logDebug("    -> Comparing target " + target + " with key " + key.getText() + " interval " + kint + " (Key Hash: " + key.hashCode() + ")");
			// --- END NEW LOGGING ---
			if (kint != null && kint.equals(target))
			{
				// --- START NEW LOGGING ---
				Debug.logDebug("    -> Found symbol via interval fallback: " + entry.getValue() + " (Matching Key Hash: " + key.hashCode() + ")");
				// --- END NEW LOGGING ---
				return Optional.ofNullable(entry.getValue());
			}
		}
		// --- START NEW LOGGING ---
		Debug.logDebug("  -> Interval fallback FAILED.");
		// --- END NEW LOGGING ---
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
}