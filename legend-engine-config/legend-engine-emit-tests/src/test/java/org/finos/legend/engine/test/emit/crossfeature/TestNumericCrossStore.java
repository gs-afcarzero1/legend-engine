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

package org.finos.legend.engine.test.emit.crossfeature;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.grammar.from.PureGrammarParser;
import org.finos.legend.engine.plan.execution.PlanExecutor;
import org.finos.legend.engine.plan.execution.result.StreamingResult;
import org.finos.legend.engine.plan.execution.result.serialization.SerializationFormat;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.SingleExecutionPlan;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.connection.PackageableConnection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.service.Service;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.DatabaseType;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.RelationalDatabaseConnection;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.authentication.TestDatabaseAuthenticationStrategy;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.specification.DuckDBDatasourceSpecification;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.specification.LocalH2DatasourceSpecification;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.store.relational.connection.specification.SnowflakeDatasourceSpecification;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.finos.legend.engine.test.emit.EMITTasks;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class TestNumericCrossStore
{
    private static final List<String> SETUP = Arrays.asList(
            "DROP TABLE IF EXISTS NumericRow",
            "CREATE TABLE NumericRow(id INTEGER PRIMARY KEY, i BIGINT, f DOUBLE, d DECIMAL(30,18), optional DECIMAL(30,18), label VARCHAR(100))",
            "INSERT INTO NumericRow VALUES (1,9007199254740993,0.00125,1.234567890123456789,NULL,'ordinary'),(2,-9007199254740993,-25.0,-2.500000000000000001,0,'negative')");

    @Test
    public void h2ExecutesGraphExternalizationAndTabular() throws Exception
    {
        check(DatabaseType.H2, true);
    }

    @Test
    public void duckDBExecutesGraphExternalizationAndTabular() throws Exception
    {
        check(DatabaseType.DuckDB, true);
    }

    @Test
    public void snowflakePlansUseSnowflakeConnectionAndSql() throws Exception
    {
        check(DatabaseType.Snowflake, false);
    }

    private void check(DatabaseType database, boolean execute) throws Exception
    {
        String grammar;
        try (InputStream input = getClass().getResourceAsStream("/emit-models/scientific-relational-controls/model.pure"))
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1; )
            {
                bytes.write(buffer, 0, read);
            }
            grammar = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
        PureModelContextData data = PureGrammarParser.newInstance().parseModel(grammar);
        RelationalDatabaseConnection connection = (RelationalDatabaseConnection) data.getElementsOfType(PackageableConnection.class).get(0).connectionValue;
        connection.type = database;
        connection.databaseType = database;
        connection.authenticationStrategy = new TestDatabaseAuthenticationStrategy();
        if (database == DatabaseType.H2)
        {
            LocalH2DatasourceSpecification specification = new LocalH2DatasourceSpecification();
            specification.testDataSetupSqls = SETUP;
            connection.datasourceSpecification = specification;
        }
        else if (database == DatabaseType.DuckDB)
        {
            DuckDBDatasourceSpecification specification = new DuckDBDatasourceSpecification();
            specification.path = "";
            specification.testDataSetupSqls = SETUP;
            connection.datasourceSpecification = specification;
        }
        else
        {
            SnowflakeDatasourceSpecification specification = new SnowflakeDatasourceSpecification();
            specification.accountName = "plan_validation";
            specification.region = "eu-west-1";
            specification.warehouseName = "NUMERIC";
            specification.databaseName = "NUMERIC";
            specification.cloudType = "aws";
            connection.datasourceSpecification = specification;
        }
        PureModel model = EMITTasks.compile(data);
        Path output = Paths.get("target", "numeric-cross-store", database.name());
        Files.createDirectories(output);
        for (Service service : data.getElementsOfType(Service.class))
        {
            SingleExecutionPlan plan = (SingleExecutionPlan) EMITTasks.runPlan(service, model);
            JsonNode planJson = ObjectMapperFactory.getNewStandardObjectMapper().valueToTree(plan);
            String planText = planJson.toString();
            Files.write(output.resolve(service.name + ".plan.json"), planText.getBytes(StandardCharsets.UTF_8));
            Assertions.assertTrue(planText.contains(database.name()), "Plan must retain the requested database");
            if (database != DatabaseType.H2)
            {
                Assertions.assertFalse(planText.contains("localH2"), "A substituted H2 connection is not database coverage");
            }
            List<JsonNode> queries = planJson.findValues("sqlQuery");
            Assertions.assertFalse(queries.isEmpty(), "Plan must contain executable SQL");
            String sql = queries.toString();
            if (database == DatabaseType.Snowflake)
            {
                Assertions.assertTrue(sql.contains("ALTER SESSION SET QUERY_TAG"), "Snowflake plans must use Snowflake session SQL");
            }
            if (service.name.equals("Operations"))
            {
                String expectedCast = database == DatabaseType.Snowflake ? "to_double(" : "cast(";
                Assertions.assertTrue(sql.toLowerCase().contains(expectedCast), "Operations SQL must retain its dialect-specific explicit numeric cast");
                Assertions.assertTrue(sql.contains("${threshold}"), "Operations SQL must retain the comparison parameter binding");
                Assertions.assertTrue(planText.contains("\"fullPath\":\"Decimal\""), "Operations plan must retain the Decimal parameter type");
            }
            if (service.name.equals("Aggregate"))
            {
                Assertions.assertTrue(sql.toLowerCase().contains("sum("), "Aggregate SQL must retain Decimal aggregation");
                Assertions.assertTrue(sql.contains("${threshold}"), "Aggregate SQL must retain the comparison parameter binding");
                Assertions.assertTrue(planText.contains("\"fullPath\":\"Decimal\""), "Aggregate plan must retain the Decimal parameter type");
            }
            if (execute)
            {
                Map<String, Object> parameters = service.name.equals("Operations") || service.name.equals("Aggregate")
                        ? Collections.singletonMap("threshold", new BigDecimal("1.0"))
                        : Collections.emptyMap();
                try (StreamingResult result = (StreamingResult) PlanExecutor.newPlanExecutorBuilder().withAvailableStoreExecutors().build()
                        .executeWithArgs(PlanExecutor.ExecuteArgs.newArgs().withPlan(plan).withParams(parameters).build()))
                {
                    SerializationFormat format = service.name.equals("Tabular") || service.name.equals("Operations") || service.name.equals("Aggregate") ? SerializationFormat.PURE_TDSOBJECT : SerializationFormat.DEFAULT;
                    String json = result.flush(result.getSerializer(format));
                    Files.write(output.resolve(service.name + ".result.json"), json.getBytes(StandardCharsets.UTF_8));
                    assertExactNumbers(service.name, json);
                }
            }
        }
    }

    private void assertExactNumbers(String service, String json) throws Exception
    {
        if (service.equals("Aggregate"))
        {
            try (JsonParser parser = new JsonFactory().createParser(json))
            {
                while (parser.nextToken() != null && !parser.currentToken().isNumeric())
                {
                    // Advance to the tabular aggregate value; column names differ by dialect.
                }
                Assertions.assertTrue(parser.currentToken().isNumeric());
                Assertions.assertEquals(0, parser.getDecimalValue().compareTo(new BigDecimal("1.234567890123456789")));
            }
            return;
        }
        if (service.equals("Operations"))
        {
            int checked = 0;
            try (JsonParser parser = new JsonFactory().createParser(json))
            {
                while (parser.nextToken() != null)
                {
                    if (parser.currentToken() == JsonToken.FIELD_NAME)
                    {
                        String name = parser.currentName();
                        parser.nextToken();
                        if (name.equals("integerArithmetic"))
                        {
                            Assertions.assertEquals(9007199254741000L, parser.getLongValue());
                            checked++;
                        }
                        else if (name.equals("floatArithmetic"))
                        {
                            Assertions.assertEquals(0.0025d, parser.getDoubleValue());
                            checked++;
                        }
                        else if (name.equals("decimalArithmetic"))
                        {
                            Assertions.assertEquals(0, parser.getDecimalValue().compareTo(new BigDecimal("2.234567890123456789")));
                            checked++;
                        }
                        else if (name.equals("explicitCast"))
                        {
                            Assertions.assertEquals(9007199254740992d, parser.getDoubleValue());
                            checked++;
                        }
                    }
                }
            }
            Assertions.assertEquals(4, checked);
            return;
        }
        int decimals = 0;
        int integers = 0;
        try (JsonParser parser = new JsonFactory().createParser(json))
        {
            while (parser.nextToken() != null)
            {
                if (parser.currentToken() == JsonToken.FIELD_NAME)
                {
                    String name = parser.currentName();
                    parser.nextToken();
                    if (name.equals("d"))
                    {
                        BigDecimal value = parser.getDecimalValue();
                        Assertions.assertTrue(value.compareTo(new BigDecimal("1.234567890123456789")) == 0 || value.compareTo(new BigDecimal("-2.500000000000000001")) == 0);
                        decimals++;
                    }
                    if (name.equals("i"))
                    {
                        long value = parser.getLongValue();
                        Assertions.assertTrue(value == 9007199254740993L || value == -9007199254740993L);
                        integers++;
                    }
                }
            }
        }
        Assertions.assertEquals(2, decimals);
        Assertions.assertEquals(2, integers);
    }
}
