package org.lokray.codegen;

import org.bytedeco.llvm.LLVM.*;
import org.lokray.parser.NebulaParser;
import org.lokray.semantic.type.PrimitiveType; // Import PrimitiveType
import org.lokray.semantic.type.Type;
import org.lokray.util.Debug;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import static org.bytedeco.llvm.global.LLVM.*;
public class TypeConverter
{
    // Cache of string struct types per LLVMContext pointer value
    private static final Map<Long, LLVMTypeRef> stringStructByCtx = new ConcurrentHashMap<>();
    private static final Map<Long, LLVMTypeRef> arrayDescStructByCtx = new ConcurrentHashMap<>();

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
                    LLVMInt32Type()                      // uint32_t length
            };
            org.bytedeco.javacpp.PointerPointer<LLVMTypeRef> pp = new org.bytedeco.javacpp.PointerPointer<>(elems);

            LLVMStructSetBody(struct, pp, elems.length, 0);
            return struct;
        });
    }

    // *** NEW: Create or reuse nebula_Array_t descriptor struct ***
    public static LLVMTypeRef getArrayDescStructTypeForContext(LLVMContextRef ctx)
    {
        long key = ctxId(ctx);
        return arrayDescStructByCtx.computeIfAbsent(key, k ->
        {
            LLVMTypeRef struct = LLVMStructCreateNamed(ctx, "nebula_Array_t");
            LLVMTypeRef[] elems = {
                    LLVMPointerType(LLVMInt8TypeInContext(ctx), 0), // void* data (represented as i8*)
                    LLVMInt32TypeInContext(ctx)                      // uint32_t size
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
        if (type == null || type == PrimitiveType.VOID) // Use direct comparison
        {
            return LLVMVoidTypeInContext(ctx);
        }

        // Use direct comparison for better performance and clarity
        if (type == PrimitiveType.BOOLEAN)
        {
            return LLVMInt1TypeInContext(ctx);
        }
        if (type == PrimitiveType.CHAR)
        {
            return LLVMInt8TypeInContext(ctx);
        }

        if (type == PrimitiveType.BYTE || type == PrimitiveType.INT8 || type == PrimitiveType.BYTE || type == PrimitiveType.UINT8) // Added UBYTE, UINT8

            if (type == PrimitiveType.SBYTE || type == PrimitiveType.INT8 || type == PrimitiveType.SBYTE || type == PrimitiveType.UINT8) // Added UBYTE, UINT8
            {
                return LLVMInt8TypeInContext(ctx);
            }
        if (type == PrimitiveType.SHORT || type == PrimitiveType.INT16 || type == PrimitiveType.USHORT || type == PrimitiveType.UINT16) // Added USHORT, UINT16
        {
            return LLVMInt16TypeInContext(ctx); // Added i16
        }
        if (type == PrimitiveType.INT || type == PrimitiveType.INT32 || type == PrimitiveType.UINT || type == PrimitiveType.UINT32) // Added UINT, UINT32
        {
            return LLVMInt32TypeInContext(ctx);
        }
        if (type == PrimitiveType.LONG || type == PrimitiveType.INT64 || type == PrimitiveType.ULONG || type == PrimitiveType.UINT64) // Added ULONG, UINT64
        {
            return LLVMInt64TypeInContext(ctx); // Added i64
        }

        if (type == PrimitiveType.FLOAT)
        {
            return LLVMFloatTypeInContext(ctx);
        }
        if (type == PrimitiveType.DOUBLE)
        {
            return LLVMDoubleTypeInContext(ctx);
        }

        // Handle string specifically using its canonical name check
        if ("string".equals(type.getName()) || "String".equals(type.getName()))
        { // Check canonical name
            LLVMTypeRef stringStruct = getStringStructTypeForContext(ctx);
            // Return the struct type itself for now, pointers handled at usage site.
            return stringStruct; // New: returning struct type itself
        }

        // If it's not any known primitive or string, maybe it's a struct/class?
        // For now, let's return a void pointer as a placeholder, but log a warning.
        System.err.println("Warning: TypeConverter encountered unknown type: " + type.getName() + ". Returning void pointer (i8*).");

        return LLVMPointerType(LLVMInt8TypeInContext(ctx), 0); // Fallback placeholder
    }

    // Context-safe wrappers
    private static LLVMTypeRef LLVMVoidTypeInContext(LLVMContextRef ctx)
    {
        return LLVMVoidType();
    }

    private static LLVMTypeRef LLVMInt1TypeInContext(LLVMContextRef ctx)
    {
        return LLVMInt1Type();
    }

    private static LLVMTypeRef LLVMInt8TypeInContext(LLVMContextRef ctx)
    {
        return LLVMInt8Type();
    }

    // NEW wrapper for i16
    private static LLVMTypeRef LLVMInt16TypeInContext(LLVMContextRef ctx)
    {
        return LLVMInt16Type();
    }

    private static LLVMTypeRef LLVMInt32TypeInContext(LLVMContextRef ctx)
    {
        return LLVMInt32Type();
    }

    // NEW wrapper for i64
    private static LLVMTypeRef LLVMInt64TypeInContext(LLVMContextRef ctx)
    {
        return LLVMInt64Type();
    }

    private static LLVMTypeRef LLVMFloatTypeInContext(LLVMContextRef ctx)
    {
        return LLVMFloatType();
    }

    private static LLVMTypeRef LLVMDoubleTypeInContext(LLVMContextRef ctx)
    {
        return LLVMDoubleType();
    }

    /**
     * Helper to convert a non-boolean LLVMValueRef into an i1 boolean value.
     * This is crucial for LLVMBuildCondBr.
     */
    public static LLVMValueRef toBoolean(LLVMValueRef value, NebulaParser.ExpressionContext ctx, LLVMContextRef moduleContext, LLVMBuilderRef builder)
    {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        LLVMTypeRef i1Type = LLVMInt1TypeInContext(moduleContext);

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
            Debug.logWarning("IR Warning: Conditional expression resulted in unhandled type kind: " + LLVMGetTypeKind(valueType) + " in " + ctx.getText());
            // Treat it as true (safest default for a potential error)
            return LLVMConstInt(i1Type, 1, 0);
        }
    }
}