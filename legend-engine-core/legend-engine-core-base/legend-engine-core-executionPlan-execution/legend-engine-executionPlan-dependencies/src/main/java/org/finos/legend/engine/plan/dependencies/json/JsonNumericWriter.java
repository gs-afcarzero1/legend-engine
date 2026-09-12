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

import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.math.BigDecimal;

/** Numeric output policy for graph serialization and JSON externalization. */
public final class JsonNumericWriter
{
    private JsonNumericWriter()
    {
    }

    public static void writeDecimal(JsonGenerator generator, BigDecimal value) throws IOException
    {
        // Jackson's plain BigDecimal writer accepts scales only in [-9999, 9999].
        // Temporarily allow its canonical exponent form outside that range. Keep the
        // typed writeNumber call: a string/raw token would lose type in TokenBuffer.
        boolean fallback = value != null && (value.scale() < -9999 || value.scale() > 9999)
                && generator.isEnabled(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        if (fallback)
        {
            generator.disable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
        }
        try
        {
            generator.writeNumber(value);
        }
        finally
        {
            if (fallback)
            {
                generator.enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);
            }
        }
    }

    public static void writeFloat(JsonGenerator generator, double value) throws IOException
    {
        if (!Double.isFinite(value))
        {
            throw new IllegalArgumentException("Cannot serialize a nonfinite Float as a JSON number: " + value);
        }
        generator.writeNumber(value);
    }
}
