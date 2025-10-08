// File: src/main/java/org/lokray/semantic/symbol/ParameterSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.parser.NebulaParser;
import org.lokray.semantic.type.Type;

import java.util.Optional;

public class ParameterSymbol extends VariableSymbol
{

	private final int position;
	private final Optional<NebulaParser.ExpressionContext> defaultValueCtx;

	public ParameterSymbol(String name, Type type, int position, NebulaParser.ExpressionContext defaultValueCtx)
	{
		// Parameters are non-static, non-const, and effectively public within the method.
		// They are final in the sense that they cannot be reassigned.
		super(name, type, false, true, true);
		this.position = position;
		this.defaultValueCtx = Optional.ofNullable(defaultValueCtx);
	}

	public int getPosition()
	{
		return position;
	}

	public boolean hasDefaultValue()
	{
		return defaultValueCtx.isPresent();
	}

	public Optional<NebulaParser.ExpressionContext> getDefaultValueCtx()
	{
		return defaultValueCtx;
	}
}