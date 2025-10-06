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
import org.lokray.semantic.symbol.Scope;
import org.lokray.util.Debug;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

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

		// Create a fresh global scope to collect NDk symbols
		Scope global = new Scope(null);

		// Walk .neb files
		List<Path> files = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(ndkRoot))
		{
			// no-op — we'll use walk below
		}
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
			// Build symbols using SymbolTableBuilder
			SymbolTableBuilder builder = new SymbolTableBuilder(global);
			builder.visit(tree);
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
		for (var child : ns.namespaces)
		{
			emitNamespaceStubs(child, qualified, sb);
		}
	}
}
