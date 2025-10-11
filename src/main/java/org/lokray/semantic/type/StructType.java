// File: src/main/java/org/lokray/semantic/type/StructType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.StructSymbol;

import java.util.Objects;

public class StructType implements Type
{

	private final StructSymbol structSymbol;

	public StructType(StructSymbol structSymbol)
	{
		this.structSymbol = structSymbol;
	}

	public StructSymbol getStructSymbol()
	{
		return structSymbol;
	}

	@Override
	public String getName()
	{
		return structSymbol.getName();
	}

	@Override
	public Type getType()
	{
		return this;
	}

	@Override
	public boolean isAssignableTo(Type other)
	{
		// A struct is only assignable to itself. Unlike classes, they cannot be null.
		return this.equals(other);
	}

	/**
	 * Structs are value types, not reference types.
	 * This prevents assigning 'null' to them.
	 *
	 * @return always false.
	 */
	@Override
	public boolean isReferenceType()
	{
		return false;
	}

	/**
	 * Identifies this type as a struct.
	 *
	 * @return always true.
	 */
	@Override
	public boolean isStruct()
	{
		return true;
	}

	@Override
	public boolean equals(Object o)
	{
        if (this == o)
        {
            return true;
        }
        if (o == null || getClass() != o.getClass())
        {
            return false;
        }
		StructType that = (StructType) o;
		return Objects.equals(structSymbol, that.structSymbol);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(structSymbol);
	}
}