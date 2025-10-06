// File: src/main/java/org/lokray/semantic/ClassSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.semantic.type.ClassType;
import org.lokray.semantic.type.Type;

import java.util.*;

public class ClassSymbol extends Scope implements Symbol
{
	private final String name;
	private final ClassType type;
	private final Map<String, List<MethodSymbol>> methodsByName = new HashMap<>();
	private boolean isNative = false;
	private ClassSymbol superClass; // Added superclass field

	public ClassSymbol(String name, Scope enclosingScope)
	{
		super(enclosingScope);
		this.name = name;
		this.type = new ClassType(this);
	}

	// Added setters and getters for modifiers and superclass
	public void setNative(boolean isNative)
	{
		this.isNative = isNative;
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

	public void defineMethod(MethodSymbol ms)
	{
		methodsByName.computeIfAbsent(ms.getName(), k -> new ArrayList<>()).add(ms);
		super.define(ms);
	}

	public List<MethodSymbol> resolveMethods(String name)
	{
		return methodsByName.getOrDefault(name, new ArrayList<>());
	}

	// Added this method for overload resolution
	public Optional<MethodSymbol> resolveMethod(String name, List<Type> argTypes)
	{
		List<MethodSymbol> candidates = resolveMethods(name);
		for (MethodSymbol candidate : candidates)
		{
			if (candidate.getParameterTypes().size() != argTypes.size())
			{
				continue;
			}
			boolean allMatch = true;
			for (int i = 0; i < argTypes.size(); i++)
			{
				if (!argTypes.get(i).isAssignableTo(candidate.getParameterTypes().get(i)))
				{
					allMatch = false;
					break;
				}
			}
			if (allMatch)
			{
				return Optional.of(candidate); // Found a match
			}
		}
		return Optional.empty(); // No suitable overload found
	}

	// Added this getter
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
	public ClassType getType()
	{
		return type;
	}
}