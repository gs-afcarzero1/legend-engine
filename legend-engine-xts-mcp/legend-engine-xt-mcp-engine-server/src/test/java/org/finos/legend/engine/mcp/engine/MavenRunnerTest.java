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

import org.junit.Assert;
import org.junit.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MavenRunnerTest
{
    @Test
    public void testSplitCommandLine()
    {
        List<String> args = MavenRunner.splitCommandLine(
            "-o -Dmaven.repo.local=.m2/repository \"-Dfoo=bar baz\"");

        Assert.assertEquals(3, args.size());
        Assert.assertEquals("-o", args.get(0));
        Assert.assertEquals("-Dmaven.repo.local=.m2/repository", args.get(1));
        Assert.assertEquals("-Dfoo=bar baz", args.get(2));

        // Single-quoted args
        List<String> singleQuoted = MavenRunner.splitCommandLine(
            "-a '-Dmy.prop=hello world' -b");
        Assert.assertEquals(3, singleQuoted.size());
        Assert.assertEquals("-a", singleQuoted.get(0));
        Assert.assertEquals("-Dmy.prop=hello world", singleQuoted.get(1));
        Assert.assertEquals("-b", singleQuoted.get(2));

        // Empty string
        List<String> empty = MavenRunner.splitCommandLine("");
        Assert.assertTrue(empty.isEmpty());

        // Multiple whitespace between args
        List<String> multiSpace = MavenRunner.splitCommandLine("  -a   -b   ");
        Assert.assertEquals(2, multiSpace.size());
        Assert.assertEquals("-a", multiSpace.get(0));
        Assert.assertEquals("-b", multiSpace.get(1));
    }

    @Test
    public void testSplitClasspathContent()
    {
        // Windows semicolons
        String[] windowsEntries = MavenRunner.splitClasspathContent(
            "C:\\repo\\a.jar;C:\\repo\\b.jar");
        Assert.assertEquals(2, windowsEntries.length);
        Assert.assertEquals("C:\\repo\\a.jar", windowsEntries[0]);
        Assert.assertEquals("C:\\repo\\b.jar", windowsEntries[1]);

        // Unix colons
        String[] unixEntries = MavenRunner.splitClasspathContent(
            "/repo/a.jar:/repo/b.jar");
        Assert.assertEquals(2, unixEntries.length);
        Assert.assertEquals("/repo/a.jar", unixEntries[0]);
        Assert.assertEquals("/repo/b.jar", unixEntries[1]);

        // Single Windows absolute path (no semicolons, should not split on colon)
        String[] singleWindowsEntry = MavenRunner.splitClasspathContent(
            "C:\\repo\\single.jar");
        Assert.assertEquals(1, singleWindowsEntry.length);
        Assert.assertEquals("C:\\repo\\single.jar", singleWindowsEntry[0]);

        // Null content
        String[] nullEntries = MavenRunner.splitClasspathContent(null);
        Assert.assertEquals(0, nullEntries.length);

        // Empty content
        String[] emptyEntries = MavenRunner.splitClasspathContent("");
        Assert.assertEquals(0, emptyEntries.length);
    }

    @Test
    public void testLooksLikeWindowsAbsolutePath()
    {
        Assert.assertTrue(MavenRunner.looksLikeWindowsAbsolutePath("C:\\foo"));
        Assert.assertTrue(MavenRunner.looksLikeWindowsAbsolutePath("D:/bar"));
        Assert.assertFalse(MavenRunner.looksLikeWindowsAbsolutePath("/foo"));
        Assert.assertFalse(MavenRunner.looksLikeWindowsAbsolutePath("relative"));
        Assert.assertFalse(MavenRunner.looksLikeWindowsAbsolutePath(null));
        Assert.assertFalse(MavenRunner.looksLikeWindowsAbsolutePath(""));
        Assert.assertFalse(MavenRunner.looksLikeWindowsAbsolutePath("ab"));
    }

    @Test
    public void testExtractArtifactId()
    {
        // Standard Maven repository jar path
        String standardPath =
            "/home/user/.m2/repository/org/example/my-artifact/1.0/my-artifact-1.0.jar";
        Assert.assertEquals("my-artifact", MavenRunner.extractArtifactId(standardPath));

        // Windows-style path
        String windowsPath =
            "C:\\Users\\dev\\.m2\\repository\\org\\example\\some-lib\\2.3.1\\some-lib-2.3.1.jar";
        Assert.assertEquals("some-lib", MavenRunner.extractArtifactId(windowsPath));

        // Non-standard path (too few slashes)
        Assert.assertNull(MavenRunner.extractArtifactId("simple.jar"));
        Assert.assertNull(MavenRunner.extractArtifactId("/simple.jar"));
    }

    @Test
    public void testBuildMavenArgs()
    {
        // SELF scope: no -am, no -amd
        List<String> selfArgs =
            MavenRunner.buildMavenArgs(
                "compile",
                "demo/module",
                BuildScope.SELF,
                true,
                null);
        Assert.assertTrue(selfArgs.contains("-DskipTests"));
        Assert.assertFalse(selfArgs.contains("-am"));
        Assert.assertFalse(selfArgs.contains("-amd"));
        Assert.assertTrue(selfArgs.contains("-pl"));
        Assert.assertTrue(selfArgs.contains("demo/module"));
        Assert.assertTrue(selfArgs.contains("compile"));

        // CLOSURE scope: both -am and -amd
        List<String> closureArgs =
            MavenRunner.buildMavenArgs(
                "compile",
                "demo/module",
                BuildScope.CLOSURE,
                true,
                null);
        Assert.assertTrue(closureArgs.contains("-am"));
        Assert.assertTrue(closureArgs.contains("-amd"));

        // UPSTREAM scope: -am only
        List<String> upstreamArgs =
            MavenRunner.buildMavenArgs(
                "compile",
                "demo/module",
                BuildScope.UPSTREAM,
                false,
                null);
        Assert.assertTrue(upstreamArgs.contains("-am"));
        Assert.assertFalse(upstreamArgs.contains("-amd"));
        Assert.assertFalse(upstreamArgs.contains("-DskipTests"));

        // DOWNSTREAM scope: -amd only
        List<String> downstreamArgs =
            MavenRunner.buildMavenArgs(
                "compile",
                "demo/module",
                BuildScope.DOWNSTREAM,
                false,
                null);
        Assert.assertFalse(downstreamArgs.contains("-am"));
        Assert.assertTrue(downstreamArgs.contains("-amd"));

        // Extra args
        List<String> extraArgs =
            MavenRunner.buildMavenArgs(
                "package",
                "my/mod",
                BuildScope.SELF,
                false,
                new String[] {"-Dfoo=bar", "-X"});
        Assert.assertTrue(extraArgs.contains("-Dfoo=bar"));
        Assert.assertTrue(extraArgs.contains("-X"));
    }

    @Test
    public void testReadClasspath() throws Exception
    {
        Path moduleDir = Files.createTempDirectory("legend-mcp-maven-runner");
        Path dependencyJar = moduleDir.resolve("dep.jar");
        Files.write(dependencyJar, new byte[0]);

        // Create a path that looks like a Maven repo jar for overlay matching
        Path repoDir = moduleDir.resolve("repo")
            .resolve("org")
            .resolve("example")
            .resolve("demo-artifact")
            .resolve("1.0.0");
        Files.createDirectories(repoDir);
        Path artifactJar = repoDir.resolve("demo-artifact-1.0.0.jar");
        Files.write(artifactJar, new byte[0]);

        String overlayArtifactPath = artifactJar.toString();

        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(overlayArtifactPath);
        classpathEntries.add(dependencyJar.toString());

        Path overlay = moduleDir.resolve("overlay").resolve("classes");
        Files.createDirectories(overlay);

        Map<String, Path> overlays = new LinkedHashMap<>();
        overlays.put("demo-artifact", overlay);

        // Use a temp directory as projectRoot (no bootstrap target/classes there)
        Path projectRoot = Files.createTempDirectory("legend-mcp-project");
        URL[] urls = MavenRunner.readClasspath(classpathEntries, overlays, projectRoot);

        // overlay should replace the artifact jar, and dep.jar should remain
        Assert.assertEquals(2, urls.length);
        Assert.assertEquals(overlay.toUri().toURL(), urls[0]);
        Assert.assertEquals(dependencyJar.toUri().toURL(), urls[1]);
    }

    @Test
    public void testIndexClasspathByArtifactId()
    {
        List<String> entries = Arrays.asList(
            "/home/user/.m2/repository/org/example/alpha/1.0/alpha-1.0.jar",
            "/home/user/.m2/repository/org/example/beta/2.0/beta-2.0.jar",
            "/home/user/.m2/repository/org/other/alpha/3.0/alpha-3.0.jar"
        );

        Map<String, List<String>> index = MavenRunner.indexClasspathByArtifactId(entries);

        Assert.assertTrue(index.containsKey("alpha"));
        Assert.assertEquals(2, index.get("alpha").size());
        Assert.assertTrue(index.containsKey("beta"));
        Assert.assertEquals(1, index.get("beta").size());
    }

    @Test
    public void testDeleteQuietly() throws Exception
    {
        // Deleting an existing temp file works
        Path tempFile = Files.createTempFile("legend-mcp-delete-", ".tmp");
        Assert.assertTrue(Files.exists(tempFile));
        MavenRunner.deleteQuietly(tempFile);
        Assert.assertFalse(Files.exists(tempFile));

        // Deleting a non-existent path does not throw
        Path nonExistent = tempFile.getParent().resolve("non-existent-file-12345.tmp");
        MavenRunner.deleteQuietly(nonExistent);

        // Deleting null does not throw
        MavenRunner.deleteQuietly(null);
    }

    @Test
    public void testIsWindows()
    {
        // Smoke test: just verify it returns a boolean without throwing
        boolean result = MavenRunner.isWindows();
        Assert.assertNotNull(result);
    }

    @Test
    public void testModuleDirectory() throws Exception
    {
        Path root = Files.createTempDirectory("legend-mcp-moddir");

        // "." should return root itself (normalized)
        Path dotDir = ReactorGraphBuilder.moduleDirectory(root, ".");
        Assert.assertEquals(root.toAbsolutePath().normalize(), dotDir);

        // A relative module path should resolve under root
        Path subDir = ReactorGraphBuilder.moduleDirectory(root, "sub/module");
        Assert.assertEquals(
            root.resolve("sub/module").toAbsolutePath().normalize(),
            subDir);
    }
}
