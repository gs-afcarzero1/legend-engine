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

package org.finos.legend.engine.plan.dependencies.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import org.junit.Assert;
import org.junit.Test;

import java.io.StringWriter;
import java.math.BigDecimal;

public class TestJsonNumericWriter
{
    @Test
    public void plainFormattingAndExponentFallbackAreExact() throws Exception
    {
        for (String token : new String[]{"1.2300", "1e-400", "1e400", "1e-9999", "1e9999", "1e-10000", "1e10000", "-1.234567890123456789e10000", "0e-10000"})
        {
            BigDecimal value = new BigDecimal(token);
            StringWriter output = new StringWriter();
            try (JsonGenerator generator = new JsonFactory().createGenerator(output))
            {
                generator.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
                JsonNumericWriter.writeDecimal(generator, value);
                Assert.assertTrue(generator.isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN));
            }
            boolean extreme = value.scale() < -9999 || value.scale() > 9999;
            Assert.assertEquals(extreme ? value.toString() : value.toPlainString(), output.toString());
            // Independent streaming parse: do not compare two trees rounded by the same mapper.
            try (JsonParser parser = new JsonFactory().createParser(output.toString()))
            {
                Assert.assertTrue(parser.nextToken().isNumeric());
                Assert.assertEquals(0, value.compareTo(parser.getDecimalValue()));
                Assert.assertNull(parser.nextToken());
            }
        }
    }

    @Test
    public void tokenBuffersKeepBigDecimalsAndScale() throws Exception
    {
        try (TokenBuffer buffer = new TokenBuffer(null, false))
        {
            buffer.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
            BigDecimal value = new BigDecimal("1.2300e10000");
            JsonNumericWriter.writeDecimal(buffer, value);
            try (JsonParser parser = buffer.asParser())
            {
                Assert.assertEquals(JsonToken.VALUE_NUMBER_FLOAT, parser.nextToken());
                Assert.assertEquals(JsonParser.NumberType.BIG_DECIMAL, parser.getNumberType());
                Assert.assertEquals(value, parser.getNumberValue());
            }
        }
    }

    @Test
    public void rejectsNonfiniteFloatsAndRetainsFiniteOutput() throws Exception
    {
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
        {
            try (JsonGenerator generator = new JsonFactory().createGenerator(new StringWriter()))
            {
                Assert.assertThrows(IllegalArgumentException.class, () -> JsonNumericWriter.writeFloat(generator, value));
            }
        }
        StringWriter output = new StringWriter();
        try (JsonGenerator generator = new JsonFactory().createGenerator(output))
        {
            JsonNumericWriter.writeFloat(generator, 0.00125);
        }
        Assert.assertEquals("0.00125", output.toString());
    }
}
