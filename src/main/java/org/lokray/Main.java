package org.lokray;

import org.lokray.util.NebulaCompilerArguments;
import org.lokray.util.Debug;
import org.lokray.util.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main
{
	public static void main(String[] args)
	{
		try
		{
			// 1. Orchestrate argument parsing and validation
			NebulaCompilerArguments nebulaCompilerArguments = NebulaCompilerArguments.parse(args);

			// 2. Validate file existence and extension
			Path filePath = nebulaCompilerArguments.getFilePath();
			if (!Files.exists(filePath))
			{
				Debug.logError("The specified file does not exist: " + filePath);
				return;
			}
			nebulaCompilerArguments.validateFile();

			// 3. Load the file
			List<String> data = FileUtils.load(filePath);
			Debug.log("Successfully loaded file: " + filePath);



		}
		catch (IllegalArgumentException e)
		{
			// Catches exceptions from argument parsing and validation
			Debug.logError("Compiler initialization failed. Reason: " + e.getMessage());
		}
		catch (IOException e)
		{
			// Catches exceptions from file loading
			Debug.logError("Error loading file.");
			Debug.logError("Reason: " + e.getMessage());
		}
		catch (Exception e)
		{
			// Catches any other unexpected exceptions
			Debug.logError("An unexpected error occurred: " + e.getMessage());
		}
	}
}