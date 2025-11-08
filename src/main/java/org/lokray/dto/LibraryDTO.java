package org.lokray.dto;

import java.util.ArrayList;
import java.util.List;

public class LibraryDTO
{
	public String name;
	public String formatVersion = "1";
	public List<NamespaceDTO> namespaces = new ArrayList<>();
}
