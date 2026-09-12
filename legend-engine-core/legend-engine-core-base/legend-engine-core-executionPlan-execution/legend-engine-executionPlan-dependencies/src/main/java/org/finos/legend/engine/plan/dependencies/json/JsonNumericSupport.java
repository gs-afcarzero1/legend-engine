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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.util.JsonParserDelegate;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;

/** Numeric conversions shared by JSON internalization and generated model readers. */
public final class JsonNumericSupport
{
    private JsonNumericSupport()
    {
    }

    /**
     * Report decimal tokens before Jackson's tree reader requests a double. Configuring
     * USE_BIG_DECIMAL_FOR_FLOATS alone still allows out-of-range exponents to become Infinity.
     * Wrap the outermost parser so this also applies when JSON pointer filtering is used.
     */
    public static JsonParser exactParser(JsonParser parser)
    {
        return new JsonParserDelegate(parser)
        {
            @Override
            public NumberType getNumberType() throws IOException
            {
                if (currentToken() == JsonToken.VALUE_NUMBER_FLOAT)
                {
                    // BigDecimal has no signed zero. Preserve the existing Float sign
                    // for exact negative zero, without allowing nonzero underflow.
                    if (isNegativeZero())
                    {
                        return NumberType.DOUBLE;
                    }
                    return NumberType.BIG_DECIMAL;
                }
                return super.getNumberType();
            }

            @Override
            public Number getNumberValue() throws IOException
            {
                if (currentToken() == JsonToken.VALUE_NUMBER_FLOAT)
                {
                    return isNegativeZero() ? Double.valueOf(-0.0d) : getDecimalValue();
                }
                return super.getNumberValue();
            }

            @Override
            public BigDecimal getDecimalValue() throws IOException
            {
                return new BigDecimal(getText());
            }

            private boolean isNegativeZero() throws IOException
            {
                return getText().startsWith("-") && getDecimalValue().signum() == 0;
            }
        };
    }

    public static long integerValue(JsonNode node)
    {
        try
        {
            return node.decimalValue().longValueExact();
        }
        catch (ArithmeticException e)
        {
            // IllegalArgumentException is translated to the existing checked-data defect by both readers.
            throw new IllegalArgumentException("Expected an exactly integral signed 64-bit Integer: " + node, e);
        }
    }

    public static double floatValue(JsonNode node)
    {
        double value = node.doubleValue();
        if (!Double.isFinite(value) || (value == 0.0 && node.decimalValue().signum() != 0))
        {
            throw new IllegalArgumentException("Value outside the finite nonzero Float range: " + node);
        }
        return value;
    }

    public static BigDecimal decimalValue(JsonNode node)
    {
        return node.isTextual() ? new BigDecimal(node.textValue()) : node.decimalValue();
    }

    public static Number numberValue(JsonNode node)
    {
        if (node.isIntegralNumber() && node.canConvertToLong())
        {
            return Long.valueOf(node.longValue());
        }
        return decimalValue(node);
    }
}
