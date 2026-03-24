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
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP server that exposes Legend Engine tools (compile, plan generation, Pure execution)
 * over a JSON-RPC stdio transport.
 *
 * <p>The JVM cannot reload classes once loaded by the system classloader. To support
 * hot-reloading of Legend Engine modules during development, the server uses a two-layer
 * classloader design:
 *
 * <ul>
 *   <li><b>Outer layer</b> (this class) — loaded once from the JVM's system classpath.
 *       Handles the stdio protocol, request routing, and child classloader lifecycle.</li>
 *   <li><b>Child layer</b> ({@code LegendEngineMcpToolDefinitions}) — loaded into a
 *       disposable {@link URLClassLoader}. On each {@code reload}, the old classloader
 *       is discarded and a new one is created with updated URLs.</li>
 * </ul>
 *
 * <p>The class is intentionally slim. Tool handlers live in {@link BootstrapToolHandlers},
 * Maven execution in {@link MavenRunner}, reactor graph in {@link ReactorGraphBuilder},
 * JSON-RPC helpers in {@link JsonRpcProtocol}, and shared types in {@link BootstrapTypes}.
 */
public class LegendEngineMcpStdioServer
{
    private static final String ENGINE_CLASS =
            "org.finos.legend.engine.mcp.engine" + ".LegendEngineMcpToolDefinitions";
    private static final String DEFAULT_PROFILE = "base";
    private static final String SERVER_PROFILE = "server";

    // ── Mutable state ────────────────────────────────────────────────────────

    private static EngineRuntime currentRuntime;
    private static List<String> cachedClasspathEntries = Collections.emptyList();
    private static Path projectRoot;
    private static String configuredProfile = DEFAULT_PROFILE;
    private static String activeProfile;
    private static String activeDonorModule;
    private static String lastEngineFailure;
    private static Map<String, Path> configuredOverlays = new LinkedHashMap<>();
    private static Map<String, Path> activeOverlays = new LinkedHashMap<>();
    private static PrintWriter logger;

    // ── Package-private state accessors (used by BootstrapToolHandlers) ──────

    static EngineRuntime currentRuntime()
    {
        return currentRuntime;
    }

    static List<String> cachedClasspathEntries()
    {
        return cachedClasspathEntries;
    }

    static Path projectRoot()
    {
        return projectRoot;
    }

    static String configuredProfile()
    {
        return configuredProfile;
    }

    static void setConfiguredProfile(String profile)
    {
        configuredProfile = profile;
    }

    static String activeProfile()
    {
        return activeProfile;
    }

    static String activeDonorModule()
    {
        return activeDonorModule;
    }

    static String lastEngineFailure()
    {
        return lastEngineFailure;
    }

    static Map<String, Path> configuredOverlays()
    {
        return configuredOverlays;
    }

    static void setConfiguredOverlays(Map<String, Path> overlays)
    {
        configuredOverlays = overlays;
    }

    static Map<String, Path> activeOverlays()
    {
        return activeOverlays;
    }

