// File: src/main/java/org/lokray/semantic/type/ErrorType.java
package org.lokray.semantic.type;

public class ErrorType implements Type
{
	public static final ErrorType INSTANCE = new ErrorType();

	private ErrorType()
	{
	}

	@Override
	public String getName()
	{
		return "<error>";
	}

	@Override
	public Type getType()
	{
		return this;
	}

	@Override
	public boolean isAssignableTo(Type other)
	{
		return true; // Avoid cascading errors
	}
}