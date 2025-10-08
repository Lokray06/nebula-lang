// File: src/main/java/org/lokray/semantic/symbol/NamespaceSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.semantic.type.NamespaceType; // NEW
import org.lokray.semantic.type.Type;          // NEW

public class NamespaceSymbol extends Scope implements Symbol
{
	private final String name;
	private final NamespaceType type; // NEW

	public NamespaceSymbol(String name, Scope enclosingScope)
	{
		super(enclosingScope);
		this.name = name;
		this.type = new NamespaceType(this); // NEW
	}

	@Override
	public String getName()
	{
		return name;
	}

	// NEW: getType() implementation
	@Override
	public Type getType()
	{
		return type;
	}

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