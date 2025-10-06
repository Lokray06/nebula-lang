// File: src/main/java/org/lokray/semantic/type/TupleType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.Scope;
import org.lokray.semantic.symbol.TupleElementSymbol;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents a tuple type, which is an ordered sequence of typed values.
 * It also acts as a Scope to allow member access to its elements (e.g., tuple.Item1, tuple.Sum).
 */
public class TupleType extends Scope implements Type
{
	private final List<TupleElementSymbol> elements;

	public TupleType(List<TupleElementSymbol> elements)
	{
		super(null); // Tuples do not have an enclosing scope.
		this.elements = elements;

		// Define all elements as symbols within this tuple's scope.
		for (int i = 0; i < elements.size(); i++)
		{
			TupleElementSymbol element = elements.get(i);

			// Define by its explicit name, if one was provided.
			if (element.getName() != null && !element.getName().isBlank())
			{
				define(element);
			}
			// Also define the default 'ItemN' accessor.
			String defaultName = "Item" + (i + 1);
			define(new TupleElementSymbol(defaultName, element.getType(), i));
		}
	}

	public List<TupleElementSymbol> getElements()
	{
		return elements;
	}

	@Override
	public String getName()
	{
		// Generates a string representation like "(int Count, string)"
		return "(" + elements.stream()
				.map(e -> e.getType().getName() + (e.getName() != null && !e.getName().isBlank() ? " " + e.getName() : ""))
				.collect(Collectors.joining(", ")) + ")";
	}

	@Override
	public Type getType()
	{
		return this;
	}

	@Override
	public boolean isAssignableTo(Type other)
	{
        if (this == other)
        {
            return true;
        }
		if (!(other instanceof TupleType otherTuple))
		{
			return false;
		}
		if (this.elements.size() != otherTuple.elements.size())
		{
			return false;
		}
		// For type-to-type assignment, we check for positional compatibility.
		for (int i = 0; i < elements.size(); i++)
		{
			Type thisElementType = this.elements.get(i).getType();
			Type otherElementType = otherTuple.elements.get(i).getType();
			if (!thisElementType.isAssignableTo(otherElementType))
			{
				return false;
			}
		}
		return true;
	}

	@Override
	public boolean isTuple()
	{
		return true;
	}

	@Override
	public boolean isReferenceType()
	{
		return true; // Tuples behave like reference types.
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
		TupleType tupleType = (TupleType) o;
		return Objects.equals(elements, tupleType.elements);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(elements);
	}
}