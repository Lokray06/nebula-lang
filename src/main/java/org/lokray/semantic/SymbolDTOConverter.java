// File: src/main/java/org/lokray/semantic/SymbolDTOConverter.java
package org.lokray.semantic;

import org.lokray.ndk.dto.ClassDTO;
import org.lokray.ndk.dto.FieldDTO;
import org.lokray.ndk.dto.MethodDTO;
import org.lokray.ndk.dto.NamespaceDTO;

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
				md.isStatic = ms.isStatic(); // CHANGE: Use the new boolean field
				dto.methods.add(md);
			}
			else if (s instanceof VariableSymbol vs)
			{
				FieldDTO fd = new FieldDTO();
				fd.name = vs.getName();
				// CHANGE: Convert Type object to its name string
				fd.type = vs.getType().getName();
				dto.fields.add(fd);
			}
		});
		return dto;
	}
}