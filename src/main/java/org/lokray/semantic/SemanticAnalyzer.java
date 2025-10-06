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

				// FIX: After NDK load, create a global alias for 'string' -> 'nebula.core.String'
				if (declaredClasses.containsKey("nebula.core.String"))
				{
					Symbol stringSymbol = declaredClasses.get("nebula.core.String");
					globalScope.define(new AliasSymbol("string", stringSymbol));
				}

			}
			catch (Exception e)
			{
				Debug.logWarning("Failed to load ndk library: " + e.getMessage());
				//e.printStackTrace(); // Uncomment for debugging
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
		SymbolTableBuilder defVisitor = new SymbolTableBuilder(globalScope, declaredClasses);
		defVisitor.visit(tree);

		this.hasErrors = defVisitor.hasErrors();
		if (hasErrors)
		{
			return false;
		}

		// Pass 3: Type Checking and Resolution for method bodies and initializers
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
					// Link return type
					if (ms.getType() instanceof UnresolvedType)
					{
						ms.setReturnType(resolveTypeByName(ms.getType().getName()));
					}
					// Link parameter types
					List<Type> realParamTypes = new ArrayList<>();
					for (Type paramType : ms.getParameterTypes())
					{
						if (paramType instanceof UnresolvedType)
						{
							realParamTypes.add(resolveTypeByName(paramType.getName()));
						}
						else
						{
							realParamTypes.add(paramType);
						}
					}
					ms.setParameterTypes(realParamTypes);
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

	public Optional<Symbol> getResolvedSymbol(ParseTree node)
	{
		return Optional.ofNullable(resolvedSymbols.get(node));
	}

	public Optional<Type> getResolvedType(ParseTree node)
	{
		return Optional.ofNullable(resolvedTypes.get(node));
	}
}