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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Builds a dependency graph of all Maven modules in the repository by recursively parsing
 * {@code pom.xml} files starting from the project root.
 *
 * <p>The resulting {@link ReactorGraph} is used to determine which modules are affected by a
 * rebuild and to resolve upstream/downstream relationships for scope-aware operations.
 * POM parsing uses the JDK's built-in XML APIs only -- no external dependencies are required.
 *
 * <p>All methods are static and the class cannot be instantiated.
 */
final class ReactorGraphBuilder
{
    static final String ROOT_MODULE_PATH = ".";

    private static final DocumentBuilderFactory XML_FACTORY;
    static
    {
        XML_FACTORY = DocumentBuilderFactory.newInstance();
        XML_FACTORY.setNamespaceAware(false);
    }

    private ReactorGraphBuilder()
    {
    }

    /**
     * Builds the complete reactor graph by recursively parsing all {@code pom.xml} files from the
     * given project root. Populates forward dependency and reverse dependent maps for every module.
     *
     * @param root the project root directory containing the top-level {@code pom.xml}
     * @return the fully populated reactor graph
     * @throws Exception if any POM cannot be read or parsed
     */
    static ReactorGraph buildReactorGraph(Path root) throws Exception
    {
        Path rootPom = root.resolve("pom.xml");
        if (!Files.exists(rootPom))
        {
            throw new IllegalStateException("No pom.xml found at " + rootPom);
        }

        Map<String, ReactorPom> poms = new LinkedHashMap<>();
        collectReactorModules(root, rootPom, poms, new LinkedHashSet<Path>());

        Map<String, ReactorModule> modulesByPath = new LinkedHashMap<>();
        Map<String, ReactorModule> modulesByCoordinate = new LinkedHashMap<>();
        Map<String, List<ReactorModule>> modulesByArtifactId = new LinkedHashMap<>();

        for (ReactorPom pom : poms.values())
        {
            ReactorModule module =
                    new ReactorModule(pom.modulePath, pom.groupId, pom.artifactId, pom.pomFile);
            modulesByPath.put(module.modulePath, module);
            if (module.groupId != null && module.artifactId != null)
            {
                modulesByCoordinate.put(module.groupId + ":" + module.artifactId, module);
            }
            if (module.artifactId != null)
            {
                modulesByArtifactId
                        .computeIfAbsent(module.artifactId, ignored -> new ArrayList<>())
                        .add(module);
            }
        }

        Map<String, Set<String>> dependencies = new LinkedHashMap<>();
        Map<String, Set<String>> dependents = new LinkedHashMap<>();

        for (ReactorModule module : modulesByPath.values())
        {
            dependencies.put(module.modulePath, new LinkedHashSet<>());
            dependents.put(module.modulePath, new LinkedHashSet<>());
        }

        for (ReactorPom pom : poms.values())
        {
            Set<String> moduleDependencies = dependencies.get(pom.modulePath);
            for (DependencyCoordinate dependency : pom.dependencies)
            {
                ReactorModule localDependency = modulesByCoordinate.get(dependency.key());
                if (localDependency != null)
                {
                    moduleDependencies.add(localDependency.modulePath);
                    dependents.get(localDependency.modulePath).add(pom.modulePath);
                }
            }
        }

        return new ReactorGraph(modulesByPath, dependencies, dependents, modulesByArtifactId);
    }

    /**
     * Recursively collects all reactor modules starting from a given POM file, following
     * declared {@code <modules>} entries. Uses a visited set to avoid processing cycles.
     *
     * @param root    the project root directory for relativizing module paths
     * @param pomFile the POM file to parse
     * @param poms    accumulator map of module path to parsed POM
     * @param visited set of already-processed POM paths to prevent cycles
     */
    static void collectReactorModules(
            Path root, Path pomFile, Map<String, ReactorPom> poms, Set<Path> visited)
            throws Exception
    {
        Path normalizedPom = pomFile.toAbsolutePath().normalize();
        if (!visited.add(normalizedPom))
        {
            return;
        }

        ReactorPom pom = parsePom(root, normalizedPom);
        poms.put(pom.modulePath, pom);

        Path moduleDir = normalizedPom.getParent();
        for (String moduleName : pom.modules)
        {
            Path childPom = moduleDir.resolve(moduleName).resolve("pom.xml").normalize();
            if (!Files.exists(childPom))
            {
                throw new IllegalStateException(
                        "Module '"
                                + moduleName
                                + "' declared by "
                                + pom.modulePath
                                + " does not contain a pom.xml"
                                + " at "
                                + childPom);
            }
            collectReactorModules(root, childPom, poms, visited);
        }
    }

