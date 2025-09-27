package org.lokray.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class NebulaCompilerArguments
{
	private static final String IGNORE_EXTENSIONS_FLAG = "--ignore-file-extensions";
	private static final String VERBOSE = "-v";
	private static final List<String> VALID_EXTENSIONS = Arrays.asList(".neb", ".nebproj");

	private final Path filePath;
	private final boolean ignoreExtension;

	private NebulaCompilerArguments(Path filePath, boolean ignoreExtension)
	{
		this.filePath = filePath;
		this.ignoreExtension = ignoreExtension;
	}

	public static NebulaCompilerArguments parse(String[] args)
	{
		if (args.length < 1)
		{
			Debug.printUsage(args);
			throw new IllegalArgumentException("Invalid number of arguments.");
		}

		String filePathString = null;
		boolean ignoreExtension = false;

		for (String arg : args)
		{
			if (arg.equals(IGNORE_EXTENSIONS_FLAG))
			{
				ignoreExtension = true;
			}
			else if (arg.equals(VERBOSE))
			{
				Debug.ENABLE_DEBUG = true;
			}
			else
			{
				filePathString = arg;
			}
		}

		if (filePathString == null)
		{
			Debug.printUsage(args);
			throw new IllegalArgumentException("File path not provided.");
		}

		return new NebulaCompilerArguments(Paths.get(filePathString), ignoreExtension);
	}

	public Path getFilePath()
	{
		return filePath;
	}

	public boolean shouldIgnoreExtension()
	{
		return ignoreExtension;
	}

	public void validateFile()
	{
		if (!shouldIgnoreExtension())
		{
			String fileExtension = FileUtils.getFileExtension(filePath);
			if (fileExtension == null || !VALID_EXTENSIONS.contains(fileExtension))
			{
				Debug.logWarning("The file doesn't seem to be a nebula source file or project file.");
				Debug.logWarning("Expected extensions: " + VALID_EXTENSIONS);
				Debug.logWarning("To load anyways, use the " + IGNORE_EXTENSIONS_FLAG + " flag.");
				throw new IllegalArgumentException("Invalid file extension.");
			}
		}
	}
}