package org.lokray.semantic;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.lokray.parser.NebulaLexer;
import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.semantic.info.SimplifiedForInfo;
import org.lokray.semantic.info.TraditionalForInfo;
import org.lokray.semantic.symbol.*;
import org.lokray.semantic.type.*;
import org.lokray.util.Debug;
import org.lokray.util.ErrorHandler;

import java.math.BigInteger;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This visitor performs the final pass, type-checking all expressions and resolving symbols.
 */
public class TypeCheckVisitor extends NebulaParserBaseVisitor<Type>
{
	private final Scope globalScope;
	private Scope currentScope;
	private ClassSymbol currentClass;
	private MethodSymbol currentMethod;
	private Type expectedType = null;
	private ErrorHandler errorHandler;

	private final Map<String, ClassSymbol> declaredClasses;
	private final Map<ParseTree, Symbol> resolvedSymbols;
	private final Map<ParseTree, Type> resolvedTypes;
	private final Map<ParseTree, Object> resolvedInfo;

	public TypeCheckVisitor(Scope globalScope, Map<String, ClassSymbol> declaredClasses, Map<ParseTree, Symbol> symbols, Map<ParseTree, Type> types, Map<ParseTree, Object> resolvedInfo, ErrorHandler errorhandler)
	{
		this.globalScope = globalScope;
		this.currentScope = globalScope;
		this.declaredClasses = declaredClasses;
		this.resolvedSymbols = symbols;
		this.resolvedTypes = types;
		this.resolvedInfo = resolvedInfo; // Store the map
		this.errorHandler = errorhandler;
	}

	public boolean hasErrors()
	{
		return errorHandler.hasErrors();
	}

	private void logError(org.antlr.v4.runtime.Token token, String msg)
	{
		// Get the current class name or an empty string if not inside a class
		String className = currentClass != null ? currentClass.getName() : "";

		// Format the error string
		String err = String.format("[Semantic Error] %s - line %d:%d - %s", className, token.getLine(), token.getCharPositionInLine() + 1, msg);

		Debug.logError(err);
		errorHandler.setHasErrors(true);
	}

	private void note(ParseTree ctx, Symbol symbol)
	{
		if (ctx != null)
		{
			resolvedSymbols.put(ctx, symbol);
		}
	}

	private void note(ParseTree ctx, Type type)
	{
		if (ctx != null)
		{
			resolvedTypes.put(ctx, type);
		}
	}

	private void noteInfo(ParseTree ctx, Object info)
	{
		if (ctx != null && info != null)
		{
			resolvedInfo.put(ctx, info);
		}
	}

	@Override
	protected Type defaultResult()
	{
		return ErrorType.INSTANCE;
	}

	private Type resolveType(NebulaParser.TypeContext ctx)
	{
		if (ctx == null)
		{
			return ErrorType.INSTANCE;
		}

		// NEW: Handle tuple types like (int, string Name)
		if (ctx.tupleType() != null)
		{
			List<TupleElementSymbol> elements = new ArrayList<>();
			for (int i = 0; i < ctx.tupleType().tupleTypeElement().size(); i++)
			{
				var elementCtx = ctx.tupleType().tupleTypeElement(i);
				Type elementType = resolveType(elementCtx.type());
				String name = elementCtx.ID() != null ? elementCtx.ID().getText() : null;

				// Note: SymbolTableBuilder already checked for duplicate names, so we don't need to here.
				elements.add(new TupleElementSymbol(name, elementType, i));
			}
			return new TupleType(elements);
		}

		String baseTypeName;
		if (ctx.primitiveType() != null)
		{
			baseTypeName = ctx.primitiveType().getText();
		}
		else if (ctx.qualifiedName() != null)
		{
			// Use getFqn to properly handle namespaces
			baseTypeName = getFqn(ctx.qualifiedName());
		}
		else
		{
			logError(ctx.start, "Unsupported type structure.");
			return ErrorType.INSTANCE;
		}

		Optional<Symbol> symbol = currentScope.resolve(baseTypeName);

		if (symbol.isEmpty())
		{
			// FIRST: Try to find the FQN from the simple name, using existing helper
			String fqn = findFqnForSimpleName(baseTypeName);

			if (fqn != null)
			{
				symbol = Optional.of(declaredClasses.get(fqn));
			}
			// SECOND: Fallback to original check (in case baseTypeName was already an FQN)
			else if (declaredClasses.containsKey(baseTypeName))
			{
				symbol = Optional.of(declaredClasses.get(baseTypeName));
			}
			// FINAL: If no luck, then it's truly an error
			else
			{
				logError(ctx.start, "Undefined type: '" + baseTypeName + "'.");
				return ErrorType.INSTANCE;
			}
		}

		Type baseType = symbol.get().getType();
		if (baseType instanceof UnresolvedType)
		{
			baseType = resolveUnresolvedType((UnresolvedType) baseType, ctx.start);
		}

		int rank = ctx.L_BRACK_SYM().size();
		for (int i = 0; i < rank; i++)
		{
			baseType = new ArrayType(baseType);
		}
		return baseType;
	}

	private Type resolveUnresolvedType(UnresolvedType unresolved, Token errorToken)
	{
		String name = unresolved.getName();
		Optional<Symbol> resolved = currentScope.resolve(name);
		if (resolved.isPresent() && resolved.get().getType() != null && !(resolved.get().getType() instanceof UnresolvedType))
		{
			return resolved.get().getType();
		}
		if (declaredClasses.containsKey(name))
		{
			return declaredClasses.get(name).getType();
		}
		String fqn = findFqnForSimpleName(name);
		if (fqn != null)
		{
			return declaredClasses.get(fqn).getType();
		}
		logError(errorToken, "Undefined type: '" + name + "'.");
		return ErrorType.INSTANCE;
	}

	private String findFqnForSimpleName(String simpleName)
	{
		if (currentScope instanceof NamespaceSymbol)
		{
			String potentialFqn = ((NamespaceSymbol) currentScope).getFqn() + "." + simpleName;
			if (declaredClasses.containsKey(potentialFqn))
			{
				return potentialFqn;
			}
		}
		else if (currentScope instanceof ClassSymbol)
		{
			String potentialFqn = ((ClassSymbol) currentScope).getEnclosingScope().getName() + "." + simpleName;
			if (declaredClasses.containsKey(potentialFqn))
			{
				return potentialFqn;
			}
		}
		for (String fqn : declaredClasses.keySet())
		{
			if (fqn.endsWith("." + simpleName))
			{
				return fqn;
			}
		}
		return null;
	}

	// --- Scope Management ---
	@Override
	public Type visitNamespaceDeclaration(NebulaParser.NamespaceDeclarationContext ctx)
	{
		String nsName = getFqn(ctx.qualifiedName());
		currentScope = (Scope) currentScope.resolve(nsName).orElse(currentScope);
		visitChildren(ctx);
		currentScope = currentScope.getEnclosingScope();
		return null;
	}

	@Override
	public Type visitClassDeclaration(NebulaParser.ClassDeclarationContext ctx)
	{
		String className = ctx.ID().getText();
		currentClass = (ClassSymbol) currentScope.resolveLocally(className).orElse(null);
		if (currentClass == null)
		{
			Debug.logWarning("Class itself is apparently null??");
			return null;
		}
		currentScope = currentClass;
		if (ctx.classBody() != null)
		{
			for (var member : ctx.classBody())
			{
				visit(member);
			}
		}
		currentScope = currentScope.getEnclosingScope();
		currentClass = null;
		return null;
	}

	@Override
	public Type visitStructDeclaration(NebulaParser.StructDeclarationContext ctx)
	{
		String structName = ctx.ID().getText();
		// Resolve the ClassSymbol created in the first pass (SymbolTableBuilder)
		ClassSymbol structSymbol = (ClassSymbol) currentScope.resolveLocally(structName).orElse(null);
		if (structSymbol == null)
		{
			// This would be an internal error if SymbolTableBuilder ran correctly
			logError(ctx.start, "Internal error: Struct symbol '" + structName + "' not found.");
			return null;
		}

		// --- SCOPE SHIFT ---
		// Backup and set the current context before visiting members
		ClassSymbol oldClass = currentClass;
		currentClass = structSymbol;
		Scope oldScope = currentScope;
		currentScope = structSymbol; // <-- This is the crucial step

		// Visit the members inside the struct body
		if (ctx.structBody() != null)
		{
			for (var member : ctx.structBody())
			{
				visit(member); // Now, when visitConstructorDeclaration is called, currentScope IS a ClassSymbol
			}
		}

		// --- SCOPE RESTORE ---
		// Restore the context after leaving the struct
		currentScope = oldScope;
		currentClass = oldClass;
		return null;
	}

	@Override
	public Type visitConstructorDeclaration(NebulaParser.ConstructorDeclarationContext ctx)
	{
		List<Type> paramTypes = new ArrayList<>();
		if (ctx.parameterList() != null)
		{
			for (var pCtx : ctx.parameterList().parameter())
			{
				paramTypes.add(resolveType(pCtx.type()));
			}
		}

		// FIX: Use resolveMethods(name) which returns the list of overloads for that name.
		// Then, find the specific overload that matches this declaration's signature.
		Optional<MethodSymbol> ctorOpt = ((ClassSymbol) currentScope).resolveMethods(ctx.ID().getText())
				.stream()
				.filter(m -> m.getParameterTypes().equals(paramTypes))
				.findFirst();

		if (ctorOpt.isEmpty())
		{
			logError(ctx.ID().getSymbol(), "Internal error: Constructor symbol not found during type checking.");
			return null;
		}

		currentMethod = ctorOpt.get();
		currentScope = currentMethod;

		// Visit parameters to define them in the constructor's scope
		if (ctx.parameterList() != null)
		{
			visit(ctx.parameterList());
		}

		// Visit the constructor body
		visit(ctx.block());

		currentScope = currentScope.getEnclosingScope();
		currentMethod = null;
		return null;
	}

