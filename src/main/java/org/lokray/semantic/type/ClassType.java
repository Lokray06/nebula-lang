// File: src/main/java/org/lokray/semantic/type/ClassType.java
package org.lokray.semantic.type;

import org.lokray.semantic.symbol.ClassSymbol;

public class ClassType implements Type
{
	private final ClassSymbol classSymbol;

	public ClassType(ClassSymbol classSymbol)
	{
		this.classSymbol = classSymbol;
	}

	public ClassSymbol getClassSymbol()
	{
		return classSymbol;
	}

	@Override
	public String getName()
	{
		return classSymbol.getName();
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
		if (other instanceof NullType)
		{
			// This is incorrect for structs, but ClassType is for classes which are reference types.
			return true;
		}
		// TODO: Future enhancement for inheritance
		// if (other instanceof ClassType) {
		//     ClassSymbol current = this.classSymbol.getSuperClass();
		//     while (current != null) {
		//         if (current.getType().equals(other)) {
		//             return true;
		//         }
		//         current = current.getSuperClass();
		//     }
		// }

		return false;
	}

	@Override
	public boolean isReferenceType()
	{
		return true;
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
		ClassType classType = (ClassType) obj;
		return classSymbol.equals(classType.classSymbol);
	}

	/**
	 * Identifies if this symbol represents a struct.
	 *
	 * @return false for a regular class.
	 */
	public boolean isStruct()
	{
		return false;
	}

	@Override
	public int hashCode()
	{
		return classSymbol.hashCode();
	}
}