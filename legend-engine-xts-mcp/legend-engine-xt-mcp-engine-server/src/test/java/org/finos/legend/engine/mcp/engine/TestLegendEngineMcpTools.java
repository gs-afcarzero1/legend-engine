package org.finos.legend.engine.mcp.engine;

import org.finos.legend.engine.language.pure.modelManager.ModelManager;
import org.finos.legend.engine.mcp.protocol.v20251125.result.content.ContentBlock;
import org.finos.legend.engine.mcp.protocol.v20251125.result.content.TextContent;
import org.finos.legend.engine.mcp.protocol.v20251125.tool.Tool;
import org.finos.legend.engine.protocol.pure.v1.PureProtocolObjectMapperFactory;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.finos.legend.engine.shared.core.deployment.DeploymentMode;
import org.finos.legend.engine.shared.core.identity.Identity;
import org.eclipse.collections.api.block.function.Function3;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestLegendEngineMcpTools
{
    @Test
    public void testCompileAndGeneratePlan()
    {
        PureProtocolObjectMapperFactory
            .withPureProtocolExtensions(
                ObjectMapperFactory
                    .getNewStandardObjectMapper());

        ModelManager modelManager =
            new ModelManager(DeploymentMode.TEST);

        Function3<Tool, Map<String, Object>,
            Identity, List<ContentBlock>> executor =
            LegendEngineMcpToolDefinitions
                .getToolExecutor(modelManager);

        List<Tool> tools =
            LegendEngineMcpToolDefinitions.getTools();

        // Find the compile tool
        Tool compileTool = tools.stream()
            .filter(t -> "compile".equals(t.getName()))
            .findFirst().get();

        // Find the generate_plan tool
        Tool planTool = tools.stream()
            .filter(t -> "generate_plan".equals(t.getName()))
            .findFirst().get();

        // 1. Compile
        Map<String, Object> compileArgs = new HashMap<>();
        compileArgs.put("code",
            "Class my::Person\n" +
            "{\n" +
            "  name: String[1];\n" +
            "  age: Integer[1];\n" +
            "}\n" +
            "\n" +
            "###Relational\n" +
            "Database my::PersonDB\n" +
            "(\n" +
            "  Table personTable\n" +
            "  (\n" +
            "    name VARCHAR(200) PRIMARY KEY,\n" +
            "    age INTEGER\n" +
            "  )\n" +
            ")\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping my::PersonMapping\n" +
            "(\n" +
            "  my::Person: Relational\n" +
            "  {\n" +
            "    ~primaryKey\n" +
            "    (\n" +
            "      [my::PersonDB]personTable.name\n" +
            "    )\n" +
            "    ~mainTable [my::PersonDB]personTable\n" +
            "    name: [my::PersonDB]personTable.name,\n" +
            "    age: [my::PersonDB]personTable.age\n" +
            "  }\n" +
            ")\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime my::PersonRuntime\n" +
            "{\n" +
            "  mappings:\n" +
            "  [\n" +
            "    my::PersonMapping\n" +
            "  ];\n" +
            "  connections:\n" +
            "  [\n" +
            "    my::PersonDB:\n" +
            "    [\n" +
            "      connection_1:\n" +
            "      #{\n" +
            "        RelationalDatabaseConnection\n" +
            "        {\n" +
            "          store: my::PersonDB;\n" +
            "          type: H2;\n" +
            "          specification: LocalH2{};\n" +
            "          auth: DefaultH2{};\n" +
            "        }\n" +
            "      }#\n" +
            "    ]\n" +
            "  ];\n" +
            "}");

        List<ContentBlock> compileResult =
            executor.value(compileTool, compileArgs,
                Identity.getAnonymousIdentity());

        System.out.println("COMPILE RESULT:");
        for (ContentBlock cb : compileResult)
        {
            if (cb instanceof TextContent)
            {
                System.out.println(
                    ((TextContent) cb).getText());
            }
        }

        // 2. Generate Plan
        Map<String, Object> planArgs = new HashMap<>();
        planArgs.put("lambda", "|my::Person.all()");
        planArgs.put("mapping", "my::PersonMapping");
        planArgs.put("runtime", "my::PersonRuntime");

        List<ContentBlock> planResult =
            executor.value(planTool, planArgs,
                Identity.getAnonymousIdentity());

        System.out.println("\nGENERATE PLAN RESULT:");
        for (ContentBlock cb : planResult)
        {
            if (cb instanceof TextContent)
            {
                System.out.println(
                    ((TextContent) cb).getText());
            }
        }

        // Assert compilation succeeded
        String compileText =
            ((TextContent) compileResult.get(0)).getText();
        Assert.assertTrue(
            "Compile output should start with success message",
            compileText.startsWith("Compilation successful."));

        // Assert plan generation succeeded
        String planText =
            ((TextContent) planResult.get(0)).getText();
        Assert.assertFalse(
            "Plan should not start with error: " + planText,
            planText.startsWith("Plan generation error"));
        Assert.assertTrue(
            "Plan should contain SQL",
            planText.contains("select"));
    }

    @Test
    public void testExecutePure()
    {
        ModelManager modelManager =
            new ModelManager(DeploymentMode.TEST);

        Function3<Tool, Map<String, Object>,
            Identity, List<ContentBlock>> executor =
            LegendEngineMcpToolDefinitions
                .getToolExecutor(modelManager);

        List<Tool> tools =
            LegendEngineMcpToolDefinitions.getTools();

        Tool executeTool = tools.stream()
            .filter(t -> "execute_pure".equals(t.getName()))
            .findFirst().get();

        // Test 1: Return value only
        Map<String, Object> argsReturn = new HashMap<>();
        argsReturn.put("code",
            "function go():Any[*]\n" +
            "{\n" +
            "  'hello-result';\n" +
            "}\n");

        String textReturn =
            ((TextContent) executor.value(executeTool, argsReturn,
                Identity.getAnonymousIdentity()).get(0)).getText();

        Assert.assertFalse(
            "Should not be an error: " + textReturn,
            textReturn.startsWith("Execution error")
                || textReturn.startsWith("Failed to initialize"));
        Assert.assertTrue(
            "Return value should appear, got: " + textReturn,
            textReturn.contains("hello-result"));

        // Test 2: Console output via println
        Map<String, Object> argsConsole = new HashMap<>();
        argsConsole.put("code",
            "function go():Any[*]\n" +
            "{\n" +
            "  println('console-capture-test');\n" +
            "}\n");

        String textConsole =
            ((TextContent) executor.value(executeTool, argsConsole,
                Identity.getAnonymousIdentity()).get(0)).getText();

        Assert.assertFalse(
            "Should not be an error: " + textConsole,
            textConsole.startsWith("Execution error")
                || textConsole.startsWith("Failed to initialize"));
        Assert.assertTrue(
            "Console output from println should appear, got: " + textConsole,
            textConsole.contains("console-capture-test"));

        // Test 3: Both console output and return value
        Map<String, Object> argsBoth = new HashMap<>();
        argsBoth.put("code",
            "function go():Any[*]\n" +
            "{\n" +
            "  println('printed-output');\n" +
            "  'returned-value';\n" +
            "}\n");

        String textBoth =
            ((TextContent) executor.value(executeTool, argsBoth,
                Identity.getAnonymousIdentity()).get(0)).getText();

        Assert.assertFalse(
            "Should not be an error: " + textBoth,
            textBoth.startsWith("Execution error")
                || textBoth.startsWith("Failed to initialize"));
        Assert.assertTrue(
            "Combined: should contain console output, got: " + textBoth,
            textBoth.contains("printed-output"));
        Assert.assertTrue(
            "Combined: should contain return value, got: " + textBoth,
            textBoth.contains("returned-value"));
    }

    @Test
    public void testToolsAreAdvertised()
    {
        List<Tool> tools =
            LegendEngineMcpToolDefinitions.getTools();

        // Bootstrap tools
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "rebuild".equals(t.getName())));
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "reload".equals(t.getName())));
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "classpath_status".equals(t.getName())));
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "affected_modules".equals(t.getName())));
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "module_status".equals(t.getName())));
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "set_classpath_profile".equals(t.getName())));
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "restore_space".equals(t.getName())));

        // Engine tools
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "execute_pure".equals(t.getName())));
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "compile".equals(t.getName())));
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "generate_plan".equals(t.getName())));
        Assert.assertTrue(
            tools.stream().anyMatch(
                t -> "execute".equals(t.getName())));
    }

    @Test
    public void testExecuteWithH2()
    {
        PureProtocolObjectMapperFactory
            .withPureProtocolExtensions(
                ObjectMapperFactory
                    .getNewStandardObjectMapper());

        ModelManager modelManager =
            new ModelManager(DeploymentMode.TEST);

        Function3<Tool, Map<String, Object>,
            Identity, List<ContentBlock>> executor =
            LegendEngineMcpToolDefinitions
                .getToolExecutor(modelManager);

        List<Tool> tools =
            LegendEngineMcpToolDefinitions.getTools();

        Tool compileTool = tools.stream()
            .filter(t -> "compile".equals(t.getName()))
            .findFirst().get();
        Tool executeTool = tools.stream()
            .filter(t -> "execute".equals(t.getName()))
            .findFirst().get();

        // Compile a model with H2 in-memory database
        Map<String, Object> compileArgs = new HashMap<>();
        compileArgs.put("code",
            "Class my::Person\n" +
            "{\n" +
            "  name: String[1];\n" +
            "  age: Integer[1];\n" +
            "}\n" +
            "\n" +
            "###Relational\n" +
            "Database my::PersonDB\n" +
            "(\n" +
            "  Table personTable\n" +
            "  (\n" +
            "    name VARCHAR(200) PRIMARY KEY,\n" +
            "    age INTEGER\n" +
            "  )\n" +
            ")\n" +
            "\n" +
            "###Mapping\n" +
            "Mapping my::PersonMapping\n" +
            "(\n" +
            "  my::Person: Relational\n" +
            "  {\n" +
            "    ~primaryKey\n" +
            "    (\n" +
            "      [my::PersonDB]personTable.name\n" +
            "    )\n" +
            "    ~mainTable [my::PersonDB]personTable\n" +
            "    name: [my::PersonDB]personTable.name,\n" +
            "    age: [my::PersonDB]personTable.age\n" +
            "  }\n" +
            ")\n" +
            "\n" +
            "###Runtime\n" +
            "Runtime my::PersonRuntime\n" +
            "{\n" +
            "  mappings:\n" +
            "  [\n" +
            "    my::PersonMapping\n" +
            "  ];\n" +
            "  connections:\n" +
            "  [\n" +
            "    my::PersonDB:\n" +
            "    [\n" +
            "      connection_1:\n" +
            "      #{\n" +
            "        RelationalDatabaseConnection\n" +
            "        {\n" +
            "          store: my::PersonDB;\n" +
            "          type: H2;\n" +
            "          specification: LocalH2\n" +
            "          {\n" +
            "            testDataSetupSqls:\n" +
            "            [\n" +
            "              'DROP TABLE IF EXISTS personTable;',\n" +
            "              'CREATE TABLE personTable(name VARCHAR(200) PRIMARY KEY, age INTEGER);',\n" +
            "              'INSERT INTO personTable VALUES (\\'Alice\\', 30);',\n" +
            "              'INSERT INTO personTable VALUES (\\'Bob\\', 25);'\n" +
            "            ];\n" +
            "          };\n" +
            "          auth: DefaultH2{};\n" +
            "        }\n" +
            "      }#\n" +
            "    ]\n" +
            "  ];\n" +
            "}");

        List<ContentBlock> compileResult =
            executor.value(compileTool, compileArgs,
                Identity.getAnonymousIdentity());
        String compileText =
            ((TextContent) compileResult.get(0)).getText();
        Assert.assertTrue(
            "Compile should succeed: " + compileText,
            compileText.startsWith("Compilation successful."));

        // Execute the query against H2
        Map<String, Object> executeArgs = new HashMap<>();
        executeArgs.put("lambda", "|my::Person.all()");
        executeArgs.put("mapping", "my::PersonMapping");
        executeArgs.put("runtime", "my::PersonRuntime");

        List<ContentBlock> executeResult =
            executor.value(executeTool, executeArgs,
                Identity.getAnonymousIdentity());
        String executeText =
            ((TextContent) executeResult.get(0)).getText();

        System.err.println("EXECUTE RESULT: " + executeText);

        Assert.assertFalse(
            "Should not be an error: " + executeText,
            executeText.startsWith("Execution error"));
        Assert.assertTrue(
            "Should contain Alice: " + executeText,
            executeText.contains("Alice"));
        Assert.assertTrue(
            "Should contain Bob: " + executeText,
            executeText.contains("Bob"));
    }
}
