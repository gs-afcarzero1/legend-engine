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
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
// implied. See the License for the specific language governing
// permissions and limitations under the License.

package org.finos.legend.engine.mcp.engine;

import org.junit.Assert;
import org.junit.Test;

public class JsonRpcProtocolTest
{
    @Test
    public void testEscapeJson()
    {
        Assert.assertEquals("Normal string", "hello", JsonRpcProtocol.escapeJson("hello"));
        Assert.assertEquals("String with quotes", "hello \\\"world\\\"", JsonRpcProtocol.escapeJson("hello \"world\""));
        Assert.assertEquals("String with newlines", "hello\\nworld", JsonRpcProtocol.escapeJson("hello\nworld"));

        // Maven ANSI Escape codes (e.g. \u001B[1m)
        String mavenAnsi = "\u001B[1mBuilding project\u001B[m";
        String escapedAnsi = JsonRpcProtocol.escapeJson(mavenAnsi);
        Assert.assertEquals("ANSI Escaped", "\\u001b[1mBuilding project\\u001b[m", escapedAnsi);

        // Null
        Assert.assertEquals("Null string", "", JsonRpcProtocol.escapeJson(null));

        // Tabs and carriage returns
        Assert.assertEquals("Tabs and CR", "a\\tb\\rc", JsonRpcProtocol.escapeJson("a\tb\rc"));
    }

    @Test
    public void testExtractStringField()
    {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"123-abc\",\"method\":\"tools/call\",\"params\":{\"name\":\"rebuild\",\"arguments\":{\"module\":\"my-module\"}}}";

        Assert.assertEquals("extracting id", "123-abc", JsonRpcProtocol.extractId(json));
        Assert.assertEquals("extracting name", "rebuild", JsonRpcProtocol.extractToolName(json));
        Assert.assertEquals("extracting arg module", "my-module", JsonRpcProtocol.extractArg(json, "module"));
    }

    @Test
    public void testExtractNumericId()
    {
        String jsonNumericId = "{\"jsonrpc\":\"2.0\",\"id\": 12345 ,\"method\":\"tools/call\",\"params\":{\"name\":\"reload\"}}";
        Assert.assertEquals("extracting numeric id", "12345", JsonRpcProtocol.extractId(jsonNumericId));
    }

    @Test
    public void testJsonRpcResult()
    {
        String resultNumeric = JsonRpcProtocol.jsonRpcResult("123", "success");
        Assert.assertTrue("Numeric ID should not be quoted", resultNumeric.contains("\"id\":123,"));
        Assert.assertTrue("Text should be embedded correctly", resultNumeric.contains("\"text\":\"success\""));

        String resultString = JsonRpcProtocol.jsonRpcResult("abc-123", "success");
        Assert.assertTrue("String ID should be quoted", resultString.contains("\"id\":\"abc-123\","));

        String resultAnsi = JsonRpcProtocol.jsonRpcResult("123", "\u001B[31mError!\u001B[0m");
        Assert.assertTrue("ANSI controls should be unicode escaped", resultAnsi.contains("\"text\":\"\\u001b[31mError!\\u001b[0m\""));
        Assert.assertFalse("Should not contain raw ESC char", resultAnsi.contains("\u001B"));

        String resultNullId = JsonRpcProtocol.jsonRpcResult(null, "success");
        Assert.assertTrue("Null ID should be handled", resultNullId.contains("\"id\":null,"));
    }

    @Test
    public void testExtractArgMissingReturnsNull()
    {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"tools/call\",\"params\":{\"name\":\"rebuild\",\"arguments\":{}}}";
        Assert.assertNull("missing arg should return null", JsonRpcProtocol.extractArg(json, "nonexistent"));
    }

    @Test
    public void testJsonRpcErrorProducesValidResponse()
    {
        String error = JsonRpcProtocol.jsonRpcError("42", "something went wrong");
        Assert.assertTrue("should contain jsonrpc version", error.contains("\"jsonrpc\":\"2.0\""));
        Assert.assertTrue("should contain numeric id", error.contains("\"id\":42,"));
        Assert.assertTrue("should contain error code", error.contains("\"code\":-32603"));
        Assert.assertTrue("should contain error message", error.contains("\"message\":\"something went wrong\""));
    }

    @Test
    public void testJsonRpcErrorWithStringId()
    {
        String error = JsonRpcProtocol.jsonRpcError("abc-def", "fail");
        Assert.assertTrue("should contain quoted string id", error.contains("\"id\":\"abc-def\","));
    }

    @Test
    public void testFormatJsonRpcIdNull()
    {
        Assert.assertEquals("null id should return literal null", "null", JsonRpcProtocol.formatJsonRpcId(null));
    }

    @Test
    public void testFormatJsonRpcIdNumeric()
    {
        Assert.assertEquals("numeric id should be unquoted", "123", JsonRpcProtocol.formatJsonRpcId("123"));
    }

    @Test
    public void testFormatJsonRpcIdString()
    {
        Assert.assertEquals("string id should be quoted", "\"abc\"", JsonRpcProtocol.formatJsonRpcId("abc"));
    }

    @Test
    public void testExtractToolNameNonToolsCallReturnsNull()
    {
        String json = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"initialize\",\"params\":{}}";
        Assert.assertNull("non tools/call method should return null", JsonRpcProtocol.extractToolName(json));
    }
}
