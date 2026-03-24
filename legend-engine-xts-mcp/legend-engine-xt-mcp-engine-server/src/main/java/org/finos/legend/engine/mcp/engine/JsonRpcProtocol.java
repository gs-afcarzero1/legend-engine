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

/**
 * Stateless utility for hand-rolled JSON-RPC 2.0 message parsing and formatting.
 *
 * <p>This class is intentionally hand-rolled rather than using Jackson or any MCP protocol library.
 * The bootstrap server must have zero external dependencies because those libraries live in the
 * child classloader; the bootstrap must remain functional even when Maven dependencies are broken
 * or when the child classpath has not yet been generated.
 *
 * <p>All methods are static and the class cannot be instantiated.
 */
final class JsonRpcProtocol
{
    private JsonRpcProtocol()
    {
    }

    /**
     * Extracts the tool name from a {@code tools/call} JSON-RPC request.
     *
     * @param json the raw JSON-RPC message
     * @return the tool name, or {@code null} if this is not a {@code tools/call} request
     */
    static String extractToolName(String json)
    {
        if (!json.contains("\"tools/call\""))
        {
            return null;
        }
        return extractStringField(json, "name");
    }

    /**
     * Extracts the JSON-RPC {@code "id"} field from a message.
     *
     * @param json the raw JSON-RPC message
     * @return the request id as a string, or {@code null} if absent
     */
    static String extractId(String json)
    {
        return extractStringField(json, "id");
    }

    /**
     * Extracts a named argument value from the JSON-RPC message body.
     *
     * @param json    the raw JSON-RPC message
     * @param argName the argument field name to look up
     * @return the argument value as a string, or {@code null} if not found
     */
    static String extractArg(String json, String argName)
    {
        return extractStringField(json, argName);
    }

    /**
     * Low-level extractor that finds the first occurrence of a named JSON field and returns its
     * string or numeric value. Handles basic JSON escape sequences but does not support nested
     * objects or arrays as values.
     *
     * @param json      the raw JSON string to search
     * @param fieldName the JSON field name (without quotes)
     * @return the field value, or {@code null} if not found
     */
    private static String extractStringField(String json, String fieldName)
    {
        String pattern = "\"" + fieldName + "\"";
        int index = json.indexOf(pattern);
        if (index < 0)
        {
            return null;
        }
        index += pattern.length();
        while (index < json.length()
                && (json.charAt(index) == ':'
                        || json.charAt(index) == ' '
                        || json.charAt(index) == '\t'))
        {
            index++;
        }
        if (index >= json.length())
        {
            return null;
        }
        if (json.charAt(index) != '"')
        {
            int start = index;
            while (index < json.length() && Character.isDigit(json.charAt(index)))
            {
                index++;
            }
            return json.substring(start, index);
        }

        index++;
        StringBuilder text = new StringBuilder();
        while (index < json.length() && json.charAt(index) != '"')
        {
            if (json.charAt(index) == '\\')
            {
                index++;
                if (index < json.length())
                {
                    text.append(json.charAt(index));
                }
            }
            else
            {
                text.append(json.charAt(index));
            }
            index++;
        }
        return text.toString();
    }

    /**
     * Escapes a string for safe embedding inside a JSON string literal, handling backslashes,
     * quotes, control characters, and Unicode line/paragraph separators.
     *
     * @param text the raw text to escape (may be {@code null})
     * @return the escaped text, or an empty string if input is {@code null}
     */
    static String escapeJson(String text)
    {
        if (text == null)
        {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            switch (c)
            {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < '\u0020'
                            || (c >= '\u0080' && c < '\u00a0')
                            || c == '\u2028'
                            || c == '\u2029')
                    {
                        String hex = "000" + Integer.toHexString(c);
                        escaped.append("\\u").append(hex.substring(hex.length() - 4));
                    }
                    else
                    {
                        escaped.append(c);
                    }
            }
        }
        return escaped.toString();
    }

    /**
     * Builds a successful JSON-RPC 2.0 response with a single text content block.
     *
     * @param requestId the original request id (numeric or string)
     * @param text      the text content to return in the result
     * @return a complete JSON-RPC response string
     */
    static String jsonRpcResult(String requestId, String text)
    {
        return "{\"jsonrpc\":\"2.0\","
                + "\"id\":"
                + formatJsonRpcId(requestId)
                + ","
                + "\"result\":{\"content\":[{"
                + "\"text\":\""
                + escapeJson(text)
                + "\","
                + "\"type\":\"text\"}],"
                + "\"isError\":false}}";
    }

    /**
     * Builds a JSON-RPC 2.0 error response with code {@code -32603} (internal error).
     *
     * @param requestId the original request id (numeric or string)
     * @param message   the error message
     * @return a complete JSON-RPC error response string
     */
    static String jsonRpcError(String requestId, String message)
    {
        return "{\"jsonrpc\":\"2.0\","
                + "\"id\":"
                + formatJsonRpcId(requestId)
                + ","
                + "\"error\":{\"code\":-32603,"
                + "\"message\":\""
                + escapeJson(message)
                + "\"}}";
    }

    /**
     * Formats a request id for inclusion in a JSON-RPC response. Numeric ids are emitted bare;
     * string ids are quoted and escaped; {@code null} becomes the JSON literal {@code null}.
     *
     * @param requestId the request id to format
     * @return the formatted id suitable for embedding in a JSON object
     */
    static String formatJsonRpcId(String requestId)
    {
        if (requestId == null)
        {
            return "null";
        }

        for (int i = 0; i < requestId.length(); i++)
        {
            if (!Character.isDigit(requestId.charAt(i)))
            {
                return "\"" + escapeJson(requestId) + "\"";
            }
        }
        return requestId;
    }
}
