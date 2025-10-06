// File: src/main/java/org/lokray/semantic/BuiltInTypeLoader.java
package org.lokray.semantic;

import org.lokray.semantic.symbol.Scope;
import org.lokray.semantic.symbol.TypeSymbol;
import org.lokray.semantic.type.NullType;
import org.lokray.semantic.type.PrimitiveType;

/**
 * Utility class responsible for defining all built-in primitive types
 * into the Global Scope of the Symbol Table.
 */
public class BuiltInTypeLoader
{
	/**
	 * Defines all primitive types as TypeSymbols in the specified Scope
	 * by querying the PrimitiveType class as the single source of truth.
	 *
	 * @param scope The scope (typically the Global Scope) to define the types in.
	 */
	public static void definePrimitives(Scope scope)
	{
		// Define all primitive types by getting them from the central registry
		PrimitiveType.getAllPrimitiveKeywords().forEach((name, type) ->
		{
			scope.define(new TypeSymbol(name, type));
		});

		// FIX: Removed special handling for 'string'. This is now handled by an alias
		// created in SemanticAnalyzer after the NDK is loaded, which is more robust.

		// Define 'null' as a special TypeSymbol that holds the NullType
		scope.define(new TypeSymbol("null", NullType.INSTANCE));
	}
}