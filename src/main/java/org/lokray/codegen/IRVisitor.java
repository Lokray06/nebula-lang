package org.lokray.codegen;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;
import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.semantic.SemanticAnalyzer;
import org.lokray.semantic.info.SimplifiedForInfo;
import org.lokray.semantic.info.TraditionalForInfo;
import org.lokray.semantic.symbol.ClassSymbol;
import org.lokray.semantic.symbol.MethodSymbol;
import org.lokray.semantic.symbol.Symbol;
import org.lokray.semantic.symbol.VariableSymbol;
import org.lokray.semantic.type.ArrayType;
import org.lokray.semantic.type.ClassType;
import org.lokray.semantic.type.PrimitiveType;
import org.lokray.semantic.type.Type;
import org.lokray.util.Debug;

import java.math.BigInteger;
import java.util.*;

import static org.bytedeco.llvm.global.LLVM.*;

public class IRVisitor extends NebulaParserBaseVisitor<LLVMValueRef>
{

	private final SemanticAnalyzer semanticAnalyzer;
	private final Stack<Map<String, LLVMValueRef>> scopedValues = new Stack<>();
	private final Map<String, LLVMValueRef> namedValues = new HashMap<>();
	private LLVMValueRef currentFunction;
	private LLVMBuilderRef builder;
	private final LLVMModuleRef module;

	// Stack for tracking loop exit blocks (for break/continue)
	private final Stack<LLVMBasicBlockRef> loopExitBlocks = new Stack<>();
	private final Stack<LLVMBasicBlockRef> loopUpdateBlocks = new Stack<>(); // For continue

	/**
	 * Creates an IRVisitor that operates on the LLVM global context.
	 * It creates its own module and builder.
	 */
	public IRVisitor(SemanticAnalyzer semanticAnalyzer)
	{
		this.semanticAnalyzer = semanticAnalyzer;
		this.builder = LLVMCreateBuilder();
		this.module = LLVMModuleCreateWithName("nebula_module");

		BytePointer triple = LLVMGetDefaultTargetTriple();
		LLVMTargetRef target = new LLVMTargetRef();
		BytePointer error = new BytePointer((Pointer) null);

		if (LLVMGetTargetFromTriple(triple, target, error) != 0)
		{
			Debug.logError("Failed to get LLVM target from triple: " + error.getString());
			LLVMDisposeMessage(error);
		}
		else
		{
			LLVMTargetMachineRef machine = LLVMCreateTargetMachine(
					target,
					triple,
					new BytePointer("generic"),
					new BytePointer(""),
					LLVMCodeGenLevelDefault,
					LLVMRelocDefault,
					LLVMCodeModelDefault
			);

			// 1. Create the Data Layout object (you did this)
			LLVMTargetDataRef dataLayoutRef = LLVMCreateTargetDataLayout(machine);

			// 2. Get the string representation from the object
			BytePointer dataLayoutString = LLVMCopyStringRepOfTargetData(dataLayoutRef);

			// 3. Set the layout on the module using the string
			LLVMSetDataLayout(module, dataLayoutString);

			// 4. Clean up ALL resources
			LLVMDisposeMessage(dataLayoutString); // Free the string copy
			LLVMDisposeTargetData(dataLayoutRef); // Free the data layout object
			LLVMDisposeTargetMachine(machine);    // Free the target machine
		}
		LLVMDisposeMessage(triple);

		// Declare 'malloc' for heap allocation (for 'new' keyword)
		// i8* malloc(i64 size)
		LLVMTypeRef i8PtrType = LLVMPointerType(LLVMInt8Type(), 0);
		LLVMTypeRef i64Type = LLVMInt64Type(); // size_t is i64 on 64-bit systems
		LLVMTypeRef mallocFuncType = LLVMFunctionType(i8PtrType, new PointerPointer<>(new LLVMTypeRef[]{i64Type}), 1, 0);
		LLVMAddFunction(module, "malloc", mallocFuncType);

		scopedValues.push(new HashMap<>());
	}

	@Override
	public LLVMValueRef visitTypeDeclaration(NebulaParser.TypeDeclarationContext ctx)
	{
		// 1. Get the ClassSymbol
		Optional<Symbol> classSymbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
		if (classSymbolOpt.isEmpty() || !(classSymbolOpt.get() instanceof ClassSymbol classSymbol))
		{
			Debug.logError("IR: Could not find ClassSymbol for TypeDeclaration: " + ctx.ID().getText());
			return null;
		}

		// 2. Create the static initializer function (e.g., Vector3.__cctor)
		String cctorName = classSymbol.getMangledName() + ".__cctor";
		LLVMTypeRef voidType = LLVMVoidType();
		// --- FIX: Use (PointerPointer) null for unchecked warning ---
		LLVMTypeRef cctorType = LLVMFunctionType(voidType, (PointerPointer) null, 0, 0);
		LLVMValueRef cctorFunc = LLVMAddFunction(module, cctorName, cctorType);
		LLVMSetLinkage(cctorFunc, LLVMPrivateLinkage); // Internal function

		LLVMBasicBlockRef entry = LLVMAppendBasicBlock(cctorFunc, "entry");
		LLVMPositionBuilderAtEnd(builder, entry);

		// 1. Pre-define all static fields as LLVM Global Variables (initial value: null)
		if (ctx.typeMember() != null)
		{
			for (NebulaParser.TypeMemberContext member : ctx.typeMember())
			{
				if (member.fieldDeclaration() != null)
				{
					visitStaticFieldDefinition(member.fieldDeclaration());
				}
			}
		}

		// 2. Set up for __cctor (Constructor execution)
		LLVMValueRef oldFunction = currentFunction;
		currentFunction = cctorFunc;
		// Don't modify namedValues/outerValues here; the globals are already permanent.
		pushScope(); // Only push scope for local variables/params within cctor

		// 3. Generate STORE instructions for static field initializers inside the __cctor
		if (ctx.typeMember() != null)
		{
			for (NebulaParser.TypeMemberContext member : ctx.typeMember())
			{
				if (member.fieldDeclaration() != null)
				{
					// Now, we only generate the STORE instructions inside the cctor
					visitStaticFieldInitializerStore(member.fieldDeclaration());
				}
			}
		}

		// 4. Add implicit return and restore context
		LLVMBuildRetVoid(builder);
		popScope();
		// No need to restore namedValues/outerValues, as they weren't cleared/saved.
		currentFunction = oldFunction;

		// 6. Visit all normal methods and constructors (NOT in the cctor)
		if (ctx.typeMember() != null)
		{
			for (NebulaParser.TypeMemberContext member : ctx.typeMember())
			{
				if (member.methodDeclaration() != null)
				{
					visit(member.methodDeclaration());
				}
				else if (member.constructorDeclaration() != null)
				{
					visit(member.constructorDeclaration());
				}

				// We skip fieldDeclarations here because they are either handled
				// by the __cctor (static) or are just part of the struct layout (instance)
			}
		}

		// 7. Add this cctor to @llvm.global_ctors to run before main
		LLVMTypeRef i32Type = LLVMInt32Type();
		LLVMTypeRef cctorFuncPtrType = LLVMPointerType(cctorType, 0);
		// struct { i32, void()*, i8* }
		// --- FIX: Use PointerPointer for LLVMStructType ---
		LLVMTypeRef[] ctorStructElems = {i32Type, cctorFuncPtrType, LLVMPointerType(LLVMInt8Type(), 0)};
		LLVMTypeRef ctorStructType = LLVMStructType(new PointerPointer<>(ctorStructElems), 3, 0);


		LLVMValueRef[] ctorStructFields = {
				LLVMConstInt(i32Type, 65535, 0), // Priority (65535 is default for C++ ctors)
				cctorFunc,
				LLVMConstNull(LLVMPointerType(LLVMInt8Type(), 0)) // Associated data (null)
		};
		// --- FIX: Use PointerPointer for LLVMConstStruct and add 'packed' argument ---
		LLVMValueRef ctorStruct = LLVMConstStruct(new PointerPointer<>(ctorStructFields), 3, 0);

		// Get or create @llvm.global_ctors array
		LLVMValueRef globalCtorsArray = LLVMGetNamedGlobal(module, "llvm.global_ctors");
		if (globalCtorsArray == null)

		{
			LLVMTypeRef arrayType = LLVMArrayType2(ctorStructType, 1);
			globalCtorsArray = LLVMAddGlobal(module, arrayType, "llvm.global_ctors");
			LLVMSetLinkage(globalCtorsArray, LLVMAppendingLinkage);

			// --- FIX: Wrap the single ctorStruct in an array ---
			LLVMValueRef[] ctorArray = {ctorStruct};
			LLVMSetInitializer(globalCtorsArray, LLVMConstArray(ctorStructType, new PointerPointer<>(ctorArray), 1));
		}
		else
		{
			// This logic is simplified. A robust implementation would read the existing
			// array, append to it, and create a new global. For now, we assume we're the only one.
			// A simple (but technically incorrect) append:
			LLVMTypeRef existingArrayType = LLVMGetElementType(LLVMTypeOf(globalCtorsArray));
			// --- FIX: Use LLVMGetArrayLength2 (non-deprecated) ---
			long existingSize = LLVMGetArrayLength2(existingArrayType);
			LLVMTypeRef newArrayType = LLVMArrayType2(ctorStructType, existingSize + 1);

			// This is complex. Let's just create a new global with a unique name if it exists.
			// The simple case (first one) is fine for now.
			Debug.logDebug("IR: Adding to @llvm.global_ctors: " + cctorName);
		}

		return null;
	}

	// 1. New Helper: Define the LLVM Global Variable for a static field
	// Place this anywhere outside of a class, perhaps right before visitStaticFieldInitializerStore
	private void visitStaticFieldDefinition(NebulaParser.FieldDeclarationContext ctx)
	{
		Debug.logDebug("Visiting static field declaration: " + ctx.getText());

		boolean isStatic = ctx.modifiers() != null && ctx.modifiers().getText().contains("static");

		if (!isStatic)
		{
			return;
		}

		Optional<Type> nebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.type());
		if (nebulaTypeOpt.isEmpty())
		{
			return;
		}
		Type nebulaType = nebulaTypeOpt.get();
		LLVMTypeRef varLLVMType = TypeConverter.toLLVMType(nebulaType); // Should be the Pointer type (%Vector3*)

