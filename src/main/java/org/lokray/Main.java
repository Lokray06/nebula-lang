package org.lokray;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.codegen.CodeGenerator;
import org.lokray.ndk.dto.LibraryDTO;
import org.lokray.parser.NebulaLexer;
import org.lokray.parser.NebulaParser;
import org.lokray.semantic.SemanticAnalyzer;
import org.lokray.semantic.SymbolTableBuilder;
import org.lokray.semantic.symbol.AliasSymbol;
import org.lokray.semantic.symbol.ClassSymbol;
import org.lokray.semantic.symbol.Scope;
import org.lokray.util.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.lokray.util.ProcessUtils.executeCommand;

/**
 * Refactored main compiler orchestration class.
 * Handles parsing arguments and delegating to 'buildLibrary' or 'buildExecutable'.
 */
public class Main
{

	public static void main(String[] args)
	{
		try
		{
			CompilerArguments arguments = CompilerArguments.parse(args);

			if (arguments.isHelpFlag())
			{
				CompilerArguments.printUsage();
				return;
			}
			if (arguments.isVersionFlag())
			{
				System.out.println("nebulac (Nebula Compiler) version 0.1.0-alpha");
				// In the future, this could also load version from the standard library's .neblib
				return;
			}

			if (arguments.getInputFiles().isEmpty())
			{
				throw new IllegalArgumentException("No input files provided. Use -h for help.");
			}

			ErrorHandler errorHandler = new ErrorHandler();

			// --- Build Pipeline ---
			if (arguments.isBuildLibrary())
			{
				buildLibrary(arguments, errorHandler);
			}
			else
			{
				buildExecutable(arguments, errorHandler);
			}

		}
		catch (IllegalArgumentException e)
		{
			Debug.logError("Compiler initialization failed: " + e.getMessage());
		}
		catch (IOException e)
		{
			Debug.logError("Error reading file: " + e.getMessage());
		}
		catch (Exception e)
		{
			Debug.logError("An unexpected error occurred: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/**
	 * Compiles the input files into a executable binary.
	 */
	private static void buildExecutable(CompilerArguments args, ErrorHandler errorHandler) throws IOException, InterruptedException
	{
		// For now, we only support single-file compilation.
		// In a project-based build, we would parse the .nebproj file here
		// to find all source files.
		Path inputFile = args.getInputFiles().get(0); // Use first file
		if (!Files.exists(inputFile))
		{
			Debug.logError("The specified file does not exist: " + inputFile);
			return;
		}

		// 1. Semantic Analysis
		Debug.logDebug("\nStarting semantic analysis for executable...");
		// Pass library paths from arguments to the analyzer
		SemanticAnalyzer analyzer = new SemanticAnalyzer(args.getLibraryFiles(), args.getLibrarySearchPaths(), errorHandler);
		ParseTree tree = parseFile(inputFile);

		if (tree == null)
		{ // parseFile handles syntax error logging
			return;
		}

		boolean success = analyzer.analyze(tree);
		if (!success || errorHandler.hasErrors())
		{
			Debug.logError("Compilation failed due to semantic errors.");
			return;
		}

		// 2. Check Only?
		if (args.isCheckOnly())
		{
			Debug.logInfo("Semantic check passed. No output generated as -k was specified.");
			return;
		}

		// 3. Code Generation (LLVM IR)
		Debug.logDebug("Semantic analysis passed. Generating LLVM IR...");
		Path llvmIrPath = getOutputPath(args, inputFile, ".ll");
		CodeGenerator codeGenerator = new CodeGenerator(tree, analyzer, llvmIrPath);
		codeGenerator.generate();

		// 4. Native Compilation & Linking
		Debug.logDebug("Compiling and linking executable...");
		compileAndLink(args, llvmIrPath);

		Debug.logInfo("Compilation successful.");
		Debug.logInfo("Executable created at: " + getOutputPath(args, inputFile, ""));
	}

	/**
	 * Compiles the input files into a library (.neblib + .a/.so).
	 */
	private static void buildLibrary(CompilerArguments args, ErrorHandler errorHandler) throws IOException, InterruptedException
	{
		Debug.logDebug("\nStarting library build...");

		// In a project-based build, we'd parse the .nebproj file here.
		// For now, we'll collect all .neb files passed as arguments.
		List<Path> nebulaFiles = args.getInputFiles().stream()
				.filter(p -> p.toString().endsWith(".neb"))
				.collect(Collectors.toList());

		if (nebulaFiles.isEmpty())
		{
			throw new IllegalArgumentException("Library build requires at least one .neb input file.");
		}

		// 1. Semantic Analysis (Two-Pass for library symbols)
		Scope global = new Scope(null);
		Map<String, ClassSymbol> declaredClasses = new HashMap<>();
		BuiltInTypeLoader.definePrimitives(global);

		// --- PASS 1: Discover all types ---
		SymbolTableBuilder discoveryVisitor = new SymbolTableBuilder(global, declaredClasses, errorHandler, true);
		for (Path p : nebulaFiles)
		{
			Debug.logDebug("Discovering types in library file: " + p);
			ParseTree tree = parseFile(p);
			if (tree != null)
			{
				discoveryVisitor.visit(tree);
			}
		}

		if (declaredClasses.containsKey("nebula.core.String"))
		{
			global.define(new AliasSymbol("string", declaredClasses.get("nebula.core.String")));
		}

		// --- PASS 2: Define members ---
		SymbolTableBuilder memberVisitor = new SymbolTableBuilder(global, declaredClasses, errorHandler, false);
		for (Path p : nebulaFiles)
		{
			Debug.logDebug("Defining members in library file: " + p);
			ParseTree tree = parseFile(p);
			if (tree != null)
			{
				memberVisitor.visit(tree);
			}
		}

		if (errorHandler.hasErrors())
		{
			Debug.logError("Library build failed due to semantic errors.");
			return;
		}

		// (We skip TypeCheckVisitor for library builds for now, as it requires a full main/entry)
		Debug.logInfo("Semantic analysis passed.");

		// 2. Compile Native Sources
		Path buildDir = getOutputPath(args, nebulaFiles.get(0), "").getParent().resolve("build");
		NativeCompiler nativeCompiler = new NativeCompiler(errorHandler, buildDir);

		// We get native files explicitly from the -n flag
		List<Path> objectFiles = nativeCompiler.compileObjectFiles(args.getNativeSourceFiles());

		// 3. Archive Native Library
		// Default output name logic
		Path outputNeblib = getOutputPath(args, nebulaFiles.get(0), ".neblib");
		String libBaseName = outputNeblib.getFileName().toString().replace(".neblib", "");
		String staticLibName = "lib" + libBaseName + ".a";
		Path staticLibPath = null;

		if (!objectFiles.isEmpty())
		{
			staticLibPath = nativeCompiler.archiveStaticLibrary(objectFiles, staticLibName);
		}

		// 4. Write .neblib Metadata
		LibraryDTO lib = new LibraryDTO();
		lib.name = libBaseName;
		lib.namespaces = SymbolDTOConverter.toNamespaces(global);
		writeLibraryMetadata(lib, outputNeblib);

		// 5. Copy static lib to output directory
		if (staticLibPath != null && Files.exists(staticLibPath))
		{
			Path destLib = outputNeblib.resolveSibling(staticLibPath.getFileName());
			Files.copy(staticLibPath, destLib, StandardCopyOption.REPLACE_EXISTING);
			Debug.logInfo("Copied static library to: " + destLib);
		}

		Debug.logInfo("Library build successful: " + outputNeblib);
	}

	/**
	 * Helper to parse a single file and report syntax errors.
	 */
	private static ParseTree parseFile(Path file) throws IOException
	{
		CharStream input = CharStreams.fromFileName(file.toString());
		NebulaLexer lexer = new NebulaLexer(input);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		NebulaParser parser = new NebulaParser(tokens);

		// Remove default error listeners to use our own
		parser.removeErrorListeners();
		parser.addErrorListener(new SyntaxErrorListener());

		ParseTree tree = parser.compilationUnit();
		if (parser.getNumberOfSyntaxErrors() > 0)
		{
			Debug.logError("Compilation failed due to syntax errors in " + file);
			return null;
		}

		if (Debug.ENABLE_DEBUG)
		{
			//Debug.logDebug("Parse Tree for " + file + ":\n" + Trees.toStringTree(tree, parser));
		}
		return tree;
	}

	/**
	 * Refactored link step to use CompilerArguments.
	 */
	private static void compileAndLink(CompilerArguments args, Path llvmIrPath) throws IOException, InterruptedException
	{
		Path executableFile = getOutputPath(args, llvmIrPath, "");
		Path parentDir = executableFile.getParent();
		if (parentDir == null)
		{
			parentDir = Paths.get(".");
		}
		Files.createDirectories(parentDir);

		String baseName = llvmIrPath.getFileName().toString().replaceFirst("[.][^.]+$", "");
		Path objectFile = parentDir.resolve(baseName + ".o");

		// 1. Compile LLVM IR to object file
		Debug.logDebug("Compiling IR: clang -c " + llvmIrPath + " -o " + objectFile);
		ProcessBuilder compileIr = new ProcessBuilder("clang", "-c", llvmIrPath.toString(), "-o", objectFile.toString());
		executeCommand(compileIr);

		// 2. Compile any *additional* native files for this executable
		List<Path> nativeObjectFiles = new ArrayList<>();
		if (!args.getNativeSourceFiles().isEmpty())
		{
			NativeCompiler nativeCompiler = new NativeCompiler(new ErrorHandler(), parentDir.resolve("build"));
			nativeObjectFiles = nativeCompiler.compileObjectFiles(args.getNativeSourceFiles());
		}

		// 3. Link everything
		List<String> linkCommand = new ArrayList<>();
		linkCommand.add("clang++"); // Use C++ linker
		linkCommand.add(objectFile.toString()); // Add main IR object
		nativeObjectFiles.forEach(obj -> linkCommand.add(obj.toAbsolutePath().toString())); // Add native objects

		// Add Library Search Paths
		args.getLibrarySearchPaths().forEach(path -> linkCommand.add("-L" + path.toAbsolutePath()));

		// Add Specific Library Files
		// This includes both -l flags and libraries loaded from --use-ndk (now just -l)
		for (Path libFile : args.getLibraryFiles())
		{
			if (libFile.toString().endsWith(".neblib"))
			{
				// Infer static library path from the .neblib path
				String libName = libFile.getFileName().toString().replace(".neblib", "");
				Path staticLib = libFile.resolveSibling("lib" + libName + ".a");
				if (!Files.exists(staticLib))
				{
					Debug.logError("Static library not found for " + libFile + " at inferred path: " + staticLib);
				}
				else
				{
					linkCommand.add(staticLib.toAbsolutePath().toString());
				}
			}
			else if (libFile.toString().endsWith(".a") || libFile.toString().endsWith(".so"))
			{
				linkCommand.add(libFile.toAbsolutePath().toString());
			}
		}

		linkCommand.add("-o");
		linkCommand.add(executableFile.toAbsolutePath().toString());

		Debug.logDebug("Linking: " + String.join(" ", linkCommand));
		ProcessBuilder link = new ProcessBuilder(linkCommand);
		executeCommand(link);
	}

	/**
	 * Writes the library metadata DTO to a .neblib file.
	 */
	private static void writeLibraryMetadata(LibraryDTO lib, Path outPath) throws IOException
	{
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Files.createDirectories(outPath.getParent());
		Files.writeString(outPath, gson.toJson(lib), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		Debug.logInfo("Wrote library metadata to: " + outPath);
	}

	/**
	 * Determines the final output path for a file.
	 *
	 * @param args         The parsed compiler arguments.
	 * @param inputFile    The primary input file, used to infer a name.
	 * @param newExtension The desired extension (e.g., ".ll", ".neblib", or "" for executable).
	 * @return The final Path.
	 */
	private static Path getOutputPath(CompilerArguments args, Path inputFile, String newExtension)
	{
		if (args.getOutputPath() != null)
		{
			Path out = args.getOutputPath();

			// Only append extension if it's not empty AND not already present
			if (!newExtension.isEmpty() && !out.toString().endsWith(newExtension))
			{
				out = out.resolveSibling(out.getFileName().toString() + newExtension);
			}

			return out;
		}

		// Default: derive from input file
		String baseName = inputFile.getFileName().toString().replaceFirst("[.][^.]+$", "");
		return inputFile.resolveSibling(baseName + newExtension);
	}
}