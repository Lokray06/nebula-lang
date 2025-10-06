// File: src/main/java/org/lokray/semantic/Scope.java
package org.lokray.semantic.symbol;

import org.lokray.semantic.type.Type;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

public class Scope implements Symbol
{
	private final Scope enclosingScope;
	private final Map<String, Symbol> symbols = new HashMap<>();

	public Scope(Scope enclosingScope)
	{
		this.enclosingScope = enclosingScope;
	}

	public void define(Symbol sym)
	{
		symbols.put(sym.getName(), sym);
	}

	public Optional<Symbol> resolve(String name)
	{
		Optional<Symbol> local = resolveLocally(name);
		if (local.isPresent())
		{
			Symbol sym = local.get();
			if (sym instanceof AliasSymbol)
			{ // Aliases are resolved at use-site
				return local;
			}
			return local;
		}
		if (enclosingScope != null)
		{
			return enclosingScope.resolve(name);
		}
		return Optional.empty();
	}

	public Optional<Symbol> resolveLocally(String name)
	{
		return Optional.ofNullable(symbols.get(name));
	}

	// Added these three methods for alias handling and symbol access
	public boolean isAlias(String name)
	{
		return resolveLocally(name).map(s -> s instanceof AliasSymbol).orElse(false);
	}

	public Symbol resolveAlias(String name)
	{
		Symbol symbol = resolveLocally(name).orElse(null);
		if (symbol instanceof AliasSymbol)
		{
			return ((AliasSymbol) symbol).getTargetSymbol();
		}
		return symbol; // Should not happen if isAlias is checked first
	}

	public Map<String, Symbol> getSymbols()
	{
		return symbols;
	}


	public Optional<Symbol> resolvePath(String qualifiedName)
	{
		String[] parts = qualifiedName.split("\\.");
		Scope currentScopeForResolve = this;
		Symbol foundSymbol = null;
		for (int i = 0; i < parts.length; i++)
		{
			String part = parts[i];
			if (currentScopeForResolve == null)
			{
				return Optional.empty();
			}

			Optional<Symbol> next = currentScopeForResolve.resolveLocally(part);

			if (next.isEmpty())
			{
				if (i == 0 && enclosingScope != null)
				{
					return enclosingScope.resolvePath(qualifiedName);
				}
				return Optional.empty();
			}

			foundSymbol = next.get();
			if (foundSymbol instanceof AliasSymbol alias)
			{
				foundSymbol = alias.getTargetSymbol();
			}

			if (foundSymbol instanceof Scope)
			{
				currentScopeForResolve = (Scope) foundSymbol;
			}
			else if (i < parts.length - 1)
			{
				return Optional.empty(); // Path continues but symbol is not a scope
			}
		}
		return Optional.ofNullable(foundSymbol);
	}

	@Override
	public String getName()
	{
		// Scope name is context-dependent, often representing a class, method, or namespace
		if (this instanceof ClassSymbol)
		{
			return (this).getName();
		}
		if (this instanceof NamespaceSymbol)
		{
			return (this).getName();
		}
		return "<scope>";
	}

	@Override
	public Type getType()
	{
		return null;
	}

	public Scope getEnclosingScope()
	{
		return enclosingScope;
	}

	public void forEachSymbol(BiConsumer<String, Symbol> visitor)
	{
		symbols.forEach(visitor);
	}
}