package org.lokray.codegen;

import org.antlr.v4.runtime.tree.ParseTree;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.llvm.LLVM.LLVMModuleRef; // <-- ADD THIS IMPORT
import org.lokray.semantic.SemanticAnalyzer;
import org.lokray.util.Debug;

import java.io.IOException;
import java.nio.file.Path;

import static org.bytedeco.llvm.global.LLVM.*;

public class CodeGenerator
{
	// STATIC INITIALIZER BLOCK
	// This will run once when the CodeGenerator class is first loaded,
	// ensuring LLVM is initialized before any code generation happens.
	static
	{
		LLVMInitializeAllTargetInfos();
		LLVMInitializeAllTargets();
		LLVMInitializeAllTargetMCs();
		LLVMInitializeAllAsmParsers();
		LLVMInitializeAllAsmPrinters();
		Debug.logDebug("LLVM Subsystems Initialized.");
	}

	private final ParseTree tree;
	private final SemanticAnalyzer semanticAnalyzer;
	private final Path outputPath;

	public CodeGenerator(ParseTree tree, SemanticAnalyzer semanticAnalyzer, Path outputPath)
	{
		this.tree = tree;
		this.semanticAnalyzer = semanticAnalyzer;
		this.outputPath = outputPath;
	}

	public void generate() throws IOException
	{
		// 1. Initialize IRVisitor, which now creates the module internally using the global context
		IRVisitor visitor = new IRVisitor(semanticAnalyzer);

		// 2. Run the visitor to populate the module
		visitor.visit(tree);

		// 3. Retrieve the generated module
		LLVMModuleRef module = visitor.getModule();

		// 4. Print the generated LLVM IR to a file
		String outputFilename = outputPath.toString();

		// Note: The BytePointer argument here is for an optional error message, which we discard.
		if (LLVMPrintModuleToFile(module, outputFilename, new BytePointer((Pointer) null)) != 0)
		{
			Debug.logError("Error writing LLVM IR to file.");
		}
		else
		{
			Debug.logDebug("LLVM IR written to " + outputFilename);
		}

		// 5. Clean up the module now that we are done with it
		LLVMDisposeModule(module);
	}
}