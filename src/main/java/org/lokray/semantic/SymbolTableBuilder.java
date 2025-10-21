// File: src/main/java/org/lokray/semantic/SymbolTableBuilder.java
package org.lokray.semantic;

import org.lokray.parser.NebulaParser;
import org.lokray.parser.NebulaParserBaseVisitor;
import org.lokray.semantic.symbol.*;
import org.lokray.semantic.type.*;
import org.lokray.util.Debug;
import org.lokray.util.ErrorHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SymbolTableBuilder extends NebulaParserBaseVisitor<Void>
{
	private final Scope root;
	private Scope currentScope;
	private ClassSymbol currentClass;
	private final ErrorHandler errorHandler;
	private Map<String, ClassSymbol> declaredClasses;
	private final boolean discoveryOnly;

	public SymbolTableBuilder(Scope root, Map<String, ClassSymbol> declaredClasses, ErrorHandler errorHandler, boolean discoveryOnly)
	{
		this.root = root;
		this.currentScope = root;
		this.declaredClasses = declaredClasses;
		this.errorHandler = errorHandler;
		this.discoveryOnly = discoveryOnly;
	}

	public boolean hasErrors()
	{
		return errorHandler.hasErrors();
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
							errorHandler.logError(elementCtx.ID().getSymbol(), "Duplicate tuple element name '" + name + "'.", currentClass);
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
			errorHandler.logError(ctx.start, "Unsupported type structure.", currentClass);
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

	@Override
	public Void visitImportDeclaration(NebulaParser.ImportDeclarationContext ctx)
	{
		if (discoveryOnly)
		{
			return null; // Skip imports in the first pass
		}
		String fqn = getFqn(ctx.qualifiedName());
		String[] parts = fqn.split("\\.");
		String simpleName = parts[parts.length - 1];

		// Note: resolvePath might need to be implemented on your Scope/Symbol if it's
		// not there, but it should resolve a multi-part name from the global scope.
		// Let's assume `root` can find symbols from the FQN map `declaredClasses`.
		Symbol targetSymbol = declaredClasses.get(fqn);

		if (targetSymbol == null)
		{
			// Fallback for namespaces, etc., if not in declaredClasses
			Optional<Symbol> resolved = root.resolvePath(fqn); // You may need to implement resolvePath
			if (resolved.isEmpty())
			{
				errorHandler.logError(ctx.qualifiedName().start, "Cannot find type to import: '" + fqn + "'.", currentClass);
				return null;
			}
			targetSymbol = resolved.get();
		}


		// Check for conflicts in the current scope (e.g., the file's top-level scope)
		if (currentScope.resolveLocally(simpleName).isPresent())
		{
			errorHandler.logError(ctx.qualifiedName().start, "A symbol named '" + simpleName + "' is already defined or imported in this scope.", currentClass);
			return null;
		}

		// Define an alias in the current scope.
		// This effectively makes `Console` an alias for the `nebula.io.Console` ClassSymbol.
		currentScope.define(new AliasSymbol(simpleName, targetSymbol));
		Debug.logDebug("  Created import alias: " + simpleName + " -> " + fqn);
		return null;
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
			errorHandler.logError(ctx.qualifiedName().start, "Cannot resolve alias target '" + targetFqn + "'.", currentClass);
			return null;
		}

		if (currentScope.resolveLocally(aliasName).isPresent())
		{
			errorHandler.logError(ctx.ID().getSymbol(), "Cannot create alias '" + aliasName + "', a symbol with that name already exists in this scope.", currentClass);
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
			errorHandler.logError(ctx.ID().getSymbol(), "Type '" + className + "' is already defined in this scope.", currentClass);
			return null;
		}
		boolean isPublic = ctx.modifiers() != null ? ctx.modifiers().getText().contains("public") : false;
		ClassSymbol cs = new ClassSymbol(className, currentScope, false, isPublic);

		String namespacePrefix = (currentScope instanceof NamespaceSymbol) ? ((NamespaceSymbol) currentScope).getFqn() : "";
		String fqn = namespacePrefix.isEmpty() ? className : namespacePrefix + "." + className;
		declaredClasses.put(fqn, cs);

		Debug.logDebug("Defined class " + cs.getName());
		currentScope.define(cs);

		ClassSymbol oldClass = currentClass;
		currentClass = cs;
		Scope oldScope = currentScope;
		currentScope = cs;

		// 1. Visit all members (fields, methods, constructors, properties)
		visitChildren(ctx);

		// 2. FIX: Define the implicit parameterless constructor if none were found
		// The constructor name is the class name itself.
		if (!cs.getMethodsByName().containsKey(cs.getName()))
		{
			// Define the default constructor: Person() { }
			MethodSymbol defaultCtor = new MethodSymbol(
					cs.getName(),
					cs.getType(),
					new ArrayList<>(), // No parameters
					cs,
					false, // not static
					true,  // public
					true,  // isConstructor
					false  // not native
			);
			cs.defineMethod(defaultCtor);
		}

		// 3. Restore scopes
		currentScope = oldScope; // Use oldScope here
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

		Debug.logDebug("Defined native class " + cs.getName());
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
	public Void visitStructDeclaration(NebulaParser.StructDeclarationContext ctx)
	{
		String structName = ctx.ID().getText();

		if (discoveryOnly)
		{
			// --- PASS 1: DISCOVERY ---
			if (currentScope.resolveLocally(structName).isPresent())
			{
				errorHandler.logError(ctx.ID().getSymbol(), "Type '" + structName + "' is already defined in this scope.", currentClass);
				return null;
			}
			boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");
			StructSymbol structSymbol = new StructSymbol(structName, currentScope, isPublic, false);

			String namespacePrefix = (currentScope instanceof NamespaceSymbol) ? ((NamespaceSymbol) currentScope).getFqn() : "";
			String fqn = namespacePrefix.isEmpty() ? structName : namespacePrefix + "." + structName;
			declaredClasses.put(fqn, structSymbol);

			Debug.logDebug("Defined struct " + structSymbol.getName());
			currentScope.define(structSymbol);
			// Do NOT visit children in this pass.
		}
		else
		{
			// --- PASS 2: MEMBER DEFINITION ---
			Optional<Symbol> symbol = currentScope.resolveLocally(structName);
			if (symbol.isEmpty() || !(symbol.get() instanceof StructSymbol))
			{
				return null; // Internal error
			}

			StructSymbol structSymbol = (StructSymbol) symbol.get();
			ClassSymbol oldClass = currentClass;
			currentClass = structSymbol;
			Scope oldScope = currentScope;
			currentScope = structSymbol;

			visitChildren(ctx);

			if (!structSymbol.getMethodsByName().containsKey(structSymbol.getName()))
			{
				MethodSymbol defaultCtor = new MethodSymbol(
						structSymbol.getName(),
						structSymbol.getType(),
						new ArrayList<>(),
						structSymbol,
						false, // not static
						true,  // public
						true,  // isConstructor
						false  // not native
				);
				structSymbol.defineMethod(defaultCtor);
			}

			currentScope = oldScope;
			currentClass = oldClass;
		}
		return null;
	}

	@Override
	public Void visitNativeStructDeclaration(NebulaParser.NativeStructDeclarationContext ctx)
	{
		String structName = ctx.ID().getText();

		if (discoveryOnly)
		{
			// --- PASS 1: DISCOVERY ---
			if (currentScope.resolveLocally(structName).isPresent())
			{
				errorHandler.logError(ctx.ID().getSymbol(), "Type '" + structName + "' is already defined in this scope.", currentClass);
				return null;
			}
			boolean isPublic = ctx.modifiers() != null && ctx.modifiers().getText().contains("public");
			StructSymbol structSymbol = new StructSymbol(structName, currentScope, isPublic, true);

			String namespacePrefix = (currentScope instanceof NamespaceSymbol) ? ((NamespaceSymbol) currentScope).getFqn() : "";
			String fqn = namespacePrefix.isEmpty() ? structName : namespacePrefix + "." + structName;
			declaredClasses.put(fqn, structSymbol);

			Debug.logDebug("Defined native struct " + structSymbol.getName());
			currentScope.define(structSymbol);
			// Do NOT visit children in this pass.
		}
		else
		{
			// --- PASS 2: MEMBER DEFINITION ---
			Optional<Symbol> symbol = currentScope.resolveLocally(structName);
			if (symbol.isEmpty() || !(symbol.get() instanceof StructSymbol))
			{
				return null; // Internal error
			}
			StructSymbol structSymbol = (StructSymbol) symbol.get();
			ClassSymbol oldClass = currentClass;
			currentClass = structSymbol;
			Scope oldScope = currentScope;
			currentScope = structSymbol;

			visitChildren(ctx);

			currentScope = oldScope;
			currentClass = oldClass;
		}
		return null;
	}

	@Override
	public Void visitMethodDeclaration(NebulaParser.MethodDeclarationContext ctx)
	{
		if (currentClass == null)
		{
			errorHandler.logError(ctx.ID().getSymbol(), "Method defined outside of a class.", currentClass);
			return null;
		}
		Type returnType = resolveTypeFromCtx(ctx.type());

		// UPDATED: Build a list of ParameterSymbol
		List<ParameterSymbol> params = new ArrayList<>();
		if (ctx.parameterList() != null)
		{
			for (int i = 0; i < ctx.parameterList().parameter().size(); i++)
			{
				var pCtx = ctx.parameterList().parameter(i);
				Type paramType = resolveTypeFromCtx(pCtx.type());
				String paramName = pCtx.ID().getText();
				// Capture the default value expression context if it exists
				NebulaParser.ExpressionContext defaultValCtx = pCtx.expression();
				params.add(new ParameterSymbol(paramName, paramType, i, defaultValCtx));
			}
		}

		boolean isStatic = ctx.modifiers() != null && ctx.modifiers().getText().contains("static");
		boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");
		MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), returnType, params, currentScope, isStatic, isPublic, false, false);
		currentClass.defineMethod(ms);

		visitChildren(ctx);

		return null;
	}

	@Override
	public Void visitConstructorDeclaration(NebulaParser.ConstructorDeclarationContext ctx)
	{
		if (currentClass == null)
		{
			errorHandler.logError(ctx.ID().getSymbol(), "Constructor defined outside of a class.", currentClass);
			return null;
		}

		// UPDATED: Build a list of ParameterSymbol
		List<ParameterSymbol> params = new ArrayList<>();
		if (ctx.parameterList() != null)
		{
			for (int i = 0; i < ctx.parameterList().parameter().size(); i++)
			{
				var pCtx = ctx.parameterList().parameter(i);
				Type paramType = resolveTypeFromCtx(pCtx.type());
				String paramName = pCtx.ID().getText();
				NebulaParser.ExpressionContext defaultValCtx = pCtx.expression();
				params.add(new ParameterSymbol(paramName, paramType, i, defaultValCtx));
			}
		}

		boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");
		MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), currentClass.getType(), params, currentScope, false, isPublic, true, false);
		currentClass.defineMethod(ms);
		return null;
	}

	@Override
	public Void visitNativeMethodDeclaration(NebulaParser.NativeMethodDeclarationContext ctx)
	{
		if (currentClass == null)
		{
			errorHandler.logError(ctx.ID().getSymbol(), "Method defined outside of a class.", currentClass);
			return null;
		}
		Type returnType = resolveTypeFromCtx(ctx.type());

		// UPDATED: Build a list of ParameterSymbol
		List<ParameterSymbol> params = new ArrayList<>();
		if (ctx.parameterList() != null)
		{
			for (int i = 0; i < ctx.parameterList().parameter().size(); i++)
			{
				var pCtx = ctx.parameterList().parameter(i);
				Type paramType = resolveTypeFromCtx(pCtx.type());
				String paramName = pCtx.ID().getText();
				NebulaParser.ExpressionContext defaultValCtx = pCtx.expression();
				params.add(new ParameterSymbol(paramName, paramType, i, defaultValCtx));
			}
		}
		boolean isStatic = ctx.modifiers() != null && ctx.modifiers().getText().contains("static");
		boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");
		MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), returnType, params, currentScope, isStatic, isPublic, false, true);
		currentClass.defineMethod(ms);
		return null;
	}

	@Override
	public Void visitFieldDeclaration(NebulaParser.FieldDeclarationContext ctx)
	{
		if (currentClass == null)
		{
			errorHandler.logError(ctx.start, "Field defined outside of a class.", currentClass);
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
				errorHandler.logError(declarator.ID().getSymbol(), "Field '" + varName + "' is already defined in this class.", currentClass);
				continue;
			}
			VariableSymbol vs = new VariableSymbol(varName, fieldType, isStatic, isPublic, isConst);
			currentClass.define(vs);
		}
		return null;
	}

	/**
	 * Visits a native field declaration context.
	 * Native fields are typically external members mapped to the underlying platform
	 * and must be declared static.
	 *
	 * @param ctx The context for the native field declaration.
	 * @return null (as the visitor returns Void).
	 */
	@Override
	public Void visitNativeFieldDeclaration(NebulaParser.NativeFieldDeclarationContext ctx)
	{
		// 1. Check if the declaration is inside a class
		if (!(currentScope instanceof ClassSymbol))
		{
			errorHandler.logError(ctx.start, "Native fields must be declared inside a class scope.", currentClass);
			return null;
		}

		// Use 'enclosingClass' to avoid shadowing the field 'this.currentClass'
		ClassSymbol enclosingClass = (ClassSymbol) currentScope;

		// 2. Resolve the Type
		// NOTE: Using the context object directly, similar to visitFieldDeclaration.
		Type fieldType = resolveTypeFromCtx(ctx.type());

		if (fieldType == null)
		{
			errorHandler.logError(ctx.type().start, "Cannot resolve type '" + ctx.type().getText() + "' for native field.", currentClass);
			return null;
		}

		// 3. Extract Name - FIX: Use ctx.ID(0) because ctx.ID() returns a list of tokens.
		String fieldName = ctx.ID(0).getText();

		// 4. Extract Modifiers - FIX: Use the modifiers context and string logic from visitFieldDeclaration.
		boolean isNative = true; // By definition of this visitor method

		NebulaParser.ModifiersContext modifiersCtx = ctx.modifiers();
		String modifiersString = modifiersCtx != null ? modifiersCtx.getText() : "";

		boolean isStatic = modifiersString.contains("static");
		boolean isConst = modifiersString.contains("const");
		// In Nebula, a field is public if 'private' is not specified.
		boolean isPublic = !modifiersString.contains("private");

		// 6. Check for Redefinition - FIX: Use resolveLocally().isPresent() to match visitFieldDeclaration.
		if (enclosingClass.resolveLocally(fieldName).isPresent())
		{
			// FIX: Use ID(0).getSymbol()
			errorHandler.logError(ctx.ID(0).getSymbol(), "A member with the name '" + fieldName + "' is already defined in class '" + enclosingClass.getName() + "'.", currentClass);
			return null;
		}

		// 7. Create and Define the Symbol - FIX: Use VariableSymbol and align constructor arguments.
		// Assuming VariableSymbol supports the 'isNative' flag for fields.
		VariableSymbol fieldSymbol = new VariableSymbol(
				fieldName,
				fieldType,
				isStatic,
				isPublic,
				isConst,
				isNative
		);

		enclosingClass.define(fieldSymbol);

		// Native fields typically do not have initializers in the Nebula source file
		return null;
	}


	@Override
	public Void visitPropertyDeclaration(NebulaParser.PropertyDeclarationContext ctx)
	{
		if (currentClass == null)
		{
			errorHandler.logError(ctx.ID().getSymbol(), "Property defined outside of a class.", currentClass);
			return null;
		}
		Type propType = resolveTypeFromCtx(ctx.type());
		String propName = ctx.ID().getText();

		if (currentClass.resolveLocally(propName).isPresent())
		{
			errorHandler.logError(ctx.ID().getSymbol(), "Member '" + propName + "' is already defined in this class.", currentClass);
			return null;
		}

		boolean isStatic = ctx.modifiers() != null && ctx.modifiers().getText().contains("static");
		boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");

		// --- DETERMINE IF A SETTER EXISTS (scan accessors) ---
		boolean hasSetter = false;
		for (var accessorCtx : ctx.accessorDeclaration())
		{
			if (accessorCtx.SET_KW() != null)
			{
				hasSetter = true;
				break;
			}
		}

		// If there is no setter, the property is read-only (make it const/read-only).
		// We create the VariableSymbol with isConst = !hasSetter
		VariableSymbol vs = new VariableSymbol(propName, propType, isStatic, isPublic, !hasSetter);
		currentClass.define(vs);

		// --- Create accessor MethodSymbols (get_/set_) and visit their bodies ---
		for (var accessorCtx : ctx.accessorDeclaration())
		{
			boolean isGetter = accessorCtx.GET_KW() != null;
			String accessorName = isGetter ? "get_" + propName : "set_" + propName;

			Type returnType = isGetter ? propType : resolveTypeFromCtx(PrimitiveType.VOID.getName());
			List<ParameterSymbol> parameters = new ArrayList<>();

			MethodSymbol ms = new MethodSymbol(
					accessorName,
					returnType,
					parameters,
					currentClass, // enclosing scope
					isStatic,
					isPublic,
					false,
					false
			);
			// define method on class
			currentClass.defineMethod(ms);

			// --- SCOPE SHIFT: define parameter 'value' if setter ---
			Scope oldScope = currentScope;
			currentScope = ms;
			if (!isGetter)
			{
				// implicit 'value' param for setter
				ParameterSymbol valueParam = new ParameterSymbol("value", propType, 0, null);
				parameters.add(valueParam);
				ms.define(valueParam);
			}

			// Visit the accessor's body so we process any statements inside
			NebulaParser.AccessorBodyContext bodyCtx = accessorCtx.accessorBody();
			if (bodyCtx.block() != null)
			{
				visitBlock(bodyCtx.block());
			}
			else if (bodyCtx.expression() != null)
			{
				visit(bodyCtx.expression());
			}

			// restore
			currentScope = oldScope;
		}

		// If the property has an initializer (e.g., `= "Unnamed user";`) visit it now
		if (ctx.expression() != null)
		{
			visit(ctx.expression());
		}

		return null;
	}

	@Override
	public Void visitBlock(NebulaParser.BlockContext ctx)
	{
		// Create a nested scope for the block
		Scope oldScope = currentScope;
		currentScope = new Scope(currentScope);

		// Visit statements/decls inside
		visitChildren(ctx);

		// Restore previous scope
		currentScope = oldScope;
		return null;
	}

	@Override
	public Void visitVariableDeclaration(NebulaParser.VariableDeclarationContext ctx)
	{
		System.out.println("VISITING VARIABLE DECLARATION FROM THE SYMBOL TABLE BUILDER=========================================");
		// e.g. int a = 5, b = 10;
		Type varType = resolveTypeFromCtx(ctx.type());

		for (var decl : ctx.variableDeclarator())
		{
			String varName = decl.ID().getText();

			// Prevent redefinition in the same scope
			if (currentScope.resolveLocally(varName).isPresent())
			{
				errorHandler.logError(decl.ID().getSymbol(),
						"Variable '" + varName + "' is already defined in this scope.",
						currentClass);
				continue;
			}

			// Create symbol for local variable
			VariableSymbol vs = new VariableSymbol(varName, varType, false, false, false);

			// Define in the current scope
			currentScope.define(vs);

			// Visit initializer expression if present
			if (decl.expression() != null)
			{
				visit(decl.expression());
			}
		}

		return null;
	}


	// Helper to resolve known primitive types like "void"
	private Type resolveTypeFromCtx(String typeName)
	{
		Optional<Symbol> symbol = root.resolve(typeName);
		return symbol.map(Symbol::getType).orElse(new UnresolvedType(typeName));
	}

	// You must also implement visitors for properties, constructors, etc.
	// to define their symbols in this pass.
}