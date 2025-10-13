package org.lokray.ndk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.Trees;
import org.lokray.ndk.dto.LibraryDTO;
import org.lokray.parser.NebulaLexer;
import org.lokray.parser.NebulaParser;
import org.lokray.semantic.SymbolTableBuilder;
import org.lokray.semantic.symbol.AliasSymbol;
import org.lokray.semantic.symbol.ClassSymbol;
import org.lokray.semantic.symbol.Scope;
import org.lokray.semantic.symbol.Symbol;
import org.lokray.util.BuiltInTypeLoader;
import org.lokray.util.Debug;
import org.lokray.util.ErrorHandler;
import org.lokray.util.SymbolDTOConverter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.lokray.util.ProcessUtils.executeCommand;

/**
 * Walks a directory of .neb NDK source files, runs semantic analysis,
 * compiles associated native C++ files, and bundles everything into a
 * .neblib (JSON metadata) and a .a (static library).
 */
public class NdkCompiler
{
	private final Path ndkRoot;
	private final ErrorHandler errorHandler;

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

		List<Path> files = Files.walk(ndkRoot)
				.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".neb"))
				.collect(Collectors.toList());

		// --- PASS 1: Discover all types first ---
		// This visitor will only define namespaces, classes, and structs without processing members.
		SymbolTableBuilder discoveryVisitor = new SymbolTableBuilder(global, declaredClasses, errorHandler, true);
		for (Path p : files)
		{
			Debug.logDebug("Discovering types in NDK file: " + p);
			var input = CharStreams.fromPath(p);
			var lexer = new NebulaLexer(input);
			var tokens = new CommonTokenStream(lexer);
			var parser = new NebulaParser(tokens);
			ParseTree tree = parser.compilationUnit();
			discoveryVisitor.visit(tree);
		}

		// After discovery, create the global 'string' alias if the class exists
		if (declaredClasses.containsKey("nebula.core.String"))
		{
			Symbol stringSymbol = declaredClasses.get("nebula.core.String");
			global.define(new AliasSymbol("string", stringSymbol));
		}


		// --- PASS 2: Define members and resolve imports ---
		// This visitor will process imports, methods, and fields.
		SymbolTableBuilder memberVisitor = new SymbolTableBuilder(global, declaredClasses, errorHandler, false);
		for (Path p : files)
		{
			Debug.logDebug("Defining members in NDK file: " + p);
			var input = CharStreams.fromPath(p);
			var lexer = new NebulaLexer(input);
			var tokens = new CommonTokenStream(lexer);
			var parser = new NebulaParser(tokens);
			ParseTree tree = parser.compilationUnit();
			memberVisitor.visit(tree); // Now build the full symbol table
		}

		lib.namespaces = SymbolDTOConverter.toNamespaces(global);

		// **NEW:** Compile native sources into a static library
		compileNativeSources();

		return lib;
	}

	private void compileNativeSources() throws IOException, InterruptedException
	{
		Path nativeDir = ndkRoot.resolve("native");
		if (!Files.exists(nativeDir) || !Files.isDirectory(nativeDir))
		{
			Debug.logDebug("No 'native' directory found in NDK root. Skipping native compilation.");
			return;
		}

		List<Path> cppFiles = Files.walk(nativeDir)
				.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".cpp"))
				.toList();

		if (cppFiles.isEmpty())
		{
			Debug.logDebug("No .cpp files found in 'runtime' directory. Skipping native compilation.");
			return;
		}

		Path buildDir = ndkRoot.resolve("build");
		Files.createDirectories(buildDir);
		List<String> objectFiles = new ArrayList<>();

		Debug.logInfo("Compiling native NDK sources...");
		for (Path cppFile : cppFiles)
		{
			String objFileName = cppFile.getFileName().toString().replace(".cpp", ".o");
			Path objFile = buildDir.resolve(objFileName);
			objectFiles.add(objFile.toString());

			ProcessBuilder compile = new ProcessBuilder("clang++", "-c", cppFile.toAbsolutePath().toString(), "-o", objFile.toAbsolutePath().toString());
			executeCommand(compile);
		}

		Debug.logInfo("Archiving native objects into a static library...");
		Path staticLibPath = buildDir.resolve("lib" + ndkRoot.getFileName().toString() + ".a");
		List<String> archiveCommand = new ArrayList<>();
		archiveCommand.add("ar");
		archiveCommand.add("rcs");
		archiveCommand.add(staticLibPath.toAbsolutePath().toString());
		archiveCommand.addAll(objectFiles);

		ProcessBuilder archive = new ProcessBuilder(archiveCommand);
		executeCommand(archive);

		Debug.logInfo("Static library created at: " + staticLibPath);
	}

	public void writeLibrary(LibraryDTO lib, Path out) throws IOException
	{
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Files.createDirectories(out.getParent());
		Files.writeString(out, gson.toJson(lib), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		Debug.logInfo("Wrote NDK library metadata to: " + out);

		// Also copy the static library to the output directory
		Path staticLib = ndkRoot.resolve("build/lib" + ndkRoot.getFileName().toString() + ".a");
		if (Files.exists(staticLib))
		{
			Path destLib = out.resolveSibling("lib" + lib.name + ".a");
			Files.copy(staticLib, destLib, StandardCopyOption.REPLACE_EXISTING);
			Debug.logInfo("Copied static library to: " + destLib);
		}
	}
}