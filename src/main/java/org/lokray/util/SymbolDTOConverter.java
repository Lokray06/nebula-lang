// File: src/main/java/org/lokray/util/SymbolDTOConverter.java
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
		dto.isNative = cs.isNative();

		// **Corrected:** Iterate over the dedicated method map that stores all overloads.
		cs.getMethodsByName().values().forEach(overloadList ->
		{
			overloadList.forEach(ms ->
			{
				MethodDTO md = new MethodDTO();
				md.name = ms.getName();
				md.isStatic = ms.isStatic();
				md.isNative = ms.isNative();
				md.isPublic = ms.isPublic();
				md.returnType = ms.getType().getName();

				for (ParameterSymbol ps : ms.getParameters())
				{
					ParameterDTO pd = new ParameterDTO();
					pd.name = ps.getName();
					pd.type = ps.getType().getName();
					md.parameters.add(pd);
				}
				dto.methods.add(md);
			});
		});

		// **Corrected:** Iterate over the general symbol map specifically for fields,
		// excluding any method symbols that might also be present.
		cs.getSymbols().values().stream()
				.filter(s -> s instanceof VariableSymbol && !(s instanceof MethodSymbol))
				.forEach(s ->
				{
					VariableSymbol vs = (VariableSymbol) s;
					FieldDTO fd = new FieldDTO();
					fd.name = vs.getName();
					fd.type = vs.getType().getName();
					fd.isNative = vs.isNative();
					fd.isConst = vs.isConst();
					fd.isPublic = vs.isPublic();
					fd.isStatic = vs.isStatic();
					dto.fields.add(fd);
				});

		return dto;
	}

	private static StructDTO structToDTO(StructSymbol ss)
	{
		StructDTO dto = new StructDTO();
		dto.name = ss.getName();
		dto.isNative = ss.isNative();

		// **Corrected:** Iterate over the dedicated method map that stores all overloads.
		ss.getMethodsByName().values().forEach(overloadList ->
		{
			overloadList.forEach(ms ->
			{
				MethodDTO md = new MethodDTO();
				md.name = ms.getName();
				md.isStatic = ms.isStatic();
				md.isNative = ms.isNative();
				md.isPublic = ms.isPublic();
				md.returnType = ms.getType().getName();

				for (ParameterSymbol ps : ms.getParameters())
				{
					ParameterDTO pd = new ParameterDTO();
					pd.name = ps.getName();
					pd.type = ps.getType().getName();
					md.parameters.add(pd);
				}
				dto.methods.add(md);
			});
		});

		// **Corrected:** Iterate over the general symbol map specifically for fields,
		// excluding any method symbols that might also be present.
		ss.getSymbols().values().stream()
				.filter(s -> s instanceof VariableSymbol && !(s instanceof MethodSymbol))
				.forEach(s ->
				{
					VariableSymbol vs = (VariableSymbol) s;
					FieldDTO fd = new FieldDTO();
					fd.name = vs.getName();
					fd.type = vs.getType().getName();
					fd.isNative = vs.isNative();
					fd.isConst = vs.isConst();
					fd.isPublic = vs.isPublic();
					fd.isStatic = vs.isStatic();
					dto.fields.add(fd);
				});

		return dto;
	}
}