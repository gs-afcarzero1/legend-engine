// Copyright 2025 Goldman Sachs
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

package org.finos.legend.engine.mcp.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.block.function.Function3;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.HelperValueSpecificationBuilder;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.language.pure.grammar.to.PureGrammarComposer;
import org.finos.legend.engine.language.pure.grammar.to.PureGrammarComposerContext;
import org.finos.legend.engine.language.pure.modelManager.ModelManager;
import org.finos.legend.engine.mcp.protocol.v20251125.implementation.Implementation;
import org.finos.legend.engine.mcp.protocol.v20251125.notification.Notification;
import org.finos.legend.engine.mcp.protocol.v20251125.request.Request;
import org.finos.legend.engine.mcp.protocol.v20251125.response.Response;
import org.finos.legend.engine.mcp.protocol.v20251125.result.content.ContentBlock;
import org.finos.legend.engine.mcp.protocol.v20251125.result.content.TextContent;
import org.finos.legend.engine.mcp.protocol.v20251125.tool.Tool;
import org.finos.legend.engine.mcp.protocol.v20251125.tool.ToolAnnotations;
import org.finos.legend.engine.mcp.server.orchestrator.LegendStatelessMcpServerOrchestrator;
import org.finos.legend.engine.plan.execution.PlanExecutor;
import org.finos.legend.engine.plan.execution.result.ConstantResult;
import org.finos.legend.engine.plan.execution.result.Result;
import org.finos.legend.engine.plan.execution.result.StreamingResult;
import org.finos.legend.engine.plan.execution.result.serialization.SerializationFormat;
import org.finos.legend.engine.plan.generation.PlanGenerator;
import org.finos.legend.engine.plan.generation.extension.PlanGeneratorExtension;
import org.finos.legend.engine.plan.generation.transformers.PlanTransformer;
import org.finos.legend.engine.plan.platform.PlanPlatform;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.SingleExecutionPlan;
import org.finos.legend.engine.protocol.pure.PureClientVersions;
import org.finos.legend.engine.protocol.pure.m3.PackageableElement;
import org.finos.legend.engine.protocol.pure.m3.function.LambdaFunction;
import org.finos.legend.engine.protocol.pure.v1.PureProtocolObjectMapperFactory;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.pure.code.core.PureCoreExtensionLoader;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.finos.legend.engine.shared.core.deployment.DeploymentMode;
import org.finos.legend.engine.shared.core.identity.Identity;
import org.finos.legend.pure.generated.Root_meta_pure_extension_Extension;
import org.finos.legend.pure.m3.coreinstance.meta.pure.mapping.Mapping;
import org.finos.legend.pure.m3.execution.FunctionExecution;
import org.finos.legend.pure.m3.serialization.filesystem.repository.CodeRepository;
import org.finos.legend.pure.m3.serialization.filesystem.repository.CodeRepositoryProviderHelper;
import org.finos.legend.pure.m3.serialization.filesystem.repository.CodeRepositorySet;
import org.finos.legend.pure.m3.serialization.filesystem.usercodestorage.classpath.ClassLoaderCodeStorage;
import org.finos.legend.pure.m3.serialization.filesystem.usercodestorage.composite.CompositeCodeStorage;
import org.finos.legend.pure.m3.serialization.runtime.GraphLoader;
import org.finos.legend.pure.m3.serialization.runtime.Message;
import org.finos.legend.pure.m3.serialization.runtime.PureRuntime;
import org.finos.legend.pure.m3.serialization.runtime.PureRuntimeBuilder;
import org.finos.legend.pure.m3.serialization.runtime.VoidPureRuntimeStatus;
import org.finos.legend.pure.m3.serialization.runtime.binary.PureRepositoryJarLibrary;
import org.finos.legend.pure.m3.serialization.runtime.binary.SimplePureRepositoryJarLibrary;
import org.finos.legend.pure.m3.navigation.Instance;
import org.finos.legend.pure.m4.coreinstance.CoreInstance;
import org.finos.legend.pure.runtime.java.interpreted.FunctionExecutionInterpreted;
import org.eclipse.collections.api.list.ListIterable;
import org.eclipse.collections.impl.tuple.Tuples;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Engine-side MCP tool implementation that lives in the child classloader. Provides compile,
 * execution plan generation, interpreted Pure execution, and grammar conversion tools.
 *
 * <p>This class is initialized reflectively by the bootstrap server: the bootstrap calls
 * {@link #initialize()} to set up the Jackson object mapper and MCP orchestrator, then delegates
 * incoming JSON-RPC messages to {@link #handleJsonRpc(String)}. Tool state (compiled model,
 * Pure runtime) is maintained across calls within a single session.
 */
public class LegendEngineMcpToolDefinitions
{
    private static final String SERVER_NAME = "legend-engine-mcp-server";
    private static final String SERVER_TITLE = "Legend Engine MCP Server";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String SERVER_DESCRIPTION = "MCP server exposing Legend Engine compile, plan generation, and Pure execution capabilities (hot-reload enabled)";

    private static LegendStatelessMcpServerOrchestrator orchestrator;
    private static ObjectMapper objectMapper;
    private static ObjectMapper protocolObjectMapper;

    private LegendEngineMcpToolDefinitions()
    {
    }

    /**
     * Initializes the engine runtime: registers Pure protocol extensions, creates the Jackson
     * object mapper, builds the MCP orchestrator, and logs the available tool count.
     * Must be called exactly once before any other public method.
     */
    public static void initialize()
    {
        PureProtocolObjectMapperFactory.withPureProtocolExtensions(ObjectMapperFactory.getNewStandardObjectMapper());
        objectMapper = ObjectMapperFactory.getNewStandardObjectMapper();
        protocolObjectMapper = PureProtocolObjectMapperFactory.getNewObjectMapper();
        ModelManager modelManager = new ModelManager(DeploymentMode.TEST);
        orchestrator = createOrchestrator(modelManager);
        System.err.println("Engine initialized with " + getTools().size() + " tools.");
    }

    /**
     * Processes a single JSON-RPC 2.0 message (request or notification) and returns the response.
     * Requests produce a JSON-RPC response string; notifications return {@code null}.
     *
     * @param jsonRpcMessage the incoming JSON-RPC message
     * @return the JSON-RPC response string, or {@code null} for notifications
     */
    public static String handleJsonRpc(String jsonRpcMessage) throws Exception
    {
        JsonNode node = objectMapper.readTree(jsonRpcMessage);

        if (node.has("id"))
        {
            Request request = objectMapper.treeToValue(node, Request.class);
            Response response = orchestrator.handleRequest(request, Identity.getAnonymousIdentity());
            return objectMapper.writeValueAsString(response);
        }
        else
        {
            Notification notification = objectMapper.treeToValue(node, Notification.class);
            orchestrator.handleNotification(notification, Identity.getAnonymousIdentity());
            return null;
        }
    }

    /** Returns the MCP server implementation descriptor with name, title, version, and description. */
    public static Implementation getServerInfo()
    {
        return new Implementation(SERVER_DESCRIPTION, null, SERVER_NAME, SERVER_TITLE, SERVER_VERSION, null);
    }

    /**
     * Returns the full list of MCP tool definitions supported by this server, including grammar
     * conversion, compile, plan generation, Pure execution, and bootstrap management tools.
     */
    public static List<Tool> getTools()
    {
        List<Tool> tools = new ArrayList<>();
        addGrammarTools(tools);
        addCompileTools(tools);
        addPlanTools(tools);
        addExecutionTools(tools);
        addBootstrapTools(tools);
        return tools;
    }

    /**
     * Creates the tool executor function that dispatches incoming tool calls to the appropriate
     * handler. Maintains per-session state for the compiled model and the lazy-initialized
     * interpreted Pure runtime.
     *
     * @param modelManager the model manager used for compiling Pure grammar
     * @return a function that maps (tool, params, identity) to a list of content blocks
     */
    public static Function3<Tool, Map<String, Object>, Identity, List<ContentBlock>> getToolExecutor(ModelManager modelManager)
    {
        final PureModel[] compiledModel = {null};
        final PureModelContextData[] compiledData = {null};
        // Lazy-initialized interpreted Pure runtime (for execute_pure)
        final PureRuntime[] pureRuntime = {null};
        final FunctionExecution[] funcExecution = {null};

        return (tool, params, identity) ->
        {
            try
            {
                return dispatch(tool, params, modelManager, identity, compiledModel, compiledData, pureRuntime, funcExecution);
            }
            catch (Exception e)
            {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                return Collections.singletonList(new TextContent(null, null, "Error: " + e.getMessage() + "\n" + sw));
            }
        };
    }

    /**
     * Creates a stateless MCP server orchestrator wired with this server's info, tools, and
     * tool executor.
     *
     * @param modelManager the model manager for compilation
     * @return a configured orchestrator ready to handle requests
     */
    public static LegendStatelessMcpServerOrchestrator createOrchestrator(ModelManager modelManager)
    {
        return new LegendStatelessMcpServerOrchestrator(getServerInfo(), getTools(), getToolExecutor(modelManager));
    }

    private static void addGrammarTools(List<Tool> tools)
    {
        tools.add(new Tool(
                null,
                new ToolAnnotations(false, true, false, true, null),
                "Parse Pure grammar text into PureModelContextData JSON. Returns the protocol JSON representation of the model.",
                null, null,
                new Tool.Schema(
                        "https://json-schema.org/draft/2020-12/schema#",
                        mapOf("code", mapOf("type", "string", "description", "The Pure grammar text to parse (e.g. 'Class my::Person { name: String[1]; }')")),
                        Collections.singletonList("code")),
                "grammar_to_json", null, "Grammar to JSON"));

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, true, false, true, null),
                "Convert PureModelContextData JSON back to Pure grammar text.",
                null, null,
                new Tool.Schema(
                        "https://json-schema.org/draft/2020-12/schema#",
                        mapOf("json", mapOf("type", "string", "description", "The PureModelContextData JSON string to convert to grammar")),
                        Collections.singletonList("json")),
                "json_to_grammar", null, "JSON to Grammar"));
    }

    private static void addCompileTools(List<Tool> tools)
    {
        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Compile Pure grammar text into a PureModel. The compiled model is stored in session and can be used by generate_plan. Returns 'OK' on success or error details.",
                null, null,
                new Tool.Schema(
                        "https://json-schema.org/draft/2020-12/schema#",
                        mapOf("code", mapOf("type", "string", "description", "The Pure grammar text to compile")),
                        Collections.singletonList("code")),
                "compile", null, "Compile"));
    }

    private static void addPlanTools(List<Tool> tools)
    {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("lambda", mapOf("type", "string", "description", "The lambda expression as Pure grammar text (e.g. '|my::Person.all()')"));
        props.put("mapping", mapOf("type", "string", "description", "The fully-qualified mapping path (e.g. 'my::MyMapping')"));
        props.put("runtime", mapOf("type", "string", "description", "The fully-qualified runtime path (e.g. 'my::MyRuntime')"));

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Generate an execution plan from a lambda, mapping, and runtime. Requires a previous successful compile call. Returns the plan JSON.",
                null, null,
                new Tool.Schema(
                        "https://json-schema.org/draft/2020-12/schema#",
                        props,
                        Arrays.asList("lambda", "mapping", "runtime")),
                "generate_plan", null, "Generate Plan"));

        Map<String, Object> execProps = new LinkedHashMap<>();
        execProps.put("lambda", mapOf("type", "string", "description", "The lambda expression as Pure grammar text (e.g. '|my::Person.all()')"));
        execProps.put("mapping", mapOf("type", "string", "description", "The fully-qualified mapping path (e.g. 'my::MyMapping')"));
        execProps.put("runtime", mapOf("type", "string", "description", "The fully-qualified runtime path (e.g. 'my::MyRuntime')"));

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Execute a query against a live database. Generates an execution plan from the lambda, mapping, and runtime, "
                        + "then executes it and returns the results as JSON. Requires a previous successful compile call. "
                        + "Supports all store types available on the classpath (Relational/H2, MongoDB, etc.).",
                null, null,
                new Tool.Schema(
                        "https://json-schema.org/draft/2020-12/schema#",
                        execProps,
                        Arrays.asList("lambda", "mapping", "runtime")),
                "execute", null, "Execute Query"));
    }

    private static void addBootstrapTools(List<Tool> tools)
    {
        Map<String, Object> rebuildProps = new LinkedHashMap<>();
        rebuildProps.put("module", mapOf("type", "string", "description", "The maven module path to rebuild"));
        rebuildProps.put("scope", mapOf("type", "string", "description",
                "Dependency-aware rebuild scope. Supported values: 'self', 'upstream', 'downstream', 'closure'. Defaults to 'self'.",
                "enum", Arrays.asList("self", "upstream", "downstream", "closure")));

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Rebuild a specific maven module (runs mvn compile and tracks a local target/classes overlay for the next reload). "
                        + "Example module: 'legend-engine-xts-mcp/legend-engine-xt-mcp-engine-server'. "
                        + "Use scope 'self', 'upstream', 'downstream', or 'closure' to choose reactor-aware rebuild breadth. Defaults to 'self'.",
                null, null,
                new Tool.Schema(
                        "https://json-schema.org/draft/2020-12/schema#",
                        rebuildProps,
                        Collections.singletonList("module")),
                "rebuild", null, "Rebuild Module"));

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Hot-reload the engine ClassLoader. Use this after rebuilding a module to load the new classes and reset extension caches. "
                        + "Reload is atomic: if it fails, the current working child runtime is preserved.",
                null, null,
                new Tool.Schema("https://json-schema.org/draft/2020-12/schema#", Collections.emptyMap(), Collections.emptyList()),
                "reload", null, "Reload Engine"));

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Report bootstrap state including configured and active profiles, active and pending overlays, "
                        + "child classpath file location, and what reload can and cannot hot-swap.",
                null, null,
                new Tool.Schema("https://json-schema.org/draft/2020-12/schema#", Collections.emptyMap(), Collections.emptyList()),
                "classpath_status", null, "Classpath Status"));

        Map<String, Object> affectedProps = new LinkedHashMap<>();
        affectedProps.put("module", mapOf("type", "string", "description", "The maven module path to inspect"));
        affectedProps.put("scope", mapOf("type", "string", "description",
                "Dependency-aware inspection scope. Supported values: 'self', 'upstream', 'downstream', 'closure'. Defaults to 'self'.",
                "enum", Arrays.asList("self", "upstream", "downstream", "closure")));

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Resolve local reactor modules affected by a given module and scope. Returns module path, artifactId, direction, "
                        + "and whether each module is on the active child classpath.",
                null, null,
                new Tool.Schema(
                        "https://json-schema.org/draft/2020-12/schema#",
                        affectedProps,
                        Collections.singletonList("module")),
                "affected_modules", null, "Affected Modules"));

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Report whether a local reactor module is on the active child classpath, which base jar entry would be used, "
                        + "and whether configured or active overlays exist for it.",
                null, null,
                new Tool.Schema(
                        "https://json-schema.org/draft/2020-12/schema#",
                        mapOf("module", mapOf("type", "string", "description", "The maven module path to inspect")),
                        Collections.singletonList("module")),
                "module_status", null, "Module Status"));

        Map<String, Object> profileProps = new LinkedHashMap<>();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("type", "string");
        profile.put("description", "The named classpath profile to use. Supported values: 'base' and 'server'.");
        profile.put("enum", Arrays.asList("base", "server"));
        profileProps.put("profile", profile);

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Switch the donor classpath profile, regenerate the child classpath if needed, and reload atomically. "
                        + "If the switch fails, the current working child runtime is preserved.",
                null, null,
                new Tool.Schema(
                        "https://json-schema.org/draft/2020-12/schema#",
                        profileProps,
                        Collections.singletonList("profile")),
                "set_classpath_profile", null, "Set Classpath Profile"));

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Restore the bootstrap state to the default 'base' profile, clear configured overlays, and reload atomically. "
                        + "If restore fails, the current working child runtime is preserved.",
                null, null,
                new Tool.Schema("https://json-schema.org/draft/2020-12/schema#", Collections.emptyMap(), Collections.emptyList()),
                "restore_space", null, "Restore Space"));
    }

    private static List<ContentBlock> dispatch(Tool tool, Map<String, Object> params, ModelManager modelManager,
                                               Identity identity, PureModel[] compiledModel, PureModelContextData[] compiledData,
                                               PureRuntime[] pureRuntime, FunctionExecution[] funcExecution)
    {
        switch (tool.getName())
        {
            case "grammar_to_json":
                return handleGrammarToJson(params);
            case "json_to_grammar":
                return handleJsonToGrammar(params);
            case "compile":
                return handleCompile(params, modelManager, identity, compiledModel, compiledData);
            case "generate_plan":
                return handleGeneratePlan(params, compiledModel, compiledData);
            case "execute":
                return handleExecute(params, compiledModel, compiledData);
            case "execute_pure":
                return handleExecutePure(params, pureRuntime, funcExecution);
            default:
                return Collections.singletonList(new TextContent(null, null, "Unknown tool: " + tool.getName()));
        }
    }

    private static List<ContentBlock> handleGrammarToJson(Map<String, Object> params)
    {
        String code = (String) params.get("code");
        try
        {
            PureGrammarParser parser = PureGrammarParser.newInstance();
            PureModelContextData data = parser.parseModel(code);
            String json = protocolObjectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
            return Collections.singletonList(new TextContent(null, null, json));
        }
        catch (Exception e)
        {
            return Collections.singletonList(new TextContent(null, null, "Parse error: " + e.getMessage()));
        }
    }

    private static List<ContentBlock> handleJsonToGrammar(Map<String, Object> params)
    {
        String json = (String) params.get("json");
        try
        {
            PureModelContextData data = protocolObjectMapper.readValue(json, PureModelContextData.class);
            String grammar = PureGrammarComposer.newInstance(PureGrammarComposerContext.Builder.newInstance().build())
                    .renderPureModelContextData(data);
            return Collections.singletonList(new TextContent(null, null, grammar));
        }
        catch (Exception e)
        {
            return Collections.singletonList(new TextContent(null, null, "Conversion error: " + e.getMessage()));
        }
    }

    private static List<ContentBlock> handleCompile(Map<String, Object> params, ModelManager modelManager,
                                                    Identity identity, PureModel[] compiledModel, PureModelContextData[] compiledData)
    {
        String code = (String) params.get("code");
        try
        {
            PureGrammarParser parser = PureGrammarParser.newInstance();
            PureModelContextData data = parser.parseModel(code);
            PureModel pureModel = modelManager.loadModel(data, PureClientVersions.production, identity, null);
            compiledModel[0] = pureModel;
            compiledData[0] = data;
            return Collections.singletonList(new TextContent(null, null, "Compilation successful.\n" + summarizeCompiledModel(data)));
        }
        catch (Exception e)
        {
            return Collections.singletonList(new TextContent(null, null, "Compilation error: " + e.getMessage()));
        }
    }

    private static String summarizeCompiledModel(PureModelContextData data)
    {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (PackageableElement element : data.getElements())
        {
            String type = element.getClass().getSimpleName();
            String path = (element._package == null ? "" : element._package + "::") + element.name;
            byType.computeIfAbsent(type, k -> new ArrayList<>()).add(path);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Elements compiled:");
        for (Map.Entry<String, List<String>> entry : byType.entrySet())
        {
            sb.append("\n  ").append(entry.getKey()).append(": ");
            List<String> paths = entry.getValue();
            if (paths.size() <= 5)
            {
                sb.append(String.join(", ", paths));
            }
            else
            {
                sb.append(String.join(", ", paths.subList(0, 5))).append(" ... (").append(paths.size()).append(" total)");
            }
        }
        return sb.toString();
    }

    private static String buildExecutionPlanJson(
            Map<String, Object> params, PureModel pureModel) throws Exception
    {
        String lambdaText = (String) params.get("lambda");
        String mappingPath = (String) params.get("mapping");
        String runtimePath = (String) params.get("runtime");

        PureGrammarParser parser = PureGrammarParser.newInstance();
        LambdaFunction lambda = parser.parseLambda(lambdaText);
        org.finos.legend.pure.m3.coreinstance.meta.pure.metamodel.function.LambdaFunction<?> compiledLambda =
                HelperValueSpecificationBuilder.buildLambda(lambda.body, lambda.parameters, pureModel.getContext());

        Mapping mapping = pureModel.getMapping(mappingPath);
        org.finos.legend.pure.generated.Root_meta_core_runtime_Runtime runtime = pureModel.getRuntime(runtimePath);

        MutableList<PlanGeneratorExtension> generatorExtensions =
                Lists.mutable.withAll(ServiceLoader.load(PlanGeneratorExtension.class));
        RichIterable<? extends Root_meta_pure_extension_Extension> extensions =
                PureCoreExtensionLoader.extensions().flatCollect(e -> e.extraPureCoreExtensions(pureModel.getExecutionSupport()));
        Iterable<? extends PlanTransformer> transformers =
                generatorExtensions.flatCollect(PlanGeneratorExtension::getExtraPlanTransformers);

        return PlanGenerator.generateExecutionPlanAsString(
                compiledLambda, mapping, runtime, null, pureModel, "vX_X_X",
                PlanPlatform.JAVA, null, extensions, transformers);
    }

    private static List<ContentBlock> handleGeneratePlan(Map<String, Object> params,
                                                         PureModel[] compiledModel, PureModelContextData[] compiledData)
    {
        if (compiledModel[0] == null)
        {
            return Collections.singletonList(new TextContent(null, null,
                    "No compiled model available. Please call the 'compile' tool first."));
        }

        try
        {
            String planJson = buildExecutionPlanJson(params, compiledModel[0]);
            return Collections.singletonList(new TextContent(null, null, planJson));
        }
        catch (Exception e)
        {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return Collections.singletonList(new TextContent(null, null,
                    "Plan generation error: " + e.getMessage() + "\n" + sw));
        }
    }

    private static List<ContentBlock> handleExecute(Map<String, Object> params,
                                                      PureModel[] compiledModel, PureModelContextData[] compiledData)
    {
        if (compiledModel[0] == null)
        {
            return Collections.singletonList(new TextContent(null, null,
                    "No compiled model available. Please call the 'compile' tool first."));
        }

        try
        {
            String planJson = buildExecutionPlanJson(params, compiledModel[0]);
            SingleExecutionPlan plan = PlanGenerator.stringToPlan(planJson);

            Result result = PlanExecutor.newPlanExecutorBuilder()
                    .withAvailableStoreExecutors()
                    .build()
                    .execute(plan);

            try
            {
                if (result instanceof StreamingResult)
                {
                    String output = ((StreamingResult) result).flush(
                            ((StreamingResult) result).getSerializer(SerializationFormat.DEFAULT));
                    return Collections.singletonList(new TextContent(null, null, output));
                }
                else if (result instanceof ConstantResult)
                {
                    Object value = ((ConstantResult) result).getValue();
                    return Collections.singletonList(new TextContent(null, null,
                            value == null ? "(null)" : value.toString()));
                }
                else
                {
                    return Collections.singletonList(new TextContent(null, null,
                            "Execution completed. Result type: " + result.getClass().getSimpleName()));
                }
            }
            finally
            {
                result.close();
            }
        }
        catch (Exception e)
        {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return Collections.singletonList(new TextContent(null, null,
                    "Execution error: " + e.getMessage() + "\n" + sw));
        }
    }

    private static void addExecutionTools(List<Tool> tools)
    {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("code", mapOf("type", "string", "description",
                "Pure source code to execute. Must define a function matching the 'function' parameter signature (default: go():Any[*])."));
        props.put("function", mapOf("type", "string", "description",
                "Function signature to execute. Default: 'go():Any[*]'. Example: 'myFunc():String[1]'"));

        tools.add(new Tool(
                null,
                new ToolAnnotations(false, false, false, false, null),
                "Execute arbitrary Pure code via the interpreted engine. The code must define a function (default: go():Any[*]) "
                        + "which will be compiled and executed. Returns console output and result. "
                        + "First call initializes the Pure interpreter (~10-30s). Subsequent calls are fast. "
                        + "This is independent of the compile/generate_plan tools — it uses its own interpreted runtime.",
                null, null,
                new Tool.Schema(
                        "https://json-schema.org/draft/2020-12/schema#",
                        props,
                        Collections.singletonList("code")),
                "execute_pure", null, "Execute Pure"));
    }

    private static synchronized void initPureRuntime(PureRuntime[] pureRuntime, FunctionExecution[] funcExecution)
    {
        if (pureRuntime[0] != null)
        {
            return;
        }
        System.err.println("Initializing Pure interpreted runtime (first call — this may take a moment)...");
        long start = System.currentTimeMillis();

        RichIterable<CodeRepository> repositories = CodeRepositorySet.newBuilder()
                .withCodeRepositories(CodeRepositoryProviderHelper.findCodeRepositories(true))
                .build()
                .getRepositories()
                .select(p -> !p.getName().startsWith("other_") && !p.getName().startsWith("test_"));

        CompositeCodeStorage codeStorage = new CompositeCodeStorage(new ClassLoaderCodeStorage(repositories));

        Message message = new Message("");
        PureRuntime runtime = new PureRuntimeBuilder(codeStorage)
                .withRuntimeStatus(VoidPureRuntimeStatus.VOID_PURE_RUNTIME_STATUS)
                .withMessage(message)
                .setUseFastCompiler(true)
                .build();

        FunctionExecutionInterpreted functionExecution = new FunctionExecutionInterpreted();
        functionExecution.init(runtime, message);
        functionExecution.getConsole().disable();

        PureRepositoryJarLibrary jarLibrary = SimplePureRepositoryJarLibrary.newLibrary(
                GraphLoader.findJars(Lists.mutable.withAll(repositories.collect(CodeRepository::getName)),
                        Thread.currentThread().getContextClassLoader(), message));
        GraphLoader loader = new GraphLoader(runtime.getModelRepository(), runtime.getContext(),
                runtime.getIncrementalCompiler().getParserLibrary(), runtime.getIncrementalCompiler().getDslLibrary(),
                runtime.getSourceRegistry(), runtime.getURLPatternLibrary(), jarLibrary);
        loader.loadAll(message);

        runtime.loadAndCompileSystem();

        pureRuntime[0] = runtime;
        funcExecution[0] = functionExecution;
        long elapsed = System.currentTimeMillis() - start;
        System.err.println("Pure interpreted runtime initialized in " + elapsed + "ms.");
    }

    private static List<ContentBlock> handleExecutePure(Map<String, Object> params,
                                                         PureRuntime[] pureRuntime, FunctionExecution[] funcExecution)
    {
        String code = (String) params.get("code");
        String functionSig = params.containsKey("function") ? (String) params.get("function") : "go():Any[*]";
        String sourceId = "mcp_execute.pure";

        try
        {
            initPureRuntime(pureRuntime, funcExecution);
        }
        catch (Exception e)
        {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return Collections.singletonList(new TextContent(null, null,
                    "Failed to initialize Pure interpreter: " + e.getMessage() + "\n" + sw));
        }

        try
        {
            PureRuntime runtime = pureRuntime[0];
            FunctionExecution execution = funcExecution[0];

            runtime.createInMemoryAndCompile(
                    Tuples.pair(sourceId, code));

            CoreInstance function = runtime.getFunction(functionSig);
            if (function == null)
            {
                return Collections.singletonList(new TextContent(null, null,
                        "Function not found: " + functionSig
                                + ". Make sure your code defines a function with this exact signature."));
            }

            ByteArrayOutputStream consoleCapture = new ByteArrayOutputStream();
            execution.getConsole().setPrintStream(new PrintStream(consoleCapture, true));
            execution.getConsole().enable();

            CoreInstance result = execution.start(function, Lists.immutable.empty());

            StringBuilder output = new StringBuilder();
            String consoleOutput = consoleCapture.toString("UTF-8");
            if (!consoleOutput.isEmpty())
            {
                output.append(consoleOutput);
                if (!consoleOutput.endsWith("\n"))
                {
                    output.append("\n");
                }
            }
            if (result != null)
            {
                output.append("Result: ");
                try
                {
                    // Extract readable values from the result InstanceValue
                    ListIterable<? extends CoreInstance> values =
                            Instance
                                    .getValueForMetaPropertyToManyResolved(
                                            result, "values", runtime.getProcessorSupport());
                    if (values != null && values.notEmpty())
                    {
                        List<String> parts = new ArrayList<>();
                        for (CoreInstance value : values)
                        {
                            String name = value.getName();
                            parts.add(name != null ? name : value.toString());
                        }
                        output.append(String.join(", ", parts));
                    }
                    else
                    {
                        String name = result.getName();
                        output.append(name != null ? name : result.toString());
                    }
                }
                catch (Exception e)
                {
                    output.append(result.toString());
                }
            }

            return Collections.singletonList(new TextContent(null, null,
                    output.length() > 0 ? output.toString() : "(no output)"));
        }
        catch (Exception e)
        {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            return Collections.singletonList(new TextContent(null, null,
                    "Execution error: " + e.getMessage() + "\n" + sw));
        }
        finally
        {
            funcExecution[0].getConsole().setPrintStream(new PrintStream(new ByteArrayOutputStream()));
            funcExecution[0].getConsole().disable();
            try
            {
                pureRuntime[0].delete(sourceId);
            }
            catch (Exception ignored)
            {
                // source may not exist if compile failed
            }
        }
    }

    private static Map<String, Object> mapOf(String k1, Object v1)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        return m;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2, String k3, Object v3)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        m.put(k3, v3);
        return m;
    }
}
