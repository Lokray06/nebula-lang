// File: src/main/java/org/lokray/semantic/ClassSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.parser.NebulaParser;
import org.lokray.semantic.type.ClassType;
import org.lokray.semantic.type.Type;

import java.util.*;

public class ClassSymbol extends Scope implements Symbol {
    private final String name;
    private final ClassType type;
    private final Map<String, List<MethodSymbol>> methodsByName = new HashMap<>();
    private ClassSymbol superClass; // Added superclass field

    // Modifiers
    private final boolean isNative;
    private final boolean isPublic;

    public ClassSymbol(String name, Scope enclosingScope, boolean isNative, boolean isPublic) {
        super(enclosingScope);
        this.name = name;
        this.type = new ClassType(this);
        this.isNative = isNative;
        this.isPublic = isPublic;
    }

    public boolean isNative() {
        return isNative;
    }

    public ClassSymbol getSuperClass() {
        return superClass;
    }

    public void setSuperClass(ClassSymbol superClass) {
        this.superClass = superClass;
    }

    /**
     *
     * @param ms MethodSymbol to define in the class' scope
     *           Defines the method with mangling (methodName_methodReturnType) if a method with that name already exists
     */
    public void defineMethod(MethodSymbol ms) {
        // Name mangle by including return type in the key
        String mangledName = ms.getName() + "_" + ms.getType().getName();
        methodsByName.computeIfAbsent(mangledName, k -> new ArrayList<>()).add(ms);

        // Keep compatibility for normal lookups too (by base name only)
        methodsByName.computeIfAbsent(ms.getName(), k -> new ArrayList<>()).add(ms);

        super.define(ms);
    }

    public List<MethodSymbol> resolveMethods(String name) {
        return methodsByName.getOrDefault(name, new ArrayList<>());
    }

    /**
     * Return the list of overload candidates that are viable for the given
     * positional and named arguments (parameter matching only). This does not
     * consider return-type — it only checks positional/named/default parameter viability.
     */
    public List<MethodSymbol> findViableMethods(
            String name,
            List<NebulaParser.ExpressionContext> positionalArgs,
            Map<String, NebulaParser.ExpressionContext> namedArgs)
    {
        List<MethodSymbol> candidates = methodsByName.getOrDefault(name, new ArrayList<>());
        List<MethodSymbol> viableCandidates = new ArrayList<>();

        for (MethodSymbol candidate : candidates)
        {
            List<ParameterSymbol> params = candidate.getParameters();
            NebulaParser.ExpressionContext[] assignedArgs = new NebulaParser.ExpressionContext[params.size()];
            boolean[] isAssigned = new boolean[params.size()];
            boolean isViable = true;

            // Too many positional args -> skip
            if (positionalArgs.size() > params.size())
            {
                continue;
            }

            // Assign positional args
            for (int i = 0; i < positionalArgs.size(); i++)
            {
                assignedArgs[i] = positionalArgs.get(i);
                isAssigned[i] = true;
            }

            // Assign named args
            for (Map.Entry<String, NebulaParser.ExpressionContext> entry : namedArgs.entrySet())
            {
                String argName = entry.getKey();
                Optional<ParameterSymbol> targetParam = params.stream()
                        .filter(p -> p.getName().equals(argName))
                        .findFirst();

                if (targetParam.isEmpty())
                {
                    isViable = false; // unknown named argument for this candidate
                    break;
                }
                int pos = targetParam.get().getPosition();
                if (isAssigned[pos])
                {
                    isViable = false; // duplicate assignment to same param
                    break;
                }
                assignedArgs[pos] = entry.getValue();
                isAssigned[pos] = true;
            }
            if (!isViable)
            {
                continue;
            }

            // Check required parameters have assignments (or defaults)
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

    /**
     * Old API kept: resolveOverload. Now it relies on findViableMethods and
     * performs ambiguity detection (different return types -> ambiguous).
     */
    public Optional<MethodSymbol> resolveOverload(
            String name,
            List<NebulaParser.ExpressionContext> positionalArgs,
            Map<String, NebulaParser.ExpressionContext> namedArgs)
    {
        List<MethodSymbol> viableCandidates = findViableMethods(name, positionalArgs, namedArgs);

        if (viableCandidates.isEmpty())
        {
            return Optional.empty();
        }
        if (viableCandidates.size() == 1)
        {
            return Optional.of(viableCandidates.get(0));
        }

        // Multiple viable candidates: if they all have identical return type, pick the first;
        // otherwise it's a true ambiguity (differing return types) and we signal that by returning empty (and emit error).
        Type firstType = viableCandidates.get(0).getType();
        boolean allSameReturn = true;
        for (MethodSymbol m : viableCandidates)
        {
            if (!m.getType().equals(firstType))
            {
                allSameReturn = false;
                break;
            }
        }

        if (allSameReturn)
        {
            return Optional.of(viableCandidates.get(0));
        }

        // Ambiguity: multiple viable overloads with different return types
        System.err.println("[Semantic Error] Ambiguous method call: multiple overloads of '"
                + name + "' match this argument list.");
        return Optional.empty();
    }

    public Optional<MethodSymbol> resolveMethodByReturnType(String name, Type expectedReturnType) {
        List<MethodSymbol> methods = methodsByName.getOrDefault(name, new ArrayList<>());
        for (MethodSymbol method : methods) {
            if (method.getType().equals(expectedReturnType)) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    // Find a method by name, parameter types, and return type (for overloads differing only by return type)
    public Optional<MethodSymbol> resolveMethodBySignature(String name, List<Type> paramTypes, Type returnType) {
        List<MethodSymbol> candidates = methodsByName.getOrDefault(name, new ArrayList<>());

        for (MethodSymbol m : candidates) {
            // Match by parameter types
            List<Type> mParams = m.getParameterTypes();
            if (mParams.size() != paramTypes.size()) continue;

            boolean paramsMatch = true;
            for (int i = 0; i < mParams.size(); i++) {
                if (!mParams.get(i).equals(paramTypes.get(i))) {
                    paramsMatch = false;
                    break;
                }
            }

            // Also match return type (important for your case)
            if (paramsMatch && m.getType().equals(returnType)) {
                return Optional.of(m);
            }
        }

        return Optional.empty();
    }

    // Added this getter
    public Map<String, List<MethodSymbol>> getMethodsByName() {
        return methodsByName;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public ClassType getType() {
        return type;
    }

    public boolean isPublic() {
        return isPublic;
    }
}