	@Override
	public Type visitMethodDeclaration(NebulaParser.MethodDeclarationContext ctx)
	{
		String methodName = ctx.ID().getText();

		// Collect parameter types from declaration
		List<Type> paramTypes = new ArrayList<>();
		if (ctx.parameterList() != null)
		{
			for (var pCtx : ctx.parameterList().parameter())
			{
				paramTypes.add(resolveType(pCtx.type()));
			}
		}

		// Resolve the declared return type of this method
		Type declaredReturnType = resolveType(ctx.type());

		// Find the exact matching method symbol (matches both params + return type)
		Optional<MethodSymbol> methodOpt = ((ClassSymbol) currentScope)
				.resolveMethodBySignature(methodName, paramTypes, declaredReturnType);

		if (methodOpt.isEmpty())
		{
			logError(ctx.ID().getSymbol(),
					"Internal error: Could not resolve method symbol for '" + methodName + "' with return type '"
							+ declaredReturnType.getName() + "'.");
			return ErrorType.INSTANCE;
		}

		MethodSymbol resolvedMethod = methodOpt.get();
		note(ctx, resolvedMethod);

		currentMethod = methodOpt.get();
		currentScope = currentMethod;

		// Visit parameters
		if (ctx.parameterList() != null)
		{
			for (var pCtx : ctx.parameterList().parameter())
			{
				visit(pCtx);
			}
		}

		// Visit method body
		if (ctx.block() != null)
		{
			visit(ctx.block());
		}

		// Check for missing return statement (for non-void, non-native methods)
		if (currentMethod.getType() != PrimitiveType.VOID && !currentMethod.isNative())
		{
			if (!blockReturns(ctx.block()))
			{
				logError(ctx.block().start, "Method must return a result of type '" + currentMethod.getType().getName() + "'. Not all code paths return a value.");
			}
		}

		// Check for main method correctness
		if (currentMethod.isMainMethod())
		{
			Type ret = currentMethod.getType();

			// Allow void or small signed integers only
			if (!(ret.isValidForMainReturnMain()))
			{
				logError(ctx.ID().getSymbol(), "Invalid return type for main(): expected a signed integer (≤ 32 bits) or void, found '" + ret.getName() + "'.");
			}
		}


		currentScope = currentScope.getEnclosingScope();
		currentMethod = null;
		return declaredReturnType;
	}

	/**
	 * Resolves a method call by finding the best overload based on structural fit,
	 * type compatibility, and contextual expected return type.
	 *
	 * @param callCtx     The context of the call for error reporting.
	 * @param classSymbol The class containing the methods.
	 * @param methodName  The name of the method to resolve.
	 * @param argListCtx  The arguments provided to the call.
	 * @return The return type of the resolved method, or ErrorType if resolution fails.
	 */
	private Type visitMethodCall(ParserRuleContext callCtx, ClassSymbol classSymbol, String methodName, NebulaParser.ArgumentListContext argListCtx)
	{
		// Debug not showing
		Debug.logDebug("Visiting method call: " + methodName);

		List<NebulaParser.ExpressionContext> positionalArgs = new ArrayList<>();
		Map<String, NebulaParser.ExpressionContext> namedArgs = new HashMap<>();
		if (argListCtx != null)
		{
			for (ParseTree child : argListCtx.children)
			{
				if (child instanceof NebulaParser.NamedArgumentContext namedArg)
				{
					String name = namedArg.ID().getText();
					if (namedArgs.containsKey(name))
					{
						errorHandler.logError(namedArg.ID().getSymbol(), "Duplicate named argument '" + name + "'.", currentClass);
						return ErrorType.INSTANCE;
					}
					namedArgs.put(name, namedArg.expression());
				}
				else if (child instanceof NebulaParser.ExpressionContext positionalArg)
				{
					if (positionalArg.getParent() == argListCtx)
					{
						if (!namedArgs.isEmpty())
						{
							errorHandler.logError(positionalArg.start, "Positional arguments cannot follow named arguments.", currentClass);
							return ErrorType.INSTANCE;
						}
						positionalArgs.add(positionalArg);
					}
				}
			}
		}

		MethodSymbol resolvedMethodOpt = classSymbol.resolveOverload(this, methodName, positionalArgs, namedArgs);

		if (resolvedMethodOpt == null)
		{
			List<MethodSymbol> viableCandidates = classSymbol.findViableMethods(methodName, positionalArgs, namedArgs);

			if (viableCandidates.isEmpty())
			{
				if (!namedArgs.isEmpty())
				{
					Set<String> allValidParamNames = classSymbol.resolveMethods(methodName).stream()
							.flatMap(m -> m.getParameters().stream().map(Symbol::getName))
							.collect(Collectors.toSet());

					for (String argName : namedArgs.keySet())
					{
						if (!allValidParamNames.contains(argName))
						{
							String signatures = classSymbol.resolveMethods(methodName).stream()
									.map(m -> m.getName() + m.getParameters().stream().map(p -> p.getType().getName() + " " + p.getName()).collect(Collectors.joining(", ", "(", ")")))
									.collect(Collectors.joining(", "));
							errorHandler.logError(callCtx.start, "Parameter with name '" + argName + "' not found in any overload of '" + methodName + "'. Available signatures: " + signatures, currentClass);
							return ErrorType.INSTANCE;
						}
					}
				}

				int argCount = positionalArgs.size() + namedArgs.size();
				String errorMsg = "No viable overload for method '" + methodName + "' takes " + argCount + " arguments.";
				List<MethodSymbol> allOverloads = classSymbol.resolveMethods(methodName);

				if (!allOverloads.isEmpty())
				{
					Set<Integer> expectedArities = new TreeSet<>();
					for (MethodSymbol m : allOverloads)
					{
						int maxArgs = m.getParameters().size();
						int minArgs = (int) m.getParameters().stream().filter(p -> !p.hasDefaultValue()).count();
						for (int i = minArgs; i <= maxArgs; i++)
						{
							expectedArities.add(i);
						}
					}
					if (!expectedArities.isEmpty())
					{
						String formattedArities;
						List<String> arityList = expectedArities.stream().map(String::valueOf).collect(Collectors.toList());
						if (arityList.size() > 1)
						{
							String last = arityList.remove(arityList.size() - 1);
							formattedArities = String.join(", ", arityList) + " or " + last;
						}
						else
						{
							formattedArities = arityList.get(0);
						}
						errorMsg += " Expected argument counts are: " + formattedArities + ".";
					}
				}
				errorHandler.logError(callCtx.start, errorMsg, currentClass);

			}
			else
			{
				String candidatesStr = viableCandidates.stream()
						.map(m -> m.getName() + m.getParameters().stream()
								.map(p -> p.getType().getName() + " " + p.getName())
								.collect(Collectors.joining(", ", "(", ")")) + " -> " + m.getType().getName())
						.collect(Collectors.joining("\n  "));
				errorHandler.logError(callCtx.start, "Ambiguous method call or no overload matches argument types. Potential candidates based on argument structure:\n  " + candidatesStr, currentClass);
			}

			return ErrorType.INSTANCE;
		}

		MethodSymbol bestMatch = resolvedMethodOpt;
		note(callCtx, bestMatch);
		typeCheckArguments(bestMatch, positionalArgs, namedArgs);
		return bestMatch.getType();
	}

	@Override
	public Type visitParameter(NebulaParser.ParameterContext ctx)
	{
		Type paramType = resolveType(ctx.type());
		String paramName = ctx.ID().getText();
		VariableSymbol varSymbol = new VariableSymbol(paramName, paramType, false, true, false);
		currentScope.define(varSymbol);
		note(ctx, varSymbol);
		note(ctx, paramType);
		return paramType;
	}

	@Override
	public Type visitBlock(NebulaParser.BlockContext ctx)
	{
		Scope blockScope = new Scope(currentScope);
		currentScope = blockScope;
		visitChildren(ctx);
		currentScope = currentScope.getEnclosingScope();
		return PrimitiveType.VOID;
	}

	@Override
	public Type visitStatement(NebulaParser.StatementContext ctx)
	{
		Debug.logDebug("VISIT STATEMENT -> hasExpr=" + (ctx.statementExpression() != null)
				+ " hasReturn=" + (ctx.returnStatement() != null)
				+ " hasFor=" + (ctx.forStatement() != null)
				+ " childCount=" + ctx.getChildCount());

		// then same body as before but keep it unchanged for diagnosis
		if (ctx.statementExpression() != null)
		{
			return visit(ctx.statementExpression());
		}
		if (ctx.returnStatement() != null)
		{
			return visit(ctx.returnStatement());
		}
		return (Type) super.visitChildren(ctx);
	}

	@Override
	public Type visitStatementExpression(NebulaParser.StatementExpressionContext ctx)
	{
		// Check if the context has enough children AND if the second child is the '(' terminal token.
		boolean isMethodCall = ctx.postfixExpression() != null
				&& ctx.getChildCount() >= 2
				&& ctx.getChild(1) instanceof TerminalNode
				&& ((TerminalNode) ctx.getChild(1)).getSymbol().getType() == NebulaLexer.L_PAREN_SYM; // Assuming NebulaLexer is available

		if (isMethodCall)
		{
			// --- START REPLACEMENT ---
			// 1. Visit the postfix part (e.g., "Console.println") to resolve it
			visit(ctx.postfixExpression());
			Symbol methodGroupSymbol = resolvedSymbols.get(ctx.postfixExpression());

			Debug.logDebug(methodGroupSymbol.getName());

			if (!(methodGroupSymbol instanceof MethodSymbol))
			{
				// This should ideally be caught by visitPostfixExpression, but good to check
				logError(ctx.start, "'" + ctx.postfixExpression().getText() + "' is not a method.");
				return ErrorType.INSTANCE;
			}

			// 2. Get the class that owns the method
			Scope enclosingScope = ((MethodSymbol) methodGroupSymbol).getEnclosingScope();
			if (!(enclosingScope instanceof ClassSymbol classOfMethod))
			{
				logError(ctx.start, "Internal error: Method is not part of a class.");
				return ErrorType.INSTANCE;
			}

			String methodName = methodGroupSymbol.getName();
			NebulaParser.ArgumentListContext argListCtx = ctx.argumentList();

			// 3. DELEGATE to the robust visitMethodCall logic!
			// We pass 'ctx' (the StatementExpression) as the "call context" for error reporting
			// and for 'note'-ing the resolved symbol.
			Type returnType = visitMethodCall(ctx, classOfMethod, methodName, argListCtx);

			// 4. (Optional) Log the successful resolution
			// visitMethodCall already does the 'note(ctx, bestMatch)', so we can check it.
			Symbol resolvedSymbol = resolvedSymbols.get(ctx);
			if (resolvedSymbol instanceof MethodSymbol resolvedMethod)
			{
				Debug.logDebug(resolvedMethod.toString());
			}
			else
			{
				// This else block will be hit if visitMethodCall failed and returned ErrorType
				System.out.println("Error: Method call resolution failed for: " + ctx.getText());
			}

			return returnType;
			// --- END REPLACEMENT ---
		}

		// fallback for other statement expressions
		return visitChildren(ctx);
	}

