// File: src/main/java/org/lokray/semantic/type/GenericType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.ClassSymbol;
import org.lokray.semantic.symbol.Scope;
import org.lokray.semantic.symbol.Symbol;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Represents an instantiated generic type, such as 'List<int>' or 'Dictionary<string, int>'.
 * This class holds the base generic class (e.g., List) and the concrete type
 * arguments (e.g., [int]).
 * <p>
 * It extends Scope so that member lookups (like 'myList.get()') can be resolved
 * against the base symbol, while allowing the TypeCheckVisitor to perform substitution.
 */
public class GenericType extends Scope implements Type
{
	private final ClassSymbol baseSymbol;
	private final List<Type> typeArguments;

	public GenericType(ClassSymbol baseSymbol, List<Type> typeArguments)
	{
		super(baseSymbol.getEnclosingScope()); // Inherit scope from base
		this.baseSymbol = baseSymbol;
		this.typeArguments = typeArguments;
	}

	public ClassSymbol getBaseSymbol()
	{
		return baseSymbol;
	}

	public List<Type> getTypeArguments()
	{
		return typeArguments;
	}

	@Override
	public String getName()
	{
		// Generates a name like "List<int, string>"
		String args = typeArguments.stream()
				.map(Type::getName)
				.collect(Collectors.joining(", "));
		return baseSymbol.getName() + "<" + args + ">";
	}

	@Override
	public Type getType()
	{
		return this;
	}

	@Override
	public boolean isAssignableTo(Type other)
	{
		if (this.equals(other))
		{
			return true;
		}

		if (other instanceof GenericType otherGeneric)
		{
			// Check if base types match (e.g., List<T> and List<U>)
			if (!this.baseSymbol.equals(otherGeneric.baseSymbol))
			{
				return false;
			}
			// Check for co/contra/invariance. For now, we assume invariance.
			// (e.g., List<string> is NOT assignable to List<Object>)
			return this.typeArguments.equals(otherGeneric.typeArguments);
		}

		// TODO: Check assignability to base interfaces or superclasses
		// e.g., if List<T> implements IEnumerable<T>,
		// then List<int> should be assignable to IEnumerable<int>.
		return false;
	}

	@Override
	public boolean isReferenceType()
	{
		// Instantiated generic classes are reference types
		// (unless the base is a struct, which is handled by its own type)
		return true;
	}

	@Override
	public ClassSymbol getClassSymbol()
	{
		// Returns the base symbol (e.g., 'List' for 'List<int>')
		return baseSymbol;
	}

	@Override
	public boolean equals(Object obj)
	{
        if (this == obj)
        {
            return true;
        }
        if (obj == null || getClass() != obj.getClass())
        {
            return false;
        }
		GenericType that = (GenericType) obj;
		// Two GenericTypes are equal if their base symbol and type arguments are all equal
		return baseSymbol.equals(that.baseSymbol) &&
				typeArguments.equals(that.typeArguments);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(baseSymbol, typeArguments);
	}

	// --- Member Resolution with Substitution ---

	/**
	 * Resolves a member from the base symbol.
	 * The TypeCheckVisitor will then use this base member and this GenericType
	 * instance to perform type substitution.
	 */
	@Override
	public java.util.Optional<Symbol> resolveLocally(String name)
	{
		// Resolve the member (field or method) from the base generic class (e.g., List<T>)
		return baseSymbol.resolveLocally(name);
	}
}