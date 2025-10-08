package org.lokray.semantic;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.semantic.symbol.*;
import org.lokray.semantic.symbol.Symbol;
import org.lokray.semantic.type.*;
import org.lokray.util.Debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * This visitor performs the final pass, type-checking all expressions and resolving symbols.
 */
public class TypeCheckVisitor extends NebulaParserBaseVisitor<Type>
{

	private final Scope globalScope;
	private Scope currentScope;
	private ClassSymbol currentClass;
	private MethodSymbol currentMethod;
	private boolean hasErrors = false;

	private final Map<String, ClassSymbol> declaredClasses;
	private final Map<ParseTree, Symbol> resolvedSymbols;
	private final Map<ParseTree, Type> resolvedTypes;

	public TypeCheckVisitor(Scope globalScope, Map<String, ClassSymbol> declaredClasses, Map<ParseTree, Symbol> symbols, Map<ParseTree, Type> types)
	{
		this.globalScope = globalScope;
		this.currentScope = globalScope;
		this.declaredClasses = declaredClasses;
		this.resolvedSymbols = symbols;
		this.resolvedTypes = types;
	}

	public boolean hasErrors()
	{
		return hasErrors;
	}

	private void logError(org.antlr.v4.runtime.Token token, String msg)
	{
		// New Format: [Semantic Error] current class name - line:char - msg
		// Get the current class name or an empty string if not inside a class
		String className = currentClass != null ? currentClass.getName() : "";

		// Format the error string
		String err = String.format("[Semantic Error] %s - line %d:%d - %s", className, token.getLine(), token.getCharPositionInLine() + 1, msg);

		Debug.logError(err);
		hasErrors = true;
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

		// Resolve the specific constructor symbol
		Optional<MethodSymbol> ctorOpt = ((ClassSymbol) currentScope).resolveMethod(ctx.ID().getText(), paramTypes);
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
        List<Type> paramTypes = new ArrayList<>();
        if (ctx.parameterList() != null)
        {
            for (var pCtx : ctx.parameterList().parameter())
            {
                paramTypes.add(resolveType(pCtx.type()));
            }
        }
        Optional<MethodSymbol> methodOpt = ((ClassSymbol) currentScope).resolveMethod(ctx.ID().getText(), paramTypes);
        if (methodOpt.isEmpty())
        {
            logError(ctx.ID().getSymbol(), "Internal error: Method symbol not found during type checking pass.");
            return null;
        }
        currentMethod = methodOpt.get();
        currentScope = currentMethod;
        if (ctx.parameterList() != null)
        {
            for (var pCtx : ctx.parameterList().parameter())
            {
                visit(pCtx);
            }
        }

        // 1. Visit the method body (block) to perform type checking on its contents
        visit(ctx.block());

        // 2. Check for required return statement only for non-void, non-native methods
        if (currentMethod.getType() != PrimitiveType.VOID && !currentMethod.isNative())
        {
            // Check if the block has an unconditionally reachable return statement
            if (!blockReturns(ctx.block()))
            {
                logError(ctx.block().start, "Method must return a result of type '" + currentMethod.getType().getName() + "'. Not all code paths return a value.");
            }
        }

        currentScope = currentScope.getEnclosingScope();
        currentMethod = null;
        return null;
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

	// --- Statements ---
	@Override
	public Type visitVariableDeclaration(NebulaParser.VariableDeclarationContext ctx)
	{
		Type declaredType = resolveType(ctx.type());
		for (var declarator : ctx.variableDeclarator())
		{
			String varName = declarator.ID().getText();
			if (currentScope.resolveLocally(varName).isPresent())
			{
				logError(declarator.ID().getSymbol(), "Variable '" + varName + "' is already defined in this scope.");
				continue;
			}
			if (declarator.expression() != null)
			{
				Type initializerType;
				NebulaParser.ExpressionContext initializerCtx = declarator.expression();

				// Check if the initializer is an array initializer
				if (initializerCtx.getText().startsWith("{")) // A simple but effective check
				{
					// This is the new, context-aware path
					initializerType = visitArrayInitializerWithContext(
							initializerCtx.assignmentExpression()
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
									.arrayInitializer(),
							declaredType
					);
				}
				else
				{
					// The original path for all other expression types
					initializerType = visit(initializerCtx);
				}

				if (!initializerType.isAssignableTo(declaredType))
				{
					logError(declarator.expression().start, "Incompatible types: cannot assign '" + initializerType.getName() + "' to '" + declaredType.getName() + "'.");
				}
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
	public Type visitForStatement(NebulaParser.ForStatementContext ctx)
	{
		Scope forScope = new Scope(currentScope);
		currentScope = forScope;

		if (ctx.simplifiedForClause() != null)
		{
			NebulaParser.SimplifiedForClauseContext simplifiedCtx = ctx.simplifiedForClause();
			String varName = simplifiedCtx.ID().getText();

			// The loop variable is implicitly an integer. Let's use 'int' as the default.
			Type intType = globalScope.resolve("int").orElseThrow().getType();

			VariableSymbol loopVar = new VariableSymbol(varName, intType, false, true, false);
			currentScope.define(loopVar);
			note(simplifiedCtx.ID(), loopVar);
			note(simplifiedCtx.ID(), intType);

			for (NebulaParser.ExpressionContext exprCtx : simplifiedCtx.expression())
			{
				Type exprType = visit(exprCtx);
				// Allow any integer type (int, long, etc.), not just 'int'.
				if (!exprType.isInteger())
				{
					logError(exprCtx.start, "Incompatible types in for loop clause: expected an integer expression, but found '" + exprType.getName() + "'.");
				}
			}

			visit(ctx.statement());

		}
		else
		{ // Traditional for-loop
			int expressionIndex = 0;
			// Handle initializer
			if (ctx.variableDeclaration() != null)
			{
				visit(ctx.variableDeclaration());
			}
			else if (!ctx.expression().isEmpty() && ctx.SEMI_SYM(0) != null)
			{
				// Make sure we don't misinterpret the condition as the initializer
				if (ctx.expression().get(0).getStart().getTokenIndex() < ctx.SEMI_SYM(0).getSymbol().getTokenIndex())
				{
					visit(ctx.expression(expressionIndex++));
				}
			}

			// Handle condition
			if (ctx.SEMI_SYM(0) != null && ctx.SEMI_SYM(1) != null)
			{
				if (ctx.expression().size() > expressionIndex)
				{
					if (ctx.expression().get(expressionIndex).getStart().getTokenIndex() > ctx.SEMI_SYM(0).getSymbol().getTokenIndex())
					{
						Type conditionType = visit(ctx.expression(expressionIndex++));
						Type boolType = globalScope.resolve("bool").orElseThrow().getType();
						if (!conditionType.isAssignableTo(boolType))
						{
							logError(ctx.expression(expressionIndex - 1).start, "For loop condition must be of type 'bool', but found '" + conditionType.getName() + "'.");
						}
					}
				}
			}


			// Handle update
			if (ctx.expression().size() > expressionIndex)
			{
				visit(ctx.expression(expressionIndex));
			}

			// Visit the loop body
			visit(ctx.statement());
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
            actualReturnType = visit(ctx.expression());
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

        if (currentSymbol == null && currentType instanceof Symbol s)
            currentSymbol = s;

        for (int i = 0; i < ctx.ID().size(); i++)
        {
            if (currentType instanceof ErrorType)
                return ErrorType.INSTANCE;

            String memberName = ctx.ID(i).getText();
            Scope scopeToSearch = null;

            // Prefer the type's scope
            if (currentType instanceof Scope s)
                scopeToSearch = s;
            else if (currentType instanceof ClassType ct)
                scopeToSearch = ct.getClassSymbol();
            else if (currentType.isArray() || (currentType instanceof ClassType && currentType.getName().equals("string")))
                Debug.logWarning("[PENDING ERROR] To implement: arrays and string's size property");
            else if (currentSymbol instanceof Scope s)
                scopeToSearch = s;

            if (scopeToSearch == null)
            {
                logError(ctx.DOT_SYM(i).getSymbol(),
                        "Cannot access member '" + memberName + "' on type '" + currentType.getName() + "'. " +
                                "It is not a class, namespace, or tuple.");
                return ErrorType.INSTANCE;
            }

            Optional<Symbol> member = scopeToSearch.resolve(memberName);
            if (member.isEmpty())
            {
                logError(ctx.ID(i).getSymbol(),
                        "Cannot resolve member '" + memberName + "' in type '" + scopeToSearch.getName() + "'.");
                return ErrorType.INSTANCE;
            }

            currentSymbol = member.get();
            if (currentSymbol instanceof AliasSymbol alias)
                currentSymbol = alias.getTargetSymbol();

            currentType = currentSymbol.getType();
            if (currentType instanceof UnresolvedType unresolved)
                currentType = resolveUnresolvedType(unresolved, ctx.ID(i).getSymbol());
        }

        if (currentSymbol != null) note(ctx, currentSymbol);
        if (currentType != null) note(ctx, currentType);
        return currentType;
    }


    @Override
    public Type visitLogicalOrExpression(NebulaParser.LogicalOrExpressionContext ctx) {
        if (ctx.logicalAndExpression().size() > 1) {
            Type left = visit(ctx.logicalAndExpression(0));
            Type right = visit(ctx.logicalAndExpression(1));

            if (!left.isBoolean() || !right.isBoolean()) {
                logError(ctx.LOG_OR_OP(0).getSymbol(), "Logical operator '||' can only be applied to boolean types.");
                return ErrorType.INSTANCE;
            }
            note(ctx, PrimitiveType.BOOLEAN);
            return PrimitiveType.BOOLEAN;
        }
        return visitChildren(ctx);
    }

    @Override
    public Type visitLogicalAndExpression(NebulaParser.LogicalAndExpressionContext ctx) {
        if (ctx.bitwiseOrExpression().size() > 1) {
            Type left = visit(ctx.bitwiseOrExpression(0));
            Type right = visit(ctx.bitwiseOrExpression(1));

            if (!left.isBoolean() || !right.isBoolean()) {
                logError(ctx.LOG_AND_OP(0).getSymbol(), "Logical operator '&&' can only be applied to boolean types.");
                return ErrorType.INSTANCE;
            }
            note(ctx, PrimitiveType.BOOLEAN);
            return PrimitiveType.BOOLEAN;
        }
        return visitChildren(ctx);
    }

    @Override
    public Type visitEqualityExpression(NebulaParser.EqualityExpressionContext ctx) {
        if (ctx.relationalExpression().size() > 1) {
            Type left = visit(ctx.relationalExpression(0));
            Type right = visit(ctx.relationalExpression(1));

            // Basic check: Allow comparison if types are assignable to each other.
            // A more robust implementation would check for common supertypes or interfaces.
            if (!left.isAssignableTo(right) && !right.isAssignableTo(left)) {
                logError(ctx.EQUAL_EQUAL_SYM(0).getSymbol(), "Operator cannot be applied to '" + left.getName() + "' and '" + right.getName() + "'.");
                return ErrorType.INSTANCE;
            }
            note(ctx, PrimitiveType.BOOLEAN);
            return PrimitiveType.BOOLEAN;
        }
        return visitChildren(ctx);
    }

    @Override
    public Type visitRelationalExpression(NebulaParser.RelationalExpressionContext ctx) {
        if (ctx.shiftExpression().size() > 1) {
            Type left = visit(ctx.shiftExpression(0));
            Type right = visit(ctx.shiftExpression(1));

            // Relational operators typically apply only to numeric types.
            if (!left.isNumeric() || !right.isNumeric()) {
                logError(ctx.getChild(1).getPayload() instanceof Token ? (Token) ctx.getChild(1).getPayload() : ctx.start,
                        "Relational operator cannot be applied to non-numeric types '" + left.getName() + "' and '" + right.getName() + "'.");
                return ErrorType.INSTANCE;
            }
            note(ctx, PrimitiveType.BOOLEAN);
            return PrimitiveType.BOOLEAN;
        }
        return visitChildren(ctx);
    }

    @Override
    public Type visitAdditiveExpression(NebulaParser.AdditiveExpressionContext ctx) {
        if (ctx.multiplicativeExpression().size() > 1) {
            Type left = visit(ctx.multiplicativeExpression(0));
            Type right = visit(ctx.multiplicativeExpression(1));

            // For simplicity, we'll require both to be numeric.
            // A real implementation would handle string concatenation ('+').
            if (!left.isNumeric() || !right.isNumeric()) {
                logError(ctx.getChild(1).getPayload() instanceof Token ? (Token) ctx.getChild(1).getPayload() : ctx.start,
                        "Arithmetic operator cannot be applied to non-numeric types '" + left.getName() + "' and '" + right.getName() + "'.");
                return ErrorType.INSTANCE;
            }
            // Simple type promotion: if either is double, result is double, etc.
            Type resultType = Type.getWiderType(left, right);
            note(ctx, resultType);
            return resultType;
        }
        return visitChildren(ctx);
    }

    @Override
    public Type visitMultiplicativeExpression(NebulaParser.MultiplicativeExpressionContext ctx) {
        if (ctx.powerExpression().size() > 1) {
            Type left = visit(ctx.powerExpression(0));
            Type right = visit(ctx.powerExpression(1));

            if (!left.isNumeric() || !right.isNumeric()) {
                logError(ctx.getChild(1).getPayload() instanceof Token ? (Token) ctx.getChild(1).getPayload() : ctx.start,
                        "Arithmetic operator cannot be applied to non-numeric types '" + left.getName() + "' and '" + right.getName() + "'.");
                return ErrorType.INSTANCE;
            }
            Type resultType = Type.getWiderType(left, right);
            note(ctx, resultType);
            return resultType;
        }
        return visitChildren(ctx);
    }


    @Override
	public Type visitCastExpression(NebulaParser.CastExpressionContext ctx)
	{
		// Resolve the target type from the grammar rule.
		Type targetType = resolveType(ctx.type());

		// Visit the expression being cast to find its type.
		// NOTE: The grammar is `castExpression: '(' type ')' unaryExpression;`
		// so we visit the unaryExpression part.
		Type originalType = visit(ctx.unaryExpression());

		// Propagate errors
		if (targetType instanceof ErrorType || originalType instanceof ErrorType)
		{
			note(ctx, ErrorType.INSTANCE);
			return ErrorType.INSTANCE;
		}

		// Define valid casting rules.
		// Allow casting between any two numeric types.
		// Also allow casting from a class type to another (for downcasting/upcasting, though without inheritance check it's very basic).
		boolean isNumericCast = originalType.isNumeric() && targetType.isNumeric();
		boolean isReferenceCast = originalType.isReferenceType() && targetType.isReferenceType();

		if (!isNumericCast && !isReferenceCast)
		{
			// A more advanced compiler would check for user-defined conversion operators here.
			logError(ctx.start, "Cannot cast from '" + originalType.getName() + "' to '" + targetType.getName() + "'.");
			note(ctx, ErrorType.INSTANCE);
			return ErrorType.INSTANCE;
		}

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
		if (ctx.ID() != null)
		{
			String name = ctx.ID().getText();

			if (name.equals("this"))
			{
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
				// 'this' refers to the current class instance.
				Type thisType = currentClass.getType();
				note(ctx, thisType);
				note(ctx, new VariableSymbol("this", thisType, false, false, true)); // Create a symbol for 'this'
				return thisType;
			}

			Optional<Symbol> symbolOpt = currentScope.resolve(name);
			if (symbolOpt.isEmpty())
			{
				logError(ctx.ID().getSymbol(), "Cannot find symbol '" + name + "'.");
				note(ctx, ErrorType.INSTANCE);
				return ErrorType.INSTANCE;
			}

			Symbol symbol = symbolOpt.get();
			if (symbol instanceof AliasSymbol)
			{
				symbol = ((AliasSymbol) symbol).getTargetSymbol();
			}

			if (name.equals("this"))
			{
				if (currentClass == null)
				{
					logError(ctx.ID().getSymbol(), "'this' cannot be used outside of an instance context.");
					return ErrorType.INSTANCE;
				}
				symbol = new VariableSymbol("this", currentClass.getType(), false, false, true);
			}

			note(ctx, symbol);
			Type type = symbol.getType();
			if (type instanceof UnresolvedType)
			{
				type = resolveUnresolvedType((UnresolvedType) type, ctx.ID().getSymbol());
			}
			note(ctx, type);
			return type;
		}
		if (ctx.literal() != null)
		{
			Type literalType = visit(ctx.literal());
			note(ctx, literalType);
			return literalType;
		}
		if (ctx.expression() != null)
		{
			Type exprType = visit(ctx.expression());
			note(ctx, exprType);
			return exprType;
		}
		return visitChildren(ctx);
	}

	@Override
	public Type visitLiteral(NebulaParser.LiteralContext ctx)
	{
		if (ctx.INTEGER_LITERAL() != null)
		{
			return PrimitiveType.INT;
		}
		if (ctx.LONG_LITERAL() != null)
		{
			return PrimitiveType.LONG;
		}
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
		if (ctx.STRING_LITERAL() != null || ctx.interpolatedString() != null)
		{
			return this.globalScope.resolve("string").get().getType();
		}
		if (ctx.NULL_T() != null)
		{
			return NullType.INSTANCE;
		}

		return ErrorType.INSTANCE;
	}

	/**
	 * Visits an array initializer with the context of the type it's being assigned to.
	 *
	 * @param ctx          The ArrayInitializerContext from the parse tree.
	 * @param expectedType The type of the variable being declared (e.g., int[]).
	 * @return The expectedType if all elements are assignable, otherwise ErrorType.
	 */
	private Type visitArrayInitializerWithContext(NebulaParser.ArrayInitializerContext ctx, Type expectedType)
	{
		// 1. Ensure the target type is actually an array
		if (!expectedType.isArray())
		{
			logError(ctx.start, "Array initializer can only be used to initialize an array.");
			return ErrorType.INSTANCE;
		}

		Type expectedElementType = ((ArrayType) expectedType).getElementType();

		// 2. Visit each element in the initializer
		for (NebulaParser.ArrayElementContext elementCtx : ctx.arrayElement())
		{
			// An element can be another expression or a nested array initializer
			if (elementCtx.expression() != null)
			{
				Type actualElementType = visit(elementCtx.expression());

				// 3. Check if the element's type can be assigned to the array's element type
				if (!actualElementType.isAssignableTo(expectedElementType))
				{
					logError(elementCtx.expression().start, "Incompatible types in array initializer: cannot convert '" + actualElementType.getName() + "' to '" + expectedElementType.getName() + "'.");
					// We can return early, or continue to find all errors in the initializer
				}
			}
			else if (elementCtx.arrayInitializer() != null)
			{
				// Handle nested initializers for multi-dimensional arrays, e.g., int[][] x = {{1}, {2}};
				visitArrayInitializerWithContext(elementCtx.arrayInitializer(), expectedElementType);
			}
		}

		// 4. If all checks pass, the type of the initializer is the expected array type
		note(ctx, expectedType);
		return expectedType;
	}

    /**
     * Checks if a single statement context guarantees an unconditional return.
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
}