	@Override
	public Type visitVariableDeclaration(NebulaParser.VariableDeclarationContext ctx)
	{
		Type declaredType = resolveType(ctx.type());
		note(ctx.type(), declaredType);
		Type sharedInitializerType = null;

		NebulaParser.VariableDeclaratorContext lastDecl =
				ctx.variableDeclarator(ctx.variableDeclarator().size() - 1);

		if (lastDecl.expression() != null)
		{
			// --- FIX ---
			// Visit the initializer, telling it we EXPECT the variable's declared type.
			// This is how `float fPi = getPi()` works.
			sharedInitializerType = visitExpecting(lastDecl.expression(), declaredType);
		}

		for (var declarator : ctx.variableDeclarator())
		{
			String varName = declarator.ID().getText();

			if (currentScope.resolveLocally(varName).isPresent())
			{
				logError(declarator.ID().getSymbol(), "Variable '" + varName + "' is already defined in this scope.");
				continue;
			}

			// --- FIX ---
			// Use visitExpecting here as well for other declarators.
			Type initializerType = declarator.expression() != null
					? visitExpecting(declarator.expression(), declaredType)
					: (sharedInitializerType != null && declarator != lastDecl ? sharedInitializerType : null);

			if (initializerType != null && !initializerType.isAssignableTo(declaredType))
			{
				logError(declarator.start,
						"Incompatible types: cannot assign '" + initializerType.getName()
								+ "' to '" + declaredType.getName() + "'.");
			}

			VariableSymbol varSymbol = new VariableSymbol(varName, declaredType, false, true, false);
			currentScope.define(varSymbol);
			note(declarator, varSymbol);
		}

		return PrimitiveType.VOID;
	}

	@Override
	public Type visitFieldDeclaration(NebulaParser.FieldDeclarationContext ctx)
	{
		for (var declarator : ctx.variableDeclarator())
		{
			if (declarator.expression() != null)
			{
				Type fieldType = resolveType(ctx.type());
				Type initializerType = visit(declarator.expression());
				if (!initializerType.isAssignableTo(fieldType))
				{
					logError(declarator.expression().start, "Incompatible types in field initialization: cannot assign '" + initializerType.getName() + "' to '" + fieldType.getName() + "'.");
				}
			}
		}
		return PrimitiveType.VOID;
	}

	@Override
	public Type visitPropertyDeclaration(NebulaParser.PropertyDeclarationContext ctx)
	{
		// 1. Resolve the property symbol itself (already done in SymbolTableBuilder)
		String propName = ctx.ID().getText();

		// Check accessors to resolve method symbols created in the first pass
		for (var accessorCtx : ctx.accessorDeclaration())
		{
			boolean isGetter = accessorCtx.GET_KW() != null;
			String accessorName = isGetter ? "get_" + propName : "set_" + propName;

			// Find the MethodSymbol created in SymbolTableBuilder (it is defined in currentClass)
			Optional<MethodSymbol> msOpt = currentClass.resolveMethods(accessorName)
					.stream()
					.filter(ms -> ms.getParameters().size() == (isGetter ? 0 : 1))
					.findFirst();

			if (msOpt.isPresent())
			{
				MethodSymbol ms = msOpt.get();

				// --- SCOPE SHIFT ---
				// Set the scope for the TypeCheckVisitor
				Scope oldScope = currentScope;
				currentScope = ms;
				currentMethod = ms; // Set currentMethod if you use it for 'return' checks, etc.

				// 2. Visit the accessor body to perform type checking and symbol resolution
				NebulaParser.AccessorBodyContext bodyCtx = accessorCtx.accessorBody();
				if (bodyCtx.block() != null)
				{
					// Must visit the block to check statements inside the setter/getter
					visitBlock(bodyCtx.block());
				}
				else if (bodyCtx.expression() != null)
				{
					// Must visit the expression inside the setter/getter
					Type exprType = visit(bodyCtx.expression());

					// You can add logic here: e.g., if setter, the expression must implicitly perform assignment.
					// For now, we ensure the expression resolves.
				}

				// --- SCOPE RESTORE ---
				currentMethod = null;
				currentScope = oldScope;
			}
			else
			{
				// This should not happen if SymbolTableBuilder ran correctly
				logError(accessorCtx.start, "Internal Error: Accessor symbol not found for property '" + propName + "'.");
			}
		}

		// The property declaration itself doesn't return a type in this pass
		return null;
	}

	@Override
	public Type visitForStatement(NebulaParser.ForStatementContext ctx)
	{
		Scope forScope = new Scope(currentScope);
		currentScope = forScope;

		if (ctx.simplifiedForClause() != null)
		{
			NebulaParser.SimplifiedForClauseContext simplifiedCtx = ctx.simplifiedForClause();
			String varName = simplifiedCtx.ID().getText();

			// --- 1. Define expressions and default types ---
			Type defaultIntType = globalScope.resolve("int").map(Symbol::getType).orElse(ErrorType.INSTANCE);

			NebulaParser.ExpressionContext startExprCtx = null;
			NebulaParser.ExpressionContext limitExprCtx;
			NebulaParser.RelationalOperatorContext operator = simplifiedCtx.relationalOperator();

			if (simplifiedCtx.expression().size() == 2)
			{ // for(i = start op limit) [cite: 2551]
				startExprCtx = simplifiedCtx.expression(0);
				limitExprCtx = simplifiedCtx.expression(1);
				Type startType = visit(startExprCtx); // Type check start [cite: 2551]
				if (!startType.isInteger())
				{
					logError(((ParserRuleContext) startExprCtx).getStart(), "Incompatible types in for loop initializer: expected an integer expression, but found '" + startType.getName() + "'.");
				}
			}
			else
			{ // for(i op limit) [cite: 2552]
				limitExprCtx = simplifiedCtx.expression(0);
			}

			// --- 2. Resolve Limit Type ---
			Type limitType = visit(limitExprCtx); // Type check limit [cite: 2552]

			if (!limitType.isInteger())
			{
				logError(((ParserRuleContext) limitExprCtx).getStart(), "Incompatible types in for loop limit: expected an integer expression, but found '" + limitType.getName() + "'.");
				// Fallback to default int type if the limit is not a valid integer type [cite: 2553]
				limitType = defaultIntType;
			}

			// --- FIX: Add Semantic Check for Implicit Start with > or >= ---
			if (startExprCtx == null && (operator.GREATER_THAN_SYM() != null || operator.GREATER_EQUAL_THAN_SYM() != null))
			{
				logError(operator.start, "Simplified 'for' loop using '>' or '>=' requires an explicit start value (e.g., 'for (i = 10 > 0)'). Cannot implicitly count down from 0.");
				// Treat as error for now, could potentially recover by setting loopVarType to ErrorType
			}
			// --- END FIX ---

			// Set loop variable type directly from the limit type (if integer)
			Type loopVarType = limitType;

			// Define the loop variable with the resolved type (e.g., int64)
			VariableSymbol loopVar = new VariableSymbol(varName, loopVarType, false, true, false); //
			currentScope.define(loopVar); //
			note(simplifiedCtx.ID(), loopVar); //
			note(simplifiedCtx.ID(), loopVarType); //

			// Store the desugared info
			SimplifiedForInfo info = new SimplifiedForInfo(loopVar, startExprCtx, limitExprCtx, operator); //
			noteInfo(ctx, info); //

			visit(ctx.block()); // Visit body
		}
		else
		{
			// --- Traditional For --- [cite: 2555]
			// ... (existing traditional for loop logic remains the same) ... [cite: 2555-2558]
			ParseTree initializer = null;
			NebulaParser.ExpressionContext conditionExprCtx = null;
			NebulaParser.ExpressionContext updateExprCtx = null;
			int expressionIndex = 0;

			// Handle initializer [cite: 2555]
			if (ctx.variableDeclaration() != null)
			{
				initializer = ctx.variableDeclaration();
				visit(initializer);
			}
			else if (ctx.expression() != null && !ctx.expression().isEmpty() && ctx.SEMI_SYM(0) != null)
			{
				if (ctx.expression(0).getStart().getTokenIndex() < ctx.SEMI_SYM(0).getSymbol().getTokenIndex())
				{
					initializer = ctx.expression(expressionIndex++);
					visit(initializer);
				}
			}

			// Handle condition [cite: 2556]
			if (ctx.SEMI_SYM(0) != null && ctx.SEMI_SYM(1) != null)
			{
				if (ctx.expression().size() > expressionIndex)
				{
					if (ctx.expression(expressionIndex).getStart().getTokenIndex() > ctx.SEMI_SYM(0).getSymbol().getTokenIndex())
					{
						conditionExprCtx = ctx.expression(expressionIndex++);
						Type conditionType = visit(conditionExprCtx);
						Type boolType = globalScope.resolve("bool").map(Symbol::getType).orElse(ErrorType.INSTANCE);
						if (!conditionType.isAssignableTo(boolType))
						{
							logError(((ParserRuleContext) conditionExprCtx).getStart(), "For loop condition must be of type 'bool', but found '" + conditionType.getName() + "'.");
						}
					}
				}
			}

			// Handle update [cite: 2557]
			if (ctx.expression().size() > expressionIndex)
			{
				if (ctx.SEMI_SYM(1) != null && ctx.expression(expressionIndex).getStart().getTokenIndex() > ctx.SEMI_SYM(1).getSymbol().getTokenIndex())
				{
					updateExprCtx = ctx.expression(expressionIndex);
					visit(updateExprCtx);
				}
			}

			// Store the desugared info for the traditional loop [cite: 2558]
			TraditionalForInfo info = new TraditionalForInfo(initializer, conditionExprCtx, updateExprCtx);
			noteInfo(ctx, info);

			visit(ctx.block()); // Visit body [cite: 2558]
			System.out.println("SOMETHING'S VERY WRONG");
		}

		currentScope = currentScope.getEnclosingScope();
		return PrimitiveType.VOID;
	}

	@Override
	public Type visitForeachStatement(NebulaParser.ForeachStatementContext ctx)
	{
		Scope foreachScope = new Scope(currentScope);
		currentScope = foreachScope;

		Type declaredElementType = resolveType(ctx.type());
		String varName = ctx.ID().getText();
		Type collectionType = visit(ctx.expression());
		Type actualElementType = ErrorType.INSTANCE;

		if (collectionType instanceof ArrayType)
		{
			actualElementType = ((ArrayType) collectionType).getElementType();
		}
		else
		{
			logError(ctx.expression().start, "Foreach loop can only iterate over arrays, but found type '" + collectionType.getName() + "'.");
		}

		if (!actualElementType.isAssignableTo(declaredElementType))
		{
			logError(ctx.type().start, "Cannot convert element of type '" + actualElementType.getName() + "' to '" + declaredElementType.getName() + "' in foreach loop.");
		}

		VariableSymbol loopVar = new VariableSymbol(varName, declaredElementType, false, true, true);
		currentScope.define(loopVar);
		note(ctx.ID(), loopVar);
		note(ctx.ID(), declaredElementType);

		visit(ctx.statement());

		currentScope = currentScope.getEnclosingScope();
		return PrimitiveType.VOID;
	}

	@Override
	public Type visitIfStatement(NebulaParser.IfStatementContext ctx)
	{
		// 1. Type check the condition
		Type conditionType = visit(ctx.expression());

		// Condition must be a boolean
		if (conditionType != PrimitiveType.BOOLEAN && !(conditionType instanceof ErrorType))
		{
			logError(ctx.expression().start, "If statement condition must be of type 'boolean', but found '" + conditionType.getName() + "'.");
		}

		// 2. Traverse the 'if' branch (statement(0))
		visit(ctx.statement(0));

		// 3. Traverse the 'else' branch if it exists (statement(1))
		if (ctx.ELSE_KW() != null)
		{
			visit(ctx.statement(1));
		}

		// An if statement itself does not have a return type
		return PrimitiveType.VOID;
	}

