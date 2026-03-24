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

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared value-object types used across the bootstrap server classes.
 *
 * <p>These are intentionally simple, immutable-style records with package-private visibility.
 * They carry no behavior beyond what is needed for field access and lightweight helpers.
 * Keeping them in a single file avoids class-file proliferation for what are essentially tuples.
 */

/**
 * Controls how much of the Maven reactor graph is included in a build or inspection operation.
 *
 * <ul>
 *   <li>{@link #SELF} -- build only the requested module (no additional Maven flags).</li>
 *   <li>{@link #UPSTREAM} -- build the module and its transitive dependencies ({@code -am}).</li>
 *   <li>{@link #DOWNSTREAM} -- build the module and everything that depends on it ({@code -amd}).</li>
 *   <li>{@link #CLOSURE} -- build the full transitive closure in both directions ({@code -am -amd}).</li>
 * </ul>
 */
enum BuildScope
{
    SELF("self", false, false),
    UPSTREAM("upstream", true, false),
    DOWNSTREAM("downstream", false, true),
    CLOSURE("closure", true, true);

    final String id;
    final boolean includesUpstream;
    final boolean includesDownstream;

    BuildScope(String id, boolean includesUpstream, boolean includesDownstream)
    {
        this.id = id;
        this.includesUpstream = includesUpstream;
        this.includesDownstream = includesDownstream;
    }

    /**
     * Appends the appropriate Maven reactor flags ({@code -am}, {@code -amd}) to the argument list.
     *
     * @param args the mutable list of Maven command-line arguments to append to
     */
    void appendTo(List<String> args)
    {
        if (this.includesUpstream)
        {
            args.add("-am");
        }
        if (this.includesDownstream)
        {
            args.add("-amd");
        }
    }

    /**
     * Parses a user-supplied scope string into a {@link BuildScope}, defaulting to {@link #SELF}
     * when the input is null or blank.
     *
     * @param value the scope identifier (e.g. "self", "upstream", "downstream", "closure")
     * @return the matching scope, or {@code null} if the value is unrecognized
     */
    static BuildScope fromInput(String value)
    {
        if (value == null || value.trim().isEmpty())
        {
            return SELF;
        }
        for (BuildScope scope : BuildScope.values())
        {
            if (scope.id.equals(value.trim()))
            {
                return scope;
            }
        }
        return null;
    }

    /** Returns a human-readable comma-separated list of valid scope identifiers. */
    static String supportedValues()
    {
        StringBuilder sb = new StringBuilder();
        for (BuildScope scope : values())
        {
            if (sb.length() > 0)
            {
                sb.append(", ");
            }
            sb.append(scope.id);
        }
        return sb.toString();
    }
}

/** Captures the exit code, merged stdout/stderr output, and reconstructed command line of a completed process. */
final class ProcessResult
{
    final int exitCode;
    final String output;
    final String commandLine;

    ProcessResult(int exitCode, String output, String commandLine)
    {
        this.exitCode = exitCode;
        this.output = output;
        this.commandLine = commandLine;
    }
}

/** A Maven groupId:artifactId coordinate extracted from a POM dependency declaration. */
final class DependencyCoordinate
{
    final String groupId;
    final String artifactId;

    DependencyCoordinate(String groupId, String artifactId)
    {
        this.groupId = groupId;
        this.artifactId = artifactId;
    }

    /** Returns the {@code groupId:artifactId} coordinate string used as a map key. */
    String key()
    {
        return this.groupId + ":" + this.artifactId;
    }
}

/**
 * Parsed representation of a single {@code pom.xml} within the reactor, including its coordinates,
 * declared child modules, and non-test dependency coordinates.
 */
final class ReactorPom
{
    final String modulePath;
    final Path pomFile;
    final String groupId;
    final String artifactId;
    final List<String> modules;
    final List<DependencyCoordinate> dependencies;

    ReactorPom(
            String modulePath,
            Path pomFile,
            String groupId,
            String artifactId,
            List<String> modules,
            List<DependencyCoordinate> dependencies)
    {
        this.modulePath = modulePath;
        this.pomFile = pomFile;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.modules = modules;
        this.dependencies = dependencies;
    }
}

/**
 * Lightweight identifier for a module in the reactor graph: its relative path, Maven coordinates,
 * and the absolute path to its {@code pom.xml}.
 */
final class ReactorModule
{
    final String modulePath;
    final String groupId;
    final String artifactId;
    final Path pomFile;

    ReactorModule(String modulePath, String groupId, String artifactId, Path pomFile)
    {
        this.modulePath = modulePath;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.pomFile = pomFile;
    }
}

/**
 * The complete dependency graph for all modules in the Maven reactor. Contains forward (dependencies)
 * and reverse (dependents) adjacency maps keyed by module path, plus lookup indexes by path and artifactId.
 */
final class ReactorGraph
{
    final Map<String, ReactorModule> modulesByPath;
    final Map<String, Set<String>> dependenciesByModulePath;
    final Map<String, Set<String>> dependentsByModulePath;
    final Map<String, List<ReactorModule>> modulesByArtifactId;

    ReactorGraph(
            Map<String, ReactorModule> modulesByPath,
            Map<String, Set<String>> dependenciesByModulePath,
            Map<String, Set<String>> dependentsByModulePath,
            Map<String, List<ReactorModule>> modulesByArtifactId)
    {
        this.modulesByPath = modulesByPath;
        this.dependenciesByModulePath = dependenciesByModulePath;
        this.dependentsByModulePath = dependentsByModulePath;
        this.modulesByArtifactId = modulesByArtifactId;
    }
}

/**
 * Result of resolving which modules are affected by a rebuild of a given module at a particular
 * {@link BuildScope}. Carries the original graph, requested module, scope, and the list of affected modules.
 */
final class AffectedModules
{
    final ReactorGraph graph;
    final ReactorModule requestedModule;
    final BuildScope scope;
    final List<AffectedModule> modules;

    AffectedModules(
            ReactorGraph graph,
            ReactorModule requestedModule,
            BuildScope scope,
            List<AffectedModule> modules)
    {
        this.graph = graph;
        this.requestedModule = requestedModule;
        this.scope = scope;
        this.modules = modules;
    }
}

/**
 * A single module within an {@link AffectedModules} result, annotated with the direction
 * of its relationship to the originally requested module (e.g. "self", "upstream", "downstream").
 */
final class AffectedModule
{
    final ReactorModule module;
    final String direction;

    AffectedModule(ReactorModule module, String direction)
    {
        this.module = module;
        this.direction = direction;
    }
}

/**
 * Represents a locally-built module whose {@code target/classes} directory can overlay (replace)
 * the corresponding JAR entry on the child classpath during hot-reload.
 */
final class OverlayCandidate
{
    final String modulePath;
    final String artifactId;
    final Path targetClasses;

    OverlayCandidate(String modulePath, String artifactId, Path targetClasses)
    {
        this.modulePath = modulePath;
        this.artifactId = artifactId;
        this.targetClasses = targetClasses;
    }
}

/**
 * Holds the child {@link URLClassLoader} and the reflectively-resolved {@code handleJsonRpc} method
 * that the bootstrap server uses to delegate tool calls into the engine classloader.
 */
final class EngineRuntime
{
    final URLClassLoader classLoader;
    final Method handleMethod;

    EngineRuntime(URLClassLoader classLoader, Method handleMethod)
    {
        this.classLoader = classLoader;
        this.handleMethod = handleMethod;
    }
}
