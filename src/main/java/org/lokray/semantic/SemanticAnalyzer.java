package org.lokray.semantic;

import org.antlr.v4.runtime.tree.ParseTree;
import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.util.Debug;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class SemanticAnalyzer
{
	private final Scope globalScope = new Scope(null);
	private Scope currentScope = globalScope;
	private boolean hasErrors = false;

	public SemanticAnalyzer()
	{
		BuiltInTypeLoader.definePrimitives(globalScope);
	}

	public SemanticAnalyzer(Path ndkLib)
	{
		BuiltInTypeLoader.definePrimitives(globalScope);
		if (Files.exists(ndkLib))
		{
			try
			{
				NebulaLibLoader.loadLibraryIntoScope(ndkLib, globalScope);
			}
			catch (Exception e)
			{
				Debug.logWarning("Failed to load ndk library: " + e.getMessage());
			}
		}
		else
		{
			Debug.logError("Failed to load ndk library: " + ndkLib);
		}
	}

	public boolean analyze(ParseTree tree)
	{
		// Pass 1
		new SymbolTableBuilder(globalScope).visit(tree);
		if (hasErrors)
		{
			return false;
		}

		// Pass 2
		new SymbolResolutionVisitor().visit(tree);
		return !hasErrors;
	}

	private class SymbolResolutionVisitor extends NebulaParserBaseVisitor<Symbol>
	{
		@Override
		public Symbol visitClassDeclaration(NebulaParser.ClassDeclarationContext ctx)
		{
			// Enter the class scope created in Pass 1
			currentScope.resolve(ctx.ID().getText()).ifPresent(s -> currentScope = (Scope) s);
			visitChildren(ctx);
			currentScope = currentScope.getEnclosingScope();
			return null;
		}

		@Override
		public Symbol visitMethodDeclaration(NebulaParser.MethodDeclarationContext ctx)
		{
			// Enter the method scope, but we need to create it here for locals
			Scope methodScope = new MethodSymbol(ctx.ID().getText(), currentScope);
			currentScope = methodScope;
			visitChildren(ctx);
			currentScope = currentScope.getEnclosingScope();
			return null;
		}

		@Override
		public Symbol visitBlock(NebulaParser.BlockContext ctx)
		{
			// Each block gets its own scope for local variables
			Scope blockScope = new Scope(currentScope);
			currentScope = blockScope;
			visitChildren(ctx);
			currentScope = currentScope.getEnclosingScope();
			return null;
		}

		@Override
		public Symbol visitVariableDeclaration(NebulaParser.VariableDeclarationContext ctx)
		{
			String typeName = ctx.type().getText();
			// A real compiler would resolve typeName to a Type Symbol here.

			for (var declarator : ctx.variableDeclarator())
			{
				String varName = declarator.ID().getText();
				if (currentScope.resolveLocally(varName).isPresent())
				{
					logError(declarator.ID().getSymbol(), "Variable '" + varName + "' is already defined in this scope.");
				}
				else
				{
					currentScope.define(new VariableSymbol(varName, typeName));
				}
				if (declarator.expression() != null)
				{
					visit(declarator.expression()); // Visit initializer to check for valid symbols
				}
			}
			return null;
		}

		@Override
		public Symbol visitPrimary(NebulaParser.PrimaryContext ctx)
		{
			if (ctx.ID() != null)
			{
				String varName = ctx.ID().getText();
				Optional<Symbol> symbol = currentScope.resolve(varName);
				if (symbol.isEmpty())
				{
					logError(ctx.ID().getSymbol(), "Cannot find symbol '" + varName + "'.");
					return null;
				}
				return symbol.get();
			}
			return visitChildren(ctx); // For literals, etc.
		}

		@Override
		public Symbol visitPostfixExpression(NebulaParser.PostfixExpressionContext ctx)
		{
			// Start by resolving the initial part of the expression (the primary)
			Symbol currentSymbol = visit(ctx.primary());
			if (currentSymbol == null)
			{
				return null;
			}

			// FIX: A more robust way to iterate through member access/calls
			int idIndex = 0;
			int argListIndex = 0;
			for (int i = 1; i < ctx.getChildCount(); i++)
			{
				ParseTree child = ctx.getChild(i);
				if (child.getText().equals("."))
				{
					i++; // Move to the ID
					String memberName = ctx.getChild(i).getText();

					if (!(currentSymbol instanceof Scope))
					{
						logError(((org.antlr.v4.runtime.tree.TerminalNode) child).getSymbol(), "Cannot access member on non-scope symbol '" + currentSymbol.getName() + "'.");
						return null;
					}

					Optional<Symbol> member = ((Scope) currentSymbol).resolve(memberName);
					if (member.isPresent())
					{
						currentSymbol = member.get();
					}
					else
					{
						logError(((org.antlr.v4.runtime.tree.TerminalNode) child).getSymbol(), "Cannot resolve member '" + memberName + "' on symbol '" + currentSymbol.getName() + "'.");
						return null;
					}

				}
				else if (child.getText().equals("("))
				{
					if (!(currentSymbol instanceof MethodSymbol))
					{
						logError(ctx.primary().getStart(), "'" + currentSymbol.getName() + "' is not a method and cannot be called.");
						return null;
					}
					// In a full implementation, visit the argumentList to check parameters
					if (ctx.argumentList(argListIndex) != null)
					{
						visit(ctx.argumentList(argListIndex));
						argListIndex++;
					}
					// Skip past the arguments and the ')'
					while (!ctx.getChild(i).getText().equals(")"))
					{
						i++;
					}
				}
			}
			return currentSymbol;
		}
	}

	private void logError(org.antlr.v4.runtime.Token token, String msg)
	{
		String err = String.format("Semantic Error at line %d:%d - %s",
				token.getLine(), token.getCharPositionInLine(), msg);
		Debug.logError(err);
		hasErrors = true;
	}
}
