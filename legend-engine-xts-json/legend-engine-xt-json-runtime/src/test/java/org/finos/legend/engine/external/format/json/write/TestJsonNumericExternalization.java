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

package org.finos.legend.engine.external.format.json.write;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import org.finos.legend.engine.plan.dependencies.store.shared.IExecutionNodeContext;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

public class TestJsonNumericExternalization
{
    private JsonDataWriter<Number> writer(Number value)
    {
        IJsonExternalizeExecutionNodeSpecifics specifics = new IJsonExternalizeExecutionNodeSpecifics()
        {
            public <T> IJsonSerializer<T> createSerializer(IJsonWriter writer, IExecutionNodeContext context)
            {
                return item ->
                {
                    writer.startObject("Numeric");
                    writer.writeNumberProperty("value", (Number) item);
                    writer.writeDecimalProperty("optional", (BigDecimal) null);
                    writer.writeStringProperty("label", "ordinary control");
                    writer.endObject();
                };
            }
        };
        return new JsonDataWriter<>(specifics, Stream.of(value), null);
    }

    @Test
    public void externalizationPreservesExtremeDecimals() throws Exception
    {
        for (String token : new String[]{"1.2300", "1e400", "1e-400", "1e10000", "1e-10000"})
        {
            BigDecimal value = new BigDecimal(token);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            writer(value).writeData(output);
            try (JsonParser parser = new JsonFactory().createParser(output.toByteArray()))
            {
                parser.nextToken();
                Assert.assertEquals("value", parser.nextFieldName());
                Assert.assertTrue(parser.nextToken().isNumeric());
                Assert.assertEquals(0, value.compareTo(parser.getDecimalValue()));
            }
            Assert.assertTrue(output.toString(StandardCharsets.UTF_8.name()).contains("\"optional\":null"));
        }
    }

    @Test
    public void externalizationRejectsNonfiniteFloat()
    {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        {
            Assert.assertThrows(IllegalArgumentException.class, () -> writer(value).writeData(new ByteArrayOutputStream()));
        }
    }
}
