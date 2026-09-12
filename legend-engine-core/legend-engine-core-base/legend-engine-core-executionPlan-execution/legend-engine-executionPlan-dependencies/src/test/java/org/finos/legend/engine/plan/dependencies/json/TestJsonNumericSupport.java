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
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.filter.FilteringParserDelegate;
import com.fasterxml.jackson.core.filter.JsonPointerBasedFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.math.BigDecimal;

public class TestJsonNumericSupport
{
    private JsonNode read(String json) throws IOException
    {
        try (JsonParser parser = JsonNumericSupport.exactParser(new JsonFactory().createParser(json)))
        {
            return new ObjectMapper().readTree(parser);
        }
    }

    @Test
    public void preservesDecimalTokensBeforeDoubleConversion() throws Exception
    {
        for (String token : new String[]{"1.25e-3", "-1.25E+3", "0e-400", "1e400", "1e-400", "1e10000", "1e-10000", "1.23456789012345678901234567890123456789"})
        {
            JsonNode node = read(token);
            Assert.assertTrue(node.isBigDecimal());
            Assert.assertEquals(token, 0, new BigDecimal(token).compareTo(JsonNumericSupport.decimalValue(node)));
            Assert.assertTrue(JsonNumericSupport.numberValue(node) instanceof BigDecimal);
        }
    }

    @Test
    public void numberPreservesIntegralTokenTypeAndPrecision() throws Exception
    {
        for (String token : new String[]{"0", "9007199254740991", "9007199254740992", "9007199254740993", "9223372036854775807", "-9223372036854775808"})
        {
            Number value = JsonNumericSupport.numberValue(read(token));
            Assert.assertEquals(Long.class, value.getClass());
            Assert.assertEquals(Long.valueOf(token), value);
        }
        for (String token : new String[]{"9223372036854775808", "-9223372036854775809", "1.0", "1e0", "\"1.25e-3\""})
        {
            Assert.assertEquals(BigDecimal.class, JsonNumericSupport.numberValue(read(token)).getClass());
        }
    }

    @Test
    public void integerConversionIsExactAndBounded() throws Exception
    {
        Assert.assertEquals(1000L, JsonNumericSupport.integerValue(read("1e3")));
        Assert.assertEquals(Long.MAX_VALUE, JsonNumericSupport.integerValue(read("9223372036854775807.0")));
        Assert.assertEquals(Long.MIN_VALUE, JsonNumericSupport.integerValue(read("-9223372036854775808.0")));
        for (String token : new String[]{"1.1", "1e-400", "9223372036854775808", "-9223372036854775809", "1e400"})
        {
            JsonNode node = read(token);
            Assert.assertThrows(token, IllegalArgumentException.class, () -> JsonNumericSupport.integerValue(node));
        }
    }

    @Test
    public void floatKeepsRoundingButRejectsOverflowAndUnderflow() throws Exception
    {
        for (String token : new String[]{"-0.0", "-0e-400", "-0E+400"})
        {
            Assert.assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(JsonNumericSupport.floatValue(read(token))));
        }
        for (String token : new String[]{"1.25e-3", "-1.25E+3", "0e-10000", "1.234567890123456789", "4.9e-324"})
        {
            Assert.assertEquals(Double.parseDouble(token), JsonNumericSupport.floatValue(read(token)), 0.0);
        }
        for (String token : new String[]{"1e400", "-1e400", "1e-400", "-1e-400"})
        {
            JsonNode node = read(token);
            Assert.assertThrows(token, IllegalArgumentException.class, () -> JsonNumericSupport.floatValue(node));
        }
    }

    @Test
    public void filteredNestedRepeatedAndNullValues() throws Exception
    {
        String json = "{\"ignored\":0,\"payload\":{\"values\":[1e400,1.25e-3,null],\"empty\":[]}}";
        try (JsonParser parser = JsonNumericSupport.exactParser(new FilteringParserDelegate(new JsonFactory().createParser(json), new JsonPointerBasedFilter("/payload"), false, false)))
        {
            JsonNode node = new ObjectMapper().readTree(parser);
            Assert.assertEquals(0, new BigDecimal("1e400").compareTo(node.get("values").get(0).decimalValue()));
            Assert.assertEquals(new BigDecimal("0.00125"), node.get("values").get(1).decimalValue());
            Assert.assertTrue(node.get("values").get(2).isNull());
            Assert.assertEquals(0, node.get("empty").size());
        }
    }

    @Test
    public void malformedExponentsFail() throws Exception
    {
        for (String json : new String[]{"[1e]", "[1e+]", "[1e-]", "[1E--2]"})
        {
            Assert.assertThrows(IOException.class, () -> read(json));
        }
    }
}
