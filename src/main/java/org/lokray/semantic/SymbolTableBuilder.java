// File: src/main/java/org/lokray/semantic/SymbolTableBuilder.java
package org.lokray.semantic;

import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.semantic.symbol.*;
import org.lokray.semantic.type.*;
import org.lokray.util.Debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SymbolTableBuilder extends NebulaParserBaseVisitor<Void>
{
	private final Scope root;
	private Scope currentScope;
	private ClassSymbol currentClass;
	private boolean hasErrors = false;
	private Map<String, ClassSymbol> declaredClasses;

	public SymbolTableBuilder(Scope root)
	{
		this.root = root;
		this.currentScope = root;
	}

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

	private void logError(org.antlr.v4.runtime.Token token, String msg)
	{
		// New Format: [Semantic Error] current class name - line:char - msg
		// Get the current class name or an empty string if not inside a class
		String className = currentClass != null ? currentClass.getName() : "";

		// Format the error string
		String err = String.format("[Semantic Error] %s - line %d:%d - %s",
				className, token.getLine(), token.getCharPositionInLine() + 1, msg);

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

		// NEW: Handle tuple types like (int, string Name)
		if (ctx.tupleType() != null)
		{
			List<TupleElementSymbol> elements = new ArrayList<>();
			for (int i = 0; i < ctx.tupleType().tupleTypeElement().size(); i++)
			{
				var elementCtx = ctx.tupleType().tupleTypeElement(i);
				Type elementType = resolveTypeFromCtx(elementCtx.type());
				String name = elementCtx.ID() != null ? elementCtx.ID().getText() : null;

				// Check for duplicate explicit names within the same tuple type declaration
				if (name != null)
				{
					for (TupleElementSymbol existing : elements)
					{
						if (name.equals(existing.getName()))
						{
							logError(elementCtx.ID().getSymbol(), "Duplicate tuple element name '" + name + "'.");
						}
					}
				}
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

	// Moved alias handling to the first pass
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
		boolean isPublic = ctx.modifiers().getText().contains("public");
		ClassSymbol cs = new ClassSymbol(className, currentScope, false, isPublic);

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
	public Void visitNativeClassDeclaration(NebulaParser.NativeClassDeclarationContext ctx)
	{
		String className = ctx.ID().getText();
		String namespacePrefix = (currentScope instanceof NamespaceSymbol) ? ((NamespaceSymbol) currentScope).getFqn() : "";
		String fqn = namespacePrefix.isEmpty() ? className : namespacePrefix + "." + className;

		boolean isPublic = false;
		if (ctx.modifiers() != null)
		{
			isPublic = ctx.modifiers().getText().contains("public");
		}
		ClassSymbol cs = new ClassSymbol(ctx.ID().getText(), currentScope, true, isPublic);
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
		MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), returnType, paramTypes, currentScope, isStatic, isPublic, false, false);
		currentClass.defineMethod(ms);
		return null;
	}

	@Override
	public Void visitNativeMethodDeclaration(NebulaParser.NativeMethodDeclarationContext ctx)
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
		MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), returnType, paramTypes, currentScope, isStatic, isPublic, false, true);
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
	public Void visitPropertyDeclaration(NebulaParser.PropertyDeclarationContext ctx)
	{
		if (currentClass == null)
		{
			logError(ctx.ID().getSymbol(), "Field defined outside of a class.");
			return null;
		}
		Type propType = resolveTypeFromCtx(ctx.type());
		String propName = ctx.ID().getText();

		if (currentClass.resolveLocally(propName).isPresent())
		{
			logError(ctx.ID().getSymbol(), "Member '" + propName + "' is already defined in this class.");
			return null;
		}

		// For simplicity, we'll model a property as a variable symbol.
		// A more advanced implementation might use a special PropertySymbol.
		boolean isStatic = ctx.modifiers() != null && ctx.modifiers().getText().contains("static");
		boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");

		VariableSymbol vs = new VariableSymbol(propName, propType, isStatic, isPublic, false);
		currentClass.define(vs);
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
		MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), currentClass.getType(), paramTypes, currentScope, false, isPublic, true, false);
		currentClass.defineMethod(ms);
		return null;
	}

	// You must also implement visitors for properties, constructors, etc.
	// to define their symbols in this pass.
}