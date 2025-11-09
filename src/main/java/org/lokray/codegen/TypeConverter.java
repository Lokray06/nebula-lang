package org.lokray.codegen;

import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import org.bytedeco.llvm.LLVM.LLVMTypeRef;
import org.bytedeco.llvm.LLVM.LLVMValueRef;
import org.lokray.parser.NebulaParser;
import org.lokray.semantic.symbol.ClassSymbol;
import org.lokray.semantic.symbol.Symbol;
import org.lokray.semantic.symbol.VariableSymbol;
import org.lokray.semantic.type.ArrayType;
import org.lokray.semantic.type.ClassType;
import org.lokray.semantic.type.PrimitiveType;
import org.lokray.semantic.type.Type;
import org.lokray.util.Debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.bytedeco.llvm.global.LLVM.*;

public class TypeConverter
{
	// Cache for global struct types
	private static final AtomicReference<LLVMTypeRef> globalStringStruct = new AtomicReference<>();
	private static final AtomicReference<LLVMTypeRef> globalArrayDescStruct = new AtomicReference<>();
	// Cache for user-defined class structs. We use FQN as the key.
	private static final Map<String, LLVMTypeRef> globalClassStructs = new HashMap<>();

	static void init()
	{
		getArrayDescStructType();
		getArrayDescStructType();
	}

	/**
	 * Creates or retrieves the LLVM struct type for a given ClassSymbol.
	 * This method defines the memory layout of a class instance.
	 */
	public static LLVMTypeRef getStructTypeForClass(ClassSymbol cs)
	{
		if (cs == null)
		{
			return null;
		}

		String fqn = cs.getFqn();
		LLVMTypeRef cachedStruct = globalClassStructs.get(fqn);
		if (cachedStruct != null)
		{
			return cachedStruct;
		}

		// Create a named, empty struct first. This is crucial for handling
		// recursive types (e.g., class Node { Node next; })
		LLVMTypeRef newStruct = LLVMStructCreateNamed(LLVMGetGlobalContext(), cs.getMangledName());
		globalClassStructs.put(fqn, newStruct);

		// Collect all *non-static* fields from this class
		List<LLVMTypeRef> fieldTypes = new ArrayList<>();
		for (Symbol member : cs.getSymbols().values())
		{
			if (member instanceof VariableSymbol vs && !vs.isStatic())
			{
				// Recursively call toLLVMType to get the type for this field
				fieldTypes.add(toLLVMType(vs.getType()));
			}
		}

		// Set the struct body with the resolved field types
		LLVMTypeRef[] elems = fieldTypes.toArray(new LLVMTypeRef[0]);
		PointerPointer<LLVMTypeRef> pp = new PointerPointer<>(elems);
		LLVMStructSetBody(newStruct, pp, elems.length, 0);

		Debug.logDebug("TypeConverter: Created struct " + cs.getMangledName() + " with " + elems.length + " fields.");
		return newStruct;
	}

	// Create or reuse nebula_string struct
	public static LLVMTypeRef getStringStructType()
	{
		LLVMTypeRef struct = globalStringStruct.get();
		if (struct == null)
		{
			LLVMTypeRef newStruct = LLVMStructCreateNamed(LLVMGetGlobalContext(), "nebula_string");
			LLVMTypeRef[] elems = {
					LLVMPointerType(LLVMInt8Type(), 0), // const char*
					LLVMInt32Type()                      // uint32_t length
			};
			PointerPointer<LLVMTypeRef> pp = new PointerPointer<>(elems);

			LLVMStructSetBody(newStruct, pp, elems.length, 0);

			if (globalStringStruct.compareAndSet(null, newStruct))
			{
				struct = newStruct;
			}
			else
			{
				struct = globalStringStruct.get(); // Another thread created it
			}
		}
		return struct;
	}

	// Create or reuse nebula_Array_t descriptor struct
	public static LLVMTypeRef getArrayDescStructType()
	{
		LLVMTypeRef struct = globalArrayDescStruct.get();
		if (struct == null)
		{
			LLVMTypeRef newStruct = LLVMStructCreateNamed(LLVMGetGlobalContext(), "nebula_Array_t");
			LLVMTypeRef[] elems = {
					LLVMPointerType(LLVMInt8Type(), 0), // void* data (represented as i8*)
					LLVMInt32Type()                      // uint32_t size
			};
			PointerPointer<LLVMTypeRef> pp = new PointerPointer<>(elems);
			LLVMStructSetBody(newStruct, pp, elems.length, 0);

			if (globalArrayDescStruct.compareAndSet(null, newStruct))
			{
				struct = newStruct;
			}
			else
			{
				struct = globalArrayDescStruct.get(); // Another thread created it
			}
		}
		return struct;
	}