	@Override
	public Type visitReturnStatement(NebulaParser.ReturnStatementContext ctx)
	{
		// A method must be in scope to have a return statement
		if (currentMethod == null)
		{
			logError(ctx.start, "Return statement found outside of a method or constructor.");
			return ErrorType.INSTANCE;
		}

		Type expectedReturnType = currentMethod.getType();
		Type actualReturnType = PrimitiveType.VOID; // Default for 'return;'

		if (ctx.expression() != null)
		{
			// Tell the nested expression we expect the function return type
			actualReturnType = visitExpecting(ctx.expression(), expectedReturnType);
		}

		if (actualReturnType instanceof ErrorType)
		{
			return actualReturnType; // Propagate error
		}

		// Check for void method returning a value
		if (expectedReturnType == PrimitiveType.VOID && actualReturnType != PrimitiveType.VOID)
		{
			logError(ctx.start, "Void method cannot return a value.");
			return ErrorType.INSTANCE;
		}

		// Check for non-void method with 'return;' (implicitly returning void)
		if (expectedReturnType != PrimitiveType.VOID && actualReturnType == PrimitiveType.VOID)
		{
			logError(ctx.start, "Method expects return type '" + expectedReturnType.getName() + "' but found 'return;' statement.");
			return ErrorType.INSTANCE;
		}

		// Check assignability for non-void return types
		if (expectedReturnType != PrimitiveType.VOID && !actualReturnType.isAssignableTo(expectedReturnType))
		{
			logError(ctx.expression().start, "Incompatible return type: cannot return '" + actualReturnType.getName() + "', expected '" + expectedReturnType.getName() + "'.");
			return ErrorType.INSTANCE;
		}

		// The type of the expression is what's being returned.
		note(ctx, actualReturnType);
		return actualReturnType;
	}

	// --- Expressions ---
	// NEW: Visitor for tuple literals like (1, "a") or (Name: "a", Value: 1)
	@Override
	public Type visitTupleLiteral(NebulaParser.TupleLiteralContext ctx)
	{
		List<TupleElementSymbol> elements = new ArrayList<>();
		boolean isNamed = !ctx.namedArgument().isEmpty();

		if (isNamed)
		{
			// Handle named arguments: (Sum: 4.5, Count: 3)
			for (int i = 0; i < ctx.namedArgument().size(); i++)
			{
				var namedArgCtx = ctx.namedArgument(i);
				String name = namedArgCtx.ID().getText();
				Type valueType = visit(namedArgCtx.expression());
				if (valueType instanceof ErrorType)
				{
					return ErrorType.INSTANCE;
				}
				elements.add(new TupleElementSymbol(name, valueType, i));
			}
		}
		else
		{
			// Handle positional arguments: (4.5, 3)
			for (int i = 0; i < ctx.expression().size(); i++)
			{
				Type valueType = visit(ctx.expression(i));
				if (valueType instanceof ErrorType)
				{
					return ErrorType.INSTANCE;
				}
				elements.add(new TupleElementSymbol(null, valueType, i)); // No explicit name
			}
		}

		TupleType tupleType = new TupleType(elements);
		note(ctx, tupleType);
		return tupleType;
	}

	@Override
	public Type visitInterpolatedString(NebulaParser.InterpolatedStringContext ctx)
	{
		// Visit each expression inside the interpolation to type-check it.
		for (var part : ctx.interpolationPart())
		{
			if (part.expression() != null)
			{
				visit(part.expression()); // We don't have an expected type, just check for internal errors.
			}
		}
		// The type of the entire literal is 'string'.
		return globalScope.resolve("string").get().getType();
	}

	// UPDATED: Handle tuple assignment, including by name
	@Override
	public Type visitAssignmentExpression(NebulaParser.AssignmentExpressionContext ctx)
	{
		Type targetType = visit(ctx.conditionalExpression(0));
		if (ctx.assignmentOperator() != null)
		{
			if (targetType instanceof ErrorType)
			{
				return ErrorType.INSTANCE;
			}
			Type valueType = visit(ctx.conditionalExpression(1));
			if (valueType instanceof ErrorType)
			{
				return ErrorType.INSTANCE;
			}

			boolean isAssignable;
			// Check for special case: assigning a named tuple literal to a tuple type
			if (targetType.isTuple() && valueType.isTuple() && !((TupleType) valueType).getElements().isEmpty() && ((TupleType) valueType).getElements().get(0).getName() != null)
			{
				isAssignable = checkNamedTupleAssignment((TupleType) targetType, (TupleType) valueType, ctx.conditionalExpression(1).start);
			}
			else
			{
				isAssignable = valueType.isAssignableTo(targetType);
			}

			if (!isAssignable)
			{
				logError(ctx.assignmentOperator().start, "Incompatible types: cannot assign '" + valueType.getName() + "' to '" + targetType.getName() + "'.");
				return ErrorType.INSTANCE;
			}
			note(ctx, targetType);
			return targetType;
		}
		note(ctx, targetType);
		return targetType;
	}

	// NEW HELPER METHOD: For assigning named tuple literals
	private boolean checkNamedTupleAssignment(TupleType target, TupleType source, Token errorToken)
	{
		if (target.getElements().size() != source.getElements().size())
		{
			return false;
		}

		for (TupleElementSymbol sourceElement : source.getElements())
		{
			// Find the corresponding element in the target by name
			Optional<Symbol> targetElementOpt = target.resolveLocally(sourceElement.getName());

			if (targetElementOpt.isEmpty())
			{
				logError(errorToken, "The tuple literal has an element named '" + sourceElement.getName() + "' which is not present in the target type '" + target.getName() + "'.");
				return false;
			}

			if (!sourceElement.getType().isAssignableTo(targetElementOpt.get().getType()))
			{
				return false; // Type mismatch
			}
		}
		return true;
	}

	@Override
	public Type visitPostfixExpression(NebulaParser.PostfixExpressionContext ctx)
	{
		Type currentType = visit(ctx.primary());
		Symbol currentSymbol = resolvedSymbols.get(ctx.primary());

		boolean primaryIsTypeName = false;

		if (ctx.primary() != null && ctx.primary().primitiveType() != null)
		{
			primaryIsTypeName = true;
		}
		else if (currentSymbol != null)
		{
			if (currentSymbol instanceof ClassSymbol ||
					currentSymbol instanceof StructSymbol ||
					currentSymbol instanceof NamespaceSymbol)
			{
				primaryIsTypeName = true;
			}
			else if (currentSymbol instanceof AliasSymbol)
			{
				Symbol target = ((AliasSymbol) currentSymbol).getTargetSymbol();
				if (target instanceof ClassSymbol || target instanceof StructSymbol || target instanceof NamespaceSymbol)
				{
					primaryIsTypeName = true;
				}
			}
		}

		if (primaryIsTypeName && ctx.getChildCount() == 1)
		{
			errorHandler.logError(ctx.start, "Expected an expression that evaluates to a value, but found a bare type name '" + currentType.getName() + "'.", currentClass);
			return ErrorType.INSTANCE;
		}

		int i = 1;
		while (i < ctx.getChildCount())
		{
			if (currentType instanceof ErrorType)
			{
				return ErrorType.INSTANCE;
			}

			ParseTree operator = ctx.getChild(i);

			if (operator.getText().equals("."))
			{
				i++;
				if (i >= ctx.getChildCount())
				{
					break;
				}

				String memberName = ctx.getChild(i).getText();
				Scope scopeToSearch = currentType.getClassSymbol();

				if (scopeToSearch == null)
				{
					if (currentType instanceof NamespaceType)
					{
						scopeToSearch = ((NamespaceType) currentType).getNamespaceSymbol();
					}
					else if (currentType instanceof TupleType)
					{
						scopeToSearch = (TupleType) currentType;
					}
				}

				if (scopeToSearch == null)
				{
					errorHandler.logError(ctx.start, "Cannot access member '" + memberName + "' on type '" + currentType.getName() + "'.", currentClass);
					return ErrorType.INSTANCE;
				}

				Optional<Symbol> memberOpt = scopeToSearch.resolve(memberName);
				if (memberOpt.isEmpty())
				{
					errorHandler.logError(((TerminalNode) ctx.getChild(i)).getSymbol(), "Cannot resolve member '" + memberName + "' in type '" + scopeToSearch.getName() + "'.", currentClass);
					return ErrorType.INSTANCE;
				}

				currentSymbol = memberOpt.get();
				if (currentSymbol instanceof AliasSymbol)
				{
					currentSymbol = ((AliasSymbol) currentSymbol).getTargetSymbol();
				}

				if (currentSymbol instanceof MethodSymbol)
				{
					note(ctx, currentSymbol); // <--- ADD THIS LINE TO RECORD THE METHOD GROUP
				}

				currentType = currentSymbol.getType();
			}
			else if (operator.getText().equals("("))
			{
				if (!(currentSymbol instanceof MethodSymbol))
				{
					errorHandler.logError(ctx.start, "'" + currentSymbol.getName() + "' is not a method and cannot be called.", currentClass);
					return ErrorType.INSTANCE;
				}

				Scope enclosingScope = ((Scope) currentSymbol).getEnclosingScope();
				if (!(enclosingScope instanceof ClassSymbol))
				{
					errorHandler.logError(ctx.start, "Internal error: Method '" + currentSymbol.getName() + "' is not a member of a class.", currentClass);
					return ErrorType.INSTANCE;
				}

				ClassSymbol classOfMethod = (ClassSymbol) enclosingScope;
				String methodName = currentSymbol.getName();

				i++;
				NebulaParser.ArgumentListContext argListCtx = null;
				if (i < ctx.getChildCount() && ctx.getChild(i) instanceof NebulaParser.ArgumentListContext)
				{
					argListCtx = (NebulaParser.ArgumentListContext) ctx.getChild(i);
					i++;
				}

				// Call visitMethodCall (which already notes internally)
				Type callType = visitMethodCall(ctx, classOfMethod, methodName, argListCtx);

				List<NebulaParser.ExpressionContext> args = new ArrayList<>();
				if (argListCtx != null && argListCtx.expression() != null)
				{
					args.addAll(argListCtx.expression());
				}

				MethodSymbol resolvedMethod = classOfMethod.resolveOverload(this, methodName, args, Map.of());

				if (resolvedMethod != null)
				{
					note(ctx, resolvedMethod);
				}
				currentType = callType;
				currentSymbol = resolvedMethod;
			}

			i++;
		}

		// --- START FIX ---
		// Store the final resolved symbol against the *entire* postfix expression node.
		// This is crucial so that parent visitors (like visitStatementExpression)
		// can retrieve the symbol from *this* node.
		if (currentSymbol != null)
		{
			note(ctx, currentSymbol);
		}
		// --- END FIX ---

		note(ctx, currentType);

		return currentType;
	}

