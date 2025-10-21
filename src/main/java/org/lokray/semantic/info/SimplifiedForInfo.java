package org.lokray.semantic.info;

import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.parser.NebulaParser;
import org.lokray.semantic.symbol.VariableSymbol;

/**
 * Holds analyzed information about a simplified for loop.
 */
public record SimplifiedForInfo(
		VariableSymbol loopVariable,
		ParseTree startExpression, // Null if implicit start at 0
		ParseTree limitExpression, // The expression defining the loop limit
		NebulaParser.RelationalOperatorContext operator // The comparison operator (<, <=, >, >=)
		// Implicit step is always +1 for this loop type
)
{
}