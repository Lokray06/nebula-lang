// File: src/main/java/org/lokray/codegen/IRVisitor.java
package org.lokray.codegen;

import org.antlr.v4.runtime.tree.ParseTree;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.llvm.LLVM.*;
import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.semantic.SemanticAnalyzer;
import org.lokray.semantic.symbol.ClassSymbol;
import org.lokray.semantic.symbol.MethodSymbol;
import org.lokray.semantic.symbol.NamespaceSymbol;
import org.lokray.semantic.symbol.Scope;
import org.lokray.semantic.type.Type;
import org.lokray.util.Debug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.bytedeco.llvm.global.LLVM.*;

public class IRVisitor extends NebulaParserBaseVisitor<LLVMValueRef>
{

	private final SemanticAnalyzer semanticAnalyzer;
	private final LLVMContext llvmContext;
	private final Map<String, LLVMValueRef> namedValues = new HashMap<>();
	private LLVMValueRef currentFunction;
	private final LLVMBuilderRef builder;
	private final LLVMModuleRef module;

	public IRVisitor(SemanticAnalyzer semanticAnalyzer, LLVMContext llvmContext)
	{
		this.semanticAnalyzer = semanticAnalyzer;
		this.llvmContext = llvmContext;
		builder = llvmContext.getBuilder();
		module = llvmContext.getModule();
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
		// The expression part of the statement, e.g., 'Console.println'
		ParseTree methodCallExpr = ctx.getChild(0);

		Debug.logWarning("IR: Processing statement expression: " + ctx.getText());

		// --- Attempt 1: Check the Method Identifier Token ---
		// The method ID is the second-to-last child of the postfixExpression context
		// for a member access (e.g., Console . println)
		ParseTree identifierNode = null;

		if (methodCallExpr instanceof NebulaParser.PostfixExpressionContext)
		{
			NebulaParser.PostfixExpressionContext pCtx = (NebulaParser.PostfixExpressionContext) methodCallExpr;
			// In the structure (postfixExpression (primary X) . ID), the ID is usually the 3rd child (index 2)
			if (pCtx.getChildCount() >= 3 && pCtx.getChild(1).getText().equals("."))
			{
				identifierNode = pCtx.getChild(2);
			}
			else
			{
				// Fallback for simple identifier calls:
				identifierNode = pCtx.getChild(0);
			}
		}

		// Check the determined identifier node first
		Optional<org.lokray.semantic.symbol.Symbol> symbolOpt = Optional.empty();
		if (identifierNode != null)
		{
			Debug.logWarning("IR: Attempt 1: Checking Method Identifier Token: (" + identifierNode.getText() + ").");
			symbolOpt = semanticAnalyzer.getResolvedSymbol(identifierNode);
			if (symbolOpt.isPresent())
			{
				Debug.logWarning("IR: Attempt 1 SUCCEEDED. Method symbol found on Identifier Node.");
			}
			else
			{
				Debug.logWarning("IR: Attempt 1 FAILED.");
			}
		}

		// --- Attempt 2: Check the PostfixExpression context (methodCallExpr) ---
		if (symbolOpt.isEmpty())
		{
			Debug.logWarning("IR: Attempt 2: Checking PostfixExpression context (" + methodCallExpr.getText() + ").");
			symbolOpt = semanticAnalyzer.getResolvedSymbol(methodCallExpr);
			if (symbolOpt.isPresent())
			{
				Debug.logWarning("IR: Attempt 2 SUCCEEDED. Method symbol found on PostfixExpression.");
			}
			else
			{
				Debug.logWarning("IR: Attempt 2 FAILED.");
			}
		}

		// --- Attempt 3: Check the entire StatementExpression context (ctx) ---
		if (symbolOpt.isEmpty())
		{
			Debug.logWarning("IR: Attempt 3: Checking StatementExpression context (" + ctx.getText() + ").");
			symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);
			if (symbolOpt.isPresent())
			{
				Debug.logWarning("IR: Attempt 3 SUCCEEDED. Method symbol found on StatementExpression.");
			}
			else
			{
				Debug.logWarning("IR: Attempt 3 FAILED. Method symbol NOT FOUND on any standard context.");
			}
		}


