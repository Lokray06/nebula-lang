// File: src/main/java/org/lokray/semantic/type/ArrayType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.ClassSymbol;

import java.util.Objects;

/**
 * Represents a fixed-size array type, like 'int[]'.
 * The size isn't stored here; it's tracked during semantic analysis and codegen
 * for specific variable instances. This type represents the general concept T[].
 */
public class ArrayType implements Type
{
	private final Type elementType;
	// We might add a built-in Array class symbol later for methods like .length
	// private static ClassSymbol backingArrayClass = null; // Example placeholder

	public ArrayType(Type elementType)
	{
		if (elementType == null || elementType instanceof ErrorType || elementType == PrimitiveType.VOID)
		{
			// Handle error case: cannot have array of void or error type
			// You might want to log an error here via an ErrorHandler passed in
			this.elementType = ErrorType.INSTANCE;
		}
		else
		{
			this.elementType = elementType;
		}
	}

	public Type getElementType()
	{
		return elementType;
	}

	@Override
	public String getName()
	{
		// Represent as elementTypeName[]
		return elementType.getName() + "[]";
	}

	@Override
	public Type getType()
	{
		// An array type's "type" is itself
		return this;
	}

	@Override
	public boolean isAssignableTo(Type other)
	{
		// Allow assignment to 'Object' (assuming Object is the root reference type)
		// Adjust "Object" if your root type has a different name
		if (other.isReferenceType() && other.getName().equals("Object"))
		{
			return true;
		}

		// Allow assigning null to array types
		if (other instanceof NullType)
		{
			return true;
		}

		// Check if 'other' is also an ArrayType
		if (!(other instanceof ArrayType otherArray))
		{
			return false;
		}

		// Arrays are typically invariant or covariant depending on the language.
		// Let's assume invariance for Nebula for simplicity (T[] assignable only to T[]).
		// If you want covariance (e.g., string[] to object[]), check element type assignability:
		// return this.elementType.isAssignableTo(otherArray.elementType);
		return this.elementType.equals(otherArray.elementType);
	}

	@Override
	public boolean isReferenceType()
	{
		// Treat arrays as reference types (stored via pointer/descriptor)
		return true;
	}

	@Override
	public boolean isArray()
	{
		return true; // Mark this type as an array
	}

	// Optional: Link to a potential built-in Array class symbol
	@Override
	public ClassSymbol getClassSymbol()
	{
		// return backingArrayClass; // Return null until a backing class is defined
		return null;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (o == null || getClass() != o.getClass())
		{
			return false;
		}
		ArrayType arrayType = (ArrayType) o;
		return Objects.equals(elementType, arrayType.elementType);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(elementType);
	}
}
