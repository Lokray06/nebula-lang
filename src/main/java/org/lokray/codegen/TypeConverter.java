// File: src/main/java/org/lokray/codegen/TypeConverter.java
package org.lokray.codegen;

import org.bytedeco.llvm.LLVM.*;
import org.lokray.semantic.type.PrimitiveType; // Import PrimitiveType
import org.lokray.semantic.type.Type;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.bytedeco.llvm.global.LLVM.*;

public class TypeConverter
{

	// Cache of string struct types per LLVMContext pointer value
	private static final Map<Long, LLVMTypeRef> stringStructByCtx = new ConcurrentHashMap<>(); // [cite: 1645]

	private static long ctxId(LLVMContextRef ctx) // [cite: 1645]
	{
		return ctx.address(); // use native pointer address [cite: 1645]
	}

	// Create or reuse nebula_string struct inside this context
	public static LLVMTypeRef getStringStructTypeForContext(LLVMContextRef ctx) // [cite: 1646]
	{
		long key = ctxId(ctx); // [cite: 1646]
		return stringStructByCtx.computeIfAbsent(key, k -> // [cite: 1646]
		{
			LLVMTypeRef struct = LLVMStructCreateNamed(ctx, "nebula_string"); // [cite: 1646]
			LLVMTypeRef[] elems = { // [cite: 1646]
					LLVMPointerType(LLVMInt8Type(), 0), // const char* [cite: 1646]
					LLVMInt32Type()                      // uint32_t length [cite: 1647]
			};
			org.bytedeco.javacpp.PointerPointer<LLVMTypeRef> pp = // [cite: 1647]
					new org.bytedeco.javacpp.PointerPointer<>(elems); // [cite: 1647]
			LLVMStructSetBody(struct, pp, elems.length, 0); // [cite: 1647]
			return struct; // [cite: 1647]
		});
	}

	// Convert Nebula Type -> LLVM type (needs module context)
	public static LLVMTypeRef toLLVMType(Type type, LLVMContextRef ctx) // [cite: 1647]
	{
		if (type == null || type == PrimitiveType.VOID) // Use direct comparison [cite: 1647]
		{
			return LLVMVoidTypeInContext(ctx); // [cite: 1647]
		}

		// Use direct comparison for better performance and clarity
		if (type == PrimitiveType.BOOLEAN)
		{
			return LLVMInt1TypeInContext(ctx); // [cite: 1648]
		}
		if (type == PrimitiveType.CHAR)
		{
			return LLVMInt8TypeInContext(ctx); // [cite: 1648]
		}

		if (type == PrimitiveType.BYTE || type == PrimitiveType.INT8 || type == PrimitiveType.UBYTE || type == PrimitiveType.UINT8)
		{
			return LLVMInt8TypeInContext(ctx); // [cite: 1648]
		}
		if (type == PrimitiveType.SHORT || type == PrimitiveType.INT16 || type == PrimitiveType.USHORT || type == PrimitiveType.UINT16)
		{
			return LLVMInt16TypeInContext(ctx); // Added i16
		}
		if (type == PrimitiveType.INT || type == PrimitiveType.INT32 || type == PrimitiveType.UINT || type == PrimitiveType.UINT32)
		{
			return LLVMInt32TypeInContext(ctx); // [cite: 1648]
		}
		if (type == PrimitiveType.LONG || type == PrimitiveType.INT64 || type == PrimitiveType.ULONG || type == PrimitiveType.UINT64)
		{
			return LLVMInt64TypeInContext(ctx); // Added i64
		}

		if (type == PrimitiveType.FLOAT)
		{
			return LLVMFloatTypeInContext(ctx); // [cite: 1648]
		}
		if (type == PrimitiveType.DOUBLE)
		{
			return LLVMDoubleTypeInContext(ctx); // [cite: 1648]
		}

		// Handle string specifically using its canonical name check
		if ("string".equals(type.getName()) || "String".equals(type.getName()))
		{ // Check canonical name
			LLVMTypeRef stringStruct = getStringStructTypeForContext(ctx); //
			// Return the struct type directly for allocas, return ptr for usage?
			// Let's return the struct type itself for now, pointers handled at usage site.
			// return LLVMPointerType(stringStruct, 0); // Old: returning pointer
			return stringStruct; // New: returning struct type itself
		}


		// --- REMOVED THE DEFAULT CASE THAT RETURNED ptr i8 ---
		// If it's not any known primitive or string, maybe it's a struct/class?
		// For now, let's return a void pointer as a placeholder, but log a warning.
		System.err.println("Warning: TypeConverter encountered unknown type: " + type.getName() + ". Returning void pointer (i8*).");
		return LLVMPointerType(LLVMInt8TypeInContext(ctx), 0); // Fallback placeholder [cite: 1648]
	}

	// Context-safe wrappers (these all just call the non-context versions)
	private static LLVMTypeRef LLVMVoidTypeInContext(LLVMContextRef ctx) // [cite: 1648]
	{
		return LLVMVoidType(); // [cite: 1648]
	}

	private static LLVMTypeRef LLVMInt1TypeInContext(LLVMContextRef ctx) // [cite: 1649]
	{
		return LLVMInt1Type(); // [cite: 1649]
	}

	private static LLVMTypeRef LLVMInt8TypeInContext(LLVMContextRef ctx) // [cite: 1649]
	{
		return LLVMInt8Type(); // [cite: 1649]
	}

	// NEW wrapper for i16
	private static LLVMTypeRef LLVMInt16TypeInContext(LLVMContextRef ctx)
	{
		return LLVMInt16Type();
	}


	private static LLVMTypeRef LLVMInt32TypeInContext(LLVMContextRef ctx) // [cite: 1649]
	{
		return LLVMInt32Type(); // [cite: 1649]
	}

	// NEW wrapper for i64
	private static LLVMTypeRef LLVMInt64TypeInContext(LLVMContextRef ctx)
	{
		return LLVMInt64Type();
	}

	private static LLVMTypeRef LLVMFloatTypeInContext(LLVMContextRef ctx) // [cite: 1649]
	{
		return LLVMFloatType(); // [cite: 1649]
	}

	private static LLVMTypeRef LLVMDoubleTypeInContext(LLVMContextRef ctx) // [cite: 1649]
	{
		return LLVMDoubleType(); // [cite: 1649]
	}
}