    /**
     * Parses a single {@code pom.xml} into a {@link ReactorPom}, extracting coordinates,
     * child module declarations, and non-test dependency coordinates. Resolves common Maven
     * property placeholders like {@code ${project.groupId}}.
     *
     * @param root    the project root for computing the relative module path
     * @param pomFile the absolute path to the POM file
     * @return the parsed POM representation
     */
    static ReactorPom parsePom(Path root, Path pomFile) throws Exception
    {
        Document document = XML_FACTORY.newDocumentBuilder().parse(pomFile.toFile());
        Element project = document.getDocumentElement();

        Element parent = firstDirectChild(project, "parent");
        String parentGroupId = textOfFirstDirectChild(parent, "groupId");
        String groupId =
                resolvePomValue(
                        textOfFirstDirectChild(project, "groupId"), null, null, parentGroupId);
        if (groupId == null)
        {
            groupId = parentGroupId;
        }

        String artifactId =
                resolvePomValue(
                        textOfFirstDirectChild(project, "artifactId"),
                        groupId,
                        null,
                        parentGroupId);
        String modulePath = normalizeModulePath(root.relativize(pomFile.getParent()));

        List<DependencyCoordinate> dependencies = new ArrayList<>();
        addDependencies(
                dependencies,
                firstDirectChild(project, "dependencies"),
                groupId,
                artifactId,
                parentGroupId);

        Element build = firstDirectChild(project, "build");
        Element plugins = firstDirectChild(build, "plugins");
        for (Element plugin : directChildren(plugins, "plugin"))
        {
            addDependencies(
                    dependencies,
                    firstDirectChild(plugin, "dependencies"),
                    groupId,
                    artifactId,
                    parentGroupId);
        }

        return new ReactorPom(
                modulePath, pomFile, groupId, artifactId, readModules(project), dependencies);
    }

    /**
     * Extracts non-test dependency coordinates from a {@code <dependencies>} XML element and
     * appends them to the accumulator list. Skips entries with scope "test" or unresolvable coordinates.
     *
     * @param dependencies        accumulator list to append to
     * @param dependenciesElement the {@code <dependencies>} XML element (may be {@code null})
     * @param moduleGroupId       the owning module's groupId for property resolution
     * @param moduleArtifactId    the owning module's artifactId for property resolution
     * @param parentGroupId       the parent POM's groupId for property resolution fallback
     */
    static void addDependencies(
            List<DependencyCoordinate> dependencies,
            Element dependenciesElement,
            String moduleGroupId,
            String moduleArtifactId,
            String parentGroupId)
    {
        if (dependenciesElement == null)
        {
            return;
        }

        for (Element dependency : directChildren(dependenciesElement, "dependency"))
        {
            String groupId =
                    resolvePomValue(
                            textOfFirstDirectChild(dependency, "groupId"),
                            moduleGroupId,
                            moduleArtifactId,
                            parentGroupId);
            String artifactId =
                    resolvePomValue(
                            textOfFirstDirectChild(dependency, "artifactId"),
                            moduleGroupId,
                            moduleArtifactId,
                            parentGroupId);
            String scope = textOfFirstDirectChild(dependency, "scope");

            if (groupId == null || artifactId == null || "test".equals(scope))
            {
                continue;
            }
            dependencies.add(new DependencyCoordinate(groupId, artifactId));
        }
    }

    /**
     * Reads the {@code <modules>} child element names from a POM project element.
     *
     * @param project the {@code <project>} root element
     * @return list of module directory names, possibly empty
     */
    static List<String> readModules(Element project)
    {
        Element modules = firstDirectChild(project, "modules");
        List<String> results = new ArrayList<>();
        for (Element module : directChildren(modules, "module"))
        {
            String text = module.getTextContent();
            if (text != null && !text.trim().isEmpty())
            {
                results.add(text.trim());
            }
        }
        return results;
    }

    /** Returns the first direct child element with the given tag name, or {@code null} if none. */
    static Element firstDirectChild(Element parent, String tagName)
    {
        if (parent == null)
        {
            return null;
        }
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
        {
            Node node = nodes.item(i);
            if (node instanceof Element && tagName.equals(((Element) node).getTagName()))
            {
                return (Element) node;
            }
        }
        return null;
    }

