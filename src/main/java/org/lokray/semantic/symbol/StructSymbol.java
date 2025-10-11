// File: src/main/java/org/lokray/semantic/symbol/StructSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.semantic.type.StructType;
import org.lokray.semantic.type.Type;

public class StructSymbol extends ClassSymbol
{

	private final StructType type;

	public StructSymbol(String name, Scope enclosingScope, boolean isPublic, boolean isNative)
	{
		// Structs are never native, so we pass 'false' for isNative.
		super(name, enclosingScope, isNative, isPublic);
		this.type = new StructType(this);
	}

	/**
	 * Overridden to return the specific StructType.
	 *
	 * @return The StructType associated with this symbol.
	 */
	@Override
	public Type getType()
	{
		return this.type;
	}
}