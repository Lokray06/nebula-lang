package org.lokray.ndk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.codegen.CodeGenerator;
import org.lokray.ndk.dto.LibraryDTO;
import org.lokray.parser.NebulaLexer;
import org.lokray.parser.NebulaParser;
import org.lokray.semantic.SemanticAnalyzer;
import org.lokray.semantic.SymbolTableBuilder;
import org.lokray.semantic.TypeCheckVisitor;
import org.lokray.semantic.symbol.AliasSymbol;
import org.lokray.semantic.symbol.ClassSymbol;
import org.lokray.semantic.symbol.Scope;
import org.lokray.semantic.symbol.Symbol;
import org.lokray.util.BuiltInTypeLoader;
import org.lokray.util.Debug;
import org.lokray.util.ErrorHandler;
import org.lokray.util.SymbolDTOConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.lokray.util.ProcessUtils.executeCommand;

/**
 * Walks a directory of .neb NDK source files, runs semantic analysis,
 * compiles associated native C++ files, generates IR for non-native methods,
 * compiles the IR, and bundles everything into a .neblib (JSON metadata)
 * and a .a (static library).
 */
public class NdkCompiler
{
	private final Path ndkRoot;
	private final ErrorHandler errorHandler;
	Map<Path, ParseTree> parseTrees = new HashMap<>();

	public NdkCompiler(Path ndkRoot, ErrorHandler errorHandler)
	{
		this.ndkRoot = ndkRoot;
		this.errorHandler = errorHandler;
	}

