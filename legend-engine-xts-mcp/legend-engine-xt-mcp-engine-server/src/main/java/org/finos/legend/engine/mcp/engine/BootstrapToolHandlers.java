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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles the seven bootstrap tool calls (rebuild, reload, classpath_status, etc.)
 * and produces human-readable status reports.
 *
 * <p>Every method in this class is called from
 * {@link LegendEngineMcpStdioServer#handleLine} and delegates to the server
 * for mutable state access and to the other extracted classes for classpath,
 * reactor-graph, and Maven operations.
 */
final class BootstrapToolHandlers
{
    private BootstrapToolHandlers()
    {
    }

    static String handleRebuild(String line)
    {
        String requestId = JsonRpcProtocol.extractId(line);
        String module = JsonRpcProtocol.extractArg(line, "module");
        BuildScope scope = BuildScope.fromInput(JsonRpcProtocol.extractArg(line, "scope"));

        if (module == null || module.isEmpty())
        {
            return JsonRpcProtocol.jsonRpcResult(requestId, "Error: 'module' argument required.");
        }
        if (scope == null)
        {
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Error: unsupported scope '"
                            + JsonRpcProtocol.extractArg(line, "scope")
                            + "'. Supported scopes: "
                            + BuildScope.supportedValues()
                            + ".");
        }

        try
        {
            Path projectRoot = LegendEngineMcpStdioServer.projectRoot();
            ReactorGraph graph = ReactorGraphBuilder.buildReactorGraph(projectRoot);
            ReactorModule requestedModule =
                    ReactorGraphBuilder.requireModule(graph, module, projectRoot);
            AffectedModules affected =
                    ReactorGraphBuilder.resolveAffectedModules(graph, requestedModule, scope);

            LegendEngineMcpStdioServer.log(
                    "Rebuilding module: " + requestedModule.modulePath + " with scope " + scope.id);
            ProcessResult result =
                    MavenRunner.runMavenCommand(
                            "compile", requestedModule.modulePath, scope, true, null, projectRoot);

            if (result.exitCode != 0)
            {
                return JsonRpcProtocol.jsonRpcResult(
                        requestId,
                        "Rebuild failed (exit "
                                + result.exitCode
                                + "):\n"
                                + "Scope: "
                                + scope.id
                                + "\n"
                                + "Command: "
                                + result.commandLine
                                + "\n"
                                + MavenRunner.truncateOutput(result.output, 4000));
            }

            String overlayMessage = trackConfiguredOverlays(affected, projectRoot);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Rebuild successful for: "
                            + requestedModule.modulePath
                            + "\nScope: "
                            + scope.id
                            + "\nCommand: "
                            + result.commandLine
                            + "\n"
                            + formatAffectedModulesText(
                                    affected, LegendEngineMcpStdioServer.cachedClasspathEntries())
                            + "\n"
                            + overlayMessage
                            + "\n"
                            + "Call 'reload' to apply configured overlays.");
        }
        catch (Throwable t)
        {
            LegendEngineMcpStdioServer.recordBootstrapFailure("Rebuild failed for " + module, t);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Rebuild error: " + LegendEngineMcpStdioServer.summarizeThrowable(t));
        }
    }

    static String handleReload(String line)
    {
        String requestId = JsonRpcProtocol.extractId(line);

        try
        {
            LegendEngineMcpStdioServer.reloadConfiguredState();
            return JsonRpcProtocol.jsonRpcResult(
                    requestId, "Reload complete.\n" + formatStatusText());
        }
        catch (Throwable t)
        {
            LegendEngineMcpStdioServer.recordEngineFailure("Reload failed", t);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Reload failed. "
                            + LegendEngineMcpStdioServer.currentStatePreservationMessage()
                            + "\n"
                            + LegendEngineMcpStdioServer.summarizeThrowable(t));
        }
    }

    static String handleClasspathStatus(String line)
    {
        return JsonRpcProtocol.jsonRpcResult(
                JsonRpcProtocol.extractId(line), formatStatusText());
    }

    static String handleAffectedModules(String line)
    {
        String requestId = JsonRpcProtocol.extractId(line);
        String module = JsonRpcProtocol.extractArg(line, "module");
        BuildScope scope = BuildScope.fromInput(JsonRpcProtocol.extractArg(line, "scope"));

        if (module == null || module.isEmpty())
        {
            return JsonRpcProtocol.jsonRpcResult(
                    requestId, "Error: 'module' argument required.");
        }
        if (scope == null)
        {
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Error: unsupported scope '"
                            + JsonRpcProtocol.extractArg(line, "scope")
                            + "'. Supported scopes: "
                            + BuildScope.supportedValues()
                            + ".");
        }

        try
        {
            Path projectRoot = LegendEngineMcpStdioServer.projectRoot();
            ReactorGraph graph = ReactorGraphBuilder.buildReactorGraph(projectRoot);
            ReactorModule requestedModule =
                    ReactorGraphBuilder.requireModule(graph, module, projectRoot);
            AffectedModules affected =
                    ReactorGraphBuilder.resolveAffectedModules(graph, requestedModule, scope);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    formatAffectedModulesText(
                            affected, LegendEngineMcpStdioServer.cachedClasspathEntries()));
        }
        catch (Throwable t)
        {
            LegendEngineMcpStdioServer.recordBootstrapFailure(
                    "Affected module resolution failed for " + module, t);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Affected module resolution error: "
                            + LegendEngineMcpStdioServer.summarizeThrowable(t));
        }
    }

    static String handleModuleStatus(String line)
    {
        String requestId = JsonRpcProtocol.extractId(line);
        String module = JsonRpcProtocol.extractArg(line, "module");

        if (module == null || module.isEmpty())
        {
            return JsonRpcProtocol.jsonRpcResult(
                    requestId, "Error: 'module' argument required.");
        }

        try
        {
            Path projectRoot = LegendEngineMcpStdioServer.projectRoot();
            ReactorGraph graph = ReactorGraphBuilder.buildReactorGraph(projectRoot);
            ReactorModule requestedModule =
                    ReactorGraphBuilder.requireModule(graph, module, projectRoot);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    formatModuleStatusText(
                            graph,
                            requestedModule,
                            LegendEngineMcpStdioServer.cachedClasspathEntries(),
                            LegendEngineMcpStdioServer.configuredOverlays(),
                            LegendEngineMcpStdioServer.activeOverlays()));
        }
        catch (Throwable t)
        {
            LegendEngineMcpStdioServer.recordBootstrapFailure(
                    "Module status resolution failed for " + module, t);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Module status error: "
                            + LegendEngineMcpStdioServer.summarizeThrowable(t));
        }
    }

    static String handleSetClasspathProfile(String line)
    {
        String requestId = JsonRpcProtocol.extractId(line);
        String requestedProfile = JsonRpcProtocol.extractArg(line, "profile");

        if (requestedProfile == null
                || LegendEngineMcpStdioServer.donorModuleForProfile(requestedProfile) == null)
        {
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Error: unsupported profile '"
                            + requestedProfile
                            + "'. Supported profiles: base, server.");
        }

        Map<String, Path> overlays =
                new LinkedHashMap<>(LegendEngineMcpStdioServer.configuredOverlays());

        try
        {
            LegendEngineMcpStdioServer.loadRequestedState(requestedProfile, overlays);
            LegendEngineMcpStdioServer.setConfiguredProfile(requestedProfile);
            LegendEngineMcpStdioServer.setConfiguredOverlays(overlays);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Classpath profile switched to '"
                            + requestedProfile
                            + "'.\n"
                            + formatStatusText());
        }
        catch (Throwable t)
        {
            LegendEngineMcpStdioServer.recordEngineFailure("Profile switch failed", t);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Profile switch failed. "
                            + LegendEngineMcpStdioServer.currentStatePreservationMessage()
                            + "\n"
                            + LegendEngineMcpStdioServer.summarizeThrowable(t));
        }
    }

    static String handleRestoreSpace(String line)
    {
        String requestId = JsonRpcProtocol.extractId(line);
        Map<String, Path> restoredOverlays = new LinkedHashMap<>();

        try
        {
            LegendEngineMcpStdioServer.loadRequestedState("base", restoredOverlays);
            LegendEngineMcpStdioServer.setConfiguredProfile("base");
            LegendEngineMcpStdioServer.setConfiguredOverlays(restoredOverlays);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId, "Bootstrap state restored.\n" + formatStatusText());
        }
        catch (Throwable t)
        {
            LegendEngineMcpStdioServer.recordEngineFailure("Restore failed", t);
            return JsonRpcProtocol.jsonRpcResult(
                    requestId,
                    "Restore failed. "
                            + LegendEngineMcpStdioServer.currentStatePreservationMessage()
                            + "\n"
                            + LegendEngineMcpStdioServer.summarizeThrowable(t));
        }
    }

    // ── Overlay tracking ────────────────────────────────────────────────────

    private static String trackConfiguredOverlays(AffectedModules affected, Path projectRoot)
    {
        List<OverlayCandidate> overlays =
                MavenRunner.collectOverlayCandidates(projectRoot, affected);
        if (overlays.isEmpty())
        {
            return "No target/classes directories"
                    + " were found in the resolved"
                    + " module set, so no overlays"
                    + " were tracked.";
        }

        Map<String, Path> configured = LegendEngineMcpStdioServer.configuredOverlays();
        StringBuilder text = new StringBuilder("Overlays tracked for next reload:");
        for (OverlayCandidate overlay : overlays)
        {
            configured.put(overlay.artifactId, overlay.targetClasses);
            text.append('\n')
                    .append("  - ")
                    .append(overlay.modulePath)
                    .append(" [")
                    .append(overlay.artifactId)
                    .append("] -> ")
                    .append(overlay.targetClasses);
        }
        return text.toString();
    }

    // ── Status formatting ───────────────────────────────────────────────────

    static String formatStatusText()
    {
        StringBuilder text = new StringBuilder();
        ReactorGraph graph = tryBuildReactorGraph();
        text.append("Legend Engine MCP bootstrap status\n");
        text.append("Engine availability: ")
                .append(LegendEngineMcpStdioServer.currentRuntime() == null
                        ? "unavailable"
                        : "available")
                .append('\n');
        text.append("Configured profile: ")
                .append(LegendEngineMcpStdioServer.configuredProfile())
                .append('\n');
        text.append("Configured donor module: ")
                .append(LegendEngineMcpStdioServer.donorModuleForProfile(
                        LegendEngineMcpStdioServer.configuredProfile()))
                .append('\n');
        text.append("Bootstrap support module: ")
                .append(MavenRunner.BOOTSTRAP_SUPPORT_MODULE)
                .append('\n');
        text.append("Active profile: ")
                .append(LegendEngineMcpStdioServer.activeProfile() == null
                        ? "<none>"
                        : LegendEngineMcpStdioServer.activeProfile())
                .append('\n');
        text.append("Active donor module: ")
                .append(LegendEngineMcpStdioServer.activeDonorModule() == null
                        ? "<none>"
                        : LegendEngineMcpStdioServer.activeDonorModule())
                .append('\n');
        text.append("Child classpath entries: ")
                .append(LegendEngineMcpStdioServer.cachedClasspathEntries().size())
                .append('\n');
        appendOverlaySection(
                text, "Configured overlays",
                LegendEngineMcpStdioServer.configuredOverlays(), graph);
        appendOverlaySection(
                text, "Active overlays",
                LegendEngineMcpStdioServer.activeOverlays(), graph);
        text.append("Hot reload can replace: ")
                .append("child-loaded engine code and")
                .append(" explicitly rebuilt overlay modules.\n");
        text.append("Restart required for: ")
                .append("LegendEngineMcpStdioServer")
                .append(" and the outer bootstrap launch classpath.\n");
        text.append("Bootstrap tools: rebuild, reload, classpath_status, ")
                .append("affected_modules, module_status, set_classpath_profile, restore_space\n");
        text.append("Last engine failure: ")
                .append(LegendEngineMcpStdioServer.lastEngineFailure() == null
                        ? "<none>"
                        : LegendEngineMcpStdioServer.lastEngineFailure());
        return text.toString();
    }

    private static ReactorGraph tryBuildReactorGraph()
    {
        try
        {
            return ReactorGraphBuilder.buildReactorGraph(
                    LegendEngineMcpStdioServer.projectRoot());
        }
        catch (Exception ignore)
        {
            return null;
        }
    }

    private static void appendOverlaySection(
            StringBuilder text, String label, Map<String, Path> overlays, ReactorGraph graph)
    {
        text.append(label).append(": ");
        if (overlays.isEmpty())
        {
            text.append("<none>\n");
            return;
        }

        text.append('\n');
        for (Map.Entry<String, Path> entry : overlays.entrySet())
        {
            text.append("  - ")
                    .append(entry.getKey())
                    .append(appendResolvedModulePath(graph, entry.getKey()))
                    .append(" -> ")
                    .append(entry.getValue())
                    .append('\n');
        }
    }

    static String formatAffectedModulesText(
            AffectedModules affected, List<String> classpathEntryList)
    {
        Map<String, List<String>> classpathEntries =
                MavenRunner.indexClasspathByArtifactId(classpathEntryList);
        StringBuilder text = new StringBuilder();
        text.append("Affected modules:").append('\n');
        for (AffectedModule module :
                ReactorGraphBuilder.sortAffectedModules(affected.modules))
        {
            ReactorGraphBuilder.ensureUniqueArtifactId(affected.graph, module.module);
            text.append("  - ")
                    .append(module.module.modulePath)
                    .append(" | artifactId=")
                    .append(module.module.artifactId)
                    .append(" | direction=")
                    .append(module.direction)
                    .append(" | on_child_classpath=")
                    .append(classpathEntries.containsKey(module.module.artifactId) ? "yes" : "no")
                    .append('\n');
        }
        if (text.charAt(text.length() - 1) == '\n')
        {
            text.setLength(text.length() - 1);
        }
        return text.toString();
    }

    static String formatModuleStatusText(
            ReactorGraph graph,
            ReactorModule module,
            List<String> classpathEntryList,
            Map<String, Path> configuredOverlayMap,
            Map<String, Path> activeOverlayMap)
    {
        ReactorGraphBuilder.ensureUniqueArtifactId(graph, module);

        Map<String, List<String>> classpathEntries =
                MavenRunner.indexClasspathByArtifactId(classpathEntryList);
        List<String> baseEntries =
                classpathEntries.containsKey(module.artifactId)
                        ? classpathEntries.get(module.artifactId)
                        : Collections.emptyList();

        StringBuilder text = new StringBuilder();
        text.append("Module status\n");
        text.append("Module path: ").append(module.modulePath).append('\n');
        text.append("ArtifactId: ").append(module.artifactId).append('\n');
        text.append("GroupId: ").append(module.groupId).append('\n');
        text.append("On child classpath: ")
                .append(baseEntries.isEmpty() ? "no" : "yes")
                .append('\n');
        text.append("Base classpath entries: ");
        if (baseEntries.isEmpty())
        {
            text.append("<none>\n");
        }
        else
        {
            text.append('\n');
            for (String entry : baseEntries)
            {
                text.append("  - ").append(entry).append('\n');
            }
        }
        appendOverlayStateLine(
                text, "Configured overlay", configuredOverlayMap.get(module.artifactId));
        appendOverlayStateLine(
                text, "Active overlay", activeOverlayMap.get(module.artifactId));
        if (text.charAt(text.length() - 1) == '\n')
        {
            text.setLength(text.length() - 1);
        }
        return text.toString();
    }

    private static void appendOverlayStateLine(
            StringBuilder text, String label, Path overlay)
    {
        text.append(label)
                .append(": ")
                .append(overlay == null ? "<none>" : overlay)
                .append('\n');
    }

    private static String appendResolvedModulePath(ReactorGraph graph, String artifactId)
    {
        if (graph == null)
        {
            return "";
        }

        List<ReactorModule> matches = graph.modulesByArtifactId.get(artifactId);
        if (matches == null || matches.isEmpty())
        {
            return "";
        }
        if (matches.size() == 1)
        {
            return " [" + matches.get(0).modulePath + "]";
        }

        List<String> modulePaths = new ArrayList<>();
        for (ReactorModule match : matches)
        {
            modulePaths.add(match.modulePath);
        }
        return " [ambiguous: " + String.join(", ", modulePaths) + "]";
    }
}
