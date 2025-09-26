package org.lokray;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.parser.NebulaLexer;
import org.lokray.parser.NebulaParser;
import org.lokray.util.Debug;
import org.lokray.util.NebulaCompilerArguments;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Main
{
	public static void main(String[] args)
	{
		try
		{
			// 1. Orchestrate argument parsing and validation
			NebulaCompilerArguments nebulaCompilerArguments = NebulaCompilerArguments.parse(args);

			// 2. Validate file existence and extension
			Path filePath = nebulaCompilerArguments.getFilePath();
			if (!Files.exists(filePath))
			{
				Debug.logError("The specified file does not exist: " + filePath);
				return;
			}
			nebulaCompilerArguments.validateFile();
			Debug.logInfo("Successfully validated file: " + filePath);

			// 3. Create a CharStream from the input file
			CharStream input = CharStreams.fromFileName(filePath.toString());

			// 4. Create a lexer that feeds off of the input CharStream
			NebulaLexer lexer = new NebulaLexer(input);

			// 5. Create a buffer of tokens pulled from the lexer
			CommonTokenStream tokens = new CommonTokenStream(lexer);

			// 6. Create a parser that feeds off the tokens buffer
			NebulaParser parser = new NebulaParser(tokens);

			// 7. Begin parsing at the 'compilationUnit' rule
			ParseTree tree = parser.compilationUnit();

			// 8. Print the LISP-style parse tree for debugging
			Debug.log("\n--- Parse Tree ---");
			Debug.log(tree.toStringTree(parser));
			Debug.log("--- End Parse Tree ---\n");

			// 9. Create a visitor to walk the parse tree and build the AST (or perform analysis)
			Debug.logInfo("--- Walking Tree with Visitor ---");
			AstBuilderVisitor visitor = new AstBuilderVisitor();
			visitor.visit(tree);
			Debug.logInfo("--- Visitor Finished ---");

		}
		catch (IllegalArgumentException e)
		{
			// Catches exceptions from argument parsing and validation
			Debug.logError("Compiler initialization failed. Reason: " + e.getMessage());
		}
		catch (IOException e)
		{
			// Catches exceptions from file loading or ANTLR CharStream creation
			Debug.logError("Error reading file.");
			Debug.logError("Reason: " + e.getMessage());
		}
		catch (Exception e)
		{
			// Catches any other unexpected exceptions
			Debug.logError("An unexpected error occurred: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
