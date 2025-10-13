package org.lokray.util;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class NebulaCompilerArguments
{
	private static final String IGNORE_EXTENSIONS_FLAG = "--ignore-file-extensions";
	private static final String VERBOSE = "-v";
	private static final String BUILD_NDK = "--build-ndk";
	private static final String NDK_OUT = "--ndk-out";
	private static final String USE_NDK = "--use-ndk";
	private static final String RUNTIME_LIB = "--runtime-lib";


	private static final List<String> VALID_EXTENSIONS = Arrays.asList(".neb", ".nebproj");

	private final Path filePath;
	private final boolean ignoreExtension;
	private final Path buildNdkPath; // optional
	private final Path ndkOutPath;   // optional
	private final Path useNdkPath;   // optional
	private final Path runtimeLibPath; // optional


	private NebulaCompilerArguments(Path filePath, boolean ignoreExtension,
	                                Path buildNdkPath, Path ndkOutPath, Path useNdkPath, Path runtimeLibPath)
	{
		this.filePath = filePath;
		this.ignoreExtension = ignoreExtension;
		this.buildNdkPath = buildNdkPath;
		this.ndkOutPath = ndkOutPath;
		this.useNdkPath = useNdkPath;
		this.runtimeLibPath = runtimeLibPath;
	}

	public static NebulaCompilerArguments parse(String[] args)
	{
		if (args.length < 1)
		{
			Debug.printUsage(args);
			throw new IllegalArgumentException("Invalid number of arguments.");
		}

		Path filePath = null;
		boolean ignoreExtension = false;
		Path buildNdk = null;
		Path ndkOut = null;
		Path useNdk = null;
		Path runtimeLib = null;

		for (int i = 0; i < args.length; i++)
		{
			String arg = args[i];
			switch (arg)
			{
				case IGNORE_EXTENSIONS_FLAG -> ignoreExtension = true;
				case VERBOSE -> Debug.ENABLE_DEBUG = true;
				case BUILD_NDK ->
				{
					i++;
					if (i >= args.length)
					{
						throw new IllegalArgumentException("Missing path after " + BUILD_NDK);
					}
					buildNdk = Paths.get(args[i]);
				}
				case NDK_OUT ->
				{
					i++;
					if (i >= args.length)
					{
						throw new IllegalArgumentException("Missing path after " + NDK_OUT);
					}
					ndkOut = Paths.get(args[i]);
				}
				case USE_NDK ->
				{
					i++;
					if (i >= args.length)
					{
						throw new IllegalArgumentException("Missing path after " + USE_NDK);
					}
					useNdk = Paths.get(args[i]);
				}
				case RUNTIME_LIB ->
				{
					i++;
					if (i >= args.length)
					{
						throw new IllegalArgumentException("Missing path after " + RUNTIME_LIB);
					}
					runtimeLib = Paths.get(args[i]);
				}
				default ->
				{
					if (filePath == null)
					{
						filePath = Paths.get(arg);
					}
				}
			}
		}

		return new NebulaCompilerArguments(filePath, ignoreExtension, buildNdk, ndkOut, useNdk, runtimeLib);
	}

	public Path getFilePath()
	{
		return filePath;
	}

	public boolean shouldIgnoreExtension()
	{
		return ignoreExtension;
	}

	public Path getBuildNdkPath()
	{
		return buildNdkPath;
	}

	public Path getNdkOutPath()
	{
		return ndkOutPath;
	}

	public Path getUseNdkPath()
	{
		return useNdkPath;
	}

	public Path getRuntimeLibPath()
	{
		return runtimeLibPath;
	}

	public void validateFile()
	{
		if (filePath == null)
		{
			return;
		}
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

