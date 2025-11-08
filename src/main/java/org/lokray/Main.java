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
import org.lokray.util.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

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

            if (!validatePaths(arguments)) {
                Debug.logError("Aborting.");
                return;
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
	 * Compiles the input files into an executable binary.
	 */
	private static void buildExecutable(CompilerArguments args, ErrorHandler errorHandler) throws IOException, InterruptedException
	{
		Debug.logDebug("\nStarting executable build...");

		List<Path> nebulaFiles = args.getInputFiles().stream()
				.filter(p -> p.toString().endsWith(".neb"))
				.toList();

		if (nebulaFiles.isEmpty())
		{
			throw new IllegalArgumentException("Executable build requires at least one .neb source file.");
		}

		// --- Parse all input files first ---
		List<ParseTree> trees = new ArrayList<>();
		for (Path file : nebulaFiles)
		{
			if (!Files.exists(file))
			{
				Debug.logError("The specified file does not exist: " + file);
				return;
			}

			ParseTree tree = parseFile(file);
			if (tree != null)
			{
				trees.add(tree);
			}
		}

		if (trees.isEmpty())
		{
			Debug.logError("No valid parse trees generated. Aborting.");
			return;
		}

		// --- Semantic Analysis (multi-file, with linked libraries) ---
		Debug.logDebug("Starting semantic analysis for executable...");
		SemanticAnalyzer analyzer = new SemanticAnalyzer(args.getLibraryFiles(), args.getLibrarySearchPaths(), errorHandler);

		boolean success = analyzer.analyze(trees);
		if (!success || errorHandler.hasErrors())
		{
			Debug.logError("Compilation failed due to semantic errors.");
			return;
		}

		// --- Check Only? ---
		if (args.isCheckOnly())
		{
			Debug.logInfo("Semantic check passed. No output generated (-k flag).");
			return;
		}

		// --- Code Generation (LLVM IR) ---
		Debug.logDebug("Semantic analysis passed. Generating LLVM IR...");
		Path llvmIrPath = getOutputPath(args, nebulaFiles.get(0), ".ll");
		CodeGenerator codeGenerator = new CodeGenerator(trees, analyzer, llvmIrPath);
		codeGenerator.generate();

		// --- Native Compilation & Linking ---
		Debug.logDebug("Compiling and linking executable...");
		compileAndLink(args, llvmIrPath);

		Debug.logInfo("Compilation successful.");
		Debug.logInfo("Executable created at: " + getOutputPath(args, nebulaFiles.get(0), ""));
	}

	/**
	 * Compiles the input files into a library (.neblib + .a/.so).
	 */
	private static void buildLibrary(CompilerArguments args, ErrorHandler errorHandler) throws IOException, InterruptedException
	{
		Debug.logDebug("\nStarting library build...");

		List<Path> nebulaFiles = args.getInputFiles().stream()
				.filter(p -> p.toString().endsWith(".neb"))
				.toList();

		if (nebulaFiles.isEmpty())
		{
			throw new IllegalArgumentException("Library build requires at least one .neb source file.");
		}

		// --- Parse all input files ---
		List<ParseTree> libraryTrees = new ArrayList<>();
		for (Path p : nebulaFiles)
		{
			ParseTree tree = parseFile(p);
			if (tree != null)
			{
				libraryTrees.add(tree);
			}
		}

		if (libraryTrees.isEmpty())
		{
			Debug.logError("No valid parse trees found. Aborting.");
			return;
		}

		// --- Semantic Analysis (multi-file) ---
		SemanticAnalyzer analyzer = new SemanticAnalyzer(new ArrayList<>(), new ArrayList<>(), errorHandler);
		if (!analyzer.analyze(libraryTrees))
		{
			Debug.logError("Library build failed due to semantic errors.");
			return;
		}

		Debug.logInfo("Semantic analysis passed.");

		// --- Code Generation ---
		Debug.logDebug("Generating LLVM IR for library...");
		Path outputNeblib = getOutputPath(args, nebulaFiles.get(0), ".neblib");
		Path buildDir = outputNeblib.getParent().resolve("build");
		String libBaseName = outputNeblib.getFileName().toString().replace(".neblib", "");
		Path libIrPath = buildDir.resolve(libBaseName + ".ll");
		Path libObjPath = buildDir.resolve(libBaseName + ".o");

		CodeGenerator codeGenerator = new CodeGenerator(libraryTrees, analyzer, libIrPath);
		codeGenerator.generate();

		// --- Native Compilation ---
		NativeCompiler nativeCompiler = new NativeCompiler(errorHandler, buildDir);

		ProcessBuilder compileLibIr = new ProcessBuilder(
				"clang",
				"-c",
				"-Wno-override-module",
				libIrPath.toAbsolutePath().toString(),
				"-o",
				libObjPath.toAbsolutePath().toString()
		);
		executeCommand(compileLibIr);
		Debug.logDebug("Compiled library IR to: " + libObjPath);

		// --- Compile any native sources provided with -n ---
		List<Path> objectFiles = nativeCompiler.compileObjectFiles(args.getNativeSourceFiles());
		objectFiles.add(libObjPath);

		// --- Archive static library (.a) ---
		String staticLibName = "lib" + libBaseName + ".a";
		Path staticLibPath = null;
		if (!objectFiles.isEmpty())
		{
			staticLibPath = nativeCompiler.archiveStaticLibrary(objectFiles, staticLibName);
		}

		// --- Write .neblib metadata ---
		LibraryDTO lib = new LibraryDTO();
		lib.name = libBaseName;
		lib.namespaces = SymbolDTOConverter.toNamespaces(analyzer.globalScope);
		writeLibraryMetadata(lib, outputNeblib);

		// --- Copy static lib next to .neblib ---
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
        for (Path path : args.getLibrarySearchPaths())
        {
            if (Files.exists(path) && Files.isDirectory(path))
            {
                linkCommand.add("-L" + path.toAbsolutePath());
            }
            else
            {
                Debug.logError("Library search path does not exist: " + path);
            }
        }

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

    private static boolean validatePaths(CompilerArguments args) {
        boolean valid = true;

        // --- Input Files ---
        for (Path input : args.getInputFiles()) {
            if (!Files.exists(input)) {
                Debug.logError("Input file not found: " + input);
                valid = false;
            }
        }

        // --- Library Files (.neblib or .a/.so) ---
        for (Path lib : args.getLibraryFiles()) {
            if (!Files.exists(lib)) {
                Debug.logError("Library file not found: " + lib);
                valid = false;
            }
        }

        // --- Library Search Paths ---
        for (Path libDir : args.getLibrarySearchPaths()) {
            if (!Files.exists(libDir)) {
                Debug.logError("Library search path does not exist: " + libDir);
                valid = false;
            } else if (!Files.isDirectory(libDir)) {
                Debug.logError("Library search path is not a directory: " + libDir);
                valid = false;
            }
        }

        // --- Native Sources ---
        for (Path nativeSrc : args.getNativeSourceFiles()) {
            if (!Files.exists(nativeSrc)) {
                Debug.logError("Native source file not found: " + nativeSrc);
                valid = false;
            }
        }

        // --- Output Directory ---
        if (args.getOutputPath() != null) {
            Path outDir = args.getOutputPath().getParent();
            if (outDir != null && !Files.exists(outDir)) {
                try {
                    Files.createDirectories(outDir);
                    Debug.logInfo("Created output directory: " + outDir);
                } catch (IOException e) {
                    Debug.logError("Failed to create output directory: " + outDir + " (" + e.getMessage() + ")");
                    valid = false;
                }
            }
        }

        return valid;
    }
}