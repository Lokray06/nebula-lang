package org.lokray.dto;

import java.util.ArrayList;
import java.util.List;

public class MethodDTO
{
	public String name;
	public boolean isStatic = false;
	public boolean isNative = false;
	public boolean isPublic = false;
	public String returnType;
	public List<ParameterDTO> parameters = new ArrayList<>();
}