	// --- NEWLY FIXED Bitwise Visitors (Based on parser grammar) ---

	@Override
	public Type visitLogicalOrExpression(NebulaParser.LogicalOrExpressionContext ctx)
	{
		if (ctx.logicalAndExpression().size() == 1)
		{
			return visit(ctx.logicalAndExpression(0));
		}

		Type resultType = PrimitiveType.BOOLEAN; // Logical operations always result in bool
		boolean hasError = false;

		// Loop through ALL operands
		for (int i = 0; i < ctx.logicalAndExpression().size(); i++)
		{
			Type operandType = visit(ctx.logicalAndExpression(i));

			if (operandType instanceof ErrorType)
			{
				hasError = true; // Propagate error
				continue;
			}

			// Check if the operand is boolean
			if (!operandType.isBoolean())
			{
				Token errorToken = (i > 0) ? ctx.LOG_OR_OP(i - 1).getSymbol() : ctx.logicalAndExpression(i).start;
				logError(errorToken, "Logical operator '||' can only be applied to boolean types, but found '" + operandType.getName() + "'.");
				hasError = true;
			}
		}

		if (hasError)
		{
			resultType = ErrorType.INSTANCE;
		}

		note(ctx, resultType);
		return resultType;
	}

	@Override
	public Type visitLogicalAndExpression(NebulaParser.LogicalAndExpressionContext ctx)
	{
		if (ctx.bitwiseOrExpression().size() == 1)
		{
			return visit(ctx.bitwiseOrExpression(0));
		}

		Type resultType = PrimitiveType.BOOLEAN; // Logical operations always result in bool
		boolean hasError = false;

		// Loop through ALL operands
		for (int i = 0; i < ctx.bitwiseOrExpression().size(); i++)
		{
			Type operandType = visit(ctx.bitwiseOrExpression(i));

			if (operandType instanceof ErrorType)
			{
				hasError = true;
				continue;
			}

			// Check if the operand is boolean
			if (!operandType.isBoolean())
			{
				Token errorToken = (i > 0) ? ctx.LOG_AND_OP(i - 1).getSymbol() : ctx.bitwiseOrExpression(i).start;
				logError(errorToken, "Logical operator '&&' can only be applied to boolean types, but found '" + operandType.getName() + "'.");
				hasError = true;
			}
		}

		if (hasError)
		{
			resultType = ErrorType.INSTANCE;
		}

		note(ctx, resultType);
		return resultType;
	}

	@Override
	public Type visitBitwiseOrExpression(NebulaParser.BitwiseOrExpressionContext ctx)
	{
		if (ctx.bitwiseXorExpression().size() == 1)
		{
			return visit(ctx.bitwiseXorExpression(0));
		}

		// Visit the first operand to establish the initial type
		Type finalType = visit(ctx.bitwiseXorExpression(0));
		if (finalType instanceof ErrorType)
		{
			return ErrorType.INSTANCE;
		}

		if (!finalType.isInteger())
		{
			logError(ctx.bitwiseXorExpression(0).start, "Bitwise operator '|' can only be applied to integer types, but found '" + finalType.getName() + "'.");
			finalType = ErrorType.INSTANCE;
		}

		// Loop through the rest of the operands
		for (int i = 1; i < ctx.bitwiseXorExpression().size(); i++)
		{
			Type rightType = visit(ctx.bitwiseXorExpression(i));
			if (rightType instanceof ErrorType)
			{
				finalType = ErrorType.INSTANCE;
			}

			if (!rightType.isInteger())
			{
				logError(ctx.bitwiseXorExpression(i).start, "Bitwise operator '|' can only be applied to integer types, but found '" + rightType.getName() + "'.");
				finalType = ErrorType.INSTANCE;
			}

			// Determine the wider type for the next iteration (if no error)
			if (finalType != ErrorType.INSTANCE)
			{
				finalType = Type.getWiderType(finalType, rightType);
			}
		}

		note(ctx, finalType);
		return finalType;
	}

	@Override
	public Type visitBitwiseXorExpression(NebulaParser.BitwiseXorExpressionContext ctx)
	{
		if (ctx.bitwiseAndExpression().size() == 1)
		{
			return visit(ctx.bitwiseAndExpression(0));
		}

		Type finalType = visit(ctx.bitwiseAndExpression(0));
		if (finalType instanceof ErrorType)
		{
			return ErrorType.INSTANCE;
		}

		if (!finalType.isInteger())
		{
			logError(ctx.bitwiseAndExpression(0).start, "Bitwise operator '^' can only be applied to integer types, but found '" + finalType.getName() + "'.");
			finalType = ErrorType.INSTANCE;
		}

		for (int i = 1; i < ctx.bitwiseAndExpression().size(); i++)
		{
			Type rightType = visit(ctx.bitwiseAndExpression(i));
			if (rightType instanceof ErrorType)
			{
				finalType = ErrorType.INSTANCE;
			}

			if (!rightType.isInteger())
			{
				logError(ctx.bitwiseAndExpression(i).start, "Bitwise operator '^' can only be applied to integer types, but found '" + rightType.getName() + "'.");
				finalType = ErrorType.INSTANCE;
			}

			if (finalType != ErrorType.INSTANCE)
			{
				finalType = Type.getWiderType(finalType, rightType);
			}
		}

		note(ctx, finalType);
		return finalType;
	}

	@Override
	public Type visitBitwiseAndExpression(NebulaParser.BitwiseAndExpressionContext ctx)
	{
		if (ctx.equalityExpression().size() == 1)
		{
			return visit(ctx.equalityExpression(0));
		}

		Type finalType = visit(ctx.equalityExpression(0));
		if (finalType instanceof ErrorType)
		{
			return ErrorType.INSTANCE;
		}

		if (!finalType.isInteger())
		{
			logError(ctx.equalityExpression(0).start, "Bitwise operator '&' can only be applied to integer types, but found '" + finalType.getName() + "'.");
			finalType = ErrorType.INSTANCE;
		}

		for (int i = 1; i < ctx.equalityExpression().size(); i++)
		{
			Type rightType = visit(ctx.equalityExpression(i));
			if (rightType instanceof ErrorType)
			{
				finalType = ErrorType.INSTANCE;
			}

			if (!rightType.isInteger())
			{
				logError(ctx.equalityExpression(i).start, "Bitwise operator '&' can only be applied to integer types, but found '" + rightType.getName() + "'.");
				finalType = ErrorType.INSTANCE;
			}

			if (finalType != ErrorType.INSTANCE)
			{
				finalType = Type.getWiderType(finalType, rightType);
			}
		}

		note(ctx, finalType);
		return finalType;
	}

	@Override
	public Type visitEqualityExpression(NebulaParser.EqualityExpressionContext ctx)
	{
		if (ctx.relationalExpression().size() == 1)
		{
			return visit(ctx.relationalExpression(0));
		}

		Type leftType = visit(ctx.relationalExpression(0));
		boolean hasError = false;

		// Loop through all comparisons
		for (int i = 1; i < ctx.relationalExpression().size(); i++)
		{
			Type rightType = visit(ctx.relationalExpression(i));

			if (leftType instanceof ErrorType || rightType instanceof ErrorType)
			{
				hasError = true; // Propagate error
			}
			else if (!leftType.isAssignableTo(rightType) && !rightType.isAssignableTo(leftType))
			{
				// Get operator token
				Token opToken = (Token) ctx.getChild(2 * i - 1).getPayload();
				logError(opToken, "Operator '" + opToken.getText() + "' cannot be applied to '" + leftType.getName() + "' and '" + rightType.getName() + "'.");
				hasError = true;
			}

			// The result of a comparison is always boolean, but for chained comparisons (a == b == c),
			// the next comparison is (boolean == c), so we update leftType.
			// For simplicity, let's assume Nebula chains like C++ (a == b) && (b == c) is not intended,
			// but rather the result of (a == b) is compared to c.
			// The result of the full expression is boolean.
			leftType = PrimitiveType.BOOLEAN;
		}

		Type resultType = hasError ? ErrorType.INSTANCE : PrimitiveType.BOOLEAN;
		note(ctx, resultType);
		return resultType;
	}

	@Override
	public Type visitRelationalExpression(NebulaParser.RelationalExpressionContext ctx)
	{
		if (ctx.shiftExpression().size() == 1)
		{
			return visit(ctx.shiftExpression(0));
		}

		Type leftType = visit(ctx.shiftExpression(0));
		boolean hasError = false;

		// Loop through all comparisons
		for (int i = 1; i < ctx.shiftExpression().size(); i++)
		{
			Type rightType = visit(ctx.shiftExpression(i));

			if (leftType instanceof ErrorType || rightType instanceof ErrorType)
			{
				hasError = true;
			}
			else if (!leftType.isNumeric() || !rightType.isNumeric())
			{
				Token opToken = (Token) ctx.getChild(2 * i - 1).getPayload();
				logError(opToken, "Relational operator '" + opToken.getText() + "' cannot be applied to non-numeric types '" + leftType.getName() + "' and '" + rightType.getName() + "'.");
				hasError = true;
			}

			// Like equality, the result of a single comparison is boolean.
			// For chained comparisons (a < b < c), the next op compares (boolean < c).
			// We will assume this is an error for now, as it's rarely intended.
			if (i > 1 && !leftType.isNumeric())
			{
				Token opToken = (Token) ctx.getChild(2 * i - 1).getPayload();
				logError(opToken, "Cannot chain relational operators. The result of '" + ctx.shiftExpression(i - 2).getText() + " " + ctx.getChild(2 * i - 3).getText() + " " + ctx.shiftExpression(i - 1).getText() + "' is a boolean, which cannot be compared with '" + opToken.getText() + "'.");
				hasError = true;
			}

			leftType = PrimitiveType.BOOLEAN; // The result of this part of the chain
		}

		Type resultType = hasError ? ErrorType.INSTANCE : PrimitiveType.BOOLEAN;
		note(ctx, resultType);
		return resultType;
	}

	@Override
	public Type visitShiftExpression(NebulaParser.ShiftExpressionContext ctx)
	{
		if (ctx.additiveExpression().size() == 1)
		{
			return visit(ctx.additiveExpression(0));
		}

		Type leftType = visit(ctx.additiveExpression(0));
		if (leftType instanceof ErrorType)
		{
			return ErrorType.INSTANCE;
		}

		// The left-hand side must be an integer
		if (!leftType.isInteger())
		{
			logError(ctx.additiveExpression(0).start, "Shift operator operand must be an integer type, but found '" + leftType.getName() + "'.");
			leftType = ErrorType.INSTANCE; // Mark as error
		}

		// Loop through all right-hand operands
		for (int i = 1; i < ctx.additiveExpression().size(); i++)
		{
			Type rightType = visit(ctx.additiveExpression(i));
			if (rightType instanceof ErrorType)
			{
				leftType = ErrorType.INSTANCE; // Propagate error
				continue;
			}

			// The right-hand side must also be an integer
			if (!rightType.isInteger())
			{
				logError(ctx.additiveExpression(i).start, "Shift operator right-hand operand must be an integer type, but found '" + rightType.getName() + "'.");
				leftType = ErrorType.INSTANCE;
			}
		}

		// The result type of a shift is always the type of the left-hand operand
		note(ctx, leftType);
		return leftType;
	}

