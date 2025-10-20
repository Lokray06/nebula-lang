package org.lokray.codegen;

import org.bytedeco.llvm.LLVM.*;
import org.lokray.semantic.type.Type;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.bytedeco.llvm.global.LLVM.*;

public class TypeConverter
{

	// Cache of string struct types per LLVMContext pointer value
	private static final Map<Long, LLVMTypeRef> stringStructByCtx = new ConcurrentHashMap<>();

	private static long ctxId(LLVMContextRef ctx)
	{
		return ctx.address(); // use native pointer address
	}

	// Create or reuse nebula_string struct inside this context
	public static LLVMTypeRef getStringStructTypeForContext(LLVMContextRef ctx)
	{
		long key = ctxId(ctx);
		return stringStructByCtx.computeIfAbsent(key, k ->
		{
			LLVMTypeRef struct = LLVMStructCreateNamed(ctx, "nebula_string");
			LLVMTypeRef[] elems = {
					LLVMPointerType(LLVMInt8Type(), 0), // const char*
					LLVMInt32Type()                     // uint32_t length
			};
			org.bytedeco.javacpp.PointerPointer<LLVMTypeRef> pp =
					new org.bytedeco.javacpp.PointerPointer<>(elems);
			LLVMStructSetBody(struct, pp, elems.length, 0);
			return struct;
		});
	}

	// Convert Nebula Type -> LLVM type (needs module context)
	public static LLVMTypeRef toLLVMType(Type type, LLVMContextRef ctx)
	{
		if (type == null)
		{
			return LLVMVoidTypeInContext(ctx);
		}

		String name = type.getName().toLowerCase();

		switch (name)
		{
			case "void":
				return LLVMVoidTypeInContext(ctx);
			case "int":
			case "int32":
				return LLVMInt32TypeInContext(ctx);
			case "bool":
				return LLVMInt1TypeInContext(ctx);
			case "char":
				return LLVMInt8TypeInContext(ctx);
			case "double":
				return LLVMDoubleTypeInContext(ctx);
			case "float":
				return LLVMFloatTypeInContext(ctx);
			case "string":
				LLVMTypeRef stringStruct = getStringStructTypeForContext(ctx);
				return LLVMPointerType(stringStruct, 0);
			default:
				return LLVMPointerType(LLVMInt8TypeInContext(ctx), 0);
		}
	}

	// Context-safe wrappers (these all just call the non-context versions)
	private static LLVMTypeRef LLVMVoidTypeInContext(LLVMContextRef ctx)
	{
		return LLVMVoidType();
	}

	private static LLVMTypeRef LLVMInt32TypeInContext(LLVMContextRef ctx)
	{
		return LLVMInt32Type();
	}

	private static LLVMTypeRef LLVMInt1TypeInContext(LLVMContextRef ctx)
	{
		return LLVMInt1Type();
	}

	private static LLVMTypeRef LLVMInt8TypeInContext(LLVMContextRef ctx)
	{
		return LLVMInt8Type();
	}

	private static LLVMTypeRef LLVMDoubleTypeInContext(LLVMContextRef ctx)
	{
		return LLVMDoubleType();
	}

	private static LLVMTypeRef LLVMFloatTypeInContext(LLVMContextRef ctx)
	{
		return LLVMFloatType();
	}
}
