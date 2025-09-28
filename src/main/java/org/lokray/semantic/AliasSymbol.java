// File: src/main/java/org/lokray/semantic/AliasSymbol.java
package org.lokray.semantic;

import org.lokray.semantic.type.Type;

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

	// CHANGE: Added required getType() method
	@Override
	public Type getType()
	{
		// The type of an alias is the type of its target.
		return targetSymbol.getType();
	}
}