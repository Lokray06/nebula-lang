package org.lokray.util;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

/**
 * A custom error listener for the ANTLR parser to route syntax errors
 * through the Debug.logError system.
 */
public class SyntaxErrorListener extends BaseErrorListener
{

	@Override
	public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e)
	{
		String err = String.format("[Syntax Error] line %d:%d - %s", line, charPositionInLine + 1, msg);
		Debug.logError(err);
	}
}
