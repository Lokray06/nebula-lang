package org.lokray.semantic;

import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.util.Debug;

import java.util.Optional;

public class SymbolTableBuilder
{
	private final Scope root;
	private Scope currentScope;

	public SymbolTableBuilder(Scope root)
	{
		this.root = root;
		this.currentScope = root;
	}

	public void visit(ParseTree tree)
	{
		new DefVisitor().visit(tree);
	}

	private class DefVisitor extends NebulaParserBaseVisitor<Void>
	{
		// FIX: Add this method to handle imports
		@Override
		public Void visitImportDeclaration(NebulaParser.ImportDeclarationContext ctx)
		{
			String qualifiedName = ctx.qualifiedName().getText();
			Optional<Symbol> target = root.resolvePath(qualifiedName);

			if (target.isPresent())
			{
				// An import is just an alias for the class's simple name
				String simpleName = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
				currentScope.define(new AliasSymbol(simpleName, target.get()));
			}
			else
			{
				Debug.logError("Semantic Error: Cannot resolve import '" + qualifiedName + "'");
			}
			return null;
		}

		// FIX: Add this method to handle aliases
		@Override
		public Void visitAliasDeclaration(NebulaParser.AliasDeclarationContext ctx)
		{
			String aliasName = ctx.ID().getText();
			String qualifiedName = ctx.qualifiedName().getText();

			// Resolve the target symbol starting from the current scope, which includes imports
			Optional<Symbol> target = currentScope.resolvePath(qualifiedName);

			if (target.isPresent())
			{
				currentScope.define(new AliasSymbol(aliasName, target.get()));
			}
			else
			{
				Debug.logError("Semantic Error: Cannot resolve alias target '" + qualifiedName + "'");
			}
			return null;
		}

		@Override
		public Void visitNamespaceDeclaration(NebulaParser.NamespaceDeclarationContext ctx)
		{
			String[] parts = ctx.qualifiedName().getText().split("\\.");
			Scope parent = currentScope;
			for (String part : parts)
			{
				var existing = parent.resolveLocally(part);
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
			if (ctx.modifiers() != null)
			{
				String mods = ctx.modifiers().getText();
				for (String m : mods.split("\\s+"))
				{
					cs.addModifier(m);
				}
			}
			currentScope.define(cs);
			Scope old = currentScope;
			currentScope = cs;
			visitChildren(ctx);
			currentScope = old;
			return null;
		}

		@Override
		public Void visitNativeClassDeclaration(NebulaParser.NativeClassDeclarationContext ctx)
		{
			ClassSymbol cs = new ClassSymbol(ctx.ID().getText(), currentScope);
			cs.addModifier("native"); // Mark it as native
			if (ctx.modifiers() != null)
			{
				String mods = ctx.modifiers().getText();
				for (String m : mods.split("\\s+"))
				{
					cs.addModifier(m);
				}
			}
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
			MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), currentScope);
			if (ctx.modifiers() != null)
			{
				String mods = ctx.modifiers().getText();
				for (String m : mods.split("\\s+"))
				{
					ms.addModifier(m);
				}
			}
			currentScope.define(ms);
			return null;
		}

		// FIX: Handles 'native ... method();'
		@Override
		public Void visitNativeMethodDeclaration(NebulaParser.NativeMethodDeclarationContext ctx)
		{
			MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), currentScope);
			ms.addModifier("native");
			if (ctx.modifiers() != null)
			{
				String mods = ctx.modifiers().getText();
				for (String m : mods.split("\\s+"))
				{
					ms.addModifier(m);
				}
			}
			currentScope.define(ms);
			return null;
		}

		@Override
		public Void visitFieldDeclaration(NebulaParser.FieldDeclarationContext ctx)
		{
			String type = ctx.type().getText();
			for (var varCtx : ctx.variableDeclarator())
			{
				VariableSymbol vs = new VariableSymbol(varCtx.ID().getText(), type);
				currentScope.define(vs);
			}
			return visitChildren(ctx);
		}

		// FIX: Handles 'native ... field;'
		@Override
		public Void visitNativeFieldDeclaration(NebulaParser.NativeFieldDeclarationContext ctx)
		{
			String type = ctx.type().getText();
			for (var idNode : ctx.ID())
			{
				VariableSymbol vs = new VariableSymbol(idNode.getText(), type);
				currentScope.define(vs);
			}
			return null;
		}
	}
}