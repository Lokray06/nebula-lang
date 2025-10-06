// File: src/main/java/org/lokray/semantic/MethodSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.semantic.type.Type;

import java.util.List;

public class MethodSymbol extends Scope implements Symbol
{
	private final String name;
	private Type returnType; // FIX: Made non-final
	private List<Type> parameterTypes; // FIX: Made non-final
	private final boolean isStatic;
	private final boolean isPublic;
	private final boolean isConstructor;

	public MethodSymbol(String name, Type returnType, List<Type> parameterTypes, Scope enclosingScope, boolean isStatic, boolean isPublic, boolean isConstructor)
	{
		super(enclosingScope);
		this.name = name;
		this.returnType = returnType;
		this.parameterTypes = parameterTypes;
		this.isStatic = isStatic;
		this.isPublic = isPublic;
		this.isConstructor = isConstructor;
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public Type getType()
	{
		return returnType;
	}

	public List<Type> getParameterTypes()
	{
		return parameterTypes;
	}

	// FIX: Added setter
	public void setReturnType(Type returnType)
	{
		this.returnType = returnType;
	}

	// FIX: Added setter
	public void setParameterTypes(List<Type> parameterTypes)
	{
		this.parameterTypes = parameterTypes;
	}

	public boolean isStatic()
	{
		return isStatic;
	}

	public boolean isPublic()
	{
		return isPublic;
	}

	public boolean isConstructor()
	{
		return isConstructor;
	}
}