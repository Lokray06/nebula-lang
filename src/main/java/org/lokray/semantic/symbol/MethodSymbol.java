// File: src/main/java/org/lokray/semantic/MethodSymbol.java
package org.lokray.semantic.symbol;

import org.lokray.semantic.type.Type;

import java.util.List;
import java.util.stream.Collectors; // NEW

public class MethodSymbol extends Scope implements Symbol
{
	private boolean isMainMethod = false;

	private final String name;
    private final String mangledName;
	private Type returnType;
	// UPDATED: Use a list of ParameterSymbol instead of just Type
	private List<ParameterSymbol> parameters;
	private final boolean isStatic;
	private final boolean isPublic;
	private final boolean isConstructor;
	private final boolean isNative;

	public MethodSymbol(String name, Type returnType, List<ParameterSymbol> parameters, Scope enclosingScope, boolean isStatic, boolean isPublic, boolean isConstructor, boolean isNative)
	{
		super(enclosingScope);
		this.name = name;
		this.returnType = returnType;
		this.parameters = parameters;
		this.isStatic = isStatic;
		this.isPublic = isPublic;
		this.isConstructor = isConstructor;
		this.isNative = isNative;
        this.mangledName = constructMangledName();
	}

    private String constructMangledName()
    {
        Scope parent = this.getEnclosingScope();
        if (parent instanceof ClassSymbol)
        {
            ClassSymbol classSymbol = (ClassSymbol) parent;
            String fqn = classSymbol.getName();
            if (classSymbol.getEnclosingScope() instanceof NamespaceSymbol)
            {
                fqn = ((NamespaceSymbol) classSymbol.getEnclosingScope()).getFqn() + "." + classSymbol.getName();
            }

            String baseName = fqn.replace('.', '_') + "_" + this.getName();
            StringBuilder mangled = new StringBuilder(baseName);

            for (Type paramType : this.getParameterTypes())
            {
                if(paramType.getName().equalsIgnoreCase("String"))
                {
                    mangled.append("__").append("string");
                }
                else
                {
                    mangled.append("__").append(paramType.getName());
                }
            }

            if(this.getType().getName().equalsIgnoreCase("String"))
            {
                mangled.append("___").append("string");
            }
            else
            {
                mangled.append("___").append(this.getType().getName());
            }

            return mangled.toString();
        }
        return this.getName();
    }

	public void setIsMainMethod()
	{
		this.isMainMethod = true;
	}

	public boolean isMainMethod()
	{
		return isMainMethod;
	}

	@Override
	public String getName()
	{
		return name;
	}

    public String getMangledName()
    {
        return this.mangledName;
    }

	@Override
	public Type getType()
	{
		return returnType;
	}

	// NEW: Getter for the new parameter list
	public List<ParameterSymbol> getParameters()
	{
		return parameters;
	}

	// UPDATED: This can be derived from the new list for compatibility
	public List<Type> getParameterTypes()
	{
		return parameters.stream().map(Symbol::getType).collect(Collectors.toList());
	}

	public void setReturnType(Type returnType)
	{
		this.returnType = returnType;
	}

	// UPDATED: Setter for the new parameter list
	public void setParameters(List<ParameterSymbol> parameters)
	{
		this.parameters = parameters;
	}

	public boolean isStatic()
	{
		return isStatic;
	}

	public boolean isPublic()
	{
		return isPublic;
	}

	public boolean isConstructor()
	{
		return isConstructor;
	}

	public boolean isNative()
	{
		return isNative;
	}

    @Override
    public String toString()
    {
        String returnType = this.getType().getName();
        String methodName = this.getName();
        String parameterTypes = "";

        int i = 0;
        for (ParameterSymbol param : this.getParameters())
        {
            parameterTypes += param.getType().getName() + " " + param.getName();

            // Isn't last parameter
            if(!(i == this.getParameters().size() - 1))
            {
                parameterTypes += ", ";
            }
            i++;
        }


        return returnType + " " + methodName + "(" + parameterTypes + "){...};";
    }
}