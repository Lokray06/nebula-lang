// File: src/main/java/org/lokray/semantic/SemanticAnalyzer.java
package org.lokray.semantic;

import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.semantic.symbol.*;
import org.lokray.semantic.type.*;
import org.lokray.util.BuiltInTypeLoader;
import org.lokray.util.Debug;
import org.lokray.util.NebulaLibLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class SemanticAnalyzer
{
	private final Scope globalScope = new Scope(null);
	private boolean hasErrors = false;
	private final Map<ParseTree, Symbol> resolvedSymbols = new HashMap<>();
	private final Map<ParseTree, Type> resolvedTypes = new HashMap<>();
	private final Map<String, ClassSymbol> declaredClasses = new LinkedHashMap<>();

	public SemanticAnalyzer(Path ndkLib)
	{
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

				// After NDK load, create a global alias for 'string' -> 'nebula.core.String'
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

	public SemanticAnalyzer()
	{
		this(null);
	}

	public boolean analyze(ParseTree tree)
	{
		// Pass 1 & 2: Discover all types, handle imports/aliases, and define all members.
		Debug.logDebug("\nSymbol resolution (Types, imports and aliases, and members and fields definition):");
		SymbolTableBuilder defVisitor = new SymbolTableBuilder(globalScope, declaredClasses);
		Debug.logDebug("  Resolved types:");
		for (ClassSymbol type : declaredClasses.values())
		{
			Debug.logDebug("    -" + type.getName());
		}
		defVisitor.visit(tree);

		this.hasErrors = defVisitor.hasErrors();
		if (hasErrors)
		{
			return false;
		}

		// Pass 3: Type Checking and Resolution for method bodies and initializers
		Debug.logDebug("\nType checking...");
		TypeCheckVisitor refVisitor = new TypeCheckVisitor(globalScope, declaredClasses, resolvedSymbols, resolvedTypes);
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
				Debug.logDebug("  Linked " + pType.getName() + " -> " + fqn);
			}
		}
	}

	public Optional<Symbol> getResolvedSymbol(ParseTree node)
	{
		return Optional.ofNullable(resolvedSymbols.get(node));
	}

	public Optional<Type> getResolvedType(ParseTree node)
	{
		return Optional.ofNullable(resolvedTypes.get(node));
	}
}