	@Override
	public Type visitAdditiveExpression(NebulaParser.AdditiveExpressionContext ctx)
	{
		if (ctx.multiplicativeExpression().size() == 1)
		{
			return visit(ctx.multiplicativeExpression(0));
		}

		// Visit left first.
		Type leftType = visit(ctx.multiplicativeExpression(0));
		if (leftType instanceof ErrorType)
		{
			return ErrorType.INSTANCE;
		}

		// Set expectation for the right operand to match left (if numeric).
		Type old = expectedType;
		if (leftType.isNumeric())
		{
			expectedType = leftType;
		}

		// Loop through all operands
		for (int i = 1; i < ctx.multiplicativeExpression().size(); i++)
		{
			Type rightType = visit(ctx.multiplicativeExpression(i));
			if (rightType instanceof ErrorType)
			{
				leftType = ErrorType.INSTANCE; // Propagate error
				continue; // Continue visiting other children to find more errors
			}

			// Check for numeric compatibility
			if (!leftType.isNumeric() || !rightType.isNumeric())
			{
				// Get the operator token
				Token opToken = (Token) ctx.getChild(2 * i - 1).getPayload();
				logError(opToken, "Arithmetic operator '" + opToken.getText() + "' cannot be applied to non-numeric types '" + leftType.getName() + "' and '" + rightType.getName() + "'.");
				leftType = ErrorType.INSTANCE;
			}
			else
			{
				// Promote for the next iteration
				leftType = Type.getWiderType(leftType, rightType);
				expectedType = leftType; // Update expectation for the *next* right operand
			}
		}

		expectedType = old; // Restore original expectation
		note(ctx, leftType); // Note the final result type
		return leftType;
	}

	@Override
	public Type visitPowerExpression(NebulaParser.PowerExpressionContext ctx)
	{
		if (ctx.unaryExpression().size() == 1)
		{
			return visit(ctx.unaryExpression(0));
		}

		Type leftType = visit(ctx.unaryExpression(0));
		if (leftType instanceof ErrorType)
		{
			return ErrorType.INSTANCE;
		}

		Type old = expectedType;
		if (leftType.isNumeric())
		{
			expectedType = leftType;
		}

		// Loop through all operands
		for (int i = 1; i < ctx.unaryExpression().size(); i++)
		{
			Type rightType = visit(ctx.unaryExpression(i));
			if (rightType instanceof ErrorType)
			{
				leftType = ErrorType.INSTANCE;
				continue;
			}

			if (!leftType.isNumeric() || !rightType.isNumeric())
			{
				Token opToken = (Token) ctx.getChild(2 * i - 1).getPayload();
				logError(opToken, "Arithmetic operator '**' cannot be applied to non-numeric types '" + leftType.getName() + "' and '" + rightType.getName() + "'.");
				leftType = ErrorType.INSTANCE;
			}
			else
			{
				// Promote for the next iteration
				leftType = Type.getWiderType(leftType, rightType);
				expectedType = leftType;
			}
		}

		expectedType = old;
		note(ctx, leftType);
		return leftType;
	}


	@Override
	public Type visitCastExpression(NebulaParser.CastExpressionContext ctx)
	{
		// Resolve the target type from the grammar rule.
		Type targetType = resolveType(ctx.type());

		if (targetType instanceof ErrorType)
		{
			note(ctx, ErrorType.INSTANCE);
			return ErrorType.INSTANCE;
		}

		// Visit the inner expression while telling it we expect the cast target type.
		Type originalType = visitExpecting(ctx.unaryExpression(), targetType);

		// Propagate errors
		if (originalType instanceof ErrorType)
		{
			note(ctx, ErrorType.INSTANCE);
			return ErrorType.INSTANCE;
		}

		// --- START FIX ---
		// Check for allowed casts
		boolean isSameType = originalType.equals(targetType) || PrimitiveType.areEquivalent(originalType, targetType);
		boolean isNumericCast = originalType.isNumeric() && targetType.isNumeric();
		// Assuming you have isReferenceType() on your Type interface
		boolean isReferenceCast = originalType.isReferenceType() && targetType.isReferenceType();

		// UPDATED CHECK: Allow if same type, OR numeric cast, OR reference cast
		if (!isSameType && !isNumericCast && !isReferenceCast)
		{
			logError(ctx.start, "Cannot cast from '" + originalType.getName() + "' to '" + targetType.getName() + "'.");
			note(ctx, ErrorType.INSTANCE);
			return ErrorType.INSTANCE;
		}
		// --- END FIX ---

		// The type of the entire cast expression is the target type.
		note(ctx, targetType);
		return targetType;
	}

	private String getFqn(NebulaParser.QualifiedNameContext ctx)
	{
		return ctx.getText();
	}

	@Override
	public Type visitPrimary(NebulaParser.PrimaryContext ctx)
	{
		// --- START NEW LOGGING ---
		Debug.logDebug("TypeCheckVisitor visiting Primary: " + ctx.getText() + " (Context Hash: " + ctx.hashCode() + ")");
		// --- END NEW LOGGING ---

		if (ctx.NEW_KW() != null)
		{
			// ... (existing 'new' logic) ... [cite: 485-490]
			// This is a constructor call: new Person(...)
			Type type = resolveType(ctx.type());
			if (!(type instanceof ClassType))
			{
				logError(ctx.type().start, "Can only instantiate a class type.");
				return ErrorType.INSTANCE;
			}
			ClassSymbol classSymbol = ((ClassType) type).getClassSymbol();

			// Handle constructor call: new Person(args)
			if (ctx.L_PAREN_SYM() != null)
			{
				// We pass `ctx` itself for error reporting
				visitMethodCall(ctx, classSymbol, classSymbol.getName(), ctx.argumentList());
			}
			// Handle array creation: new int[size]
			else if (ctx.L_BRACK_SYM() != null)
			{
				Type sizeType = visit(ctx.expression());
				if (!sizeType.isInteger())
				{
					logError(ctx.expression().start, "Array size must be an integer.");
				}
				// The type of `new int[5]` is `int[]`.
				type = new ArrayType(type);
			}


			note(ctx, type);
			return type;
		}

		if (ctx.ID() != null)
		{
			// --- START NEW LOGGING ---
			Debug.logDebug("  -> Primary is an Identifier: " + ctx.ID().getText());
			// --- END NEW LOGGING ---

			String name = ctx.ID().getText();

			if (name.equals("this"))
			{
				// ... (existing 'this' logic) ... [cite: 491-493]
				if (currentMethod != null && currentMethod.isStatic())
				{
					logError(ctx.ID().getSymbol(), "'this' cannot be used in a static context.");
					return ErrorType.INSTANCE;
				}
				if (currentClass == null)
				{
					logError(ctx.ID().getSymbol(), "'this' cannot be used outside of an instance context.");
					return ErrorType.INSTANCE;
				}
				Type thisType = currentClass.getType();
				note(ctx, thisType);
				note(ctx, new VariableSymbol("this", thisType, false, false, true));
				return thisType;
			}

			Optional<Symbol> symbolOpt = currentScope.resolve(name);
			if (symbolOpt.isEmpty())
			{
				// --- START NEW LOGGING ---
				Debug.logDebug("    -> Symbol resolution FAILED for '" + name + "'");
				// --- END NEW LOGGING ---
				logError(ctx.ID().getSymbol(), "Cannot find symbol '" + name + "'.");
				note(ctx, ErrorType.INSTANCE);
				return ErrorType.INSTANCE;
			}

			Symbol symbol = symbolOpt.get();
			if (symbol instanceof AliasSymbol)
			{
				symbol = ((AliasSymbol) symbol).getTargetSymbol();
			}

			// --- START NEW LOGGING ---
			Debug.logDebug("    -> Symbol resolution SUCCEEDED for '" + name + "': " + symbol);
			// --- END NEW LOGGING ---

			note(ctx, symbol);
			Type type = symbol.getType();
			if (type instanceof UnresolvedType)
			{
				type = resolveUnresolvedType((UnresolvedType) type, ctx.ID().getSymbol());
			}

			// --- START NEW LOGGING ---
			Debug.logDebug("    -> Final type for '" + name + "': " + (type != null ? type.getName() : "null"));
			// --- END NEW LOGGING ---

			note(ctx, type);
			return type;
		}
		if (ctx.literal() != null)
		{
			// ... (existing literal logic) ... [cite: 496]
			Type literalType = visit(ctx.literal());
			note(ctx, literalType);
			return literalType;
		}
		if (ctx.expression() != null)
		{
			// ... (existing parenthesized expression logic) ... [cite: 497]
			Type exprType = visit(ctx.expression());
			note(ctx, exprType);
			return exprType;
		}

		// This handles the new primitiveType in primary rule
		if (ctx.primitiveType() != null)
		{
			// ... (existing primitive type logic) ... [cite: 497-499]
			String baseTypeName = ctx.primitiveType().getText();

			// Find the symbol for the primitive type in the global scope
			Optional<Symbol> symbolOpt = globalScope.resolve(baseTypeName);

			if (symbolOpt.isEmpty())
			{
				logError(ctx.primitiveType().start, "Undefined primitive type: '" + baseTypeName + "'.");
				return ErrorType.INSTANCE;
			}

			Type primitive = symbolOpt.get().getType();
			note(ctx, primitive);
			return primitive;
		}

		return visitChildren(ctx);
	}

