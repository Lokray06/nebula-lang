package org.lokray.semantic;

/**
 * Represents a variable or a field in the symbol table.
 */
public class VariableSymbol implements Symbol
{
	private final String name;
	private final String type; // For now, we'll store type as a string.

	public VariableSymbol(String name, String type)
	{
		this.name = name;
		this.type = type;
	}

	@Override
	public String getName()
	{
		return name;
	}

	public String getType()
	{
		return type;
	}
}
