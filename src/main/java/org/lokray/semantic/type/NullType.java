// File: src/main/java/org/lokray/semantic/type/NullType.java
package org.lokray.semantic.type;

public class NullType implements Type
{
	public static final NullType INSTANCE = new NullType();

	private NullType()
	{
	}

	@Override
	public String getName()
	{
		return "null";
	}

	@Override
	public Type getType()
	{
		return this;
	}

	@Override
	public boolean isAssignableTo(Type other)
	{
		return other.isReferenceType(); // null can be assigned to any reference type
	}
}