package org.lokray.codegen;

import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.lokray.semantic.type.PrimitiveType;
import org.lokray.semantic.type.Type;

import static org.bytedeco.llvm.global.LLVM.*;

public class TypeConverter {

	public static LLVMTypeRef toLLVMType(Type type) {
		if (type == null) {
			return LLVMVoidType();
		}

		String name = type.getName().toLowerCase();

		switch (name) {
			case "void":
				return LLVMVoidType();
			case "int":
			case "int32":
				return LLVMInt32Type();
			case "bool":
				return LLVMInt1Type();
			case "char":
				return LLVMInt8Type();
			case "double":
				return LLVMDoubleType();
			case "float":
				return LLVMFloatType();
			case "string":
				// Strings are pointers to bytes (i8*)
				return LLVMPointerType(LLVMInt8Type(), 0);
			default:
				// For classes, structs, or unresolved types, use i8* as a generic pointer
				return LLVMPointerType(LLVMInt8Type(), 0);
		}
	}
}
