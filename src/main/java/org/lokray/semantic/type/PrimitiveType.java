// File: src/main/java/org/lokray/semantic/type/PrimitiveType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.Symbol;
import org.lokray.semantic.symbol.VariableSymbol;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class PrimitiveType implements Type
{
	// --- Canonical Type Instances ---
	public static final PrimitiveType VOID = new PrimitiveType("void");
	public static final PrimitiveType BOOLEAN = new PrimitiveType("bool");
	public static final PrimitiveType CHAR = new PrimitiveType("char");

	public static final PrimitiveType BYTE = new PrimitiveType("byte");
	public static final PrimitiveType SHORT = new PrimitiveType("short");
	public static final PrimitiveType INT = new PrimitiveType("int");
	public static final PrimitiveType LONG = new PrimitiveType("long");
	public static final PrimitiveType UBYTE = new PrimitiveType("ubyte");
	public static final PrimitiveType USHORT = new PrimitiveType("ushort");
	public static final PrimitiveType UINT = new PrimitiveType("uint");
	public static final PrimitiveType ULONG = new PrimitiveType("ulong");

	public static final PrimitiveType INT8 = new PrimitiveType("int8");
	public static final PrimitiveType INT16 = new PrimitiveType("int16");
	public static final PrimitiveType INT32 = new PrimitiveType("int32");
	public static final PrimitiveType INT64 = new PrimitiveType("int64");
	public static final PrimitiveType UINT8 = new PrimitiveType("ubyte");
	public static final PrimitiveType UINT16 = new PrimitiveType("ushort");
	public static final PrimitiveType UINT32 = new PrimitiveType("uint");
	public static final PrimitiveType UINT64 = new PrimitiveType("ulong");

	public static final PrimitiveType FLOAT = new PrimitiveType("float");
	public static final PrimitiveType DOUBLE = new PrimitiveType("double");
	// You can add uint, byte, etc., here following the same pattern

	// --- The Single Source of Truth for all keywords ---
	private static final Map<String, PrimitiveType> KEYWORD_TO_TYPE_MAP;

	// Static initializer block to populate the map once
	static
	{
		Map<String, PrimitiveType> map = new HashMap<>();
		// Special
		map.put("void", VOID);
		map.put("bool", BOOLEAN);
		map.put("char", CHAR);

		// Integers
		map.put("byte", BYTE);
		map.put("short", SHORT);
		map.put("int", INT);
		map.put("long", LONG);
		map.put("int8", INT8);
		map.put("int16", INT16);
		map.put("int32", INT32);
		map.put("int64", INT64);
		map.put("ubyte", UBYTE);
		map.put("ushort", USHORT);
		map.put("uint", UINT);
		map.put("ulong", ULONG);
		map.put("uint8", UINT8);
		map.put("uint16", UINT16);
		map.put("uint32", UINT32);
		map.put("uint64", UINT64);

		// Floating point
		map.put("float", FLOAT);
		map.put("double", DOUBLE);

		KEYWORD_TO_TYPE_MAP = Collections.unmodifiableMap(map);
	}

	/**
	 * Public method for the loader to get all keywords and their associated types.
	 *
	 * @return An unmodifiable map of all primitive keywords.
	 */
	public static Map<String, PrimitiveType> getAllPrimitiveKeywords()
	{
		return KEYWORD_TO_TYPE_MAP;
	}


	// --- Instance Members ---
	private final String name;
	private final Map<String, Symbol> staticProperties = new HashMap<>();

	private PrimitiveType(String name)
	{
		this.name = name;
		initializeStaticProperties();
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public Type getType()
	{
		return this; // A type is its own type
	}

	@Override
	public boolean isAssignableTo(Type other)
	{
		if (this.equals(other))
		{
			return true;
		}
		// Allow assigning integer literals to wider numeric types
		if (this.isNumeric() && other.isNumeric())
		{
			// A more robust implementation would check for widening conversions only
			return true;
		}
		return false;
	}

	@Override
	public boolean isNumeric()
	{
		return this.equals(INT) || this.equals(LONG) || this.equals(FLOAT) || this.equals(DOUBLE) || this.equals(CHAR);
	}

	@Override
	public boolean isInteger()
	{
		return this.equals(INT) || this.equals(LONG) || this.equals(CHAR);
	}

	private void initializeStaticProperties()
	{
		if (isInteger())
		{
			staticProperties.put("min", new VariableSymbol("min", this, true, true, true));
			staticProperties.put("max", new VariableSymbol("max", this, true, true, true));
		}
		else if (isNumeric())
		{ // float, double
			staticProperties.put("max", new VariableSymbol("max", this, true, true, true));
		}
	}

	public Symbol resolveStaticProperty(String name)
	{
		return staticProperties.get(name);
	}
}