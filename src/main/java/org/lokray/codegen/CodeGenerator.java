package org.lokray.codegen;

import org.antlr.v4.runtime.tree.ParseTree;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;
import org.lokray.semantic.SemanticAnalyzer;
import org.lokray.util.Debug;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.bytedeco.llvm.global.LLVM.*;

public class CodeGenerator
{
	// STATIC INITIALIZER BLOCK
	static
	{
		LLVMInitializeAllTargetInfos();
		LLVMInitializeAllTargets();
		LLVMInitializeAllTargetMCs();
		LLVMInitializeAllAsmParsers();
		LLVMInitializeAllAsmPrinters();
		Debug.logDebug("LLVM Subsystems Initialized.");
	}

	private final List<ParseTree> trees;
	private final SemanticAnalyzer semanticAnalyzer;
	private final Path outputPath;

	public CodeGenerator(List<ParseTree> trees, SemanticAnalyzer semanticAnalyzer, Path outputPath)
	{
		this.trees = trees;
		this.semanticAnalyzer = semanticAnalyzer;
		this.outputPath = outputPath;
	}

    public void generate() throws IOException
    {
        // 1. Initialize IRVisitor
        IRVisitor visitor = new IRVisitor(semanticAnalyzer);

        // 2. Run the visitor to populate the module
        for (ParseTree tree : trees)
        {
            visitor.visit(tree);
        }

        // 3. Retrieve the generated module
        LLVMModuleRef module = visitor.getModule();

        // 4. Prepare the output path
        String outputFilename = outputPath.toString();

        // ✅ Ensure parent directory exists before writing
        if (outputPath.getParent() != null)
        {
            java.nio.file.Files.createDirectories(outputPath.getParent());
        }

        // 5. Write the LLVM IR to file
        if (LLVMPrintModuleToFile(module, outputFilename, new BytePointer((Pointer) null)) != 0)
        {
            Debug.logError("Error writing LLVM IR to file: " + outputFilename);
        }
        else
        {
            Debug.logDebug("LLVM IR written to " + outputFilename);
        }

        // 6. Clean up the module
        LLVMDisposeModule(module);
    }
}
