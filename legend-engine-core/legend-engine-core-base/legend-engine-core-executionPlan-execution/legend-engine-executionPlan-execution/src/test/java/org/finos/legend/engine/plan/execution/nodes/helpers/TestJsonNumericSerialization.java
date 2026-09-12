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

package org.finos.legend.engine.plan.execution.nodes.helpers;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import org.finos.legend.engine.plan.dependencies.store.platform.IGraphSerializer;
import org.finos.legend.engine.plan.dependencies.store.platform.IPlatformPureExpressionExecutionNodeSerializeSpecifics;
import org.finos.legend.engine.plan.execution.result.ConstantResult;
import org.finos.legend.engine.plan.execution.result.json.JsonStreamingResult;
import org.finos.legend.engine.plan.execution.result.json.JsonStreamToPureFormatSerializer;
import org.junit.Assert;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.Collections;

public class TestJsonNumericSerialization
{
    private JsonStreamingResult result(Number number)
    {
        IPlatformPureExpressionExecutionNodeSerializeSpecifics specifics = (writer, context) -> (IGraphSerializer<Number>) value ->
        {
            writer.startObject("Numeric");
            writer.writeNumberProperty("value", value);
            writer.writeDecimalProperty("optional", (BigDecimal) null);
            writer.writeIntegerProperty("integers", Collections.singletonList(9007199254740993L));
            writer.writeStringProperty("label", "ordinary control");
            writer.endObject();
        };
        return (JsonStreamingResult) ExecutionNodeSerializerHelper.executeSerialize(specifics, null, new ConstantResult(number), null);
    }

    @Test
    public void graphSerializationPreservesExtremeDecimals() throws Exception
    {
        for (String token : new String[]{"1.2300", "1e400", "1e-400", "1e10000", "1e-10000"})
        {
            BigDecimal value = new BigDecimal(token);
            JsonStreamingResult result = result(value);
            String json = result.flush(new JsonStreamToPureFormatSerializer(result));
            try (JsonParser parser = new JsonFactory().createParser(json))
            {
                parser.nextToken();
                Assert.assertEquals("value", parser.nextFieldName());
                Assert.assertTrue(parser.nextToken().isNumeric());
                Assert.assertEquals(0, value.compareTo(parser.getDecimalValue()));
            }
        }
    }

    @Test
    public void graphTokenBufferRetainsDecimalType() throws Exception
    {
        BigDecimal value = new BigDecimal("1.234567890123456789e10000");
        JsonNode node = result(value).toStream().findFirst().get();
        Assert.assertTrue(node.get("value").isBigDecimal());
        Assert.assertEquals(0, value.compareTo(node.get("value").decimalValue()));
        Assert.assertTrue(node.get("optional").isNull());
        Assert.assertEquals(9007199254740993L, node.get("integers").get(0).longValue());
    }

    @Test
    public void graphRejectsNonfiniteFloat()
    {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        {
            JsonStreamingResult result = result(value);
            Assert.assertThrows(IllegalArgumentException.class, () -> result.flush(new JsonStreamToPureFormatSerializer(result)));
        }
    }
}