		for (NebulaParser.VariableDeclaratorContext declarator : ctx.variableDeclarator())
		{
			String varName = declarator.ID().getText();
			Optional<Symbol> varSymbolOpt = semanticAnalyzer.getResolvedSymbol(declarator);
			if (varSymbolOpt.isEmpty() || !(varSymbolOpt.get() instanceof VariableSymbol varSymbol))
			{
				Debug.logError("IR: Could not find symbol for static var: " + varName);
				continue;
			}
			String mangledName = varSymbol.getMangledName();

			// Create the LLVMAddGlobal for the static field
			LLVMValueRef globalVar = LLVMAddGlobal(module, varLLVMType, mangledName);
			LLVMSetInitializer(globalVar, LLVMConstNull(varLLVMType)); // Initialize to NULL (a constant)
			LLVMSetLinkage(globalVar, LLVMCommonLinkage);

			// Add to the base scope permanently so it's always accessible to all functions
			// Assumes scopedValues.get(0) is the base scope.
			scopedValues.get(0).put(mangledName, globalVar);
			namedValues.put(mangledName, globalVar);
		}
	}

	// 2. Modified Helper: Only generate the STORE instruction for the initializer
	// Rename your existing visitStaticFieldInitializer to this:
	private void visitStaticFieldInitializerStore(NebulaParser.FieldDeclarationContext ctx)
	{
		boolean isStatic = ctx.modifiers() != null && ctx.modifiers().getText().contains("static");
		if (!isStatic)
		{
			Debug.logDebug("visitStaticFieldInitializerStore() -> Exited early because isStatic is false: " + ctx.getText());
			return;
		}

		for (NebulaParser.VariableDeclaratorContext declarator : ctx.variableDeclarator())
		{
			if (declarator.expression() == null)
			{
				// Only process if an initializer exists
				continue;
			}

			Optional<Symbol> varSymbolOpt = semanticAnalyzer.getResolvedSymbol(declarator);
			VariableSymbol varSymbol = (VariableSymbol) varSymbolOpt.orElse(null);
			if (varSymbol == null)
			{
				Debug.logDebug("visitStaticFieldInitializerStore() -> Exited early, couldn't resolve the corresponding Variable symbol: " + ctx.getText());
				continue;
			}
			String mangledName = varSymbol.getMangledName();

			// Retrieve the global variable which was defined in visitStaticFieldDefinition
			LLVMValueRef globalVar = namedValues.get(mangledName);
			if (globalVar == null)
			{
				Debug.logDebug("visitStaticFieldInitializerStore() -> failed to retrieve the global var " + mangledName + " from the namedValues map.");
				continue;
			}

			// Generate IR for the initializer (e.g., new Vector3())
			LLVMValueRef initVal = visit(declarator.expression());
			if (initVal == null)
			{
				continue;
			}

			// Store the result of the initializer (the new pointer) into the global pointer
			LLVMBuildStore(builder, initVal, globalVar);
			Debug.logDebug("IR: Initialized static field: " + mangledName);
		}
	}

	@Override
	public LLVMValueRef visitMethodDeclaration(NebulaParser.MethodDeclarationContext ctx)
	{
		// 1. Resolve the MethodSymbol
		Optional<Symbol> symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
		if (symbolOpt.isEmpty() || !(symbolOpt.get() instanceof MethodSymbol methodSymbol))
		{
			Debug.logError("IR: Method declaration symbol not found or is not a MethodSymbol: " + ctx.getText());
			return null;
		}

		String methodName;
		if (methodSymbol.isMainMethod())
		{
			methodName = methodSymbol.getName();
		}
		else
		{
			methodName = methodSymbol.getMangledName();
		}
		Debug.logDebug("IR: Defining function: " + methodName);

		// 2. Create or get the function prototype (include 'this' for instance methods)
		LLVMValueRef function = LLVMGetNamedFunction(module, methodName);
		if (function == null)
		{
			List<Type> paramTypes = methodSymbol.getParameterTypes();
			List<LLVMTypeRef> llvmParamTypeList = new ArrayList<>();

			// Add 'this' as first parameter if instance method
			if (!methodSymbol.isStatic())
			{
				Type thisType = ((ClassSymbol) methodSymbol.getEnclosingScope()).getType();
				LLVMTypeRef thisLLVMType = TypeConverter.toLLVMType(thisType);
				llvmParamTypeList.add(thisLLVMType);
			}

			// Add remaining parameters
			for (Type paramType : paramTypes)
			{
				llvmParamTypeList.add(TypeConverter.toLLVMType(paramType));
			}

			LLVMTypeRef[] llvmParamTypes = llvmParamTypeList.toArray(new LLVMTypeRef[0]);
			LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType());
			LLVMTypeRef functionType = LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), llvmParamTypeList.size(), 0);
			function = LLVMAddFunction(module, methodName, functionType);

			// If this method is native, make sure it's an external declaration (no body).
			if (methodSymbol.isNative())
			{
				// Mark as external linkage so it remains a declaration.
				LLVMSetLinkage(function, LLVMExternalLinkage);
				Debug.logDebug("IR: Created external declaration for native method: " + methodName);
				return function;
			}
		}
		else
		{
			// If the function already exists but the symbol is native, ensure it's external (no body).
			if (methodSymbol.isNative())
			{
				LLVMSetLinkage(function, LLVMExternalLinkage);
				Debug.logDebug("IR: Found existing function and ensured external linkage for native: " + methodName);
				return function;
			}
		}

		// 3. Mark main method
		if (methodSymbol.isMainMethod())
		{
			Debug.logDebug("IR: Marking as main method: " + methodName);
		}

		// 4. Create entry block and position builder
		LLVMBasicBlockRef entryBlock = LLVMAppendBasicBlock(function, "entry");
		LLVMPositionBuilderAtEnd(builder, entryBlock);

		// 5. Set function context
		LLVMValueRef oldFunction = currentFunction;
		currentFunction = function;
		Map<String, LLVMValueRef> outerValues = new HashMap<>(namedValues);
		namedValues.clear();

		// NEW: Create a new scope for parameters + 'this'
		pushScope();

		// 6. Process parameters (handle 'this' offset)
		int totalParams = methodSymbol.getParameterTypes().size() + (methodSymbol.isStatic() ? 0 : 1);
		PointerPointer<LLVMValueRef> params = new PointerPointer<>(totalParams);
		LLVMGetParams(function, params);

		int paramOffset = 0;
		if (!methodSymbol.isStatic())
		{
			LLVMValueRef thisPtr = params.get(LLVMValueRef.class, 0);
			Type thisType = ((ClassSymbol) methodSymbol.getEnclosingScope()).getType();
			LLVMTypeRef thisLLVMType = TypeConverter.toLLVMType(thisType);

			LLVMSetValueName2(thisPtr, new BytePointer("this.ptr"), 8);
			LLVMValueRef thisAlloca = createEntryBlockAlloca(function, thisLLVMType, "this");
			LLVMBuildStore(builder, thisPtr, thisAlloca);
			namedValues.put("this", thisAlloca);
			addVariableToScope("this", thisAlloca);
			paramOffset = 1;
		}

		for (int i = 0; i < methodSymbol.getParameterTypes().size(); i++)
		{
			Type paramNebulaType = methodSymbol.getParameterTypes().get(i);
			String paramName = methodSymbol.getParameters().get(i).getName();
			LLVMTypeRef paramLLVMType = TypeConverter.toLLVMType(paramNebulaType);
			LLVMValueRef incomingValue = params.get(LLVMValueRef.class, i + paramOffset);

			LLVMSetValueName2(incomingValue, new BytePointer(paramName + ".ptr"), paramName.length() + 4);
			LLVMValueRef alloca = createEntryBlockAlloca(function, paramLLVMType, paramName);
			LLVMBuildStore(builder, incomingValue, alloca);
			namedValues.put(paramName, alloca);
			addVariableToScope(paramName, alloca);
		}

		// 7. Visit the method body if present (non-native methods)
		if (ctx.block() != null)
		{
			visit(ctx.block());
		}

		// 8. Add implicit return if needed
		LLVMBasicBlockRef lastBlock = LLVMGetLastBasicBlock(function);
		if (LLVMGetBasicBlockTerminator(lastBlock) == null)
		{
			Type returnType = methodSymbol.getType();
			if (returnType == PrimitiveType.VOID)
			{
				LLVMBuildRetVoid(builder);
			}
			else if (methodSymbol.isMainMethod())
			{
				LLVMBuildRet(builder, LLVMConstInt(TypeConverter.toLLVMType(returnType), 0, 0));
			}
			else
			{
				Debug.logWarning("IR: Non-void method " + methodName + " missing return. Building unreachable.");
				LLVMBuildUnreachable(builder);
			}
		}

		// 9. Restore previous context
		popScope();
		namedValues.clear();
		namedValues.putAll(outerValues);
		currentFunction = oldFunction;

		return function;
	}

	@Override
	public LLVMValueRef visitConstructorDeclaration(NebulaParser.ConstructorDeclarationContext ctx)
	{
		// 1. Resolve the MethodSymbol (constructors are stored as MethodSymbols)
		Optional<Symbol> symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
		if (symbolOpt.isEmpty())
		{
			Debug.logError("IR: Error retrieving the method symbol for: " + ctx.getText());
			return null;
		}
		if (!(symbolOpt.get() instanceof MethodSymbol methodSymbol))
		{
			Debug.logError("IR: Constructor declaration symbol is not a MethodSymbol: " + ctx.getText());
			return null;
		}

		String methodName = methodSymbol.getMangledName();
		Debug.logDebug("IR: Defining constructor: " + methodName);

		// 2. Get the function prototype (it includes 'this' as the first param)
		LLVMValueRef function = LLVMGetNamedFunction(module, methodName);
		if (function == null)
		{
			List<Type> paramTypes = methodSymbol.getParameterTypes();
			List<LLVMTypeRef> llvmParamTypeList = new ArrayList<>();

			// Add 'this' as first parameter
			Type thisType = ((ClassSymbol) methodSymbol.getEnclosingScope()).getType();
			LLVMTypeRef thisLLVMType = TypeConverter.toLLVMType(thisType);
			llvmParamTypeList.add(thisLLVMType); // <-- 'this' is param 0

			// Add remaining parameters
			for (Type paramType : paramTypes)
			{
				llvmParamTypeList.add(TypeConverter.toLLVMType(paramType));
			}

			LLVMTypeRef[] llvmParamTypes = llvmParamTypeList.toArray(new LLVMTypeRef[0]);
			LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType()); // Should be void
			LLVMTypeRef functionType = LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), llvmParamTypeList.size(), 0);
			function = LLVMAddFunction(module, methodName, functionType);
		}

		// 3. Create entry block and position builder
		LLVMBasicBlockRef entryBlock = LLVMAppendBasicBlock(function, "entry");
		LLVMPositionBuilderAtEnd(builder, entryBlock);

		// 4. Set function context
		LLVMValueRef oldFunction = currentFunction;
		currentFunction = function;
		Map<String, LLVMValueRef> outerValues = new HashMap<>(namedValues);
		namedValues.clear();
		pushScope();

		// 5. Process parameters (handle 'this' offset)
		int totalParams = methodSymbol.getParameterTypes().size() + 1; // +1 for 'this'
		PointerPointer<LLVMValueRef> params = new PointerPointer<>(totalParams);
		LLVMGetParams(function, params);

		// Param 0 is 'this'
		LLVMValueRef thisPtr = params.get(LLVMValueRef.class, 0); // This is the %Vector3* from malloc
		Type thisType = ((ClassSymbol) methodSymbol.getEnclosingScope()).getType();
		LLVMTypeRef thisLLVMType = TypeConverter.toLLVMType(thisType);

		LLVMSetValueName2(thisPtr, new BytePointer("this.ptr"), 8);

		// Create an alloca for 'this' and store the incoming pointer in it.
		// This makes 'this' behave just like any other local variable.
		LLVMValueRef thisAlloca = createEntryBlockAlloca(function, thisLLVMType, "this");
		LLVMBuildStore(builder, thisPtr, thisAlloca);
		namedValues.put("this", thisAlloca);
		addVariableToScope("this", thisAlloca);

		// Other params start at index 1
		for (int i = 0; i < methodSymbol.getParameterTypes().size(); i++)
		{
			Type paramNebulaType = methodSymbol.getParameterTypes().get(i);
			String paramName = methodSymbol.getParameters().get(i).getName();
			LLVMTypeRef paramLLVMType = TypeConverter.toLLVMType(paramNebulaType);
			LLVMValueRef incomingValue = params.get(LLVMValueRef.class, i + 1); // +1 offset

			LLVMSetValueName2(incomingValue, new BytePointer(paramName + ".ptr"), paramName.length() + 4);
			LLVMValueRef alloca = createEntryBlockAlloca(function, paramLLVMType, paramName);
			LLVMBuildStore(builder, incomingValue, alloca);
			namedValues.put(paramName, alloca);
			addVariableToScope(paramName, alloca);
		}

		// 6. Visit the method body
		if (ctx.block() != null)
		{
			visit(ctx.block());
		}

		// 7. Add implicit return (constructors return this)
		if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
		{
			// --- FIX: Return the 'this' parameter, not void ---
			// 'thisParam' was captured at the start of this method
			Debug.logDebug("IR: Adding implicit 'return this' to constructor.");
			LLVMBuildRet(builder, thisPtr);
		}

		// 8. Restore previous context
		popScope();
		namedValues.clear();
		namedValues.putAll(outerValues);
		currentFunction = oldFunction;

		return function;
	}

	@Override
	public LLVMValueRef visitPowerExpression(NebulaParser.PowerExpressionContext ctx)
	{
		if (ctx.unaryExpression().size() == 1)
		{
			return visit(ctx.unaryExpression(0));
		}

		LLVMValueRef leftVal = visit(ctx.unaryExpression(0));

		// Get the final expected type for the whole expression
		Optional<org.lokray.semantic.type.Type> resultTypeOpt = semanticAnalyzer.getResolvedType(ctx);
		if (resultTypeOpt.isEmpty() || !resultTypeOpt.get().isNumeric())
		{
			org.lokray.util.Debug.logError("IR: Power expression type not found or not numeric.");
			return null;
		}
		LLVMTypeRef resultLLVMType = org.lokray.codegen.TypeConverter.toLLVMType(resultTypeOpt.get());

		// Cast left operand to the final result type
		LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "pow_lhs_cast");

		// NOTE: LLVM does not have a built-in power instruction.
		// We must call the C standard library function 'pow' (usually for double).

		if (LLVMGetTypeKind(resultLLVMType) != LLVMDoubleTypeKind)
		{
			org.lokray.util.Debug.logWarning("IR: Power operator (**) is only implemented for double, falling back to basic implementation for: " + resultTypeOpt.get().getName());
			// For simplicity, we only implement the left side for now.
			return leftCasted;
		}

		// Ensure 'pow' function is declared
		LLVMValueRef powFunc = LLVMGetNamedFunction(module, "pow");
		if (powFunc == null)
		{
			LLVMTypeRef doubleType = LLVMDoubleType();
			LLVMTypeRef[] paramTypes = {doubleType, doubleType};
			LLVMTypeRef funcType = LLVMFunctionType(doubleType, new org.bytedeco.javacpp.PointerPointer<>(paramTypes), 2, 0);
			powFunc = LLVMAddFunction(module, "pow", funcType);
		}


		for (int i = 1; i < ctx.unaryExpression().size(); i++)
		{
			LLVMValueRef rightVal = visit(ctx.unaryExpression(i));
			if (leftCasted == null || rightVal == null)
			{
				return null;
			}

			// Cast right operand to double (the expected type for pow)
			LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "pow_rhs_cast");

			// Build the function call: pow(leftCasted, rightCasted)
			LLVMValueRef[] args = {leftCasted, rightCasted};
			LLVMTypeRef powFuncType = LLVMTypeOf(powFunc);
			LLVMValueRef powResult = LLVMBuildCall2(builder, powFuncType, powFunc, new PointerPointer<>(args), 2, "pow_tmp");

			// The result of pow becomes the new left operand for the next exponentiation
			leftCasted = powResult;
		}

		return leftCasted;
	}

	@Override
	public LLVMValueRef visitForStatement(NebulaParser.ForStatementContext ctx)
	{
		Optional<Object> loopInfoOpt = semanticAnalyzer.getResolvedInfo(ctx);

		if (loopInfoOpt.isEmpty())
		{
			Debug.logError("Codegen Error: No valid loop information found for ForStatementContext: " + ctx.getText());
			return null;
		}
		Object loopInfo = loopInfoOpt.get();

		if (loopInfo instanceof SimplifiedForInfo info)
		{
			// SIMPLIFIED FOR LOOP
			Debug.logDebug("Codegen: Simplified for loop (using Semantic Info): " + ctx.getText());
			VariableSymbol loopVarSymbol = info.loopVariable();
			String varName = loopVarSymbol.getName();
			Type loopVarNebulaType = loopVarSymbol.getType();
			LLVMTypeRef varType = TypeConverter.toLLVMType(loopVarNebulaType);
			LLVMValueRef function = currentFunction;

			LLVMValueRef startVal;
			if (info.startExpression() != null)
			{
				startVal = visit(info.startExpression());
			}
			else
			{
				startVal = LLVMConstInt(varType, 0, 0);
			}
			if (startVal == null)
			{ /* ... error handling ... */
				return null;
			}

			LLVMValueRef varAlloca = createEntryBlockAlloca(function, varType, varName);
			LLVMBuildStore(builder, startVal, varAlloca); // Initialize

			Map<String, LLVMValueRef> outerValues = new HashMap<>(namedValues); // Backup scope
			namedValues.put(varName, varAlloca);

			// Create Blocks
			LLVMBasicBlockRef loopHeaderBlock = LLVMAppendBasicBlock(function, "loop.header");
			LLVMBasicBlockRef loopBodyBlock = LLVMAppendBasicBlock(function, "loop.body");
			LLVMBasicBlockRef loopExitBlock = LLVMAppendBasicBlock(function, "loop.exit");

			LLVMBuildBr(builder, loopHeaderBlock); // Branch to Header

			// Populate Header
			LLVMPositionBuilderAtEnd(builder, loopHeaderBlock);
			LLVMValueRef currentVal = LLVMBuildLoad2(builder, varType, varAlloca, varName + ".load");
			LLVMValueRef limitVal = visit(info.limitExpression());
			if (limitVal == null)
			{ /* ... error handling ... */
				namedValues.clear();
				namedValues.putAll(outerValues);
				return null;
			}

			// Comparison
			int llvmPredicate;
			// Determine signedness from loop variable type
			boolean isSigned = !loopVarNebulaType.getName().startsWith("u"); // Check if the Nebula type name starts with 'u'
			String operator = info.operator().getText();
			switch (operator)
			{
				case "<":
					llvmPredicate = isSigned ? LLVMIntSLT : LLVMIntULT;
					break;
				case ">":
					llvmPredicate = isSigned ? LLVMIntSGT : LLVMIntUGT;
					break;
				case "<=":
					llvmPredicate = isSigned ? LLVMIntSLE : LLVMIntULE;
					break;
				case ">=":
					llvmPredicate = isSigned ? LLVMIntSGE : LLVMIntUGE;
					break;
				default: /* ... error handling ... */
					namedValues.clear();
					namedValues.putAll(outerValues);
					return null;
			}
			LLVMValueRef condition = LLVMBuildICmp(builder, llvmPredicate, currentVal, limitVal, "loop.cond");
			LLVMBuildCondBr(builder, condition, loopBodyBlock, loopExitBlock);

			// Populate Body
			LLVMPositionBuilderAtEnd(builder, loopBodyBlock);
			pushScope();
			visit(ctx.block());
			popScope();

			// Increment or Decrement
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{
				LLVMValueRef currentValForIncrement = LLVMBuildLoad2(builder, varType, varAlloca, varName + ".load.inc");

				// Determine increment direction based on comparison operator
				long stepSign = switch (operator)
				{
					case "<", "<=" -> +1;   // counting up
					case ">", ">=" -> -1;   // counting down
					default -> +1;           // fallback, shouldn't happen
				};

				LLVMValueRef stepVal = LLVMConstInt(varType, Math.abs(stepSign), 0);

				LLVMValueRef nextVal;
				if (stepSign > 0)
				{
					nextVal = LLVMBuildAdd(builder, currentValForIncrement, stepVal, varName + ".inc");
				}
				else
				{
					nextVal = LLVMBuildSub(builder, currentValForIncrement, stepVal, varName + ".dec");
				}

				LLVMBuildStore(builder, nextVal, varAlloca);
				LLVMBuildBr(builder, loopHeaderBlock);
			}

			// Exit Block
			LLVMPositionBuilderAtEnd(builder, loopExitBlock);
			namedValues.clear(); // Restore outer scope
			namedValues.putAll(outerValues);

			Debug.logDebug("Codegen: Finished simplified for loop (using Semantic Info): " + ctx.getText());
			return null;

		}
		else if (loopInfo instanceof TraditionalForInfo info)
		{
			// TRADITIONAL FOR LOOP
			// ... (existing traditional for loop codegen remains the same) ...
			Debug.logDebug("Codegen: Traditional for loop (using Semantic Info): " + ctx.getText());
			LLVMValueRef function = currentFunction;
			Map<String, LLVMValueRef> outerValues = new HashMap<>(namedValues);

			// 1. Initializer
			if (info.initializer() != null)
			{
				if (info.initializer() instanceof NebulaParser.VariableDeclarationContext varDeclCtx)
				{
					visit(varDeclCtx);
				}
				else if (info.initializer() instanceof NebulaParser.ExpressionContext exprCtx)
				{
					visit(exprCtx); // Execute initializer expression
				}
			}

			// 2. Create Blocks
			LLVMBasicBlockRef loopHeaderBlock = LLVMAppendBasicBlock(function, "for.cond");
			LLVMBasicBlockRef loopBodyBlock = LLVMAppendBasicBlock(function, "for.body");
			LLVMBasicBlockRef loopUpdateBlock = LLVMAppendBasicBlock(function, "for.update");
			LLVMBasicBlockRef loopExitBlock = LLVMAppendBasicBlock(function, "for.exit");

			LLVMBuildBr(builder, loopHeaderBlock); // Branch to Header

			// 4. Populate Header Block
			LLVMPositionBuilderAtEnd(builder, loopHeaderBlock);
			LLVMValueRef conditionValue;
			if (info.condition() != null)
			{
				conditionValue = visit(info.condition());
				if (conditionValue == null)
				{ /* ... error handling ... */
					namedValues.clear();
					namedValues.putAll(outerValues);
					return null;
				}
				Optional<Type> condNebulaTypeOpt = semanticAnalyzer.getResolvedType(info.condition());
				if (condNebulaTypeOpt.isPresent() && condNebulaTypeOpt.get() == PrimitiveType.BOOLEAN)
				{
					// It's already i1, use directly
				}
				else
				{
					LLVMTypeRef condLLVMType = LLVMTypeOf(conditionValue);
					if (LLVMGetTypeKind(condLLVMType) == LLVMIntegerTypeKind)
					{
						LLVMValueRef zero = LLVMConstNull(condLLVMType);
						conditionValue = LLVMBuildICmp(builder, LLVMIntNE, conditionValue, zero, "tobool");
					}
					else
					{
						LLVMValueRef zero = LLVMConstNull(condLLVMType);
						if (LLVMGetTypeKind(condLLVMType) == LLVMFloatTypeKind || LLVMGetTypeKind(condLLVMType) == LLVMDoubleTypeKind)
						{
							conditionValue = LLVMBuildFCmp(builder, LLVMRealONE, conditionValue, zero, "tobool_fp");
						}
						else
						{
							conditionValue = LLVMBuildICmp(builder, LLVMIntNE, conditionValue, zero, "tobool_ptr");
						}
						Debug.logDebug("Codegen Warning: Condition in traditional for loop isn't bool/int. Using != zero/null comparison.");
					}
				}
			}
			else
			{
				conditionValue = LLVMConstInt(LLVMInt1Type(), 1, 0);
			}
			LLVMBuildCondBr(builder, conditionValue, loopBodyBlock, loopExitBlock); // Branch

			// 5. Populate Body Block
			LLVMPositionBuilderAtEnd(builder, loopBodyBlock);
			visit(ctx.block()); // Visit loop body
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{ // Branch to update if not terminated
				LLVMBuildBr(builder, loopUpdateBlock);
			}

			// 6. Populate Update Block
			LLVMPositionBuilderAtEnd(builder, loopUpdateBlock);
			if (info.update() != null)
			{
				visit(info.update()); // Generate update code from info
			}
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{ // Branch to header if not terminated
				LLVMBuildBr(builder, loopHeaderBlock);
			}

			// 7. Position Builder at Exit Block
			LLVMPositionBuilderAtEnd(builder, loopExitBlock);
			namedValues.clear(); // Restore outer scope
			namedValues.putAll(outerValues);
			Debug.logDebug("Codegen: Finished traditional for loop (using Semantic Info): " + ctx.getText());
			return null;
		}
		else
		{
			Debug.logError("Codegen Error: No valid loop information found for ForStatementContext: " + ctx.getText());
			return null;
		}
	}

	@Override
	public LLVMValueRef visitForeachStatement(NebulaParser.ForeachStatementContext ctx)
	{
		// 1. Get loop variable info from semantic pass
		Optional<Symbol> loopVarSymbolOpt = semanticAnalyzer.getResolvedSymbol(ctx.ID());
		Optional<Type> loopVarNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.ID());
		if (loopVarSymbolOpt.isEmpty() || loopVarNebulaTypeOpt.isEmpty() || !(loopVarSymbolOpt.get() instanceof VariableSymbol loopVarSymbol))
		{
			Debug.logError("IR Error: Could not resolve foreach loop variable symbol: " + ctx.ID().getText());
			return null;
		}
		String loopVarName = loopVarSymbol.getName();
		Type loopVarNebulaType = loopVarNebulaTypeOpt.get();
		LLVMTypeRef loopVarLLVMType = TypeConverter.toLLVMType(loopVarNebulaType);

		// 2. Get collection expression (this is the *pointer* to the descriptor)
		LLVMValueRef collectionDescPtr = visit(ctx.expression());
		Optional<Type> collectionNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.expression());
		if (collectionDescPtr == null || collectionNebulaTypeOpt.isEmpty() || !(collectionNebulaTypeOpt.get() instanceof ArrayType collectionArrayType))
		{
			Debug.logError("IR Error: Foreach collection is not a valid array: " + ctx.expression().getText());
			return null;
		}

		// Get the LLVM type of the *elements* in the collection
		Type elementNebulaType = collectionArrayType.getElementType();
		LLVMTypeRef elementLLVMType = TypeConverter.toLLVMType(elementNebulaType);

		// 3. Load descriptor and extract size/data pointer
		LLVMTypeRef arrayDescType = TypeConverter.getArrayDescStructType();
		LLVMValueRef descStruct = LLVMBuildLoad2(builder, arrayDescType, collectionDescPtr, "foreach.desc.load");
		LLVMValueRef dataPtrI8 = LLVMBuildExtractValue(builder, descStruct, 0, "foreach.data.ptr.i8");
		LLVMValueRef arraySize = LLVMBuildExtractValue(builder, descStruct, 1, "foreach.size");

		// 4. Cast i8* data pointer to correct element pointer type (e.g., i32* or %nebula_string*)
		LLVMTypeRef elementPtrType = LLVMPointerType(elementLLVMType, 0);
		LLVMValueRef dataPtrTyped = LLVMBuildBitCast(builder, dataPtrI8, elementPtrType, "foreach.data.ptr.typed");

		// 5. Create loop counter (alloca for 'i')
		LLVMValueRef function = currentFunction;
		LLVMTypeRef i32Type = LLVMInt32Type();
		LLVMValueRef counterAlloca = createEntryBlockAlloca(function, i32Type, "foreach.i");
		LLVMBuildStore(builder, LLVMConstInt(i32Type, 0, 0), counterAlloca); // i = 0

		// 6. Create loop blocks
		LLVMBasicBlockRef loopHeaderBlock = LLVMAppendBasicBlock(function, "foreach.header");
		LLVMBasicBlockRef loopBodyBlock = LLVMAppendBasicBlock(function, "foreach.body");
		LLVMBasicBlockRef loopExitBlock = LLVMAppendBasicBlock(function, "foreach.exit");

		LLVMBuildBr(builder, loopHeaderBlock); // Jump to header

		// 7. Populate Header (Condition check: i < size)
		LLVMPositionBuilderAtEnd(builder, loopHeaderBlock);
		LLVMValueRef currentCounter = LLVMBuildLoad2(builder, i32Type, counterAlloca, "foreach.i.load");
		LLVMValueRef condition = LLVMBuildICmp(builder, LLVMIntULT, currentCounter, arraySize, "foreach.cond"); // Use unsigned compare
		LLVMBuildCondBr(builder, condition, loopBodyBlock, loopExitBlock);

		// 8. Populate Body
		LLVMPositionBuilderAtEnd(builder, loopBodyBlock);
		pushScope(); // New scope for the loop variable

		// 8a. Get element address: elementPtr = &dataPtrTyped[i]
		// The constructor new PointerPointer<>(LLVMValueRef) is wrong.
		// You must use the varargs constructor that takes an array.
		LLVMValueRef[] indices = {currentCounter};
		LLVMValueRef elementPtr = LLVMBuildGEP2(builder, elementLLVMType, dataPtrTyped, new PointerPointer<>(indices), 1, "foreach.elem.ptr");
		// 8b. Load element value: elementVal = *elementPtr
		LLVMValueRef elementVal = LLVMBuildLoad2(builder, elementLLVMType, elementPtr, "foreach.elem.load");

		// 8c. Create alloca for loop var (e.g., 'num')
		LLVMValueRef loopVarAlloca = createEntryBlockAlloca(function, loopVarLLVMType, loopVarName);

		// 8d. Cast element value to loop var type (e.g. if element is int8 and var is int32)
		LLVMValueRef castedElementVal = buildCast(builder, elementVal, loopVarLLVMType, "foreach.var.cast");
		LLVMBuildStore(builder, castedElementVal, loopVarAlloca); // num = elementVal

		// 8e. Add loop var alloca to scope so visitPrimary can find it
		addVariableToScope(loopVarName, loopVarAlloca);

		// 8f. Visit the loop body statement
		visit(ctx.statement());

		popScope(); // End loop variable's scope

		// 8g. Increment counter: i = i + 1
		LLVMValueRef nextCounter = LLVMBuildAdd(builder, currentCounter, LLVMConstInt(i32Type, 1, 0), "foreach.i.inc");
		LLVMBuildStore(builder, nextCounter, counterAlloca);

		// 8h. Branch back to header
		LLVMBuildBr(builder, loopHeaderBlock);

		// 9. Populate Exit Block
		LLVMPositionBuilderAtEnd(builder, loopExitBlock);

		return null; // foreach statement returns no value
	}

	private void visitIfStatementRecursive(NebulaParser.IfStatementContext ctx, LLVMBasicBlockRef finalMergeBlock)
	{
		LLVMValueRef function = currentFunction;

		// 1. Visit the condition
		LLVMValueRef conditionValue = visit(ctx.expression());
		if (conditionValue == null)
		{
			Debug.logError("IR Error: Failed to generate IR for if condition: " + ctx.expression().getText());
			return;
		}
		LLVMValueRef finalCondition = TypeConverter.toBoolean(conditionValue, ctx.expression(), builder);

		// 2. Create the blocks for this IF
		LLVMBasicBlockRef thenBlock = LLVMAppendBasicBlock(function, "if.then");

		// Retrieve the optional else statement (if it exists)
		NebulaParser.StatementContext elseStmt = ctx.statement().size() > 1 ? ctx.statement(1) : null;

		if (elseStmt != null && elseStmt.ifStatement() != null)
		{
			// This is an 'else if'. The false branch jumps to the next condition check.
			LLVMBasicBlockRef nextCheckBlock = LLVMAppendBasicBlock(function, "else.if.cond");
			LLVMBuildCondBr(builder, finalCondition, thenBlock, nextCheckBlock);

			// Position builder for the recursive call
			LLVMPositionBuilderAtEnd(builder, nextCheckBlock);
			// Recursively call with the same finalMergeBlock
			visitIfStatementRecursive(elseStmt.ifStatement(), finalMergeBlock);
		}
		else if (elseStmt != null)
		{
			// This is a final 'else' block. The false branch jumps directly to the 'else' body.
			LLVMBasicBlockRef elseBlock = LLVMAppendBasicBlock(function, "if.else");
			LLVMBuildCondBr(builder, finalCondition, thenBlock, elseBlock);

			// Populate 'else' block
			LLVMPositionBuilderAtEnd(builder, elseBlock);
			visit(elseStmt);

			// If 'else' block didn't terminate, branch to the final merge block
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{
				LLVMBuildBr(builder, finalMergeBlock);
			}
		}
		else
		{
			// Simple 'if'. The false branch jumps directly to the final merge block.
			LLVMBuildCondBr(builder, finalCondition, thenBlock, finalMergeBlock);
		}

		// 3. Populate 'then' block
		LLVMPositionBuilderAtEnd(builder, thenBlock);
		visit(ctx.statement(0)); // ✅ Correct: the "then" statement is always statement(0)

		// If 'then' block didn't terminate, branch to the final merge block
		if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
		{
			LLVMBuildBr(builder, finalMergeBlock);
		}
	}

	/**
	 * Public visitor entry point for IfStatement. Sets up the final merge block
	 * and initiates the recursive chain.
	 */
	@Override
	public LLVMValueRef visitIfStatement(NebulaParser.IfStatementContext ctx)
	{
		LLVMValueRef function = currentFunction;
		// 1. Create the final block where the entire chain converges
		LLVMBasicBlockRef mergeBlock = LLVMAppendBasicBlock(function, "if.merge");

		// 2. Start the recursive processing of the if-else if chain
		visitIfStatementRecursive(ctx, mergeBlock);

		// 3. Position builder at the final merge block
		LLVMPositionBuilderAtEnd(builder, mergeBlock);

		return null;
	}

	@Override
	public LLVMValueRef visitEqualityExpression(NebulaParser.EqualityExpressionContext ctx)
	{
		// If there’s only one child relationalExpression, just visit it normally
		if (ctx.relationalExpression().size() == 1)
		{
			return visit(ctx.relationalExpression(0));
		}

		LLVMValueRef left = visit(ctx.relationalExpression(0));

		for (int i = 1; i < ctx.relationalExpression().size(); i++)
		{
			LLVMValueRef right = visit(ctx.relationalExpression(i));
			String op = ctx.getChild(2 * i - 1).getText(); // operator token between expressions

			LLVMTypeRef leftType = LLVMTypeOf(left);
			LLVMTypeRef rightType = LLVMTypeOf(right);

			if (LLVMGetTypeKind(leftType) == LLVMIntegerTypeKind &&
					LLVMGetTypeKind(rightType) == LLVMIntegerTypeKind)
			{
				// promote to max width
				int lBits = LLVMGetIntTypeWidth(leftType);
				int rBits = LLVMGetIntTypeWidth(rightType);
				int targetBits = Math.max(lBits, rBits);
				LLVMTypeRef targetType = LLVMIntType(targetBits);

				if (lBits < targetBits)
				{
					left = LLVMBuildZExt(builder, left, targetType, "zext_eq_lhs");
				}
				if (rBits < targetBits)
				{
					right = LLVMBuildZExt(builder, right, targetType, "zext_eq_rhs");
				}

				int pred = op.equals("==") ? LLVMIntEQ : LLVMIntNE;
				left = LLVMBuildICmp(builder, pred, left, right, "eqcmp");
			}
			else if ((LLVMGetTypeKind(leftType) == LLVMFloatTypeKind || LLVMGetTypeKind(leftType) == LLVMDoubleTypeKind) &&
					(LLVMGetTypeKind(rightType) == LLVMFloatTypeKind || LLVMGetTypeKind(rightType) == LLVMDoubleTypeKind))
			{
				// promote to double
				if (LLVMGetTypeKind(leftType) == LLVMFloatTypeKind)
				{
					left = LLVMBuildFPExt(builder, left, LLVMDoubleType(), "fpext_lhs");
				}
				if (LLVMGetTypeKind(rightType) == LLVMFloatTypeKind)
				{
					right = LLVMBuildFPExt(builder, right, LLVMDoubleType(), "fpext_rhs");
				}

				int pred = op.equals("==") ? LLVMRealOEQ : LLVMRealONE;
				left = LLVMBuildFCmp(builder, pred, left, right, "feqcmp");
			}
			else
			{
				Debug.logError("Invalid ==/!= between incompatible types: " + ctx.getText());
				return null;
			}
		}
		return left; // the final boolean result
	}

	@Override
	public LLVMValueRef visitRelationalExpression(NebulaParser.RelationalExpressionContext ctx)
	{
		if (ctx.shiftExpression().size() == 1)
		{
			return visit(ctx.shiftExpression(0));
		}

		LLVMValueRef left = visit(ctx.shiftExpression(0));

		for (int i = 1; i < ctx.shiftExpression().size(); i++)
		{
			LLVMValueRef right = visit(ctx.shiftExpression(i));
			String op = ctx.getChild(2 * i - 1).getText();

			LLVMTypeRef leftType = LLVMTypeOf(left);
			LLVMTypeRef rightType = LLVMTypeOf(right);

			if (LLVMGetTypeKind(leftType) == LLVMIntegerTypeKind &&
					LLVMGetTypeKind(rightType) == LLVMIntegerTypeKind)
			{
				int lBits = LLVMGetIntTypeWidth(leftType);
				int rBits = LLVMGetIntTypeWidth(rightType);
				int targetBits = Math.max(lBits, rBits);
				LLVMTypeRef targetType = LLVMIntType(targetBits);

				if (lBits < targetBits)
				{
					left = LLVMBuildZExt(builder, left, targetType, "zext_rel_lhs");
				}
				if (rBits < targetBits)
				{
					right = LLVMBuildZExt(builder, right, targetType, "zext_rel_rhs");
				}

				int pred;
				switch (op)
				{
					case "<":
						pred = LLVMIntSLT;
						break;
					case "<=":
						pred = LLVMIntSLE;
						break;
					case ">":
						pred = LLVMIntSGT;
						break;
					case ">=":
						pred = LLVMIntSGE;
						break;
					default:
						Debug.logError("Unknown relational operator: " + op);
						return null;
				}
				left = LLVMBuildICmp(builder, pred, left, right, "relcmp");
			}
			else if ((LLVMGetTypeKind(leftType) == LLVMFloatTypeKind || LLVMGetTypeKind(leftType) == LLVMDoubleTypeKind) &&
					(LLVMGetTypeKind(rightType) == LLVMFloatTypeKind || LLVMGetTypeKind(rightType) == LLVMDoubleTypeKind))
			{
				if (LLVMGetTypeKind(leftType) == LLVMFloatTypeKind)
				{
					left = LLVMBuildFPExt(builder, left, LLVMDoubleType(), "fpext_lhs");
				}
				if (LLVMGetTypeKind(rightType) == LLVMFloatTypeKind)
				{
					right = LLVMBuildFPExt(builder, right, LLVMDoubleType(), "fpext_rhs");
				}

				int pred;
				switch (op)
				{
					case "<":
						pred = LLVMRealOLT;
						break;
					case "<=":
						pred = LLVMRealOLE;
						break;
					case ">":
						pred = LLVMRealOGT;
						break;
					case ">=":
						pred = LLVMRealOGE;
						break;
					default:
						Debug.logError("Unknown FP relational operator: " + op);
						return null;
				}
				left = LLVMBuildFCmp(builder, pred, left, right, "frelcmp");
			}
			else
			{
				Debug.logError("Invalid relational comparison: " + ctx.getText());
				return null;
			}
		}
		return left;
	}

	@Override
	public LLVMValueRef visitVariableDeclaration(NebulaParser.VariableDeclarationContext ctx)
	{
		Optional<Type> nebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.type());
		if (nebulaTypeOpt.isEmpty())
		{
			Debug.logError("IR Error: Could not resolve type for variable declaration: " + ctx.type().getText());
			return null;
		}
		Type nebulaType = nebulaTypeOpt.get();

		// Handle Array Declarations
		if (nebulaType instanceof ArrayType arrayNebulaType)
		{
			LLVMTypeRef elementLLVMType = TypeConverter.toLLVMType(arrayNebulaType.getElementType());
			LLVMTypeRef arrayDescLLVMType = TypeConverter.getArrayDescStructType();

			// We must loop through each declarator (e.g., int[] a, b, c)
			// The logic for size detection and allocation must be *inside* this loop.
			for (NebulaParser.VariableDeclaratorContext declarator : ctx.variableDeclarator())
			{
				String varName = declarator.ID().getText();
				int finalArraySize = -1;
				NebulaParser.ArrayInitializerContext initCtx = null;

				// 1. DETERMINE SIZE
				// Check for explicit size (e.g., new int[5]).
				// NOTE: Your current grammar doesn't support `int[5] numbers;` but `new int[5]`.
				// This code assumes size comes from an initializer if not explicit in the type.
				// For `int[] numbers`, explicit size is -1.
				if (ctx.type().L_BRACK_SYM().size() > 0 && ctx.type().getChildCount() == 3)
				{
					finalArraySize = -1; // Size omitted, must come from initializer
				}
				// ... (add logic for explicit size `int[5]` if your grammar changes) ...


				// Check initializer for size
				if (declarator.expression() != null)
				{
					initCtx = findArrayInitializer(declarator.expression());
					if (initCtx != null)
					{
						int initSize = initCtx.arrayElement().size();
						if (finalArraySize == -1)
						{
							finalArraySize = initSize; // Size inferred from initializer
							Debug.logDebug("IR: Inferred array size " + finalArraySize + " for '" + varName + "'.");
						}
						else if (finalArraySize != initSize)
						{
							Debug.logError("IR Error: Initializer size (" + initSize + ") does not match declared array size (" + finalArraySize + ") for '" + varName + "'.");
							continue; // Skip this declarator
						}
					}
					else
					{
						Debug.logError("IR Error: Array variable '" + varName + "' must be initialized with an array initializer literal { ... }.");
						continue;
					}
				}
				else if (finalArraySize == -1)
				{
					Debug.logError("IR Error: Array variable '" + varName + "' declared without size must have an initializer.");
					continue;
				}

				// 2. ALLOCATE (now that we have the size)
				if (finalArraySize == -1)
				{
					Debug.logError("IR Error: Could not determine final array size for '" + varName + "'.");
					continue;
				}

				// Create the LLVM array type (e.g., [5 x i32])
				LLVMTypeRef arrayDataLLVMType = LLVMArrayType2(elementLLVMType, finalArraySize);

				// 2a. Allocate space for the actual array data [Size x ElementType]
				LLVMValueRef dataAlloca = createEntryBlockAlloca(currentFunction, arrayDataLLVMType, varName + ".data");

				// 2b. Allocate space for the descriptor struct { i8*, i32 }
				LLVMValueRef descAlloca = createEntryBlockAlloca(currentFunction, arrayDescLLVMType, varName);

				// 3. INITIALIZE DESCRIPTOR
				LLVMValueRef zero = LLVMConstInt(LLVMInt32Type(), 0, 0);
				LLVMValueRef[] indices = {zero, zero}; // Index into [Size x Type] -> get pointer to first element
				LLVMValueRef dataPtr = LLVMBuildGEP2(builder, arrayDataLLVMType, dataAlloca, new PointerPointer<>(indices), 2, varName + ".ptr");

				// 3b. Cast dataPtr to i8* for storing in the descriptor
				LLVMValueRef dataPtrI8 = LLVMBuildBitCast(builder, dataPtr, LLVMPointerType(LLVMInt8Type(), 0), varName + ".ptr.i8");

				// 3c. Store dataPtrI8 and size into the descriptor struct
				LLVMValueRef dataPtrField = LLVMBuildStructGEP2(builder, arrayDescLLVMType, descAlloca, 0, varName + ".data.ptr.addr");
				LLVMValueRef sizeField = LLVMBuildStructGEP2(builder, arrayDescLLVMType, descAlloca, 1, varName + ".size.addr");
				LLVMBuildStore(builder, dataPtrI8, dataPtrField);
				LLVMBuildStore(builder, LLVMConstInt(LLVMInt32Type(), finalArraySize, 0), sizeField);

				// 4. FILL ARRAY DATA (if initializer exists)
				if (initCtx != null)
				{
					// Visit each element and store it
					for (int i = 0; i < finalArraySize; i++)
					{
						// We must visit the expression inside the array element
						LLVMValueRef elementVal = visit(initCtx.arrayElement(i).expression());
						if (elementVal == null)
						{
							continue; // Skip if visiting failed
						}

						// Cast element value to the array's element type
						elementVal = buildCast(builder, elementVal, elementLLVMType, "init_cast");

						// If elementVal is a pointer to a struct (e.g., a global string literal)
						// and the array stores struct *values*, we must load it first.
						if (LLVMGetTypeKind(LLVMTypeOf(elementVal)) == LLVMPointerTypeKind && LLVMGetTypeKind(elementLLVMType) == LLVMStructTypeKind && LLVMGetElementType(LLVMTypeOf(elementVal)).equals(elementLLVMType))
						{
							elementVal = LLVMBuildLoad2(builder, elementLLVMType, elementVal, "global.load");
						}

						// Get pointer to the i-th element
						LLVMValueRef idxVal = LLVMConstInt(LLVMInt32Type(), i, 0);
						LLVMValueRef[] elementIndices = {zero, idxVal};
						LLVMValueRef elementPtr = LLVMBuildGEP2(builder, arrayDataLLVMType, dataAlloca, new PointerPointer<>(elementIndices), 2, varName + ".elem." + i + ".ptr");

						// Store the value
						LLVMBuildStore(builder, elementVal, elementPtr);
					}
				}

				// 5. ADD TO SCOPE
				// Add the *descriptor* alloca to the scope, not the data alloca
				addVariableToScope(varName, descAlloca);
				namedValues.put(varName, descAlloca); // Store descriptor pointer
			}
			return null; // Handled array declaration
		}
		// End Array Handling


		// Existing Primitive/Other Type Handling
		LLVMTypeRef varLLVMType = TypeConverter.toLLVMType(nebulaType);

		for (NebulaParser.VariableDeclaratorContext declarator : ctx.variableDeclarator())
		{
			String varName = declarator.ID().getText();
			LLVMValueRef varAlloca = createEntryBlockAlloca(currentFunction, varLLVMType, varName);

			if (declarator.expression() != null)
			{
				LLVMValueRef initVal = visit(declarator.expression());
				if (initVal == null)
				{
					continue;
				}

				LLVMTypeRef initType = LLVMTypeOf(initVal);

				// Automatically extend/truncate/convert if sizes/types differ
				if (LLVMGetTypeKind(initType) == LLVMIntegerTypeKind &&
						LLVMGetTypeKind(varLLVMType) == LLVMIntegerTypeKind)
				{
					int fromBits = LLVMGetIntTypeWidth(initType);
					int toBits = LLVMGetIntTypeWidth(varLLVMType);
					boolean isUnsigned = nebulaTypeOpt.get().getName().startsWith("u");

					if (toBits > fromBits)
					{
						initVal = isUnsigned
								? LLVMBuildZExt(builder, initVal, varLLVMType, "zext_store")
								: LLVMBuildSExt(builder, initVal, varLLVMType, "sext_store");
					}
					else if (toBits < fromBits)
					{
						initVal = LLVMBuildTrunc(builder, initVal, varLLVMType, "trunc_store");
					}
				}
				else if ((LLVMGetTypeKind(initType) == LLVMFloatTypeKind || LLVMGetTypeKind(initType) == LLVMDoubleTypeKind) &&
						(LLVMGetTypeKind(varLLVMType) == LLVMFloatTypeKind || LLVMGetTypeKind(varLLVMType) == LLVMDoubleTypeKind))
				{
					// Promotion: float (32-bit) -> double (64-bit)
					if (LLVMGetTypeKind(varLLVMType) == LLVMDoubleTypeKind &&
							LLVMGetTypeKind(initType) == LLVMFloatTypeKind)
					{
						initVal = LLVMBuildFPExt(builder, initVal, varLLVMType, "fpext_store");
					}
					// Truncation: double (64-bit) -> float (32-bit)
					else if (LLVMGetTypeKind(varLLVMType) == LLVMFloatTypeKind &&
							LLVMGetTypeKind(initType) == LLVMDoubleTypeKind)
					{
						initVal = LLVMBuildFPTrunc(builder, initVal, varLLVMType, "fptrunc_store");
					}
				}

				LLVMBuildStore(builder, initVal, varAlloca);
			}

			addVariableToScope(varName, varAlloca);
			namedValues.put(varName, varAlloca);
		}
		return null;
	}

	@Override
	public LLVMValueRef visitLiteral(NebulaParser.LiteralContext ctx)
	{
		// Get Final Semantic Info
		Optional<Type> finalNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx);
		Optional<Object> valueInfoOpt = semanticAnalyzer.getResolvedInfo(ctx);

		// STRING LITERALS
		if (ctx.STRING_LITERAL() != null)
		{
			String value = ctx.STRING_LITERAL().getText();
			value = value.substring(1, value.length() - 1);

			LLVMValueRef stringData = LLVMConstString(value, value.length(), 1);

			// --- FIX: Use a non-conflicting name prefix ---
			LLVMValueRef globalData = LLVMAddGlobal(module, LLVMTypeOf(stringData), ".str.data");
			LLVMSetInitializer(globalData, stringData);
			LLVMSetGlobalConstant(globalData, 1);
			LLVMSetLinkage(globalData, LLVMPrivateLinkage);

			LLVMValueRef zero32 = LLVMConstInt(LLVMInt32Type(), 0, 0);
			LLVMValueRef[] indices = {zero32, zero32};
			LLVMValueRef dataPtr = LLVMConstGEP2(LLVMTypeOf(stringData), globalData, new PointerPointer<>(indices), 2);

			LLVMTypeRef stringType = TypeConverter.getStringStructType();
			LLVMValueRef[] fields = new LLVMValueRef[]{dataPtr, LLVMConstInt(LLVMInt32Type(), value.length(), 0)
			};
			LLVMValueRef structConst = LLVMConstNamedStruct(stringType, new PointerPointer<>(fields), fields.length);

			// --- FIX: Change hardcoded name to a prefix ---
			// By using ".str.literal" instead of "str_literal_struct",
			// LLVM will automatically append .1, .2, etc. to make it unique.
			LLVMValueRef globalString = LLVMAddGlobal(module, LLVMTypeOf(structConst), ".str.literal");
			LLVMSetInitializer(globalString, structConst);
			LLVMSetGlobalConstant(globalString, 1);
			LLVMSetLinkage(globalString, LLVMPrivateLinkage); // Also set linkage for the struct
			return globalString;
		}

		// INTERPOLATED STRING
		if (ctx.interpolatedString() != null)
		{
			Debug.logWarning("IR generation for interpolated strings not fully implemented yet.");
			// Placeholder: return an empty string for now
			LLVMTypeRef stringStructType = TypeConverter.getStringStructType();
			LLVMValueRef emptyStr = LLVMConstNull(stringStructType); // Or create a proper empty string global
			return LLVMBuildLoad2(builder, stringStructType, emptyStr, "interpolated_stub"); // Assuming global needs load
		}

		// NULL
		if (ctx.NULL_T() != null)
		{
			// Need to know the expected pointer type if possible, otherwise use i8*
			// For now, let's assume i8* as a generic null pointer
			LLVMTypeRef i8Ptr = LLVMPointerType(LLVMInt8Type(), 0);
			return LLVMConstNull(i8Ptr);
		}

		// BOOLEAN
		if (ctx.BOOLEAN_LITERAL() != null)
		{
			boolean val = ctx.BOOLEAN_LITERAL().getText().equals("true");
			return LLVMConstInt(LLVMInt1Type(), val ? 1 : 0, 0);
		}

		// CHAR
		if (ctx.CHAR_LITERAL() != null)
		{
			String text = ctx.CHAR_LITERAL().getText();
			// Basic unescaping for simple cases - needs robust handling
			char val = text.length() > 2 ? text.charAt(1) : 0; // Simplified
			if (text.length() == 4 && text.startsWith("'\\"))
			{ // e.g., '\n'
				// Add more escapes as needed
				val = switch (text.charAt(2))
				{
					case 'n' -> '\n';
					case 't' -> '\t';
					case '\\' -> '\\';
					case '\'' -> '\'';
					default -> val;
				};
			}
			return LLVMConstInt(LLVMInt8Type(), val, 0); // Assuming char is i8
		}


		// NUMERIC LITERALS (Using Semantic Info)
		if (finalNebulaTypeOpt.isPresent() && valueInfoOpt.isPresent())
		{
			Type finalNebulaType = finalNebulaTypeOpt.get();
			Object valueInfo = valueInfoOpt.get();
			LLVMTypeRef targetLLVMType = TypeConverter.toLLVMType(finalNebulaType);

			// Floating Point
			if (finalNebulaType == PrimitiveType.FLOAT && valueInfo instanceof Float)
			{
				return LLVMConstReal(targetLLVMType, (Float) valueInfo);
			}
			if (finalNebulaType == PrimitiveType.DOUBLE && valueInfo instanceof Double)
			{
				return LLVMConstReal(targetLLVMType, (Double) valueInfo);
			}
			// Handle cases where literal was parsed as Double but target is Float
			if (finalNebulaType == PrimitiveType.FLOAT && valueInfo instanceof Double)
			{
				return LLVMConstReal(targetLLVMType, ((Double) valueInfo).floatValue());
			}


			// Integer
			if (finalNebulaType.isInteger() && valueInfo instanceof BigInteger biValue)
			{
				// Use the BigInteger value and the final LLVM type determined by semantics
				long longVal = biValue.longValue(); // Get long value (potential truncation ok due to prior semantic checks)

				// LLVMConstInt takes a 'long' for the value.
				// For unsigned types, the bit pattern matters. We rely on the long having the correct bit pattern.
				// LLVM treats integer types primarily by bit width, signedness is mainly for specific instructions (sdiv, udiv, etc.)
				return LLVMConstInt(targetLLVMType, longVal, 0); // Use 0 for signed extension flag for simplicity, LLVM handles it
			}
		}

		// Fallback (Should ideally not be reached often if semantics are correct)
		Debug.logDebug("IRVisitor: Fallback literal handling for: " + ctx.getText());
		if (ctx.INTEGER_LITERAL() != null)
		{
			return LLVMConstInt(LLVMInt32Type(), Long.parseLong(ctx.INTEGER_LITERAL().getText()), 0);
		}
		// ... other fallbacks. Shouldn't need implementation ...

		Debug.logError("IR Error: Unhandled literal type in visitLiteral: " + ctx.getText());
		return null; // Error case
	}


	@Override
	public LLVMValueRef visitReturnStatement(NebulaParser.ReturnStatementContext ctx)
	{
		if (ctx.expression() != null)
		{
			LLVMValueRef retVal = visit(ctx.expression());
			return LLVMBuildRet(builder, retVal); // This should be LLVMBuildRet for the correct type!
		}
		// This default return for functions without an expression is likely wrong
		// for non-void functions, but should not be reached here.
		return LLVMBuildRet(builder, LLVMConstInt(LLVMInt32Type(), 0, 0));
	}

	private String canonicalTypeName(Type t)
	{
		String n = t.getName();
		if (n == null)
		{
			return "";
		}
		n = n.toLowerCase();
		if (n.endsWith("string") || n.equals("string"))
		{
			return "string";
		}
		if (n.equals("int") || n.contains("int32"))
		{
			return "int";
		}
		if (n.equals("double"))
		{
			return "double";
		}
		if (n.equals("float"))
		{
			return "float";
		}
		if (n.equals("void"))
		{
			return "void";
		}
		return n.replace('.', '_');
	}

	@Override
	public LLVMValueRef visitBlock(NebulaParser.BlockContext ctx)
	{
		pushScope();
		for (NebulaParser.StatementContext stmtCtx : ctx.statement())
		{
			visit(stmtCtx);
		}
		popScope();
		return null;
	}

	@Override
	public LLVMValueRef visitStatement(NebulaParser.StatementContext ctx)
	{
		visitChildren(ctx);
		return null;
	}

	@Override
	public LLVMValueRef visitPostfixExpression(NebulaParser.PostfixExpressionContext ctx)
	{
		Debug.logDebug("IR (Postfix): Visiting: " + ctx.getText() + " (Hash: " + ctx.hashCode() + ", Interval: " + ctx.getSourceInterval() + ")");

		// --- START FIX: Check for 'new' expression in primary ---
		// If the primary expression is a 'new' expression, it handles its own
		// constructor call. We must not treat it as a method call and must
		// delegate to visitPrimary immediately.
		if (ctx.primary() != null && ctx.primary().NEW_KW() != null)
		{
			Debug.logDebug("IR (Postfix): Primary is 'new' expression. Deferring to visitPrimary.");
			return visit(ctx.primary());
		}
		// --- END FIX ---

		Optional<Symbol> symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
		Optional<Type> resultTypeOpt = semanticAnalyzer.getResolvedType(ctx); // Get the final type

		// ---------- METHOD CALL (instance or static) ----------
		if (symbolOpt.isPresent() && symbolOpt.get() instanceof MethodSymbol methodSymbol)
		{
			Debug.logDebug("IR (Postfix): Resolved as method call: " + methodSymbol);

			String mangledName = methodSymbol.getMangledName();
			LLVMValueRef function = LLVMGetNamedFunction(module, mangledName);

			// --- 1. Build function parameter type list (include 'this' for instance methods) ---
			List<Type> paramTypes = methodSymbol.getParameterTypes();
			List<LLVMTypeRef> llvmParamTypesList = new ArrayList<>();

			if (!methodSymbol.isStatic())
			{
				// 'this' pointer type (pointer to class/struct)
				Type thisType = ((ClassSymbol) methodSymbol.getEnclosingScope()).getType();
				llvmParamTypesList.add(TypeConverter.toLLVMType(thisType));
			}

			for (Type p : paramTypes)
			{
				llvmParamTypesList.add(TypeConverter.toLLVMType(p));
			}

			LLVMTypeRef[] llvmParamTypes = llvmParamTypesList.toArray(new LLVMTypeRef[0]);
			LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType());
			LLVMTypeRef functionType = LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), llvmParamTypesList.size(), 0);

			// --- 2. Create function prototype if missing ---
			if (function == null)
			{
				Debug.logDebug("IR (Postfix): Function prototype not found. Creating LLVM declaration for: " + mangledName);
				function = LLVMAddFunction(module, mangledName, functionType);
			}

			// If method is native, ensure external linkage (so we don't emit a body later).
			if (methodSymbol.isNative())
			{
				LLVMSetLinkage(function, LLVMExternalLinkage);
				Debug.logDebug("IR (Postfix): Ensured external linkage for native method: " + mangledName);
			}

			// --- 3. Prepare args list (compute 'this' first for instance methods) ---
			List<LLVMValueRef> args = new ArrayList<>();

			int argOffset = 0;
			if (!methodSymbol.isStatic())
			{
				// The 'this' object is the result of visiting the 'primary' part
				// (e.g., in `obj.foo()` the primary is `obj`)
				LLVMValueRef thisObject = visit(ctx.primary());
				if (thisObject == null)
				{
					Debug.logError("IR Error: Failed to generate 'this' for instance method call: " + ctx.getText());
					return null;
				}

				LLVMTypeRef expectedThisType = llvmParamTypesList.get(0);
				thisObject = buildCast(builder, thisObject, expectedThisType, "this_cast");
				args.add(thisObject);
				argOffset = 1;
			}

			// --- INTRINSIC ARRAY METHOD HANDLING ---
			// Check if this is a special compiler-known method on nebula.core.Array
			if (methodSymbol.getEnclosingScope() instanceof ClassSymbol cs && cs.getFqn().equals("nebula.core.Array"))
			{
				LLVMValueRef thisObject = visit(ctx.primary()); // Get the nebula_Array_t*
				LLVMTypeRef arrayDescType = TypeConverter.getArrayDescStructType();

				// 1. --- Handle 'arr.length' (which is a call to 'get_length') ---
				if (methodSymbol.getName().equals("get_length"))
				{
					Debug.logDebug("IR (Intrinsic): Handling arr.length property");
					LLVMValueRef descStruct = LLVMBuildLoad2(builder, arrayDescType, thisObject, "arr.desc.load");
					LLVMValueRef sizeVal = LLVMBuildExtractValue(builder, descStruct, 1, "arr.size");
					return sizeVal;
				}

				// 2. --- Get arguments (shared for elementAt and setElementAt) ---
				NebulaParser.ArgumentListContext argListCtx = null;
				for (int i = 0; i < ctx.getChildCount(); i++)
				{
					if (ctx.getChild(i) instanceof NebulaParser.ArgumentListContext)
					{
						argListCtx = (NebulaParser.ArgumentListContext) ctx.getChild(i);
						break;
					}
				}
				if (argListCtx == null || argListCtx.expression().isEmpty())
				{
					Debug.logError("IR Error: Array method call missing arguments: " + ctx.getText());
					return null;
				}

				// Get index (arg 0)
				LLVMValueRef indexVal = visit(argListCtx.expression(0));
				indexVal = buildCast(builder, indexVal, LLVMInt32Type(), "idx_cast");

				// 3. --- Get Element Pointer (shared logic) ---
				Optional<Type> baseNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.primary());
				if (baseNebulaTypeOpt.isEmpty() || !(baseNebulaTypeOpt.get() instanceof ArrayType baseArrayType))
				{
					Debug.logError("IR Error: Cannot determine element type for array access: " + ctx.primary().getText());
					return null;
				}
				LLVMTypeRef elementLLVMType = TypeConverter.toLLVMType(baseArrayType.getElementType());
				LLVMValueRef elementPtr = getArrayElementPointer(thisObject, indexVal, elementLLVMType);
				if (elementPtr == null)
				{
					return null; // Error handled in helper
				}

				// 4. --- Handle 'arr.elementAt(i)' ---
				if (methodSymbol.getName().equals("elementAt"))
				{
					Debug.logDebug("IR (Intrinsic): Handling arr.elementAt(i)");
					// elementAt returns the VALUE (R-Value), so we must load it.
					return LLVMBuildLoad2(builder, elementLLVMType, elementPtr, "arr.elem.load");
				}

				// 5. --- Handle 'arr.setElementAt(i, v)' ---
				if (methodSymbol.getName().equals("setElementAt"))
				{
					Debug.logDebug("IR (Intrinsic): Handling arr.setElementAt(i, v)");
					if (argListCtx.expression().size() < 2)
					{
						Debug.logError("IR Error: Array.setElementAt call missing value argument: " + ctx.getText());
						return null;
					}
					// Get value (arg 1)
					LLVMValueRef valueToStore = visit(argListCtx.expression(1));
					valueToStore = buildCast(builder, valueToStore, elementLLVMType, "val_cast");

					// Store the value
					LLVMBuildStore(builder, valueToStore, elementPtr);
					return null; // void return
				}
			}

			// Locate argument list child if present
			NebulaParser.ArgumentListContext argListCtx = null;
			for (int i = 0; i < ctx.getChildCount(); i++)
			{
				if (ctx.getChild(i) instanceof NebulaParser.ArgumentListContext)
				{
					argListCtx = (NebulaParser.ArgumentListContext) ctx.getChild(i);
					break;
				}
			}

			List<Type> expectedParamTypes = methodSymbol.getParameterTypes();
			if (argListCtx != null)
			{
				Debug.logDebug("IR (Postfix): Processing " + argListCtx.expression().size() + " arguments...");
				for (int i = 0; i < argListCtx.expression().size(); i++)
				{
					NebulaParser.ExpressionContext exprCtx = argListCtx.expression().get(i);
					Debug.logDebug("IR (Postfix): Visiting argument #" + i + ": " + exprCtx.getText() +
							" (Hash: " + exprCtx.hashCode() +
							", Interval: " + exprCtx.getSourceInterval() + ")");
					LLVMValueRef argValue = visit(exprCtx);

					// Argument Type Conversion: account for 'this' offset in param index
					int paramIdx = i + (methodSymbol.isStatic() ? 0 : 1);
					if (paramIdx < llvmParamTypesList.size() && argValue != null)
					{
						LLVMTypeRef targetType = llvmParamTypesList.get(paramIdx); // e.g., i32
						LLVMTypeRef argType = LLVMTypeOf(argValue);     // e.g., i32*

						// *** NEW FIX: Load L-Value if R-Value is expected ***
						if (LLVMGetTypeKind(argType) == LLVMPointerTypeKind && LLVMGetTypeKind(targetType) != LLVMPointerTypeKind)
						{
							LLVMTypeRef pointedToType = LLVMGetElementType(argType);
							// Load the value from the pointer
							argValue = LLVMBuildLoad2(builder, pointedToType, argValue, "arg.load");
						}
						// *** END FIX ***

						argValue = buildCast(builder, argValue, targetType, "arg_cast" + i);
					}
					args.add(argValue);
				}
			}

			// --- 4. Build call ---
			PointerPointer<LLVMValueRef> argsPtr = new PointerPointer<>(args.size());
			for (int i = 0; i < args.size(); i++)
			{
				argsPtr.put(i, args.get(i));
			}

			Debug.logDebug("IR (Postfix): Building LLVM call instruction for: " + mangledName);
			String callName = "";
			if (methodSymbol.getType() != PrimitiveType.VOID)
			{
				callName = methodSymbol.getName() + ".call";
			}

			return LLVMBuildCall2(builder, functionType, function, argsPtr, args.size(), callName.isEmpty() ? "" : callName);
		}

		// --- FIELD/PROPERTY ACCESS ---
		// This handles cases like 'nums.len' where the *entire expression*
		// resolves to a VariableSymbol (the 'len' field).
		if (symbolOpt.isPresent() && symbolOpt.get() instanceof VariableSymbol varSymbol)
		{
			// This handles simple `varName` access, which should be done in visitPrimary.
			// But if there's a dot, it's field access.
			if (ctx.DOT_SYM().size() == 0)
			{
				// Not a field access, it's a simple variable. Let visitPrimary handle it.
				Debug.logDebug("IR (Postfix): Resolved as simple variable. Deferring to primary.");
				return visit(ctx.primary());
			}

			Debug.logDebug("IR (Postfix): Resolved as variable/property access: " + varSymbol.getName());

			// 1. Get the pointer to the base object (e.g., 'this' or 'vec')
			LLVMValueRef baseObjectPtr = visit(ctx.primary());
			if (baseObjectPtr == null)
			{
				// --- START STATIC FIX ---
				// Base is null. This is expected if it's a static access (e.g., "Vector3.zero")
				if (varSymbol.isStatic())
				{
					Debug.logDebug("IR (Postfix): Resolved as STATIC variable access: " + varSymbol.getMangledName());
					LLVMValueRef globalVar = namedValues.get(varSymbol.getMangledName());
					if (globalVar == null)
					{
						globalVar = LLVMGetNamedGlobal(module, varSymbol.getMangledName());
					}
					if (globalVar == null)
					{
						Debug.logError("IR Error: Could not find global variable for static field: " + varSymbol.getMangledName());
						return null;
					}
					// Return the POINTER to the global (the L-Value)
					// The caller (assignment, additive) will load if it needs the R-Value.
					return globalVar;
				}
				// --- END STATIC FIX ---

				Debug.logError("IR Error: Base of field access is null: " + ctx.primary().getText());
				return null;
			}

			// --- This is an INSTANCE field access (e.g., "this.x") ---
			// 2. Get the *type* of the base object (e.g., Vector3)
			Optional<Type> baseNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.primary());
			if (baseNebulaTypeOpt.isEmpty() || !(baseNebulaTypeOpt.get() instanceof ClassType))
			{
				Debug.logError("IR Error: Cannot determine base class type for field access: " + ctx.primary().getText());
				return null;
			}
			ClassSymbol baseClassSymbol = baseNebulaTypeOpt.get().getClassSymbol();

			// 3. Get the LLVM struct type from the pointer (e.g., %nebula_Vector3)
			//    The baseObjectPtr is a POINTER (e.g. %nebula_Vector3*).
			//    We need the element type to GEP.
			LLVMTypeRef basePtrType = LLVMTypeOf(baseObjectPtr);
			LLVMTypeRef baseStructType = LLVMGetElementType(basePtrType);

			if (LLVMGetTypeKind(baseStructType) != LLVMStructTypeKind)
			{
				Debug.logError("IR Error: Base of field access is not a pointer to a struct. Type is: " + LLVMPrintTypeToString(basePtrType));
				return null;
			}
			Debug.logDebug("IR (Postfix): GEPing with struct type: " + LLVMPrintTypeToString(baseStructType));

			// 4. Find the field's index in the struct.
			int fieldIndex = findFieldIndex(baseClassSymbol, varSymbol.getName());
			if (fieldIndex == -1)
			{
				Debug.logError("IR Error: Field index lookup failed for: " + baseClassSymbol.getFqn() + "." + varSymbol.getName());
				return null;
			}

			// 5. Generate GEP to get the address of the field
			//    LLVMBuildStructGEP2 is for GEPing a struct *value*.
			//    Since we have a *pointer*, we must GEP with {0, fieldIndex}
			LLVMValueRef zero = LLVMConstInt(LLVMInt32Type(), 0, 0);
			LLVMValueRef idx = LLVMConstInt(LLVMInt32Type(), fieldIndex, 0);
			LLVMValueRef[] indices = {zero, idx};

			LLVMValueRef fieldPtr = LLVMBuildGEP2(builder, baseStructType, baseObjectPtr, new PointerPointer<>(indices), 2, varSymbol.getName() + ".ptr");

			// 6. Return the POINTER (L-Value)
			// The caller (visitAssignmentExpression) needs the pointer to store into.
			return fieldPtr;
		}

		// ---------- ARRAY ELEMENT ACCESS: arr[idx] ----------
		if (ctx.expression() != null && !ctx.expression().isEmpty() && ctx.L_BRACK_SYM().size() > 0)
		{
			Debug.logDebug("IR (Postfix): Detected potential array access.");

			// 1. Visit the base expression (e.g., 'arr') to get the descriptor alloca
			LLVMValueRef baseDescPtr = visit(ctx.primary()); // Assuming base is primary for now
			if (baseDescPtr == null)
			{
				// Might be a more complex base like obj.arrayField[i] - needs full handling
				Debug.logError("IR Error: Base of array access is null or complex access not implemented: " + ctx.primary().getText());
				return null;
			}

			// Dont Validate, trust the semantic analysis validity of equal types

			// 2. Visit the index expression (support single index for now)
			LLVMValueRef indexVal = visit(ctx.expression(0));
			if (indexVal == null)
			{
				Debug.logError("IR Error: Failed to generate IR for array index: " + ctx.expression(0).getText());
				return null;
			}
			indexVal = buildCast(builder, indexVal, LLVMInt32Type(), "idx_cast"); // assume i32 index

			// 3. --- Get Element Pointer (using new helper) ---
			Optional<Type> baseNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.primary());
			if (baseNebulaTypeOpt.isEmpty() || !(baseNebulaTypeOpt.get() instanceof ArrayType baseArrayType))
			{
				Debug.logError("IR Error: Cannot determine element type for array access: " + ctx.primary().getText());
				return null;
			}
			LLVMTypeRef elementLLVMType = TypeConverter.toLLVMType(baseArrayType.getElementType());
			LLVMValueRef elementPtr = getArrayElementPointer(baseDescPtr, indexVal, elementLLVMType);

			Debug.logDebug("IR (Postfix): Array access returning element pointer.");
			return elementPtr;
		}

		// ---------- POSTFIX ++ / -- ----------
		if (ctx.INC_OP().size() > 0 || ctx.DEC_OP().size() > 0)
		{
			LLVMValueRef baseVal = visit(ctx.primary()); // Get the L-Value (address)
			if (baseVal == null || LLVMGetTypeKind(LLVMTypeOf(baseVal)) != LLVMPointerTypeKind)
			{
				Debug.logError("IR Error: Postfix increment/decrement requires a variable or addressable element.");
				return null;
			}

			LLVMTypeRef elementType = LLVMGetElementType(LLVMTypeOf(baseVal));
			LLVMValueRef loadedVal = LLVMBuildLoad2(builder, elementType, baseVal, "postop.load");
			LLVMValueRef resultVal; // The value *before* the operation
			LLVMValueRef newVal;
			LLVMValueRef one = LLVMConstInt(elementType, 1, 0);

			if (!ctx.INC_OP().isEmpty())
			{ // Post-increment x++
				newVal = LLVMBuildAdd(builder, loadedVal, one, "postinc");
				resultVal = loadedVal; // Return original value
			}
			else
			{ // Post-decrement x--
				newVal = LLVMBuildSub(builder, loadedVal, one, "postdec");
				resultVal = loadedVal; // Return original value
			}

			LLVMBuildStore(builder, newVal, baseVal);
			return resultVal;
		}

		// ---------- FALLBACK: variable access or primary ----------
		Debug.logDebug("IR (Postfix): Not a method call or array access. Visiting Primary child: " + ctx.primary().getText() +
				" (Hash: " + ctx.primary().hashCode() +
				", Interval: " + ctx.primary().getSourceInterval() + ")");
		return visit(ctx.primary());
	}

	@Override
	public LLVMValueRef visitCastExpression(NebulaParser.CastExpressionContext ctx)
	{
		LLVMValueRef originalValue = visit(ctx.unaryExpression());
		if (originalValue == null)
		{
			return null;
		}

		Optional<Type> originalNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.unaryExpression());
		Optional<Type> targetNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx);

		if (originalNebulaTypeOpt.isEmpty() || targetNebulaTypeOpt.isEmpty())
		{
			Debug.logError("IR Error: Could not resolve types for cast expression: " + ctx.getText());
			return null;
		}

		Type originalType = originalNebulaTypeOpt.get();
		Type targetType = targetNebulaTypeOpt.get();

		LLVMTypeRef targetLLVMType = TypeConverter.toLLVMType(targetType);
		LLVMTypeRef originalLLVMType = LLVMTypeOf(originalValue);

		if (originalLLVMType.equals(targetLLVMType))
		{
			return originalValue;
		}

		boolean targetIsNumeric = targetType.isNumeric();
		boolean originalIsNumeric = originalType.isNumeric();
		boolean targetIsUnsigned = targetType.getName().startsWith("u");
		boolean originalIsUnsigned = originalType.getName().startsWith("u");

		if (targetIsNumeric && originalIsNumeric)
		{
			int targetBits = (LLVMGetTypeKind(targetLLVMType) == LLVMIntegerTypeKind)
					? LLVMGetIntTypeWidth(targetLLVMType) : 0;
			int originalBits = (LLVMGetTypeKind(originalLLVMType) == LLVMIntegerTypeKind)
					? LLVMGetIntTypeWidth(originalLLVMType) : 0;

			boolean targetIsFloat = (LLVMGetTypeKind(targetLLVMType) == LLVMFloatTypeKind
					|| LLVMGetTypeKind(targetLLVMType) == LLVMDoubleTypeKind);
			boolean originalIsFloat = (LLVMGetTypeKind(originalLLVMType) == LLVMFloatTypeKind
					|| LLVMGetTypeKind(originalLLVMType) == LLVMDoubleTypeKind);

			// int -> int
			if (targetBits > 0 && originalBits > 0)
			{
				if (targetBits < originalBits)
				{
					return LLVMBuildTrunc(builder, originalValue, targetLLVMType, "trunc");
				}
				else if (targetBits > originalBits)
				{
					return originalIsUnsigned
							? LLVMBuildZExt(builder, originalValue, targetLLVMType, "zext")
							: LLVMBuildSExt(builder, originalValue, targetLLVMType, "sext");
				}
				return LLVMBuildBitCast(builder, originalValue, targetLLVMType, "intcast");
			}

			// float -> float
			if (targetIsFloat && originalIsFloat)
			{
				if (LLVMGetTypeKind(targetLLVMType) == LLVMDoubleTypeKind &&
						LLVMGetTypeKind(originalLLVMType) == LLVMFloatTypeKind)
				{
					return LLVMBuildFPExt(builder, originalValue, targetLLVMType, "fpext");
				}
				else if (LLVMGetTypeKind(targetLLVMType) == LLVMFloatTypeKind &&
						LLVMGetTypeKind(originalLLVMType) == LLVMDoubleTypeKind)
				{
					return LLVMBuildFPTrunc(builder, originalValue, targetLLVMType, "fptrunc");
				}
			}

			// int -> float
			if (targetIsFloat && originalBits > 0)
			{
				return originalIsUnsigned
						? LLVMBuildUIToFP(builder, originalValue, targetLLVMType, "uitofp")
						: LLVMBuildSIToFP(builder, originalValue, targetLLVMType, "sitofp");
			}

			// float -> int / uint
			if (targetBits > 0 && originalIsFloat)
			{
				// 1. convert to a wide signed int
				LLVMTypeRef wideInt = LLVMInt64Type();
				LLVMValueRef wide = LLVMBuildFPToSI(builder, originalValue, wideInt, "fptosi_wide");

				// 2. mask if target is unsigned to emulate modulo 2^N
				if (targetIsUnsigned)
				{
					long mask = (targetBits == 64) ? -1L : ((1L << targetBits) - 1L);
					LLVMValueRef maskConst = LLVMConstInt(wideInt, mask, 0);
					wide = LLVMBuildAnd(builder, wide, maskConst, "mask_low_bits");
				}

				// 3. truncate to final size
				return LLVMBuildTrunc(builder, wide, targetLLVMType, "trunc_to_target");
			}
		}

		Debug.logDebug("IR: Using fallback LLVMBuildBitCast for cast: " + ctx.getText());
		return LLVMBuildBitCast(builder, originalValue, targetLLVMType, "bitcast");
	}

	@Override
	public LLVMValueRef visitPrimary(NebulaParser.PrimaryContext ctx)
	{
		Debug.logDebug("IR (Primary): Visiting: " + ctx.getText() + " (Hash: " + ctx.hashCode() + ", Interval: " + ctx.getSourceInterval() + ")");

		// 1) Literal: delegate
		if (ctx.literal() != null)
		{
			return visit(ctx.literal());
		}

		// 2) Parenthesized expression: ( expr )
		if (ctx.expression() != null)
		{
			return visit(ctx.expression()); //
		}

		// --- Handle 'new' expressions ---
		if (ctx.NEW_KW() != null)
		{
			Debug.logDebug("IR (Primary): Handling 'new' expression.");

			// 1. Get the constructor symbol (e.g., Vector3::Vector3(float, ...))
			Optional<Symbol> ctorSymbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
			// 2. Get the type being constructed (e.g., Vector3)
			Optional<Type> classTypeOpt = semanticAnalyzer.getResolvedType(ctx);

			if (ctorSymbolOpt.isEmpty() || !(ctorSymbolOpt.get() instanceof MethodSymbol ctorSymbol) ||
					classTypeOpt.isEmpty() || !(classTypeOpt.get() instanceof ClassType classType))
			{
				Debug.logError("IR Error: Semantic info not found for 'new' expression: " + ctx.getText());
				if (ctorSymbolOpt.isPresent())
				{
					Debug.logError("  -> Resolved symbol was: " + ctorSymbolOpt.get().getName() + " of type " +
							ctorSymbolOpt.get().getClass().getSimpleName());
				}
				return null;
			}

			// 3. Get the LLVM struct type (the value type, not the pointer)
			LLVMTypeRef classLLVMPtrType = TypeConverter.toLLVMType(classType);
			if (classLLVMPtrType == null)
			{
				Debug.logError("IR Error: Failed to convert class type to LLVM type for: " + classType.getName());
				return null;
			}
			Debug.logDebug("IR (Primary): classLLVMPtrType kind=" + LLVMGetTypeKind(classLLVMPtrType));
			LLVMTypeRef classLLVMStructType = LLVMGetElementType(classLLVMPtrType);
			if (classLLVMStructType == null)
			{
				Debug.logError("IR Error: Couldn't get element type of classLLVMPtrType for " + classType.getName());
				return null;
			}
			Debug.logDebug("IR (Primary): classLLVMStructType kind=" + LLVMGetTypeKind(classLLVMStructType));

			// 4. Allocate memory for the new instance using 'malloc'
			LLVMValueRef mallocFunc = LLVMGetNamedFunction(module, "malloc");
			LLVMTypeRef mallocFuncType;

			if (mallocFunc == null)
			{
				Debug.logDebug("IR (Primary): 'malloc' not found in module; declaring prototype (i8* malloc(i64)).");
				LLVMTypeRef i8ptr = LLVMPointerType(LLVMInt8Type(), 0);
				LLVMTypeRef[] mParams = {LLVMInt64Type()};
				mallocFuncType = LLVMFunctionType(i8ptr, new PointerPointer<>(mParams), 1, 0); // [Source 3607]
				mallocFunc = LLVMAddFunction(module, "malloc", mallocFuncType);
				if (mallocFunc == null)
				{
					Debug.logError("IR Error: Failed to add declaration for malloc.");
					return null;
				}
			}
			else
			{
				// --- Get the function type *from* the function pointer type ---
				LLVMTypeRef mallocFuncPtrType = LLVMTypeOf(mallocFunc);
				if (LLVMGetTypeKind(mallocFuncPtrType) != LLVMPointerTypeKind)
				{
					Debug.logError("IR Error: Malloc function reference is not a pointer type! kind=" + LLVMGetTypeKind(mallocFuncPtrType));
					return null;
				}

				// Get the type the pointer *points to* (the actual function type)
				mallocFuncType = LLVMGetElementType(mallocFuncPtrType);

				if (mallocFuncType == null)
				{
					Debug.logError("IR Error: LLVMTypeOf(mallocFunc) returned null or not a pointer.");
					return null;
				}
				if (LLVMGetTypeKind(mallocFuncType) != LLVMFunctionTypeKind)
				{
					Debug.logError("IR Error: mallocFuncType is not a function type! kind=" + LLVMGetTypeKind(mallocFuncType));
					return null;
				}
				Debug.logDebug("IR (Primary): Found existing malloc. funcType kind=" + LLVMGetTypeKind(mallocFuncType));
			}

			// 5. Compute struct size
			LLVMValueRef sizeOfStruct = LLVMSizeOf(classLLVMStructType);
			if (sizeOfStruct == null)
			{
				Debug.logError("IR Error: LLVMSizeOf returned null for struct type.");
				return null;
			}

			// Cast size to i64 for malloc
			LLVMValueRef sizeCasted = LLVMBuildIntCast2(builder, sizeOfStruct, LLVMInt64Type(), 1, "size_cast");
			if (sizeCasted == null)
			{
				Debug.logError("IR Error: size cast failed before malloc.");
				return null;
			}

			// Prepare malloc args
			LLVMValueRef[] mallocArgs = {sizeCasted};

			Debug.logDebug("IR (Primary): Calling malloc (args count = " + mallocArgs.length + ").");
			LLVMValueRef mallocPtr = LLVMBuildCall2(
					builder,
					mallocFuncType,  // ✅ Correct function type now
					mallocFunc,
					new PointerPointer<>(mallocArgs),
					mallocArgs.length,
					"malloc.tmp"
			);

			if (mallocPtr == null)
			{
				Debug.logError("IR Error: LLVMBuildCall2(malloc) returned null.");
				return null;
			}

			// Cast malloc result to our struct pointer type
			LLVMValueRef newInstancePtr = LLVMBuildBitCast(builder, mallocPtr, classLLVMPtrType, "new.instance");
			if (newInstancePtr == null)
			{
				Debug.logError("IR Error: Failed to cast malloc result to class pointer type.");
				return null;
			}

			// 5. Prepare arguments for the constructor call
			List<LLVMValueRef> args = new ArrayList<>();
			// Add the allocated 'this' pointer as the first argument
			args.add(newInstancePtr);

			// 6. Visit and collect other arguments
			List<org.lokray.semantic.symbol.ParameterSymbol> formalParams = ctorSymbol.getParameters();
			int providedArgCount = 0;

			// 6a. Visit and collect *provided* arguments
			if (ctx.argumentList() != null)
			{
				List<NebulaParser.ExpressionContext> providedArgExprs = ctx.argumentList().expression();
				providedArgCount = providedArgExprs.size();
				Debug.logDebug("IR (Primary): Provided positional arg count = " + providedArgCount);
				for (int i = 0; i < providedArgExprs.size(); i++)
				{
					LLVMValueRef argVal = visit(providedArgExprs.get(i));
					if (argVal == null)
					{
						Debug.logError("IR Error: Generated null for provided argument index " + i + " (" + providedArgExprs.get(i).getText() + ").");
						return null;
					}
					// Cast it to the formal param type
					if (i >= formalParams.size())
					{
						Debug.logError("IR Error: Too many provided arguments for constructor. Provided: " + providedArgExprs.size() + ", Expected: " + formalParams.size());
						return null;
					}
					LLVMTypeRef formalType = TypeConverter.toLLVMType(formalParams.get(i).getType());
					if (formalType == null)
					{
						Debug.logError("IR Error: Could not convert formal param type to LLVM type for param index " + i);
						return null;
					}
					argVal = buildCast(builder, argVal, formalType, "arg_cast" + i);
					args.add(argVal);
					Debug.logDebug("IR (Primary): Added provided arg[" + i + "] text='" + providedArgExprs.get(i).getText() + "' llvmKind=" + LLVMGetTypeKind(LLVMTypeOf(argVal)));
				}
				// TODO: Handle named arguments from ctx.argumentList().namedArgument()
			}

			// 6b. Fill in default arguments
			if (providedArgCount < formalParams.size())
			{
				Debug.logDebug("IR (Primary): Filling " + (formalParams.size() - providedArgCount) + " default arguments for constructor.");
				for (int i = providedArgCount; i < formalParams.size(); i++)
				{
					org.lokray.semantic.symbol.ParameterSymbol param = formalParams.get(i);
					if (param.hasDefaultValue())
					{
						NebulaParser.ExpressionContext defaultCtx = param.getDefaultValueCtx().get();
						Debug.logDebug("IR (Primary): Visiting default value for param '" + param.getName() + "': " + defaultCtx.getText());
						LLVMValueRef defaultVal = visit(defaultCtx); // Visit the default value expression (e.g., "0")
						if (defaultVal == null)
						{
							Debug.logError("IR Error: Default value generation failed for param '" + param.getName() + "'.");
							return null;
						}

						// Cast it to the formal param type
						LLVMTypeRef formalType = TypeConverter.toLLVMType(param.getType());
						if (formalType == null)
						{
							Debug.logError("IR Error: Could not convert formal param type for default param '" + param.getName() + "'.");
							return null;
						}
						defaultVal = buildCast(builder, defaultVal, formalType, "default_val_cast" + i);
						args.add(defaultVal);
						Debug.logDebug("IR (Primary): Added default arg for '" + param.getName() + "' llvmKind=" + LLVMGetTypeKind(LLVMTypeOf(defaultVal)));
					}
					else
					{
						// This should be caught by semantic analysis, but good to check.
						Debug.logError("IR Error: Missing argument for non-default parameter " + param.getName() + " in constructor call.");
						return null;
					}
				}
			}

			// 7. Get the constructor function from the module
			String mangledName = ctorSymbol.getMangledName();
			Debug.logDebug("IR (Primary): Constructor mangled name: " + mangledName);
			LLVMValueRef ctorFunc = LLVMGetNamedFunction(module, mangledName);
			LLVMTypeRef reliableFuncType; // <-- Declare up here

			if (ctorFunc == null)
			{
				// Need to create the prototype if it wasn't seen yet
				Debug.logDebug("IR (Primary): Constructor prototype not found. Creating LLVM declaration for: " + mangledName);
				List<LLVMTypeRef> paramTypes = new ArrayList<>();
				paramTypes.add(classLLVMPtrType); // 'this' pointer
				for (Type pType : ctorSymbol.getParameterTypes())
				{
					LLVMTypeRef pLLVM = TypeConverter.toLLVMType(pType);
					if (pLLVM == null)
					{
						Debug.logError("IR Error: Could not convert ctor parameter type to LLVM type for constructor: " + mangledName);
						return null;
					}
					paramTypes.add(pLLVM);
				}
				LLVMTypeRef returnType = TypeConverter.toLLVMType(ctorSymbol.getType()); // void
				if (returnType == null)
				{
					Debug.logError("IR Error: Could not convert constructor return type to LLVM type for: " + mangledName);
					return null;
				}
				LLVMTypeRef funcType = LLVMFunctionType(returnType, new PointerPointer<>(paramTypes.toArray(new LLVMTypeRef[0])), paramTypes.size(), 0);

				ctorFunc = LLVMAddFunction(module, mangledName, funcType);
				if (ctorFunc == null)
				{
					Debug.logError("IR Error: LLVMAddFunction failed for constructor: " + mangledName);
					return null;
				}
				reliableFuncType = funcType; // We just made it, so it's reliable
				Debug.logDebug("IR (Primary): Constructor prototype added to module.");
			}
			else
			{
				reliableFuncType = safeGetFunctionType(ctorFunc, ctorSymbol);
				if (reliableFuncType == null)
				{
					Debug.logError("IR Error: Failed to resolve reliable function type for constructor: " + mangledName);
					return null;
				}
			}

			// 8. Build the 'call' instruction
			// Convert your ArrayList to a standard Java array
			LLVMValueRef[] argsArray = args.toArray(new LLVMValueRef[0]);

			// Sanity: log args types and count before building call
			Debug.logDebug("IR (Primary): Calling ctor '" + mangledName + "' with " + argsArray.length + " args (including 'this').");
			for (int i = 0; i < argsArray.length; i++)
			{
				LLVMValueRef a = argsArray[i];
				if (a == null)
				{
					Debug.logError("IR Error: Null argument at position " + i + " for constructor call.");
					return null;
				}
				LLVMTypeRef t = LLVMTypeOf(a);
				Debug.logDebug("  arg[" + i + "] text=<> llvmKind=" + (t == null ? "null" : LLVMGetTypeKind(t)));
			}

			// Create the PointerPointer from the Java array (just like you did for malloc)
			PointerPointer<LLVMValueRef> argsPtr = new PointerPointer<>(argsArray);

			// This call is now safe and correct (we've checked ctorFunc and reliableFuncType)
			LLVMValueRef callRes = LLVMBuildCall2(builder, reliableFuncType, ctorFunc, argsPtr, argsArray.length, "ctor.call.tmp");
			if (callRes == null && LLVMGetReturnType(reliableFuncType) != LLVMVoidType())
			{
				Debug.logError("IR Error: Constructor call produced null value but constructor has non-void return.");
				// continue: constructors normally return void; we ignore callRes
			}

			// 9. The 'new' expression evaluates to the pointer to the new instance
			Debug.logDebug("IR (Primary): Successfully generated 'new' expression. Returning pointer to new instance.");
			return newInstancePtr;
		}

		// 4) Identifier usage
		if (ctx.ID() != null)
		{
			Debug.logDebug("Visiting primary expression for ID:" + ctx.ID().getText()); //
			String name = ctx.ID().getText();
			Debug.logDebug("IR (Primary): Looking up symbol for ID '" + name + "' using context with Hash: " + ctx.hashCode() + ", Interval: " + ctx.getSourceInterval());
			Optional<Symbol> symOpt = semanticAnalyzer.getResolvedSymbol(ctx); //

			if (symOpt.isPresent())
			{
				Symbol sym = symOpt.get();

				if (sym instanceof VariableSymbol varSym)
				{
					Debug.logDebug("Resolved:" + symOpt.get().getType().getName() + " " + symOpt.get().getName()); //

					// Find the alloca for this variable using the scope-aware lookup
					LLVMValueRef alloca = lookupVariable(varSym.getName()); // Use scope-aware lookup

					if (alloca == null && varSym.isStatic())
					{
						// It's a static variable, look it up in globals
						alloca = namedValues.get(varSym.getMangledName());
						if (alloca == null)
						{
							alloca = LLVMGetNamedGlobal(module, varSym.getMangledName());
						}
						if (alloca == null)
						{
							Debug.logError("IR: variable '" + varSym.getName() + "' (static) used but no alloca/global found.");
							return null;
						}
					}
					else if (alloca == null)
					{
						// If lookupVariable fails, the alloca is genuinely missing.
						Debug.logError("IR: variable '" + varSym.getName() + "' used but no alloca found in any scope. Are you missing an allocation?"); //
						return null; //
					}

					// If the variable is an array, return the pointer to its descriptor (the alloca) directly.
					// Do NOT load it. Callers (like foreach or array_access) expect the pointer.
					if (varSym.getType() instanceof ArrayType)
					{
						Debug.logDebug("IR (Primary): Resolved '" + name + "' as ArrayType. Returning alloca (pointer) directly.");
						return alloca;
					}

					// Load the variable value and return it
					LLVMTypeRef varType = TypeConverter.toLLVMType(varSym.getType()); //
					LLVMValueRef loaded = LLVMBuildLoad2(builder, varType, alloca, name + ".load"); //
					return loaded;
				}
				else if (sym instanceof MethodSymbol) //
				{
					// This is a method group (e.g., "getPi").
					// This is correct. The PostfixExpression visitor will handle the call.
					Debug.logDebug("IR: primary ID '" + name + "' resolved to method group: " + sym); //
					return null;
				}
				else if (sym instanceof ClassSymbol)
				{
					// This is a type name (e.g., "Vector3" in "Vector3.zero").
					// This is correct. The PostfixExpression visitor will handle the member access.
					Debug.logDebug("IR: primary ID '" + name + "' resolved to class/type: " + sym); //
					return null; // Return null, Postfix visitor will handle it.
				}
				else
				{
					// Not a variable symbol (could be a type name, etc.)
					Debug.logDebug("IR: primary ID '" + name + "' resolved to unhandled symbol: " + sym); //
					return null;
				}
			}
			else //
			{
				/* ... existing fallback comment ... */ //
				Debug.logError("IR (Primary): FAILED TO RESOLVE SYMBOL for ID '" + name + "' using context Hash: " + ctx.hashCode() + ", Interval: " + ctx.getSourceInterval());
				return null; //
			}
		}

		// 5) other primary forms (this, null, new, etc.) - simple fallback for now
		return visitChildren(ctx); //
	}

	@Override
	public LLVMValueRef visitAssignmentExpression(NebulaParser.AssignmentExpressionContext ctx)
	{
		// 1. Handle Pass-Through Case (not an assignment, e.g., just "a + b")
		if (ctx.assignmentOperator() == null)
		{
			return visit(ctx.conditionalExpression(0));
		}

		// 2. This is an assignment (LHS op= RHS)
		NebulaParser.ConditionalExpressionContext lhsCtx = ctx.conditionalExpression(0);
		NebulaParser.ConditionalExpressionContext rhsCtx = ctx.conditionalExpression(1);

		// 3. Get the L-Value (pointer) for the LHS
		LLVMValueRef lhsPointer = null;
		Type lhsNebulaType = null; // This will be the type of the *element* (e.g., int)

		// --- Find the LHS Pointer (L-Value) ---
		// We need the *address* (alloca or GEP result), not the value.
		// We must manually check for the simple variable case, because the default
		// visitPrimary(Source 3679) returns a *loaded value* (R-Value), which we can't assign to.

		String lhsText = lhsCtx.getText();
		// We check the *primary* context that the semantic analyzer associated with this expression
		Optional<Symbol> lhsSymOpt = semanticAnalyzer.getResolvedSymbol(lhsCtx);

		// Check if the resolved symbol for the *whole expression* is a simple variable
		if (lhsSymOpt.isPresent() && lhsSymOpt.get() instanceof VariableSymbol varSym && varSym.getName().equals(lhsText))
		{
			// Case 1: LHS is a simple ID (e.g., "a = ...")
			lhsPointer = lookupVariable(varSym.getName()); // Find the alloca
			lhsNebulaType = varSym.getType();
			Debug.logDebug("IR (Assign): LHS is simple variable '" + varSym.getName() + "'.");
		}
		else
		{
			// Case 2: LHS is a complex expression (e.g., "a[i] = ..." or "obj.field = ...")
			// We visit the LHS expression, *expecting* it to return a pointer (GEP result).
			// This relies on visitPostfixExpression (for array access ) working correctly.
			// Note: Field access (obj.field) is not implemented in your provided visitPostfixExpression.
			Debug.logDebug("IR (Assign): LHS is complex expression '" + lhsText + "'. Visiting to get pointer.");
			lhsPointer = visit(lhsCtx);

			// For complex L-Values (like a[i]), the *resolved type* of the expression
			// is the type of the element itself (e.g., int for a[i]).
			Optional<Type> typeOpt = semanticAnalyzer.getResolvedType(lhsCtx);
			if (typeOpt.isPresent())
			{
				lhsNebulaType = typeOpt.get();
			}
		}

		// 4. Validate the LHS pointer
		if (lhsPointer == null)
		{
			Debug.logError("IR Error: Could not resolve LHS of assignment to a memory location: " + lhsCtx.getText());
			return null;
		}
		if (LLVMGetTypeKind(LLVMTypeOf(lhsPointer)) != LLVMPointerTypeKind)
		{
			Debug.logError("IR Error: Cannot assign to a non-variable (R-Value): " + lhsCtx.getText());
			return null;
		}
		if (lhsNebulaType == null)
		{
			Debug.logError("IR Error: Could not determine semantic type of LHS: " + lhsCtx.getText());
			return null;
		}

		// 5. Get the LLVM type of the element we're storing into (e.g., i32 from i32*)
		LLVMTypeRef targetLLVMType = LLVMGetElementType(LLVMTypeOf(lhsPointer));

		// 6. Get the RHS value
		LLVMValueRef rhsValue = visit(rhsCtx);
		if (rhsValue == null)
		{
			Debug.logError("IR Error: Could not generate code for RHS of assignment: " + rhsCtx.getText());
			return null;
		}

		// 7. Cast RHS value to match the LHS type
		LLVMValueRef castedRhsValue = buildCast(builder, rhsValue, targetLLVMType, "assign_cast");

		// 8. Handle compound assignment (+=, -=, etc.)
		String op = ctx.assignmentOperator().getText();
		LLVMValueRef finalValueToStore;

		if (op.equals("="))
		{
			finalValueToStore = castedRhsValue;
		}
		else
		{
			// It's compound, so we need the *current* value from the LHS
			LLVMValueRef currentValue = LLVMBuildLoad2(builder, targetLLVMType, lhsPointer, "load_for_compound");

			boolean isFloat = (LLVMGetTypeKind(targetLLVMType) == LLVMFloatTypeKind || LLVMGetTypeKind(targetLLVMType) == LLVMDoubleTypeKind);
			// Use the *semantic* type to determine signedness, not the LLVM type
			boolean isUnsigned = lhsNebulaType.getName().startsWith("u");

			switch (op)
			{
				case "+=":
					finalValueToStore = isFloat ? LLVMBuildFAdd(builder, currentValue, castedRhsValue, "fadd_compound")
							: LLVMBuildAdd(builder, currentValue, castedRhsValue, "add_compound");
					break;
				case "-=":
					finalValueToStore = isFloat ? LLVMBuildFSub(builder, currentValue, castedRhsValue, "fsub_compound")
							: LLVMBuildSub(builder, currentValue, castedRhsValue, "sub_compound");
					break;
				case "*=":
					finalValueToStore = isFloat ? LLVMBuildFMul(builder, currentValue, castedRhsValue, "fmul_compound")
							: LLVMBuildMul(builder, currentValue, castedRhsValue, "mul_compound");
					break;
				case "/=":
					if (isFloat)
					{
						finalValueToStore = LLVMBuildFDiv(builder, currentValue, castedRhsValue, "fdiv_compound");
					}
					else
					{
						finalValueToStore = isUnsigned ? LLVMBuildUDiv(builder, currentValue, castedRhsValue, "udiv_compound")
								: LLVMBuildSDiv(builder, currentValue, castedRhsValue, "sdiv_compound");
					}
					break;
				case "%=":
					if (isFloat)
					{
						finalValueToStore = LLVMBuildFRem(builder, currentValue, castedRhsValue, "frem_compound");
					}
					else
					{
						finalValueToStore = isUnsigned ? LLVMBuildURem(builder, currentValue, castedRhsValue, "urem_compound")
								: LLVMBuildSRem(builder, currentValue, castedRhsValue, "srem_compound");
					}
					break;
				// Bitwise operators (only for integers)
				case "&=":
					finalValueToStore = LLVMBuildAnd(builder, currentValue, castedRhsValue, "and_compound");
					break;
				case "|=":
					finalValueToStore = LLVMBuildOr(builder, currentValue, castedRhsValue, "or_compound");
					break;
				case "^=":
					finalValueToStore = LLVMBuildXor(builder, currentValue, castedRhsValue, "xor_compound");
					break;
				case "<<=":
					finalValueToStore = LLVMBuildShl(builder, currentValue, castedRhsValue, "shl_compound");
					break;
				case ">>=":
					finalValueToStore = isUnsigned ? LLVMBuildLShr(builder, currentValue, castedRhsValue, "lshr_compound")
							: LLVMBuildAShr(builder, currentValue, castedRhsValue, "ashr_compound");
					break;
				default:
					Debug.logError("IR Error: Unimplemented assignment operator: " + op);
					return null;
			}
		}

		// 9. Store the final value
		LLVMBuildStore(builder, finalValueToStore, lhsPointer);

		// 10. Assignment expressions return the stored value
		return finalValueToStore;
	}

	@Override
	public LLVMValueRef visitMultiplicativeExpression(NebulaParser.MultiplicativeExpressionContext ctx)
	{
		Debug.logDebug("IR (Multiplicative): Visiting: " + ctx.getText() +
				" (Hash: " + ctx.hashCode() +
				", Interval: " + ctx.getSourceInterval() + ")");

		if (ctx.powerExpression().size() == 1)
		{
			Debug.logDebug("IR (Multiplicative): Visiting single PowerExpression child: " + ctx.powerExpression(0).getText() +
					" (Hash: " + ctx.powerExpression(0).hashCode() +
					", Interval: " + ctx.powerExpression(0).getSourceInterval() + ")");
			return visit(ctx.powerExpression(0));
		}

		Debug.logDebug("IR (Multiplicative): Visiting left PowerExpression child: " + ctx.powerExpression(0).getText() +
				" (Hash: " + ctx.powerExpression(0).hashCode() +
				", Interval: " + ctx.powerExpression(0).getSourceInterval() + ")");
		LLVMValueRef leftVal = visit(ctx.powerExpression(0));

		for (int i = 1; i < ctx.powerExpression().size(); i++)
		{
			Debug.logDebug("IR (Multiplicative): Visiting right PowerExpression child #" + i + ": " + ctx.powerExpression(i).getText() +
					" (Hash: " + ctx.powerExpression(i).hashCode() +
					", Interval: " + ctx.powerExpression(i).getSourceInterval() + ")");
			LLVMValueRef rightVal = visit(ctx.powerExpression(i));
			String op = ctx.getChild(2 * i - 1).getText(); // Get the operator (e.g., "*")

			if (leftVal == null || rightVal == null)
			{
				return null;
			}

			// 1. Get the final result type (determined by the type checker)
			Optional<org.lokray.semantic.type.Type> resultTypeOpt = semanticAnalyzer.getResolvedType(ctx);
			if (resultTypeOpt.isEmpty())
			{
				Debug.logError("IR: Multiplicative expression result type not found."); //
				return null; //
			}
			LLVMTypeRef resultLLVMType = TypeConverter.toLLVMType(resultTypeOpt.get());

			// 2. Cast operands to the result type
			LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "mult_lhs");
			LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "mult_rhs");

			LLVMTypeRef finalType = LLVMTypeOf(leftCasted); // Now should be the common widest type

			// 3. Select the operation based on the type
			if (LLVMGetTypeKind(finalType) == LLVMDoubleTypeKind || LLVMGetTypeKind(finalType) == LLVMFloatTypeKind)
			{
				// Floating-point arithmetic
				switch (op)
				{
					case "*":
						leftVal = LLVMBuildFMul(builder, leftCasted, rightCasted, "fmul_tmp"); //
						break;
					case "/":
						leftVal = LLVMBuildFDiv(builder, leftCasted, rightCasted, "fdiv_tmp");
						break;
					case "%":
						leftVal = LLVMBuildFRem(builder, leftCasted, rightCasted, "frem_tmp"); //
						break;
					default:
						Debug.logError("IR: Unknown FP multiplicative operator: " + op);
						return null;
				}
			}
			else if (LLVMGetTypeKind(finalType) == LLVMIntegerTypeKind) //
			{
				// Integer arithmetic (assuming signed for Div and Rem)
				switch (op)
				{
					case "*":
						leftVal = LLVMBuildMul(builder, leftCasted, rightCasted, "mul_tmp"); //
						break;
					case "/":
						leftVal = LLVMBuildSDiv(builder, leftCasted, rightCasted, "sdiv_tmp"); // Signed division //
						break; //
					case "%":
						leftVal = LLVMBuildSRem(builder, leftCasted, rightCasted, "srem_tmp"); // Signed remainder
						break;
					default:
						Debug.logError("IR: Unknown Int multiplicative operator: " + op); //
						return null;
				}
			}
			else //
			{
				Debug.logError("IR: Unsupported types for multiplicative operation.");
				return null;
			}
		}

		return leftVal; // Return the final accumulated value
	}

	@Override
	public LLVMValueRef visitLogicalOrExpression(NebulaParser.LogicalOrExpressionContext ctx)
	{
		if (ctx.logicalAndExpression().size() == 1)
		{
			return visit(ctx.logicalAndExpression(0));
		}

		LLVMTypeRef i1Type = LLVMInt1Type();
		LLVMBasicBlockRef currentBlock = LLVMGetInsertBlock(builder);
		LLVMValueRef entryFunction = LLVMGetBasicBlockParent(currentBlock);

		LLVMBasicBlockRef endBlock = LLVMAppendBasicBlock(entryFunction, "lor.end");

		LLVMValueRef phi = LLVMBuildPhi(builder, i1Type, "lor.phi");

		// Visit the first operand
		LLVMValueRef leftVal = visit(ctx.logicalAndExpression(0));
		LLVMValueRef leftBool = TypeConverter.toBoolean(leftVal, null, builder);
		currentBlock = LLVMGetInsertBlock(builder);

		// Explicitly create single-element arrays for arguments
		LLVMValueRef[] incomingValue1 = {leftBool};
		LLVMBasicBlockRef[] incomingBlock1 = {currentBlock};
		LLVMAddIncoming(phi, new PointerPointer(incomingValue1), new PointerPointer(incomingBlock1), 1);

		for (int i = 1; i < ctx.logicalAndExpression().size(); i++)
		{
			LLVMBasicBlockRef nextOperandBlock = LLVMAppendBasicBlock(entryFunction, "lor.next");

			LLVMBuildCondBr(builder, leftBool, endBlock, nextOperandBlock);

			LLVMPositionBuilderAtEnd(builder, nextOperandBlock);
			LLVMValueRef rightVal = visit(ctx.logicalAndExpression(i));

			LLVMValueRef rightBool = TypeConverter.toBoolean(rightVal, null, builder);
			currentBlock = LLVMGetInsertBlock(builder);

			// Explicitly create single-element arrays for arguments
			LLVMValueRef[] incomingValue2 = {rightBool};
			LLVMBasicBlockRef[] incomingBlock2 = {currentBlock};
			LLVMAddIncoming(phi, new PointerPointer(incomingValue2), new PointerPointer(incomingBlock2), 1);

			LLVMBuildBr(builder, endBlock);

			leftBool = rightBool;
		}

		LLVMPositionBuilderAtEnd(builder, endBlock);

		return phi;
	}

	@Override
	public LLVMValueRef visitLogicalAndExpression(NebulaParser.LogicalAndExpressionContext ctx)
	{
		if (ctx.bitwiseOrExpression().size() == 1)
		{
			return visit(ctx.bitwiseOrExpression(0));
		}

		LLVMTypeRef i1Type = LLVMInt1Type();
		LLVMBasicBlockRef currentBlock = LLVMGetInsertBlock(builder);
		LLVMValueRef entryFunction = LLVMGetBasicBlockParent(currentBlock);

		LLVMBasicBlockRef endBlock = LLVMAppendBasicBlock(entryFunction, "land.end");

		LLVMValueRef phi = LLVMBuildPhi(builder, i1Type, "land.phi");

		// Visit the first operand
		LLVMValueRef leftVal = visit(ctx.bitwiseOrExpression(0));
		LLVMValueRef leftBool = TypeConverter.toBoolean(leftVal, null, builder);
		currentBlock = LLVMGetInsertBlock(builder);

		// Explicitly create single-element arrays for arguments
		LLVMValueRef[] incomingValue3 = {leftBool};
		LLVMBasicBlockRef[] incomingBlock3 = {currentBlock};
		LLVMAddIncoming(phi, new PointerPointer(incomingValue3), new PointerPointer(incomingBlock3), 1);


		for (int i = 1; i < ctx.bitwiseOrExpression().size(); i++)
		{
			LLVMBasicBlockRef nextOperandBlock = LLVMAppendBasicBlock(entryFunction, "land.next");

			LLVMBuildCondBr(builder, leftBool, nextOperandBlock, endBlock);

			LLVMPositionBuilderAtEnd(builder, nextOperandBlock);
			LLVMValueRef rightVal = visit(ctx.bitwiseOrExpression(i));

			LLVMValueRef rightBool = TypeConverter.toBoolean(rightVal, null, builder);
			currentBlock = LLVMGetInsertBlock(builder);

			// Explicitly create single-element arrays for arguments
			LLVMValueRef[] incomingValue4 = {rightBool};
			LLVMBasicBlockRef[] incomingBlock4 = {currentBlock};
			LLVMAddIncoming(phi, new PointerPointer(incomingValue4), new PointerPointer(incomingBlock4), 1);

			LLVMBuildBr(builder, endBlock);

			leftBool = rightBool;
		}

		LLVMPositionBuilderAtEnd(builder, endBlock);

		return phi;
	}

	@Override
	public LLVMValueRef visitBitwiseOrExpression(NebulaParser.BitwiseOrExpressionContext ctx)
	{
		if (ctx.bitwiseXorExpression().size() == 1)
		{
			return visit(ctx.bitwiseXorExpression(0));
		}

		LLVMValueRef leftVal = visit(ctx.bitwiseXorExpression(0));

		// Get the final expected type for the whole expression
		Optional<org.lokray.semantic.type.Type> resultTypeOpt = semanticAnalyzer.getResolvedType(ctx);
		if (resultTypeOpt.isEmpty() || !(resultTypeOpt.get().isInteger()))
		{
			org.lokray.util.Debug.logError("IR: BitwiseOr expression type not found or not integer.");
			return null;
		}
		LLVMTypeRef resultLLVMType = org.lokray.codegen.TypeConverter.toLLVMType(resultTypeOpt.get());

		// Cast left operand to the final result type
		LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "or_lhs_cast");

		for (int i = 1; i < ctx.bitwiseXorExpression().size(); i++)
		{
			LLVMValueRef rightVal = visit(ctx.bitwiseXorExpression(i));
			if (leftCasted == null || rightVal == null)
			{
				return null;
			}

			LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "or_rhs_cast");

			leftCasted = LLVMBuildOr(builder, leftCasted, rightCasted, "or_tmp");
		}
		return leftCasted;
	}

	@Override
	public LLVMValueRef visitBitwiseXorExpression(NebulaParser.BitwiseXorExpressionContext ctx)
	{
		if (ctx.bitwiseAndExpression().size() == 1)
		{
			return visit(ctx.bitwiseAndExpression(0));
		}

		LLVMValueRef leftVal = visit(ctx.bitwiseAndExpression(0));

		Optional<org.lokray.semantic.type.Type> resultTypeOpt = semanticAnalyzer.getResolvedType(ctx);
		if (resultTypeOpt.isEmpty() || !(resultTypeOpt.get().isInteger()))
		{
			org.lokray.util.Debug.logError("IR: BitwiseXor expression type not found or not integer.");
			return null;
		}
		LLVMTypeRef resultLLVMType = org.lokray.codegen.TypeConverter.toLLVMType(resultTypeOpt.get());

		LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "xor_lhs_cast");

		for (int i = 1; i < ctx.bitwiseAndExpression().size(); i++)
		{
			LLVMValueRef rightVal = visit(ctx.bitwiseAndExpression(i));
			if (leftCasted == null || rightVal == null)
			{
				return null;
			}

			LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "xor_rhs_cast");

			leftCasted = LLVMBuildXor(builder, leftCasted, rightCasted, "xor_tmp");
		}
		return leftCasted;
	}

	@Override
	public LLVMValueRef visitBitwiseAndExpression(NebulaParser.BitwiseAndExpressionContext ctx)
	{
		if (ctx.equalityExpression().size() == 1)
		{
			return visit(ctx.equalityExpression(0));
		}

		LLVMValueRef leftVal = visit(ctx.equalityExpression(0));

		Optional<org.lokray.semantic.type.Type> resultTypeOpt = semanticAnalyzer.getResolvedType(ctx);
		if (resultTypeOpt.isEmpty() || !(resultTypeOpt.get().isInteger()))
		{
			org.lokray.util.Debug.logError("IR: BitwiseAnd expression type not found or not integer.");
			return null;
		}
		LLVMTypeRef resultLLVMType = org.lokray.codegen.TypeConverter.toLLVMType(resultTypeOpt.get());

		LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "and_lhs_cast");

		for (int i = 1; i < ctx.equalityExpression().size(); i++)
		{
			LLVMValueRef rightVal = visit(ctx.equalityExpression(i));
			if (leftCasted == null || rightVal == null)
			{
				return null;
			}

			LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "and_rhs_cast");

			leftCasted = LLVMBuildAnd(builder, leftCasted, rightCasted, "and_tmp");
		}
		return leftCasted;
	}

	@Override
	public LLVMValueRef visitShiftExpression(NebulaParser.ShiftExpressionContext ctx)
	{
		if (ctx.additiveExpression().size() == 1)
		{
			return visit(ctx.additiveExpression(0));
		}

		LLVMValueRef leftVal = visit(ctx.additiveExpression(0));

		// Get the type of the left-hand side, which determines the result type
		Optional<org.lokray.semantic.type.Type> leftTypeOpt = semanticAnalyzer.getResolvedType(ctx.additiveExpression(0));
		if (leftTypeOpt.isEmpty() || !(leftTypeOpt.get().isInteger()))
		{
			org.lokray.util.Debug.logError("IR: Shift expression left operand type not found or not integer.");
			return null;
		}
		LLVMTypeRef resultLLVMType = org.lokray.codegen.TypeConverter.toLLVMType(leftTypeOpt.get());

		// This logic depends on TypeConverter/Type information, assuming it's available.
		boolean isUnsigned = leftTypeOpt.get().getName().startsWith("u");

		LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "sh_lhs_cast");

		for (int i = 1; i < ctx.additiveExpression().size(); i++)
		{
			LLVMValueRef rightVal = visit(ctx.additiveExpression(i));
			String op = ctx.getChild(2 * i - 1).getText();

			if (leftCasted == null || rightVal == null)
			{
				return null;
			}

			// Get type of right operand for casting purposes
			Optional<org.lokray.semantic.type.Type> rightTypeOpt = semanticAnalyzer.getResolvedType(ctx.additiveExpression(i));
			if (rightTypeOpt.isEmpty() || !(rightTypeOpt.get().isInteger()))
			{
				org.lokray.util.Debug.logError("IR: Shift expression right operand type not found or not integer.");
				return null;
			}

			// Shift amount (right side) MUST match the type of the left side (resultLLVMType)
			LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "sh_rhs_cast");

			switch (op)
			{
				case "<<":
					leftCasted = LLVMBuildShl(builder, leftCasted, rightCasted, "shl_tmp");
					break;
				case ">>":
					if (isUnsigned)
					{
						leftCasted = LLVMBuildLShr(builder, leftCasted, rightCasted, "lshr_tmp"); // Logical Shift Right
					}
					else
					{
						leftCasted = LLVMBuildAShr(builder, leftCasted, rightCasted, "ashr_tmp"); // Arithmetic Shift Right
					}
					break;
				default:
					org.lokray.util.Debug.logError("IR: Unknown shift operator: " + op);
					return null;
			}
		}
		return leftCasted;
	}

	@Override
	public LLVMValueRef visitUnaryExpression(NebulaParser.UnaryExpressionContext ctx)
	{
		Debug.logDebug("IR (Unary): Visiting: " + ctx.getText() +
				" (Hash: " + ctx.hashCode() +
				", Interval: " + ctx.getSourceInterval() + ")");

		// --- START FIX ---
		// The grammar changed from a generic 'op' field to specific token methods.
		if (ctx.ADD_OP() != null)
		{
			Debug.logDebug("IR (Unary): Operator '+' present. Visiting nested UnaryExpression: " + ctx.unaryExpression().getText() +
					" (Hash: " + ctx.unaryExpression().hashCode() +
					", Interval: " + ctx.unaryExpression().getSourceInterval() + ")");
			LLVMValueRef operand = visit(ctx.unaryExpression());
			return operand; // Unary plus is a no-op
		}

		if (ctx.SUB_OP() != null)
		{
			Debug.logDebug("IR (Unary): Operator '-' present. Visiting nested UnaryExpression: " + ctx.unaryExpression().getText() +
					" (Hash: " + ctx.unaryExpression().hashCode() +
					", Interval: " + ctx.unaryExpression().getSourceInterval() + ")");
			LLVMValueRef operand = visit(ctx.unaryExpression());
			if (operand == null)
			{
				return null;
			}
			LLVMTypeRef type = LLVMTypeOf(operand);
			if (LLVMGetTypeKind(type) == LLVMIntegerTypeKind)
			{
				return LLVMBuildNeg(builder, operand, "neg_tmp");
			}
			else if (LLVMGetTypeKind(type) == LLVMFloatTypeKind || LLVMGetTypeKind(type) == LLVMDoubleTypeKind)
			{
				return LLVMBuildFNeg(builder, operand, "fneg_tmp");
			}
			else
			{
				Debug.logError("IR: Unary minus applied to non-numeric type: " + ctx.getText());
				return null;
			}
		}

		if (ctx.LOG_NOT_OP() != null)
		{
			Debug.logDebug("IR (Unary): Operator '!' present. Visiting nested UnaryExpression: " + ctx.unaryExpression().getText() +
					" (Hash: " + ctx.unaryExpression().hashCode() +
					", Interval: " + ctx.unaryExpression().getSourceInterval() + ")");
			LLVMValueRef operand = visit(ctx.unaryExpression());
			if (operand == null)
			{
				return null;
			}
			// Convert operand to boolean (i1) if it isn't already
			LLVMValueRef boolOperand = TypeConverter.toBoolean(operand, null, builder); // Simplified context passing for now
			// XOR with true (1) to negate
			LLVMValueRef one = LLVMConstAllOnes(LLVMTypeOf(boolOperand)); // produces all-ones of same type (i1 -> true)
			return LLVMBuildXor(builder, boolOperand, one, "lognot_tmp");
		}

		if (ctx.BIT_NOT_OP() != null)
		{
			Debug.logDebug("IR (Unary): Operator '~' present. Visiting nested UnaryExpression: " + ctx.unaryExpression().getText() +
					" (Hash: " + ctx.unaryExpression().hashCode() +
					", Interval: " + ctx.unaryExpression().getSourceInterval() + ")");
			LLVMValueRef operand = visit(ctx.unaryExpression());
			if (operand == null)
			{
				return null;
			}
			if (LLVMGetTypeKind(LLVMTypeOf(operand)) == LLVMIntegerTypeKind)
			{
				// LLVM's bitwise not is XOR with -1 (all bits set)
				LLVMValueRef minusOne = LLVMConstAllOnes(LLVMTypeOf(operand));
				return LLVMBuildXor(builder, operand, minusOne, "bitnot_tmp");
			}
			else
			{
				Debug.logError("IR: Bitwise NOT applied to non-integer type: " + ctx.getText());
				return null;
			}
		}

		if (ctx.INC_OP() != null) // Pre-increment
		{
			Debug.logDebug("IR (Unary): Operator '++' present. Visiting nested UnaryExpression: " + ctx.unaryExpression().getText() +
					" (Hash: " + ctx.unaryExpression().hashCode() +
					", Interval: " + ctx.unaryExpression().getSourceInterval() + ")");
			LLVMValueRef operand = visit(ctx.unaryExpression());
			if (operand == null)
			{
				return null;
			}
			// These require finding the variable's alloca, loading, incrementing/decrementing, storing back, and returning the *new* value.
			// This logic is more complex and depends on the operand being a valid l-value (variable/field).
			// For now, let's skip implementation and log a warning.
			Debug.logWarning("IR: Pre-increment/decrement not yet fully implemented in IRVisitor for: " + ctx.getText());
			return operand; // Placeholder
		}

		if (ctx.DEC_OP() != null) // Pre-decrement
		{
			Debug.logDebug("IR (Unary): Operator '--' present. Visiting nested UnaryExpression: " + ctx.unaryExpression().getText() +
					" (Hash: " + ctx.unaryExpression().hashCode() +
					", Interval: " + ctx.unaryExpression().getSourceInterval() + ")");
			LLVMValueRef operand = visit(ctx.unaryExpression());
			if (operand == null)
			{
				return null;
			}
			Debug.logWarning("IR: Pre-increment/decrement not yet fully implemented in IRVisitor for: " + ctx.getText());
			return operand; // Placeholder
		}
		// --- END FIX ---

		// No prefix operator, must be cast or postfix
		if (ctx.castExpression() != null)
		{
			Debug.logDebug("IR (Unary): Visiting CastExpression child: " + ctx.castExpression().getText() +
					" (Hash: " + ctx.castExpression().hashCode() +
					", Interval: " + ctx.castExpression().getSourceInterval() + ")");
			return visit(ctx.castExpression());
		}
		else if (ctx.postfixExpression() != null)
		{
			Debug.logDebug("IR (Unary): Visiting PostfixExpression child: " + ctx.postfixExpression().getText() +
					" (Hash: " + ctx.postfixExpression().hashCode() +
					", Interval: " + ctx.postfixExpression().getSourceInterval() + ")");
			return visit(ctx.postfixExpression());
		}

		// Should not happen based on grammar
		return null;
	}

	@Override
	public LLVMValueRef visitAdditiveExpression(NebulaParser.AdditiveExpressionContext ctx)
	{
		if (ctx.multiplicativeExpression().size() == 1)
		{
			return visit(ctx.multiplicativeExpression(0));
		}

		LLVMValueRef leftVal = visit(ctx.multiplicativeExpression(0));

		for (int i = 1; i < ctx.multiplicativeExpression().size(); i++)
		{
			LLVMValueRef rightVal = visit(ctx.multiplicativeExpression(i));
			String op = ctx.getChild(2 * i - 1).getText(); // Get the operator (e.g., "+")

			if (leftVal == null || rightVal == null)
			{
				return null;
			}

			// 1. Get the final result type (determined by the type checker)
			Optional<org.lokray.semantic.type.Type> resultTypeOpt = semanticAnalyzer.getResolvedType(ctx);
			if (resultTypeOpt.isEmpty())
			{
				Debug.logError("IR: Additive expression result type not found.");
				return null;
			}

			// --- START FIX: Handle R-Value vs L-Value ---
			// If the operands are pointers (L-Values), load them to get R-Values
			// unless the result type is *also* a pointer (e.g. a class type)
			Type resultNebulaType = resultTypeOpt.get();
			LLVMTypeRef resultLLVMType = TypeConverter.toLLVMType(resultNebulaType);

			LLVMValueRef leftOperand = leftVal;
			LLVMValueRef rightOperand = rightVal;

			// If leftVal is ptr %Vector3* and result is %Vector3*, we need to load
			if (LLVMGetTypeKind(LLVMTypeOf(leftVal)) == LLVMPointerTypeKind)
			{
				leftOperand = LLVMBuildLoad2(builder, LLVMGetElementType(LLVMTypeOf(leftVal)), leftVal, "add.lhs.load");
			}
			if (LLVMGetTypeKind(LLVMTypeOf(rightVal)) == LLVMPointerTypeKind)
			{
				rightOperand = LLVMBuildLoad2(builder, LLVMGetElementType(LLVMTypeOf(rightVal)), rightVal, "add.rhs.load");
			}

			// 2. Cast operands to the result type (if needed, e.g. int to float)
			LLVMValueRef leftCasted = buildCast(builder, leftOperand, resultLLVMType, "add_lhs");
			LLVMValueRef rightCasted = buildCast(builder, rightOperand, resultLLVMType, "add_rhs");
			// --- END FIX ---

			LLVMTypeRef finalType = LLVMTypeOf(leftCasted);

			// 3. Select the operation based on the type
			if (LLVMGetTypeKind(finalType) == LLVMDoubleTypeKind || LLVMGetTypeKind(finalType) == LLVMFloatTypeKind)
			{
				// Floating-point arithmetic
				switch (op)
				{
					case "+":
						leftVal = LLVMBuildFAdd(builder, leftCasted, rightCasted, "fadd_tmp");
						break;
					case "-":
						leftVal = LLVMBuildFSub(builder, leftCasted, rightCasted, "fsub_tmp");
						break;
					default:
						Debug.logError("IR: Unknown FP additive operator: " + op);
						return null;
				}
			}
			else if (LLVMGetTypeKind(finalType) == LLVMIntegerTypeKind)
			{
				// Integer arithmetic
				switch (op)
				{
					case "+":
						leftVal = LLVMBuildAdd(builder, leftCasted, rightCasted, "add_tmp");
						break;
					case "-":
						leftVal = LLVMBuildSub(builder, leftCasted, rightCasted, "sub_tmp");
						break;
					default:
						Debug.logError("IR: Unknown Int additive operator: " + op);
						return null;
				}
			}
			// --- START FIX: OPERATOR OVERLOADING ---
			else
			{
				// Not numeric. Check for operator overload symbol.
				Optional<Symbol> opSymbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
				if (opSymbolOpt.isPresent() && opSymbolOpt.get() instanceof MethodSymbol opMethod)
				{
					Debug.logDebug("IR: Found operator overload: " + opMethod.getMangledName());
					LLVMValueRef opFunc = LLVMGetNamedFunction(module, opMethod.getMangledName());
					if (opFunc == null)
					{
						Debug.logError("IR: Operator overload function " + opMethod.getMangledName() + " not found in module.");
						return null;
					}

					// Note: Overload is an *instance* method. 'leftCasted' is the 'this' pointer.
					// 'rightCasted' is the first argument.
					// **CRITICAL**: The values passed to the function must be the *pointers*, not
					// the loaded values, because the types are class types (e.g. Vector3)
					LLVMValueRef[] args = {leftVal, rightVal}; // Use the original L-Values
					LLVMTypeRef funcType = safeGetFunctionType(opFunc, opMethod); // Use your helper

					leftVal = LLVMBuildCall2(builder, funcType, opFunc, new PointerPointer<>(args), args.length, "op.call");
				}
				else
				{
					Debug.logError("IR: Unsupported types for additive operation and no operator overload found.");
					return null;
				}
			}
			// --- END FIX ---
		}

		return leftVal; // Return the final accumulated value
	}

	private LLVMValueRef createEntryBlockAlloca(LLVMValueRef function, LLVMTypeRef type, String varName)
	{
		LLVMBasicBlockRef entryBlock = LLVMGetEntryBasicBlock(function); //
		LLVMBuilderRef tmpBuilder = LLVMCreateBuilder(); // Create a temporary builder
		// Try to insert before the first non-alloca instruction
		LLVMValueRef firstInstruction = LLVMGetFirstInstruction(entryBlock); //
		if (firstInstruction != null && LLVMIsAAllocaInst(firstInstruction) != null)
		{ // Check if first is alloca
			LLVMValueRef currentInst = firstInstruction;
			LLVMValueRef nextInst = LLVMGetNextInstruction(currentInst); //
			// Find the first instruction that is *not* an alloca
			while (nextInst != null && LLVMIsAAllocaInst(nextInst) != null)
			{ //
				currentInst = nextInst;
				nextInst = LLVMGetNextInstruction(currentInst); //
			}
			if (nextInst != null)
			{ // Found a non-alloca, position builder before it
				LLVMPositionBuilderBefore(tmpBuilder, nextInst); //
			}
			else
			{ // All instructions are allocas, position at the end of the block
				LLVMPositionBuilderAtEnd(tmpBuilder, entryBlock); //
			}
		}
		else if (firstInstruction != null)
		{ // First instruction is not alloca, position before it
			LLVMPositionBuilderBefore(tmpBuilder, firstInstruction); //
		}
		else
		{ // Block is empty, position at the end (which is the start)
			LLVMPositionBuilderAtEnd(tmpBuilder, entryBlock); //
		}

		LLVMValueRef alloca = LLVMBuildAlloca(tmpBuilder, type, varName); // Use the temporary builder
		LLVMDisposeBuilder(tmpBuilder); // Dispose the temporary builder
		return alloca;
	}

	// Creates an alloca in the function's entry block for a local variable/parameter.
