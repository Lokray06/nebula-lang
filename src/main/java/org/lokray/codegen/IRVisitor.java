// File: src/main/java/org/lokray/codegen/IRVisitor.java
package org.lokray.codegen;

import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
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

import java.util.*;

import static org.bytedeco.llvm.global.LLVM.*;

public class IRVisitor extends NebulaParserBaseVisitor<LLVMValueRef>
{

	private final SemanticAnalyzer semanticAnalyzer;
	private final LLVMContext llvmContext;
	// Scope management for local variables
	private final Stack<Map<String, LLVMValueRef>> scopedValues = new Stack<>();
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
		moduleContext = LLVMGetModuleContext(module);
		// Initialize global scope
		scopedValues.push(new HashMap<>());
	}

	// Helper to find variable allocation in current or outer scopes
	private LLVMValueRef findNamedValue(String name)
	{
		for (int i = scopedValues.size() - 1; i >= 0; i--)
		{
			if (scopedValues.get(i).containsKey(name))
			{
				return scopedValues.get(i).get(name);
			}
		}
		// Could also check for globals here if needed
		return null;
	}

	// Helper to add variable allocation to the current scope
	private void addNamedValue(String name, LLVMValueRef value)
	{
		if (!scopedValues.isEmpty())
		{
			scopedValues.peek().put(name, value);
		}
		else
		{
			Debug.logError("IR Error: Cannot add named value - scope stack is empty!");
		}
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
		// Handle global functions if necessary
		return methodSymbol.getName();
	}

	@Override
	public LLVMValueRef visitMethodDeclaration(NebulaParser.MethodDeclarationContext ctx)
	{
		String methodName = ctx.ID().getText();

		// 1. Check for the plain "main" function first
		if (methodName.equals("main"))
		{
			// Simple main function (e.g., int main()) - no mangling
			LLVMTypeRef mainRetType = LLVMInt32TypeInContext(moduleContext);
			LLVMTypeRef[] mainParamTypes = {}; // Assuming int main() signature
			LLVMTypeRef mainFuncType = LLVMFunctionType(mainRetType, new PointerPointer<>(mainParamTypes), 0, 0);

			// Try to get existing 'main' function
			LLVMValueRef mainFunction = LLVMGetNamedFunction(module, "main");
			if (mainFunction == null)
			{
				// Add 'main' function
				mainFunction = LLVMAddFunction(module, "main", mainFuncType);
			}
			else if (!LLVMTypeOf(mainFunction).equals(mainFuncType))
			{
				// Handle signature mismatch if 'main' was declared differently
				// In a real compiler, this is an error unless handling overloaded 'main' (uncommon/non-standard)
				Debug.logError("IR Error: main function signature mismatch or multiple declarations.");
				return null;
			}

			currentFunction = mainFunction;

			// Create entry block
			LLVMBasicBlockRef entryBlock = LLVMAppendBasicBlockInContext(moduleContext, mainFunction, "entry");
			LLVMPositionBuilderAtEnd(builder, entryBlock);

			// No parameters to allocate for simple 'int main()'

			scopedValues.push(new HashMap<>()); // Push scope for function body

			// Visit the function body
			visit(ctx.block());

			// --- Return Handling ---
			LLVMBasicBlockRef lastBlock = LLVMGetLastBasicBlock(mainFunction);
			boolean needsReturn = (LLVMGetBasicBlockTerminator(lastBlock) == null);

			if (needsReturn)
			{
				// Ensure main returns 0 by default if no explicit return
				LLVMBuildRet(builder, LLVMConstInt(LLVMInt32TypeInContext(moduleContext), 0, 0));
			}

			// --- Verification (Optional but Recommended) ---
			// LLVMVerifyFunction(mainFunction, LLVMPrintMessageAction);

			scopedValues.pop(); // Pop function scope
			currentFunction = null; // Reset current function context

			return mainFunction;
		}
		// End of main function specific handling
// -----------------------------------------------------------------------------

		// 2. Handle all other methods (potentially using mangled names)
		// Get the MethodSymbol from the semantic analyzer
		Optional<Symbol> methodSymbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
		if (methodSymbolOpt.isEmpty() || !(methodSymbolOpt.get() instanceof MethodSymbol))
		{
			Debug.logError("IR Error: Could not resolve MethodSymbol for: " + methodName);
			return null;
		}
		MethodSymbol methodSymbol = (MethodSymbol) methodSymbolOpt.get();

		String functionName = getMangledName(methodSymbol); // Use mangled name for non-main methods

		LLVMValueRef function = LLVMGetNamedFunction(module, functionName);
		if (function == null)
		{
			// Define the function if not already defined (e.g., could be declared by a call earlier)
			List<Type> paramTypes = methodSymbol.getParameterTypes();
			LLVMTypeRef[] llvmParamTypes = new LLVMTypeRef[paramTypes.size()];
			for (int i = 0; i < paramTypes.size(); i++)
			{
				llvmParamTypes[i] = TypeConverter.toLLVMType(paramTypes.get(i), moduleContext);
			}
			LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType(), moduleContext);
			LLVMTypeRef functionType = LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), paramTypes.size(), 0);
			function = LLVMAddFunction(module, functionName, functionType);
		}

		currentFunction = function;

		// Create entry block
		LLVMBasicBlockRef entryBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "entry");
		LLVMPositionBuilderAtEnd(builder, entryBlock);

		// --- Parameter Allocation ---
		scopedValues.push(new HashMap<>()); // Push scope for function body
		if (!methodSymbol.isStatic())
		{
			// Add 'this' parameter handling if needed
		}
		for (int i = 0; i < methodSymbol.getParameters().size(); i++)
		{
			ParameterSymbol paramSym = methodSymbol.getParameters().get(i);
			String paramName = paramSym.getName();
			LLVMValueRef funcParam = LLVMGetParam(function, i);
			LLVMSetValueName(funcParam, paramName); // Set name for easier IR readability

			// Allocate space for the parameter on the stack and store its value
			LLVMTypeRef paramLLVMType = TypeConverter.toLLVMType(paramSym.getType(), moduleContext);
			LLVMValueRef paramAlloca = createEntryBlockAlloca(function, paramLLVMType, paramName + ".addr");
			LLVMBuildStore(builder, funcParam, paramAlloca);
			addNamedValue(paramName, paramAlloca); // Add alloca to symbol map
		}


		// Visit the function body
		visit(ctx.block());

		// --- Return Handling ---
		LLVMBasicBlockRef lastBlock = LLVMGetLastBasicBlock(function);
		boolean needsReturn = (LLVMGetBasicBlockTerminator(lastBlock) == null);

		if (needsReturn)
		{
			if (methodSymbol.getType() == PrimitiveType.VOID)
			{
				LLVMBuildRetVoid(builder);
			}
			else
			{
				// Non-void function ended without return
				LLVMTypeRef retType = TypeConverter.toLLVMType(methodSymbol.getType(), moduleContext);
				LLVMBuildRet(builder, LLVMConstNull(retType)); // Return zero/null equivalent
				Debug.logWarning("IR Warning: Non-void function '" + functionName + "' might reach end without return. Added default return.");
			}
		}

		// --- Verification (Optional but Recommended) ---
		// LLVMVerifyFunction(function, LLVMPrintMessageAction);

		scopedValues.pop(); // Pop function scope
		currentFunction = null; // Reset current function context

		return function; // Return the function reference
	}

	@Override
	public LLVMValueRef visitStatementExpression(NebulaParser.StatementExpressionContext ctx)
	{
		Debug.logDebug("IR: Processing statement expression: " + ctx.getText());

		// Case 1: Assignment (handled by visitActualAssignment via visitChildren)
		if (ctx.actualAssignment() != null)
		{
			return visitChildren(ctx);
		}

		// Case 2: Pre/Post Increment/Decrement or Method Call
		if (ctx.postfixExpression() != null)
		{
			// Check if it's a method call like foo()
			if (ctx.getChildCount() >= 2 && ctx.getChild(1).getText().equals("("))
			{
				Optional<Symbol> symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
				if (symbolOpt.isPresent() && symbolOpt.get() instanceof MethodSymbol methodSymbol)
				{
					// --- Method Call ---
					String mangledName = getMangledName(methodSymbol);
					LLVMValueRef function = LLVMGetNamedFunction(module, mangledName);
					if (function == null)
					{
						// Create function prototype if not found
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

					List<LLVMValueRef> args = new ArrayList<>();
					if (ctx.argumentList() != null)
					{
						for (NebulaParser.ExpressionContext exprCtx : ctx.argumentList().expression())
						{
							LLVMValueRef argVal = visit(exprCtx);
							if (argVal == null)
							{
								Debug.logError("IR Error: Failed to generate argument value for call to " + mangledName);
								return null; // Error generating argument
							}
							args.add(argVal);
						}
					}

					PointerPointer<LLVMValueRef> argsPtr = new PointerPointer<>(args.size());
					for (int i = 0; i < args.size(); i++)
					{
						argsPtr.put(i, args.get(i));
					}

					LLVMTypeRef funcType = safeGetFunctionType(function, methodSymbol);
					String callName = (methodSymbol.getType() == PrimitiveType.VOID) ? "" : "calltmp";
					return LLVMBuildCall2(builder, funcType, function, argsPtr, args.size(), callName);
				}
				else
				{
					Debug.logError("IR Error: Could not resolve method symbol for statement call: " + ctx.getText());
					return null;
				}
			}
			// Check if it's postfix increment/decrement like a++ or a--
			else if (ctx.getChildCount() >= 2 && (ctx.getChild(1).getText().equals("++") || ctx.getChild(1).getText().equals("--")))
			{
				LLVMValueRef targetAlloca = getLValue(ctx.postfixExpression().primary()); // Helper to get the alloca
				if (targetAlloca == null)
				{
					return null;
				}

				Optional<Type> typeOpt = semanticAnalyzer.getResolvedType(ctx.postfixExpression().primary());
				if (typeOpt.isEmpty() || !typeOpt.get().isNumeric())
				{
					Debug.logError("IR Error: Cannot inc/dec non-numeric type: " + ctx.postfixExpression().primary().getText());
					return null;
				}
				LLVMTypeRef type = LLVMTypeOf(targetAlloca); // Get type from alloca

				LLVMValueRef currentValue = LLVMBuildLoad2(builder, type, targetAlloca, "posttmp");
				LLVMValueRef one = LLVMConstInt(type, 1, 0);
				LLVMValueRef newValue;
				boolean isInc = ctx.getChild(1).getText().equals("++");

				if (LLVMGetTypeKind(type) == LLVMFloatTypeKind || LLVMGetTypeKind(type) == LLVMDoubleTypeKind)
				{
					newValue = isInc ? LLVMBuildFAdd(builder, currentValue, one, "faddtmp")
							: LLVMBuildFSub(builder, currentValue, one, "fsubtmp");
				}
				else
				{ // Integer type
					newValue = isInc ? LLVMBuildAdd(builder, currentValue, one, "addtmp")
							: LLVMBuildSub(builder, currentValue, one, "subtmp");
				}
				LLVMBuildStore(builder, newValue, targetAlloca);
				// Postfix returns the *original* value, but in a statement, it's unused.
				return currentValue; // Or just return null if result isn't needed.
			}
		}

		// Case 3: Prefix Increment/Decrement ++a or --a
		if (ctx.unaryExpression() != null && ctx.op != null)
		{
			LLVMValueRef targetAlloca = getLValue(ctx.unaryExpression()); // Helper needed
			if (targetAlloca == null)
			{
				return null;
			}

			Optional<Type> typeOpt = semanticAnalyzer.getResolvedType(ctx.unaryExpression());
			if (typeOpt.isEmpty() || !typeOpt.get().isNumeric())
			{
				Debug.logError("IR Error: Cannot inc/dec non-numeric type: " + ctx.unaryExpression().getText());
				return null;
			}
			LLVMTypeRef type = LLVMTypeOf(targetAlloca);

			LLVMValueRef currentValue = LLVMBuildLoad2(builder, type, targetAlloca, "pretmp");
			LLVMValueRef one = LLVMConstInt(type, 1, 0);
			LLVMValueRef newValue;
			boolean isInc = ctx.op.getType() == NebulaParser.INC_OP;

			if (LLVMGetTypeKind(type) == LLVMFloatTypeKind || LLVMGetTypeKind(type) == LLVMDoubleTypeKind)
			{
				newValue = isInc ? LLVMBuildFAdd(builder, currentValue, one, "faddtmp")
						: LLVMBuildFSub(builder, currentValue, one, "fsubtmp");
			}
			else
			{
				newValue = isInc ? LLVMBuildAdd(builder, currentValue, one, "addtmp")
						: LLVMBuildSub(builder, currentValue, one, "subtmp");
			}
			LLVMBuildStore(builder, newValue, targetAlloca);
			// Prefix returns the *new* value, but in a statement, it's unused.
			return newValue; // Or just return null if result isn't needed.
		}

		// Fallback if none of the above match (should ideally not happen for valid statement expressions)
		Debug.logWarning("IR: Unhandled statement expression type: " + ctx.getText());
		return visitChildren(ctx);
	}


	// New method to implement variable declaration IR generation
	@Override
	public LLVMValueRef visitVariableDeclaration(NebulaParser.VariableDeclarationContext ctx)
	{
		System.out.println("================================================ VISITNG VARIABLE DECLARATION IN THE IR VISITOR");

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
				if (initVal == null)
				{
					Debug.logError("IR Error: Failed to generate initializer for variable: " + varName);
					// Decide whether to continue or stop
					continue; // Skip storing for this var
				}
				// TODO: Add type checking/casting if initializer type doesn't match varType exactly
				LLVMBuildStore(builder, initVal, varAlloca);
			}
			// else: Variable is declared but not initialized (LLVM alloca defaults to undef/garbage)

			// Add the allocation to the current scope map
			addNamedValue(varName, varAlloca);
		}
		return null; // Variable declaration statement doesn't produce a value
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
			Type loopVarNebulaType = loopVarSymbol.getType(); // Use Nebula type for signedness check
			LLVMTypeRef varType = TypeConverter.toLLVMType(loopVarNebulaType, moduleContext);
			LLVMValueRef function = currentFunction;

			LLVMValueRef startVal;
			if (info.startExpression() != null)
			{
				startVal = visit(info.startExpression());
			}
			else
			{
				startVal = LLVMConstInt(varType, 0, 0); // Implicit start at 0
			}
			if (startVal == null)
			{
				return null; // Error visiting start expr
			}

			LLVMValueRef varAlloca = createEntryBlockAlloca(function, varType, varName);
			LLVMBuildStore(builder, startVal, varAlloca);

			// --- Scope Management for Loop Variable ---
			scopedValues.push(new HashMap<>()); // Push loop scope
			addNamedValue(varName, varAlloca); // Add loop var to current (loop) scope


			// --- Create Blocks ---
			LLVMBasicBlockRef loopHeaderBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "loop.header");
			LLVMBasicBlockRef loopBodyBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "loop.body");
			LLVMBasicBlockRef loopExitBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "loop.exit");

			LLVMBuildBr(builder, loopHeaderBlock);

			// --- Populate Header ---
			LLVMPositionBuilderAtEnd(builder, loopHeaderBlock);
			LLVMValueRef currentVal = LLVMBuildLoad2(builder, varType, varAlloca, varName + ".load");
			LLVMValueRef limitVal = visit(info.limitExpression());
			if (limitVal == null)
			{
				scopedValues.pop();
				return null;
			} // Error, restore scope

			// --- Comparison ---
			int llvmPredicate;
			boolean isSigned = !loopVarNebulaType.getName().startsWith("u"); // Determine signedness
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
				default:
					scopedValues.pop();
					return null; // Error, restore scope
			}
			LLVMValueRef condition = LLVMBuildICmp(builder, llvmPredicate, currentVal, limitVal, "loop.cond");
			LLVMBuildCondBr(builder, condition, loopBodyBlock, loopExitBlock);

			// --- Populate Body ---
			LLVMPositionBuilderAtEnd(builder, loopBodyBlock);
			visit(ctx.block()); // Visit the loop body block

			// --- Increment or Decrement (if block doesn't terminate itself) ---
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{
				LLVMValueRef currentValForIncDec = LLVMBuildLoad2(builder, varType, varAlloca, varName + ".load.incdec");
				long stepSign = (operator.equals("<") || operator.equals("<=")) ? +1 : -1;
				LLVMValueRef stepVal = LLVMConstInt(varType, 1, 0); // Step is always 1

				LLVMValueRef nextVal = (stepSign > 0) ?
						LLVMBuildAdd(builder, currentValForIncDec, stepVal, varName + ".inc") :
						LLVMBuildSub(builder, currentValForIncDec, stepVal, varName + ".dec");

				LLVMBuildStore(builder, nextVal, varAlloca);
				LLVMBuildBr(builder, loopHeaderBlock); // Jump back to header
			}

			// --- Exit Block ---
			LLVMPositionBuilderAtEnd(builder, loopExitBlock);
			scopedValues.pop(); // Pop loop scope, removing loop variable

			Debug.logDebug("Codegen: Finished simplified for loop (using Semantic Info): " + ctx.getText());
			return null;

		}
		else if (loopInfo instanceof TraditionalForInfo info)
		{
			// --- TRADITIONAL FOR LOOP ---
			Debug.logDebug("Codegen: Traditional for loop (using Semantic Info): " + ctx.getText());
			LLVMValueRef function = currentFunction;

			scopedValues.push(new HashMap<>()); // Push loop scope (for init vars)

			// --- 1. Initializer ---
			if (info.initializer() != null)
			{
				if (info.initializer() instanceof NebulaParser.VariableDeclarationContext varDeclCtx)
				{
					// visitVariableDeclarationForLoopInit will add allocas to the *current* (loop) scope
					visitVariableDeclarationForLoopInit(varDeclCtx);
				}
				else if (info.initializer() instanceof NebulaParser.ExpressionContext exprCtx)
				{
					visit(exprCtx); // Execute initializer expression
				}
			}

			// --- 2. Create Blocks ---
			LLVMBasicBlockRef loopHeaderBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.cond");
			LLVMBasicBlockRef loopBodyBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.body");
			LLVMBasicBlockRef loopUpdateBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.update");
			LLVMBasicBlockRef loopExitBlock = LLVMAppendBasicBlockInContext(moduleContext, function, "for.exit");

			LLVMBuildBr(builder, loopHeaderBlock); // Branch to Header

			// --- 4. Populate Header Block ---
			LLVMPositionBuilderAtEnd(builder, loopHeaderBlock);
			LLVMValueRef conditionValue;
			if (info.condition() != null)
			{
				conditionValue = visit(info.condition());
				if (conditionValue == null)
				{
					scopedValues.pop();
					return null;
				} // Error, restore scope
				Optional<Type> condNebulaTypeOpt = semanticAnalyzer.getResolvedType(info.condition());

				// Ensure condition is i1
				if (condNebulaTypeOpt.isEmpty() || condNebulaTypeOpt.get() != PrimitiveType.BOOLEAN)
				{
					LLVMTypeRef condLLVMType = LLVMTypeOf(conditionValue);
					if (LLVMGetTypeKind(condLLVMType) == LLVMIntegerTypeKind)
					{
						LLVMValueRef zero = LLVMConstNull(condLLVMType);
						conditionValue = LLVMBuildICmp(builder, LLVMIntNE, conditionValue, zero, "tobool");
					}
					else if (LLVMGetTypeKind(condLLVMType) == LLVMFloatTypeKind || LLVMGetTypeKind(condLLVMType) == LLVMDoubleTypeKind)
					{
						LLVMValueRef zero = LLVMConstNull(condLLVMType);
						conditionValue = LLVMBuildFCmp(builder, LLVMRealONE, conditionValue, zero, "tobool_fp");
					}
					else
					{ // Assume pointer or other type, compare against null
						LLVMValueRef nullPtr = LLVMConstNull(condLLVMType); // Get appropriate null for the type
						conditionValue = LLVMBuildIsNotNull(builder, conditionValue, "tobool_ptr"); // More robust check
					}
					Debug.logWarning("IR Warning: Condition in traditional for loop isn't bool. Using comparison.");
				}
			}
			else
			{
				conditionValue = LLVMConstInt(LLVMInt1TypeInContext(moduleContext), 1, 0); // Default to true if no condition
			}
			LLVMBuildCondBr(builder, conditionValue, loopBodyBlock, loopExitBlock); // Branch

			// --- 5. Populate Body Block ---
			LLVMPositionBuilderAtEnd(builder, loopBodyBlock);
			visit(ctx.block()); // Visit loop body
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{ // Branch to update if not terminated
				LLVMBuildBr(builder, loopUpdateBlock);
			}

			// --- 6. Populate Update Block ---
			LLVMPositionBuilderAtEnd(builder, loopUpdateBlock);
			if (info.update() != null)
			{
				visit(info.update()); // Generate update code from info
			}
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) == null)
			{ // Branch to header if not terminated
				LLVMBuildBr(builder, loopHeaderBlock);
			}

			// --- 7. Position Builder at Exit Block ---
			LLVMPositionBuilderAtEnd(builder, loopExitBlock);
			scopedValues.pop(); // Pop loop scope
			Debug.logDebug("Codegen: Finished traditional for loop (using Semantic Info): " + ctx.getText());
			return null;
		}
		else
		{
			Debug.logError("Codegen Error: Unknown loop info type for ForStatementContext: " + ctx.getText());
			return null;
		}
	}


	// Helper for Traditional For Loop Initializer
	private void visitVariableDeclarationForLoopInit(NebulaParser.VariableDeclarationContext ctx)
	{
		Optional<Type> nebulaTypeOpt = semanticAnalyzer.getResolvedType(ctx.type());
		if (nebulaTypeOpt.isEmpty())
		{ /* ... error handling ... */
			return;
		}
		LLVMTypeRef varType = TypeConverter.toLLVMType(nebulaTypeOpt.get(), moduleContext);

		for (NebulaParser.VariableDeclaratorContext declarator : ctx.variableDeclarator())
		{
			String varName = declarator.ID().getText();
			// Use createEntryBlockAlloca for loop init vars
			LLVMValueRef varAlloca = createEntryBlockAlloca(currentFunction, varType, varName);

			if (declarator.expression() != null)
			{
				LLVMValueRef initVal = visit(declarator.expression());
				if (initVal != null)
				{
					LLVMBuildStore(builder, initVal, varAlloca);
				}
				else
				{ /* ... error handling ... */ }
			}
			// Add to *current* scope (which should be the loop's scope)
			addNamedValue(varName, varAlloca);
		}
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
	public LLVMValueRef visitUnaryExpression(NebulaParser.UnaryExpressionContext ctx)
	{
		// Handle prefix operators first
		if (ctx.op != null)
		{
			LLVMValueRef operand = visit(ctx.unaryExpression()); // Visit the expression being operated on
			if (operand == null)
			{
				return null;
			}

			switch (ctx.op.getType())
			{
				case NebulaParser.ADD_OP: // Unary plus (usually a no-op for numerics)
					return operand; // Or potentially check if it's numeric
				case NebulaParser.SUB_OP: // Unary minus
					Optional<Type> typeOpt = semanticAnalyzer.getResolvedType(ctx.unaryExpression());
					if (typeOpt.isPresent() && typeOpt.get().isNumeric())
					{
						LLVMTypeRef type = LLVMTypeOf(operand);
						if (LLVMGetTypeKind(type) == LLVMFloatTypeKind || LLVMGetTypeKind(type) == LLVMDoubleTypeKind)
						{
							return LLVMBuildFNeg(builder, operand, "fnegtmp");
						}
						else
						{ // Integer type
							return LLVMBuildNeg(builder, operand, "negtmp");
						}
					}
					else
					{
						Debug.logError("IR Error: Unary minus applied to non-numeric type: " + ctx.unaryExpression().getText());
						return null;
					}
				case NebulaParser.LOG_NOT_OP: // Logical not !
					// Ensure operand is boolean (i1) or convert it
					LLVMValueRef boolOperand = ensureBoolean(operand, ctx.unaryExpression());
					if (boolOperand == null)
					{
						return null;
					}
					// Logical not is XOR with true (1)
					return LLVMBuildXor(builder, boolOperand, LLVMConstInt(LLVMInt1Type(), 1, 0), "lognot");
				case NebulaParser.BIT_NOT_OP: // Bitwise not ~
					Optional<Type> typeOptBitNot = semanticAnalyzer.getResolvedType(ctx.unaryExpression());
					if (typeOptBitNot.isPresent() && typeOptBitNot.get().isInteger())
					{
						// Bitwise not is XOR with -1 (all bits set)
						LLVMTypeRef type = LLVMTypeOf(operand);
						LLVMValueRef allOnes = LLVMConstAllOnes(type); // Get -1 for the specific integer type
						return LLVMBuildXor(builder, operand, allOnes, "bitnot");
					}
					else
					{
						Debug.logError("IR Error: Bitwise not applied to non-integer type: " + ctx.unaryExpression().getText());
						return null;
					}

				case NebulaParser.INC_OP: // Prefix ++ (handled in statementExpression for standalone)
				case NebulaParser.DEC_OP: // Prefix -- (handled in statementExpression for standalone)
					// If used within a larger expression, need to load, inc/dec, store, and return NEW value
					LLVMValueRef targetAllocaPre = getLValue(ctx.unaryExpression());
					if (targetAllocaPre == null)
					{
						return null;
					}
					LLVMTypeRef typePre = LLVMTypeOf(targetAllocaPre);
					LLVMValueRef currentValPre = LLVMBuildLoad2(builder, typePre, targetAllocaPre, "pretmp");
					LLVMValueRef onePre = LLVMConstInt(typePre, 1, 0);
					LLVMValueRef newValPre;
					boolean isIncPre = ctx.op.getType() == NebulaParser.INC_OP;
					if (LLVMGetTypeKind(typePre) == LLVMFloatTypeKind || LLVMGetTypeKind(typePre) == LLVMDoubleTypeKind)
					{
						newValPre = isIncPre ? LLVMBuildFAdd(builder, currentValPre, onePre, "faddtmp")
								: LLVMBuildFSub(builder, currentValPre, onePre, "fsubtmp");
					}
					else
					{
						newValPre = isIncPre ? LLVMBuildAdd(builder, currentValPre, onePre, "addtmp")
								: LLVMBuildSub(builder, currentValPre, onePre, "subtmp");
					}
					LLVMBuildStore(builder, newValPre, targetAllocaPre);
					return newValPre; // Prefix returns the *new* value

				default:
					Debug.logError("IR Error: Unknown unary operator: " + ctx.op.getText());
					return null;
			}
		}

		// If no prefix operator, visit the child (cast or postfix)
		return visitChildren(ctx);
	}


	@Override
	public LLVMValueRef visitPrimary(NebulaParser.PrimaryContext ctx)
	{
		// 1) Literal
		if (ctx.literal() != null)
		{
			return visit(ctx.literal());
		}

		// 2) Parenthesized expression
		if (ctx.expression() != null)
		{
			return visit(ctx.expression());
		}

		// 3) Identifier usage (load variable)
		if (ctx.ID() != null)
		{
			String name = ctx.ID().getText();
			LLVMValueRef alloca = findNamedValue(name); // Use helper to search scopes

			if (alloca != null)
			{
				// Determine the type to load. Should come from the alloca's element type.
				LLVMTypeRef pointerType = LLVMTypeOf(alloca);
				if (LLVMGetTypeKind(pointerType) != LLVMPointerTypeKind)
				{
					Debug.logError("IR Error: Expected pointer type for alloca of '" + name + "', but got something else.");
					return null;
				}
				LLVMTypeRef varType = LLVMGetElementType(pointerType); // Get the type the pointer points to

				return LLVMBuildLoad2(builder, varType, alloca, name + ".load");
			}
			else
			{
				// Could be a global or function name - handle later if needed
				Debug.logWarning("IR Warning: Identifier '" + name + "' not found in local scopes/allocas.");
				// Attempt to find as a function (might be needed for function pointers later)
				LLVMValueRef func = LLVMGetNamedFunction(module, name);
				if (func != null)
				{
					return func; // Return function reference itself
				}
				return null; // Variable not found
			}
		}

		// 4) 'new' expression (more complex, involves memory allocation - skip for now)
		if (ctx.NEW_KW() != null)
		{
			Debug.logWarning("IR: 'new' keyword generation not implemented yet.");
			return null;
		}

		// 5) Array Initializer / Tuple Literal (might need specific handling if used as values)
		if (ctx.arrayInitializer() != null)
		{
			Debug.logWarning("IR: Array initializer expression generation not implemented yet.");
			return null;
		}
		if (ctx.tupleLiteral() != null)
		{
			Debug.logWarning("IR: Tuple literal expression generation not implemented yet.");
			return null;
		}

		// 6) Primitive type used as primary (e.g., int.MaxValue) - handle if needed for static access
		if (ctx.primitiveType() != null)
		{
			Debug.logWarning("IR: Static access on primitive types (e.g., int.MaxValue) not implemented yet.");
			return null; // Placeholder
		}


		// Fallback
		return visitChildren(ctx);
	}

	// Helper to get the LLVMValueRef representing the memory location (alloca) of an LValue expression
	// Needed for assignments, increments, decrements.
	private LLVMValueRef getLValue(ParseTree ctx)
	{
		if (ctx instanceof NebulaParser.PrimaryContext primaryCtx && primaryCtx.ID() != null)
		{
			String name = primaryCtx.ID().getText();
			LLVMValueRef alloca = findNamedValue(name);
			if (alloca == null)
			{
				Debug.logError("IR Error: Cannot assign to undeclared variable '" + name + "'");
			}
			// Check if it's actually an alloca (pointer)
			if (alloca != null && LLVMGetTypeKind(LLVMTypeOf(alloca)) == LLVMPointerTypeKind)
			{
				return alloca;
			}
			else if (alloca != null)
			{
				Debug.logError("IR Error: Target for assignment/inc/dec '" + name + "' is not a memory location (alloca).");
			}
			return null;
		}
		// TODO: Handle field access (e.g., obj.field) and array access (e.g., arr[index])
		// These would involve GEP (GetElementPtr) instructions.
		Debug.logError("IR Error: Unsupported LValue expression for assignment/inc/dec: " + ctx.getText());
		return null;
	}

	// Helper to convert a value to boolean (i1) if necessary
	private LLVMValueRef ensureBoolean(LLVMValueRef value, ParseTree contextForTypeError)
	{
		if (value == null)
		{
			return null;
		}
		LLVMTypeRef type = LLVMTypeOf(value);
		if (LLVMGetTypeKind(type) == LLVMIntegerTypeKind && LLVMGetIntTypeWidth(type) == 1)
		{
			return value; // Already i1
		}
		if (LLVMGetTypeKind(type) == LLVMIntegerTypeKind)
		{
			LLVMValueRef zero = LLVMConstNull(type);
			return LLVMBuildICmp(builder, LLVMIntNE, value, zero, "tobool");
		}
		if (LLVMGetTypeKind(type) == LLVMFloatTypeKind || LLVMGetTypeKind(type) == LLVMDoubleTypeKind)
		{
			LLVMValueRef zero = LLVMConstNull(type);
			return LLVMBuildFCmp(builder, LLVMRealONE, value, zero, "tobool_fp"); // O!= Ordered not equal
		}
		if (LLVMGetTypeKind(type) == LLVMPointerTypeKind)
		{
			LLVMValueRef nullPtr = LLVMConstNull(type);
			return LLVMBuildIsNotNull(builder, value, "tobool_ptr");
		}

		Debug.logError("IR Error: Cannot convert type " + LLVMPrintTypeToString(type).getString() + " to boolean for context: " + contextForTypeError.getText());
		return null;
	}


	// Allocates memory in the entry block of the function.
	private LLVMValueRef createEntryBlockAlloca(LLVMValueRef function, LLVMTypeRef type, String varName)
	{
		LLVMBasicBlockRef entryBlock = LLVMGetEntryBasicBlock(function);
		if (entryBlock == null)
		{
			// Should not happen if entry block is created in visitMethodDeclaration
			Debug.logError("IR Error: Entry block not found for function!");
			return null;
		}

		LLVMBuilderRef tmpBuilder = LLVMCreateBuilderInContext(moduleContext);

		// Find the first non-alloca instruction in the entry block
		LLVMValueRef firstInstruction = LLVMGetFirstInstruction(entryBlock);
		LLVMValueRef insertBefore = firstInstruction;
		while (insertBefore != null && LLVMIsAAllocaInst(insertBefore) != null)
		{
			insertBefore = LLVMGetNextInstruction(insertBefore);
		}

		// Position the temporary builder
		if (insertBefore != null)
		{
			LLVMPositionBuilderBefore(tmpBuilder, insertBefore);
		}
		else
		{
			// If no non-alloca instructions (or empty block), insert at the end
			LLVMPositionBuilderAtEnd(tmpBuilder, entryBlock);
		}

		// Build the alloca instruction
		LLVMValueRef alloca = LLVMBuildAlloca(tmpBuilder, type, varName);

		LLVMDisposeBuilder(tmpBuilder); // Clean up the temporary builder
		return alloca;
	}

	// Overload for convenience when name is directly available
	private LLVMValueRef createEntryBlockAlloca(LLVMValueRef func, String name, LLVMTypeRef type)
	{
		return createEntryBlockAlloca(func, type, name);
	}

	// (Keep canonicalTypeName and safeGetFunctionType helpers as they are)
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
		if (n.equals("uint") || n.contains("uint32"))
		{
			return "uint"; // Add canonical name for uint
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
		// Add other unsigned types if needed for mangling
		if (n.equals("ulong") || n.contains("uint64"))
		{
			return "ulong";
		}
		if (n.equals("ushort") || n.contains("uint16"))
		{
			return "ushort";
		}
		if (n.equals("ubyte") || n.contains("uint8"))
		{
			return "ubyte";
		}
		return n.replace('.', '_');
	}

	private LLVMTypeRef safeGetFunctionType(LLVMValueRef func, MethodSymbol methodSymbol)
	{
		// LLVMTypeOf on a function value returns a pointer TO the function type
		LLVMTypeRef type = LLVMTypeOf(func);
		if (LLVMGetTypeKind(type) == LLVMPointerTypeKind)
		{
			LLVMTypeRef elementType = LLVMGetElementType(type);
			if (LLVMGetTypeKind(elementType) == LLVMFunctionTypeKind)
			{
				return elementType; // Return the actual function type
			}
		}

		// Fallback: Reconstruct the type from MethodSymbol if needed (shouldn't be necessary if func is valid)
		Debug.logWarning("IR Warning: Could not directly get function type for " + methodSymbol.getName() + ". Reconstructing.");
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
		scopedValues.push(new HashMap<>()); // Enter new scope
		for (NebulaParser.StatementContext stmtCtx : ctx.statement())
		{
			visit(stmtCtx);
			// Stop processing statements in this block if a terminator was generated (e.g., return, break, continue)
			if (LLVMGetBasicBlockTerminator(LLVMGetInsertBlock(builder)) != null)
			{
				break;
			}
		}
		scopedValues.pop(); // Exit scope
		return null; // Block itself doesn't produce a value
	}

	@Override
	public LLVMValueRef visitStatement(NebulaParser.StatementContext ctx)
	{
		visitChildren(ctx); // Delegate to specific statement visitors
		return null;
	}

	// visitPostfixExpression might need refinement if used within expressions,
	// but for statementExpression, the specific cases (call, inc/dec) are handled there.
	@Override
	public LLVMValueRef visitPostfixExpression(NebulaParser.PostfixExpressionContext ctx)
	{
		LLVMValueRef base = visit(ctx.primary());
		if (base == null)
		{
			return null;
		}

		LLVMValueRef currentValue = base; // Start with the primary value

		// Iterate through postfix operations (. , [], (), ++, --)
		for (int i = 1; i < ctx.getChildCount(); i++)
		{
			ParseTree op = ctx.getChild(i);
			String opText = op.getText();

			if (opText.equals("."))
			{
				if (i + 1 < ctx.getChildCount() && ctx.getChild(i + 1) instanceof TerminalNode idNode)
				{
					String memberName = idNode.getText();
					// Need type information to generate GEP for field access
					// Optional<Type> baseTypeOpt = semanticAnalyzer.getResolvedType(ctx.getChild(i - 1)); // Type of the thing before '.'
					Debug.logWarning("IR: Field/Property access (obj." + memberName + ") generation not implemented yet.");
					// Generate GEP instruction here based on struct/class layout
					currentValue = null; // Placeholder
					i++; // Consume the ID
					if (currentValue == null)
					{
						return null;
					}
				}
				else
				{
					Debug.logError("IR Error: Expected identifier after '.'");
					return null;
				}
			}
			else if (opText.equals("["))
			{
				if (i + 2 < ctx.getChildCount() && ctx.getChild(i + 1) instanceof NebulaParser.ExpressionContext indexExpr)
				{
					LLVMValueRef indexValue = visit(indexExpr);
					if (indexValue == null)
					{
						return null;
					}
					// Need type information (is it an array?) to generate GEP
					Debug.logWarning("IR: Array element access (arr[idx]) generation not implemented yet.");
					currentValue = null; // Placeholder: GEP for array element
					i += 2; // Consume expression and ']'
					if (currentValue == null)
					{
						return null;
					}
				}
				else
				{
					Debug.logError("IR Error: Expected expression and ']' after '['");
					return null;
				}
			}
			else if (opText.equals("("))
			{
				// Method call - This is complex because 'currentValue' might be the function pointer
				// or it might be an object instance for an instance method call.
				// This logic is mostly handled in visitStatementExpression for standalones.
				// If used *within* another expression, need full call generation here.
				Debug.logWarning("IR: Method call within expression (obj.method()) generation not fully implemented in visitPostfixExpression.");
				// Need to resolve method, generate call, update currentValue with result
				currentValue = null; // Placeholder
				i++; // Consume '('
				if (i < ctx.getChildCount() && ctx.getChild(i) instanceof NebulaParser.ArgumentListContext)
				{
					i++; // Consume argument list
				}
				if (i < ctx.getChildCount() && ctx.getChild(i).getText().equals(")"))
				{
					// i++; // Consume ')' - handled by loop increment
				}
				else
				{
					Debug.logError("IR Error: Expected ')' after method call arguments.");
					return null;
				}
				if (currentValue == null)
				{
					return null;
				}

			}
			else if (opText.equals("++") || opText.equals("--"))
			{
				// Postfix inc/dec USED AS VALUE
				LLVMValueRef lValue = getLValue(ctx.getChild(0)); // Get address of the primary base
				if (lValue == null)
				{
					return null;
				}
				LLVMTypeRef type = LLVMTypeOf(lValue);
				LLVMValueRef originalValue = LLVMBuildLoad2(builder, type, lValue, "postincdec.orig");
				LLVMValueRef one = LLVMConstInt(type, 1, 0);
				LLVMValueRef newValue;
				boolean isInc = opText.equals("++");

				if (LLVMGetTypeKind(type) == LLVMFloatTypeKind || LLVMGetTypeKind(type) == LLVMDoubleTypeKind)
				{
					newValue = isInc ? LLVMBuildFAdd(builder, originalValue, one, "faddtmp")
							: LLVMBuildFSub(builder, originalValue, one, "fsubtmp");
				}
				else
				{
					newValue = isInc ? LLVMBuildAdd(builder, originalValue, one, "addtmp")
							: LLVMBuildSub(builder, originalValue, one, "subtmp");
				}
				LLVMBuildStore(builder, newValue, lValue);
				currentValue = originalValue; // Postfix returns the ORIGINAL value
			}
			else
			{
				Debug.logError("IR Error: Unexpected token in postfix expression: " + opText);
				return null;
			}
		}

		return currentValue;
	}


} // End of IRVisitor class