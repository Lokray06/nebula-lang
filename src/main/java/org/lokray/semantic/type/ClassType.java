// File: src/main/java/org/lokray/semantic/type/ClassType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.ClassSymbol;

public class ClassType implements Type
{
	private final ClassSymbol classSymbol;

	public ClassType(ClassSymbol classSymbol)
	{
		this.classSymbol = classSymbol;
	}

	public ClassSymbol getClassSymbol()
	{
		return classSymbol;
	}

	@Override
	public String getName()
	{
		return classSymbol.getName();
	}

	@Override
	public Type getType()
	{
		return this;
	}

	@Override
	public boolean isAssignableTo(Type other)
	{
		if (this.equals(other) || other instanceof NullType)
		{
			return true;
		}
		// Add inheritance checks here in the future
		return false;
	}

	@Override
	public boolean isReferenceType()
	{
		return true;
	}

	@Override
	public boolean equals(Object obj)
	{
        if (this == obj)
        {
            return true;
        }
        if (obj == null || getClass() != obj.getClass())
        {
            return false;
        }
		ClassType classType = (ClassType) obj;
		return classSymbol.equals(classType.classSymbol);
	}

	@Override
	public int hashCode()
	{
		return classSymbol.hashCode();
	}
}