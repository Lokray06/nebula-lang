package org.lokray.ndk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.ndk.dto.LibraryDTO;
import org.lokray.ndk.dto.NamespaceDTO;
import org.lokray.parser.NebulaLexer;
import org.lokray.parser.NebulaParser;
import org.lokray.semantic.*;
import org.lokray.semantic.symbol.AliasSymbol;
import org.lokray.semantic.symbol.ClassSymbol;
import org.lokray.semantic.symbol.Scope;
import org.lokray.semantic.symbol.Symbol;
import org.lokray.semantic.type.Type;
import org.lokray.util.BuiltInTypeLoader;
import org.lokray.util.Debug;
import org.lokray.util.SymbolDTOConverter;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a directory of .neb NDk source files, runs Pass1 to build symbols,
 * converts Scope -> DTO and writes JSON .neblib.
 */
public class NdkCompiler
{
	private final Path ndkRoot;

	public NdkCompiler(Path ndkRoot)
	{
		this.ndkRoot = ndkRoot;
	}

	public LibraryDTO buildLibrary() throws IOException
	{
		LibraryDTO lib = new LibraryDTO();
		lib.name = ndkRoot.getFileName().toString();

		// 1. INITIALIZE SHARED STATE
		Scope global = new Scope(null);
		Map<String, ClassSymbol> declaredClasses = new HashMap<>(); // Used to track all declared FQNs
		List<ParseTree> trees = new ArrayList<>();

		// Define all primitive types into the shared global scope
		BuiltInTypeLoader.definePrimitives(global); //

		// 2. PARSE ALL FILES AND COLLECT TREES
		List<Path> files = new ArrayList<>();
		Files.walk(ndkRoot)
				.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".neb"))
				.forEach(files::add);

		for (Path p : files)
		{
			Debug.logDebug("Parsing NDK file: " + p);
			var input = CharStreams.fromPath(p);
			var lexer = new NebulaLexer(input);
			var tokens = new CommonTokenStream(lexer);
			var parser = new NebulaParser(tokens);
			ParseTree tree = parser.compilationUnit();
			trees.add(tree);
		}

		// =========================================================
		// PASS 1: Symbol Table Building (Declaration)
		// =========================================================
		Debug.logDebug("NDK Build Pass 1: Declaring Symbols");
		// Instantiate the SymbolTableBuilder ONCE with the shared state
		SymbolTableBuilder builder = new SymbolTableBuilder(global, declaredClasses); //

		for (int i = 0; i < files.size(); i++)
		{
			Path p = files.get(i);
			ParseTree tree = trees.get(i);
			Debug.logDebug("Declaring symbols for NDK file: " + p);
			// Run on the shared global scope, collecting symbols from all files
			builder.visit(tree);
		}

		// POST-PASS 1: Setup Global Aliases (as SemanticAnalyzer does)
		if (declaredClasses.containsKey("nebula.core.String"))
		{
			Symbol stringSymbol = declaredClasses.get("nebula.core.String");
			global.define(new AliasSymbol("string", stringSymbol)); // Create alias 'string' -> 'nebula.core.String'
		}


		// =========================================================
		// PASS 2: Type Checking and Resolution (The TypeCheckVisitor)
		// =========================================================
		Debug.logDebug("NDK Build Pass 2: Semantic Analysis");
		Map<ParseTree, Symbol> resolvedSymbols = new HashMap<>();
		Map<ParseTree, Type> resolvedTypes = new HashMap<>();
		boolean hasErrors = builder.hasErrors(); // Start with errors from Pass 1

		for (int i = 0; i < files.size(); i++)
		{
			Path p = files.get(i);
			ParseTree tree = trees.get(i);
			Debug.logDebug("Analyzing NDK file for Pass 2: " + p);

			// Instantiate the TypeCheckVisitor with the fully populated shared state
			TypeCheckVisitor refVisitor = new TypeCheckVisitor(global, declaredClasses, resolvedSymbols, resolvedTypes); //
			refVisitor.visit(tree);

			hasErrors = refVisitor.hasErrors();
		}

		// Convert the global scope into DTOs
		lib.namespaces = SymbolDTOConverter.toNamespaces(global);

		return lib;
	}

	public void writeLibrary(LibraryDTO lib, Path out) throws IOException
	{
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		Files.createDirectories(out.getParent());
		Files.writeString(out, gson.toJson(lib), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		Debug.logInfo("Wrote NDk library to: " + out.toString());
	}

	/**
	 * Helper that produces a simple C runtime stub file for native classes found in the library.
	 * This is just a template generator; you can later implement real native behavior.
	 */
	public void emitRuntimeStub(LibraryDTO lib, Path outCFile) throws IOException
	{
		StringBuilder sb = new StringBuilder();
		sb.append("/* Generated runtime stubs for nebula - minimal template */\n");
		sb.append("#include <stdio.h>\n\n");
		sb.append("/* NOTE: Implement these functions and link into your final binary */\n\n");

		for (NamespaceDTO ns : lib.namespaces)
		{
			emitNamespaceStubs(ns, "", sb);
		}

		Files.writeString(outCFile, sb.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
		Debug.logInfo("Wrote runtime stub C file to: " + outCFile);
	}

	private void emitNamespaceStubs(NamespaceDTO ns, String prefix, StringBuilder sb)
	{
		String qualified = prefix.isEmpty() ? ns.name : prefix + "_" + ns.name;
		for (var cls : ns.classes)
		{
			if (cls.isNative)
			{
				for (var m : cls.methods)
				{
					String fnName = String.format("nebula_%s_%s_%s", qualified, cls.name, m.name);
					sb.append("void ").append(fnName).append("() { printf(\"[nebula runtime stub] ").append(fnName).append("\\n\"); }\n");
				}
			}
		}
		for (var strc : ns.structs)
		{
			if (strc.isNative)
			{
				for (var m : strc.methods)
				{
					String fnName = String.format("nebula_%s_%s_%s", qualified, strc.name, m.name);
					sb.append("void ").append(fnName).append("() { printf(\"[nebula runtime stub] ").append(fnName).append("\\n\"); }\n");
				}
			}
		}
		for (var child : ns.namespaces)
		{
			emitNamespaceStubs(child, qualified, sb);
		}
	}
}
