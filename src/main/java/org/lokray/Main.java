package org.lokray;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.Trees;
import org.lokray.ndk.NdkCompiler;
import org.lokray.ndk.dto.LibraryDTO;
import org.lokray.parser.NebulaLexer;
import org.lokray.parser.NebulaParser;
import org.lokray.semantic.SemanticAnalyzer;
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
			NebulaCompilerArguments arguments = NebulaCompilerArguments.parse(args);
			Path ndkBuild = arguments.getBuildNdkPath();
			Path ndkOut = arguments.getNdkOutPath();
			Path useNdk = arguments.getUseNdkPath();
			Path filePath = arguments.getFilePath();

			if (ndkBuild != null)
			{
				NdkCompiler nc = new NdkCompiler(ndkBuild);
				LibraryDTO lib = nc.buildLibrary();
				Path out = ndkOut != null ? ndkOut : ndkBuild.resolveSibling("ndk.neblib");
				nc.writeLibrary(lib, out);
				Path stub = out.getParent().resolve("nebula_runtime_stubs.c");
				nc.emitRuntimeStub(lib, stub);
				if (filePath == null)
				{
					return;
				}
			}

			if (filePath == null)
			{
				Debug.logError("No file provided to compile.");
				return;
			}
			if (!Files.exists(filePath))
			{
				Debug.logError("The specified file does not exist: " + filePath);
				return;
			}
			arguments.validateFile();

			CharStream input = CharStreams.fromFileName(filePath.toString());
			NebulaLexer lexer = new NebulaLexer(input);
			CommonTokenStream tokens = new CommonTokenStream(lexer);
			NebulaParser parser = new NebulaParser(tokens);
			ParseTree tree = parser.compilationUnit();
			Debug.logDebug("Parse Tree:\n" + Trees.toStringTree(tree, parser));

			if (parser.getNumberOfSyntaxErrors() > 0)
			{
				Debug.logError("Compilation failed due to syntax errors.");
				return;
			}

			SemanticAnalyzer analyzer = (useNdk != null) ? new SemanticAnalyzer(useNdk) : new SemanticAnalyzer();
			boolean success = analyzer.analyze(tree);

			if (!success)
			{
				Debug.logError("Compilation failed due to semantic errors.");
				return;
			}

			Debug.logInfo("Semantic analysis passed successfully!");
			Debug.logInfo("Next step would be Code Generation.");
		}
		catch (IllegalArgumentException e)
		{
			Debug.logError("Compiler initialization failed. Reason: " + e.getMessage());
		}
		catch (IOException e)
		{
			Debug.logError("Error reading file: " + e.getMessage());
		}
		catch (Exception e)
		{
			Debug.logError("Unexpected error: " + e.getMessage());
			e.printStackTrace();
		}
	}
}
