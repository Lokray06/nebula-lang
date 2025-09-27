package org.lokray.semantic;

import java.util.Set;

/**
 * Utility class responsible for defining all built-in primitive types
 * into the Global Scope of the Symbol Table.
 * This abstracts the language's primitive types away from the core
 * semantic analysis logic.
 */
public class BuiltInTypeLoader
{

	// A complete set of all Nebula primitive type keywords.
	private static final Set<String> PRIMITIVE_TYPES = Set.of(
			// Special
			"void", "null",
			"bool",
			"char", "string",

			// Integers
			"byte", "short", "int", "long",         // Normal
			"ubyte", "ushort", "uint", "ulong",     // Normal Unsigned
			"int8", "int16", "int32", "int64",      // Explicit
			"uint8", "uint16", "uint32", "uint64",  // Explicit unsigned

			// Floating point
			"float", "double"
	);

	/**
	 * Defines all primitive types as TypeSymbols in the specified Scope.
	 *
	 * @param scope The scope (typically the Global Scope) to define the types in.
	 */
	public static void definePrimitives(Scope scope)
	{
		for (String typeName : PRIMITIVE_TYPES)
		{
			scope.define(new TypeSymbol(typeName));
		}
	}
}