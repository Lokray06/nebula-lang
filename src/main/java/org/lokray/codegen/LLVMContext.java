package org.lokray.codegen;

import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import org.bytedeco.llvm.LLVM.LLVMContextRef;
import org.bytedeco.llvm.LLVM.LLVMModuleRef;

import static org.bytedeco.llvm.global.LLVM.*;

public class LLVMContext implements AutoCloseable {

	private final LLVMContextRef context;
	private final LLVMModuleRef module;
	private final LLVMBuilderRef builder;

	public LLVMContext() {
		// Initialize LLVM
		LLVMInitializeAllTargetInfos();
		LLVMInitializeAllTargets();
		LLVMInitializeAllTargetMCs();
		LLVMInitializeAllAsmParsers();
		LLVMInitializeAllAsmPrinters();

		this.context = LLVMContextCreate();
		this.module = LLVMModuleCreateWithNameInContext("nebula_module", context);
		this.builder = LLVMCreateBuilderInContext(context);
	}

	public LLVMContextRef getContext() {
		return context;
	}

	public LLVMModuleRef getModule() {
		return module;
	}

	public LLVMBuilderRef getBuilder() {
		return builder;
	}

	@Override
	public void close() {
		LLVMDisposeBuilder(builder);
		LLVMDisposeModule(module);
		LLVMContextDispose(context);
	}
}

