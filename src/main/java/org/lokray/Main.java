package org.lokray;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.Trees;
import org.lokray.codegen.CodeGenerator;
import org.lokray.ndk.NdkCompiler;
import org.lokray.ndk.dto.LibraryDTO;
import org.lokray.parser.NebulaLexer;
import org.lokray.parser.NebulaParser;
import org.lokray.semantic.SemanticAnalyzer;
import org.lokray.util.Debug;
import org.lokray.util.ErrorHandler;
import org.lokray.util.NebulaCompilerArguments;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.lokray.util.ProcessUtils.executeCommand;

public class Main
{
	public static void main(String[] args)
	{
		try
		{
			ErrorHandler errorHandler = new ErrorHandler();
			NebulaCompilerArguments arguments = NebulaCompilerArguments.parse(args);
			Path ndkBuild = arguments.getBuildNdkPath();
			Path ndkOut = arguments.getNdkOutPath();
			Path useNdk = arguments.getUseNdkPath();
			Path filePath = arguments.getFilePath();

			if (ndkBuild != null)
			{
				NdkCompiler nc = new NdkCompiler(ndkBuild, errorHandler);
				LibraryDTO lib = nc.buildLibrary();
				Path out = ndkOut != null ? ndkOut : ndkBuild.resolveSibling(ndkBuild.getFileName().toString() + ".neblib");
				nc.writeLibrary(lib, out);
				// No longer need to emit stubs, as we are building a real library
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
			Debug.logDebug("\nParsing...");
			NebulaParser parser = new NebulaParser(tokens);
			ParseTree tree = parser.compilationUnit();
			if (Debug.ENABLE_DEBUG)
			{
				Debug.logDebug("Parse Tree:\n" + Trees.toStringTree(tree, parser));
			}

			if (parser.getNumberOfSyntaxErrors() > 0)
			{
				Debug.logError("Compilation failed due to syntax errors.");
				return;
			}

			Debug.logDebug("\nSemantic analysis:");
			SemanticAnalyzer analyzer = (useNdk != null) ? new SemanticAnalyzer(useNdk, errorHandler) : new SemanticAnalyzer(errorHandler);
			boolean success = analyzer.analyze(tree);

			if (!success)
			{
				Debug.logError("Compilation failed due to semantic errors.");
				return;
			}

			Debug.logInfo("Semantic analysis passed successfully!");
			Debug.logInfo("Generating LLVM IR...");

			Path llvmIrPath = filePath.resolveSibling(filePath.getFileName().toString().replaceFirst("[.][^.]+$", ".ll"));
			CodeGenerator codeGenerator = new CodeGenerator(tree, analyzer, llvmIrPath);
			codeGenerator.generate();

			Debug.logInfo("Compiling and linking...");
			compileAndLink(llvmIrPath, useNdk);

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

	private static void compileAndLink(Path llvmIrPath, Path useNdkPath) throws IOException, InterruptedException
	{
		String baseName = llvmIrPath.getFileName().toString().replaceFirst("[.][^.]+$", "");
		Path parentDir = llvmIrPath.getParent();
		Path objectFile = parentDir.resolve(baseName + ".o");
		Path executableFile = parentDir.resolve(baseName);

		// --- Step 1: Compile LLVM IR to an object file using clang ---
		Debug.logDebug("Compiling IR: clang -c " + llvmIrPath + " -o " + objectFile);
		ProcessBuilder compileIr = new ProcessBuilder("clang", "-c", llvmIrPath.toString(), "-o", objectFile.toString());
		executeCommand(compileIr);

		// --- Step 2: Link the object file with the static library ---
		List<String> linkCommand = new ArrayList<>();
		linkCommand.add("clang++");
		linkCommand.add(objectFile.toString());

		if (useNdkPath != null)
		{
			// Infer static library path from the .neblib path
			String libName = useNdkPath.getFileName().toString().replace(".neblib", "");
			Path libPath = useNdkPath.resolveSibling("lib" + libName + ".a");

			if (!Files.exists(libPath))
			{
				Debug.logError("Static library not found at inferred path: " + libPath);
				return;
			}
			// Add the static library to the linker command
			linkCommand.add(libPath.toAbsolutePath().toString());
		}

		linkCommand.add("-o");
		linkCommand.add(executableFile.toString());

		Debug.logDebug("Linking: " + String.join(" ", linkCommand));
		ProcessBuilder link = new ProcessBuilder(linkCommand);
		executeCommand(link);

		Debug.logInfo("Compilation successful! Executable created at: " + executableFile);
	}
}