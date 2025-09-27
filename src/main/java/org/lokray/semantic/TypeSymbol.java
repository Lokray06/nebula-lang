package org.lokray.semantic;

/**
 * Represents a built-in type like 'int' or 'string'.
 */
public class TypeSymbol implements Symbol
{
	private final String name;

	public TypeSymbol(String name)
	{
		this.name = name;
	}

	@Override
	public String getName()
	{
		return name;
	}
}