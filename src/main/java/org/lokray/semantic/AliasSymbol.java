// File: java/org/lokray/semantic/AliasSymbol.java
package org.lokray.semantic;

/**
 * Represents a symbol that is an alias for another symbol.
 * Used for both 'import' and 'alias' declarations.
 */
public class AliasSymbol implements Symbol
{
	private final String name;
	private final Symbol targetSymbol;

	public AliasSymbol(String name, Symbol targetSymbol)
	{
		this.name = name;
		this.targetSymbol = targetSymbol;
	}

	@Override
	public String getName()
	{
		return name;
	}

	public Symbol getTargetSymbol()
	{
		return targetSymbol;
	}
}