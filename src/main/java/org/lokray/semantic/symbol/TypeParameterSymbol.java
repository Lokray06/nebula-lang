// File: src/main/java/org/lokray/semantic/symbol/TypeParameterSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.semantic.type.Type;
import org.lokray.semantic.type.TypeParameterType;

/**
 * Represents a type parameter variable (e.g., 'T' in class List<T>).
 * It is a symbol that is resolvable within its declaring scope (e.g., the class).
 */
public class TypeParameterSymbol implements Symbol
{
	private final String name;
	private final Type type;
	// TODO: Add List<Type> constraints (e.g., where T : ISomething)

	public TypeParameterSymbol(String name)
	{
		this.name = name;
		this.type = new TypeParameterType(this);
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
}