// File: src/main/java/org/lokray/semantic/symbol/ClassSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.parser.NebulaParser;
import org.lokray.semantic.type.ClassType;
import org.lokray.semantic.type.Type;
import org.lokray.semantic.type.UnresolvedType;

import java.util.*;
import java.util.stream.Collectors;

public class ClassSymbol extends Scope implements Symbol
{
	private final String name;
	private final ClassType type;
	private final Map<String, List<MethodSymbol>> methodsByName = new HashMap<>();
	private ClassSymbol superClass; // Added superclass field

	// Modifiers
	private final boolean isNative;
	private final boolean isPublic;

	public ClassSymbol(String name, Scope enclosingScope, boolean isNative, boolean isPublic)
	{
		super(enclosingScope);
		this.name = name;
		this.type = new ClassType(this);
		this.isNative = isNative;
		this.isPublic = isPublic;
	}

	public boolean isNative()
	{
		return isNative;
	}

	public ClassSymbol getSuperClass()
	{
		return superClass;
	}

	public void setSuperClass(ClassSymbol superClass)
	{
		this.superClass = superClass;
	}

	@Override
	public ClassSymbol getClassSymbol()
	{
		return this;
	}

	/**
	 * Defines a method in the class's scope. All overloads are stored under the same name.
	 *
	 * @param ms MethodSymbol to define.
	 */
	public void defineMethod(MethodSymbol ms)
	{
		methodsByName.computeIfAbsent(ms.getName(), k -> new ArrayList<>()).add(ms);
		super.define(ms);
	}

	/**
	 * Retrieves all method overloads for a given name.
	 *
	 * @param name The simple name of the method.
	 * @return A list of MethodSymbol overloads, or an empty list if none exist.
	 */
	public List<MethodSymbol> resolveMethods(String name)
	{
		List<MethodSymbol> methods = methodsByName.getOrDefault(name, new ArrayList<>());
		// Also check superclass if inheritance is implemented
		if (superClass != null)
		{
			methods.addAll(superClass.resolveMethods(name));
		}
		return methods;
	}

	/**
	 * Finds methods that are structurally viable for a given set of arguments,
	 * checking arity, named parameter correctness, and default value availability.
	 * This pass does NOT check for type compatibility.
	 */
	public List<MethodSymbol> findViableMethods(
			String name,
			List<NebulaParser.ExpressionContext> positionalArgs,
			Map<String, NebulaParser.ExpressionContext> namedArgs)
	{
		List<MethodSymbol> candidates = resolveMethods(name);
		List<MethodSymbol> viableCandidates = new ArrayList<>();

		for (MethodSymbol candidate : candidates)
		{
			List<ParameterSymbol> params = candidate.getParameters();
			boolean[] isAssigned = new boolean[params.size()];
			boolean isViable = true;

			// Rule 1: Too many positional args makes it non-viable.
			if (positionalArgs.size() > params.size())
			{
				continue;
			}

			// Rule 2: Positional args must come before named args (grammar enforces this).
			// Assign positional args first.
			for (int i = 0; i < positionalArgs.size(); i++)
			{
				isAssigned[i] = true;
			}

			// Rule 3: Assign named args and check for errors.
			for (Map.Entry<String, NebulaParser.ExpressionContext> entry : namedArgs.entrySet())
			{
				String argName = entry.getKey();
				Optional<ParameterSymbol> targetParamOpt = params.stream()
						.filter(p -> p.getName().equals(argName))
						.findFirst();

				if (targetParamOpt.isEmpty())
				{
					isViable = false; // Unknown named argument for this candidate.
					break;
				}
				int pos = targetParamOpt.get().getPosition();
				if (isAssigned[pos])
				{
					isViable = false; // Parameter already assigned positionally.
					break;
				}
				isAssigned[pos] = true;
			}
			if (!isViable)
			{
				continue;
			}

			// Rule 4: All parameters without default values must be assigned.
			for (int i = 0; i < params.size(); i++)
			{
				if (!isAssigned[i] && !params.get(i).hasDefaultValue())
				{
					isViable = false;
					break;
				}
			}

			if (isViable)
			{
				viableCandidates.add(candidate);
			}
		}

		return viableCandidates;
	}

	// Find a method by name, parameter types, and return type (for overloads differing only by return type)
	public Optional<MethodSymbol> resolveMethodBySignature(String name, List<Type> paramTypes, Type returnType)
	{
		List<MethodSymbol> candidates = resolveMethods(name);

		for (MethodSymbol m : candidates)
		{
			// Match by parameter types
			List<Type> mParams = m.getParameterTypes();
			if (mParams.size() != paramTypes.size())
			{
				continue;
			}

			boolean paramsMatch = true;
			for (int i = 0; i < mParams.size(); i++)
			{
				// Allow unresolved types to match for initial definition finding
				if (!mParams.get(i).equals(paramTypes.get(i)) && !(mParams.get(i) instanceof UnresolvedType || paramTypes.get(i) instanceof UnresolvedType))
				{
					paramsMatch = false;
					break;
				}
			}

			// Also match return type
			if (paramsMatch && (m.getType().equals(returnType) || m.getType() instanceof UnresolvedType || returnType instanceof UnresolvedType))
			{
				return Optional.of(m);
			}
		}

		return Optional.empty();
	}

	public Map<String, List<MethodSymbol>> getMethodsByName()
	{
		return methodsByName;
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public Type getType()
	{
		return type;
	}

	public boolean isPublic()
	{
		return isPublic;
	}
}