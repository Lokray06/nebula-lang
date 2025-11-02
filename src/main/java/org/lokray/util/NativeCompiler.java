package org.lokray.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.lokray.util.ProcessUtils.executeCommand;

/**
 * Handles the compilation of native source files (e.g., C/C++) into
 * object files and archives (.a or .so).
 * <p>
 * This logic was extracted from the old NdkCompiler.
 */
public class NativeCompiler
{

	private final ErrorHandler errorHandler;
	private final Path buildDir;

	public NativeCompiler(ErrorHandler errorHandler, Path buildDir)
	{
		this.errorHandler = errorHandler;
		this.buildDir = buildDir;
	}

	/**
	 * Compiles a list of native source files into object files.
	 *
	 * @param nativeSourceFiles A list of paths to .cpp, .c, etc. files.
	 * @return A list of paths to the generated .o object files.
	 */
	public List<Path> compileObjectFiles(List<Path> nativeSourceFiles) throws IOException, InterruptedException
	{
		if (nativeSourceFiles.isEmpty())
		{
			Debug.logDebug("No native source files provided. Skipping native compilation.");
			return new ArrayList<>();
		}

		Files.createDirectories(buildDir);
		List<Path> objectFiles = new ArrayList<>();

		Debug.logInfo("Compiling native sources...");
		for (Path sourceFile : nativeSourceFiles)
		{
			String objFileName = sourceFile.getFileName().toString().replaceAll("\\.(cpp|c|cc|cxx)$", ".o");
			Path objFile = buildDir.resolve(objFileName);
			objectFiles.add(objFile);

			// Simple compile command, this could be expanded to include -I flags, etc.
			ProcessBuilder compile = new ProcessBuilder("clang++", "-c", sourceFile.toAbsolutePath().toString(), "-o", objFile.toAbsolutePath().toString());
			executeCommand(compile);
		}

		return objectFiles;
	}

	/**
	 * Archives a list of object files into a static library (.a).
	 *
	 * @param objectFiles       The list of .o files to archive.
	 * @param outputLibraryName The desired name of the library (e.g., "libmyproject.a").
	 * @return The path to the generated static library.
	 */
	public Path archiveStaticLibrary(List<Path> objectFiles, String outputLibraryName) throws IOException, InterruptedException
	{
		if (objectFiles.isEmpty())
		{
			Debug.logDebug("No object files to archive.");
			return null;
		}

		Debug.logInfo("Archiving native objects into a static library...");
		Path staticLibPath = buildDir.resolve(outputLibraryName);
		List<String> archiveCommand = new ArrayList<>();
		archiveCommand.add("ar");
		archiveCommand.add("rcs"); // Create/replace, create symbol table
		archiveCommand.add(staticLibPath.toAbsolutePath().toString());
		archiveCommand.addAll(objectFiles.stream().map(Path::toString).collect(Collectors.toList()));

		ProcessBuilder archive = new ProcessBuilder(archiveCommand);
		executeCommand(archive);

		Debug.logInfo("Static library created at: " + staticLibPath);
		return staticLibPath;
	}

	/**
	 * Finds native source files within a project structure.
	 * This can be used if a .nebproj file specifies a 'nativeSrc' directory.
	 *
	 * @param nativeRoot The root directory to search (e.g., 'src/native').
	 * @return A list of all .cpp and .c files found.
	 */
	public static List<Path> findNativeSources(Path nativeRoot) throws IOException
	{
		if (!Files.exists(nativeRoot) || !Files.isDirectory(nativeRoot))
		{
			Debug.logDebug("No 'native' directory found at: " + nativeRoot);
			return new ArrayList<>();
		}

		try (var stream = Files.walk(nativeRoot))
		{
			return stream
					.filter(p -> Files.isRegularFile(p) && (p.toString().endsWith(".cpp") || p.toString().endsWith(".c")))
					.collect(Collectors.toList());
		}
	}
}
