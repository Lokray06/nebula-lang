package org.lokray.semantic.type;

// A helper class for Pass 1 to represent types that haven't been resolved to a ClassSymbol yet.
	public class UnresolvedType implements Type
	{
		private final String name;

		public UnresolvedType(String name)
		{
			this.name = name;
		}

		@Override
		public String getName()
		{
			return name;
		}

		@Override
		public Type getType()
		{
			return this;
		}

		@Override
		public boolean isAssignableTo(Type other)
		{
			return false;
		}
	}