    /** Returns the trimmed text content of the first direct child with the given tag, or {@code null}. */
    static String textOfFirstDirectChild(Element parent, String tagName)
    {
        Element child = firstDirectChild(parent, tagName);
        if (child == null)
        {
            return null;
        }
        String text = child.getTextContent();
        return text == null ? null : text.trim();
    }

    /** Returns all direct child elements with the given tag name, filtering out non-element nodes. */
    static List<Element> directChildren(Element parent, String tagName)
    {
        if (parent == null)
        {
            return Collections.emptyList();
        }

        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++)
        {
            Node node = nodes.item(i);
            if (node instanceof Element && tagName.equals(((Element) node).getTagName()))
            {
                children.add((Element) node);
            }
        }
        return children;
    }

    /**
     * Resolves common Maven property placeholders (e.g. {@code ${project.groupId}}) to their
     * concrete values. Returns the raw value unchanged if no placeholder is recognized.
     *
     * @param rawValue          the value from the POM, possibly containing a property reference
     * @param moduleGroupId     the current module's groupId
     * @param moduleArtifactId  the current module's artifactId
     * @param parentGroupId     the parent POM's groupId (fallback for groupId properties)
     * @return the resolved value, or {@code null} if the input is blank
     */
    static String resolvePomValue(
            String rawValue, String moduleGroupId, String moduleArtifactId, String parentGroupId)
    {
        if (rawValue == null || rawValue.trim().isEmpty())
        {
            return null;
        }

        String value = rawValue.trim();
        if ("${project.groupId}".equals(value)
                || "${pom.groupId}".equals(value)
                || "${groupId}".equals(value)
                || "${project.parent.groupId}".equals(value))
        {
            return moduleGroupId != null ? moduleGroupId : parentGroupId;
        }
        if ("${project.artifactId}".equals(value) || "${pom.artifactId}".equals(value))
        {
            return moduleArtifactId;
        }
        return value;
    }

    /**
     * Performs a breadth-first traversal of the module graph from a starting module, collecting
     * all transitively reachable module paths. The start module itself is not included in the result.
     *
     * @param adjacency       the adjacency map (either dependencies or dependents)
     * @param startModulePath the module path to start traversal from
     * @return an insertion-ordered set of all reachable module paths
     */
    static LinkedHashSet<String> traverseModuleGraph(
            Map<String, Set<String>> adjacency, String startModulePath)
    {
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> neighbors = adjacency.get(startModulePath);
        if (neighbors != null)
        {
            queue.addAll(neighbors);
        }

        while (!queue.isEmpty())
        {
            String modulePath = queue.removeFirst();
            if (!visited.add(modulePath))
            {
                continue;
            }
            Set<String> next = adjacency.get(modulePath);
            if (next != null)
            {
                queue.addAll(next);
            }
        }
        return visited;
    }

    /**
     * Normalizes a relative path into a forward-slash-separated module path string,
     * using {@code "."} for the root module.
     */
    static String normalizeModulePath(Path relativePath)
    {
        String normalized = relativePath.toString().replace('\\', '/');
        return normalized.isEmpty() ? ROOT_MODULE_PATH : normalized;
    }

    /**
     * Resolves a user-supplied module argument (relative or absolute path) to a {@link ReactorModule},
     * throwing if the module is outside the project root or not part of the reactor.
     *
     * @param graph       the reactor graph to look up in
     * @param moduleArg   the module path as provided by the user
     * @param projectRoot the project root directory
     * @return the matching reactor module
     * @throws IllegalArgumentException if the module is not found in the reactor
     */
    static ReactorModule requireModule(ReactorGraph graph, String moduleArg, Path projectRoot)
    {
        Path candidate = Paths.get(moduleArg);
        Path moduleDir = candidate.isAbsolute() ? candidate : projectRoot.resolve(candidate);
        if (moduleDir.getFileName() != null
                && "pom.xml".equals(moduleDir.getFileName().toString()))
        {
            moduleDir = moduleDir.getParent();
        }
        moduleDir = moduleDir.toAbsolutePath().normalize();
        if (!moduleDir.startsWith(projectRoot))
        {
            throw new IllegalArgumentException(
                    "Module path must be inside the" + " project root: " + moduleArg);
        }

        String modulePath = normalizeModulePath(projectRoot.relativize(moduleDir));
        ReactorModule module = graph.modulesByPath.get(modulePath);
        if (module == null)
        {
            throw new IllegalArgumentException(
                    "Module '" + moduleArg + "' is not part of the" + " local reactor.");
        }
        return module;
    }

    /** Looks up a module by its path in the reactor graph, returning {@code null} if not found. */
    static ReactorModule findModule(ReactorGraph graph, String modulePath)
    {
        return graph.modulesByPath.get(modulePath);
    }

    /**
     * Validates that a module's artifactId is unique within the reactor. Throws if multiple
     * modules share the same artifactId, which would cause ambiguity during overlay resolution.
     *
     * @throws IllegalStateException if the artifactId maps to more than one module path
     */
    static void ensureUniqueArtifactId(ReactorGraph graph, ReactorModule module)
    {
        List<ReactorModule> matches = graph.modulesByArtifactId.get(module.artifactId);
        if (matches == null || matches.size() <= 1)
        {
            return;
        }

        List<String> modulePaths = new ArrayList<>();
        for (ReactorModule match : matches)
        {
            modulePaths.add(match.modulePath);
        }
        throw new IllegalStateException(
                "ArtifactId '"
                        + module.artifactId
                        + "' is ambiguous across reactor"
                        + " modules: "
                        + String.join(", ", modulePaths));
    }

    /**
     * Resolves all modules affected by a rebuild of the given module at the specified scope.
     * Traverses upstream and/or downstream edges as dictated by the scope, tagging each
     * result with its relationship direction.
     *
     * @param graph           the reactor graph
     * @param requestedModule the module being rebuilt
     * @param scope           the build scope controlling traversal direction
     * @return the affected modules result
     */
    static AffectedModules resolveAffectedModules(
            ReactorGraph graph, ReactorModule requestedModule, BuildScope scope)
    {
        LinkedHashSet<String> upstreamPaths =
                scope.includesUpstream
                        ? traverseModuleGraph(
                                graph.dependenciesByModulePath, requestedModule.modulePath)
                        : new LinkedHashSet<>();
        LinkedHashSet<String> downstreamPaths =
                scope.includesDownstream
                        ? traverseModuleGraph(
                                graph.dependentsByModulePath, requestedModule.modulePath)
                        : new LinkedHashSet<>();

        LinkedHashSet<ReactorModule> resolved = new LinkedHashSet<>();
        resolved.add(requestedModule);

        for (String modulePath : upstreamPaths)
        {
            resolved.add(graph.modulesByPath.get(modulePath));
        }
        for (String modulePath : downstreamPaths)
        {
            resolved.add(graph.modulesByPath.get(modulePath));
        }

        List<AffectedModule> affected = new ArrayList<>();
        for (ReactorModule module : resolved)
        {
            String direction =
                    requestedModule.modulePath.equals(module.modulePath)
                            ? "self"
                            : upstreamPaths.contains(module.modulePath)
                                            && downstreamPaths.contains(module.modulePath)
                                    ? "upstream+downstream"
                                    : upstreamPaths.contains(module.modulePath)
                                            ? "upstream"
                                            : "downstream";
            affected.add(new AffectedModule(module, direction));
        }
        return new AffectedModules(graph, requestedModule, scope, affected);
    }

    /**
     * Sorts affected modules by direction priority (self, upstream, downstream, other)
     * and then alphabetically by module path within each group.
     */
    static List<AffectedModule> sortAffectedModules(List<AffectedModule> affectedModules)
    {
        List<AffectedModule> sorted = new ArrayList<>(affectedModules);
        sorted.sort(
                Comparator.comparingInt(
                                (AffectedModule affected) -> directionRank(affected.direction))
                        .thenComparing(affected -> affected.module.modulePath));
        return sorted;
    }

    /** Returns a numeric sort rank for a direction string: self=0, upstream=1, downstream=2, other=3. */
    static int directionRank(String direction)
    {
        if ("self".equals(direction))
        {
            return 0;
        }
        if ("upstream".equals(direction))
        {
            return 1;
        }
        if ("downstream".equals(direction))
        {
            return 2;
        }
        return 3;
    }

    /** Resolves a module path to its absolute directory on disk, handling the root module ({@code "."}) case. */
    static Path moduleDirectory(Path root, String modulePath)
    {
        return ROOT_MODULE_PATH.equals(modulePath)
                ? root.toAbsolutePath().normalize()
                : root.resolve(modulePath).toAbsolutePath().normalize();
    }
}