	// Convert Nebula Type -> LLVM type
	public static LLVMTypeRef toLLVMType(Type type)
	{
		if (type == null || type == PrimitiveType.VOID)
		{
			return LLVMVoidType();
		}

		if (type == PrimitiveType.BOOLEAN)
		{
			return LLVMInt1Type();
		}
		if (type == PrimitiveType.CHAR)
		{
			return LLVMInt8Type();
		}

		if (type == PrimitiveType.SBYTE || type == PrimitiveType.INT8 || type == PrimitiveType.BYTE || type == PrimitiveType.UINT8)
		{
			return LLVMInt8Type();
		}
		if (type == PrimitiveType.SHORT || type == PrimitiveType.INT16 || type == PrimitiveType.USHORT || type == PrimitiveType.UINT16)
		{
			return LLVMInt16Type();
		}
		if (type == PrimitiveType.INT || type == PrimitiveType.INT32 || type == PrimitiveType.UINT || type == PrimitiveType.UINT32)
		{
			return LLVMInt32Type();
		}
		if (type == PrimitiveType.LONG || type == PrimitiveType.INT64 || type == PrimitiveType.ULONG || type == PrimitiveType.UINT64)
		{
			return LLVMInt64Type();
		}

		if (type == PrimitiveType.FLOAT)
		{
			return LLVMFloatType();
		}
		if (type == PrimitiveType.DOUBLE)
		{
			return LLVMDoubleType();
		}

		// Handle string
		if ("string".equals(type.getName()) || "String".equals(type.getName()))
		{
			LLVMTypeRef stringStruct = getStringStructType();
			// Per C++ ABI (see Console.h), strings are passed as POINTERS
			return LLVMPointerType(stringStruct, 0); // <-- FIX
		}

		// Handle arrays
		if (type instanceof ArrayType)
		{
			// Arrays are represented by a pointer to their descriptor struct
			LLVMTypeRef descStruct = getArrayDescStructType();
			return LLVMPointerType(descStruct, 0);
		}

		// --- Handle Class Types ---
		if (type instanceof ClassType classType)
		{
			// Classes are references, so the type is a pointer to the struct
			LLVMTypeRef structType = getStructTypeForClass(classType.getClassSymbol());
			return LLVMPointerType(structType, 0); // Return a POINTER to the struct
		}

		// Fallback for unknown types
		System.err.println("Warning: TypeConverter encountered unknown type: " + type.getName() + ". Returning void pointer (i8*).");
		return LLVMPointerType(LLVMInt8Type(), 0);
	}

	/**
	 * Helper to convert a non-boolean LLVMValueRef into an i1 boolean value.
	 * This is crucial for LLVMBuildCondBr.
	 */
	public static LLVMValueRef toBoolean(LLVMValueRef value, NebulaParser.ExpressionContext ctx, LLVMBuilderRef builder)
	{
		LLVMTypeRef valueType = LLVMTypeOf(value);
		LLVMTypeRef i1Type = LLVMInt1Type();

		if (LLVMGetTypeKind(valueType) == LLVMIntegerTypeKind)
		{
			if (LLVMGetIntTypeWidth(valueType) == 1)
			{
				// Already i1 (boolean), return it directly
				return value;
			}
			else
			{
				// Integer (i32, i64, etc.) -> i1 by comparing to zero (i.e., val != 0)
				LLVMValueRef zero = LLVMConstInt(valueType, 0, 0);
				return LLVMBuildICmp(builder, LLVMIntNE, value, zero, "tobool.int");
			}
		}
		else if (LLVMGetTypeKind(valueType) == LLVMFloatTypeKind || LLVMGetTypeKind(valueType) == LLVMDoubleTypeKind)
		{
			// Floating point -> i1 by comparing to zero (i.e., val != 0.0)
			LLVMValueRef zero = LLVMConstNull(valueType);
			return LLVMBuildFCmp(builder, LLVMRealONE, value, zero, "tobool.fp");
		}
		else if (LLVMGetTypeKind(valueType) == LLVMPointerTypeKind)
		{
			// Pointers -> i1 by comparing to null (i.e., ptr != null)
			LLVMValueRef nullPtr = LLVMConstNull(valueType);
			return LLVMBuildICmp(builder, LLVMIntNE, value, nullPtr, "tobool.ptr");
		}
		else
		{
			// Fallback for other types; log error if type is unexpected
			String ctxText = (ctx != null) ? ctx.getText() : "unknown expression";
			Debug.logWarning("IR Warning: Conditional expression resulted in unhandled type kind: " + LLVMGetTypeKind(valueType) + " in " + ctxText);
			// Treat it as true (safest default for a potential error)
			return LLVMConstInt(i1Type, 1, 0);
		}
	}
}