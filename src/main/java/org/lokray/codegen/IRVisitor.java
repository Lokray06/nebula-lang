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
	private final LLVMContextRef moduleContext; // <-- add this

	public IRVisitor(SemanticAnalyzer semanticAnalyzer, LLVMContext llvmContext)
	{
		this.semanticAnalyzer = semanticAnalyzer;
		this.llvmContext = llvmContext;
		builder = llvmContext.getBuilder();
		module = llvmContext.getModule();
		moduleContext = LLVMGetModuleContext(module);
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
		Optional<org.lokray.semantic.symbol.Symbol> symbolOpt = semanticAnalyzer.getResolvedSymbol(ctx);

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
	public LLVMValueRef visitLiteral(NebulaParser.LiteralContext ctx)
	{
		if (ctx.STRING_LITERAL() != null)
		{
			String value = ctx.STRING_LITERAL().getText();
			value = value.substring(1, value.length() - 1); // strip quotes

			// --- START FIX ---

			// 1. Create the global constant for the raw string data (e.g., [14 x i8] c"Hello world!!!")
			//    We use DontNullTerminate = 1 because String.cpp uses .write() with a length,
			//    so we don't need the extra '\0' byte.
			LLVMValueRef stringData = LLVMConstStringInContext(moduleContext, value, value.length(), 1);
			LLVMValueRef globalData = LLVMAddGlobal(module, LLVMTypeOf(stringData), ".str.data");
			LLVMSetInitializer(globalData, stringData);
			LLVMSetGlobalConstant(globalData, 1);
			LLVMSetLinkage(globalData, LLVMPrivateLinkage);

			// 2. Create a constant pointer (i8*) to the first element of that data.
			//    This is a GEP (Get Element Ptr) with indices [0, 0].
			LLVMValueRef zero32 = LLVMConstInt(LLVMInt32Type(), 0, 0);
			LLVMValueRef[] indices = {zero32, zero32};
			LLVMValueRef dataPtr = LLVMConstGEP2(LLVMTypeOf(stringData), globalData, new PointerPointer<>(indices), 2);

			// 3. Get your string struct type
			LLVMTypeRef stringType = TypeConverter.getStringStructTypeForContext(moduleContext);

			// 4. Construct the struct constant, now using the correct { i8*, i32 } layout
			LLVMValueRef[] fields = new LLVMValueRef[]{
					dataPtr,                                        // The i8* pointer
					LLVMConstInt(LLVMInt32Type(), value.length(), 0) // The i32 length
			};
			LLVMValueRef structConst = LLVMConstNamedStruct(stringType, new PointerPointer<>(fields), fields.length);

			// 5. Store the constant *struct* in its own global variable
			LLVMValueRef globalString = LLVMAddGlobal(module, LLVMTypeOf(structConst), "str_literal_struct");
			LLVMSetInitializer(globalString, structConst);
			LLVMSetGlobalConstant(globalString, 1);
			LLVMSetLinkage(globalString, LLVMPrivateLinkage);

			// 6. Return the pointer to the global struct
			return globalString;

			// --- END FIX ---
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