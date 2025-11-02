package org.lokray.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses and holds all command-line arguments for the Nebula compiler based on the new specification.
 * Replaces the old NebulaCompilerArguments.
 */
public class CompilerArguments
{

	private final List<Path> inputFiles = new ArrayList<>();
	private final List<Path> nativeSourceFiles = new ArrayList<>();
	private final List<Path> librarySearchPaths = new ArrayList<>();
	private final List<Path> libraryFiles = new ArrayList<>();
	private boolean helpFlag = false;
	private boolean versionFlag = false;
	private boolean verboseFlag = false; // Added verbose flag
	private String entryPointClass = null; // Default: null (linker will search for 'main')
	private Path outputPath = null;
	private boolean buildLibrary = false;
	private String targetPlatform = null; // Default: null (use host platform)
	private boolean checkOnly = false;
	private String borrowCheckingLevel = "allowed"; // Default
	private boolean ignoreWarnings = false;

	// Private constructor, use parse()
	private CompilerArguments()
	{
	}

	public static CompilerArguments parse(String[] args)
	{
		CompilerArguments parsedArgs = new CompilerArguments();

		if (args.length < 1)
		{
			parsedArgs.helpFlag = true; // No args, show help
			return parsedArgs;
		}

		try
		{
			for (int i = 0; i < args.length; i++)
			{
				String arg = args[i];

				// --- Flags with no argument ---
				if (arg.equals("-h") || arg.equals("--help"))
				{
					parsedArgs.helpFlag = true;
					return parsedArgs; // Help flag overrides all else
				}
				if (arg.equals("--version"))
				{ // Changed: -v is now --verbose
					parsedArgs.versionFlag = true;
					return parsedArgs; // Version flag overrides all else
				}
				if (arg.equals("-v") || arg.equals("--verbose"))
				{ // Added
					parsedArgs.verboseFlag = true;
					Debug.ENABLE_DEBUG = true; // Set debug flag immediately
					continue;
				}
				if (arg.equals("-B") || arg.equals("--library"))
				{
					parsedArgs.buildLibrary = true;
					continue;
				}
				if (arg.equals("-k") || arg.equals("--check"))
				{
					parsedArgs.checkOnly = true;
					continue;
				}
				if (arg.equals("--ignore-warnings"))
				{
					parsedArgs.ignoreWarnings = true;
					continue;
				}

				// --- Flags with one argument ---
				if (arg.equals("-e") || arg.equals("--entry"))
				{
					parsedArgs.entryPointClass = getNextArg(args, ++i, arg);
					continue;
				}
				if (arg.equals("-o") || arg.equals("--output"))
				{
					parsedArgs.outputPath = Paths.get(getNextArg(args, ++i, arg));
					continue;
				}
				if (arg.equals("-L"))
				{
					parsedArgs.librarySearchPaths.add(Paths.get(getNextArg(args, ++i, arg)));
					continue;
				}
				if (arg.equals("-l"))
				{
					parsedArgs.libraryFiles.add(Paths.get(getNextArg(args, ++i, arg)));
					continue;
				}
				if (arg.equals("-t") || arg.equals("--target"))
				{
					parsedArgs.targetPlatform = getNextArg(args, ++i, arg);
					continue;
				}
				if (arg.startsWith("--borrow-checking="))
				{
					parsedArgs.borrowCheckingLevel = arg.substring(arg.indexOf('=') + 1);
					if (!List.of("none", "allowed", "enforced").contains(parsedArgs.borrowCheckingLevel))
					{
						throw new IllegalArgumentException("Invalid value for --borrow-checking: " + parsedArgs.borrowCheckingLevel);
					}
					continue;
				}

				// --- Flags with multiple arguments (like -n) ---
				if (arg.equals("-n") || arg.equals("--native"))
				{
					// Consume all subsequent args until we hit another flag
					i++;
					while (i < args.length && !args[i].startsWith("-"))
					{
						parsedArgs.nativeSourceFiles.add(Paths.get(args[i]));
						i++;
					}
					i--; // Go back one step so the outer loop processes the flag we just found
					continue;
				}

				// --- Handle file inputs ---
				if (arg.startsWith("-"))
				{
					throw new IllegalArgumentException("Unknown option: " + arg);
				}

				// If it's not a flag, it's an input file
				parsedArgs.inputFiles.add(Paths.get(arg));
			}
		}
		catch (IllegalArgumentException e)
		{
			Debug.logError(e.getMessage());
			parsedArgs.helpFlag = true; // Show help on bad parse
		}

		return parsedArgs;
	}

	private static String getNextArg(String[] args, int i, String flag)
	{
		if (i >= args.length || args[i].startsWith("-"))
		{
			throw new IllegalArgumentException("Missing argument after " + flag);
		}
		return args[i];
	}

	public static void printUsage()
	{
		System.out.println("OVERVIEW: Compiler for the Nebula language.");
		System.out.println("\nUSAGE: nebulac [options] file...");
		System.out.println("\nOPTIONS:");
		System.out.println("  -h, --help                Show this help message and exit.");
		System.out.println("  --version                 Show compiler version and exit.");
		System.out.println("  -v, --verbose             Enable verbose debug logging."); // Added
		System.out.println("  -e, --entry <class.name>  Specify the entry point class for an executable.");
		System.out.println("  -o, --output <file>       Specify the output file name.");
		System.out.println("  -L <path>                 Add a directory to the library search path.");
		System.out.println("  -l <file>                 Link a specific .neblib library file.");
		System.out.println("  -B, --library             Compile as a library (.neblib + .a/.so).");
		System.out.println("  -t, --target <platform>   Specify the target platform (e.g., 'linux-x64').");
		System.out.println("  -k, --check               Run semantic analysis only; do not generate output.");
		System.out.println("  -n, --native <files...>   Explicitly include native source files (.cpp, .h) in the build.");
		System.out.println("\nFLAGS:");
		System.out.println("  --borrow-checking=<level> Set borrow checking level: none, allowed (default), enforced.");
		System.out.println("  --ignore-warnings         Compile while ignoring all warnings.");
	}

	// --- Getters ---

	public List<Path> getInputFiles()
	{
		return inputFiles;
	}

	public List<Path> getNativeSourceFiles()
	{
		return nativeSourceFiles;
	}

	public List<Path> getLibrarySearchPaths()
	{
		return librarySearchPaths;
	}

	public List<Path> getLibraryFiles()
	{
		return libraryFiles;
	}

	public boolean isHelpFlag()
	{
		return helpFlag;
	}

	public boolean isVersionFlag()
	{
		return versionFlag;
	}

	public boolean isVerboseFlag()
	{ // Added
		return verboseFlag;
	}

	public String getEntryPointClass()
	{
		return entryPointClass;
	}

	public Path getOutputPath()
	{
		return outputPath;
	}

	public boolean isBuildLibrary()
	{
		return buildLibrary;
	}

	public String getTargetPlatform()
	{
		return targetPlatform;
	}

	public boolean isCheckOnly()
	{
		return checkOnly;
	}

	public String getBorrowCheckingLevel()
	{
		return borrowCheckingLevel;
	}

	public boolean isIgnoreWarnings()
	{
		return ignoreWarnings;
	}
}