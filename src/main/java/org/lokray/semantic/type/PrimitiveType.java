// File: src/main/java/org/lokray/semantic/type/PrimitiveType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.ClassSymbol;
import org.lokray.semantic.symbol.StructSymbol;
import org.lokray.semantic.symbol.Symbol;
import org.lokray.semantic.symbol.VariableSymbol;

import java.math.BigInteger;
import java.util.*;

import static org.lokray.parser.NebulaLexer.*;

public class PrimitiveType implements Type
{
	// --- Canonical Type Instances ---
	public static final PrimitiveType VOID = new PrimitiveType("void");
	public static final PrimitiveType BOOLEAN = new PrimitiveType("bool");
	public static final PrimitiveType CHAR = new PrimitiveType("char");

	public static final PrimitiveType SBYTE = new PrimitiveType("sbyte");
	public static final PrimitiveType SHORT = new PrimitiveType("short");
	public static final PrimitiveType INT = new PrimitiveType("int");
	public static final PrimitiveType LONG = new PrimitiveType("long");
	public static final PrimitiveType BYTE = new PrimitiveType("byte");
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


	// --- NEW: BigInteger Range Constants ---
	public static final BigInteger MIN_SBYTE = BigInteger.valueOf(Byte.MIN_VALUE);
	public static final BigInteger MAX_SBYTE = BigInteger.valueOf(Byte.MAX_VALUE);
	public static final BigInteger MIN_BYTE = BigInteger.ZERO;
	public static final BigInteger MAX_BYTE = BigInteger.valueOf(255);

	public static final BigInteger MIN_SHORT = BigInteger.valueOf(Short.MIN_VALUE);
	public static final BigInteger MAX_SHORT = BigInteger.valueOf(Short.MAX_VALUE);
	public static final BigInteger MIN_USHORT = BigInteger.ZERO;
	public static final BigInteger MAX_USHORT = BigInteger.valueOf(65535);

	public static final BigInteger MIN_INT = BigInteger.valueOf(Integer.MIN_VALUE);
	public static final BigInteger MAX_INT = BigInteger.valueOf(Integer.MAX_VALUE);
	public static final BigInteger MIN_UINT = BigInteger.ZERO;
	public static final BigInteger MAX_UINT = new BigInteger("4294967295");

	public static final BigInteger MIN_LONG = BigInteger.valueOf(Long.MIN_VALUE);
	public static final BigInteger MAX_LONG = BigInteger.valueOf(Long.MAX_VALUE);
	public static final BigInteger MIN_ULONG = BigInteger.ZERO;
	public static final BigInteger MAX_ULONG = new BigInteger("18446744073709551615");

	// --- The Single Source of Truth for all keywords ---
	private static final Map<String, PrimitiveType> KEYWORD_TO_TYPE_MAP;
	private static final Map<PrimitiveType, Set<PrimitiveType>> WIDENING_MAP = new HashMap<>();
	private StructSymbol backingStruct = null;