		if (symbolOpt.isPresent() && symbolOpt.get() instanceof MethodSymbol methodSymbol)
		{
			// --- Symbol Found: Proceed with Call Generation ---
			Debug.logWarning("IR: RESOLUTION SUCCESSFUL. Proceeding with code generation for method: " + methodSymbol.getName());

			String mangledName = getMangledName(methodSymbol);
			Debug.logWarning("IR: Generated mangled name: " + mangledName);

			// 1. Function Prototype/Definition Lookup
			LLVMValueRef function = LLVMGetNamedFunction(module, mangledName);
			if (function == null)
			{
				Debug.logWarning("IR: Function prototype not found. Creating LLVM declaration for: " + mangledName);
				// If not declared, create the function prototype.
				List<Type> paramTypes = methodSymbol.getParameterTypes();
				LLVMTypeRef[] llvmParamTypes = new LLVMTypeRef[paramTypes.size()];
				for (int i = 0; i < paramTypes.size(); i++)
				{
					llvmParamTypes[i] = TypeConverter.toLLVMType(paramTypes.get(i));
				}
				LLVMTypeRef returnType = TypeConverter.toLLVMType(methodSymbol.getType());
				LLVMTypeRef functionType = LLVMFunctionType(returnType, new PointerPointer<>(llvmParamTypes), paramTypes.size(), 0);
				function = LLVMAddFunction(module, mangledName, functionType);
			}

			// --- 2. Prepare arguments for the call ---
			List<LLVMValueRef> args = new ArrayList<>();
			if (ctx.argumentList() != null)
			{
				Debug.logWarning("IR: Processing " + ctx.argumentList().expression().size() + " arguments...");
				for (NebulaParser.ExpressionContext exprCtx : ctx.argumentList().expression())
				{
					// This recursively calls visitLiteral and correctly generates the string pointer
					args.add(visit(exprCtx));
				}
				Debug.logWarning("IR: Finished processing arguments.");
			}

			PointerPointer<LLVMValueRef> argsPtr = new PointerPointer<>(args.size());
			for (int i = 0; i < args.size(); i++)
			{
				argsPtr.put(i, args.get(i));
			}

			// --- 3. Build the call instruction ---
			Debug.logWarning("IR: Building LLVM call instruction for: " + mangledName);
			LLVMBuildCall2(builder, safeGetFunctionType(function, methodSymbol), function, argsPtr, args.size(), "");
			Debug.logWarning("IR: Call generation complete.");

			return null; // A statement expression doesn't return a value.
		}
		else
		{
			Debug.logWarning("IR: WARNING: Not a method call, or symbol not found on any context. Falling back to visitChildren.");
			// Fallback for other statement expressions (assignments, increments, etc.)
			return visitChildren(ctx);
		}
	}

	@Override
	public LLVMValueRef visitLiteral(NebulaParser.LiteralContext ctx)
	{
		if (ctx.STRING_LITERAL() != null)
		{
			String str = ctx.STRING_LITERAL().getText();
			str = str.substring(1, str.length() - 1);
			return LLVMBuildGlobalStringPtr(builder, str, "str_literal");
		}
		if (ctx.INTEGER_LITERAL() != null)
		{
			int val = Integer.parseInt(ctx.INTEGER_LITERAL().getText());
			return LLVMConstInt(LLVMInt32Type(), val, 1);
		}
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

	private LLVMTypeRef safeGetFunctionType(LLVMValueRef func, MethodSymbol symbol)
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
		List<Type> paramTypes = symbol.getParameterTypes();
		LLVMTypeRef[] llvmParamTypes = new LLVMTypeRef[paramTypes.size()];
		for (int i = 0; i < paramTypes.size(); i++)
		{
			llvmParamTypes[i] = TypeConverter.toLLVMType(paramTypes.get(i));
		}
		LLVMTypeRef retType = TypeConverter.toLLVMType(symbol.getType());
		return LLVMFunctionType(retType, new PointerPointer<>(llvmParamTypes), paramTypes.size(), 0);
	}

	@Override
	public LLVMValueRef visitBlock(NebulaParser.BlockContext ctx)
	{
		for (NebulaParser.StatementContext stmtCtx : ctx.statement())
		{
			visit(stmtCtx);
		}
		return null;
	}

	@Override
	public LLVMValueRef visitStatement(NebulaParser.StatementContext ctx)
	{
		visitChildren(ctx);
		return null;
	}
}