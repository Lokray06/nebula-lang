package org.lokray.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class FileUtils
{
	public static List<String> load(Path filePath) throws IOException
	{
		// Read all lines from the file into a List<String>
		return Files.lines(filePath).collect(Collectors.toList());
	}

	public static String getFileExtension(Path path)
	{
		String fileName = path.getFileName().toString();
		int lastDotIndex = fileName.lastIndexOf('.');
		if (lastDotIndex > 0)
		{
			return fileName.substring(lastDotIndex);
		}
		return null;
	}
}
