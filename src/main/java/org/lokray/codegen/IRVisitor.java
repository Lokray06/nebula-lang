// File: src/main/java/org/lokray/codegen/IRVisitor.java
package org.lokray.codegen;

import org.antlr.v4.runtime.tree.ParseTree;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Pointer;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;
import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.semantic.SemanticAnalyzer;
import org.lokray.semantic.info.SimplifiedForInfo;
import org.lokray.semantic.info.TraditionalForInfo;
import org.lokray.semantic.symbol.*;
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
	private final LLVMContextRef moduleContext; // <-- add this

	public IRVisitor(SemanticAnalyzer semanticAnalyzer, LLVMContext llvmContext)
	{
		this.semanticAnalyzer = semanticAnalyzer;
		this.llvmContext = llvmContext;
		builder = llvmContext.getBuilder();
		module = llvmContext.getModule();
		moduleContext = LLVMGetModuleContext(module);// Get the map
        scopedValues.push(new HashMap<>());
	}

	private String getMangledName(MethodSymbol methodSymbol)
	{
		Scope parent = methodSymbol.getEnclosingScope();
		if (parent instanceof ClassSymbol)
		{
			ClassSymbol classSymbol = (ClassSymbol) parent;
			String fqn = classSymbol.getName();
			if (classSymbol.getEnclosingScope() instanceof NamespaceSymbol)
			{
				fqn = ((NamespaceSymbol) classSymbol.getEnclosingScope()).getFqn() + "." + classSymbol.getName();
			}

			String baseName = fqn.replace('.', '_') + "_" + methodSymbol.getName();
			StringBuilder mangled = new StringBuilder(baseName);

			for (Type paramType : methodSymbol.getParameterTypes())
			{
				mangled.append("__").append(canonicalTypeName(paramType));
			}

			mangled.append("___").append(canonicalTypeName(methodSymbol.getType()));

			return mangled.toString();
		}
		return methodSymbol.getName();
	}

	@Override
	public LLVMValueRef visitMethodDeclaration(NebulaParser.MethodDeclarationContext ctx)
	{
		if (ctx.ID().getText().equals("main"))
		{
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

			return mainFunction;
		}
		return null;
	}

	@Override
	public LLVMValueRef visitStatementExpression(NebulaParser.StatementExpressionContext ctx)
	{
		// The expression part of the statement, e.g., 'Console.println(...)'
		ParseTree methodCallExpr = ctx.getChild(0);

		Debug.logWarning("IR: Processing statement expression: " + ctx.getText());

		// --- START MODIFICATION ---
		// Directly look up the symbol associated with the *entire statement expression context*.
		// This is where TypeCheckVisitor stores the result of overload resolution.
		Debug.logWarning("IR: Attempting lookup using StatementExpression context: (" + ctx.getText() + ").");
		Optional<Symbol> symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);

		if (symbolOpt.isPresent())
		{
			Debug.logWarning("IR: Lookup SUCCEEDED. Symbol found: " + symbolOpt.get());
		}
		else
		{
			Debug.logWarning("IR: Lookup FAILED. No symbol found for StatementExpression context.");
		}
		// --- END MODIFICATION ---


		if (symbolOpt.isPresent() && symbolOpt.get() instanceof MethodSymbol methodSymbol)
		{
			// --- Symbol Found: Proceed with Call Generation ---
			Debug.logWarning("IR: RESOLUTION SUCCESSFUL. Proceeding with code generation for method: " + methodSymbol); // Log the full symbol

			String mangledName = getMangledName(methodSymbol);
			Debug.logWarning("IR: Generated mangled name: " + mangledName);

			// 1. Function Prototype/Definition Lookup
			LLVMValueRef function = LLVMGetNamedFunction(module, mangledName);
			if (function == null)
			{
				Debug.logWarning("IR: Function prototype not found. Creating LLVM declaration for: " + mangledName);
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
				Debug.logWarning("IR: Processing " + ctx.argumentList().expression().size() + " arguments...");
				for (NebulaParser.ExpressionContext exprCtx : ctx.argumentList().expression()) // [cite: 2443]
				{
					// This recursively calls visitLiteral and correctly generates the string pointer
					args.add(visit(exprCtx));
				}
				Debug.logWarning("IR: Finished processing arguments.");
			}

			PointerPointer<LLVMValueRef> argsPtr = new PointerPointer<>(args.size()); // [cite: 3763]
			for (int i = 0; i < args.size(); i++)
			{
				argsPtr.put(i, args.get(i));
			}

			// --- 3. Build the call instruction ---
			Debug.logWarning("IR: Building LLVM call instruction for: " + mangledName);
			LLVMBuildCall2(builder, safeGetFunctionType(function, methodSymbol), function, argsPtr, args.size(), ""); // [cite: 3781]
			Debug.logWarning("IR: Call generation complete.");

			return null; // A statement expression doesn't return a value.
		}
		else
		{
			Debug.logWarning("IR: WARNING: Not a method call, or resolved symbol not found/not a MethodSymbol for context: " + ctx.getText() + ". Falling back to visitChildren.");
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

    // New method to implement variable declaration IR generation
    @Override
    public LLVMValueRef visitVariableDeclaration(NebulaParser.VariableDeclarationContext ctx)
    {
        System.out.println("================================================ VISITING VARIABLE DECLARATION IN THE IR VISITOR");

        Optional<Type> nebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.type());
        // If the semantic analyzer resolves it, it *must* be present here.
        if (nebulaTypeOpt.isEmpty())
        {
            // If this error happens, the subsequent code will fail.
            Debug.logError("IR Error: Could not resolve type for variable declaration: " + ctx.type().getText());
            return null;
        }

        // Ensure the TypeConverter can handle the resolved Nebula Type
        LLVMTypeRef varLLVMType = TypeConverter.toLLVMType(nebulaTypeOpt.get(), moduleContext);

        for (NebulaParser.VariableDeclaratorContext declarator : ctx.variableDeclarator())
        {
            String varName = declarator.ID().getText();

            // Create allocation in the function entry block for local variables
            LLVMValueRef varAlloca = createEntryBlockAlloca(currentFunction, varLLVMType, varName);

            // Handle initializer if present
            if (declarator.expression() != null)
            {
                LLVMValueRef initVal = visit(declarator.expression());
                System.out.println("============================================ VISITING INITIALIZER RETURNED:" + declarator.expression().getText());

                if (initVal == null)
                {
                    Debug.logError("IR Error: Failed to generate initializer for variable: " + varName);
                    continue; // Skip storing for this var
                }
                LLVMBuildStore(builder, initVal, varAlloca);
            }
            // else: Variable is declared but not initialized (LLVM alloca defaults to undef/garbage)

            // Add the allocation to the current scope map
            addVariableToScope(varName, varAlloca);
            namedValues.put(varName, varAlloca); // optional: maintain flat lookup consistency
        }
        return null; // Variable declaration statement doesn't produce a value
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
		// Strings: keep your existing implementation (unchanged).
		if (ctx.STRING_LITERAL() != null)
		{
			String value = ctx.STRING_LITERAL().getText();
			value = value.substring(1, value.length() - 1); // strip quotes

			// --- START FIX for String ---
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
			// --- END FIX for String ---
		}

		// Try to reuse semantic-phase parsed constant and resolved type if available
		Object semConst = null;
		org.lokray.semantic.type.Type semType = null;
		try
		{
			// FIX 1 & 2: Use getResolvedInfo for constant and handle Optional for both constant and type
			Optional<Object> constantOpt = semanticAnalyzer.getResolvedInfo(ctx);
			Optional<org.lokray.semantic.type.Type> typeOpt = semanticAnalyzer.getResolvedType(ctx);

			if (constantOpt.isPresent())
			{
				semConst = constantOpt.get();
			}
			if (typeOpt.isPresent())
			{
				semType = typeOpt.get();
			}
		}
		catch (Exception ignored)
		{
			// If your semantic analyzer API differs, this is non-fatal: we'll fall back to parsing below.
		}

		// Helper to safely build LLVM integer constants from BigInteger and bit width.
		java.util.function.BiFunction<BigInteger, Integer, LLVMValueRef> buildIntConst =
				(bigInt, bits) ->
				{
					BigInteger min64 = BigInteger.valueOf(Long.MIN_VALUE);
					BigInteger max64 = BigInteger.valueOf(Long.MAX_VALUE);
					BigInteger min32 = BigInteger.valueOf(Integer.MIN_VALUE);
					BigInteger max32 = BigInteger.valueOf(Integer.MAX_VALUE);

					if (bits == 32)
					{
						if (bigInt.compareTo(min32) < 0 || bigInt.compareTo(max32) > 0)
						{
							Debug.logError("Integer literal out of range for i32: " + ctx.getText());
							return null;
						}
						int v = bigInt.intValue();
						// LLVMConstInt takes unsigned long for value; pass unsigned representation of int
						return LLVMConstInt(LLVMInt32Type(), Integer.toUnsignedLong(v), 1);
					}
					else if (bits == 64)
					{
						if (bigInt.compareTo(min64) < 0 || bigInt.compareTo(max64) > 0)
						{
							Debug.logError("Integer literal out of range for i64: " + ctx.getText());
							return null;
						}
						long v = bigInt.longValue();
						return LLVMConstInt(LLVMInt64Type(), v, 1);
					}
					else
					{
						Debug.logError("Unsupported integer width: " + bits);
						return null;
					}
				};

		// If semantic info present, use it
		if (semConst != null && semType != null)
		{
			// FIX 3: Check type name instead of calling non-existent isInt64()
			if (semType.getName().equals("int64"))
			{
				long v = ((Number) semConst).longValue();
				return LLVMConstInt(LLVMInt64Type(), v, 1);
				// FIX 4: Check type name instead of calling non-existent isInt32()
			}
			else if (semType.getName().equals("int32"))
			{
				int v = ((Number) semConst).intValue();
				return LLVMConstInt(LLVMInt32Type(), Integer.toUnsignedLong(v), 1);
			}
			// If semanticType is something else, fallthrough to other handlers below.
		}

		// Defensive fallback parsing if no semantic constant available
		if (ctx.INTEGER_LITERAL() != null)
		{
			try
			{
				BigInteger bi = new BigInteger(ctx.INTEGER_LITERAL().getText(), 10);
				return buildIntConst.apply(bi, 32);
			}
			catch (NumberFormatException ex)
			{
				Debug.logError("Invalid integer literal: " + ctx.INTEGER_LITERAL().getText());
				return null;
			}
		}

		if (ctx.LONG_LITERAL() != null)
		{
			String text = ctx.LONG_LITERAL().getText();
			text = text.substring(0, text.length() - 1); // strip L
			try
			{
				BigInteger bi = new BigInteger(text, 10);
				return buildIntConst.apply(bi, 64);
			}
			catch (NumberFormatException ex)
			{
				Debug.logError("Invalid long literal format: " + ctx.LONG_LITERAL().getText());
				return null;
			}
		}

		if (ctx.HEX_LITERAL() != null)
		{
			String text = ctx.HEX_LITERAL().getText();
			boolean longSuffix = text.endsWith("l") || text.endsWith("L");
			String raw = longSuffix ? text.substring(2, text.length() - 1) : text.substring(2);
			try
			{
				BigInteger bi = new BigInteger(raw, 16);
				return buildIntConst.apply(bi, longSuffix ? 64 : 32);
			}
			catch (NumberFormatException ex)
			{
				Debug.logError("Invalid hex literal: " + ctx.HEX_LITERAL().getText());
				return null;
			}
		}

		// Doubles, floats, booleans, chars (reuse your existing code or keep current handling):
		if (ctx.DOUBLE_LITERAL() != null)
		{
			try
			{
				double val = Double.parseDouble(ctx.DOUBLE_LITERAL().getText());
				return LLVMConstReal(LLVMDoubleType(), val);
			}
			catch (NumberFormatException e)
			{
				Debug.logError("Invalid double literal format: " + ctx.DOUBLE_LITERAL().getText());
				return null;
			}
		}

		if (ctx.FLOAT_LITERAL() != null)
		{
			try
			{
				String text = ctx.FLOAT_LITERAL().getText();
				text = text.substring(0, text.length() - 1);
				float val = Float.parseFloat(text);
				return LLVMConstReal(LLVMFloatType(), val);
			}
			catch (NumberFormatException e)
			{
				Debug.logError("Invalid float literal format: " + ctx.FLOAT_LITERAL().getText());
				return null;
			}
		}

		if (ctx.BOOLEAN_LITERAL() != null)
		{
			boolean isTrue = ctx.BOOLEAN_LITERAL().getText().equals("true");
			return LLVMConstInt(LLVMInt1Type(), isTrue ? 1 : 0, 0);
		}

		if (ctx.CHAR_LITERAL() != null)
		{
			Debug.logWarning("Character literals are not yet fully implemented in IRVisitor.");
			return LLVMConstInt(LLVMInt8Type(), 0, 0); // Placeholder
		}

		return super.visitLiteral(ctx);
	}

	@Override
	public LLVMValueRef visitReturnStatement(NebulaParser.ReturnStatementContext ctx)
	{
		if (ctx.expression() != null)
		{
			LLVMValueRef retVal = visit(ctx.expression());
			return LLVMBuildRet(builder, retVal);
		}
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
        for (NebulaParser.StatementContext stmtCtx : ctx.statement()) {
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
		// Most postfix expressions delegate to their primary part (field access / method calls
		// are handled elsewhere). This conservative fallback visits the primary and returns whatever
		// value it produces (or null).
		if (ctx.primary() != null)
		{
			return visit(ctx.primary());
		}
		return visitChildren(ctx);
	}

    @Override
    public LLVMValueRef visitCastExpression(NebulaParser.CastExpressionContext ctx)
    {
        // 1. Visit the inner expression
        LLVMValueRef originalValue = visit(ctx.unaryExpression());
        if (originalValue == null)
        {
            return null;
        }

        // 2. Get Nebula types
        Optional<Type> originalNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.unaryExpression());
        Optional<Type> targetNebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx);
        System.out.println("Trying cast on " + ctx.getText() + " resolved as:" + originalNebulaTypeOpt.get().getName() + " " + targetNebulaTypeOpt.get().getName());

        if (originalNebulaTypeOpt.isEmpty() || targetNebulaTypeOpt.isEmpty())
        {
            Debug.logError("IR Error: Could not resolve types for cast expression: " + ctx.getText());
            return null;
        }
        Type originalType = originalNebulaTypeOpt.get();
        Type targetType = targetNebulaTypeOpt.get();

        // 3. Get LLVM types
        LLVMTypeRef targetLLVMType = TypeConverter.toLLVMType(targetType, moduleContext);
        LLVMTypeRef originalLLVMType = LLVMTypeOf(originalValue);

        // 4. No-op check
        if (originalLLVMType.equals(targetLLVMType))
        {
            return originalValue;
        }

        // 5. Determine cast instruction based on Nebula types for signedness
        boolean targetIsNumeric = targetType.isNumeric();
        boolean originalIsNumeric = originalType.isNumeric();
        boolean targetIsUnsigned = targetType.getName().startsWith("u");
        boolean originalIsUnsigned = originalType.getName().startsWith("u");

        if (targetIsNumeric && originalIsNumeric)
        {
            int targetBits = 0;
            int originalBits = 0;
            if (LLVMGetTypeKind(targetLLVMType) == LLVMIntegerTypeKind)
            {
                targetBits = LLVMGetIntTypeWidth(targetLLVMType);
            }
            if (LLVMGetTypeKind(originalLLVMType) == LLVMIntegerTypeKind)
            {
                originalBits = LLVMGetIntTypeWidth(originalLLVMType);
            }

            // Int to Int
            if (targetBits > 0 && originalBits > 0)
            {
                if (targetBits < originalBits)
                {
                    return LLVMBuildTrunc(builder, originalValue, targetLLVMType, "trunc");
                }
                else if (targetBits > originalBits)
                {
                    return originalIsUnsigned ?
                            LLVMBuildZExt(builder, originalValue, targetLLVMType, "zext") :
                            LLVMBuildSExt(builder, originalValue, targetLLVMType, "sext");
                }
                // else same bits -> fallthrough to bitcast if needed (e.g., int<->uint)
                // LLVM treats i32 the same regardless of signedness attribute in source lang
                // A bitcast might be needed if you represent pointers differently, but for int<->uint of same size, it's often a no-op value-wise
                return LLVMBuildBitCast(builder, originalValue, targetLLVMType, "intcast");
            }

            // Float to Float
            boolean targetIsFloatOrDouble = (LLVMGetTypeKind(targetLLVMType) == LLVMFloatTypeKind || LLVMGetTypeKind(targetLLVMType) == LLVMDoubleTypeKind);
            boolean originalIsFloatOrDouble = (LLVMGetTypeKind(originalLLVMType) == LLVMFloatTypeKind || LLVMGetTypeKind(originalLLVMType) == LLVMDoubleTypeKind);
            if (targetIsFloatOrDouble && originalIsFloatOrDouble)
            {
                if (LLVMGetTypeKind(targetLLVMType) == LLVMDoubleTypeKind && LLVMGetTypeKind(originalLLVMType) == LLVMFloatTypeKind)
                {
                    return LLVMBuildFPExt(builder, originalValue, targetLLVMType, "fpext");
                }
                else if (LLVMGetTypeKind(targetLLVMType) == LLVMFloatTypeKind && LLVMGetTypeKind(originalLLVMType) == LLVMDoubleTypeKind)
                {
                    return LLVMBuildFPTrunc(builder, originalValue, targetLLVMType, "fptrunc");
                }
            }

            // Int to Float
            if (targetIsFloatOrDouble && originalBits > 0)
            {
                return originalIsUnsigned ?
                        LLVMBuildUIToFP(builder, originalValue, targetLLVMType, "uitofp") :
                        LLVMBuildSIToFP(builder, originalValue, targetLLVMType, "sitofp");
            }

            // Float to Int
            if (targetBits > 0 && originalIsFloatOrDouble)
            {
                return targetIsUnsigned ?
                        LLVMBuildFPToUI(builder, originalValue, targetLLVMType, "fptoui") :
                        LLVMBuildFPToSI(builder, originalValue, targetLLVMType, "fptosi");
            }
        }

        // Fallback (e.g., pointer casts, reference type casts if you add them)
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

		// 3) Identifier usage: load from namedValues (the allocation should have been
		//    created earlier — e.g. loop variable allocation uses createEntryBlockAlloca
		//    and namedValues.put(varName, varAlloca))
		if (ctx.ID() != null)
		{
            System.out.println("Visiting primary expression for ID:" + ctx.ID().getText());
			String name = ctx.ID().getText();

			// Prefer a symbol-based lookup first (semantic analyzer stores resolved symbols for parse nodes).
			Optional<Symbol> symOpt = semanticAnalyzer.getResolvedSymbol(ctx);


			if (symOpt.isPresent())
			{
				Symbol sym = symOpt.get();

				if (sym instanceof VariableSymbol varSym)
				{
                    System.out.println("Resolved:" + symOpt.get().getType().getName() + " " + symOpt.get().getName());

                    // Find the alloca for this variable
					LLVMValueRef alloca = namedValues.get(varSym.getName());
					if (alloca == null)
					{
						// If we don't have an alloca in namedValues, it may be a parameter/field/global.
						// Try to defensively handle some cases:

						// - function parameter case: parameters are named on the LLVM function; try to find it
						if (currentFunction != null)
						{
							// Count params and get them
							int paramCount = LLVMCountParams(currentFunction);
							PointerPointer paramsPP = new PointerPointer(paramCount);
							LLVMGetParams(currentFunction, paramsPP);

							for (int i = 0; i < paramCount; i++)
							{
								// Wrap raw pointer into LLVMValueRef
								Pointer pptr = paramsPP.get(i);
								if (pptr == null)
								{
									continue;
								}
								LLVMValueRef paramValue = new LLVMValueRef(pptr);

								// Try to get the parameter name from the LLVM value (may be empty if unnamed)
								BytePointer namePtr = LLVMGetValueName(paramValue);
								String paramName = (namePtr != null) ? namePtr.getString() : ("arg" + i);

								// Determine the LLVM type for this parameter (use TypeOf or your type info)
								LLVMTypeRef paramType = LLVMTypeOf(paramValue);

								// Create an alloca in the entry block for this parameter
								LLVMValueRef paramAlloca = createEntryBlockAlloca(currentFunction, paramName, paramType);

								// Store the incoming parameter value into the alloca so the parameter behaves like a local
								LLVMBuildStore(builder, paramValue, paramAlloca);

								// Put the alloca into the namedValues map so loads will find it
								namedValues.put(paramName, paramAlloca);
							}
						}

						Debug.logWarning("IR: variable '" + varSym.getName() + "' used but no alloca found in namedValues. Are you missing an allocation?");
						return null;
					}

					// Load the variable value and return it
					LLVMTypeRef varType = TypeConverter.toLLVMType(varSym.getType(), moduleContext);
					// Use LLVMBuildLoad2 which needs explicit type and produces proper typed value
					LLVMValueRef loaded = LLVMBuildLoad2(builder, varType, alloca, name + ".load");
					return loaded;
				}
				else
				{
					// Not a variable symbol (could be a type name, method group, etc.)
					Debug.logWarning("IR: primary ID '" + name + "' resolved to non-variable symbol: " + sym);
					return null;
				}
			}
			else
			{
				// No symbol resolved for this primary node. As a fallback, check namedValues by text.
				LLVMValueRef alloca = namedValues.get(name);
				if (alloca != null)
				{
					// Heuristic: assume int32 if we can't find type info (but better to use semantic info)
					// Try to obtain the Type via semanticAnalyzer.getResolvedType(ctx)
					Optional<org.lokray.semantic.type.Type> typeOpt = semanticAnalyzer.getResolvedType(ctx);
					LLVMTypeRef llvmType = typeOpt.isPresent() ? TypeConverter.toLLVMType(typeOpt.get(), moduleContext) : LLVMInt32Type();
					return LLVMBuildLoad2(builder, llvmType, alloca, name + ".load");
				}
				Debug.logWarning("IR: No resolved symbol for primary '" + ctx.getText() + "' and not present in namedValues.");
				return null;
			}
		}

		// 4) other primary forms (this, null, new, etc.) - simple fallback for now
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
    private void pushScope() {
        scopedValues.push(new HashMap<>());
    }

    /**
     * Pops the most recent scope, removing all variables declared within it.
     */
    private void popScope() {
        if (!scopedValues.isEmpty()) {
            scopedValues.pop();
        }
    }

    /**
     * Adds a variable to the current (top) scope.
     */
    private void addVariableToScope(String name, LLVMValueRef value) {
        if (scopedValues.isEmpty()) {
            scopedValues.push(new HashMap<>());
        }
        scopedValues.peek().put(name, value);
    }

    /**
     * Looks up a variable across all active scopes, from innermost to outermost.
     */
    private LLVMValueRef lookupVariable(String name) {
        for (int i = scopedValues.size() - 1; i >= 0; i--) {
            Map<String, LLVMValueRef> scope = scopedValues.get(i);
            if (scope.containsKey(name)) {
                return scope.get(name);
            }
        }
        return namedValues.get(name); // fallback for global or top-level vars
    }
}