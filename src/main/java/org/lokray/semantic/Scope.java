package org.lokray.semantic;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * Represents a scope (like a namespace, class, or method body) in the program.
 * It manages symbols defined within it.
 */
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

	/**
	 * Resolves a symbol by name, searching up through enclosing scopes.
	 * CRITICAL: Automatically dereferences AliasSymbols to find the final target symbol.
	 */
	public Optional<Symbol> resolve(String name)
	{
		Optional<Symbol> local = resolveLocally(name);
		if (local.isPresent())
		{
			Symbol sym = local.get();
			// Keep following the alias until we find the real symbol
			while (sym instanceof AliasSymbol alias)
			{
				sym = alias.getTargetSymbol();
			}
			return Optional.of(sym);
		}

		if (enclosingScope != null)
		{
			// Search the parent scope
			return enclosingScope.resolve(name);
		}
		return Optional.empty();
	}

	/**
	 * Resolves a symbol only within the current scope, without searching parents or dereferencing aliases.
	 */
	public Optional<Symbol> resolveLocally(String name)
	{
		return Optional.ofNullable(symbols.get(name));
	}

	/**
	 * Resolves a qualified path (e.g., "nebula.io.Console" or "Console.println") starting from this scope.
	 * It performs a deep, local-only search through scopes/namespaces.
	 */
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
				// If the first part is not found locally, we should check the enclosing scopes (Global/Namespace)
				if (i == 0 && enclosingScope != null)
				{
					return enclosingScope.resolvePath(qualifiedName);
				}
				return Optional.empty();
			}

			foundSymbol = next.get();

			// CRITICAL FIX: Dereference the alias to get the actual Class/Namespace symbol
			// This is required when resolving a path like "Console.println" where "Console"
			// is an imported AliasSymbol.
			while (foundSymbol instanceof AliasSymbol alias)
			{
				foundSymbol = alias.getTargetSymbol();
			}

			if (foundSymbol instanceof Scope)
			{
				currentScopeForResolve = (Scope) foundSymbol;
			}
			else if (i < parts.length - 1)
			{
				// We found a non-scope symbol (like a variable or method) but there are more parts.
				// This means the path traversal must stop.
				return Optional.empty();
			}
		}
		return Optional.ofNullable(foundSymbol);
	}

	@Override
	public String getName()
	{
		return "<scope>";
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
