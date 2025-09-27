package org.lokray.ndk.dto;

import java.util.ArrayList;
import java.util.List;

public class NamespaceDTO
{
	public String name;
	public List<NamespaceDTO> namespaces = new ArrayList<>();
	public List<ClassDTO> classes = new ArrayList<>();
}
