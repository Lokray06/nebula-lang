// File: src/main/java/org/lokray/semantic/type/PrimitiveType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.Symbol;
import org.lokray.semantic.symbol.VariableSymbol;

import static org.lokray.parser.NebulaLexer.*;

import java.util.*;

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
	public static final PrimitiveType UINT8 = new PrimitiveType("uint8");
	public static final PrimitiveType UINT16 = new PrimitiveType("uint16");
	public static final PrimitiveType UINT32 = new PrimitiveType("uint32");
	public static final PrimitiveType UINT64 = new PrimitiveType("uint64");

	public static final PrimitiveType FLOAT = new PrimitiveType("float");
	public static final PrimitiveType DOUBLE = new PrimitiveType("double");
	// You can add uint, byte, etc., here following the same pattern

	// --- The Single Source of Truth for all keywords ---
	private static final Map<String, PrimitiveType> KEYWORD_TO_TYPE_MAP;
	private static final Map<PrimitiveType, Set<PrimitiveType>> WIDENING_MAP = new HashMap<>();


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

		// Define widening conversions
		Set<PrimitiveType> byteWidens = Set.of(SHORT, INT, LONG, FLOAT, DOUBLE, INT16, INT32, INT64);
		Set<PrimitiveType> shortWidens = Set.of(INT, LONG, FLOAT, DOUBLE, INT32, INT64);
		Set<PrimitiveType> intWidens = Set.of(LONG, FLOAT, DOUBLE, INT64, ULONG, UINT64); // Allow int to ulong
		Set<PrimitiveType> longWidens = Set.of(FLOAT, DOUBLE);
		Set<PrimitiveType> charWidens = Set.of(INT, LONG, FLOAT, DOUBLE, INT32, INT64);
		Set<PrimitiveType> floatWidens = Set.of(DOUBLE);

		WIDENING_MAP.put(BYTE, byteWidens);
		WIDENING_MAP.put(INT8, byteWidens);

		WIDENING_MAP.put(SHORT, shortWidens);
		WIDENING_MAP.put(INT16, shortWidens);

		WIDENING_MAP.put(INT, intWidens);
		WIDENING_MAP.put(INT32, intWidens);

		WIDENING_MAP.put(LONG, longWidens);
		WIDENING_MAP.put(INT64, longWidens);

		WIDENING_MAP.put(CHAR, charWidens);
		WIDENING_MAP.put(FLOAT, floatWidens);

		Set<PrimitiveType> ubyteWidens = Set.of(USHORT, UINT, ULONG, UINT16, UINT32, UINT64, SHORT, INT, LONG, FLOAT, DOUBLE, INT16, INT32, INT64);
		Set<PrimitiveType> ushortWidens = Set.of(UINT, ULONG, UINT32, UINT64, INT, LONG, FLOAT, DOUBLE, INT32, INT64);
		Set<PrimitiveType> uintWidens = Set.of(ULONG, UINT64, LONG, FLOAT, DOUBLE, INT64);
		Set<PrimitiveType> ulongWidens = Set.of(FLOAT, DOUBLE);

		WIDENING_MAP.put(UBYTE, ubyteWidens);
		WIDENING_MAP.put(UINT8, ubyteWidens);

		WIDENING_MAP.put(USHORT, ushortWidens);
		WIDENING_MAP.put(UINT16, ushortWidens);

		WIDENING_MAP.put(UINT, uintWidens);
		WIDENING_MAP.put(UINT32, uintWidens);

		WIDENING_MAP.put(ULONG, ulongWidens);
		WIDENING_MAP.put(UINT64, ulongWidens);
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

		// Handle type aliases (e.g., int and int32 are interchangeable)
		if (areEquivalent(this, other))
		{
			return true;
		}

		if (other instanceof PrimitiveType otherPrimitive)
		{
			Set<PrimitiveType> allowed = WIDENING_MAP.get(this);
			if (allowed != null && allowed.contains(otherPrimitive))
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean isNumeric()
	{
		return isInteger() || this.equals(FLOAT) || this.equals(DOUBLE);
	}

	@Override
	public boolean isInteger()
	{
		return this.equals(BYTE) || this.equals(SHORT) || this.equals(INT) || this.equals(LONG) ||
				this.equals(UBYTE) || this.equals(USHORT) || this.equals(UINT) || this.equals(ULONG) ||
				this.equals(INT8) || this.equals(INT16) || this.equals(INT32) || this.equals(INT64) ||
				this.equals(UINT8) || this.equals(UINT16) || this.equals(UINT32) || this.equals(UINT64) ||
				this.equals(CHAR); // Chars can be treated as integers
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

	private static boolean areEquivalent(Type a, Type b)
	{
		if (!(a instanceof PrimitiveType typeA) || !(b instanceof PrimitiveType typeB))
		{
			return false;
		}
		return getCanonicalType(typeA).equals(getCanonicalType(typeB));
	}

	private static PrimitiveType getCanonicalType(PrimitiveType type)
	{
		if (type.equals(INT8) || type.equals(BYTE_SPE_T))
		{
			return BYTE;
		}
		if (type.equals(INT16) || type.equals(SHORT_SPE_T))
		{
			return SHORT;
		}
		if (type.equals(INT32) || type.equals(INT_SPE_T))
		{
			return INT;
		}
		if (type.equals(INT64) || type.equals(LONG_SPE_T))
		{
			return LONG;
		}
		if (type.equals(UINT8) || type.equals(U_BYTE_SPE_T))
		{
			return UBYTE;
		}
		if (type.equals(UINT16) || type.equals(U_SHORT_SPE_T))
		{
			return USHORT;
		}
		if (type.equals(UINT32) || type.equals(U_INT_SPE_T))
		{
			return UINT;
		}
		if (type.equals(UINT64) || type.equals(U_LONG_SPE_T))
		{
			return ULONG;
		}
		return type;
	}
}
