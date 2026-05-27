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
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.engine.lsp.debug;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.finos.legend.engine.lsp.LegendPureSession;
import org.finos.legend.engine.lsp.RepositoryScanner;
import org.finos.legend.engine.lsp.UriMapper;
import org.finos.legend.engine.lsp.protocol.LegendDebug;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class LegendDebugSessionTest
{
    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test(timeout = 60_000)
    public void normalRuntimeCompilesAndExecutesDebugWithoutStopping()
    {
        LegendPureSession session = newInitializedSession();
        LegendPureSession.CompileResult compile = session.modifyAndCompile(
                "debug_normal_go.pure",
                "function go():Any[*]\n" +
                        "{\n" +
                        "  meta::pure::ide::debug();\n" +
                        "  'done';\n" +
                        "}\n");

        Assert.assertTrue("debug() should compile in the normal LSP runtime: " + errorMessage(compile), compile.isSuccess());
        LegendPureSession.ExecuteResult execute = session.executeGo();
        Assert.assertTrue("normal executeGo must not stop on debug(): " + execute.getError(), execute.isSuccess());
    }

    @Test(timeout = 60_000)
    public void debugRuntimeStopsAtExplicitDebugExposesVariablesEvaluatesAndContinues()
    {
        LegendPureSession session = newInitializedSession();
        assertCompiled(session.modifyAndCompile(
                "debug_explicit_go.pure",
                "function go():Any[*]\n" +
                        "{\n" +
                        "  let x = 'hello';\n" +
                        "  meta::pure::ide::debug();\n" +
                        "  $x;\n" +
                        "}\n"));

        LegendDebugSession debug = LegendDebugSession.create(
                session, null, new UriMapper(), Collections.emptyMap(), "go():Any[*]", Collections.emptyList());

        LegendDebug.Response paused = debug.start();
        Assert.assertTrue(paused.isSuccess());
        Assert.assertEquals("paused", paused.getState());
        Assert.assertEquals("pause", paused.getReason());
        Assert.assertTrue(debug.variables().stream().anyMatch(variable -> "x".equals(variable.getName())));

        LegendDebug.EvaluateResult evaluated = debug.evaluate("$x");
        Assert.assertTrue("Evaluate should succeed: " + evaluated.getError(), evaluated.isSuccess());

        LegendDebug.Response completed = debug.continueExecution();
        Assert.assertTrue(completed.isSuccess());
        Assert.assertEquals("completed", completed.getState());
    }

    @Test(timeout = 60_000)
    public void redDotBreakpointUsesUnchangedDebugCopyAndRealSourceLine()
    {
        LegendPureSession session = newInitializedSession();
        String sourceId = "debug_breakpoint_go.pure";
        String uri = "file:///workspace/debug_breakpoint_go.pure";
        String code =
                "function helper():Any[*]\n" +
                        "{\n" +
                        "  let x = 'red';\n" +
                        "  print($x, 1);\n" +
                        "}\n" +
                        "function go():Any[*]\n" +
                        "{\n" +
                        "  helper();\n" +
                        "}\n";
        assertCompiled(session.modifyAndCompile(sourceId, code));

        UriMapper uriMapper = new UriMapper();
        uriMapper.register(uri, sourceId);

        LegendDebugSession debug = LegendDebugSession.create(
                session,
                null,
                uriMapper,
                Collections.emptyMap(),
                "go():Any[*]",
                Collections.singletonList(new LegendDebug.Breakpoint(uri, 3)));

        LegendDebug.Response paused = debug.start();
        Assert.assertTrue(paused.isSuccess());
        Assert.assertEquals("paused", paused.getState());
        Assert.assertEquals("breakpoint", paused.getReason());
        Assert.assertEquals("Breakpoint line should use the original source line",
                4, paused.getStackFrames().get(0).getLine());
        Assert.assertTrue(debug.variables().stream().anyMatch(variable -> "x".equals(variable.getName())));
        Assert.assertEquals("Main runtime source must not be modified by debugger startup",
                code, session.getPureRuntime().getSourceById(sourceId).getContent());
        Assert.assertEquals("Debug runtime source must not be instrumented",
                code, debug.debugSourceContent(sourceId));

        debug.stop();
    }

    @Test(timeout = 60_000)
    public void debugRuntimeUsesUnsavedOpenDocumentSnapshot()
    {
        LegendPureSession session = newInitializedSession();
        String sourceId = "debug_unsaved_go.pure";
        String uri = "file:///workspace/debug_unsaved_go.pure";
        UriMapper uriMapper = new UriMapper();
        uriMapper.register(uri, sourceId);

        String unsavedCode =
                "function go():Any[*]\n" +
                        "{\n" +
                        "  let x = 'unsaved';\n" +
                        "  meta::pure::ide::debug();\n" +
                        "  $x;\n" +
                        "}\n";
        LegendDebugSession debug = LegendDebugSession.create(
                session,
                null,
                uriMapper,
                Collections.singletonMap(sourceId, unsavedCode),
                "go():Any[*]",
                Collections.emptyList());

        LegendDebug.Response paused = debug.start();
        Assert.assertTrue(paused.isSuccess());
        Assert.assertEquals("paused", paused.getState());
        Assert.assertTrue(debug.variables().stream().anyMatch(variable -> "x".equals(variable.getName())));
        Assert.assertNull("Unsaved debug source should not be added to the main runtime",
                session.getPureRuntime().getSourceById(sourceId));

        debug.stop();
    }

    @Test(timeout = 60_000)
    public void debugRuntimeDoesNotInstrumentClasspathRepositorySources()
    {
        LegendPureSession session = new LegendPureSession();
        session.initialize(null, Collections.singleton("core_functions_unclassified"));
        Assert.assertNotNull(session.getPureRuntime().getSourceById(
                "/core_functions_unclassified/meta/type/function/functionDescriptorToId.pure"));
        String classpathSourceContent = session.getPureRuntime().getSourceById(
                "/core_functions_unclassified/meta/type/function/functionDescriptorToId.pure").getContent();
        assertCompiled(session.modifyAndCompile(
                "debug_with_classpath_dependencies_go.pure",
                "function go():Any[*]\n" +
                        "{\n" +
                        "  let x = 'classpath';\n" +
                        "  meta::pure::ide::debug();\n" +
                        "  $x;\n" +
                        "}\n"));

        LegendDebugSession debug = LegendDebugSession.create(
                session, null, new UriMapper(), Collections.emptyMap(), "go():Any[*]", Collections.emptyList());

        LegendDebug.Response paused = debug.start();
        Assert.assertTrue("Debug start should not reparse instrumented classpath sources: " + paused.getMessage(), paused.isSuccess());
        Assert.assertEquals("paused", paused.getState());
        Assert.assertTrue(debug.variables().stream().anyMatch(variable -> "x".equals(variable.getName())));
        Assert.assertEquals("Classpath source content must remain unchanged in the main runtime",
                classpathSourceContent,
                session.getPureRuntime().getSourceById(
                        "/core_functions_unclassified/meta/type/function/functionDescriptorToId.pure").getContent());
        Assert.assertEquals("Classpath source content must remain unchanged in the debug runtime",
                classpathSourceContent,
                debug.debugSourceContent("/core_functions_unclassified/meta/type/function/functionDescriptorToId.pure"));

        debug.stop();
    }

    @Test(timeout = 60_000)
    public void debugRuntimeDoesNotInstrumentConfiguredDependencyReposFoundInWorkspace() throws IOException
    {
        Path resourcesDir = this.tempFolder.getRoot().toPath().resolve("configured-dependency/src/main/resources");
        Path repoDir = resourcesDir.resolve("debug_dependency_repo/debugdep");
        Files.createDirectories(repoDir);
        Files.write(resourcesDir.resolve("debug_dependency_repo.definition.json"),
                ("{\"name\":\"debug_dependency_repo\","
                        + "\"pattern\":\"(debugdep)(::.*)?\","
                        + "\"dependencies\":[\"platform\"]}").getBytes(StandardCharsets.UTF_8));
        String dependencyCode =
                "function debugdep::dependency():Any[*]\n" +
                        "{\n" +
                        "  print(\n" +
                        "    'dependency',\n" +
                        "    1);\n" +
                        "}\n";
        Files.write(repoDir.resolve("dependency.pure"), dependencyCode.getBytes(StandardCharsets.UTF_8));

        RepositoryScanner scanner = new RepositoryScanner();
        scanner.scan(Collections.singletonList(this.tempFolder.getRoot().toPath()));
        LegendPureSession session = new LegendPureSession();
        session.initialize(scanner, Collections.singleton("debug_dependency_repo"));
        assertCompiled(session.modifyAndCompile(
                "debug_configured_dependency_go.pure",
                "function go():Any[*]\n" +
                        "{\n" +
                        "  let x = 'configured dependency';\n" +
                        "  meta::pure::ide::debug();\n" +
                        "  $x;\n" +
                        "}\n"));

        LegendDebugSession debug = LegendDebugSession.create(
                session, scanner, new UriMapper(), Collections.emptyMap(), "go():Any[*]", Collections.emptyList());

        LegendDebug.Response paused = debug.start();
        Assert.assertTrue("Configured dependency repo sources must not be instrumented: " + paused.getMessage(), paused.isSuccess());
        Assert.assertEquals("paused", paused.getState());
        Assert.assertTrue(debug.variables().stream().anyMatch(variable -> "x".equals(variable.getName())));

        debug.stop();
    }

    @Test(timeout = 60_000)
    public void debugRuntimeOverlaysWorkspaceRepositorySources() throws IOException
    {
        Path resourcesDir = this.tempFolder.getRoot().toPath().resolve("module/src/main/resources");
        Path repoDir = resourcesDir.resolve("core_relational_memsql/debug");
        Files.createDirectories(repoDir);
        Files.write(resourcesDir.resolve("core_relational_memsql.definition.json"),
                ("{\"name\":\"core_relational_memsql\","
                        + "\"pattern\":\"(debug)(::.*)?\","
                        + "\"dependencies\":[\"platform\"]}").getBytes(StandardCharsets.UTF_8));

        String sourceId = "/core_relational_memsql/debug/go.pure";
        Path sourceFile = repoDir.resolve("go.pure");
        String code =
                "function debug::go():Any[*]\n" +
                        "{\n" +
                        "  let x = 'repo';\n" +
                        "  print($x, 1);\n" +
                        "}\n";
        Files.write(sourceFile, code.getBytes(StandardCharsets.UTF_8));

        RepositoryScanner scanner = new RepositoryScanner();
        scanner.scan(Collections.singletonList(this.tempFolder.getRoot().toPath()));
        LegendPureSession session = new LegendPureSession();
        session.initialize(scanner);
        Assert.assertNotNull(session.getPureRuntime().getSourceById(sourceId));

        UriMapper uriMapper = new UriMapper();
        String uri = sourceFile.toUri().toString();
        uriMapper.register(uri, sourceId);
        LegendDebugSession debug = LegendDebugSession.create(
                session,
                scanner,
                uriMapper,
                Collections.emptyMap(),
                "debug::go():Any[*]",
                Collections.singletonList(new LegendDebug.Breakpoint(uri, 3)));

        LegendDebug.Response paused = debug.start();
        Assert.assertTrue(paused.isSuccess());
        Assert.assertEquals("paused", paused.getState());
        Assert.assertEquals("breakpoint", paused.getReason());
        Assert.assertEquals(4, paused.getStackFrames().get(0).getLine());
        Assert.assertEquals(code, session.getPureRuntime().getSourceById(sourceId).getContent());
        Assert.assertEquals(code, debug.debugSourceContent(sourceId));
        Assert.assertEquals(code, new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8));

        debug.stop();
    }

    @Test(timeout = 60_000)
    public void mainSessionStillWorksWhileDebugExecutionIsPaused()
    {
        LegendPureSession session = newInitializedSession();
        assertCompiled(session.modifyAndCompile(
                "debug_paused_go.pure",
                "function go():Any[*]\n" +
                        "{\n" +
                        "  meta::pure::ide::debug();\n" +
                        "  'done';\n" +
                        "}\n"));

        LegendDebugSession debug = LegendDebugSession.create(
                session, null, new UriMapper(), Collections.emptyMap(), "go():Any[*]", Collections.emptyList());
        LegendDebug.Response paused = debug.start();
        Assert.assertEquals("paused", paused.getState());

        LegendPureSession.CompileResult compile = session.modifyAndCompile(
                "debug_main_still_live.pure",
                "Class test::debug::StillLive\n{\n  name: String[1];\n}\n");
        Assert.assertTrue("Main session should compile while debug runtime is paused: " + errorMessage(compile), compile.isSuccess());

        debug.stop();
    }

    @Test(timeout = 60_000)
    public void stepOverStaysInTheCurrentFunction()
    {
        LegendPureSession session = newInitializedSession();
        String sourceId = "debug_step_over_go.pure";
        String uri = "file:///workspace/debug_step_over_go.pure";
        assertCompiled(session.modifyAndCompile(sourceId, steppingCode()));

        UriMapper uriMapper = new UriMapper();
        uriMapper.register(uri, sourceId);
        LegendDebugSession debug = LegendDebugSession.create(
                session,
                null,
                uriMapper,
                Collections.emptyMap(),
                "go():Any[*]",
                Collections.singletonList(new LegendDebug.Breakpoint(uri, 8)));

        Assert.assertEquals(9, debug.start().getStackFrames().get(0).getLine());
        LegendDebug.Response stepped = debug.stepOver();
        Assert.assertEquals("step", stepped.getReason());
        Assert.assertEquals(10, stepped.getStackFrames().get(0).getLine());

        debug.stop();
    }

    @Test(timeout = 60_000)
    public void stepInStopsInsideNestedPureFunction()
    {
        LegendPureSession session = newInitializedSession();
        String sourceId = "debug_step_in_go.pure";
        String uri = "file:///workspace/debug_step_in_go.pure";
        assertCompiled(session.modifyAndCompile(sourceId, steppingCode()));

        UriMapper uriMapper = new UriMapper();
        uriMapper.register(uri, sourceId);
        LegendDebugSession debug = LegendDebugSession.create(
                session,
                null,
                uriMapper,
                Collections.emptyMap(),
                "go():Any[*]",
                Collections.singletonList(new LegendDebug.Breakpoint(uri, 8)));

        Assert.assertEquals(9, debug.start().getStackFrames().get(0).getLine());
        LegendDebug.Response stepped = debug.stepIn();
        Assert.assertEquals("step", stepped.getReason());
        Assert.assertEquals(3, stepped.getStackFrames().get(0).getLine());

        debug.stop();
    }

    @Test(timeout = 60_000)
    public void breakpointsStopOnCommonFunctionExecutionStatementBoundaries()
    {
        LegendPureSession session = newInitializedSession();
        String sourceId = "debug_common_statement_breakpoints_go.pure";
        String uri = "file:///workspace/debug_common_statement_breakpoints_go.pure";
        String code =
                "function helper():String[1]\n" +
                        "{\n" +
                        "  'helper';\n" +
                        "}\n" +
                        "function go():Any[*]\n" +
                        "{\n" +
                        "  let x = helper();\n" +
                        "  print($x, 1);\n" +
                        "  'abc'->toString();\n" +
                        "}\n";
        assertCompiled(session.modifyAndCompile(sourceId, code));

        assertBreakpointLine(session, sourceId, uri, 6, 7);
        assertBreakpointLine(session, sourceId, uri, 7, 8);
        assertBreakpointLine(session, sourceId, uri, 8, 9);
    }

    @Ignore("FunctionExecutionInterpreted.executeFunction is not invoked for bare variable/literal ValueSpecifications; this requires a guarded ValueSpecification hook in legend-pure.")
    @Test(timeout = 60_000)
    public void breakpointOnVariableOnlyExpressionRequiresPureValueSpecificationHook()
    {
        LegendPureSession session = newInitializedSession();
        String sourceId = "debug_variable_only_breakpoint_go.pure";
        String uri = "file:///workspace/debug_variable_only_breakpoint_go.pure";
        String code =
                "function go():Any[*]\n" +
                        "{\n" +
                        "  let x = 'value';\n" +
                        "  $x;\n" +
                        "}\n";
        assertCompiled(session.modifyAndCompile(sourceId, code));

        assertBreakpointLine(session, sourceId, uri, 3, 4);
    }

    private static String steppingCode()
    {
        return "function helper():Any[*]\n" +
                "{\n" +
                "  print('inside', 1);\n" +
                "}\n" +
                "\n" +
                "function go():Any[*]\n" +
                "{\n" +
                "  let x = 'start';\n" +
                "  helper();\n" +
                "  print('after', 1);\n" +
                "}\n";
    }

    private static void assertBreakpointLine(LegendPureSession session, String sourceId, String uri,
                                             int breakpointLineZeroBased, int expectedLineOneBased)
    {
        UriMapper uriMapper = new UriMapper();
        uriMapper.register(uri, sourceId);
        LegendDebugSession debug = LegendDebugSession.create(
                session,
                null,
                uriMapper,
                Collections.emptyMap(),
                "go():Any[*]",
                Collections.singletonList(new LegendDebug.Breakpoint(uri, breakpointLineZeroBased)));

        LegendDebug.Response paused = debug.start();
        Assert.assertTrue("Expected breakpoint pause: " + paused.getMessage(), paused.isSuccess());
        Assert.assertEquals("paused", paused.getState());
        Assert.assertEquals("breakpoint", paused.getReason());
        Assert.assertEquals(expectedLineOneBased, paused.getStackFrames().get(0).getLine());
        debug.stop();
    }

    private static LegendPureSession newInitializedSession()
    {
        LegendPureSession session = new LegendPureSession();
        session.initialize();
        return session;
    }

    private static void assertCompiled(LegendPureSession.CompileResult result)
    {
        Assert.assertTrue("Expected compile success: " + errorMessage(result), result.isSuccess());
    }

    private static String errorMessage(LegendPureSession.CompileResult result)
    {
        return result.getError() == null ? "" : result.getError().getMessage();
    }
}