	// --- MODIFIED visitLiteral ---
	@Override
	public Type visitLiteral(NebulaParser.LiteralContext ctx)
	{
		// --- NEW Combined Integer Handling ---
		if (ctx.INTEGER_LITERAL() != null || ctx.HEX_LITERAL() != null || ctx.BIN_LITERAL() != null)
		{
			// Step 1: Analyze the literal's value, default type, and store them.
			resolveIntegerLiteralSemantics(ctx); // This notes default type and BigInteger value

			// Step 2: Retrieve the analyzed info
			Type defaultType = resolvedTypes.get(ctx);
			Object info = resolvedInfo.get(ctx); // Value stored by resolveIntegerLiteralSemantics
			BigInteger literalValue = (info instanceof BigInteger) ? (BigInteger) info : null;

			if (defaultType == null || defaultType instanceof ErrorType || literalValue == null)
			{
				return ErrorType.INSTANCE; // Error already logged during semantic resolution
			}

			Type finalType = defaultType; // Assume default unless context overrides

			// Step 3: Check context (expected type)
			Type currentExpectedType = getExpectedType(); // Use the getter

			if (currentExpectedType != null && currentExpectedType.isInteger() && currentExpectedType instanceof PrimitiveType expectedPrimitive)
			{
				Debug.logDebug("Integer literal context expects type: " + expectedPrimitive.getName());

				// Step 4: Validate value against expected type's range
				if (checkIntegerFits(literalValue, expectedPrimitive))
				{
					// It fits! Use the expected type as the final type for this literal.
					finalType = expectedPrimitive;
					Debug.logDebug("Literal '" + ctx.getText() + "' fits expected type. Using: " + finalType.getName());
					note(ctx, finalType); // Re-note with the more specific contextual type
					// Keep the original BigInteger value noted in resolvedInfo
				}
				else
				{
					// It doesn't fit the expected type. Report error.
					logError(ctx.start, "Integer literal '" + ctx.getText() + "' is out of range for the expected type '" + expectedPrimitive.getName() + "'.");
					note(ctx, ErrorType.INSTANCE); // Note error
					return ErrorType.INSTANCE;
				}
			}
			else
			{
				// No specific integer type expected, or expected type isn't integer. Use the default.
				Debug.logDebug("No specific integer type expected or not an integer. Using default type: " + defaultType.getName() + " for literal '" + ctx.getText() + "'");
				// The default type and value are already noted by resolveIntegerLiteralSemantics.
			}
			return finalType;
		}
		// --- END Combined Integer Handling ---
		if (ctx.FLOAT_LITERAL() != null)
		{
			return PrimitiveType.FLOAT;
		}
		if (ctx.DOUBLE_LITERAL() != null)
		{
			return PrimitiveType.DOUBLE;
		}
		if (ctx.BOOLEAN_LITERAL() != null)
		{
			return PrimitiveType.BOOLEAN;
		}
		if (ctx.CHAR_LITERAL() != null)
		{
			return PrimitiveType.CHAR;
		}
		if (ctx.STRING_LITERAL() != null)
		{
			return this.globalScope.resolve("string").get().getType();
		}
		if (ctx.interpolatedString() != null)
		{
			return visitInterpolatedString(ctx.interpolatedString());
		}
		if (ctx.NULL_T() != null)
		{
			return NullType.INSTANCE;
		}

		// Defensive fallback
		return ErrorType.INSTANCE;
	}

	/**
	 * Checks if a single statement context guarantees an unconditional return.
	 *
	 * @param ctx The StatementContext to check.
	 * @return true if the statement guarantees a return, false otherwise.
	 */
	private boolean statementReturns(NebulaParser.StatementContext ctx)
	{
		// Case 1: The statement is a direct return.
		if (ctx.returnStatement() != null)
		{
			return true;
		}

		// Case 2: The statement is an IF-ELSE block.
		if (ctx.ifStatement() != null)
		{
			NebulaParser.IfStatementContext ifCtx = ctx.ifStatement();

			// For an IF statement to guarantee a return, it MUST have an 'else' clause.
			if (ifCtx.ELSE_KW() == null)
			{
				return false;
			}

			// Check the 'if' branch body (statement at index 0).
			boolean ifBranchReturns = statementReturns(ifCtx.statement(0));

			// Check the 'else' branch body (statement at index 1).
			boolean elseBranchReturns = statementReturns(ifCtx.statement(1));

			// Guarantees return only if BOTH branches guarantee a return.
			return ifBranchReturns && elseBranchReturns;
		}

		// Case 3: The statement is a block { ... } containing other statements.
		if (ctx.block() != null)
		{
			return blockReturns(ctx.block());
		}

		// Case 4: Other statements (e.g., assignment, loop) do not guarantee a return.
		return false;
	}


	/**
	 * Checks if a block guarantees an unconditional return.
	 *
	 * @param ctx The BlockContext to check.
	 * @return true if the block is guaranteed to return, false otherwise.
	 */
	private boolean blockReturns(NebulaParser.BlockContext ctx)
	{
		List<NebulaParser.StatementContext> statements = ctx.statement();

		if (statements.isEmpty())
		{
			return false;
		}

		// Only the very last statement needs to be checked for a guaranteed return.
		var lastStatementCtx = statements.get(statements.size() - 1);

		return statementReturns(lastStatementCtx);
	}

	/**
	 * Performs the final type-check for each argument against the chosen method's parameters.
	 * This is done after resolution to ensure the correct `visitExpecting` calls are made and
	 * the AST is annotated correctly for the single resolved overload.
	 */
	private void typeCheckArguments(MethodSymbol resolvedMethod, List<NebulaParser.ExpressionContext> positionalArgs, Map<String, NebulaParser.ExpressionContext> namedArgs)
	{
		List<ParameterSymbol> formalParams = resolvedMethod.getParameters();

		// Positional arguments
		for (int i = 0; i < positionalArgs.size(); i++)
		{
			Type paramType = formalParams.get(i).getType();
			Type argType = visitExpecting(positionalArgs.get(i), paramType);
			if (!argType.isAssignableTo(paramType))
			{
				logError(positionalArgs.get(i).start, "Argument " + (i + 1) + ": cannot convert '" + argType.getName() + "' to '" + paramType.getName() + "'.");
			}
		}

		// Named arguments
		for (Map.Entry<String, NebulaParser.ExpressionContext> entry : namedArgs.entrySet())
		{
			String argName = entry.getKey();
			NebulaParser.ExpressionContext argExpr = entry.getValue();
			Optional<ParameterSymbol> paramOpt = formalParams.stream().filter(p -> p.getName().equals(argName)).findFirst();
			if (paramOpt.isPresent())
			{
				Type paramType = paramOpt.get().getType();
				Type argType = visitExpecting(argExpr, paramType);
				if (!argType.isAssignableTo(paramType))
				{
					logError(argExpr.start, "Named argument '" + argName + "': cannot convert '" + argType.getName() + "' to '" + paramType.getName() + "'.");
				}
			}
			// No need for an 'else' here, findViableMethods already confirmed the name is valid.
		}
	}

	/**
	 * Visit a subtree while temporarily setting an expected type (restores previous expectedType afterwards).
	 * Accepts any ParseTree so it's flexible (expression subcontexts, unaryExpression, etc).
	 */
	private Type visitExpecting(org.antlr.v4.runtime.tree.ParseTree node, Type expected)
	{
		Type old = expectedType;
		expectedType = expected;
		Type result = visit(node); // base visitor supports visit(ParseTree)
		expectedType = old;
		return result;
	}

	@Override
	public Type visitActualAssignment(NebulaParser.ActualAssignmentContext ctx)
	{
		// The LHS is the first child; it can be a PostfixExpression OR a UnaryExpression that contains a PostfixExpression.
		ParseTree lhsRoot = ctx.getChild(0);
		Type targetType = null;

		// First, try to extract a PostfixExpression if available (either directly or inside a unaryExpression).
		NebulaParser.PostfixExpressionContext lhsPostfix = null;
		if (lhsRoot instanceof NebulaParser.PostfixExpressionContext pf)
		{
			lhsPostfix = pf;
		}
		else if (lhsRoot instanceof NebulaParser.UnaryExpressionContext ue && ue.postfixExpression() != null)
		{
			lhsPostfix = ue.postfixExpression();
		}

		if (lhsPostfix != null)
		{
			// Check for simple member access pattern: ... . ID
			int lastIndex = lhsPostfix.getChildCount() - 1;
			if (lastIndex >= 2 &&
					lhsPostfix.getChild(lastIndex - 1) instanceof TerminalNode dotNode &&
					dotNode.getSymbol().getType() == NebulaLexer.DOT_SYM &&
					lhsPostfix.getChild(lastIndex) instanceof TerminalNode memberIdNode &&
					((TerminalNode) memberIdNode).getSymbol().getType() == NebulaLexer.ID)
			{
				// --- Member assignment (e.g. p1.name = ...) ---
				// 1) Resolve the object's type (the thing before the dot)
				Type targetObjectType = visit(lhsPostfix.primary());

				if (targetObjectType instanceof ErrorType || targetObjectType.getClassSymbol() == null)
				{
					return ErrorType.INSTANCE; // error already reported
				}

				ClassSymbol owningClass = targetObjectType.getClassSymbol();
				String memberName = memberIdNode.getText();

				// 2) Resolve the member symbol inside the owning class
				Optional<Symbol> memberOpt = owningClass.resolve(memberName);
				if (memberOpt.isEmpty() || !(memberOpt.get() instanceof VariableSymbol memberSymbol))
				{
					logError(memberIdNode.getSymbol(), "Cannot find field or property '" + memberName + "' in type '" + owningClass.getName() + "'.");
					return ErrorType.INSTANCE;
				}

				// 3.A Visibility: if member is private and caller is not the owning class --> error
				if (!memberSymbol.isPublic() && owningClass != currentClass)
				{
					logError(memberIdNode.getSymbol(), "Cannot access private member '" + memberName + "' from outside its class.");
					return ErrorType.INSTANCE;
				}

				// 3.B Const / immutability check
				if (memberSymbol.isConst())
				{
					logError(memberIdNode.getSymbol(), "Cannot assign to constant field/property '" + memberName + "'.");
					return ErrorType.INSTANCE;
				}

				// 3.C Property setter existence & accessibility
				String setterName = "set_" + memberName;
				List<MethodSymbol> setterCandidates = owningClass.resolveMethods(setterName)
						.stream()
						.filter(ms -> ms.getParameters().size() == 1)
						.collect(Collectors.toList());

				if (setterCandidates.isEmpty())
				{
					logError(memberIdNode.getSymbol(), "Cannot assign to read-only property '" + memberName + "'. Setter not defined.");
					return ErrorType.INSTANCE;
				}

				// The setter must also be accessible (public) or be callable from the same class
				boolean setterAccessible = setterCandidates.stream().anyMatch(ms -> ms.isPublic() || owningClass == currentClass);
				if (!setterAccessible)
				{
					logError(memberIdNode.getSymbol(), "Cannot assign to property '" + memberName + "': setter is not accessible from here.");
					return ErrorType.INSTANCE;
				}

				// 4) Link and return the member type
				this.resolvedSymbols.put(memberIdNode, memberSymbol);
				targetType = memberSymbol.getType();
			}
			else
			{
				// Not a direct .ID member pattern — fall back to general postfix visit.
				targetType = visit(lhsPostfix);
			}
		}
		else
		{
			// No postfix expression available — just visit the LHS normally.
			targetType = visit(lhsRoot);
		}

		if (targetType instanceof ErrorType)
		{
			return ErrorType.INSTANCE;
		}

		// --- Final assignment type check ---
		Type rhsType = visit(ctx.expression());
		if (!rhsType.isAssignableTo(targetType))
		{
			logError(ctx.assignmentOperator().start, "Cannot assign '" + rhsType.getName() + "' to '" + targetType.getName() + "'.");
			return ErrorType.INSTANCE;
		}

		return targetType;
	}

