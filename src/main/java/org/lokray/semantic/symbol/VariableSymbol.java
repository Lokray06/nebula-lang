// File: src/main/java/org/lokray/semantic/VariableSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.semantic.type.Type;

public class VariableSymbol implements Symbol
{
	private final String name;
	private Type type;
	private final boolean isStatic;
	private final boolean isPublic;
	private final boolean isConst;
	private final boolean isNative;

	public VariableSymbol(String name, Type type, boolean isStatic, boolean isPublic, boolean isConst, boolean isNative)
	{
		this.name = name;
		this.type = type;
		this.isStatic = isStatic;
		this.isPublic = isPublic;
		this.isConst = isConst;
		this.isNative = isNative;
	}

	public VariableSymbol(String name, Type type, boolean isStatic, boolean isPublic, boolean isConst)
	{
		this(name, type, isStatic, isPublic, isConst, false);
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public Type getType()
	{
		return type;
	}

	public void setType(Type type)
	{
		this.type = type;
	}

	public boolean isStatic()
	{
		return isStatic;
	}

	public boolean isPublic()
	{
		return isPublic;
	}

	public boolean isConst()
	{
		return isConst;
	}
	public boolean isNative()
	{
		return isNative;
	}
}