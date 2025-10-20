// File: src/main/java/org/lokray/semantic/symbol/ClassSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.parser.NebulaParser;
import org.lokray.semantic.type.ClassType;
import org.lokray.semantic.type.Type;
import org.lokray.semantic.type.UnresolvedType;
import org.lokray.util.Debug;

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
	 * Overrides the default scope resolution to handle methods and fields within a class.
	 *
	 * @param name The simple name of the symbol to resolve.
	 * @return An Optional containing the resolved Symbol, or empty if not found.
	 */
	@Override
	public Optional<Symbol> resolveLocally(String name)
	{
		// First, try to resolve non-method symbols (fields, properties) from the regular symbol map.
		Optional<Symbol> symbol = super.resolveLocally(name);
		if (symbol.isPresent())
		{
			return symbol;
		}

		// If no field/property is found, check if it's a method name.
		if (methodsByName.containsKey(name))
		{
			// It's a method group. Return the first overload as a representative symbol.
			// The full overload resolution logic in TypeCheckVisitor will handle the rest.
			return Optional.of(methodsByName.get(name).get(0));
		}

		return Optional.empty();
	}

	/**
	 * Finds methods that are structurally viable for a given set of arguments,
	 * checking arity, named parameter correctness, and default value availability.
	 * This pass does NOT check for type compatibility.
	 */
	public List<MethodSymbol> findViableMethods(String name, List<NebulaParser.ExpressionContext> positionalArgs, Map<String, NebulaParser.ExpressionContext> namedArgs)
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

	/**
	 * The main entry point for overload resolution. Finds the single best method
	 * overload for a given call, considering arity, names, type compatibility,
	 * and specificity.
	 *
	 * @param visitor        The TypeCheckVisitor instance, used to resolve argument types.
	 * @param name           The name of the method being called.
	 * @param positionalArgs The list of positional argument expressions.
	 * @param namedArgs      A map of named argument expressions.
	 * @return An Optional containing the best-fit MethodSymbol, or empty if no unambiguous match is found.
	 */
	public MethodSymbol resolveOverload(org.lokray.semantic.TypeCheckVisitor visitor, String name, List<NebulaParser.ExpressionContext> positionalArgs, Map<String, NebulaParser.ExpressionContext> namedArgs)
	{
		// Phase 1: Find structurally viable candidates (checks arity, names, defaults)
		List<MethodSymbol> candidates = findViableMethods(name, positionalArgs, namedArgs);
		if (candidates.isEmpty())
		{
			return null; // No structurally viable methods found.
		}

		// Phase 2: Filter by argument type compatibility
		List<MethodSymbol> typeCompatible = new ArrayList<>();
		Debug.logDebug("--- Overload Phase 2 (Type Check) for: " + name + " ---");
		for (MethodSymbol m : candidates)
		{
			// We'll add logs to areArgumentsAssignableTo in the next step
			boolean assignable = visitor.areArgumentsAssignableTo(m, positionalArgs, namedArgs);
			Debug.logDebug("  Checking: " + m + " -> Assignable? " + assignable);
			if (assignable)
			{
				typeCompatible.add(m);
			}
		}
		Debug.logDebug("--- End Phase 2 ---");


		if (typeCompatible.isEmpty())
		{
			return null; // No methods match the argument types.
		}

		if (typeCompatible.size() == 1)
		{
			return typeCompatible.getFirst(); // Only one match, we're done.
		}

		// Phase 3: Disambiguation by Argument Specificity (Tie-breaker)
		Debug.logDebug("--- Overload Phase 3 (Costing) ---");
		Debug.logDebug("  Type compatible candidates (" + typeCompatible.size() + "):");
		for (MethodSymbol m : typeCompatible)
		{
			Debug.logDebug("    - " + m);
		}

		MethodSymbol bestMatch = null;
		int minCost = Integer.MAX_VALUE;
		boolean isAmbiguous = false;

		for (MethodSymbol candidate : typeCompatible)
		{
			Debug.logDebug("  Costing: " + candidate);
			// We'll add logs to calculateArgumentMatchCost in the next step
			int currentCost = visitor.calculateArgumentMatchCost(candidate, positionalArgs, namedArgs);
			Debug.logDebug("    -> Cost: " + currentCost);

			if (currentCost < minCost)
			{
				minCost = currentCost;
				bestMatch = candidate;
				isAmbiguous = false;
				Debug.logDebug("    -> New Best Match. minCost=" + minCost + ", isAmbiguous=false");
			}
			else if (currentCost == minCost)
			{
				isAmbiguous = true;
				Debug.logDebug("    -> Ambiguous Match. currentCost=" + currentCost + ", minCost=" + minCost + ", isAmbiguous=true");
			}
			else
			{
				Debug.logDebug("    -> Worse Match. currentCost=" + currentCost + ", minCost=" + minCost);
			}
		}

		if (isAmbiguous)
		{
			Debug.logDebug("--- Overload Result: AMBIGUOUS ---");
			return null; // Ambiguous call
		}

		Debug.logDebug("--- Overload Result: " + (bestMatch != null ? bestMatch.toString() : "NULL") + " ---");
		return bestMatch;
	}
}