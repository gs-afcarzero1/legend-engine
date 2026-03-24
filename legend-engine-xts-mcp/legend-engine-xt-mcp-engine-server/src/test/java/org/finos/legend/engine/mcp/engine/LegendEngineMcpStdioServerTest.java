package org.finos.legend.engine.mcp.engine;

import org.junit.Assert;
import org.junit.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class LegendEngineMcpStdioServerTest
{
    @Test
    public void testEscapeJson()
    {
        Assert.assertEquals("hello", JsonRpcProtocol.escapeJson("hello"));
        Assert.assertEquals("hello \\\"world\\\"", JsonRpcProtocol.escapeJson("hello \"world\""));
        Assert.assertEquals("hello\\nworld", JsonRpcProtocol.escapeJson("hello\nworld"));

        String mavenAnsi = "\u001B[1mBuilding project\u001B[m";
        Assert.assertEquals("\\u001b[1mBuilding project\\u001b[m", JsonRpcProtocol.escapeJson(mavenAnsi));

        Assert.assertEquals("", JsonRpcProtocol.escapeJson(null));
        Assert.assertEquals("a\\tb\\rc", JsonRpcProtocol.escapeJson("a\tb\rc"));
    }

    @Test
    public void testExtractStringField()
    {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"123-abc\",\"method\":\"tools/call\",\"params\":{\"name\":\"rebuild\",\"arguments\":{\"module\":\"my-module\"}}}";

        Assert.assertEquals("123-abc", JsonRpcProtocol.extractId(json));
        Assert.assertEquals("rebuild", JsonRpcProtocol.extractToolName(json));
        Assert.assertEquals("my-module", JsonRpcProtocol.extractArg(json, "module"));
    }

    @Test
    public void testExtractNumericId()
    {
        String jsonNumericId = "{\"jsonrpc\":\"2.0\",\"id\": 12345 ,\"method\":\"tools/call\",\"params\":{\"name\":\"reload\"}}";
        Assert.assertEquals("12345", JsonRpcProtocol.extractId(jsonNumericId));
    }

    @Test
    public void testJsonRpcResult()
    {
        String resultNumeric = JsonRpcProtocol.jsonRpcResult("123", "success");
        Assert.assertTrue(resultNumeric.contains("\"id\":123,"));
        Assert.assertTrue(resultNumeric.contains("\"text\":\"success\""));

        String resultString = JsonRpcProtocol.jsonRpcResult("abc-123", "success");
        Assert.assertTrue(resultString.contains("\"id\":\"abc-123\","));

        String resultAnsi = JsonRpcProtocol.jsonRpcResult("123", "\u001B[31mError!\u001B[0m");
        Assert.assertTrue(resultAnsi.contains("\"text\":\"\\u001b[31mError!\\u001b[0m\""));
        Assert.assertFalse(resultAnsi.contains("\u001B"));

        String resultNullId = JsonRpcProtocol.jsonRpcResult(null, "success");
        Assert.assertTrue(resultNullId.contains("\"id\":null,"));
    }

    @Test
    public void testProfileResolution()
    {
        Assert.assertEquals(
            "legend-engine-config/legend-engine-extensions-collection-generation",
            LegendEngineMcpStdioServer.donorModuleForProfile("base"));
        Assert.assertEquals(
            "legend-engine-config/legend-engine-server/legend-engine-server-http-server",
            LegendEngineMcpStdioServer.donorModuleForProfile("server"));
        Assert.assertEquals(
            "base",
            LegendEngineMcpStdioServer.normalizeProfile("unknown"));
    }

    @Test
    public void testShouldRegenerateClasspathReturnsTrueWhenCacheEmpty()
    {
        Assert.assertTrue(
            LegendEngineMcpStdioServer.shouldRegenerateClasspath("base"));
    }

    @Test
    public void testSplitCommandLine()
    {
        List<String> args = MavenRunner.splitCommandLine(
            "-o -Dmaven.repo.local=.m2/repository \"-Dfoo=bar baz\"");

        Assert.assertEquals(3, args.size());
        Assert.assertEquals("-o", args.get(0));
        Assert.assertEquals("-Dmaven.repo.local=.m2/repository", args.get(1));
        Assert.assertEquals("-Dfoo=bar baz", args.get(2));
    }

    @Test
    public void testSplitClasspathContent()
    {
        String[] windowsEntries = MavenRunner.splitClasspathContent(
            "C:\\repo\\a.jar;C:\\repo\\b.jar");
        Assert.assertEquals(2, windowsEntries.length);
        Assert.assertEquals("C:\\repo\\a.jar", windowsEntries[0]);
        Assert.assertEquals("C:\\repo\\b.jar", windowsEntries[1]);

        String[] unixEntries = MavenRunner.splitClasspathContent(
            "/repo/a.jar:/repo/b.jar");
        Assert.assertEquals(2, unixEntries.length);
        Assert.assertEquals("/repo/a.jar", unixEntries[0]);
        Assert.assertEquals("/repo/b.jar", unixEntries[1]);

        String[] singleWindowsEntry = MavenRunner.splitClasspathContent(
            "C:\\repo\\single.jar");
        Assert.assertEquals(1, singleWindowsEntry.length);
        Assert.assertEquals("C:\\repo\\single.jar", singleWindowsEntry[0]);
    }

    @Test
    public void testReadClasspathPrefersOverlayAndAddsBootstrapClasses() throws Exception
    {
        Path moduleDir = Files.createTempDirectory("legend-mcp-classpath");
        Path dependencyJar = moduleDir.resolve("dep.jar");
        Files.write(dependencyJar, new byte[0]);

        String overlayArtifactPath = moduleDir
            .resolve("repo")
            .resolve("org")
            .resolve("example")
            .resolve("demo-artifact")
            .resolve("1.0.0")
            .resolve("demo-artifact-1.0.0.jar")
            .toString();

        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(overlayArtifactPath);
        classpathEntries.add(dependencyJar.toString());

        Path overlay = moduleDir.resolve("overlay").resolve("classes");
        Files.createDirectories(overlay);

        Map<String, Path> overlays = new LinkedHashMap<>();
        overlays.put("demo-artifact", overlay);

        URL[] urls = MavenRunner.readClasspath(classpathEntries, overlays, moduleDir);

        Assert.assertEquals(2, urls.length);
        Assert.assertEquals(overlay.toUri().toURL(), urls[0]);
        Assert.assertEquals(dependencyJar.toUri().toURL(), urls[1]);
    }

    @Test
    public void testBuildMavenArgsRespectsScope()
    {
        List<String> selfArgs =
            MavenRunner.buildMavenArgs("compile", "demo/module", BuildScope.SELF, true, null);
        Assert.assertTrue(selfArgs.contains("-DskipTests"));
        Assert.assertFalse(selfArgs.contains("-am"));
        Assert.assertFalse(selfArgs.contains("-amd"));

        List<String> closureArgs =
            MavenRunner.buildMavenArgs("compile", "demo/module", BuildScope.CLOSURE, true, null);
        Assert.assertTrue(closureArgs.contains("-am"));
        Assert.assertTrue(closureArgs.contains("-amd"));
    }

    @Test
    public void testResolveAffectedModulesUsesRegularAndPluginDependencies() throws Exception
    {
        Path root = createReactorFixture();
        ReactorGraph graph = ReactorGraphBuilder.buildReactorGraph(root);
        ReactorModule moduleB = ReactorGraphBuilder.findModule(graph, "module-b");

        AffectedModules closure =
            ReactorGraphBuilder.resolveAffectedModules(graph, moduleB, BuildScope.CLOSURE);

        String affectedText =
            BootstrapToolHandlers.formatAffectedModulesText(closure, Collections.emptyList());

        Assert.assertTrue(
            affectedText.contains("module-a | artifactId=module-a | direction=upstream"));
        Assert.assertTrue(
            affectedText.contains("module-c | artifactId=module-c | direction=downstream"));
        Assert.assertTrue(
            affectedText.contains("module-d | artifactId=module-d | direction=downstream"));
    }

    @Test
    public void testCollectOverlayCandidatesTracksOnlyModulesWithTargetClasses() throws Exception
    {
        Path root = createReactorFixture();
        Files.createDirectories(root.resolve("module-a").resolve("target").resolve("classes"));
        Files.createDirectories(root.resolve("module-b").resolve("target").resolve("classes"));

        ReactorGraph graph = ReactorGraphBuilder.buildReactorGraph(root);
        ReactorModule moduleB = ReactorGraphBuilder.findModule(graph, "module-b");
        AffectedModules closure =
            ReactorGraphBuilder.resolveAffectedModules(graph, moduleB, BuildScope.CLOSURE);

        List<OverlayCandidate> overlays = MavenRunner.collectOverlayCandidates(root, closure);

        Assert.assertEquals(2, overlays.size());
    }

    @Test
    public void testFormatModuleStatusTextReportsBaseJarAndOverlayState() throws Exception
    {
        Path root = createReactorFixture();
        Path baseJar = root.resolve("repo")
            .resolve("org")
            .resolve("example")
            .resolve("module-b")
            .resolve("1.0.0")
            .resolve("module-b-1.0.0.jar");
        Files.createDirectories(baseJar.getParent());
        Files.write(baseJar, new byte[0]);

        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(baseJar.toString());

        Path overlay = root.resolve("module-b").resolve("target").resolve("classes");
        Files.createDirectories(overlay);

        ReactorGraph graph = ReactorGraphBuilder.buildReactorGraph(root);
        ReactorModule moduleB = ReactorGraphBuilder.findModule(graph, "module-b");

        Map<String, Path> configured = new LinkedHashMap<>();
        configured.put("module-b", overlay);

        String status = BootstrapToolHandlers.formatModuleStatusText(
            graph, moduleB, classpathEntries, configured, Collections.emptyMap());

        Assert.assertTrue(status.contains("Module path: module-b"));
        Assert.assertTrue(status.contains("On child classpath: yes"));
        Assert.assertTrue(status.contains(baseJar.toString()));
        Assert.assertTrue(status.contains("Configured overlay: " + overlay));
        Assert.assertTrue(status.contains("Active overlay: <none>"));
    }

    private static Path createReactorFixture() throws Exception
    {
        Path root = Files.createTempDirectory("legend-mcp-reactor");

        writePom(root.resolve("pom.xml"),
            "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <groupId>org.example</groupId>\n"
                + "  <artifactId>root</artifactId>\n"
                + "  <version>1.0.0</version>\n"
                + "  <packaging>pom</packaging>\n"
                + "  <modules>\n"
                + "    <module>module-a</module>\n"
                + "    <module>module-b</module>\n"
                + "    <module>module-c</module>\n"
                + "    <module>module-d</module>\n"
                + "  </modules>\n"
                + "</project>\n");
        writeModulePom(root, "module-a", "module-a", "");
        writeModulePom(root, "module-b", "module-b",
            "  <dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>org.example</groupId>\n"
                + "      <artifactId>module-a</artifactId>\n"
                + "    </dependency>\n"
                + "  </dependencies>\n");
        writeModulePom(root, "module-c", "module-c",
            "  <build>\n"
                + "    <plugins>\n"
                + "      <plugin>\n"
                + "        <groupId>org.example</groupId>\n"
                + "        <artifactId>fake-plugin</artifactId>\n"
                + "        <dependencies>\n"
                + "          <dependency>\n"
                + "            <groupId>org.example</groupId>\n"
                + "            <artifactId>module-b</artifactId>\n"
                + "          </dependency>\n"
                + "        </dependencies>\n"
                + "      </plugin>\n"
                + "    </plugins>\n"
                + "  </build>\n");
        writeModulePom(root, "module-d", "module-d",
            "  <dependencies>\n"
                + "    <dependency>\n"
                + "      <groupId>org.example</groupId>\n"
                + "      <artifactId>module-b</artifactId>\n"
                + "    </dependency>\n"
                + "  </dependencies>\n");

        return root;
    }

    private static void writeModulePom(Path root, String moduleDir, String artifactId, String extraBody)
        throws Exception
    {
        writePom(root.resolve(moduleDir).resolve("pom.xml"),
            "<project>\n"
                + "  <modelVersion>4.0.0</modelVersion>\n"
                + "  <parent>\n"
                + "    <groupId>org.example</groupId>\n"
                + "    <artifactId>root</artifactId>\n"
                + "    <version>1.0.0</version>\n"
                + "  </parent>\n"
                + "  <artifactId>" + artifactId + "</artifactId>\n"
                + extraBody
                + "</project>\n");
    }

    private static void writePom(Path pom, String content) throws Exception
    {
        Files.createDirectories(pom.getParent());
        Files.write(pom, content.getBytes(StandardCharsets.UTF_8));
    }
}
