// File: src/main/java/org/lokray/semantic/Symbol.java
package org.lokray.semantic.symbol;

import org.lokray.semantic.type.Type;

public interface Symbol
{
	String getName();

	Type getType();
}