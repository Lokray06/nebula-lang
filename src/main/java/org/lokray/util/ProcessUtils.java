package org.lokray.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ProcessUtils
{
	public static void executeCommand(ProcessBuilder pb) throws IOException, InterruptedException
	{
		Debug.logDebug("Executing: " + String.join(" ", pb.command()));
		Process process = pb.start();
		BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
		BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));

		String s;
		while ((s = stdInput.readLine()) != null)
		{
			Debug.logDebug(s);
		}
		while ((s = stdError.readLine()) != null)
		{
			Debug.logError(s);
		}

		int exitCode = process.waitFor();
		if (exitCode != 0)
		{
			throw new RuntimeException("Command failed with exit code " + exitCode + " for: " + String.join(" ", pb.command()));
		}
	}
}