package org.lokray.semantic;

/**
 * Represents a named scope that acts like a package or namespace.
 * It is both a Symbol (it has a name) and a Scope (it contains other symbols).
 */
public class NamespaceSymbol extends Scope implements Symbol
{
	private final String name;

	public NamespaceSymbol(String name, Scope enclosingScope)
	{
		super(enclosingScope);
		this.name = name;
	}

	@Override
	public String getName()
	{
		return name;
	}
}