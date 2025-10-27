// File: src/main/java/org/lokray/codegen/IRVisitor.java
package org.lokray.codegen;

import org.antlr.v4.runtime.tree.ParseTree;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;
import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.semantic.SemanticAnalyzer;
import org.lokray.semantic.info.SimplifiedForInfo;
import org.lokray.semantic.info.TraditionalForInfo;
import org.lokray.semantic.symbol.MethodSymbol;
import org.lokray.semantic.symbol.Symbol;
import org.lokray.semantic.symbol.VariableSymbol;
import org.lokray.semantic.type.ArrayType;
import org.lokray.semantic.type.PrimitiveType;
import org.lokray.semantic.type.Type;
import org.lokray.util.Debug;

import java.math.BigInteger;
import java.util.*;

import static org.bytedeco.llvm.global.LLVM.*;

public class IRVisitor extends NebulaParserBaseVisitor<LLVMValueRef>
{

	private final SemanticAnalyzer semanticAnalyzer;
	private final LLVMContext llvmContext;
	private final Stack<Map<String, LLVMValueRef>> scopedValues = new Stack<>();
	private final Map<String, LLVMValueRef> namedValues = new HashMap<>();
	private LLVMValueRef currentFunction;
	private LLVMBuilderRef builder;
	private final LLVMModuleRef module;
	private final LLVMContextRef moduleContext;

	// --- Stack for tracking loop exit blocks (for break/continue) ---
	private final Stack<LLVMBasicBlockRef> loopExitBlocks = new Stack<>();
	private final Stack<LLVMBasicBlockRef> loopUpdateBlocks = new Stack<>(); // For continue

