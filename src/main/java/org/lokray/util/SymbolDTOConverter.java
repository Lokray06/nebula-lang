// File: src/main/java/org/lokray/semantic/SymbolDTOConverter.java
package org.lokray.util;

import org.lokray.ndk.dto.*;
import org.lokray.semantic.symbol.*;

import java.util.ArrayList;
import java.util.List;

public class SymbolDTOConverter
{
	public static List<NamespaceDTO> toNamespaces(Scope global)
	{
		List<NamespaceDTO> out = new ArrayList<>();
		global.forEachSymbol((name, sym) ->
		{
			if (sym instanceof NamespaceSymbol ns)
			{
				out.add(namespaceToDTO(ns));
			}
		});
		return out;
	}

	private static NamespaceDTO namespaceToDTO(NamespaceSymbol ns)
	{
		NamespaceDTO dto = new NamespaceDTO();
		dto.name = ns.getName();
		ns.forEachSymbol((n, s) ->
		{
			if (s instanceof NamespaceSymbol child)
			{
				dto.namespaces.add(namespaceToDTO(child));
			}
			else if (s instanceof StructSymbol ss)
			{
				dto.structs.add(structToDTO(ss));
			}
			else if (s instanceof ClassSymbol cs)
			{
				dto.classes.add(classToDTO(cs));
			}
		});
		return dto;
	}

	private static ClassDTO classToDTO(ClassSymbol cs)
	{
		ClassDTO dto = new ClassDTO();
		dto.name = cs.getName();
		dto.isNative = cs.isNative(); // CHANGE: Use the new boolean field

		cs.forEachSymbol((n, s) ->
		{
			if (s instanceof MethodSymbol ms)
			{
				MethodDTO md = new MethodDTO();
				md.name = ms.getName();
				md.isStatic = ms.isStatic();
				md.isNative = ms.isNative();
				md.isPublic = ms.isPublic();
				md.returnType = ms.getType().getName(); // Use the type name/FQN

				// --- MODIFICATION HERE ---
				// Iterate over the new ParameterSymbol list
				for (ParameterSymbol ps : ms.getParameters())
				{
					ParameterDTO pd = new ParameterDTO();
					pd.name = ps.getName();

					// Use the Unresolved Type Name (like "Object" or "string")
					// which is what the JSON is expected to contain before resolution
					pd.type = ps.getType().getName();

					md.parameters.add(pd);
				}
				// --- END MODIFICATION ---

				dto.methods.add(md);
			}
			else if (s instanceof VariableSymbol vs)
			{
				FieldDTO fd = new FieldDTO();
				fd.name = vs.getName();
				// CHANGE: Convert Type object to its name string
				fd.type = vs.getType().getName();

				// Modifiers
				fd.isNative = vs.isNative();
				fd.isConst = vs.isConst();
				fd.isPublic = vs.isPublic();
				fd.isStatic = vs.isStatic();

				dto.fields.add(fd);
			}
		});
		return dto;
	}

	private static StructDTO structToDTO(StructSymbol ss)
	{
		StructDTO dto = new StructDTO();
		dto.name = ss.getName();
		dto.isNative = ss.isNative(); // CHANGE: Use the new boolean field

		ss.forEachSymbol((n, s) ->
		{
			if (s instanceof MethodSymbol ms)
			{
				MethodDTO md = new MethodDTO();
				md.name = ms.getName();
				md.isStatic = ms.isStatic();
				md.isNative = ms.isNative();
				md.isPublic = ms.isPublic();
				md.returnType = ms.getType().getName(); // Use the type name/FQN

				// --- MODIFICATION HERE ---
				// Iterate over the new ParameterSymbol list
				for (ParameterSymbol ps : ms.getParameters())
				{
					ParameterDTO pd = new ParameterDTO();
					pd.name = ps.getName();

					// Use the Unresolved Type Name (like "Object" or "string")
					// which is what the JSON is expected to contain before resolution
					pd.type = ps.getType().getName();

					md.parameters.add(pd);
				}
				// --- END MODIFICATION ---

				dto.methods.add(md);
			}
			else if (s instanceof VariableSymbol vs)
			{
				FieldDTO fd = new FieldDTO();
				fd.name = vs.getName();
				// CHANGE: Convert Type object to its name string
				fd.type = vs.getType().getName();

				// Modifiers
				fd.isNative = vs.isNative();
				fd.isConst = vs.isConst();
				fd.isPublic = vs.isPublic();
				fd.isStatic = vs.isStatic();

				dto.fields.add(fd);
			}
		});
		return dto;
	}
}