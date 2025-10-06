// File: src/main/java/org/lokray/semantic/type/Type.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.Symbol;

public interface Type extends Symbol
{
	@Override
	String getName();

	boolean isAssignableTo(Type other);

	default boolean isNumeric()
	{
		return false;
	}

	default boolean isInteger()
	{
		return false;
	}

	default boolean isTuple()
	{
		return false;
	}

	default boolean isReferenceType()
	{
		return false;
	}

	default boolean isArray()
	{
		return false;
	}
}