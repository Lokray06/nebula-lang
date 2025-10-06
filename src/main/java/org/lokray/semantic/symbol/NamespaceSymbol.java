// File: src/main/java/org/lokray/semantic/NamespaceSymbol.java
package org.lokray.semantic.symbol;

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

	// FIX: Added this method
	public String getFqn()
	{
		if (getEnclosingScope() instanceof NamespaceSymbol)
		{
			String parentFqn = ((NamespaceSymbol) getEnclosingScope()).getFqn();
			return parentFqn.isEmpty() ? name : parentFqn + "." + name;
		}
		return name; // Top-level namespace
	}
}