	/**
	 * A helper to calculate a numeric "cost" for how well a method's parameters
	 * match the provided arguments. Lower is better.
	 * Cost is calculated as:
	 * - 0 for an exact type match.
	 * - 1 for a match requiring a widening conversion.
	 * - 2 for other conversions (e.g., boxing, implicit reference casts)
	 * - Infinity if a conversion is not possible.
	 */
	public int calculateArgumentMatchCost(MethodSymbol candidate, List<NebulaParser.ExpressionContext> positionalArgs, Map<String, NebulaParser.ExpressionContext> namedArgs)
	{
		int totalCost = 0;
		List<ParameterSymbol> params = candidate.getParameters();

		for (int i = 0; i < positionalArgs.size(); i++)
		{
			Type paramType = params.get(i).getType();

			// --- FIX ---
			// Visit the argument expression *while telling it to expect the parameter type*.
			// This propagates context into nested calls like getPi().
			Type argType = visitExpecting(positionalArgs.get(i), paramType);

			if (!argType.isAssignableTo(paramType))
			{
				return Integer.MAX_VALUE;
			}

			// --- NEW COST LOGIC ---
			int cost = 2; // 3. Boxing/Other match (default)
			if (PrimitiveType.areEquivalent(argType, paramType))
			{
				cost = 0; // 1. Exact match (int -> int)
			}
			else if (paramType.isNumeric() && argType.isNumeric())
			{
				cost = 1; // 2. Widening match (int -> double)
			}

			Debug.logDebug("      Positional Arg " + i + ": argType=" + argType.getName() + ", paramType=" + paramType.getName() + ", cost=" + cost);
			totalCost += cost;
		}

		// (Do the same for namedArgs)
		for (Map.Entry<String, NebulaParser.ExpressionContext> entry : namedArgs.entrySet())
		{
			Optional<ParameterSymbol> paramOpt = params.stream().filter(p -> p.getName().equals(entry.getKey())).findFirst();
			Type paramType = paramOpt.get().getType();

			// --- FIX ---
			// Also use visitExpecting for named arguments.
			Type argType = visitExpecting(entry.getValue(), paramType);

			if (!argType.isAssignableTo(paramType))
			{
				return Integer.MAX_VALUE;
			}

			// --- NEW COST LOGIC ---
			int cost = 2; // 3. Boxing/Other match (default)
			if (PrimitiveType.areEquivalent(argType, paramType))
			{
				cost = 0;
			}
			else if (paramType.isNumeric() && argType.isNumeric())
			{
				cost = 1;
			}

			Debug.logDebug("      Named Arg '" + entry.getKey() + "': argType=" + argType.getName() + ", paramType=" + paramType.getName() + ", cost=" + cost);
			totalCost += cost;
		}
		return totalCost;
	}

	/**
	 * Checks if arguments are assignable to a method's parameters.
	 */
	public boolean areArgumentsAssignableTo(MethodSymbol m, List<NebulaParser.ExpressionContext> positionalArgs, Map<String, NebulaParser.ExpressionContext> namedArgs)
	{
		List<ParameterSymbol> params = m.getParameters();

		for (int i = 0; i < positionalArgs.size(); i++)
		{
			Type paramType = params.get(i).getType();

			// --- FIX ---
			// Visit the argument expression *while telling it to expect the parameter type*.
			Type argType = visitExpecting(positionalArgs.get(i), paramType);
			boolean assignable = argType.isAssignableTo(paramType);

			Debug.logDebug("      Arg " + i + ": argType=" + argType.getName() + ", paramType=" + paramType.getName() + ", assignable=" + assignable);

			if (!assignable)
			{
				return false;
			}
		}

		for (Map.Entry<String, NebulaParser.ExpressionContext> e : namedArgs.entrySet())
		{
			ParameterSymbol p = params.stream()
					.filter(pp -> pp.getName().equals(e.getKey()))
					.findFirst().orElse(null);
			if (p == null)
			{
				return false;
			}

			Type paramType = p.getType();

			// --- FIX ---
			// Also use visitExpecting for named arguments.
			Type argType = visitExpecting(e.getValue(), paramType);
			boolean assignable = argType.isAssignableTo(paramType);

			Debug.logDebug("      Named Arg '" + e.getKey() + "': argType=" + argType.getName() + ", paramType=" + paramType.getName() + ", assignable=" + assignable);

			if (!assignable)
			{
				return false;
			}
		}
		return true;
	}

	// --- REFINED resolveIntegerLiteralSemantics ---
	// Return type changed to void. It now *notes* the default type and value.
	private void resolveIntegerLiteralSemantics(NebulaParser.LiteralContext ctx)
	{
		String raw = null;
		Token literalToken = null; // Store the token for better error reporting

		if (ctx.INTEGER_LITERAL() != null)
		{
			raw = ctx.INTEGER_LITERAL().getText();
			literalToken = ctx.INTEGER_LITERAL().getSymbol();
		}
		else if (ctx.HEX_LITERAL() != null)
		{
			raw = ctx.HEX_LITERAL().getText();
			literalToken = ctx.HEX_LITERAL().getSymbol();
		}
		else if (ctx.BIN_LITERAL() != null)
		{
			raw = ctx.BIN_LITERAL().getText();
			literalToken = ctx.BIN_LITERAL().getSymbol();
		}
		else
		{
			return; // Should not happen if called correctly
		}

		String originalText = raw;

		// --- Separate sign ---
		boolean negative = false;
		if (raw.startsWith("-"))
		{
			negative = true;
			raw = raw.substring(1);
		}

		// --- Extract suffix ---
		int suffixStart = raw.length();
		while (suffixStart > 0 && Character.isLetter(raw.charAt(suffixStart - 1)))
		{
			suffixStart--;
		}
		String suffix = raw.substring(suffixStart);
		String numericPart = raw.substring(0, suffixStart);
		String suffixLower = suffix.toLowerCase(Locale.ROOT);

		// Check specifically for signed byte suffix 'sb'
		boolean hasSignedByte = suffixLower.contains("s") && suffixLower.contains("b");
		boolean hasByte = suffixLower.contains("b") && !suffixLower.contains("s");
		boolean hasShort = suffixLower.contains("s");
		boolean hasLong = suffixLower.contains("l");
		boolean hasUnsigned = suffixLower.contains("u");

		// --- Validate Suffix ---
		for (int i = 0; i < suffixLower.length(); ++i)
		{
			char c = suffixLower.charAt(i);
			if (c != 'b' && c != 's' && c != 'l' && c != 'u')
			{
				logError(literalToken, "Unknown integer literal suffix '" + c + "' in: " + originalText);
				note(ctx, ErrorType.INSTANCE);
				return;
			}
		}
		if (hasByte && hasShort)
		{ /* ... error ... */
			return;
		}
		// Potentially add more suffix conflict checks (e.g., 'bl' vs 'sl') if needed.

		// --- Parse Value ---
		numericPart = numericPart.replace("_", "");
		int base = 10;
		if (numericPart.startsWith("0x") || numericPart.startsWith("0X"))
		{ /* ... base = 16 ... */ }
		else if (numericPart.startsWith("0b") || numericPart.startsWith("0B"))
		{ /* ... base = 2 ... */ }

		if (numericPart.isEmpty())
		{ /* ... error ... */
			return;
		}

		BigInteger bi;
		try
		{
			bi = new BigInteger(numericPart, base);
			if (negative)
			{
				bi = bi.negate();
			}
		}
		catch (NumberFormatException ex)
		{ /* ... error ... */
			return;
		}

		// --- Determine Type Based on Suffix AND Value Range ---
		PrimitiveType determinedType = null; // Default to error

		// 1. Check explicit suffixes first
		if (hasSignedByte)
		{
			determinedType = PrimitiveType.SBYTE;
			if(!checkIntegerFits(bi, determinedType))
			{
				// Range error
				return;
			}
		}
		if (hasByte)
		{
			determinedType = PrimitiveType.BYTE;
			if (!checkIntegerFits(bi, determinedType))
			{ /* ... range error ... */
				return;
			}
		}
		else if (hasShort)
		{
			determinedType = hasUnsigned ? PrimitiveType.USHORT : PrimitiveType.SHORT;
			if (!checkIntegerFits(bi, determinedType))
			{ /* ... range error ... */
				return;
			}
		}
		else if (hasLong)
		{
			determinedType = hasUnsigned ? PrimitiveType.ULONG : PrimitiveType.LONG;
			if (!checkIntegerFits(bi, determinedType))
			{ /* ... range error ... */
				return;
			}
		}
		else if (hasUnsigned)
		{ // 'u' suffix without 'l', 's', 'b'
			if (checkIntegerFits(bi, PrimitiveType.UINT))
			{
				determinedType = PrimitiveType.UINT;
			}
			else if (checkIntegerFits(bi, PrimitiveType.ULONG))
			{
				determinedType = PrimitiveType.ULONG;
			}
			else
			{ /* ... range error for unsigned ... */
				return;
			}
		}
		else
		{ // 2. No suffix - determine smallest fitting *signed* type
			if (checkIntegerFits(bi, PrimitiveType.INT))
			{
				determinedType = PrimitiveType.INT;
			}
			else if (checkIntegerFits(bi, PrimitiveType.LONG))
			{
				determinedType = PrimitiveType.LONG;
			}
			else
			{
				// If it doesn't fit signed long, check if it fits unsigned long
				if (checkIntegerFits(bi, PrimitiveType.ULONG))
				{
					logError(literalToken, "Integer literal '" + originalText + "' is too large for signed types. Use 'ul' suffix for unsigned long.");
				}
				else
				{
					logError(literalToken, "Integer literal '" + originalText + "' out of range for all built-in integer types.");
				}
				note(ctx, ErrorType.INSTANCE);
				return;
			}
		}

		// --- Note the results ---
		note(ctx, determinedType); // Note the determined *default* type
		noteInfo(ctx, bi);         // Note the BigInteger value
	}

	// --- NEW HELPER for Range Checking ---
	private boolean checkIntegerFits(BigInteger value, PrimitiveType targetType)
	{
		if (!targetType.isInteger())
		{
			return false; // Safety check
		}

		Optional<BigInteger> minOpt = targetType.getMinValue();
		Optional<BigInteger> maxOpt = targetType.getMaxValue();

		if (minOpt.isEmpty() || maxOpt.isEmpty())
		{
			// Should not happen for valid integer PrimitiveTypes after adding constants
			Debug.logWarning("Range check failed: MIN/MAX not defined for type " + targetType.getName());
			return false;
		}

		BigInteger min = minOpt.get();
		BigInteger max = maxOpt.get();

		return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
	}

	/**
	 * Provides access to the contextual expected type for the overload resolution logic.
	 */
	public Type getExpectedType()
	{
		return expectedType;
	}
}