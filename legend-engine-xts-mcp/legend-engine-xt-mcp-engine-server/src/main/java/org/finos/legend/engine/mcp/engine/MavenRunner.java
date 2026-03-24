// Copyright 2026 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
// implied. See the License for the specific language governing
// permissions and limitations under the License.

package org.finos.legend.engine.mcp.engine;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles Maven process execution, classpath generation from donor modules, classpath-to-URL
 * resolution with overlay support, and related utilities.
 *
 * <p>Overlay support allows locally-compiled {@code target/classes} directories to replace their
 * corresponding JAR entries on the child classpath, enabling hot-reload after incremental rebuilds.
 *
 * <p>All methods are static. This class has no mutable state of its own; callers pass in
 * the project root and other context as method parameters.
 */
final class MavenRunner
{
    static final String BOOTSTRAP_SUPPORT_MODULE =
            "legend-engine-xts-mcp/" + "legend-engine-xt-mcp-engine-server";
    private static final String MAVEN_ARGS_ENV = "LEGEND_ENGINE_MCP_MAVEN_ARGS";

    private MavenRunner()
    {
    }

    // ── Classpath generation (Maven) ───────────────────────────────────────────

    /**
     * Generates the merged child classpath by running {@code mvn dependency:build-classpath} on
     * both the donor module and the bootstrap support module, then deduplicating entries.
     *
     * @param donorModule the Maven module whose runtime dependencies form the base classpath
     * @param projectRoot the project root directory
     * @return deduplicated list of classpath entry strings
     */
    static List<String> generateChildClasspath(String donorModule, Path projectRoot) throws Exception
    {
        System.err.println("[mcp-bootstrap] Generating child classpath from donor: " + donorModule);
        Path donorClasspath = Files.createTempFile("legend-engine-mcp-donor-", ".cp.txt");
        Path bootstrapSupportClasspath =
                Files.createTempFile("legend-engine-mcp-bootstrap-", ".cp.txt");
        try
        {
            buildClasspathFile(donorClasspath, donorModule, projectRoot);
            buildClasspathFile(bootstrapSupportClasspath, BOOTSTRAP_SUPPORT_MODULE, projectRoot);

            List<String> mergedEntries = new ArrayList<>();
            appendClasspathEntries(mergedEntries, donorClasspath);
            appendClasspathEntries(mergedEntries, bootstrapSupportClasspath);

            return mergedEntries;
        }
        finally
        {
            deleteQuietly(donorClasspath);
            deleteQuietly(bootstrapSupportClasspath);
        }
    }

    /**
     * Runs {@code mvn dependency:build-classpath} for a single module, writing the result
     * to the specified output file. Throws if Maven exits non-zero.
     *
     * @param outputFile  the file where Maven will write the classpath string
     * @param module      the Maven module path (e.g. "legend-engine-xts-mcp/legend-engine-xt-mcp-engine-server")
     * @param projectRoot the project root directory
     */
    static void buildClasspathFile(Path outputFile, String module, Path projectRoot)
            throws Exception
    {
        ProcessResult result =
                runMavenCommand(
                        "dependency:build-classpath",
                        module,
                        BuildScope.SELF,
                        false,
                        new String[]
                        {
                            "-Dmdep.includeScope=runtime",
                            "-Dmdep.outputFile=" + outputFile.toAbsolutePath(),
                            "-Dstyle.color=never"
                        },
                        projectRoot);

        if (result.exitCode != 0)
        {
            throw new IllegalStateException(
                    "Classpath generation failed for "
                            + module
                            + " (exit "
                            + result.exitCode
                            + "): "
                            + truncateOutput(result.output, 4000));
        }
    }

    /**
     * Reads a classpath file and appends any entries not already present in the merged list.
     *
     * @param mergedEntries the accumulator list of classpath entries
     * @param classpathFile the file containing a platform-separated classpath string
     */
    static void appendClasspathEntries(List<String> mergedEntries, Path classpathFile)
            throws Exception
            {
        Set<String> seen = new LinkedHashSet<>(mergedEntries);
        for (String entry :
                splitClasspathContent(
                        new String(Files.readAllBytes(classpathFile), StandardCharsets.UTF_8)))
                        {
            if (seen.add(entry))
            {
                mergedEntries.add(entry);
            }
        }
    }

    // ── Maven process execution ────────────────────────────────────────────────

