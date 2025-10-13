package org.lokray.codegen;

import org.antlr.v4.runtime.tree.ParseTree;
import org.bytedeco.javacpp.Pointer;
import org.lokray.semantic.SemanticAnalyzer;
import org.lokray.util.Debug;

import java.io.IOException;
import java.nio.file.Path;

import static org.bytedeco.llvm.global.LLVM.LLVMPrintModuleToFile;

public class CodeGenerator
{

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
		try (LLVMContext llvmContext = new LLVMContext())
		{
			IRVisitor visitor = new IRVisitor(semanticAnalyzer, llvmContext);
			visitor.visit(tree);

			// Print the generated LLVM IR to a file
			String outputFilename = outputPath.toString();
			if (LLVMPrintModuleToFile(llvmContext.getModule(), outputFilename, new org.bytedeco.javacpp.BytePointer((Pointer) null)) != 0)
			{
				Debug.logError("Error writing LLVM IR to file.");
			}
			else
			{
				Debug.logInfo("LLVM IR written to " + outputFilename);
			}
		}
	}
}

