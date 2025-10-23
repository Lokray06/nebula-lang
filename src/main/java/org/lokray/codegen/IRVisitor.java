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
import org.lokray.semantic.type.PrimitiveType;
import org.lokray.semantic.type.Type;
import org.lokray.util.Debug;

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
		String methodName = ctx.ID().getText();

		if (methodName.equals("main"))
		{
			// --- EXISTING MAIN LOGIC (Keep as is) ---
			LLVMTypeRef[] noParams = {};
			LLVMTypeRef mainFunctionType = LLVMFunctionType(LLVMInt32Type(), new PointerPointer<>(noParams), 0, 0);
			LLVMValueRef mainFunction = LLVMAddFunction(llvmContext.getModule(), "main", mainFunctionType);
			currentFunction = mainFunction;

			LLVMBasicBlockRef entryBlock = LLVMAppendBasicBlock(mainFunction, "entry");
			LLVMPositionBuilderAtEnd(llvmContext.getBuilder(), entryBlock);

			visit(ctx.block());
			LLVMBasicBlockRef lastBlock = LLVMGetLastBasicBlock(mainFunction);
			if (LLVMGetBasicBlockTerminator(lastBlock) == null)
			{
				LLVMBuildRet(llvmContext.getBuilder(), LLVMConstInt(LLVMInt32Type(), 0, 0));
			}

			currentFunction = null; // Clear current function context
			return mainFunction;
		}

		// 1. Resolve the MethodSymbol
		Optional<Symbol> symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
		if (symbolOpt.isEmpty() || !(symbolOpt.get() instanceof MethodSymbol methodSymbol))
		{
			// This should not happen if semantic analysis passed.
			Debug.logError("IR: Method declaration symbol not found or is not a MethodSymbol: " + ctx.getText());
			return null;
		}

		// --- FIX: Logic for ALL other (user-defined) methods ---
		String mangledName = methodSymbol.getMangledName();
		Debug.logWarning("IR: Defining function: " + mangledName);

		// 2. Create/Get the Function Prototype
		LLVMValueRef function = LLVMGetNamedFunction(module, mangledName);
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
			function = LLVMAddFunction(module, mangledName, functionType);
		}

		// 3. Create Entry Block and Position Builder
		LLVMBasicBlockRef entryBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "entry");
		LLVMPositionBuilderAtEnd(builder, entryBlock);

		// 4. Set context and process body
		LLVMValueRef oldFunction = currentFunction;
		currentFunction = function;
		Map<String, LLVMValueRef> outerValues = new HashMap<>(namedValues);
		namedValues.clear(); // Clear namedValues for the function's scope

        // 4. Process parameters: Create allocas and store incoming arguments
        PointerPointer<LLVMValueRef> params = new PointerPointer<>(methodSymbol.getParameterTypes().size());
        LLVMGetParams(function, params);

        for (int i = 0; i < methodSymbol.getParameterTypes().size(); i++)
        {
            Type paramNebulaType = methodSymbol.getParameterTypes().get(i);
            String paramName = methodSymbol.getParameters().get(i).getName(); // Assuming parameter names are available
            LLVMTypeRef paramLLVMType = TypeConverter.toLLVMType(paramNebulaType, moduleContext);
            LLVMValueRef incomingValue = params.get(LLVMValueRef.class, i);

            // 1. Create alloca in the entry block
            LLVMValueRef alloca = createEntryBlockAlloca(function, paramLLVMType, paramName);

            // 2. Store the incoming argument value into the alloca
            LLVMBuildStore(builder, incomingValue, alloca);

            // 3. Register the alloca in the current scope for lookups
            namedValues.put(paramName, alloca);
            addVariableToScope(paramName, alloca);
        }

        // 5. Visit the body
        visit(ctx.block());

		// 6. Finalize (Add implicit return for non-terminated blocks/void functions)
		LLVMBasicBlockRef lastBlock = LLVMGetLastBasicBlock(function);
		if (LLVMGetBasicBlockTerminator(lastBlock) == null)
		{
			Type returnType = methodSymbol.getType();
			if (returnType == PrimitiveType.VOID) // Assuming PrimitiveType.VOID exists
			{
				LLVMBuildRetVoid(builder);
			}
			else
			{
				// Add a default return (e.g., zero for numeric types) - **Requires more robust handling**
				// For simplicity with your example, we'll assume a return is always present.
				// If the language allows a non-void function to "fall off the end",
				// you must insert an unreachable instruction or a zero return based on type.
			}
		}

		// 7. Restore context
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
				List<Type> paramTypes = methodSymbol.getParameterTypes(); // [cite: 2310]
				LLVMTypeRef[] llvmParamTypes = new LLVMTypeRef[paramTypes.size()];
				for (int i = 0; i < paramTypes.size(); i++)
				{
					llvmParamTypes[i] = TypeConverter.toLLVMType(paramTypes.get(i), moduleContext); // [cite: 3787]
				}
				LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType(), moduleContext); // [cite: 2309, 3787]
				LLVMTypeRef functionType = LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), paramTypes.size(), 0); // [cite: 3763]
				function = LLVMAddFunction(module, mangledName, functionType); // [cite: 3763]
			}

			// --- 2. Prepare arguments for the call ---
			List<LLVMValueRef> args = new ArrayList<>();
			if (ctx.argumentList() != null) // [cite: 2400]
			{
				Debug.logDebug("IR: Processing " + ctx.argumentList().expression().size() + " arguments...");
				for (NebulaParser.ExpressionContext exprCtx : ctx.argumentList().expression()) // [cite: 2443]
				{
					// This recursively calls visitLiteral and correctly generates the string pointer
					args.add(visit(exprCtx));
				}
				Debug.logDebug("IR: Finished processing arguments.");
			}

			PointerPointer<LLVMValueRef> argsPtr = new PointerPointer<>(args.size()); // [cite: 3763]
			for (int i = 0; i < args.size(); i++)
			{
				argsPtr.put(i, args.get(i));
			}

			// --- 3. Build the call instruction ---
			Debug.logDebug("IR: Building LLVM call instruction for: " + mangledName);
			LLVMBuildCall2(builder, safeGetFunctionType(function, methodSymbol), function, argsPtr, args.size(), ""); // [cite: 3781]
			Debug.logDebug("IR: Call generation complete.");

			return null; // A statement expression doesn't return a value.
		}
		else
		{
			Debug.logDebug("IR: WARNING: Not a method call, or resolved symbol not found/not a MethodSymbol for context: " + ctx.getText() + ". Falling back to visitChildren.");
			// Fallback for other statement expressions (assignments, increments, etc.)
			return visitChildren(ctx); // [cite: 2845]
		}
	}

	@Override
	public LLVMValueRef visitForStatement(NebulaParser.ForStatementContext ctx)
	{
		Optional<Object> loopInfoOpt = semanticAnalyzer.getResolvedInfo(ctx); // [cite: 2734]

		if (loopInfoOpt.isEmpty())
		{ // [cite: 2734]
			Debug.logError("Codegen Error: No valid loop information found for ForStatementContext: " + ctx.getText()); // [cite: 3783]
			return null; // [cite: 3783]
		}
		Object loopInfo = loopInfoOpt.get(); // [cite: 3783]

		if (loopInfo instanceof SimplifiedForInfo info)
		{ // [cite: 3783]
			// --- SIMPLIFIED FOR LOOP ---
			Debug.logDebug("Codegen: Simplified for loop (using Semantic Info): " + ctx.getText()); // [cite: 3767]
			VariableSymbol loopVarSymbol = info.loopVariable(); // [cite: 3767]
			String varName = loopVarSymbol.getName(); // [cite: 3767]
			// *** FIX: Use loopVarSymbol's actual resolved type ***
			Type loopVarNebulaType = loopVarSymbol.getType();
			LLVMTypeRef varType = TypeConverter.toLLVMType(loopVarNebulaType, moduleContext); // [cite: 3767]
			LLVMValueRef function = currentFunction; // [cite: 3768]

			LLVMValueRef startVal; // [cite: 3768]
			if (info.startExpression() != null)
			{ // [cite: 3768]
				startVal = visit(info.startExpression()); // [cite: 3768]
			}
			else
			{
				startVal = LLVMConstInt(varType, 0, 0); // [cite: 3768]
			}
			if (startVal == null)
			{ /* ... error handling ... */ // [cite: 3768]
				return null; // [cite: 3768]
			}

			LLVMValueRef varAlloca = createEntryBlockAlloca(function, varType, varName); // [cite: 3768]
			LLVMBuildStore(builder, startVal, varAlloca); // Initialize [cite: 3769]

			Map<String, LLVMValueRef> outerValues = new HashMap<>(namedValues); // Backup scope [cite: 3769]
			namedValues.put(varName, varAlloca); // [cite: 3769]

			// --- Create Blocks --- [cite: 3769]
			LLVMBasicBlockRef loopHeaderBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "loop.header"); // [cite: 3769]
			LLVMBasicBlockRef loopBodyBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "loop.body"); // [cite: 3769]
			LLVMBasicBlockRef loopExitBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "loop.exit"); // [cite: 3769]

			LLVMBuildBr(builder, loopHeaderBlock); // Branch to Header [cite: 3769]

			// --- Populate Header --- [cite: 3770]
			LLVMPositionBuilderAtEnd(builder, loopHeaderBlock); // [cite: 3770]
			LLVMValueRef currentVal = LLVMBuildLoad2(builder, varType, varAlloca, varName + ".load"); // [cite: 3770]
			LLVMValueRef limitVal = visit(info.limitExpression()); // [cite: 3770]
			if (limitVal == null)
			{ /* ... error handling ... */ // [cite: 3770]
				namedValues.clear(); // [cite: 3770]
				namedValues.putAll(outerValues); // [cite: 3770]
				return null; // [cite: 3770]
			}

			// --- Comparison --- [cite: 3770]
			int llvmPredicate; // [cite: 3771]
			// *** FIX: Determine signedness from loop variable type ***
			boolean isSigned = !loopVarNebulaType.getName().startsWith("u"); // Check if the Nebula type name starts with 'u'
			String operator = info.operator().getText(); // [cite: 3771]
			switch (operator)
			{ // [cite: 3771]
				case "<":
					llvmPredicate = isSigned ? LLVMIntSLT : LLVMIntULT;
					break; // [cite: 3771]
				case ">":
					llvmPredicate = isSigned ? LLVMIntSGT : LLVMIntUGT;
					break; // [cite: 3771]
				case "<=":
					llvmPredicate = isSigned ? LLVMIntSLE : LLVMIntULE;
					break; // [cite: 3771]
				case ">=":
					llvmPredicate = isSigned ? LLVMIntSGE : LLVMIntUGE;
					break; // [cite: 3772]
				default: /* ... error handling ... */ // [cite: 3772]
					namedValues.clear(); // [cite: 3772]
					namedValues.putAll(outerValues); // [cite: 3772]
					return null; // [cite: 3772]
			}
			LLVMValueRef condition = LLVMBuildICmp(builder, llvmPredicate, currentVal, limitVal, "loop.cond"); // [cite: 3772]
			LLVMBuildCondBr(builder, condition, loopBodyBlock, loopExitBlock); // [cite: 3772]

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
			LLVMPositionBuilderAtEnd(builder, loopExitBlock); // [cite: 3773]
			namedValues.clear(); // Restore outer scope [cite: 3774]
			namedValues.putAll(outerValues); // [cite: 3774]

			Debug.logDebug("Codegen: Finished simplified for loop (using Semantic Info): " + ctx.getText()); // [cite: 3774]
			return null; // [cite: 3774]

		}
		else if (loopInfo instanceof TraditionalForInfo info)
		{ // [cite: 3774]
			// --- TRADITIONAL FOR LOOP ---
			// ... (existing traditional for loop codegen remains the same) ... [cite: 3774-3783]
			Debug.logDebug("Codegen: Traditional for loop (using Semantic Info): " + ctx.getText()); // [cite: 3774]
			LLVMValueRef function = currentFunction; // [cite: 3774]
			Map<String, LLVMValueRef> outerValues = new HashMap<>(namedValues); // [cite: 3774] // Backup outer scope

			// --- 1. Initializer --- [cite: 3775]
			if (info.initializer() != null)
			{ // [cite: 3775]
				if (info.initializer() instanceof NebulaParser.VariableDeclarationContext varDeclCtx)
				{ // [cite: 3775]
					visitVariableDeclarationForLoopInit(varDeclCtx); // [cite: 3775]
				}
				else if (info.initializer() instanceof NebulaParser.ExpressionContext exprCtx)
				{ // [cite: 3776]
					visit(exprCtx); // Execute initializer expression [cite: 3776]
				}
			}

			// --- 2. Create Blocks --- [cite: 3776]
			LLVMBasicBlockRef loopHeaderBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.cond"); // [cite: 3776]
			LLVMBasicBlockRef loopBodyBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.body"); // [cite: 3776]
			LLVMBasicBlockRef loopUpdateBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.update"); // [cite: 3776]
			LLVMBasicBlockRef loopExitBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.exit"); // [cite: 3776]

			LLVMBuildBr(builder, loopHeaderBlock); // Branch to Header [cite: 3776]

			// --- 4. Populate Header Block --- [cite: 3777]
			LLVMPositionBuilderAtEnd(builder, loopHeaderBlock); // [cite: 3777]
			LLVMValueRef conditionValue; // [cite: 3777]
			if (info.condition() != null)
			{ // [cite: 3777]
				conditionValue = visit(info.condition()); // [cite: 3777]
				if (conditionValue == null)
				{ /* ... error handling ... */ // [cite: 3777]
					namedValues.clear(); // [cite: 3777]
					namedValues.putAll(outerValues); // [cite: 3777]
					return null; // [cite: 3777]
				}
				Optional<Type> condNebulaTypeOpt = semanticAnalyzer.getResolvedType(info.condition()); // [cite: 3778]
				if (condNebulaTypeOpt.isPresent() && condNebulaTypeOpt.get() == PrimitiveType.BOOLEAN)
				{ // [cite: 3778]
					// It's already i1, use directly [cite: 3778]
				}
				else
				{
					LLVMTypeRef condLLVMType = LLVMTypeOf(conditionValue); // [cite: 3778]
					if (LLVMGetTypeKind(condLLVMType) == LLVMIntegerTypeKind)
					{ // [cite: 3778]
						LLVMValueRef zero = LLVMConstNull(condLLVMType); // [cite: 3779]
						conditionValue = LLVMBuildICmp(builder, LLVMIntNE, conditionValue, zero, "tobool"); // [cite: 3779]
					}
					else
					{
						LLVMValueRef zero = LLVMConstNull(condLLVMType); // [cite: 3779]
						if (LLVMGetTypeKind(condLLVMType) == LLVMFloatTypeKind || LLVMGetTypeKind(condLLVMType) == LLVMDoubleTypeKind)
						{ // [cite: 3780]
							conditionValue = LLVMBuildFCmp(builder, LLVMRealONE, conditionValue, zero, "tobool_fp"); // [cite: 3780]
						}
						else
						{
							conditionValue = LLVMBuildICmp(builder, LLVMIntNE, conditionValue, zero, "tobool_ptr"); // [cite: 3780]
						}
						Debug.logWarning("Codegen Warning: Condition in traditional for loop isn't bool/int. Using != zero/null comparison."); // [cite: 3781]
					}
				}
			}
			else
			{
				conditionValue = LLVMConstInt(LLVMInt1TypeInContext(moduleContext), 1, 0); // [cite: 3781]
			}
			LLVMBuildCondBr(builder, conditionValue, loopBodyBlock, loopExitBlock); // Branch [cite: 3781]

			// --- 5. Populate Body Block --- [cite: 3781]
			LLVMPositionBuilderAtEnd(builder, loopBodyBlock); // [cite: 3781]
			visit(ctx.block()); // Visit loop body [cite: 3782]
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{ // Branch to update if not terminated [cite: 3782]
				LLVMBuildBr(builder, loopUpdateBlock); // [cite: 3782]
			}

			// --- 6. Populate Update Block --- [cite: 3782]
			LLVMPositionBuilderAtEnd(builder, loopUpdateBlock); // [cite: 3782]
			if (info.update() != null)
			{ // [cite: 3782]
				visit(info.update()); // Generate update code from info [cite: 3782]
			}
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{ // Branch to header if not terminated [cite: 3782]
				LLVMBuildBr(builder, loopHeaderBlock); // [cite: 3783]
			}

			// --- 7. Position Builder at Exit Block --- [cite: 3783]
			LLVMPositionBuilderAtEnd(builder, loopExitBlock); // [cite: 3783]
			namedValues.clear(); // Restore outer scope [cite: 3783]
			namedValues.putAll(outerValues); // [cite: 3783]
			Debug.logDebug("Codegen: Finished traditional for loop (using Semantic Info): " + ctx.getText()); // [cite: 3783]
			return null; // [cite: 3783]
		}
		else
		{
			Debug.logError("Codegen Error: No valid loop information found for ForStatementContext: " + ctx.getText()); // [cite: 3783]
			return null; // [cite: 3783]
		}
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

		LLVMTypeRef varLLVMType = TypeConverter.toLLVMType(nebulaTypeOpt.get(), moduleContext);

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

	// --- Optional Helper for Traditional For Loop Initializer ---
	// This ensures variables declared in the 'for' initializer use createEntryBlockAlloca
	private void visitVariableDeclarationForLoopInit(NebulaParser.VariableDeclarationContext ctx)
	{
		Optional<Type> nebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.type()); //
		if (nebulaTypeOpt.isEmpty())
		{ /* ... error handling ... */
			return;
		}
		LLVMTypeRef varType = TypeConverter.toLLVMType(nebulaTypeOpt.get(), moduleContext); //

		for (NebulaParser.VariableDeclaratorContext declarator : ctx.variableDeclarator())
		{ //
			String varName = declarator.ID().getText(); //
			// *** Use createEntryBlockAlloca for loop init vars ***
			LLVMValueRef varAlloca = createEntryBlockAlloca(currentFunction, varType, varName);

			if (declarator.expression() != null)
			{
				LLVMValueRef initVal = visit(declarator.expression()); //
				if (initVal != null)
				{
					LLVMBuildStore(builder, initVal, varAlloca); //
				}
				else
				{ /* ... error handling ... */ }
			}
			// else: Uninitialized variable

			namedValues.put(varName, varAlloca); // Make available within loop scope
		}
	}

	@Override
	public LLVMValueRef visitLiteral(NebulaParser.LiteralContext ctx)
	{
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
			LLVMValueRef[] fields = new LLVMValueRef[]{
					dataPtr,
					LLVMConstInt(LLVMInt32Type(), value.length(), 0)
			};
			LLVMValueRef structConst = LLVMConstNamedStruct(stringType, new PointerPointer<>(fields), fields.length);

			LLVMValueRef globalString = LLVMAddGlobal(module, LLVMTypeOf(structConst), "str_literal_struct");
			LLVMSetInitializer(globalString, structConst);
			LLVMSetGlobalConstant(globalString, 1);
			LLVMSetLinkage(globalString, LLVMPrivateLinkage);
			return globalString;
		}

		// ----- SEMANTIC CONSTANT HANDLING -----
		Object semConst = null;
		org.lokray.semantic.type.Type semType = null;
		try
		{
			semConst = semanticAnalyzer.getResolvedInfo(ctx).orElse(null);
			semType = semanticAnalyzer.getResolvedType(ctx).orElse(null);
		}
		catch (Exception ignored)
		{
		}

		if (semConst != null && semType != null)
		{
			String t = semType.getName();
			switch (t)
			{
				case "int8":
				case "uint8":
				{
					int v = ((Number) semConst).intValue();
					return LLVMConstInt(LLVMInt8Type(), Integer.toUnsignedLong(v), 0);
				}
				case "int16":
				case "uint16":
				{
					int v = ((Number) semConst).intValue();
					return LLVMConstInt(LLVMInt16Type(), Integer.toUnsignedLong(v), 0);
				}
				case "int32":
				case "uint32":
				{
					int v = ((Number) semConst).intValue();
					return LLVMConstInt(LLVMInt32Type(), Integer.toUnsignedLong(v), 0);
				}
				case "int64":
				case "uint64":
				{
					long v = ((Number) semConst).longValue();
					return LLVMConstInt(LLVMInt64Type(), v, 0);
				}
				case "float":
				{
					float v = ((Number) semConst).floatValue();
					return LLVMConstReal(LLVMFloatType(), v);
				}
				case "double":
				{
					double v = ((Number) semConst).doubleValue();
					return LLVMConstReal(LLVMDoubleType(), v);
				}
			}
		}

		// ----- FALLBACK PARSING -----
		if (ctx.INTEGER_LITERAL() != null)
		{
			return LLVMConstInt(LLVMInt32Type(),
					Integer.toUnsignedLong(Integer.parseInt(ctx.INTEGER_LITERAL().getText())), 0);
		}

		if (ctx.LONG_LITERAL() != null)
		{
			return LLVMConstInt(LLVMInt64Type(),
					Long.parseLong(ctx.LONG_LITERAL().getText().replaceAll("[lL]$", "")), 0);
		}

		if (ctx.HEX_LITERAL() != null)
		{
			return LLVMConstInt(LLVMInt32Type(),
					Long.parseLong(ctx.HEX_LITERAL().getText().replaceFirst("0x", ""), 16), 0);
		}

		if (ctx.FLOAT_LITERAL() != null)
		{
			return LLVMConstReal(LLVMFloatType(),
					Float.parseFloat(ctx.FLOAT_LITERAL().getText().replace("f", "")));
		}

		if (ctx.DOUBLE_LITERAL() != null)
		{
			return LLVMConstReal(LLVMDoubleType(), Double.parseDouble(ctx.DOUBLE_LITERAL().getText()));
		}

		if (ctx.BOOLEAN_LITERAL() != null)
		{
			return LLVMConstInt(LLVMInt1Type(), ctx.BOOLEAN_LITERAL().getText().equals("true") ? 1 : 0, 0);
		}

		if (ctx.CHAR_LITERAL() != null)
		{
			return LLVMConstInt(LLVMInt8Type(), 0, 0);
		}

		return super.visitLiteral(ctx);
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
		// Check if the *entire* postfix expression (e.g., "getPi()")
		// was resolved to a MethodSymbol by the TypeCheckVisitor.
		Optional<Symbol> symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);

		if (symbolOpt.isPresent() && symbolOpt.get() instanceof MethodSymbol methodSymbol)
		{
			// --- This is a method call used as an expression ---
			Debug.logWarning("IR (Postfix): Resolved as method call: " + methodSymbol);

			String mangledName = methodSymbol.getMangledName();
			LLVMValueRef function = LLVMGetNamedFunction(module, mangledName);

			// 1. Create Function Prototype if it doesn't exist
			if (function == null)
			{
				Debug.logWarning("IR (Postfix): Function prototype not found. Creating LLVM declaration for: " + mangledName);
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
            List<Type> expectedParamTypes = methodSymbol.getParameterTypes(); // <--- Get expected types
			// Find the ArgumentListContext child node
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
                Debug.logWarning("IR (Postfix): Processing " + argListCtx.expression().size() + " arguments...");
                for (int i = 0; i < argListCtx.expression().size(); i++)
                {
                    NebulaParser.ExpressionContext exprCtx = argListCtx.expression().get(i);
                    LLVMValueRef argValue = visit(exprCtx);

                    // --- NEW CONVERSION LOGIC ---
                    if (i < expectedParamTypes.size() && argValue != null)
                    {
                        Type targetType = expectedParamTypes.get(i);
                        LLVMTypeRef targetLLVMType = TypeConverter.toLLVMType(targetType, moduleContext);
                        LLVMTypeRef actualLLVMType = LLVMTypeOf(argValue);

                        // Check if argument needs promotion/conversion (e.g., int to double)
                        if (!actualLLVMType.equals(targetLLVMType))
                        {
                            // This is a common conversion utility, assuming you have one or build one.
                            // For int-to-double:
                            if (LLVMGetTypeKind(actualLLVMType) == LLVMIntegerTypeKind &&
                                    LLVMGetTypeKind(targetLLVMType) == LLVMDoubleTypeKind)
                            {
                                // Convert Signed Integer to Floating Point
                                argValue = LLVMBuildSIToFP(builder, argValue, targetLLVMType, "arg_sitofp");
                            }
                            // Add logic for int->float, float->double (fpext) if needed,
                            // but int->double fixes this specific case.
                        }
                    }
                    // --- END CONVERSION LOGIC ---

                    args.add(argValue);
                }
            }

			PointerPointer<LLVMValueRef> argsPtr = new PointerPointer<>(args.size());
			for (int i = 0; i < args.size(); i++)
			{
				argsPtr.put(i, args.get(i));
			}

			// 3. Build the call instruction
			Debug.logWarning("IR (Postfix): Building LLVM call instruction for: " + mangledName);
			// This call *returns a value*, so we return the LLVMValueRef from the call.
			return LLVMBuildCall2(builder, safeGetFunctionType(function, methodSymbol), function, argsPtr, args.size(), methodSymbol.getName() + ".call");
		}
		else
		{
			// --- Not a method call ---
			// This is likely a variable access (like "fPi") or member access.
			// Just visit the primary part to load the variable.
			return visit(ctx.primary());
		}
	}

    @Override
    public LLVMValueRef visitCastExpression(NebulaParser.CastExpressionContext ctx)
    {
        LLVMValueRef originalValue = visit(ctx.unaryExpression());
        if (originalValue == null)
            return null;

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
            return originalValue;

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
                    return LLVMBuildTrunc(builder, originalValue, targetLLVMType, "trunc");
                else if (targetBits > originalBits)
                    return originalIsUnsigned
                            ? LLVMBuildZExt(builder, originalValue, targetLLVMType, "zext")
                            : LLVMBuildSExt(builder, originalValue, targetLLVMType, "sext");
                return LLVMBuildBitCast(builder, originalValue, targetLLVMType, "intcast");
            }

            // ----- float -> float -----
            if (targetIsFloat && originalIsFloat)
            {
                if (LLVMGetTypeKind(targetLLVMType) == LLVMDoubleTypeKind &&
                        LLVMGetTypeKind(originalLLVMType) == LLVMFloatTypeKind)
                    return LLVMBuildFPExt(builder, originalValue, targetLLVMType, "fpext");
                else if (LLVMGetTypeKind(targetLLVMType) == LLVMFloatTypeKind &&
                        LLVMGetTypeKind(originalLLVMType) == LLVMDoubleTypeKind)
                    return LLVMBuildFPTrunc(builder, originalValue, targetLLVMType, "fptrunc");
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

        Debug.logWarning("IR: Using fallback LLVMBuildBitCast for cast: " + ctx.getText());
        return LLVMBuildBitCast(builder, originalValue, targetLLVMType, "bitcast");
    }

	@Override
	public LLVMValueRef visitPrimary(NebulaParser.PrimaryContext ctx)
	{
		// 1) Literal: delegate
		if (ctx.literal() != null)
		{
			return visit(ctx.literal());
		}

		// 2) Parenthesized expression: ( expr )
		if (ctx.expression() != null)
		{
			return visit(ctx.expression());
		}

		// 3) Identifier usage
		if (ctx.ID() != null)
		{
			Debug.logDebug("Visiting primary expression for ID:" + ctx.ID().getText());
			String name = ctx.ID().getText();
			Optional<Symbol> symOpt = semanticAnalyzer.getResolvedSymbol(ctx);

			if (symOpt.isPresent())
			{
				Symbol sym = symOpt.get();

				if (sym instanceof VariableSymbol varSym)
				{
					Debug.logDebug("Resolved:" + symOpt.get().getType().getName() + " " + symOpt.get().getName());

					// --- START FIX ---
					// Find the alloca for this variable using the scope-aware lookup
					LLVMValueRef alloca = lookupVariable(varSym.getName()); // Use scope-aware lookup
					// --- END FIX ---

					if (alloca == null)
					{
						// --- MODIFIED ERROR ---
						// The old parameter-finding logic was unreliable.
						// If lookupVariable fails, the alloca is genuinely missing.
						Debug.logWarning("IR: variable '" + varSym.getName() + "' used but no alloca found in any scope. Are you missing an allocation?");
						return null;
						// --- END MODIFIED ERROR ---
					}

					// Load the variable value and return it
					LLVMTypeRef varType = TypeConverter.toLLVMType(varSym.getType(), moduleContext);
					LLVMValueRef loaded = LLVMBuildLoad2(builder, varType, alloca, name + ".load");
					return loaded;
				}
				else if (sym instanceof MethodSymbol)
				{
					// This is a method group (e.g., "getPi").
					// This is correct. The PostfixExpression visitor will handle the call.
					Debug.logWarning("IR: primary ID '" + name + "' resolved to method group: " + sym);
					return null;
				}
				else
				{
					// Not a variable symbol (could be a type name, etc.)
					Debug.logWarning("IR: primary ID '" + name + "' resolved to non-variable symbol: " + sym);
					return null;
				}
			}
			else
			{
				// --- START FIX ---
				// Fallback: check scope-aware lookup by text
				LLVMValueRef alloca = lookupVariable(name);
				// --- END FIX ---
				if (alloca != null)
				{
					Optional<org.lokray.semantic.type.Type> typeOpt = semanticAnalyzer.getResolvedType(ctx);
					LLVMTypeRef llvmType = typeOpt.isPresent() ? TypeConverter.toLLVMType(typeOpt.get(), moduleContext) : LLVMInt32Type();
					return LLVMBuildLoad2(builder, llvmType, alloca, name + ".load");
				}
				Debug.logWarning("IR: No resolved symbol for primary '" + ctx.getText() + "' and not present in any scope.");
				return null;
			}
		}

		// 4) other primary forms (this, null, new, etc.) - simple fallback for now
		return visitChildren(ctx);
	}
    @Override
    public LLVMValueRef visitMultiplicativeExpression(NebulaParser.MultiplicativeExpressionContext ctx)
    {
        if (ctx.powerExpression().size() > 1)
        {
            LLVMValueRef leftVal = visit(ctx.powerExpression(0));
            LLVMValueRef rightVal = visit(ctx.powerExpression(1));

            if (leftVal == null || rightVal == null)
            {
                return null;
            }

            // 1. Get the final result type (determined by the type checker)
            Optional<org.lokray.semantic.type.Type> resultTypeOpt = semanticAnalyzer.getResolvedType(ctx);
            if (resultTypeOpt.isEmpty())
            {
                Debug.logError("IR: Multiplicative expression result type not found.");
                return null;
            }
            LLVMTypeRef resultLLVMType = TypeConverter.toLLVMType(resultTypeOpt.get(), moduleContext);

            // 2. Cast operands to the result type
            LLVMValueRef leftCasted = buildCast(builder, leftVal, resultLLVMType, "mult_lhs");
            LLVMValueRef rightCasted = buildCast(builder, rightVal, resultLLVMType, "mult_rhs");

            LLVMTypeRef finalType = LLVMTypeOf(leftCasted); // Now should be the common widest type

            // 3. Select the operation based on the type
            if (LLVMGetTypeKind(finalType) == LLVMDoubleTypeKind || LLVMGetTypeKind(finalType) == LLVMFloatTypeKind)
            {
                // Floating-point arithmetic
                if (ctx.MUL_OP() != null)
                {
                    return LLVMBuildFMul(builder, leftCasted, rightCasted, "fmul_tmp");
                }
                else if (ctx.DIV_OP() != null)
                {
                    return LLVMBuildFDiv(builder, leftCasted, rightCasted, "fdiv_tmp");
                }
                else if (ctx.MOD_OP() != null)
                {
                    // Note: FRem is floating-point remainder (modulus)
                    return LLVMBuildFRem(builder, leftCasted, rightCasted, "frem_tmp");
                }
            }
            else if (LLVMGetTypeKind(finalType) == LLVMIntegerTypeKind)
            {
                // Integer arithmetic (assuming signed for Div and Rem)
                if (ctx.MUL_OP() != null)
                {
                    return LLVMBuildMul(builder, leftCasted, rightCasted, "mul_tmp");
                }
                else if (ctx.DIV_OP() != null)
                {
                    return LLVMBuildSDiv(builder, leftCasted, rightCasted, "sdiv_tmp"); // Signed division
                }
                else if (ctx.MOD_OP() != null)
                {
                    return LLVMBuildSRem(builder, leftCasted, rightCasted, "srem_tmp"); // Signed remainder
                }
            }

            Debug.logError("IR: Unsupported types for multiplicative operation.");
            return null;
        }
        return visitChildren(ctx);
    }

    @Override
    public LLVMValueRef visitAdditiveExpression(NebulaParser.AdditiveExpressionContext ctx)
    {
        if (ctx.multiplicativeExpression().size() > 1)
        {
            LLVMValueRef leftVal = visit(ctx.multiplicativeExpression(0));
            LLVMValueRef rightVal = visit(ctx.multiplicativeExpression(1));

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
                if (ctx.ADD_OP() != null)
                {
                    return LLVMBuildFAdd(builder, leftCasted, rightCasted, "fadd_tmp");
                }
                else if (ctx.SUB_OP() != null)
                {
                    return LLVMBuildFSub(builder, leftCasted, rightCasted, "fsub_tmp");
                }
            }
            else if (LLVMGetTypeKind(finalType) == LLVMIntegerTypeKind)
            {
                // Integer arithmetic
                if (ctx.ADD_OP() != null)
                {
                    return LLVMBuildAdd(builder, leftCasted, rightCasted, "add_tmp");
                }
                else if (ctx.SUB_OP() != null)
                {
                    return LLVMBuildSub(builder, leftCasted, rightCasted, "sub_tmp");
                }
            }

            Debug.logError("IR: Unsupported types for additive operation.");
            return null;
        }
        return visitChildren(ctx);
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

        if (sourceType.equals(targetType)) {
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

            if (targetBits > sourceBits) {
                // Assuming signed extension for arithmetic
                return LLVMBuildSExt(builder, sourceValue, targetType, name + ".sext");
            } else if (targetBits < sourceBits) {
                return LLVMBuildTrunc(builder, sourceValue, targetType, name + ".trunc");
            }
        }

        // Fallback for other complex or unsupported cases
        Debug.logWarning("IR: Attempted unsupported numeric cast from " + LLVMPrintTypeToString(sourceType) + " to " + LLVMPrintTypeToString(targetType));
        return sourceValue;
    }
}