	public IRVisitor(SemanticAnalyzer semanticAnalyzer, LLVMContext llvmContext)
	{
		this.semanticAnalyzer = semanticAnalyzer;
		this.llvmContext = llvmContext;
		builder = llvmContext.getBuilder();
		module = llvmContext.getModule();
		moduleContext = LLVMGetModuleContext(module);// Get the map
		scopedValues.push(new HashMap<>());
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

		// 2. Create or get the function prototype
		LLVMValueRef function = LLVMGetNamedFunction(module, methodName);
		if (function == null)
		{
			List<Type> paramTypes = methodSymbol.getParameterTypes();
			LLVMTypeRef[] llvmParamTypes = new LLVMTypeRef[paramTypes.size()];
			for (int i = 0; i < paramTypes.size(); i++)
			{
				llvmParamTypes[i] = TypeConverter.toLLVMType(paramTypes.get(i), moduleContext);
			}

			LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType(), moduleContext);
			LLVMTypeRef functionType = LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), paramTypes.size(), 0);
			function = LLVMAddFunction(module, methodName, functionType);
		}

		// 3. Mark main method (no special behavior, just identification)
		if (methodSymbol.isMainMethod())
		{
			Debug.logDebug("IR: Marking as main method: " + methodName);
			// Optional: could store function reference externally if needed
		}

		// 4. Create entry block and position builder
		LLVMBasicBlockRef entryBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "entry");
		LLVMPositionBuilderAtEnd(builder, entryBlock);

		// 5. Set function context
		LLVMValueRef oldFunction = currentFunction;
		currentFunction = function;
		Map<String, LLVMValueRef> outerValues = new HashMap<>(namedValues);
		namedValues.clear();

		// 6. Process parameters
		PointerPointer<LLVMValueRef> params = new PointerPointer<>(methodSymbol.getParameterTypes().size());
		LLVMGetParams(function, params);

		for (int i = 0; i < methodSymbol.getParameterTypes().size(); i++)
		{
			Type paramNebulaType = methodSymbol.getParameterTypes().get(i);
			String paramName = methodSymbol.getParameters().get(i).getName();
			LLVMTypeRef paramLLVMType = TypeConverter.toLLVMType(paramNebulaType, moduleContext);
			LLVMValueRef incomingValue = params.get(LLVMValueRef.class, i);

			LLVMValueRef alloca = createEntryBlockAlloca(function, paramLLVMType, paramName);
			LLVMBuildStore(builder, incomingValue, alloca);
			namedValues.put(paramName, alloca);
			addVariableToScope(paramName, alloca);
		}

		// 7. Visit the method body
		visit(ctx.block());

		// 8. Add implicit return if needed
		LLVMBasicBlockRef lastBlock = LLVMGetLastBasicBlock(function);
		if (LLVMGetBasicBlockTerminator(lastBlock) == null)
		{
			Type returnType = methodSymbol.getType();
			if (returnType == PrimitiveType.VOID)
			{
				LLVMBuildRetVoid(builder);
			}
			else
			{
				// Optionally add default return for non-void types if language allows fall-through
				// e.g., LLVMBuildRet(builder, LLVMConstNull(TypeConverter.toLLVMType(returnType, moduleContext)));
			}
		}

		// 9. Restore previous context
		namedValues.putAll(outerValues);
		currentFunction = oldFunction;

		return function;
	}

	@Override
	public LLVMValueRef visitStatementExpression(NebulaParser.StatementExpressionContext ctx)
	{
		// The expression part of the statement, e.g., 'Console.println(...)'
		ParseTree methodCallExpr = ctx.getChild(0);

		Debug.logDebug("IR: Processing statement expression: " + ctx.getText());

		// --- START MODIFICATION ---
		// Directly look up the symbol associated with the *entire statement expression context*.
		// This is where TypeCheckVisitor stores the result of overload resolution.
		Debug.logDebug("IR: Attempting lookup using StatementExpression context: (" + ctx.getText() + ").");
		Optional<Symbol> symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);

		if (symbolOpt.isPresent())
		{
			Debug.logDebug("IR: Lookup SUCCEEDED. Symbol found: " + symbolOpt.get());
		}
		else
		{
			Debug.logDebug("IR: Lookup FAILED. No symbol found for StatementExpression context.");
		}
		// --- END MODIFICATION ---


		if (symbolOpt.isPresent() && symbolOpt.get() instanceof MethodSymbol methodSymbol)
		{
			// --- Symbol Found: Proceed with Call Generation ---
			Debug.logDebug("IR: RESOLUTION SUCCESSFUL. Proceeding with code generation for method: " + methodSymbol); // Log the full symbol

			String mangledName = methodSymbol.getMangledName();
			Debug.logDebug("IR: Generated mangled name: " + mangledName);

			// 1. Function Prototype/Definition Lookup
			LLVMValueRef function = LLVMGetNamedFunction(module, mangledName);
			if (function == null)
			{
				Debug.logDebug("IR: Function prototype not found. Creating LLVM declaration for: " + mangledName);
				// If not declared, create the function prototype.
				List<Type> paramTypes = methodSymbol.getParameterTypes();
				LLVMTypeRef[] llvmParamTypes = new LLVMTypeRef[paramTypes.size()];
				for (int i = 0; i < paramTypes.size(); i++)
				{
					llvmParamTypes[i] = TypeConverter.toLLVMType(paramTypes.get(i), moduleContext);
				}
				LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType(), moduleContext); // [cite: 2309, 3787]
				LLVMTypeRef functionType = LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), paramTypes.size(), 0);
				function = LLVMAddFunction(module, mangledName, functionType);
			}

			// --- 2. Prepare arguments for the call ---
			List<LLVMValueRef> args = new ArrayList<>();
			if (ctx.argumentList() != null)
			{
				Debug.logDebug("IR: Processing " + ctx.argumentList().expression().size() + " arguments...");
				for (NebulaParser.ExpressionContext exprCtx : ctx.argumentList().expression())
				{
					// This recursively calls visitLiteral and correctly generates the string pointer
					args.add(visit(exprCtx));
				}
				Debug.logDebug("IR: Finished processing arguments.");
			}

			PointerPointer<LLVMValueRef> argsPtr = new PointerPointer<>(args.size());
			for (int i = 0; i < args.size(); i++)
			{
				argsPtr.put(i, args.get(i));
			}

			// --- 3. Build the call instruction ---
			Debug.logDebug("IR: Building LLVM call instruction for: " + mangledName);
			LLVMBuildCall2(builder, safeGetFunctionType(function, methodSymbol), function, argsPtr, args.size(), "");
			Debug.logDebug("IR: Call generation complete.");

			return null; // A statement expression doesn't return a value.
		}
		else
		{
			Debug.logDebug("IR: WARNING: Not a method call, or resolved symbol not found/not a MethodSymbol for context: " + ctx.getText() + ". Falling back to visitChildren.");
			// Fallback for other statement expressions (assignments, increments, etc.)
			return visitChildren(ctx);
		}
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
		LLVMTypeRef resultLLVMType = org.lokray.codegen.TypeConverter.toLLVMType(resultTypeOpt.get(), moduleContext);

		// Cast left operand to the final result type
		LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "pow_lhs_cast");

		// NOTE: LLVM does not have a built-in power instruction.
		// We must call the C standard library function 'pow' (usually for double).

		if (org.bytedeco.llvm.global.LLVM.LLVMGetTypeKind(resultLLVMType) != org.bytedeco.llvm.global.LLVM.LLVMDoubleTypeKind)
		{
			org.lokray.util.Debug.logWarning("IR: Power operator (**) is only implemented for double, falling back to basic implementation for: " + resultTypeOpt.get().getName());
			// For simplicity, we only implement the left side for now.
			return leftCasted;
		}

		// Ensure 'pow' function is declared
		LLVMValueRef powFunc = org.bytedeco.llvm.global.LLVM.LLVMGetNamedFunction(module, "pow");
		if (powFunc == null)
		{
			LLVMTypeRef doubleType = org.bytedeco.llvm.global.LLVM.LLVMDoubleTypeInContext(moduleContext);
			LLVMTypeRef[] paramTypes = {doubleType, doubleType};
			LLVMTypeRef funcType = org.bytedeco.llvm.global.LLVM.LLVMFunctionType(doubleType, new org.bytedeco.javacpp.PointerPointer<>(paramTypes), 2, 0);
			powFunc = org.bytedeco.llvm.global.LLVM.LLVMAddFunction(module, "pow", funcType);
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
			LLVMValueRef powResult = org.bytedeco.llvm.global.LLVM.LLVMBuildCall2(builder, org.bytedeco.llvm.global.LLVM.LLVMGetCalledFunctionType(powFunc), powFunc, new org.bytedeco.javacpp.PointerPointer<>(args), 2, "pow_tmp");

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
			// --- SIMPLIFIED FOR LOOP ---
			Debug.logDebug("Codegen: Simplified for loop (using Semantic Info): " + ctx.getText());
			VariableSymbol loopVarSymbol = info.loopVariable();
			String varName = loopVarSymbol.getName();
			// *** FIX: Use loopVarSymbol's actual resolved type ***
			Type loopVarNebulaType = loopVarSymbol.getType();
			LLVMTypeRef varType = TypeConverter.toLLVMType(loopVarNebulaType, moduleContext);
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
			LLVMBuildStore(builder, startVal, varAlloca); // Initialize [cite: 3769]

			Map<String, LLVMValueRef> outerValues = new HashMap<>(namedValues); // Backup scope [cite: 3769]
			namedValues.put(varName, varAlloca);

			// --- Create Blocks --- [cite: 3769]
			LLVMBasicBlockRef loopHeaderBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "loop.header");
			LLVMBasicBlockRef loopBodyBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "loop.body");
			LLVMBasicBlockRef loopExitBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "loop.exit");

			LLVMBuildBr(builder, loopHeaderBlock); // Branch to Header [cite: 3769]

			// --- Populate Header --- [cite: 3770]
			LLVMPositionBuilderAtEnd(builder, loopHeaderBlock);
			LLVMValueRef currentVal = LLVMBuildLoad2(builder, varType, varAlloca, varName + ".load");
			LLVMValueRef limitVal = visit(info.limitExpression());
			if (limitVal == null)
			{ /* ... error handling ... */
				namedValues.clear();
				namedValues.putAll(outerValues);
				return null;
			}

			// --- Comparison --- [cite: 3770]
			int llvmPredicate;
			// *** FIX: Determine signedness from loop variable type ***
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

			// --- Populate Body ---
			LLVMPositionBuilderAtEnd(builder, loopBodyBlock);
			pushScope();
			visit(ctx.block());
			popScope();

			// --- Increment or Decrement ---
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

			// --- Exit Block --- [cite: 3773]
			LLVMPositionBuilderAtEnd(builder, loopExitBlock);
			namedValues.clear(); // Restore outer scope [cite: 3774]
			namedValues.putAll(outerValues);

			Debug.logDebug("Codegen: Finished simplified for loop (using Semantic Info): " + ctx.getText());
			return null;

		}
		else if (loopInfo instanceof TraditionalForInfo info)
		{
			// --- TRADITIONAL FOR LOOP ---
			// ... (existing traditional for loop codegen remains the same) ... [cite: 3774-3783]
			Debug.logDebug("Codegen: Traditional for loop (using Semantic Info): " + ctx.getText());
			LLVMValueRef function = currentFunction;
			Map<String, LLVMValueRef> outerValues = new HashMap<>(namedValues);

			// --- 1. Initializer --- [cite: 3775]
			if (info.initializer() != null)
			{
				if (info.initializer() instanceof NebulaParser.VariableDeclarationContext varDeclCtx)
				{
					visit(varDeclCtx);
				}
				else if (info.initializer() instanceof NebulaParser.ExpressionContext exprCtx)
				{
					visit(exprCtx); // Execute initializer expression [cite: 3776]
				}
			}

			// --- 2. Create Blocks --- [cite: 3776]
			LLVMBasicBlockRef loopHeaderBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.cond");
			LLVMBasicBlockRef loopBodyBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.body");
			LLVMBasicBlockRef loopUpdateBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.update");
			LLVMBasicBlockRef loopExitBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.exit");

			LLVMBuildBr(builder, loopHeaderBlock); // Branch to Header [cite: 3776]

			// --- 4. Populate Header Block --- [cite: 3777]
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
					// It's already i1, use directly [cite: 3778]
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
				conditionValue = LLVMConstInt(LLVMInt1TypeInContext(moduleContext), 1, 0);
			}
			LLVMBuildCondBr(builder, conditionValue, loopBodyBlock, loopExitBlock); // Branch [cite: 3781]

			// --- 5. Populate Body Block --- [cite: 3781]
			LLVMPositionBuilderAtEnd(builder, loopBodyBlock);
			visit(ctx.block()); // Visit loop body [cite: 3782]
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{ // Branch to update if not terminated [cite: 3782]
				LLVMBuildBr(builder, loopUpdateBlock);
			}

			// --- 6. Populate Update Block --- [cite: 3782]
			LLVMPositionBuilderAtEnd(builder, loopUpdateBlock);
			if (info.update() != null)
			{
				visit(info.update()); // Generate update code from info [cite: 3782]
			}
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{ // Branch to header if not terminated [cite: 3782]
				LLVMBuildBr(builder, loopHeaderBlock);
			}

			// --- 7. Position Builder at Exit Block --- [cite: 3783]
			LLVMPositionBuilderAtEnd(builder, loopExitBlock);
			namedValues.clear(); // Restore outer scope [cite: 3783]
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
		System.out.println("=================================> Visiting foreach statement: " + ctx.getText());

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
		LLVMTypeRef loopVarLLVMType = TypeConverter.toLLVMType(loopVarNebulaType, moduleContext);

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
		LLVMTypeRef elementLLVMType = TypeConverter.toLLVMType(elementNebulaType, moduleContext);

		// 3. Load descriptor and extract size/data pointer
		LLVMTypeRef arrayDescType = TypeConverter.getArrayDescStructTypeForContext(moduleContext);
		LLVMValueRef descStruct = LLVMBuildLoad2(builder, arrayDescType, collectionDescPtr, "foreach.desc.load");
		LLVMValueRef dataPtrI8 = LLVMBuildExtractValue(builder, descStruct, 0, "foreach.data.ptr.i8");
		LLVMValueRef arraySize = LLVMBuildExtractValue(builder, descStruct, 1, "foreach.size");

		// 4. Cast i8* data pointer to correct element pointer type (e.g., i32* or %nebula_string*)
		LLVMTypeRef elementPtrType = LLVMPointerType(elementLLVMType, 0);
		LLVMValueRef dataPtrTyped = LLVMBuildBitCast(builder, dataPtrI8, elementPtrType, "foreach.data.ptr.typed");

		// 5. Create loop counter (alloca for 'i')
		LLVMValueRef function = currentFunction;
		LLVMTypeRef i32Type = LLVMInt32TypeInContext(moduleContext);
		LLVMValueRef counterAlloca = createEntryBlockAlloca(function, i32Type, "foreach.i");
		LLVMBuildStore(builder, LLVMConstInt(i32Type, 0, 0), counterAlloca); // i = 0

		// 6. Create loop blocks
		LLVMBasicBlockRef loopHeaderBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "foreach.header");
		LLVMBasicBlockRef loopBodyBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "foreach.body");
		LLVMBasicBlockRef loopExitBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "foreach.exit");

		LLVMBuildBr(builder, loopHeaderBlock); // Jump to header

		// 7. Populate Header (Condition check: i < size)
		LLVMPositionBuilderAtEnd(builder, loopHeaderBlock);
		LLVMValueRef currentCounter = LLVMBuildLoad2(builder, i32Type, counterAlloca, "foreach.i.load");
		LLVMValueRef condition = LLVMBuildICmp(builder, LLVMIntULT, currentCounter, arraySize, "foreach.cond"); // Use unsigned compare
		LLVMBuildCondBr(builder, condition, loopBodyBlock, loopExitBlock);

		// 8. Populate Body
		LLVMPositionBuilderAtEnd(builder, loopBodyBlock);
		pushScope(); // <--- CRITICAL: New scope for the loop variable

		// 8a. Get element address: elementPtr = &dataPtrTyped[i]
		// The constructor new PointerPointer<>(LLVMValueRef) is wrong.
		// You must use the varargs constructor that takes an array.
		LLVMValueRef[] indices = { currentCounter };
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

		popScope(); // <--- CRITICAL: End loop variable's scope

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
		LLVMValueRef finalCondition = TypeConverter.toBoolean(conditionValue, ctx.expression(), moduleContext, builder);

		// 2. Create the blocks for this IF
		LLVMBasicBlockRef thenBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "if.then");

		// Retrieve the optional else statement (if it exists)
		NebulaParser.StatementContext elseStmt = ctx.statement().size() > 1 ? ctx.statement(1) : null;

		if (elseStmt != null && elseStmt.ifStatement() != null)
		{
			// This is an 'else if'. The false branch jumps to the next condition check.
			LLVMBasicBlockRef nextCheckBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "else.if.cond");
			LLVMBuildCondBr(builder, finalCondition, thenBlock, nextCheckBlock);

			// Position builder for the recursive call
			LLVMPositionBuilderAtEnd(builder, nextCheckBlock);
			// Recursively call with the same finalMergeBlock
			visitIfStatementRecursive(elseStmt.ifStatement(), finalMergeBlock);
		}
		else if (elseStmt != null)
		{
			// This is a final 'else' block. The false branch jumps directly to the 'else' body.
			LLVMBasicBlockRef elseBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "if.else");
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
		LLVMBasicBlockRef mergeBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "if.merge");

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
				LLVMTypeRef targetType = LLVMIntTypeInContext(moduleContext, targetBits);

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
					left = LLVMBuildFPExt(builder, left, LLVMDoubleTypeInContext(moduleContext), "fpext_lhs");
				}
				if (LLVMGetTypeKind(rightType) == LLVMFloatTypeKind)
				{
					right = LLVMBuildFPExt(builder, right, LLVMDoubleTypeInContext(moduleContext), "fpext_rhs");
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
				LLVMTypeRef targetType = LLVMIntTypeInContext(moduleContext, targetBits);

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
					left = LLVMBuildFPExt(builder, left, LLVMDoubleTypeInContext(moduleContext), "fpext_lhs");
				}
				if (LLVMGetTypeKind(rightType) == LLVMFloatTypeKind)
				{
					right = LLVMBuildFPExt(builder, right, LLVMDoubleTypeInContext(moduleContext), "fpext_rhs");
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

		// --- NEW: Handle Array Declarations ---
		if (nebulaType instanceof ArrayType arrayNebulaType)
		{
			LLVMTypeRef elementLLVMType = TypeConverter.toLLVMType(arrayNebulaType.getElementType(), moduleContext);
			LLVMTypeRef arrayDescLLVMType = TypeConverter.getArrayDescStructTypeForContext(moduleContext);

			// We must loop through each declarator (e.g., int[] a, b, c)
			// The logic for size detection and allocation must be *inside* this loop.
			for (NebulaParser.VariableDeclaratorContext declarator : ctx.variableDeclarator())
			{
				String varName = declarator.ID().getText();
				int finalArraySize = -1;
				NebulaParser.ArrayInitializerContext initCtx = null;

				// --- 1. DETERMINE SIZE ---
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

				// --- 2. ALLOCATE (now that we have the size) ---
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

				// --- 3. INITIALIZE DESCRIPTOR ---
				LLVMValueRef zero = LLVMConstInt(LLVMInt32Type(), 0, 0);
				LLVMValueRef[] indices = {zero, zero}; // Index into [Size x Type] -> get pointer to first element
				LLVMValueRef dataPtr = LLVMBuildGEP2(builder, arrayDataLLVMType, dataAlloca, new PointerPointer<>(indices), 2, varName + ".ptr");

				// 3b. Cast dataPtr to i8* for storing in the descriptor
				LLVMValueRef dataPtrI8 = LLVMBuildBitCast(builder, dataPtr, LLVMPointerType(LLVMInt8TypeInContext(moduleContext), 0), varName + ".ptr.i8");

				// 3c. Store dataPtrI8 and size into the descriptor struct
				LLVMValueRef dataPtrField = LLVMBuildStructGEP2(builder, arrayDescLLVMType, descAlloca, 0, varName + ".data.ptr.addr");
				LLVMValueRef sizeField = LLVMBuildStructGEP2(builder, arrayDescLLVMType, descAlloca, 1, varName + ".size.addr");
				LLVMBuildStore(builder, dataPtrI8, dataPtrField);
				LLVMBuildStore(builder, LLVMConstInt(LLVMInt32Type(), finalArraySize, 0), sizeField);

				// --- 4. FILL ARRAY DATA (if initializer exists) ---
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

				// --- 5. ADD TO SCOPE ---
				// Add the *descriptor* alloca to the scope, not the data alloca
				addVariableToScope(varName, descAlloca);
				namedValues.put(varName, descAlloca); // Store descriptor pointer
			}
			return null; // Handled array declaration
		}
		// --- End Array Handling ---


		// --- Existing Primitive/Other Type Handling ---
		LLVMTypeRef varLLVMType = TypeConverter.toLLVMType(nebulaType, moduleContext);

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

				// 🔧 Automatically extend/truncate/convert if sizes/types differ
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
				// 💡 NEW BLOCK FOR FLOATING POINT CONVERSION
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
				// 💡 END NEW BLOCK

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
		// ----- Get Final Semantic Info -----
		Optional<Type> finalNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx);
		Optional<Object> valueInfoOpt = semanticAnalyzer.getResolvedInfo(ctx);

		// ----- STRING LITERALS (unchanged) -----
		if (ctx.STRING_LITERAL() != null)
		{
			String value = ctx.STRING_LITERAL().getText();
			value = value.substring(1, value.length() - 1);

			LLVMValueRef stringData = LLVMConstStringInContext(moduleContext, value, value.length(), 1);
			LLVMValueRef globalData = LLVMAddGlobal(module, LLVMTypeOf(stringData), ".str.data");
			LLVMSetInitializer(globalData, stringData);
			LLVMSetGlobalConstant(globalData, 1);
			LLVMSetLinkage(globalData, LLVMPrivateLinkage);

			LLVMValueRef zero32 = LLVMConstInt(LLVMInt32Type(), 0, 0);
			LLVMValueRef[] indices = {zero32, zero32};
			LLVMValueRef dataPtr = LLVMConstGEP2(LLVMTypeOf(stringData), globalData, new PointerPointer<>(indices), 2);

			LLVMTypeRef stringType = TypeConverter.getStringStructTypeForContext(moduleContext);
			LLVMValueRef[] fields = new LLVMValueRef[]{dataPtr, LLVMConstInt(LLVMInt32Type(), value.length(), 0)
			};
			LLVMValueRef structConst = LLVMConstNamedStruct(stringType, new PointerPointer<>(fields), fields.length);

			LLVMValueRef globalString = LLVMAddGlobal(module, LLVMTypeOf(structConst), "str_literal_struct");
			LLVMSetInitializer(globalString, structConst);
			LLVMSetGlobalConstant(globalString, 1);
			LLVMSetLinkage(globalString, LLVMPrivateLinkage);
			return globalString;
		}

		// ----- INTERPOLATED STRING (Needs implementation if not done) -----
		if (ctx.interpolatedString() != null)
		{
			Debug.logWarning("IR generation for interpolated strings not fully implemented yet.");
			// Placeholder: return an empty string for now
			LLVMTypeRef stringStructType = TypeConverter.getStringStructTypeForContext(moduleContext);
			LLVMValueRef emptyStr = LLVMConstNull(stringStructType); // Or create a proper empty string global
			return LLVMBuildLoad2(builder, stringStructType, emptyStr, "interpolated_stub"); // Assuming global needs load
		}


		// ----- NULL -----
		if (ctx.NULL_T() != null)
		{
			// Need to know the expected pointer type if possible, otherwise use i8*
			// For now, let's assume i8* as a generic null pointer
			LLVMTypeRef i8Ptr = LLVMPointerType(LLVMInt8TypeInContext(moduleContext), 0);
			return LLVMConstNull(i8Ptr);
		}

		// ----- BOOLEAN -----
		if (ctx.BOOLEAN_LITERAL() != null)
		{
			boolean val = ctx.BOOLEAN_LITERAL().getText().equals("true");
			return LLVMConstInt(LLVMInt1TypeInContext(moduleContext), val ? 1 : 0, 0);
		}

		// ----- CHAR -----
		if (ctx.CHAR_LITERAL() != null)
		{
			String text = ctx.CHAR_LITERAL().getText();
			// Basic unescaping for simple cases - needs robust handling
			char val = text.length() > 2 ? text.charAt(1) : 0; // Simplified
			if (text.length() == 4 && text.startsWith("'\\"))
			{ // e.g., '\n'
				switch (text.charAt(2))
				{
					case 'n':
						val = '\n';
						break;
					case 't':
						val = '\t';
						break;
					case '\\':
						val = '\\';
						break;
					case '\'':
						val = '\'';
						break;
					// Add more escapes as needed
				}
			}
			return LLVMConstInt(LLVMInt8TypeInContext(moduleContext), val, 0); // Assuming char is i8
		}


		// ----- NUMERIC LITERALS (Using Semantic Info) -----
		if (finalNebulaTypeOpt.isPresent() && valueInfoOpt.isPresent())
		{
			Type finalNebulaType = finalNebulaTypeOpt.get();
			Object valueInfo = valueInfoOpt.get();
			LLVMTypeRef targetLLVMType = TypeConverter.toLLVMType(finalNebulaType, moduleContext);

			// --- Floating Point ---
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


			// --- Integer ---
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

		// ----- Fallback (Should ideally not be reached often if semantics are correct) -----
		Debug.logDebug("IRVisitor: Fallback literal handling for: " + ctx.getText());
		if (ctx.INTEGER_LITERAL() != null)
		{
			return LLVMConstInt(LLVMInt32Type(), Long.parseLong(ctx.INTEGER_LITERAL().getText()), 0);
		}
		// ... other fallbacks. Shoudn't need implementation ...

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
		List<Type> paramTypes = methodSymbol.getParameterTypes();
		LLVMTypeRef[] llvmParamTypes = new LLVMTypeRef[paramTypes.size()];
		for (int i = 0; i < paramTypes.size(); i++)
		{
			llvmParamTypes[i] = TypeConverter.toLLVMType(paramTypes.get(i), moduleContext);
		}
		LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType(), moduleContext);
		return LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), paramTypes.size(), 0);
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

	/**
	 * --- UPDATED METHOD ---
	 * Handles expressions that are method calls (e.g., getPi()) or variable
	 * lookups (e.g., fPi).
	 */
	@Override
	public LLVMValueRef visitPostfixExpression(NebulaParser.PostfixExpressionContext ctx)
	{
		// ... (existing logging) ...
		Debug.logDebug("IR (Postfix): Visiting: " + ctx.getText() +
				" (Hash: " + ctx.hashCode() +
				", Interval: " + ctx.getSourceInterval() + ")");


		Optional<Symbol> symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
		Optional<Type> resultTypeOpt = semanticAnalyzer.getResolvedType(ctx); // Get the final type

		// --- Handle Array Element Access arr[idx] ---
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

			// Check if the base 'arr' is indeed an array descriptor pointer (alloca result)
			LLVMTypeRef baseDescPtrType = LLVMTypeOf(baseDescPtr);
			LLVMTypeRef arrayDescType = TypeConverter.getArrayDescStructTypeForContext(moduleContext);
			if (LLVMGetTypeKind(baseDescPtrType) != LLVMPointerTypeKind || !LLVMGetElementType(baseDescPtrType).equals(arrayDescType))
			{
				Debug.logError("IR Error: Base of array access is not an array descriptor: " + ctx.primary().getText());
				return null;
			}


			// 2. Visit the index expression
			LLVMValueRef indexVal = visit(ctx.expression(0)); // Assuming single index for now
			if (indexVal == null)
			{
				Debug.logError("IR Error: Failed to generate IR for array index: " + ctx.expression(0).getText());
				return null;
			}
			// Ensure index is integer type (semantic analysis should guarantee this, but cast if needed)
			indexVal = buildCast(builder, indexVal, LLVMInt32TypeInContext(moduleContext), "idx_cast"); // Assume i32 index


			// 3. Load the descriptor struct
			LLVMValueRef descStruct = LLVMBuildLoad2(builder, arrayDescType, baseDescPtr, "arr.desc.load");

			// 4. Extract the data pointer (i8*) from the struct (index 0)
			LLVMValueRef dataPtrI8 = LLVMBuildExtractValue(builder, descStruct, 0, "arr.data.ptr.i8");

			// --- Bounds Checking ---
			LLVMValueRef sizeVal = LLVMBuildExtractValue(builder, descStruct, 1, "arr.size");
			LLVMValueRef isInBounds = LLVMBuildICmp(builder, LLVMIntULT, indexVal, sizeVal, "bounds.check"); // Unsigned comparison

			LLVMBasicBlockRef currentBlock = LLVMGetInsertBlock(builder);
			LLVMValueRef function = LLVMGetBasicBlockParent(currentBlock);
			LLVMBasicBlockRef thenBlock = LLVMAppendBasicBlock(function, "bounds.ok");
			LLVMBasicBlockRef elseBlock = LLVMAppendBasicBlock(function, "bounds.err");

			LLVMBuildCondBr(builder, isInBounds, thenBlock, elseBlock);

			LLVMPositionBuilderAtEnd(builder, elseBlock);
			// Call a runtime error function, print message, or trap
			// Example: call void @runtime_error("Index out of bounds")
			LLVMBuildUnreachable(builder); // Terminate error block

			LLVMPositionBuilderAtEnd(builder, thenBlock); // Continue GEP in the 'then' block
			// --- End Bounds Checking ---


			// 5. Cast the i8* data pointer back to the correct element pointer type
			Optional<Type> baseNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.primary());
			if (baseNebulaTypeOpt.isEmpty() || !(baseNebulaTypeOpt.get() instanceof ArrayType baseArrayType))
			{
				Debug.logError("IR Error: Cannot determine element type for array access: " + ctx.primary().getText());
				return null;
			}
			LLVMTypeRef elementLLVMType = TypeConverter.toLLVMType(baseArrayType.getElementType(), moduleContext);
			LLVMTypeRef elementPtrType = LLVMPointerType(elementLLVMType, 0);
			LLVMValueRef dataPtrTyped = LLVMBuildBitCast(builder, dataPtrI8, elementPtrType, "arr.data.ptr.typed");

			// 6. Build the GEP instruction to get the element address
			LLVMValueRef elementPtr = LLVMBuildGEP2(builder, elementLLVMType, dataPtrTyped, new PointerPointer<>(indexVal), 1, "arr.elem.ptr");

			// IMPORTANT: Return the POINTER to the element.
			// The parent expression (e.g., assignment, another operation) will handle loading/storing.
			Debug.logDebug("IR (Postfix): Array access returning element pointer.");
			return elementPtr;
		}
		// --- End Array Element Access ---

		// --- Existing Method Call Handling ---
		if (symbolOpt.isPresent() && symbolOpt.get() instanceof MethodSymbol methodSymbol)
		{
			Debug.logDebug("IR (Postfix): Resolved as method call: " + methodSymbol);

			String mangledName = methodSymbol.getMangledName();
			LLVMValueRef function = LLVMGetNamedFunction(module, mangledName);

			// 1. Create Function Prototype if it doesn't exist
			if (function == null)
			{
				// ... (existing prototype creation) ...
				Debug.logDebug("IR (Postfix): Function prototype not found. Creating LLVM declaration for: " + mangledName);
				List<Type> paramTypes = methodSymbol.getParameterTypes();
				LLVMTypeRef[] llvmParamTypes = new LLVMTypeRef[paramTypes.size()];
				for (int i = 0; i < paramTypes.size(); i++)
				{
					llvmParamTypes[i] = TypeConverter.toLLVMType(paramTypes.get(i), moduleContext);
				}
				LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType(), moduleContext);
				LLVMTypeRef functionType = LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), paramTypes.size(), 0);
				function = LLVMAddFunction(module, mangledName, functionType);
			}

			// 2. Prepare arguments
			List<LLVMValueRef> args = new ArrayList<>();
			List<Type> expectedParamTypes = methodSymbol.getParameterTypes();
			NebulaParser.ArgumentListContext argListCtx = null;
			for (int i = 0; i < ctx.getChildCount(); i++)
			{
				if (ctx.getChild(i) instanceof NebulaParser.ArgumentListContext)
				{
					argListCtx = (NebulaParser.ArgumentListContext) ctx.getChild(i);
					break;
				}
			}

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

					// Argument Type Conversion
					if (i < expectedParamTypes.size() && argValue != null)
					{
						Type targetType = expectedParamTypes.get(i);
						argValue = buildCast(builder, argValue, TypeConverter.toLLVMType(targetType, moduleContext), "arg_cast" + i);
					}
					args.add(argValue);
				}
			}

			PointerPointer<LLVMValueRef> argsPtr = new PointerPointer<>(args.size());
			for (int i = 0; i < args.size(); i++)
			{
				argsPtr.put(i, args.get(i));
			}

			// 3. Build the call instruction
			Debug.logDebug("IR (Postfix): Building LLVM call instruction for: " + mangledName);
			String callName = ""; // Default to void return
			if (methodSymbol.getType() != PrimitiveType.VOID)
			{
				callName = methodSymbol.getName() + ".call"; // Name result if non-void
			}
			return LLVMBuildCall2(builder, safeGetFunctionType(function, methodSymbol), function, argsPtr, args.size(), callName);
		}
		// --- End Method Call Handling ---

		// --- Handle Postfix Inc/Dec ---
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
				resultVal = loadedVal; // Return the original value
			}
			else
			{ // Post-decrement x--
				newVal = LLVMBuildSub(builder, loadedVal, one, "postdec");
				resultVal = loadedVal; // Return the original value
			}

			LLVMBuildStore(builder, newVal, baseVal); // Store the modified value back
			return resultVal; // Return the value *before* modification
		}
		// --- End Postfix Inc/Dec ---


		// --- Fallback: Variable Access ---
		Debug.logDebug("IR (Postfix): Not a method call or array access. Visiting Primary child: " + ctx.primary().getText() +
				" (Hash: " + ctx.primary().hashCode() +
				", Interval: " + ctx.primary().getSourceInterval() + ")");
		return visit(ctx.primary()); // Load variable value if primary is ID
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

		LLVMTypeRef targetLLVMType = TypeConverter.toLLVMType(targetType, moduleContext);
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

			// ----- int -> int -----
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

			// ----- float -> float -----
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

			// ----- int -> float -----
			if (targetIsFloat && originalBits > 0)
			{
				return originalIsUnsigned
						? LLVMBuildUIToFP(builder, originalValue, targetLLVMType, "uitofp")
						: LLVMBuildSIToFP(builder, originalValue, targetLLVMType, "sitofp");
			}

			// ----- float -> int / uint -----
			if (targetBits > 0 && originalIsFloat)
			{
				// 1. convert to a wide signed int
				LLVMTypeRef wideInt = LLVMInt64TypeInContext(LLVMGetGlobalContext());
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
		// --- START NEW LOGGING ---
		Debug.logDebug("IR (Primary): Visiting: " + ctx.getText() +
				" (Hash: " + ctx.hashCode() +
				", Interval: " + ctx.getSourceInterval() + ")");
		// --- END NEW LOGGING ---

		// 1) Literal: delegate [cite: 1475]
		if (ctx.literal() != null)
		{
			return visit(ctx.literal()); // [cite: 1475]
		}

		// 2) Parenthesized expression: ( expr ) [cite: 1476]
		if (ctx.expression() != null)
		{
			return visit(ctx.expression()); // [cite: 1476]
		}

		// 3) Identifier usage [cite: 1477]
		if (ctx.ID() != null)
		{
			Debug.logDebug("Visiting primary expression for ID:" + ctx.ID().getText()); // [cite: 1477]
			String name = ctx.ID().getText();
			// --- START NEW LOGGING ---
			Debug.logDebug("IR (Primary): Looking up symbol for ID '" + name + "' using context with Hash: " + ctx.hashCode() + ", Interval: " + ctx.getSourceInterval());
			// --- END NEW LOGGING ---
			Optional<Symbol> symOpt = semanticAnalyzer.getResolvedSymbol(ctx); // [cite: 1477]

			if (symOpt.isPresent())
			{
				Symbol sym = symOpt.get();

				if (sym instanceof VariableSymbol varSym)
				{
					Debug.logDebug("Resolved:" + symOpt.get().getType().getName() + " " + symOpt.get().getName()); // [cite: 1478]

					// --- START FIX --- [cite: 1478]
					// Find the alloca for this variable using the scope-aware lookup [cite: 1479]
					LLVMValueRef alloca = lookupVariable(varSym.getName()); // Use scope-aware lookup [cite: 1479]
					// --- END FIX --- [cite: 1479]

					if (alloca == null) // [cite: 1480]
					{
						// --- MODIFIED ERROR --- [cite: 1480]
						// The old parameter-finding logic was unreliable. [cite: 1481]
						// If lookupVariable fails, the alloca is genuinely missing. [cite: 1481]
						Debug.logDebug("IR: variable '" + varSym.getName() + "' used but no alloca found in any scope. Are you missing an allocation?"); // [cite: 1481]
						return null; // [cite: 1481]
						// --- END MODIFIED ERROR --- [cite: 1481]
					}

					// If the variable is an array, return the pointer to its descriptor (the alloca) directly.
					// Do NOT load it. Callers (like foreach or array_access) expect the pointer.
					if (varSym.getType() instanceof ArrayType)
					{
						Debug.logDebug("IR (Primary): Resolved '" + name + "' as ArrayType. Returning alloca (pointer) directly.");
						return alloca;
					}

					// Load the variable value and return it [cite: 1482]
					LLVMTypeRef varType = TypeConverter.toLLVMType(varSym.getType(), moduleContext); // [cite: 1482]
					LLVMValueRef loaded = LLVMBuildLoad2(builder, varType, alloca, name + ".load"); // [cite: 1482]
					return loaded;
				}
				else if (sym instanceof MethodSymbol) // [cite: 1483]
				{
					// This is a method group (e.g., "getPi"). [cite: 1484]
					// This is correct. The PostfixExpression visitor will handle the call. [cite: 1484]
					Debug.logDebug("IR: primary ID '" + name + "' resolved to method group: " + sym); // [cite: 1484]
					return null;
				}
				else // [cite: 1485]
				{
					// Not a variable symbol (could be a type name, etc.) [cite: 1485]
					Debug.logDebug("IR: primary ID '" + name + "' resolved to non-variable symbol: " + sym); // [cite: 1485]
					return null;
				}
			}
			else // [cite: 1486]
			{
				/* ... existing fallback comment ... */ // [cite: 1487-1490]
				// --- START NEW LOGGING ---
				Debug.logError("IR (Primary): FAILED TO RESOLVE SYMBOL for ID '" + name + "' using context Hash: " + ctx.hashCode() + ", Interval: " + ctx.getSourceInterval());
				// --- END NEW LOGGING ---
				return null; // [cite: 1490]
			}
		}

		// 4) other primary forms (this, null, new, etc.) - simple fallback for now [cite: 1491]
		return visitChildren(ctx); // [cite: 1491]
	}

	@Override
	public LLVMValueRef visitMultiplicativeExpression(NebulaParser.MultiplicativeExpressionContext ctx)
	{
		// --- START NEW LOGGING ---
		Debug.logDebug("IR (Multiplicative): Visiting: " + ctx.getText() +
				" (Hash: " + ctx.hashCode() +
				", Interval: " + ctx.getSourceInterval() + ")");
		// --- END NEW LOGGING ---

		if (ctx.powerExpression().size() == 1)
		{
			// --- START NEW LOGGING ---
			Debug.logDebug("IR (Multiplicative): Visiting single PowerExpression child: " + ctx.powerExpression(0).getText() +
					" (Hash: " + ctx.powerExpression(0).hashCode() +
					", Interval: " + ctx.powerExpression(0).getSourceInterval() + ")");
			// --- END NEW LOGGING ---
			return visit(ctx.powerExpression(0));
		}

		// --- START NEW LOGGING ---
		Debug.logDebug("IR (Multiplicative): Visiting left PowerExpression child: " + ctx.powerExpression(0).getText() +
				" (Hash: " + ctx.powerExpression(0).hashCode() +
				", Interval: " + ctx.powerExpression(0).getSourceInterval() + ")");
		// --- END NEW LOGGING ---
		LLVMValueRef leftVal = visit(ctx.powerExpression(0));

		for (int i = 1; i < ctx.powerExpression().size(); i++)
		{
			// --- START NEW LOGGING ---
			Debug.logDebug("IR (Multiplicative): Visiting right PowerExpression child #" + i + ": " + ctx.powerExpression(i).getText() +
					" (Hash: " + ctx.powerExpression(i).hashCode() +
					", Interval: " + ctx.powerExpression(i).getSourceInterval() + ")");
			// --- END NEW LOGGING ---
			LLVMValueRef rightVal = visit(ctx.powerExpression(i));
			String op = ctx.getChild(2 * i - 1).getText(); // Get the operator (e.g., "*") [cite: 1493]

			if (leftVal == null || rightVal == null)
			{
				return null;
			}

			// 1. Get the final result type (determined by the type checker) [cite: 1494]
			Optional<org.lokray.semantic.type.Type> resultTypeOpt = semanticAnalyzer.getResolvedType(ctx);
			if (resultTypeOpt.isEmpty())
			{
				Debug.logError("IR: Multiplicative expression result type not found."); // [cite: 1495]
				return null; // [cite: 1495]
			}
			LLVMTypeRef resultLLVMType = TypeConverter.toLLVMType(resultTypeOpt.get(), moduleContext);

			// 2. Cast operands to the result type [cite: 1495]
			LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "mult_lhs");
			LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "mult_rhs");

			LLVMTypeRef finalType = LLVMTypeOf(leftCasted); // Now should be the common widest type [cite: 1496]

			// 3. Select the operation based on the type [cite: 1496]
			if (LLVMGetTypeKind(finalType) == LLVMDoubleTypeKind || LLVMGetTypeKind(finalType) == LLVMFloatTypeKind)
			{
				// Floating-point arithmetic [cite: 1497]
				switch (op)
				{
					case "*":
						leftVal = LLVMBuildFMul(builder, leftCasted, rightCasted, "fmul_tmp"); // [cite: 1498]
						break;
					case "/":
						leftVal = LLVMBuildFDiv(builder, leftCasted, rightCasted, "fdiv_tmp");
						break;
					case "%":
						leftVal = LLVMBuildFRem(builder, leftCasted, rightCasted, "frem_tmp"); // [cite: 1499]
						break;
					default:
						Debug.logError("IR: Unknown FP multiplicative operator: " + op);
						return null;
				}
			}
			else if (LLVMGetTypeKind(finalType) == LLVMIntegerTypeKind) // [cite: 1500]
			{
				// Integer arithmetic (assuming signed for Div and Rem) [cite: 1501]
				switch (op)
				{
					case "*":
						leftVal = LLVMBuildMul(builder, leftCasted, rightCasted, "mul_tmp"); // [cite: 1502]
						break;
					case "/":
						leftVal = LLVMBuildSDiv(builder, leftCasted, rightCasted, "sdiv_tmp"); // Signed division // [cite: 1503]
						break; // [cite: 1503]
					case "%":
						leftVal = LLVMBuildSRem(builder, leftCasted, rightCasted, "srem_tmp"); // Signed remainder [cite: 1503]
						break;
					default:
						Debug.logError("IR: Unknown Int multiplicative operator: " + op); // [cite: 1504]
						return null;
				}
			}
			else // [cite: 1505]
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

		LLVMTypeRef i1Type = LLVMInt1TypeInContext(moduleContext);
		LLVMBasicBlockRef currentBlock = LLVMGetInsertBlock(builder);
		LLVMValueRef entryFunction = LLVMGetBasicBlockParent(currentBlock);

		LLVMBasicBlockRef endBlock = LLVMAppendBasicBlockInContext(moduleContext, entryFunction, "lor.end");

		LLVMValueRef phi = LLVMBuildPhi(builder, i1Type, "lor.phi");

		// Visit the first operand
		LLVMValueRef leftVal = visit(ctx.logicalAndExpression(0));
		LLVMValueRef leftBool = TypeConverter.toBoolean(leftVal, null, moduleContext, builder);
		currentBlock = LLVMGetInsertBlock(builder);

		// FIX 1: Explicitly create single-element arrays for arguments
		LLVMValueRef[] incomingValue1 = {leftBool};
		LLVMBasicBlockRef[] incomingBlock1 = {currentBlock};
		LLVMAddIncoming(phi, new PointerPointer(incomingValue1), new PointerPointer(incomingBlock1), 1);

		for (int i = 1; i < ctx.logicalAndExpression().size(); i++)
		{
			LLVMBasicBlockRef nextOperandBlock = LLVMAppendBasicBlockInContext(moduleContext, entryFunction, "lor.next");

			LLVMBuildCondBr(builder, leftBool, endBlock, nextOperandBlock);

			LLVMPositionBuilderAtEnd(builder, nextOperandBlock);
			LLVMValueRef rightVal = visit(ctx.logicalAndExpression(i));

			LLVMValueRef rightBool = TypeConverter.toBoolean(rightVal, null, moduleContext, builder);
			currentBlock = LLVMGetInsertBlock(builder);

			// FIX 2: Explicitly create single-element arrays for arguments
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

		LLVMTypeRef i1Type = LLVMInt1TypeInContext(moduleContext);
		LLVMBasicBlockRef currentBlock = LLVMGetInsertBlock(builder);
		LLVMValueRef entryFunction = LLVMGetBasicBlockParent(currentBlock);

		LLVMBasicBlockRef endBlock = LLVMAppendBasicBlockInContext(moduleContext, entryFunction, "land.end");

		LLVMValueRef phi = LLVMBuildPhi(builder, i1Type, "land.phi");

		// Visit the first operand
		LLVMValueRef leftVal = visit(ctx.bitwiseOrExpression(0));
		LLVMValueRef leftBool = TypeConverter.toBoolean(leftVal, null, moduleContext, builder);
		currentBlock = LLVMGetInsertBlock(builder);

		// FIX 3: Explicitly create single-element arrays for arguments
		LLVMValueRef[] incomingValue3 = {leftBool};
		LLVMBasicBlockRef[] incomingBlock3 = {currentBlock};
		LLVMAddIncoming(phi, new PointerPointer(incomingValue3), new PointerPointer(incomingBlock3), 1);

		for (int i = 1; i < ctx.bitwiseOrExpression().size(); i++)
		{
			LLVMBasicBlockRef nextOperandBlock = LLVMAppendBasicBlockInContext(moduleContext, entryFunction, "land.next");

			LLVMBuildCondBr(builder, leftBool, nextOperandBlock, endBlock);

			LLVMPositionBuilderAtEnd(builder, nextOperandBlock);
			LLVMValueRef rightVal = visit(ctx.bitwiseOrExpression(i));

			LLVMValueRef rightBool = TypeConverter.toBoolean(rightVal, null, moduleContext, builder);
			currentBlock = LLVMGetInsertBlock(builder);

			// FIX 4: Explicitly create single-element arrays for arguments
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
		LLVMTypeRef resultLLVMType = org.lokray.codegen.TypeConverter.toLLVMType(resultTypeOpt.get(), moduleContext);

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

			leftCasted = org.bytedeco.llvm.global.LLVM.LLVMBuildOr(builder, leftCasted, rightCasted, "or_tmp");
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
		LLVMTypeRef resultLLVMType = org.lokray.codegen.TypeConverter.toLLVMType(resultTypeOpt.get(), moduleContext);

		LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "xor_lhs_cast");

		for (int i = 1; i < ctx.bitwiseAndExpression().size(); i++)
		{
			LLVMValueRef rightVal = visit(ctx.bitwiseAndExpression(i));
			if (leftCasted == null || rightVal == null)
			{
				return null;
			}

			LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "xor_rhs_cast");

			leftCasted = org.bytedeco.llvm.global.LLVM.LLVMBuildXor(builder, leftCasted, rightCasted, "xor_tmp");
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
		LLVMTypeRef resultLLVMType = org.lokray.codegen.TypeConverter.toLLVMType(resultTypeOpt.get(), moduleContext);

		LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "and_lhs_cast");

		for (int i = 1; i < ctx.equalityExpression().size(); i++)
		{
			LLVMValueRef rightVal = visit(ctx.equalityExpression(i));
			if (leftCasted == null || rightVal == null)
			{
				return null;
			}

			LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "and_rhs_cast");

			leftCasted = org.bytedeco.llvm.global.LLVM.LLVMBuildAnd(builder, leftCasted, rightCasted, "and_tmp");
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
		LLVMTypeRef resultLLVMType = org.lokray.codegen.TypeConverter.toLLVMType(leftTypeOpt.get(), moduleContext);

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
					leftCasted = org.bytedeco.llvm.global.LLVM.LLVMBuildShl(builder, leftCasted, rightCasted, "shl_tmp");
					break;
				case ">>":
					if (isUnsigned)
					{
						leftCasted = org.bytedeco.llvm.global.LLVM.LLVMBuildLShr(builder, leftCasted, rightCasted, "lshr_tmp"); // Logical Shift Right
					}
					else
					{
						leftCasted = org.bytedeco.llvm.global.LLVM.LLVMBuildAShr(builder, leftCasted, rightCasted, "ashr_tmp"); // Arithmetic Shift Right
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
		// --- START NEW LOGGING ---
		Debug.logDebug("IR (Unary): Visiting: " + ctx.getText() +
				" (Hash: " + ctx.hashCode() +
				", Interval: " + ctx.getSourceInterval() + ")");
		// --- END NEW LOGGING ---

		if (ctx.op != null)
		{ // Check if there's a prefix operator
			// --- START NEW LOGGING ---
			Debug.logDebug("IR (Unary): Operator '" + ctx.op.getText() + "' present. Visiting nested UnaryExpression: " + ctx.unaryExpression().getText() +
					" (Hash: " + ctx.unaryExpression().hashCode() +
					", Interval: " + ctx.unaryExpression().getSourceInterval() + ")");
			// --- END NEW LOGGING ---
			LLVMValueRef operand = visit(ctx.unaryExpression());
			if (operand == null)
			{
				return null;
			}

			switch (ctx.op.getType())
			{
				case NebulaParser.ADD_OP:
					return operand; // Unary plus is usually a no-op
				case NebulaParser.SUB_OP:
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
				case NebulaParser.LOG_NOT_OP:
					// Convert operand to boolean (i1) if it isn't already
					LLVMValueRef boolOperand = TypeConverter.toBoolean(operand, null, moduleContext, builder); // Simplified context passing for now
					// XOR with true (1) to negate
					LLVMValueRef one = LLVMConstAllOnes(LLVMTypeOf(boolOperand)); // produces all-ones of same type (i1 -> true)
					return LLVMBuildXor(builder, boolOperand, one, "lognot_tmp");
				case NebulaParser.BIT_NOT_OP:
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
				case NebulaParser.INC_OP: // Pre-increment
				case NebulaParser.DEC_OP: // Pre-decrement
					// These require finding the variable's alloca, loading, incrementing/decrementing, storing back, and returning the *new* value.
					// This logic is more complex and depends on the operand being a valid l-value (variable/field).
					// For now, let's skip implementation and log a warning.
					Debug.logWarning("IR: Pre-increment/decrement not yet fully implemented in IRVisitor for: " + ctx.getText());
					return operand; // Placeholder
				default:
					Debug.logError("IR: Unknown unary operator: " + ctx.op.getText());
					return null;
			}
		}
		else if (ctx.castExpression() != null)
		{
			// --- START NEW LOGGING ---
			Debug.logDebug("IR (Unary): Visiting CastExpression child: " + ctx.castExpression().getText() +
					" (Hash: " + ctx.castExpression().hashCode() +
					", Interval: " + ctx.castExpression().getSourceInterval() + ")");
			// --- END NEW LOGGING ---
			return visit(ctx.castExpression());
		}
		else if (ctx.postfixExpression() != null)
		{
			// --- START NEW LOGGING ---
			Debug.logDebug("IR (Unary): Visiting PostfixExpression child: " + ctx.postfixExpression().getText() +
					" (Hash: " + ctx.postfixExpression().hashCode() +
					", Interval: " + ctx.postfixExpression().getSourceInterval() + ")");
			// --- END NEW LOGGING ---
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
			LLVMTypeRef resultLLVMType = TypeConverter.toLLVMType(resultTypeOpt.get(), moduleContext);

			// 2. Cast operands to the result type
			LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "add_lhs");
			LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "add_rhs");

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
			else
			{
				Debug.logError("IR: Unsupported types for additive operation.");
				return null;
			}
		}

		return leftVal; // Return the final accumulated value
	}

	private LLVMValueRef createEntryBlockAlloca(LLVMValueRef function, LLVMTypeRef type, String varName)
	{
		LLVMBasicBlockRef entryBlock = LLVMGetEntryBasicBlock(function); //
		LLVMBuilderRef tmpBuilder = LLVMCreateBuilderInContext(moduleContext); // Create a temporary builder in the correct context
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
}