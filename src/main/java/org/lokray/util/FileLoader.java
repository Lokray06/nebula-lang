package org.lokray.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class FileLoader
{
	private final Path filePath;
	private List<String> data;

	public FileLoader(Path filePath)
	{
		this.filePath = filePath;
	}

	public void load() throws IOException
	{
		// Read all lines from the file into a List<String>
		this.data = Files.lines(this.filePath).collect(Collectors.toList());
	}

	public List<String> getData()
	{
		return this.data;
	}
}