// File: src/main/java/org/lokray/semantic/SemanticAnalyzer.java
package org.lokray.semantic;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.semantic.type.*;
import org.lokray.util.Debug;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class SemanticAnalyzer
{
	private final Scope globalScope = new Scope(null);
	private boolean hasErrors = false;
	private final Map<ParseTree, Symbol> resolvedSymbols = new HashMap<>();
	private final Map<ParseTree, Type> resolvedTypes = new HashMap<>();
	private final Map<String, ClassSymbol> declaredClasses = new LinkedHashMap<>();

	public SemanticAnalyzer(Path ndkLib)
	{
		// Define all primitive types first
		BuiltInTypeLoader.definePrimitives(globalScope);

		// Preload NDK library symbols into the global scope and declaredClasses map
		if (ndkLib != null && Files.exists(ndkLib))
		{
			try
			{
				NebulaLibLoader.loadLibrary(ndkLib, globalScope, declaredClasses);
				// After loading, ensure all NDK types are properly linked
				linkNdkSymbols();

				// FIX: After NDK load, create a global alias for 'string' -> 'nebula.core.String'
				if (declaredClasses.containsKey("nebula.core.String"))
				{
					Symbol stringSymbol = declaredClasses.get("nebula.core.String");
					globalScope.define(new AliasSymbol("string", stringSymbol));
				}

			}
			catch (Exception e)
			{
				Debug.logWarning("Failed to load ndk library: " + e.getMessage());
				//e.printStackTrace(); // Uncomment for debugging
			}
		}
	}

	public SemanticAnalyzer()
	{
		this(null);
	}

	public boolean analyze(ParseTree tree)
	{
		// Pass 1 & 2: Discover all types, handle imports/aliases, and define all members.
		SymbolTableBuilder defVisitor = new SymbolTableBuilder(globalScope, declaredClasses);
		defVisitor.visit(tree);

		this.hasErrors = defVisitor.hasErrors();
		if (hasErrors)
		{
			return false;
		}

		// Pass 3: Type Checking and Resolution for method bodies and initializers
		TypeCheckVisitor refVisitor = new TypeCheckVisitor(globalScope, declaredClasses, resolvedSymbols, resolvedTypes);
		refVisitor.visit(tree);
		this.hasErrors = refVisitor.hasErrors();

		return !hasErrors;
	}

	private void linkNdkSymbols()
	{
		for (ClassSymbol cs : declaredClasses.values())
		{
			if (!cs.isNative())
			{
				continue;
			}

			// Link Field Types
			cs.getSymbols().values().stream()
					.filter(sym -> sym instanceof VariableSymbol)
					.map(sym -> (VariableSymbol) sym)
					.forEach(vs ->
					{
						if (vs.getType() instanceof UnresolvedType)
						{
							vs.setType(resolveTypeByName(vs.getType().getName()));
						}
					});

			// Link Method Return and Parameter Types
			for (List<MethodSymbol> overloads : cs.getMethodsByName().values())
			{
				for (MethodSymbol ms : overloads)
				{
					// Link return type
					if (ms.getType() instanceof UnresolvedType)
					{
						ms.setReturnType(resolveTypeByName(ms.getType().getName()));
					}
					// Link parameter types
					List<Type> realParamTypes = new ArrayList<>();
					for (Type paramType : ms.getParameterTypes())
					{
						if (paramType instanceof UnresolvedType)
						{
							realParamTypes.add(resolveTypeByName(paramType.getName()));
						}
						else
						{
							realParamTypes.add(paramType);
						}
					}
					ms.setParameterTypes(realParamTypes);
				}
			}
		}
	}

	private Type resolveTypeByName(String name)
	{
		Optional<Symbol> primitive = globalScope.resolveLocally(name);
		if (primitive.isPresent() && primitive.get().getType() instanceof PrimitiveType)
		{
			return primitive.get().getType();
		}
		if (declaredClasses.containsKey(name))
		{
			return declaredClasses.get(name).getType();
		}
		for (String fqn : declaredClasses.keySet())
		{
			if (fqn.endsWith("." + name))
			{
				return declaredClasses.get(fqn).getType();
			}
		}
		return new UnresolvedType(name);
	}


	public Optional<Symbol> getResolvedSymbol(ParseTree node)
	{
		return Optional.ofNullable(resolvedSymbols.get(node));
	}

	public Optional<Type> getResolvedType(ParseTree node)
	{
		return Optional.ofNullable(resolvedTypes.get(node));
	}

	/**
	 * This visitor now handles Pass 1 and 2:
	 * 1. Handles imports and aliases, modifying the scope.
	 * 2. Discovers all types and defines member signatures.
	 */
	private static class SymbolTableBuilder extends NebulaParserBaseVisitor<Void>
	{

		private final Scope root;
		private Scope currentScope;
		private ClassSymbol currentClass;
		private boolean hasErrors = false;
		private final Map<String, ClassSymbol> declaredClasses;

		public SymbolTableBuilder(Scope root, Map<String, ClassSymbol> declaredClasses)
		{
			this.root = root;
			this.currentScope = root;
			this.declaredClasses = declaredClasses;
		}

		public boolean hasErrors()
		{
			return hasErrors;
		}

		private void logError(Token token, String msg)
		{
			String err = String.format("Semantic Error at line %d:%d - %s",
					token.getLine(), token.getCharPositionInLine() + 1, msg);
			Debug.logError(err);
			hasErrors = true;
		}

		private String getFqn(NebulaParser.QualifiedNameContext ctx)
		{
			return ctx.getText();
		}

		private Type resolveTypeFromCtx(NebulaParser.TypeContext ctx)
		{
			if (ctx == null)
			{
				return ErrorType.INSTANCE;
			}
			String baseTypeName;
			if (ctx.primitiveType() != null)
			{
				baseTypeName = ctx.primitiveType().getText();
			}
			else if (ctx.qualifiedName() != null)
			{
				baseTypeName = ctx.qualifiedName().getText();
			}
			else
			{
				logError(ctx.start, "Unsupported type structure.");
				return ErrorType.INSTANCE;
			}
			Optional<Symbol> typeSymbol = currentScope.resolve(baseTypeName);
			Type baseType = typeSymbol.map(Symbol::getType).orElse(new UnresolvedType(baseTypeName));
			int rank = ctx.L_BRACK_SYM().size();
			for (int i = 0; i < rank; i++)
			{
				baseType = new ArrayType(baseType);
			}
			return baseType;
		}

		// FIX: Implemented import handling in the first pass
		@Override
		public Void visitImportDeclaration(NebulaParser.ImportDeclarationContext ctx)
		{
			String fqn = getFqn(ctx.qualifiedName());
			Optional<Symbol> targetSymbol = root.resolvePath(fqn);

			if (targetSymbol.isEmpty())
			{
				logError(ctx.qualifiedName().start, "Cannot resolve import target '" + fqn + "'.");
				return null;
			}

			if (!(targetSymbol.get() instanceof ClassSymbol))
			{
				logError(ctx.qualifiedName().start, "Import target must be a class.");
				return null;
			}

			String aliasName = targetSymbol.get().getName(); // Use the simple name of the class
			if (currentScope.resolveLocally(aliasName).isPresent())
			{
				logError(ctx.qualifiedName().start, "Cannot import '" + fqn + "', a symbol named '" + aliasName + "' already exists.");
				return null;
			}

			currentScope.define(new AliasSymbol(aliasName, targetSymbol.get()));
			return null;
		}

		// FIX: Moved alias handling to the first pass
		@Override
		public Void visitAliasDeclaration(NebulaParser.AliasDeclarationContext ctx)
		{
			String aliasName = ctx.ID().getText();
			String targetFqn = getFqn(ctx.qualifiedName());

			Optional<Symbol> targetSymbol = root.resolvePath(targetFqn);

			if (targetSymbol.isEmpty())
			{
				logError(ctx.qualifiedName().start, "Cannot resolve alias target '" + targetFqn + "'.");
				return null;
			}

			if (currentScope.resolveLocally(aliasName).isPresent())
			{
				logError(ctx.ID().getSymbol(), "Cannot create alias '" + aliasName + "', a symbol with that name already exists in this scope.");
				return null;
			}

			currentScope.define(new AliasSymbol(aliasName, targetSymbol.get()));
			return null;
		}

		@Override
		public Void visitNamespaceDeclaration(NebulaParser.NamespaceDeclarationContext ctx)
		{
			String fqn = getFqn(ctx.qualifiedName());
			String[] parts = fqn.split("\\.");
			Scope parent = currentScope;
			for (String part : parts)
			{
				Optional<Symbol> existing = parent.resolveLocally(part);
				if (existing.isPresent() && existing.get() instanceof NamespaceSymbol)
				{
					parent = (NamespaceSymbol) existing.get();
				}
				else
				{
					NamespaceSymbol ns = new NamespaceSymbol(part, parent);
					parent.define(ns);
					parent = ns;
				}
			}
			Scope oldScope = currentScope;
			currentScope = parent;
			visitChildren(ctx);
			currentScope = oldScope;
			return null;
		}

		@Override
		public Void visitClassDeclaration(NebulaParser.ClassDeclarationContext ctx)
		{
			String className = ctx.ID().getText();
			if (currentScope.resolveLocally(className).isPresent())
			{
				logError(ctx.ID().getSymbol(), "Type '" + className + "' is already defined in this scope.");
				return null;
			}
			ClassSymbol cs = new ClassSymbol(className, currentScope);

			String namespacePrefix = (currentScope instanceof NamespaceSymbol) ? ((NamespaceSymbol) currentScope).getFqn() : "";
			String fqn = namespacePrefix.isEmpty() ? className : namespacePrefix + "." + className;
			declaredClasses.put(fqn, cs);

			currentScope.define(cs);

			ClassSymbol oldClass = currentClass;
			currentClass = cs;
			currentScope = cs;
			visitChildren(ctx);
			currentScope = currentScope.getEnclosingScope();
			currentClass = oldClass;
			return null;
		}

		@Override
		public Void visitMethodDeclaration(NebulaParser.MethodDeclarationContext ctx)
		{
			if (currentClass == null)
			{
				logError(ctx.ID().getSymbol(), "Method defined outside of a class.");
				return null;
			}
			Type returnType = resolveTypeFromCtx(ctx.type());
			List<Type> paramTypes = new ArrayList<>();
			if (ctx.parameterList() != null)
			{
				for (var pCtx : ctx.parameterList().parameter())
				{
					paramTypes.add(resolveTypeFromCtx(pCtx.type()));
				}
			}
			boolean isStatic = ctx.modifiers() != null && ctx.modifiers().getText().contains("static");
			boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");
			MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), returnType, paramTypes, currentScope, isStatic, isPublic, false);
			currentClass.defineMethod(ms);
			return null;
		}

		@Override
		public Void visitFieldDeclaration(NebulaParser.FieldDeclarationContext ctx)
		{
			if (currentClass == null)
			{
				logError(ctx.start, "Field defined outside of a class.");
				return null;
			}
			Type fieldType = resolveTypeFromCtx(ctx.type());
			boolean isStatic = ctx.modifiers() != null && ctx.modifiers().getText().contains("static");
			boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");
			boolean isConst = ctx.modifiers() != null && ctx.modifiers().getText().contains("const");

			for (var declarator : ctx.variableDeclarator())
			{
				String varName = declarator.ID().getText();
				if (currentClass.resolveLocally(varName).isPresent())
				{
					logError(declarator.ID().getSymbol(), "Field '" + varName + "' is already defined in this class.");
					continue;
				}
				VariableSymbol vs = new VariableSymbol(varName, fieldType, isStatic, isPublic, isConst);
				currentClass.define(vs);
			}
			return null;
		}

		@Override
		public Void visitConstructorDeclaration(NebulaParser.ConstructorDeclarationContext ctx)
		{
			if (currentClass == null)
			{
				logError(ctx.ID().getSymbol(), "Constructor defined outside of a class.");
				return null;
			}
			List<Type> paramTypes = new ArrayList<>();
			if (ctx.parameterList() != null)
			{
				for (var pCtx : ctx.parameterList().parameter())
				{
					paramTypes.add(resolveTypeFromCtx(pCtx.type()));
				}
			}
			boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");
			MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), currentClass.getType(), paramTypes, currentScope, false, isPublic, true);
			currentClass.defineMethod(ms);
			return null;
		}
	}

	/**
	 * This visitor performs the final pass, type-checking all expressions and resolving symbols.
	 */
	private static class TypeCheckVisitor extends NebulaParserBaseVisitor<Type>
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

		private void logError(Token token, String msg)
		{
			String err = String.format("Semantic Error at line %d:%d - %s", token.getLine(), token.getCharPositionInLine() + 1, msg);
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
			String baseTypeName;
			if (ctx.primitiveType() != null)
			{
				baseTypeName = ctx.primitiveType().getText();
			}
			else if (ctx.qualifiedName() != null)
			{
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
				if (declaredClasses.containsKey(baseTypeName))
				{
					symbol = Optional.of(declaredClasses.get(baseTypeName));
				}
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
			visit(ctx.block());
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
					Type initializerType = visit(declarator.expression());
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

		// --- Expressions ---
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
				if (!valueType.isAssignableTo(targetType))
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

		// FIX: Rewritten to be more robust and avoid NPEs
		@Override
		public Type visitPostfixExpression(NebulaParser.PostfixExpressionContext ctx)
		{
			Type currentType = visit(ctx.primary());
			Symbol currentSymbol = resolvedSymbols.get(ctx.primary());

			for (int i = 0; i < ctx.ID().size(); i++)
			{
				if (currentType instanceof ErrorType)
				{
					return ErrorType.INSTANCE;
				}

				String memberName = ctx.ID(i).getText();
				Scope scopeToSearch = null;

				if (currentSymbol instanceof Scope)
				{
					scopeToSearch = (Scope) currentSymbol;
				}
				else if (currentType instanceof ClassType)
				{
					scopeToSearch = ((ClassType) currentType).getClassSymbol();
				}

				if (scopeToSearch == null)
				{
					logError(ctx.DOT_SYM(i).getSymbol(), "Cannot access member on type '" + currentType.getName() + "'. It is not a class or namespace.");
					return ErrorType.INSTANCE;
				}

				Optional<Symbol> member = scopeToSearch.resolve(memberName);
				if (member.isEmpty())
				{
					logError(ctx.ID(i).getSymbol(), "Cannot resolve member '" + memberName + "' in type '" + scopeToSearch.getName() + "'.");
					return ErrorType.INSTANCE;
				}

				currentSymbol = member.get();
				if (currentSymbol instanceof AliasSymbol)
				{ // Follow alias
					currentSymbol = ((AliasSymbol) currentSymbol).getTargetSymbol();
				}

				currentType = currentSymbol.getType();
				if (currentType instanceof UnresolvedType)
				{
					currentType = resolveUnresolvedType((UnresolvedType) currentType, ctx.ID(i).getSymbol());
				}
			}

			note(ctx, currentSymbol);
			note(ctx, currentType);
			return currentType;
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
	}
}