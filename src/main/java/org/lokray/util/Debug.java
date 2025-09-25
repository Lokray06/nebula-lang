package org.lokray.util;

public class Debug
{

	// ANSI escape codes for colors
	public static final String ANSI_RESET = "\u001B[0m";
	public static final String ANSI_YELLOW = "\u001B[33m";
	public static final String ANSI_RED = "\u001B[31m";
	public static final String ANSI_GREEN = "\u001B[32m";

	// A flag to control logging. Set this to 'false' to disable DEBUG logs.
	// The 'final' keyword means this value can't be changed after it's set.
	public static final boolean ENABLE_DEBUG = true;

	// Log levels can be used to control verbosity.
	public static void log(String log)
	{
		System.out.println(log);
	}

	public static void logInfo(String log)
	{
		System.out.println(ANSI_GREEN + log + ANSI_RESET);
	}

	// This is the new method for debug-specific logs
	public static void logDebug(String log)
	{
		// Only print if the ENABLE_DEBUG flag is true
		if (ENABLE_DEBUG)
		{
			System.out.println(log);
		}
	}

	public static void logWarning(String log)
	{
		System.out.println(ANSI_YELLOW + log + ANSI_RESET);
	}

	public static void logError(String log)
	{
		System.err.println(ANSI_RED + log + ANSI_RESET);
	}
}