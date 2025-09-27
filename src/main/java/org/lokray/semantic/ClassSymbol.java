package org.lokray.semantic;

import java.util.ArrayList;
import java.util.List;

public class ClassSymbol extends Scope implements Symbol
{
	private final String name;
	private final Scope enclosingScope;
	private final List<String> modifiers = new ArrayList<>();

	public ClassSymbol(String name, Scope enclosingScope)
	{
		super(enclosingScope);
		this.name = name;
		this.enclosingScope = enclosingScope;
	}

	public void addModifier(String mod)
	{
		modifiers.add(mod);
	}

	public List<String> getModifiers()
	{
		return modifiers;
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public Scope getEnclosingScope()
	{
		return enclosingScope;
	}
}