    // ── Startup ──────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception
    {
        PrintStream mcpOut = System.out;
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.err), true));

        bootstrapLog(
                "main() entered, args.length="
                        + args.length
                        + ", cwd="
                        + Paths.get("").toAbsolutePath());

        projectRoot =
                Paths.get(args.length > 0 ? args[0] : findProjectRoot())
                        .toAbsolutePath()
                        .normalize();
        configuredProfile = normalizeProfile(args.length > 1 ? args[1] : DEFAULT_PROFILE);

        initializeLogger();
        bootstrapLog("projectRoot=" + projectRoot + ", profile=" + configuredProfile);

        try
        {
            reloadConfiguredState();
        }
        catch (Throwable t)
        {
            recordEngineFailure("Initial engine load failed", t);
        }

        bootstrapLog("ready. Engine=" + (currentRuntime == null ? "unavailable" : "available"));

        runStdioLoop(mcpOut);
    }

    private static void initializeLogger()
    {
        try
        {
            Path logPath = projectRoot.resolve("mcp_server.log");
            logger = new PrintWriter(new FileWriter(logPath.toFile(), true), true);
            logger.println();
            logger.println("--- Server started at " + new java.util.Date() + " ---");
        }
        catch (Exception e)
        {
            System.err.println("Failed to initialize log file: " + e.getMessage());
        }
    }

    // ── MCP stdio loop ──────────────────────────────────────────────────────

    private static void runStdioLoop(PrintStream mcpOut) throws Exception
    {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        String line;
        while ((line = reader.readLine()) != null)
        {
            if (logger != null)
            {
                logger.println("IN: " + line);
            }

            line = line.trim();
            if (line.isEmpty())
            {
                continue;
            }

            try
            {
                String response = handleLine(line);
                if (response != null)
                {
                    if (logger != null)
                    {
                        logger.println("OUT: " + response);
                    }
                    mcpOut.println(response);
                    mcpOut.flush();
                }
            }
            catch (Throwable t)
            {
                String requestId = JsonRpcProtocol.extractId(line);
                recordBootstrapFailure("Tool call failed", t);
                String response = JsonRpcProtocol.jsonRpcError(requestId, summarizeThrowable(t));
                if (logger != null)
                {
                    logger.println("OUT: " + response);
                }
                mcpOut.println(response);
                mcpOut.flush();
            }
        }
    }

    // ── Request routing ─────────────────────────────────────────────────────

    private static String handleLine(String line) throws Exception
    {
        String toolName = JsonRpcProtocol.extractToolName(line);

        if ("rebuild".equals(toolName))
        {
            return BootstrapToolHandlers.handleRebuild(line);
        }
        if ("reload".equals(toolName))
        {
            return BootstrapToolHandlers.handleReload(line);
        }
        if ("classpath_status".equals(toolName))
        {
            return BootstrapToolHandlers.handleClasspathStatus(line);
        }
        if ("affected_modules".equals(toolName))
        {
            return BootstrapToolHandlers.handleAffectedModules(line);
        }
        if ("module_status".equals(toolName))
        {
            return BootstrapToolHandlers.handleModuleStatus(line);
        }
        if ("set_classpath_profile".equals(toolName))
        {
            return BootstrapToolHandlers.handleSetClasspathProfile(line);
        }
        if ("restore_space".equals(toolName))
        {
            return BootstrapToolHandlers.handleRestoreSpace(line);
        }
        if (currentRuntime == null)
        {
            return JsonRpcProtocol.jsonRpcResult(
                    JsonRpcProtocol.extractId(line), unavailableEngineMessage());
        }
        return invokeEngine(line);
    }

    // ── Engine invocation (child classloader) ───────────────────────────────

    private static String invokeEngine(String jsonRpcMessage) throws Exception
    {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(currentRuntime.classLoader);
            return (String) currentRuntime.handleMethod.invoke(null, jsonRpcMessage);
        }
        catch (InvocationTargetException e)
        {
            throw asException(e);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    // ── Child classloader lifecycle ─────────────────────────────────────────

    static void reloadConfiguredState() throws Exception
    {
        loadRequestedState(configuredProfile, configuredOverlays);
    }

    static void loadRequestedState(String profile, Map<String, Path> overlays) throws Exception
    {
        String donorModule = donorModuleForProfile(profile);
        if (donorModule == null)
        {
            throw new IllegalArgumentException("Unsupported profile: " + profile);
        }

        boolean regenerate = shouldRegenerateClasspath(profile);
        List<String> classpathEntries = cachedClasspathEntries;

        if (regenerate)
        {
            classpathEntries = MavenRunner.generateChildClasspath(donorModule, projectRoot);
        }

        EngineRuntime candidate = null;
        try
        {
            candidate = buildEngineRuntime(classpathEntries, overlays);
            cachedClasspathEntries = classpathEntries;
            activateRuntime(candidate, profile, donorModule, overlays);
        }
        catch (Throwable t)
        {
            closeQuietly(candidate);
            throw asException(t);
        }
    }

    private static EngineRuntime buildEngineRuntime(
            List<String> classpathEntries, Map<String, Path> overlays) throws Exception
    {
        URL[] urls = MavenRunner.readClasspath(classpathEntries, overlays, projectRoot);

        ClassLoader platformCl = getPlatformClassLoader();
        URLClassLoader candidateLoader = new URLClassLoader(urls, platformCl);

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(candidateLoader);

            Class<?> engineClass = candidateLoader.loadClass(ENGINE_CLASS);
            engineClass.getMethod("initialize").invoke(null);
            Method candidateHandleMethod = engineClass.getMethod("handleJsonRpc", String.class);
            return new EngineRuntime(candidateLoader, candidateHandleMethod);
        }
        catch (Throwable t)
        {
            closeQuietly(candidateLoader);
            throw asException(t);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static void activateRuntime(
            EngineRuntime candidate, String profile, String donorModule,
            Map<String, Path> overlays)
    {
        EngineRuntime previous = currentRuntime;
        currentRuntime = candidate;
        activeProfile = profile;
        activeDonorModule = donorModule;
        activeOverlays = new LinkedHashMap<>(overlays);
        lastEngineFailure = null;
        Thread.currentThread().setContextClassLoader(candidate.classLoader);
        closeQuietly(previous);
        log("Engine activated for profile '" + profile + "' with "
                + activeOverlays.size() + " active overlay(s).");
    }

    static String donorModuleForProfile(String profile)
    {
        if (DEFAULT_PROFILE.equals(profile))
        {
            return "legend-engine-config/legend-engine-extensions-collection-generation";
        }
        if (SERVER_PROFILE.equals(profile))
        {
            return "legend-engine-config/legend-engine-server/legend-engine-server-http-server";
        }
        return null;
    }

    static String normalizeProfile(String profile)
    {
        return donorModuleForProfile(profile) == null ? DEFAULT_PROFILE : profile;
    }

    static boolean shouldRegenerateClasspath(String requestedProfile)
    {
        if (cachedClasspathEntries.isEmpty())
        {
            return true;
        }

        String donorModule = donorModuleForProfile(requestedProfile);
        return !requestedProfile.equals(activeProfile)
                || !donorModule.equals(activeDonorModule);
    }

    // ── Error handling and logging ──────────────────────────────────────────

    private static String unavailableEngineMessage()
    {
        return "Legend Engine child runtime is currently unavailable.\n"
                + BootstrapToolHandlers.formatStatusText();
    }

    static void recordEngineFailure(String context, Throwable t)
    {
        lastEngineFailure = context + ": " + summarizeThrowable(t);
        recordBootstrapFailure(context, t);
    }

    static void recordBootstrapFailure(String context, Throwable t)
    {
        log(context + ": " + summarizeThrowable(t));
        if (logger != null)
        {
            t.printStackTrace(logger);
        }
        t.printStackTrace(System.err);
    }

    static void log(String message)
    {
        if (logger != null)
        {
            logger.println(message);
        }
    }

    private static void bootstrapLog(String message)
    {
        log(message);
        System.err.println("[mcp-bootstrap] " + message);
    }

    static String currentStatePreservationMessage()
    {
        return currentRuntime == null
                ? "Engine remains unavailable."
                : "The current working child runtime was preserved.";
    }

    static String summarizeThrowable(Throwable t)
    {
        Throwable current = unwrapThrowable(t);
        String message = current.getMessage();
        if (message == null || message.trim().isEmpty())
        {
            message = current.getClass().getName();
        }
        return current.getClass().getName() + ": " + message;
    }

    // ── Utility methods ─────────────────────────────────────────────────────

    private static String findProjectRoot()
    {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null)
        {
            if (Files.exists(dir.resolve("pom.xml")))
            {
                return dir.toString();
            }
            dir = dir.getParent();
        }
        return Paths.get("").toAbsolutePath().toString();
    }

    private static ClassLoader getPlatformClassLoader()
    {
        try
        {
            return (ClassLoader)
                    ClassLoader.class.getMethod("getPlatformClassLoader").invoke(null);
        }
        catch (Exception e)
        {
            ClassLoader system = ClassLoader.getSystemClassLoader();
            return system == null ? null : system.getParent();
        }
    }

    private static void closeQuietly(EngineRuntime runtime)
    {
        if (runtime != null)
        {
            closeQuietly(runtime.classLoader);
        }
    }

    private static void closeQuietly(URLClassLoader classLoader)
    {
        if (classLoader == null)
        {
            return;
        }
        try
        {
            classLoader.close();
        }
        catch (Exception ignore)
        {
        }
    }

    private static Throwable unwrapThrowable(Throwable t)
    {
        Throwable current = t;
        while (current instanceof InvocationTargetException
                && ((InvocationTargetException) current).getTargetException() != null)
        {
            current = ((InvocationTargetException) current).getTargetException();
        }
        return current;
    }

    private static Exception asException(Throwable t) throws Exception
    {
        Throwable current = unwrapThrowable(t);
        if (current instanceof Exception)
        {
            return (Exception) current;
        }
        if (current instanceof Error)
        {
            throw (Error) current;
        }
        return new Exception(current);
    }
}
