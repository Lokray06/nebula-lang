package org.lokray.dto;

import java.util.ArrayList;
import java.util.List;

public class StructDTO
{
	public String name;
	public boolean isNative = false;
	public boolean isStatic = false;
	public boolean isPublic = false;
	public List<MethodDTO> methods = new ArrayList<>();
	public List<FieldDTO> fields = new ArrayList<>();
}
