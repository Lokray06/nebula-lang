// File: src/main/java/org/lokray/semantic/SymbolTableBuilder.java
package org.lokray.semantic;

import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.semantic.type.*;
import org.lokray.util.Debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SymbolTableBuilder extends NebulaParserBaseVisitor<Void>
{
	private final Scope root;
	private Scope currentScope;
	private boolean hasErrors = false;

	public SymbolTableBuilder(Scope root)
	{
		this.root = root;
		this.currentScope = root;
	}

	public boolean hasErrors()
	{
		return hasErrors;
	}

	private void logError(org.antlr.v4.runtime.Token token, String msg)
	{
		String err = String.format("Semantic Error at line %d:%d - %s",
				token.getLine(), token.getCharPositionInLine() + 1, msg);
		Debug.logError(err);
		hasErrors = true;
	}

	// Type resolution during this pass is minimal; we just create placeholders
	// or resolve primitives. Full resolution happens in Pass 2.
	private Type resolveTypeFromCtx(NebulaParser.TypeContext ctx)
	{
		if (ctx.primitiveType() != null)
		{
			String typeName = ctx.primitiveType().getText();
			Optional<Symbol> typeSymbol = root.resolve(typeName);
			if (typeSymbol.isPresent())
			{
				return typeSymbol.get().getType();
			}
		}
		// For class types, we can't fully resolve yet. We just use the name.
		// A placeholder or deferred type resolution mechanism would be more robust.
		// For now, we'll rely on string names and resolve fully in the next pass.
		return new UnresolvedType(ctx.getText());
	}

	@Override
	public Void visitNamespaceDeclaration(NebulaParser.NamespaceDeclarationContext ctx)
	{
		String[] parts = ctx.qualifiedName().getText().split("\\.");
		Scope parent = currentScope;
		for (String part : parts)
		{
			Optional<Symbol> existing = parent.resolveLocally(part);
			if (existing.isPresent() && existing.get() instanceof NamespaceSymbol ns)
			{
				parent = ns;
			}
			else
			{
				NamespaceSymbol ns = new NamespaceSymbol(part, parent);
				parent.define(ns);
				parent = ns;
			}
		}
		Scope old = currentScope;
		currentScope = parent;
		visitChildren(ctx);
		currentScope = old;
		return null;
	}

	@Override
	public Void visitClassDeclaration(NebulaParser.ClassDeclarationContext ctx)
	{
		ClassSymbol cs = new ClassSymbol(ctx.ID().getText(), currentScope);
		currentScope.define(cs);
		Scope old = currentScope;
		currentScope = cs;
		visitChildren(ctx);
		currentScope = old;
		return null;
	}

	@Override
	public Void visitMethodDeclaration(NebulaParser.MethodDeclarationContext ctx)
	{
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
		boolean isPublic = ctx.modifiers() == null || ctx.modifiers().getText().contains("public");

		MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), returnType, paramTypes, currentScope, isStatic, isPublic, false);
		((ClassSymbol) currentScope).defineMethod(ms);
		return null; // Don't visit block in this pass
	}

	@Override
	public Void visitFieldDeclaration(NebulaParser.FieldDeclarationContext ctx)
	{
		Type fieldType = resolveTypeFromCtx(ctx.type());
		boolean isStatic = ctx.modifiers() != null && ctx.modifiers().getText().contains("static");
		boolean isPublic = ctx.modifiers() == null || ctx.modifiers().getText().contains("public");
		boolean isConst = ctx.modifiers() != null && ctx.modifiers().getText().contains("const");

		for (var declarator : ctx.variableDeclarator())
		{
			VariableSymbol vs = new VariableSymbol(declarator.ID().getText(), fieldType, isStatic, isPublic, isConst);
			if (currentScope.resolveLocally(vs.getName()).isPresent())
			{
				logError(declarator.ID().getSymbol(), "Field '" + vs.getName() + "' is already defined in this class.");
			}
			else
			{
				currentScope.define(vs);
			}
		}
		return null; // Don't visit initializers yet
	}

	// You must also implement visitors for properties, constructors, etc.
	// to define their symbols in this pass.
}