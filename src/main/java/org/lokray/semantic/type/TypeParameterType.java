// File: src/main/java/org/lokray/semantic/type/TypeParameterType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.TypeParameterSymbol;

import java.util.Objects;

/**
 * Represents the "type" of a type parameter (e.g., T).
 * This allows 'T' to be used as a type for fields, parameters, and return values
 * within the generic class body.
 */
public class TypeParameterType implements Type
{
	private final TypeParameterSymbol symbol;

	public TypeParameterType(TypeParameterSymbol symbol)
	{
		this.symbol = symbol;
	}

	public TypeParameterSymbol getSymbol()
	{
		return symbol;
	}

	@Override
	public String getName()
	{
		return symbol.getName();
	}

	@Override
	public Type getType()
	{
		return this;
	}

	/**
	 * A type parameter is only assignable to itself or to 'Object'.
	 * Concrete assignability is determined after substitution.
	 */
	@Override
	public boolean isAssignableTo(Type other)
	{
		if (this.equals(other))
		{
			return true;
		}
		// TODO: Add constraint checking (e.g., if T : U, then T is assignable to U)

		// Allow assigning to Object (assuming Object is a known base type)
		if (other.isReferenceType() && other.getName().equals("Object"))
		{
			return true;
		}
		return false;
	}

	@Override
	public boolean equals(Object obj)
	{
        if (this == obj)
        {
            return true;
        }
        if (obj == null || getClass() != obj.getClass())
        {
            return false;
        }
		TypeParameterType that = (TypeParameterType) obj;
		// Two TypeParameterTypes are equal if they refer to the *exact same symbol*
		return symbol.equals(that.symbol);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(symbol);
	}
}