// `func` is the LLVM function, `name` is the variable name, `type` is an LLVM type.
	private LLVMValueRef createEntryBlockAlloca(LLVMValueRef func, String name, LLVMTypeRef type)
	{
		// Save current insertion point
		LLVMBuilderRef oldBuilder = builder;

		// Create a temporary builder and position it at the start of the entry block
		LLVMBuilderRef tmpBuilder = LLVMCreateBuilder();
		LLVMBasicBlockRef entry = LLVMGetEntryBasicBlock(func);

		// If entry block doesn't exist yet, create it (defensive)
		if (entry == null)
		{
			entry = LLVMAppendBasicBlock(func, "entry");
		}

		LLVMPositionBuilderAtEnd(tmpBuilder, entry);

		// Build the alloca in the entry block
		LLVMValueRef alloca = LLVMBuildAlloca(tmpBuilder, type, name);

		// dispose the temporary builder
		LLVMDisposeBuilder(tmpBuilder);

		// restore previous builder (if you keep a single shared builder)
		// (if `builder` is a field, it's already unchanged; kept here for clarity)
		builder = oldBuilder;

		return alloca;
	}

	/**
	 * Pushes a new scope for variable declarations (e.g., a new block or function).
	 */
	private void pushScope()
	{
		scopedValues.push(new HashMap<>());
	}

	/**
	 * Pops the most recent scope, removing all variables declared within it.
	 */
	private void popScope()
	{
		if (!scopedValues.isEmpty())
		{
			scopedValues.pop();
		}
	}

	/**
	 * Adds a variable to the current (top) scope.
	 */
	private void addVariableToScope(String name, LLVMValueRef value)
	{
		if (scopedValues.isEmpty())
		{
			scopedValues.push(new HashMap<>());
		}
		scopedValues.peek().put(name, value);
	}

	/**
	 * Looks up a variable across all active scopes, from innermost to outermost.
	 */
	private LLVMValueRef lookupVariable(String name)
	{
		for (int i = scopedValues.size() - 1; i >= 0; i--)
		{
			Map<String, LLVMValueRef> scope = scopedValues.get(i);
			if (scope.containsKey(name))
			{
				return scope.get(name);
			}
		}
		return namedValues.get(name); // fallback for global or top-level vars
	}

	private LLVMValueRef buildCast(LLVMBuilderRef builder, LLVMValueRef sourceValue, LLVMTypeRef targetType, String name)
	{
		LLVMTypeRef sourceType = LLVMTypeOf(sourceValue);

		if (sourceType.equals(targetType))
		{
			return sourceValue; // No cast needed
		}

		int sourceKind = LLVMGetTypeKind(sourceType);
		int targetKind = LLVMGetTypeKind(targetType);

		// 1. Integer to Floating Point (SIToFP - assuming signed ints)
		if (sourceKind == LLVMIntegerTypeKind &&
				(targetKind == LLVMFloatTypeKind || targetKind == LLVMDoubleTypeKind))
		{
			return LLVMBuildSIToFP(builder, sourceValue, targetType, name + ".sitofp");
		}

		// 2. Floating Point Extension (float -> double)
		if (sourceKind == LLVMFloatTypeKind && targetKind == LLVMDoubleTypeKind)
		{
			return LLVMBuildFPExt(builder, sourceValue, targetType, name + ".fpext");
		}

		// 3. Floating Point Truncation (double -> float)
		if (sourceKind == LLVMDoubleTypeKind && targetKind == LLVMFloatTypeKind)
		{
			return LLVMBuildFPTrunc(builder, sourceValue, targetType, name + ".fptrunc");
		}

		// 4. Integer to Integer (SExt/ZExt/Trunc)
		if (sourceKind == LLVMIntegerTypeKind && targetKind == LLVMIntegerTypeKind)
		{
			int sourceBits = LLVMGetIntTypeWidth(sourceType);
			int targetBits = LLVMGetIntTypeWidth(targetType);

			if (targetBits > sourceBits)
			{
				// Assuming signed extension for arithmetic
				return LLVMBuildSExt(builder, sourceValue, targetType, name + ".sext");
			}
			else if (targetBits < sourceBits)
			{
				return LLVMBuildTrunc(builder, sourceValue, targetType, name + ".trunc");
			}
		}

		// Fallback for other complex or unsupported cases
		Debug.logDebug("IR: Attempted unsupported numeric cast from " + LLVMPrintTypeToString(sourceType) + " to " + LLVMPrintTypeToString(targetType));
		return sourceValue;
	}

	/**
	 * Helper to dig through the expression chain to find a nested array initializer.
	 * Based on the parse tree: expression -> ... -> primary -> arrayInitializer
	 */
	private NebulaParser.ArrayInitializerContext findArrayInitializer(NebulaParser.ExpressionContext ctx)
	{
		if (ctx == null)
		{
			return null;
		}
		try
		{
			// This chain matches the one used in visitVariableDeclaration
			return ctx.assignmentExpression()
					.conditionalExpression(0)
					.logicalOrExpression()
					.logicalAndExpression(0)
					.bitwiseOrExpression(0)
					.bitwiseXorExpression(0)
					.bitwiseAndExpression(0)
					.equalityExpression(0)
					.relationalExpression(0)
					.shiftExpression(0)
					.additiveExpression(0)
					.multiplicativeExpression(0)
					.powerExpression(0)
					.unaryExpression(0)
					.postfixExpression()
					.primary()
					.arrayInitializer();
		}
		catch (Exception e)
		{
			// This happens if any part of the chain is null, meaning it's not a simple array initializer
			return null;
		}
	}

	/**
	 * Dumps the current LLVM IR code from the module into a Java String.
	 * WARNING: The result of LLVMPrintModuleToString MUST be freed with LLVMDisposeMessage.
	 *
	 * @return The current LLVM IR code as a String.
	 */
	public String getIRCode()
	{
		// 1. Call the LLVM C API function. It returns the allocated buffer as a BytePointer.
		// This fixes the "cannot find symbol: method getString()" error.
		BytePointer outputBuffer = LLVMPrintModuleToString(this.module);

		// 2. Convert the C string (BytePointer) to a Java String.
		String irCode = outputBuffer.getString();

		// 3. IMPORTANT: Free the memory allocated by LLVMPrintModuleToString.
		// Using BytePointer as the type for the buffer fixes the "no suitable method found
		// for LLVMDisposeMessage(org.bytedeco.javacpp.Pointer)" error.
		LLVMDisposeMessage(outputBuffer);

		return irCode;
	}

	/**
	 * Retrieves the LLVM module generated by the visitor.
	 */
	public LLVMModuleRef getModule()
	{
		return module;
	}

	private LLVMTypeRef safeGetFunctionType(LLVMValueRef func, MethodSymbol methodSymbol)
	{
		LLVMTypeRef type = LLVMTypeOf(func);
		if (LLVMGetTypeKind(type) == LLVMPointerTypeKind)
		{
			LLVMTypeRef element = LLVMGetElementType(type);
			if (LLVMGetTypeKind(element) == LLVMFunctionTypeKind)
			{
				return element;
			}
		}

		// --- START FIX: Reconstruct type from MethodSymbol, handling 'this' ---
		List<Type> paramTypes = methodSymbol.getParameterTypes();
		List<LLVMTypeRef> llvmParamTypeList = new ArrayList<>();

		// Add 'this' as first parameter if instance method
		if (!methodSymbol.isStatic())
		{
			Type thisType = ((ClassSymbol) methodSymbol.getEnclosingScope()).getType();
			LLVMTypeRef thisLLVMType = TypeConverter.toLLVMType(thisType);
			llvmParamTypeList.add(thisLLVMType);
		}

		// Add remaining parameters
		for (Type paramType : paramTypes)
		{
			llvmParamTypeList.add(TypeConverter.toLLVMType(paramType));
		}

		LLVMTypeRef[] llvmParamTypes = llvmParamTypeList.toArray(new LLVMTypeRef[0]);
		LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType());
		return LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), llvmParamTypeList.size(), 0);
		// --- END FIX ---
	}

	/**
	 * Reusable helper to generate the IR for getting an element pointer (GEP)
	 * from an array descriptor, including bounds checking.
	 *
	 * @param baseDescPtr     Pointer to the nebula_Array_t descriptor (nebula_Array_t*)
	 * @param indexVal        The index to access (i32)
	 * @param elementLLVMType The *LLVM type* of the element (e.g., i8, i32, %nebula_string*)
	 * @return The pointer to the element (e.g., i8*, i32*, %nebula_string**), or null on error.
	 */
	private LLVMValueRef getArrayElementPointer(LLVMValueRef baseDescPtr, LLVMValueRef indexVal, LLVMTypeRef elementLLVMType)
	{
		Debug.logDebug("-> Entering getArrayElementPointer");

		// Don't infer the descriptor type; get it directly from the TypeConverter.
		// This ensures we GEP using the *struct type*, not just 'ptr'.
		LLVMTypeRef arrayDescriptorType = TypeConverter.getArrayDescStructType();
		Debug.logDebug("Array descriptor type: " + LLVMPrintTypeToString(arrayDescriptorType));

		LLVMTypeRef i32Type = LLVMInt32Type();
		LLVMTypeRef i8PtrType = LLVMPointerType(LLVMInt8Type(), 0);

		// GEP indices for accessing fields of a struct pointer:
		// Index 0: Dereference the pointer (always 0 for struct member access on a pointer)
		// Index 1: Field index within the struct (0 for data, 1 for size)
		LLVMValueRef zero = LLVMConstInt(i32Type, 0, 0); // i32 0
		LLVMValueRef dataIndex = LLVMConstInt(i32Type, 0, 0); // i32 0
		LLVMValueRef sizeIndex = LLVMConstInt(i32Type, 1, 0); // i32 1

		// 1. Get pointer to the 'data' field (Index 0)
		// This will generate: getelementptr <aggregate type>, <pointer>, i32 0, i32 0
		Debug.logDebug("-> GEP: Getting pointer to data field (Index 0)...");
		LLVMValueRef[] dataIndices = {zero, dataIndex};
		LLVMValueRef dataPtrPtr = LLVMBuildGEP2(
				builder,
				arrayDescriptorType, // <--- Base Type: The struct aggregate type (%nebula_Array_t)
				baseDescPtr,         // <--- Base Pointer: Pointer to the struct (ptr %nebula_Array_t)
				new PointerPointer<>(dataIndices),
				2,
				"arr.data.ptr.ptr"
		);

		// 2. Load the actual 'data' pointer (the i8* value)
		Debug.logDebug("-> GEP: Loading data pointer...");
		LLVMValueRef dataPtrI8 = LLVMBuildLoad2(builder, i8PtrType, dataPtrPtr, "arr.data.ptr");

		// 3. Get pointer to the 'size' field (Index 1)
		// This will generate: getelementptr <aggregate type>, <pointer>, i32 0, i32 1
		Debug.logDebug("-> GEP: Getting pointer to size field (Index 1)...");
		LLVMValueRef[] sizeIndices = {zero, sizeIndex};
		LLVMValueRef sizePtr = LLVMBuildGEP2(
				builder,
				arrayDescriptorType, // <--- Base Type: The struct aggregate type (%nebula_Array_t)
				baseDescPtr,         // <--- Base Pointer: Pointer to the struct (ptr %nebula_Array_t)
				new PointerPointer<>(sizeIndices),
				2,
				"arr.size.ptr"
		);

		// 4. Load the 'size' value
		Debug.logDebug("-> GEP: Loading size value...");
		LLVMValueRef sizeVal = LLVMBuildLoad2(builder, i32Type, sizePtr, "arr.size");

		// Bounds Check Logic (Remains the same)
		Debug.logDebug("-> GEP: Building bounds check...");
		LLVMBasicBlockRef currentBlock = LLVMGetInsertBlock(builder); //
		LLVMValueRef function = LLVMGetBasicBlockParent(currentBlock);

		// Insert new blocks *after* the current block, but before
		// any subsequent blocks (like loop.exit).
		LLVMBasicBlockRef nextBlock = LLVMGetNextBasicBlock(currentBlock);
		LLVMBasicBlockRef errorBlock;
		LLVMBasicBlockRef okBlock;

		if (nextBlock != null)
		{
			// Insert before the next block
			errorBlock = LLVMInsertBasicBlock(nextBlock, "bounds.err");
			okBlock = LLVMInsertBasicBlock(nextBlock, "bounds.ok");
		}
		else
		{
			// We are at the end of the function, so just append
			errorBlock = LLVMAppendBasicBlock(function, "bounds.err"); //
			okBlock = LLVMAppendBasicBlock(function, "bounds.ok"); //
		}

		// Check: index < size
		LLVMValueRef boundsCheck = LLVMBuildICmp(builder, LLVMIntULT, indexVal, sizeVal, "bounds.check");
		LLVMBuildCondBr(builder, boundsCheck, okBlock, errorBlock);

		// Error Path: Unreachable for now (can be replaced by a runtime error call later)
		LLVMPositionBuilderAtEnd(builder, errorBlock);
		LLVMBuildUnreachable(builder);

		// OK Path: Continue
		LLVMPositionBuilderAtEnd(builder, okBlock);

		// Final GEP Logic (Remains the same)
		Debug.logDebug("-> GEP: Bitcasting data ptr...");
		// Cast i8* to ElementType*
		LLVMTypeRef elementPtrType = LLVMPointerType(elementLLVMType, 0);
		LLVMValueRef dataPtrTyped = LLVMBuildBitCast(builder, dataPtrI8, elementPtrType, "arr.data.ptr.typed");

		LLVMValueRef[] indices = {indexVal};
		Debug.logDebug("-> GEP: Building GEP instruction...");
		// GEP calculates the address of the element: BaseAddress + Index * SizeOf(ElementType)
		LLVMValueRef elementPtr = LLVMBuildGEP2(builder, elementLLVMType, dataPtrTyped, new PointerPointer<>(indices), 1, "arr.elem.ptr");

		Debug.logDebug("-> Exiting getArrayElementPointer");
		return elementPtr;
	}

	/**
	 * Finds the 0-based index of a non-static field within a class.
	 * This MUST match the order used in TypeConverter.getStructTypeForClass.
	 */
	private int findFieldIndex(ClassSymbol cs, String fieldName)
	{
		if (cs == null)
		{
			return -1;
		}

		int index = 0;
		// We must iterate over symbols in a guaranteed order.
		// Assuming 'getSymbols()' returns a Map that preserves insertion order
		// (like SymbolTableBuilder's LinkedHashMap).
		for (Symbol member : cs.getSymbols().values())
		{
			if (member instanceof VariableSymbol vs && !vs.isStatic())
			{
				if (vs.getName().equals(fieldName))
				{
					return index;
				}
				index++;
			}
		}

		// Field not found (could be in a superclass, but not implemented yet)
		return -1;
	}
}