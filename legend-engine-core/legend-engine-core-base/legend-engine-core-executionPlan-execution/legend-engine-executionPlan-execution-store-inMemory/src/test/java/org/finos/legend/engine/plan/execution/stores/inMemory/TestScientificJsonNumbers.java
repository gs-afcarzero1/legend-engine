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

package org.finos.legend.engine.plan.execution.stores.inMemory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import org.finos.legend.engine.plan.execution.PlanExecutor;
import org.finos.legend.engine.plan.execution.result.json.JsonStreamToPureFormatSerializer;
import org.finos.legend.engine.plan.execution.result.json.JsonStreamingResult;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.SingleExecutionPlan;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.finos.legend.engine.plan.execution.stores.inMemory.utils.TestUtils.buildPlanForQuery;

public class TestScientificJsonNumbers
{
    private SingleExecutionPlan plan(boolean decimal)
    {
        String optional = decimal ? " extra: Decimal[0..1];" : "";
        String tree = "#{numeric::Target{n, i, f" + (decimal ? ", extra" : "") + "}}#";
        String grammar = "###Pure\nClass numeric::Source { n: Number[1]; i: Integer[1]; f: Float[1];" + optional + " }\n"
                + "Class numeric::Target { n: Number[1]; i: Integer[1]; f: Float[1];" + optional + " }\n"
                + "function showcase::query(): Any[1] { {|numeric::Target.all()->graphFetch(" + tree + ")->serialize(" + tree + ")}; }\n"
                + "###Mapping\nMapping numeric::Mapping (*numeric::Target: Pure { ~src numeric::Source n: $src.n, i: $src.i, f: $src.f" + (decimal ? ", extra: $src.extra" : "") + " })\n"
                + "###Runtime\nRuntime numeric::Runtime { mappings: [numeric::Mapping]; connections: [ModelStore: [input: #{JsonModelConnection { class: numeric::Source; url: 'executor:default'; }}#]]; }";
        return buildPlanForQuery(grammar, "numeric::Mapping", "numeric::Runtime");
    }

    private String execute(SingleExecutionPlan plan, String json)
    {
        PlanExecutor executor = PlanExecutor.newPlanExecutorBuilder().withAvailableStoreExecutors().build();
        try (JsonStreamingResult result = (JsonStreamingResult) executor.executeWithArgs(PlanExecutor.ExecuteArgs.newArgs()
                .withPlan(plan).withInputAsStream(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))).build()))
        {
            return result.flush(new JsonStreamToPureFormatSerializer(result));
        }
    }

    @Test
    public void generatedReaderPreservesNumbersWithAndWithoutDecimal() throws Exception
    {
        for (boolean decimal : new boolean[]{false, true})
        {
            SingleExecutionPlan plan = plan(decimal);
            for (String number : new String[]{"1.25e-3", "9007199254740993", "9223372036854775808", "1.2345678901234567890123456789", "1e400", "1e-400"})
            {
                String json = execute(plan, "{\"n\":" + number + ",\"i\":9223372036854775807,\"f\":1.25e-3}");
                try (JsonParser parser = new JsonFactory().createParser(json))
                {
                    parser.nextToken();
                    Assert.assertEquals("n", parser.nextFieldName());
                    Assert.assertTrue(parser.nextToken().isNumeric());
                    Assert.assertEquals(number, 0, new BigDecimal(number).compareTo(parser.getDecimalValue()));
                    Assert.assertEquals("i", parser.nextFieldName());
                    parser.nextToken();
                    Assert.assertEquals(Long.MAX_VALUE, parser.getLongValue());
                }
            }
        }
    }

    @Test
    public void generatedIntegerAndFloatErrorsUseCheckedDataDefects()
    {
        SingleExecutionPlan plan = plan(true);
        for (String fields : new String[]{"\"i\":1.25,\"f\":1", "\"i\":9223372036854775808,\"f\":1", "\"i\":1,\"f\":1e400", "\"i\":1,\"f\":1e-400", "\"i\":\"1\",\"f\":1", "\"i\":1,\"f\":\"1\""})
        {
            RuntimeException exception = Assert.assertThrows(RuntimeException.class, () -> execute(plan, "{\"n\":1," + fields + "}"));
            Assert.assertFalse(exception.toString(), exception.toString().contains("JavaCompileException"));
            Assert.assertTrue(exception.toString(), exception.toString().contains("Invalid") || exception.toString().contains("defect") || exception.toString().contains("Unexpected"));
        }
    }
}
