package org.lokray;

import org.lokray.util.Debug;
import org.lokray.util.FileLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class Main
{

	private static final List<String> VALID_EXTENSIONS = Arrays.asList(".neb", ".nebproj");
	private static final String IGNORE_EXTENSIONS_FLAG = "--ignore-file-extensions";

	public static void main(String[] args)
	{
		if (args.length < 1 || args.length > 2)
		{
			printUsage(args);
			return;
		}

		// --- Argument Parsing Logic ---
		String filePathString = null;
		boolean ignoreExtension = false;

		for (String arg : args)
		{
			if (arg.equals(IGNORE_EXTENSIONS_FLAG))
			{
				ignoreExtension = true;
			}
			else
			{
				filePathString = arg;
			}
		}

		// If no file path was provided, print usage and exit.
		if (filePathString == null)
		{
			printUsage(args);
			return;
		}
		// --- End of Parsing Logic ---

		Path filePath = Path.of(filePathString);

		if (!Files.exists(filePath))
		{
			Debug.logError("The specified file does not exist: " + filePath);
			return;
		}

		if (!ignoreExtension)
		{
			String fileExtension = getFileExtension(filePath);
			if (fileExtension == null || !VALID_EXTENSIONS.contains(fileExtension))
			{
				Debug.logWarning("The file doesn't seem to be a nebula source file or project file.");
				Debug.logWarning("Expected extensions: " + VALID_EXTENSIONS);
				Debug.logWarning("To load anyways, use the " + IGNORE_EXTENSIONS_FLAG + " flag.");
				return;
			}
		}

		FileLoader fileLoader = new FileLoader(filePath);

		try
		{
			fileLoader.load();
			List<String> data = fileLoader.getData();
			Debug.log("Successfully loaded file: " + filePathString);
		}
		catch (IOException e)
		{
			Debug.logError("Error loading file: " + filePathString);
			Debug.logError("Reason: " + e.getMessage());
		}
	}

	private static String getFileExtension(Path path)
	{
		String fileName = path.getFileName().toString();
		int lastDotIndex = fileName.lastIndexOf('.');
		if (lastDotIndex > 0)
		{
			return fileName.substring(lastDotIndex);
		}
		return null;
	}

	static void printUsage(String[] invalidArgs)
	{
		System.err.println("Invalid arguments: " + Arrays.toString(invalidArgs));
		System.err.println("Usage:");
		System.err.println("    -> nebc /path/to/nebula/file.neb [" + IGNORE_EXTENSIONS_FLAG + "]");
		System.err.println("    or");
		System.err.println("    -> nebc [" + IGNORE_EXTENSIONS_FLAG + "] /path/to/nebula/file.neb");
	}
}