    /**
     * Executes a Maven command as a child process, capturing merged stdout/stderr output.
     * Automatically uses {@code cmd /c mvn} on Windows.
     *
     * @param goal        the Maven goal (e.g. "compile", "dependency:build-classpath")
     * @param module      the Maven module path to target with {@code -pl}
     * @param scope       the build scope controlling reactor flags
     * @param skipTests   whether to append {@code -DskipTests}
     * @param extraArgs   additional Maven arguments (may be {@code null})
     * @param projectRoot the project root directory used as the process working directory
     * @return the process result with exit code and captured output
     */
    static ProcessResult runMavenCommand(
            String goal,
            String module,
            BuildScope scope,
            boolean skipTests,
            String[] extraArgs,
            Path projectRoot)
            throws Exception
            {
        List<String> mavenArgs = buildMavenArgs(goal, module, scope, skipTests, extraArgs);
        List<String> command = new ArrayList<>();
        if (isWindows())
        {
            command.add("cmd");
            command.add("/c");
            command.add("mvn");
        }
        else
        {
            command.add("mvn");
        }

        command.addAll(mavenArgs);

        System.err.println("[mcp-bootstrap] Running command: " + String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(projectRoot.toFile());
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
                        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                output.append(line).append('\n');
            }
        }
        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, output.toString(), "mvn " + String.join(" ", mavenArgs));
    }

    /**
     * Assembles the Maven argument list from the goal, module, scope, and any extra arguments.
     * Prepends user-configured extra args from the {@code LEGEND_ENGINE_MCP_MAVEN_ARGS} environment variable.
     */
    static List<String> buildMavenArgs(
            String goal, String module, BuildScope scope, boolean skipTests, String[] extraArgs)
            {
        List<String> args = new ArrayList<>();
        args.addAll(readExtraMavenArgs());
        if (skipTests)
        {
            args.add("-DskipTests");
        }
        args.add("-pl");
        args.add(module);
        scope.appendTo(args);
        args.add(goal);
        if (extraArgs != null)
        {
            Collections.addAll(args, extraArgs);
        }
        return args;
    }

    // ── Overlay tracking ──────────────────────────────────────────────────────

    /**
     * Scans the affected modules for those that have a {@code target/classes} directory,
     * returning them as overlay candidates suitable for classpath replacement during hot-reload.
     *
     * @param root     the project root directory
     * @param affected the resolved set of affected modules
     * @return overlay candidates with existing {@code target/classes} directories
     */
    static List<OverlayCandidate> collectOverlayCandidates(Path root, AffectedModules affected)
    {
        List<OverlayCandidate> overlays = new ArrayList<>();
        for (AffectedModule affectedModule :
                ReactorGraphBuilder.sortAffectedModules(affected.modules))
        {
            ReactorModule module = affectedModule.module;
            ReactorGraphBuilder.ensureUniqueArtifactId(affected.graph, module);

            Path moduleDir = ReactorGraphBuilder.moduleDirectory(root, module.modulePath);
            Path targetClasses = moduleDir.resolve("target").resolve("classes");
            if (Files.isDirectory(targetClasses))
            {
                overlays.add(
                        new OverlayCandidate(module.modulePath, module.artifactId, targetClasses));
            }
        }
        return overlays;
    }

    // ── Classpath resolution ───────────────────────────────────────────────────

    /**
     * Converts classpath entry strings to an array of URLs, applying overlay replacements
     * where a local {@code target/classes} directory is available for an artifact. Also appends
     * the bootstrap support module's own {@code target/classes} if it exists.
     *
     * @param classpathEntries the raw classpath entries (JAR paths)
     * @param overlays         map of artifactId to local {@code target/classes} path
     * @param projectRoot      the project root directory (may be {@code null} to skip bootstrap overlay)
     * @return the resolved URL array for constructing a {@link java.net.URLClassLoader}
     */
    static URL[] readClasspath(
            List<String> classpathEntries, Map<String, Path> overlays, Path projectRoot)
            throws Exception
    {
        List<URL> urls = new ArrayList<>();
        int replaced = 0;

        for (String entry : classpathEntries)
        {
            String trimmed = entry.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }

            String artifactId = extractArtifactId(trimmed);
            Path overlay = artifactId == null ? null : overlays.get(artifactId);
            if (overlay != null && Files.isDirectory(overlay))
            {
                urls.add(overlay.toUri().toURL());
                replaced++;
                continue;
            }

            File file = new File(trimmed);
            if (file.exists())
            {
                urls.add(file.toURI().toURL());
            }
        }

        if (projectRoot != null)
        {
            Path bootstrapTargetClasses =
                    projectRoot
                            .resolve(BOOTSTRAP_SUPPORT_MODULE)
                            .resolve("target")
                            .resolve("classes");
            if (Files.isDirectory(bootstrapTargetClasses))
            {
                urls.add(bootstrapTargetClasses.toUri().toURL());
            }
        }

        System.err.println(
                "[mcp-bootstrap] Resolved child classpath with "
                        + urls.size()
                        + " entries ("
                        + replaced
                        + " overlay replacement(s)).");
        return urls.toArray(new URL[0]);
    }

    /**
     * Splits a classpath string into individual entries, handling both Unix ({@code :}) and
     * Windows ({@code ;}) separators. Correctly avoids splitting on the colon in Windows
     * drive letters like {@code C:\}.
     */
    static String[] splitClasspathContent(String content)
    {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.isEmpty())
        {
            return new String[0];
        }
        if (trimmed.contains(";"))
        {
            return trimmed.split(";");
        }
        if (looksLikeWindowsAbsolutePath(trimmed))
        {
            return new String[] {trimmed};
        }
        return trimmed.split(":");
    }

    /** Returns {@code true} if the path starts with a drive letter pattern like {@code C:\} or {@code C:/}. */
    static boolean looksLikeWindowsAbsolutePath(String path)
    {
        return path != null
                && path.length() >= 3
                && Character.isLetter(path.charAt(0))
                && path.charAt(1) == ':'
                && (path.charAt(2) == '\\' || path.charAt(2) == '/');
    }

    /**
     * Groups classpath entries by their extracted artifactId, creating an index for fast lookup
     * during overlay resolution and status reporting.
     *
     * @param classpathEntries the raw classpath entries
     * @return map of artifactId to the list of classpath entries matching that artifact
     */
    static Map<String, List<String>> indexClasspathByArtifactId(
            List<String> classpathEntries)
    {
        Map<String, List<String>> index = new LinkedHashMap<>();
        for (String entry : classpathEntries)
        {
            String trimmed = entry.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }

            String artifactId = extractArtifactId(trimmed);
            if (artifactId == null)
            {
                continue;
            }

            index.computeIfAbsent(artifactId, ignored -> new ArrayList<>()).add(trimmed);
        }
        return index;
    }

    // ── Utility methods ───────────────────────────────────────────────────────

    /**
     * Reads additional Maven arguments from the {@code LEGEND_ENGINE_MCP_MAVEN_ARGS} environment
     * variable, splitting on whitespace with support for single and double quoting.
     */
    static List<String> readExtraMavenArgs()
    {
        String rawArgs = System.getenv(MAVEN_ARGS_ENV);
        if (rawArgs == null || rawArgs.trim().isEmpty())
        {
            return Collections.emptyList();
        }
        return splitCommandLine(rawArgs);
    }

    /**
     * Splits a command-line string into tokens, respecting single and double quotes.
     * Quotes are consumed but not included in the output tokens.
     *
     * @param commandLine the raw command-line string
     * @return list of individual argument tokens
     */
    static List<String> splitCommandLine(String commandLine)
    {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = '\0';

        for (int i = 0; i < commandLine.length(); i++)
        {
            char c = commandLine.charAt(i);
            if ((c == '"' || c == '\'') && (!inQuotes || quoteChar == c))
            {
                if (inQuotes)
                {
                    inQuotes = false;
                    quoteChar = '\0';
                }
                else
                {
                    inQuotes = true;
                    quoteChar = c;
                }
                continue;
            }

            if (Character.isWhitespace(c) && !inQuotes)
            {
                if (current.length() > 0)
                {
                    args.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }

        if (current.length() > 0)
        {
            args.add(current.toString());
        }
        return args;
    }

    static boolean isWindows()
    {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /** Deletes a file if it exists, silently ignoring any errors. Safe for cleanup in finally blocks. */
    static void deleteQuietly(Path path)
    {
        if (path == null)
        {
            return;
        }
        try
        {
            Files.deleteIfExists(path);
        }
        catch (Exception ignore)
        {
            // Best-effort cleanup only.
        }
    }

    /**
     * Extracts the Maven artifactId from a JAR file path by parsing the standard Maven repository
     * layout: {@code .../artifactId/version/filename.jar}.
     *
     * @param jarPath the file system path to a JAR file
     * @return the artifactId, or {@code null} if the path does not match the expected layout
     */
    static String extractArtifactId(String jarPath)
    {
        String normalized = jarPath.replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash <= 0)
        {
            return null;
        }
        int versionSlash = normalized.lastIndexOf('/', lastSlash - 1);
        if (versionSlash <= 0)
        {
            return null;
        }
        int artifactSlash = normalized.lastIndexOf('/', versionSlash - 1);
        if (artifactSlash < 0)
        {
            return null;
        }
        return normalized.substring(artifactSlash + 1, versionSlash);
    }

    /** Returns the last {@code maxLength} characters of the output, or the full string if shorter. */
    static String truncateOutput(String output, int maxLength)
    {
        if (output == null || output.length() <= maxLength)
        {
            return output == null ? "" : output;
        }
        return output.substring(output.length() - maxLength);
    }
}
