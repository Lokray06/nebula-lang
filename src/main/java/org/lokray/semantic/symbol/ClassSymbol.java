// File: src/main/java/org/lokray/semantic/symbol/ClassSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.parser.NebulaParser;
import org.lokray.semantic.type.ClassType;
import org.lokray.semantic.type.Type;
import org.lokray.semantic.type.UnresolvedType;
import org.lokray.util.Debug;

import java.util.*;

public class ClassSymbol extends Scope implements Symbol
{
	private final String name;
	private final ClassType type;
	private final Map<String, List<MethodSymbol>> methodsByName = new HashMap<>();
	private final Map<String, List<MethodSymbol>> methodsByMangledName = new HashMap<>();
	private ClassSymbol superClass; // Added superclass field
	private final List<TypeParameterSymbol> typeParameters = new ArrayList<>();

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

	/**
	 * Adds a type parameter to this class's definition (e.g., 'T').
	 * This is used by the SymbolTableBuilder during the discovery pass.
	 *
	 * @param paramSymbol The symbol for the type parameter (e.g., T).
	 */
	public void addTypeParameter(TypeParameterSymbol paramSymbol)
	{
		this.typeParameters.add(paramSymbol);
	}

	/**
	 * Gets the list of declared type parameters (e.g., [T, U]).
	 *
	 * @return An unmodifiable list of type parameters.
	 */
	public List<TypeParameterSymbol> getTypeParameters()
	{
		return Collections.unmodifiableList(this.typeParameters);
	}

	/**
	 * Checks if this class is a generic type definition (e.g., List<T>).
	 *
	 * @return true if the class declares type parameters, false otherwise.
	 */
	public boolean isGenericDefinition()
	{
		return !this.typeParameters.isEmpty();
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
	 * @param method MethodSymbol to define.
	 */
	public void defineMethod(MethodSymbol method)
	{
		// 1. Store by simple name (for lookup/overload resolution)
		methodsByName.computeIfAbsent(method.getName(), k -> new ArrayList<>()).add(method);

		// 2. Store by MANGLED name (for unique definition/IR generation)
		// The MethodSymbol must calculate its mangledName in its constructor.
		methodsByMangledName.computeIfAbsent(method.getMangledName(), k -> new ArrayList<>()).add(method);

		super.define(method);
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
	 * Retrieves a method by its unique, mangled name.
	 * Used primarily for code generation to look up the method definition.
	 */
	public Optional<MethodSymbol> resolveMethodByMangledName(String mangledName)
	{
		// For methods, there should only ever be one symbol per unique mangled name.
		List<MethodSymbol> methods = methodsByMangledName.get(mangledName);
		if (methods != null && methods.size() == 1)
		{
			return Optional.of(methods.get(0));
		}
		return Optional.empty();
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
	 * specificity, and finally the expected return type.
	 *
	 * @param visitor        The TypeCheckVisitor instance, used to resolve argument types
	 *                       and provide the expected return type.
	 * @param name           The name of the method being called.
	 * @param positionalArgs The list of positional argument expressions.
	 * @param namedArgs      A map of named argument expressions.
	 * @return The best-fit MethodSymbol, or null if no unambiguous match is found.
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
		// This now correctly uses the updated `areArgumentsAssignableTo` which passes
		// expected types to the argument expressions.
		List<MethodSymbol> typeCompatible = new ArrayList<>();
		Debug.logDebug("--- Overload Phase 2 (Type Check) for: " + name + " ---");
		for (MethodSymbol m : candidates)
		{
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
		// This phase finds the set of methods with the *lowest* argument conversion cost.
		Debug.logDebug("--- Overload Phase 3 (Costing) ---");
		Debug.logDebug("  Type compatible candidates (" + typeCompatible.size() + "):");
		for (MethodSymbol m : typeCompatible)
		{
			Debug.logDebug("    - " + m);
		}

		// --- REVISED LOGIC ---
		// We now collect *all* best matches, not just the first one.
		List<MethodSymbol> bestArgMatches = new ArrayList<>();
		int minCost = Integer.MAX_VALUE;

		for (MethodSymbol candidate : typeCompatible)
		{
			Debug.logDebug("  Costing: " + candidate);
			// This now correctly uses the updated `calculateArgumentMatchCost`
			int currentCost = visitor.calculateArgumentMatchCost(candidate, positionalArgs, namedArgs);
			Debug.logDebug("    -> Cost: " + currentCost);

			if (currentCost < minCost)
			{
				minCost = currentCost;
				bestArgMatches.clear(); // Found a new best, clear old list
				bestArgMatches.add(candidate);
				Debug.logDebug("    -> New Best Match. minCost=" + minCost);
			}
			else if (currentCost == minCost)
			{
				bestArgMatches.add(candidate); // Add to list of ambiguous best matches
				Debug.logDebug("    -> Ambiguous Match. currentCost=" + currentCost + ", minCost=" + minCost);
			}
			else
			{
				Debug.logDebug("    -> Worse Match. currentCost=" + currentCost + ", minCost=" + minCost);
			}
		}
		// --- END REVISED LOGIC ---

		// If Phase 3 found a single best match, return it.
		if (bestArgMatches.size() == 1)
		{
			MethodSymbol bestMatch = bestArgMatches.get(0);
			Debug.logDebug("--- Overload Result (Phase 3): " + bestMatch + " ---");
			return bestMatch;
		}

		// --- NEW: Phase 4: Return Type Tie-breaker ---
		// If we are here, bestArgMatches.size() > 1. We have an ambiguity
		// based on arguments alone. Now we check the expected return type.
		Type expectedReturnType = visitor.getExpectedType();
		if (expectedReturnType != null)
		{
			Debug.logDebug("--- Overload Phase 4 (Return Type Tie-break) ---");
			Debug.logDebug("  Context expects return type: " + expectedReturnType.getName());

			// 1. Look for an *exact* return type match
			List<MethodSymbol> exactReturnMatches = bestArgMatches.stream()
					.filter(m -> m.getType().equals(expectedReturnType))
					.toList();

			if (exactReturnMatches.size() == 1)
			{
				MethodSymbol bestMatch = exactReturnMatches.get(0);
				Debug.logDebug("  -> Found single *exact* return type match: " + bestMatch);
				Debug.logDebug("--- Overload Result (Phase 4): " + bestMatch + " ---");
				return bestMatch;
			}

			// 2. If no exact match, look for an *assignable* return type match
			if (exactReturnMatches.isEmpty())
			{
				List<MethodSymbol> assignableReturnMatches = bestArgMatches.stream()
						.filter(m -> m.getType().isAssignableTo(expectedReturnType))
						.toList();

				if (assignableReturnMatches.size() == 1)
				{
					MethodSymbol bestMatch = assignableReturnMatches.get(0);
					Debug.logDebug("  -> Found single *assignable* return type match: " + bestMatch);
					Debug.logDebug("--- Overload Result (Phase 4): " + bestMatch + " ---");
					return bestMatch;
				}
			}

			// If we're still ambiguous (e.g., multiple exact matches, or multiple
			// assignable matches), we fall through to the ambiguity error.
		}

		// If we're here, we had multiple best-cost matches (bestArgMatches.size() > 1)
		// and the expected return type was either null or couldn't break the tie.
		Debug.logDebug("--- Overload Result: AMBIGUOUS (Phases 3 & 4 failed to find single match) ---");
		return null; // Ambiguous call
	}
}