	// Static initializer block to populate the map once
	static
	{
		Map<String, PrimitiveType> map = new HashMap<>();
		// Special
		map.put("void", VOID);
		map.put("bool", BOOLEAN);
		map.put("char", CHAR);

		// Integers
		map.put("sbyte", SBYTE);
		map.put("short", SHORT);
		map.put("int", INT);
		map.put("long", LONG);
		map.put("int8", INT8);
		map.put("int16", INT16);
		map.put("int32", INT32);
		map.put("int64", INT64);
		map.put("byte", BYTE);
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

		WIDENING_MAP.put(SBYTE, byteWidens);
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

		WIDENING_MAP.put(BYTE, ubyteWidens);
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

	public void setBackingStruct(StructSymbol symbol)
	{
		this.backingStruct = symbol;
	}

	public StructSymbol getBackingStruct()
	{
		return this.backingStruct;
	}

	@Override
	public ClassSymbol getClassSymbol()
	{
		return this.backingStruct;
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

		// --- START FIX ---
		// Allow any primitive (except void) to be "boxed" or assigned to Object
		if (other.isReferenceType() && other.getName().equals("Object"))
		{
			return this != VOID; // void cannot be assigned to Object
		}
		// --- END FIX ---

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
		return this.equals(SBYTE) || this.equals(SHORT) || this.equals(INT) || this.equals(LONG) ||
				this.equals(BYTE) || this.equals(USHORT) || this.equals(UINT) || this.equals(ULONG) ||
				this.equals(INT8) || this.equals(INT16) || this.equals(INT32) || this.equals(INT64) ||
				this.equals(UINT8) || this.equals(UINT16) || this.equals(UINT32) || this.equals(UINT64) ||
				this.equals(CHAR); // Chars can be treated as integers
	}

	public boolean isUnsigned()
	{
		return this == SBYTE || this == USHORT || this == UINT || this == ULONG ||
				this == UINT8 || this == UINT16 || this == UINT32 || this == UINT64;
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

	@Override
	public boolean isBoolean()
	{
		return this.equals(BOOLEAN);
	}

	public Symbol resolveStaticProperty(String name)
	{
		return staticProperties.get(name);
	}

	public static boolean areEquivalent(Type a, Type b)
	{
		if (!(a instanceof PrimitiveType typeA) || !(b instanceof PrimitiveType typeB))
		{
			return false;
		}

		if (typeA.isUnsigned() != typeB.isUnsigned())
		{
			// Different signedness — not equivalent
			return false;
		}

		return getCanonicalType(typeA).equals(getCanonicalType(typeB));
	}

	private static PrimitiveType getCanonicalType(PrimitiveType type)
	{
		if (type.equals(INT8) || type.equals(BYTE_SPE_T))
		{
			return SBYTE;
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
			return BYTE;
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

	public static Type getWiderType(Type a, Type b)
	{
		// If both are exactly the same type, return it
		if (a.equals(b))
		{
			return a;
		}

		// Only handle primitive types for now
		if (!(a instanceof PrimitiveType pa) || !(b instanceof PrimitiveType pb))
		{
			// For non-primitives, no widening — just return an error or one of them
			return a.isAssignableTo(b) ? b : (b.isAssignableTo(a) ? a : ErrorType.INSTANCE);
		}

		// If one can be assigned to the other, the wider type is the one it can be assigned to.
		if (pa.isAssignableTo(pb))
		{
			return pb;
		}
		if (pb.isAssignableTo(pa))
		{
			return pa;
		}

		// If neither can widen to the other, choose a fallback — for numeric types, prefer float/double
		if (pa.isNumeric() && pb.isNumeric())
		{
			if (pa.equals(DOUBLE) || pb.equals(DOUBLE))
			{
				return DOUBLE;
			}
			if (pa.equals(FLOAT) || pb.equals(FLOAT))
			{
				return FLOAT;
			}
			if (pa.equals(LONG) || pb.equals(LONG))
			{
				return LONG;
			}
			if (pa.equals(INT) || pb.equals(INT))
			{
				return INT;
			}
		}

		// Otherwise, incompatible types
		return ErrorType.INSTANCE;
	}

	@Override
	public boolean isValidForMainReturnMain()
	{
		// Valid main return types:
		// - void
		// - signed integers <= 32 bits
		// - unsigned integers <= 16 bits
		return  this == VOID ||                 // void
				this == SBYTE || this == INT8 || // signed 8-bit
				this == SHORT || this == INT16 || // signed 16-bit
				this == INT || this == INT32 ||   // signed 32-bit
				this == BYTE || this == UINT8 ||  // unsigned 8-bit
				this == USHORT || this == UINT16; // unsigned 16-bit
	}


	// NEW Helper: Get BigInteger MIN value for this primitive type
	public Optional<BigInteger> getMinValue()
	{
		return switch (this.name)
		{
            case "sbyte", "int8", "char" -> Optional.of(MIN_SBYTE);
            case "short", "int16" -> Optional.of(MIN_SHORT);
			case "int", "int32" -> Optional.of(MIN_INT);
			case "long", "int64" -> Optional.of(MIN_LONG);
			case "byte", "uint8" -> Optional.of(MIN_BYTE);
			case "ushort", "uint16" -> Optional.of(MIN_USHORT);
			case "uint", "uint32" -> Optional.of(MIN_UINT);
			case "ulong", "uint64" -> Optional.of(MIN_ULONG);
			default -> Optional.empty(); // Not an integer type
		};
	}

	// NEW Helper: Get BigInteger MAX value for this primitive type
	public Optional<BigInteger> getMaxValue()
	{
		return switch (this.name)
		{
            case "sbyte", "int8", "char" -> Optional.of(MAX_SBYTE);
            case "short", "int16" -> Optional.of(MAX_SHORT);
			case "int", "int32" -> Optional.of(MAX_INT);
			case "long", "int64" -> Optional.of(MAX_LONG);
			case "byte", "uint8" -> Optional.of(MAX_BYTE);
			case "ushort", "uint16" -> Optional.of(MAX_USHORT);
			case "uint", "uint32" -> Optional.of(MAX_UINT);
			case "ulong", "uint64" -> Optional.of(MAX_ULONG);
			default -> Optional.empty(); // Not an integer type
		};
	}
}
