// File: src/main/java/org/lokray/semantic/type/NamespaceType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.NamespaceSymbol;

public class NamespaceType implements Type
{
	private final NamespaceSymbol namespaceSymbol;

	public NamespaceType(NamespaceSymbol namespaceSymbol)
	{
		this.namespaceSymbol = namespaceSymbol;
	}

	public NamespaceSymbol getNamespaceSymbol()
	{
		return namespaceSymbol;
	}

	@Override
	public String getName()
	{
		return namespaceSymbol.getFqn();
	}

	@Override
	public Type getType()
	{
		return this;
	}

	@Override
	public boolean isAssignableTo(Type other)
	{
		// Namespaces cannot be assigned.
		return false;
	}
}