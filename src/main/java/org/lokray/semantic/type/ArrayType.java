// File: src/main/java/org/lokray/semantic/type/ArrayType.java
package org.lokray.semantic.type;

public class ArrayType implements Type
{
	private final Type elementType;

	public ArrayType(Type elementType)
	{
		this.elementType = elementType;
	}

	public Type getElementType()
	{
		return elementType;
	}

	@Override
	public String getName()
	{
		return elementType.getName() + "[]";
	}

	@Override
	public Type getType()
	{
		return this;
	}

	@Override
	public boolean isAssignableTo(Type other)
	{
        if (other instanceof NullType)
        {
            return true;
        }
		if (other instanceof ArrayType)
		{
			// Arrays are covariant in some languages, invariant in others. Let's assume invariance for now.
			return this.elementType.equals(((ArrayType) other).getElementType());
		}
		return false;
	}

	@Override
	public boolean isReferenceType()
	{
		return true;
	}

	@Override
	public boolean isArray()
	{
		return true;
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
		ArrayType arrayType = (ArrayType) obj;
		return elementType.equals(arrayType.elementType);
	}

	@Override
	public int hashCode()
	{
		return elementType.hashCode();
	}
}