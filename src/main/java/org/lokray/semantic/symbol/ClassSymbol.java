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

    // NEW: The advanced overload resolution method
    public Optional<MethodSymbol> resolveOverload(
            String name,
            List<NebulaParser.ExpressionContext> positionalArgs,
            Map<String, NebulaParser.ExpressionContext> namedArgs) {

        List<MethodSymbol> candidates = methodsByName.getOrDefault(name, new ArrayList<>());
        List<MethodSymbol> viableCandidates = new ArrayList<>();

        for (MethodSymbol candidate : candidates) {
            List<ParameterSymbol> params = candidate.getParameters();
            NebulaParser.ExpressionContext[] assignedArgs = new NebulaParser.ExpressionContext[params.size()];
            boolean[] isAssigned = new boolean[params.size()];
            boolean isViable = true;

            // 1. Match positional arguments
            if (positionalArgs.size() > params.size()) {
                continue; // Too many positional args
            }
            for (int i = 0; i < positionalArgs.size(); i++) {
                assignedArgs[i] = positionalArgs.get(i);
                isAssigned[i] = true;
            }

            // 2. Match named arguments
            for (Map.Entry<String, NebulaParser.ExpressionContext> entry : namedArgs.entrySet()) {
                String argName = entry.getKey();
                Optional<ParameterSymbol> targetParam = params.stream()
                        .filter(p -> p.getName().equals(argName))
                        .findFirst();

                if (targetParam.isEmpty()) {
                    isViable = false; // Parameter name does not exist in this overload
                    break;
                }
                int pos = targetParam.get().getPosition();
                if (isAssigned[pos]) {
                    isViable = false; // Argument for this parameter was already provided positionally
                    break;
                }
                assignedArgs[pos] = entry.getValue();
                isAssigned[pos] = true;
            }
            if (!isViable) {
                continue;
            }

            // 3. Check if all non-default parameters have been assigned
            for (int i = 0; i < params.size(); i++) {
                if (!isAssigned[i] && !params.get(i).hasDefaultValue()) {
                    isViable = false; // A required parameter was not provided
                    break;
                }
            }

            if (isViable) {
                viableCandidates.add(candidate);
            }
        }

        // 4. Select the best candidate (for now, just the first viable one)
        // A more advanced system could score candidates (e.g., fewer default values used is better)
        if (viableCandidates.isEmpty()) {
            return Optional.empty();
        } else {
            // TODO: Add ambiguity check if viableCandidates.size() > 1
            return Optional.of(viableCandidates.get(0));
        }
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