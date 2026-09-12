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

package org.finos.legend.engine.external.format.json.read;

import com.fasterxml.jackson.databind.JsonNode;
import org.finos.legend.engine.plan.dependencies.domain.dataQuality.IChecked;
import org.finos.legend.engine.plan.dependencies.domain.dataQuality.IDefect;
import org.finos.legend.engine.plan.dependencies.store.inMemory.DataParsingException;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class TestJsonNumericReader
{
    private static class Reader extends JsonDataReader<Object>
    {
        private final Function<Reader, Function<JsonNode, Object>> conversion;
        private final List<String> defects = new ArrayList<>();

        Reader(String data, boolean decimalProperty, String path, Function<Reader, Function<JsonNode, Object>> conversion)
        {
            super(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)), decimalProperty, path);
            this.conversion = conversion;
        }

        @Override
        protected IChecked<Object> readCheckedObject(JsonNode node, JsonDataRecord source)
        {
            Object value = acceptMany(node.get("value"), conversion.apply(this), defects::add);
            return new IChecked<Object>()
            {
                public List<IDefect> getDefects()
                {
                    return Collections.emptyList();
                }

                public Object getSource()
                {
                    return source;
                }

                public Object getValue()
                {
                    return value;
                }
            };
        }

        List<Object> values()
        {
            return startStream().map(IChecked::getValue).collect(Collectors.toList());
        }
    }

    @Test
    public void numberIsIndependentOfDecimalPropertyAndSupportsFilteredPaths()
    {
        for (boolean decimal : new boolean[]{false, true})
        {
            Reader reader = new Reader("{\"payload\":[{\"value\":[1.25e-3,9007199254740993,1e400,\"-2E+2\"]},{\"value\":null},{\"value\":[]}]}", decimal, "/payload", r -> r::acceptNumber);
            List<Object> values = reader.values();
            Assert.assertEquals(java.util.Arrays.asList(new BigDecimal("0.00125"), 9007199254740993L, new BigDecimal("1e400"), new BigDecimal("-2E+2")), values.get(0));
            Assert.assertEquals(Collections.emptyList(), values.get(1));
            Assert.assertEquals(Collections.emptyList(), values.get(2));
            Assert.assertTrue(reader.defects.isEmpty());
        }
    }

    @Test
    public void invalidIntegerAndFloatBecomeCheckedDefects()
    {
        Reader integers = new Reader("{\"value\":[1e3,1.2,9223372036854775808,\"12\"]}", false, null, r -> r::acceptInteger);
        Assert.assertEquals(Collections.singletonList(Collections.singletonList(1000L)), integers.values());
        Assert.assertEquals(3, integers.defects.size());
        Reader floats = new Reader("{\"value\":[1.25e-3,1e400,1e-400,\"1.2\"]}", true, null, r -> r::acceptFloat);
        Assert.assertEquals(Collections.singletonList(Collections.singletonList(0.00125)), floats.values());
        Assert.assertEquals(3, floats.defects.size());
    }

    @Test
    public void decimalRetainsExtremeExponentsAndStringSupport()
    {
        Reader reader = new Reader("{\"value\":[1e10000,1e-10000,\"1.2345678901234567890123456789\"]}", false, null, r -> r::acceptDecimal);
        Assert.assertEquals(Collections.singletonList(java.util.Arrays.asList(new BigDecimal("1e10000"), new BigDecimal("1e-10000"), new BigDecimal("1.2345678901234567890123456789"))), reader.values());
        Assert.assertTrue(reader.defects.isEmpty());
    }

    @Test
    public void numericStringsRemainInvalidForIntegerAndFloat()
    {
        Reader reader = new Reader("{}", false, null, r -> r::acceptNumber);
        JsonNode text = new com.fasterxml.jackson.databind.node.TextNode("1e2");
        Assert.assertThrows(DataParsingException.class, () -> reader.acceptInteger(text));
        Assert.assertThrows(DataParsingException.class, () -> reader.acceptFloat(text));
    }

    @Test
    public void malformedExponentFailsParsing()
    {
        Reader reader = new Reader("{\"value\":1e+}", false, null, r -> r::acceptNumber);
        Assert.assertThrows(UncheckedIOException.class, reader::values);
    }
}
