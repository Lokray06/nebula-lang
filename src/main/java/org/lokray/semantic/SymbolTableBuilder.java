// File: src/main/java/org/lokray/semantic/OldSymbolTableBuilder.1java
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

	/**
	 * Compute the fully qualified name (FQN) for a type declaration
	 * based on the current scope chain (namespaces and enclosing classes).
	 */
	private String getFqn(NebulaParser.TypeDeclarationContext ctx)
	{
		String simpleName = ctx.ID().getText();

		Scope scopeCheck = currentScope;

		// Walk up through namespaces/classes until reaching the root.
		while (scopeCheck != null)
		{
			if (scopeCheck instanceof NamespaceSymbol ns)
			{
				String nsFqn = ns.getFqn();
				return nsFqn.isEmpty() ? simpleName : nsFqn + "." + simpleName;
			}
			else if (scopeCheck instanceof ClassSymbol cs)
			{
				String classFqn = cs.getFqn();
				return classFqn + "." + simpleName;
			}
			scopeCheck = scopeCheck.getEnclosingScope();
		}

		// No namespace/class → top-level type
		return simpleName;
	}

	/**
	 * Resolve a Type from the parser TypeContext.
	 * Updated to use tupleElement (the renamed rule).
	 */
	private Type resolveTypeFromCtx(NebulaParser.TypeContext ctx)
	{
		if (ctx == null)
		{
			return ErrorType.INSTANCE;
		}

		// Handle tuple types like (int, string Name)
		if (ctx.tupleType() != null)
		{
			List<TupleElementSymbol> elements = new ArrayList<>();
			for (int i = 0; i < ctx.tupleType().tupleElement().size(); i++)
			{
				var elementCtx = ctx.tupleType().tupleElement(i);
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

	/**
	 * Unified handler for both classes and structs (native or not).
	 * Branches on CLASS_KW / STRUCT_KW and NATIVE_KW.
	 */
	@Override
	public Void visitTypeDeclaration(NebulaParser.TypeDeclarationContext ctx)
	{
		boolean isNative = ctx.NATIVE_KW() != null;
		boolean isClass = ctx.CLASS_KW() != null;
		boolean isStruct = ctx.STRUCT_KW() != null;

		if (!isClass && !isStruct)
		{
			// should not happen
			return null;
		}

		String typeName = ctx.ID().getText();

		// Discovery-only first-pass for types (structs historically used this; we apply for both for safety)
		if (discoveryOnly)
		{
			if (currentScope.resolveLocally(typeName).isPresent())
			{
				errorHandler.logError(ctx.ID().getSymbol(), "Type '" + typeName + "' is already defined in this scope.", currentClass);
				return null;
			}

			boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");
			ClassSymbol newTypeSymbol;

			if (isClass)
			{
				ClassSymbol classSymbol = new ClassSymbol(typeName, currentScope, isNative, isPublic);
				String fqn = getFqn(ctx);
				Debug.logWarning("Resolved FQN for type '" + typeName + "' -> " + fqn);
				declaredClasses.put(fqn, classSymbol);
				currentScope.define(classSymbol);
				Debug.logDebug("Defined " + (isNative ? "native " : "") + "class " + classSymbol.getName());
				newTypeSymbol = classSymbol;
			}
			else // struct
			{
				StructSymbol structSymbol = new StructSymbol(typeName, currentScope, isPublic, isNative);
				String fqn = getFqn(ctx);
				Debug.logWarning("Resolved FQN for type '" + typeName + "' -> " + fqn);
				declaredClasses.put(fqn, structSymbol);
				currentScope.define(structSymbol);
				Debug.logDebug("Defined " + (isNative ? "native " : "") + "struct " + structSymbol.getName());
				newTypeSymbol = structSymbol;
			}

			// Process its type parameters.
			// We must also push the symbol onto the scope stack *before* processing
			// parameters, so the parameters are defined *inside* the class scope.
			Scope oldScope = currentScope;
			currentScope = newTypeSymbol;

			if (ctx.typeParameterList() != null)
			{
				Debug.logDebug("  Parsing type parameters for " + typeName + "...");
				for (var paramCtx : ctx.typeParameterList().typeParameter())
				{
					String paramName = paramCtx.ID().getText();

					// Check if parameter name conflicts with the class name
					if (paramName.equals(typeName))
					{
						errorHandler.logError(paramCtx.ID().getSymbol(), "A type parameter cannot have the same name as its enclosing type '" + paramName + "'.", null); // currentClass is null here
						continue;
					}

					TypeParameterSymbol paramSymbol = new TypeParameterSymbol(paramName);
					newTypeSymbol.addTypeParameter(paramSymbol);
					currentScope.define(paramSymbol); // Define 'T' within the class's scope
					Debug.logDebug("    Defined type parameter: " + paramName);
				}
			}

			// Restore the original scope (e.g., the namespace)
			currentScope = oldScope;
			return null;
		}

		// Non-discovery pass: define members, visit children
		if (isClass)
		{
			// Retrieve the ClassSymbol that was created in the discovery pass.
			Optional<Symbol> symbol = currentScope.resolveLocally(typeName);
			if (symbol.isEmpty() || !(symbol.get() instanceof ClassSymbol))
			{
				// This should not happen if discovery ran correctly
				errorHandler.logError(ctx.ID().getSymbol(), "Internal error: Class symbol '" + typeName + "' not found in Pass 2.", currentClass);
				return null;
			}

			ClassSymbol cs = (ClassSymbol) symbol.get();

			// Set scopes
			ClassSymbol oldClass = currentClass;
			currentClass = cs;
			Scope oldScope = currentScope;
			currentScope = cs;

			// Type parameters must also be defined in the scope for Pass 2
			// so that member definitions (fields, methods) can resolve them.
			for (TypeParameterSymbol paramSymbol : cs.getTypeParameters())
			{
				currentScope.define(paramSymbol);
			}

			// Visit all members (fields, methods, constructors, properties)
			visitChildren(ctx);

			// Define implicit parameterless constructor if none found
			if (!cs.getMethodsByName().containsKey(cs.getName()))
			{
				MethodSymbol defaultCtor = new MethodSymbol(
						cs.getName(),
						cs.getType(),
						new ArrayList<>(),
						cs,
						false, // not static
						true,  // public
						true,  // isConstructor
						false  // not native
				);
				cs.defineMethod(defaultCtor);
			}

			// Restore scopes
			currentScope = oldScope;
			currentClass = oldClass;
			return null;
		}
		else // struct (non-discovery)
		{
			Optional<Symbol> symbol = currentScope.resolveLocally(typeName);
			if (symbol.isEmpty() || !(symbol.get() instanceof StructSymbol))
			{
				// internal error or not discovered — bail
				return null;
			}

			StructSymbol structSymbol = (StructSymbol) symbol.get();
			ClassSymbol oldClass = currentClass;
			currentClass = structSymbol;
			Scope oldScope = currentScope;
			currentScope = structSymbol;

			// Re-define parameters for Pass 2
			for (TypeParameterSymbol paramSymbol : structSymbol.getTypeParameters())
			{
				currentScope.define(paramSymbol);
			}

			visitChildren(ctx);

			// Ensure implicit ctor exists
			if (!structSymbol.getMethodsByName().containsKey(structSymbol.getName()))
			{
				MethodSymbol defaultCtor = new MethodSymbol(
						structSymbol.getName(),
						structSymbol.getType(),
						new ArrayList<>(),
						structSymbol,
						false,
						true,
						true,
						false
				);
				structSymbol.defineMethod(defaultCtor);
			}

			currentScope = oldScope;
			currentClass = oldClass;
			return null;
		}
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

		// Build a list of ParameterSymbol
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
		boolean isNative = ctx.NATIVE_KW() != null;

		MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), returnType, params, currentScope, isStatic, isPublic, false, isNative);
		currentClass.defineMethod(ms);

		// Check for Main Method
		if (ms.getName().equals("main"))
		{
			ms.setIsMainMethod();
			Debug.logDebug("STB: Identified primary 'main' method:" + ms.getMangledName());
		}

		// If method has a body and is not native, visit its children (for discovery we may skip but here not)
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

		// Build a list of ParameterSymbol
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
		boolean isNative = ctx.NATIVE_KW() != null;

		MethodSymbol ms = new MethodSymbol(ctx.ID().getText(), currentClass.getType(), params, currentScope, false, isPublic, true, isNative);
		currentClass.defineMethod(ms);

		// Do not visit body here; TypeCheck will visit and check bodies
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
		boolean isNative = ctx.NATIVE_KW() != null;

		for (var declarator : ctx.variableDeclarator())
		{
			String varName = declarator.ID().getText();
			if (currentClass.resolveLocally(varName).isPresent())
			{
				errorHandler.logError(declarator.ID().getSymbol(), "Field '" + varName + "' is already defined in this class.", currentClass);
				continue;
			}

			VariableSymbol vs = new VariableSymbol(varName, fieldType, isStatic, isPublic, isConst, isNative);
			currentClass.define(vs);
		}
		return null;
	}

	@Override
	public Void visitPropertyDeclaration(NebulaParser.PropertyDeclarationContext ctx)
	{
		// define property symbol (could be implemented in Symbol classes)
		String propName = ctx.ID().getText();
		Type propType = resolveTypeFromCtx(ctx.type());
		boolean isNative = ctx.NATIVE_KW() != null;
		boolean isPublic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("private");
		boolean isStatic = ctx.modifiers() == null || !ctx.modifiers().getText().contains("static");

		// Represent property as a symbol if your design uses it
		VariableSymbol propSym = new VariableSymbol(propName, propType, isStatic, isNative, isPublic);
		currentClass.define(propSym);

		// Create accessor method symbols (get_/set_)
		for (var acc : ctx.accessorDeclaration())
		{
			boolean isGetter = acc.GET_KW() != null;
			String accessorName = isGetter ? "get_" + propName : "set_" + propName;
			List<ParameterSymbol> params = new ArrayList<>();
			if (!isGetter)
			{
				params.add(new ParameterSymbol("value", propType, 0, null));
			}
			MethodSymbol accessorMethod = new MethodSymbol(accessorName, isGetter ? propType : PrimitiveType.VOID, params, currentScope, false, isPublic, false, isNative);
			currentClass.defineMethod(accessorMethod);
		}

		// Optional initializer handled in TypeCheckVisitor
		return null;
	}
	// variableDeclarator, parameter handling and other rules will be visited by visitors later
}
