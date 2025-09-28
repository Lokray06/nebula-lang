// File: src/main/java/org/lokray/semantic/TypeSymbol.java
package org.lokray.semantic;

import org.lokray.semantic.type.Type;

/**
 * Represents a type name in the code (like 'int' or 'string') and links it to a canonical Type object.
 */
public class TypeSymbol implements Symbol
{
	private final String name; // The name used in code, e.g., "int"
	private final Type resolvedType; // The actual Type object, e.g., PrimitiveType.INT

	public TypeSymbol(String name, Type resolvedType)
	{
		this.name = name;
		this.resolvedType = resolvedType;
	}

	@Override
	public String getName()
	{
		return name;
	}

	/**
	 * Gets the canonical Type object that this symbol represents.
	 *
	 * @return The resolved Type.
	 */
	@Override
	public Type getType()
	{
		return resolvedType;
	}
}