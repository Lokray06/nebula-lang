// File: src/main/java/org/lokray/util/ErrorHandler.java
package org.lokray.util;

import org.antlr.v4.runtime.Token;
import org.lokray.semantic.symbol.ClassSymbol;

public class ErrorHandler
{
	private boolean hasErrors = false;

	public void logError(Token token, String msg, ClassSymbol currentClass)
	{
		String className = currentClass != null ? currentClass.getName() : "";
		String err = String.format("[Semantic Error] %s - line %d:%d - %s",
				className, token.getLine(), token.getCharPositionInLine() + 1, msg);
		Debug.logError(err);
		hasErrors = true;
	}

	public boolean hasErrors()
	{
		return hasErrors;
	}
}