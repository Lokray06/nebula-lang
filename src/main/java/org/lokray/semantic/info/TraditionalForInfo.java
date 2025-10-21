package org.lokray.semantic.info;

import org.antlr.v4.runtime.tree.ParseTree;

/**
 * Holds analyzed information about a traditional C-style for loop.
 * Fields can be null if the corresponding part is omitted in the source.
 */
public record TraditionalForInfo(
		ParseTree initializer,   // Can be VariableDeclarationContext or ExpressionContext
		ParseTree condition,     // ExpressionContext
		ParseTree update         // ExpressionContext
)
{
}