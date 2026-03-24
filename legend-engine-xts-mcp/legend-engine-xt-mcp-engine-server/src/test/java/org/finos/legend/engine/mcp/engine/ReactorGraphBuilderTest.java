package org.finos.legend.engine.mcp.engine;

import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class ReactorGraphBuilderTest
{
    @Test
    public void testBuildReactorGraph() throws Exception
    {
        Path root = createSimpleReactorFixture();
        ReactorGraph graph = ReactorGraphBuilder.buildReactorGraph(root);

        // Root + A + B + C = 4 modules
        Assert.assertEquals(4, graph.modulesByPath.size());
        Assert.assertNotNull(graph.modulesByPath.get("."));
        Assert.assertNotNull(graph.modulesByPath.get("module-a"));
        Assert.assertNotNull(graph.modulesByPath.get("module-b"));
        Assert.assertNotNull(graph.modulesByPath.get("module-c"));

        // B depends on A
        Set<String> bDeps = graph.dependenciesByModulePath.get("module-b");
        Assert.assertTrue(
                "module-b should depend on module-a",
                bDeps.contains("module-a"));

        // A's dependents include B
        Set<String> aDependents = graph.dependentsByModulePath.get("module-a");
        Assert.assertTrue(
                "module-a should have module-b as dependent",
                aDependents.contains("module-b"));

        // C has no dependencies on reactor modules
        Set<String> cDeps = graph.dependenciesByModulePath.get("module-c");
        Assert.assertTrue(
                "module-c should have no reactor dependencies",
                cDeps.isEmpty());
    }

    @Test
    public void testResolveAffectedModules() throws Exception
    {
        Path root = createReactorFixture();
        ReactorGraph graph = ReactorGraphBuilder.buildReactorGraph(root);
        ReactorModule moduleB = ReactorGraphBuilder.findModule(graph, "module-b");

        AffectedModules closure =
                ReactorGraphBuilder.resolveAffectedModules(
                        graph,
                        moduleB,
                        BuildScope.CLOSURE);

        // Build a direction map from the affected modules for verification
        Map<String, String> directionByPath = new LinkedHashMap<>();
        for (AffectedModule affected : closure.modules)
        {
            directionByPath.put(affected.module.modulePath, affected.direction);
        }

        Assert.assertEquals("self", directionByPath.get("module-b"));
        Assert.assertEquals("upstream", directionByPath.get("module-a"));
        Assert.assertEquals("downstream", directionByPath.get("module-c"));
        Assert.assertEquals("downstream", directionByPath.get("module-d"));
    }

    @Test
    public void testRequireModule() throws Exception
    {
        Path root = createSimpleReactorFixture();
        ReactorGraph graph = ReactorGraphBuilder.buildReactorGraph(root);

        // Valid module path
        ReactorModule moduleA =
                ReactorGraphBuilder.requireModule(graph, "module-a", root);
        Assert.assertNotNull(moduleA);
        Assert.assertEquals("module-a", moduleA.modulePath);
        Assert.assertEquals("module-a", moduleA.artifactId);

        // Invalid module path should throw
        try
        {
            ReactorGraphBuilder.requireModule(graph, "nonexistent-module", root);
            Assert.fail("Expected IllegalArgumentException for nonexistent module");
        }
        catch (IllegalArgumentException e)
        {
            Assert.assertTrue(
                    e.getMessage().contains("nonexistent-module"));
        }
    }

    @Test
    public void testNormalizeModulePath()
    {
        // Empty path returns "."
        Assert.assertEquals(".", ReactorGraphBuilder.normalizeModulePath(Paths.get("")));

        // Normal path returned as-is
        Assert.assertEquals(
                "foo/bar",
                ReactorGraphBuilder.normalizeModulePath(Paths.get("foo/bar")));

        // Single segment
        Assert.assertEquals(
                "module-a",
                ReactorGraphBuilder.normalizeModulePath(Paths.get("module-a")));
    }

    @Test
    public void testTraverseModuleGraph()
    {
        Map<String, Set<String>> adjacency = new LinkedHashMap<>();
        adjacency.put("A", setOf("B"));
        adjacency.put("B", setOf("C"));
        adjacency.put("C", new LinkedHashSet<>());

        LinkedHashSet<String> visited =
                ReactorGraphBuilder.traverseModuleGraph(adjacency, "A");

        Assert.assertTrue("Should visit B", visited.contains("B"));
        Assert.assertTrue("Should visit C", visited.contains("C"));
        Assert.assertFalse("Should not visit A (start node)", visited.contains("A"));
        Assert.assertEquals(2, visited.size());
    }

    @Test
    public void testFindModule() throws Exception
    {
        Path root = createSimpleReactorFixture();
        ReactorGraph graph = ReactorGraphBuilder.buildReactorGraph(root);

        // Existing module
        ReactorModule moduleA = ReactorGraphBuilder.findModule(graph, "module-a");
        Assert.assertNotNull("findModule should return non-null for existing module", moduleA);
        Assert.assertEquals("module-a", moduleA.artifactId);

        // Non-existing module
        ReactorModule missing = ReactorGraphBuilder.findModule(graph, "nonexistent");
        Assert.assertNull("findModule should return null for missing module", missing);
    }

    @Test
    public void testModuleDirectory() throws Exception
    {
        Path root = Paths.get("/tmp/test-project").toAbsolutePath().normalize();

        // Root module path "." resolves to root itself
        Path rootDir = ReactorGraphBuilder.moduleDirectory(root, ".");
        Assert.assertEquals(root.toAbsolutePath().normalize(), rootDir);

        // Nested module path resolves to subdirectory
        Path fooBarDir = ReactorGraphBuilder.moduleDirectory(root, "foo/bar");
        Assert.assertEquals(
                root.resolve("foo/bar").toAbsolutePath().normalize(),
                fooBarDir);
    }

    // ── Fixture helpers (copied from LegendEngineMcpStdioServerTest) ─────────

    private static Path createSimpleReactorFixture() throws Exception
    {
        Path root = Files.createTempDirectory("legend-mcp-reactor");

        writePom(
                root.resolve("pom.xml"),
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
                        + "  </modules>\n"
                        + "</project>\n");
        writeModulePom(root, "module-a", "module-a", "");
        writeModulePom(
                root,
                "module-b",
                "module-b",
                "  <dependencies>\n"
                        + "    <dependency>\n"
                        + "      <groupId>org.example</groupId>\n"
                        + "      <artifactId>module-a</artifactId>\n"
                        + "    </dependency>\n"
                        + "  </dependencies>\n");
        writeModulePom(root, "module-c", "module-c", "");

        return root;
    }

    private static Path createReactorFixture() throws Exception
    {
        Path root = Files.createTempDirectory("legend-mcp-reactor");

        writePom(
                root.resolve("pom.xml"),
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
        writeModulePom(
                root,
                "module-b",
                "module-b",
                "  <dependencies>\n"
                        + "    <dependency>\n"
                        + "      <groupId>org.example</groupId>\n"
                        + "      <artifactId>module-a</artifactId>\n"
                        + "    </dependency>\n"
                        + "  </dependencies>\n");
        writeModulePom(
                root,
                "module-c",
                "module-c",
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
        writeModulePom(
                root,
                "module-d",
                "module-d",
                "  <dependencies>\n"
                        + "    <dependency>\n"
                        + "      <groupId>org.example</groupId>\n"
                        + "      <artifactId>module-b</artifactId>\n"
                        + "    </dependency>\n"
                        + "  </dependencies>\n");

        return root;
    }

    private static void writeModulePom(
            Path root, String moduleDir, String artifactId, String extraBody)
            throws Exception
    {
        Path pom = root.resolve(moduleDir).resolve("pom.xml");
        writePom(
                pom,
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

    private static Set<String> setOf(String... values)
    {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (String value : values)
        {
            set.add(value);
        }
        return set;
    }
}