	public LibraryDTO buildLibrary() throws IOException, InterruptedException
	{
		LibraryDTO lib = new LibraryDTO();
		lib.name = ndkRoot.getFileName().toString();

		Scope global = new Scope(null);
		Map<String, ClassSymbol> declaredClasses = new HashMap<>();
		BuiltInTypeLoader.definePrimitives(global);

		// **NEW:** We need to store parse trees for the code generation pass
		Path buildDir = ndkRoot.resolve("build"); // <-- MOVED UP
		Files.createDirectories(buildDir); // <-- MOVED UP

		List<Path> files = Files.walk(ndkRoot)
				.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".neb"))
				.collect(Collectors.toList());

		// --- PASS 1: Discover all types first ---
		SymbolTableBuilder discoveryVisitor = new SymbolTableBuilder(global, declaredClasses, errorHandler, true);
		for (Path p : files)
		{
			Debug.logDebug("Discovering types in NDK file: " + p);
			var input = CharStreams.fromPath(p);
			var lexer = new NebulaLexer(input);
			var tokens = new CommonTokenStream(lexer);
			var parser = new NebulaParser(tokens);
			ParseTree tree = parser.compilationUnit();
			parseTrees.put(p, tree); // <-- Store the tree
			discoveryVisitor.visit(tree);
		}

		// After discovery, create the global 'string' alias
		if (declaredClasses.containsKey("nebula.core.String"))
		{
			Symbol stringSymbol = declaredClasses.get("nebula.core.String");
			global.define(new AliasSymbol("string", stringSymbol));
		}

		// --- PASS 2: Define members and resolve imports ---
		SymbolTableBuilder memberVisitor = new SymbolTableBuilder(global, declaredClasses, errorHandler, false);
		for (Path p : files)
		{
			Debug.logDebug("Defining members in NDK file: " + p);
			ParseTree tree = parseTrees.get(p); // <-- Retrieve the tree
			memberVisitor.visit(tree);
		}

		lib.namespaces = SymbolDTOConverter.toNamespaces(global);

		// --- PASS 3: Generate LLVM IR for non-native methods ---
		List<Path> generatedLlFiles = new ArrayList<>();
		Debug.logInfo("Generating IR for NDK non-native methods...");

		SemanticAnalyzer ndkAnalyzer = new SemanticAnalyzer(errorHandler); // Use NDK error handler
		// Manually set the state based on our NDK analysis passes
		ndkAnalyzer.globalScope = global;
		ndkAnalyzer.declaredClasses = declaredClasses;
		// Run the TypeCheckVisitor pass *on the NDK code* to populate resolvedSymbols/Types
		// This is crucial for the CodeGenerator to look up resolved types/symbols.
		Debug.logDebug("\nNDK Type checking (for IR generation)...");
		TypeCheckVisitor ndkTypeChecker = new org.lokray.semantic.TypeCheckVisitor(ndkAnalyzer.globalScope, ndkAnalyzer.declaredClasses, ndkAnalyzer.resolvedSymbols, ndkAnalyzer.resolvedTypes, ndkAnalyzer.resolvedInfo, errorHandler);

		for (ParseTree nebTree : parseTrees.values())
		{
			ndkTypeChecker.visit(nebTree);
		}
		// Check for NDK semantic errors *before* generating code
		if (ndkTypeChecker.hasErrors())
		{
			Debug.logError("NDK semantic errors found. Skipping IR generation.");
			// Decide how to handle this - throw exception, return null, etc.
			throw new RuntimeException("NDK semantic errors prevent library build.");
		}
		Debug.logDebug("NDK Type checking passed.");


		for (Map.Entry<Path, ParseTree> entry : parseTrees.entrySet())
		{
			Path nebFile = entry.getKey();
			ParseTree nebTree = entry.getValue();

			// Generate a corresponding .ll file in the build directory
			String llFileName = nebFile.getFileName().toString().replace(".neb", ".ll");
			Path llFile = buildDir.resolve(llFileName);

			Debug.logDebug("Generating IR for: " + nebFile.getFileName() + " -> " + llFile.getFileName());
			try
			{
				// Pass the *populated* ndkAnalyzer
				CodeGenerator codeGenerator = new CodeGenerator(nebTree, ndkAnalyzer, llFile);
				codeGenerator.generate();
				generatedLlFiles.add(llFile);
			}
			catch (Exception e)
			{
				Debug.logError("Failed to generate IR for " + nebFile.getFileName() + ": " + e.getMessage());
				e.printStackTrace(); // Keep this for debugging
				// Optionally re-throw or mark the build as failed
				throw new IOException("IR generation failed for " + nebFile.getFileName(), e);
			}
		}

		// Compile native sources AND generated IR into a static library
		compileNativeSources(generatedLlFiles); // <-- Pass the new .ll files

		return lib;
	}

	/**
	 * Compiles native .cpp files and generated .ll files into object files,
	 * then archives them into a single static library.
	 *
	 * @param generatedLlFiles List of .ll files generated from .neb sources
	 */
	private void compileNativeSources(List<Path> generatedLlFiles) throws IOException, InterruptedException
	{
		Path nativeDir = ndkRoot.resolve("native");
		List<Path> cppFiles = new ArrayList<>();
		if (Files.exists(nativeDir) && Files.isDirectory(nativeDir))
		{
			cppFiles = Files.walk(nativeDir)
					.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".cpp"))
					.toList();
		}

		// **MODIFIED:** Check if there is *anything* to compile
		if (cppFiles.isEmpty() && generatedLlFiles.isEmpty())
		{
			Debug.logDebug("No 'native' .cpp files or non-native .neb methods found. Skipping static library creation.");
			return; // Nothing to do
		}

		Path buildDir = ndkRoot.resolve("build");
		List<String> objectFiles = new ArrayList<>(); // Stores paths to .o files

		// --- Compile .cpp files ---
		if (!cppFiles.isEmpty())
		{
			Debug.logInfo("Compiling native NDK sources...");
			for (Path cppFile : cppFiles)
			{
				String objFileName = cppFile.getFileName().toString().replace(".cpp", ".o");
				Path objFile = buildDir.resolve(objFileName);
				objectFiles.add(objFile.toString()); // Add .o path

				// Use clang++ for C++ files
				ProcessBuilder compile = new ProcessBuilder("clang++", "-c", cppFile.toAbsolutePath().toString(), "-o", objFile.toAbsolutePath().toString());
				executeCommand(compile);
			}
		}

		// --- **NEW:** Compile generated .ll files ---
		if (!generatedLlFiles.isEmpty())
		{
			Debug.logInfo("Compiling generated NDK IR...");
			for (Path llFile : generatedLlFiles)
			{
				String objFileName = llFile.getFileName().toString().replace(".ll", ".o");
				Path objFile = buildDir.resolve(objFileName);
				objectFiles.add(objFile.toString()); // Add .o path

				// Use clang (not clang++) to compile LLVM IR to an object file
				ProcessBuilder compileIr = new ProcessBuilder("clang", "-c", llFile.toAbsolutePath().toString(), "-o", objFile.toAbsolutePath().toString());
				executeCommand(compileIr);
			}
		}

		// --- Archive ALL object files ---
		Debug.logInfo("Archiving native and generated objects into a static library...");
		Path staticLibPath = buildDir.resolve("lib" + ndkRoot.getFileName().toString() + ".a");
		List<String> archiveCommand = new ArrayList<>();
		archiveCommand.add("ar");
		archiveCommand.add("rcs"); // r = replace/insert, c = create archive if needed, s = create index
		archiveCommand.add(staticLibPath.toAbsolutePath().toString());
		archiveCommand.addAll(objectFiles); // Add all .o file paths

		ProcessBuilder archive = new ProcessBuilder(archiveCommand);
		executeCommand(archive);

		Debug.logInfo("Static library created at: " + staticLibPath);
	}

	public void writeLibrary(LibraryDTO lib, Path out) throws IOException
	{
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Files.createDirectories(out.getParent()); // Ensure output directory exists
		Files.writeString(out, gson.toJson(lib), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		Debug.logInfo("Wrote NDK library metadata to: " + out);

		// Also copy the static library to the output directory
		Path staticLib = ndkRoot.resolve("build/lib" + ndkRoot.getFileName().toString() + ".a");
		if (Files.exists(staticLib))
		{
			Path destLib = out.resolveSibling("lib" + lib.name + ".a"); // e.g., next to ndk.neblib, create libndk.a
			Files.copy(staticLib, destLib, StandardCopyOption.REPLACE_EXISTING);
			Debug.logInfo("Copied static library to: " + destLib);
		}
		else
		{
			// Only log if we actually expected to create one
			if (!Files.exists(ndkRoot.resolve("native")) && parseTrees.isEmpty())
			{
				Debug.logDebug("No native sources or .neb files found, so no static library was created or copied.");
			}
			else
			{
				Debug.logWarning("Static library " + staticLib + " not found after compilation/archiving. Cannot copy.");
			}
		}
	}
}