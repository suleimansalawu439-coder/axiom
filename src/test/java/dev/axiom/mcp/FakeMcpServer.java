package dev.axiom.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal fake MCP server speaking JSON-RPC 2.0 over stdio, for
 * {@link McpClientTest}. Understands {@code initialize}, {@code ping},
 * {@code tools/list}, {@code tools/call} ({@code echo}, {@code boom}),
 * {@code resources/list}, and {@code resources/read}.
 *
 * <p>When it receives {@code notifications/initialized} it issues a
 * server-initiated {@code roots/list} request; if the client's answer is not
 * an empty roots list, it prints {@code ROOTS-FAIL} and exits 42.
 * Pass {@code hang} as argv[0] to never answer (timeout tests).
 */
public final class FakeMcpServer {
    private FakeMcpServer() {}

    public static void main(String[] args) throws Exception {
        boolean hang = args.length > 0 && "hang".equals(args[0]);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        PrintWriter out = new PrintWriter(new OutputStreamWriter(System.out), true);
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            if (hang) continue; // never respond: client must time out
            String method = extractString(line, "method");
            if (method == null) {
                // A response to our server-initiated roots/list request.
                if (!line.contains("\"roots\":[]") && !line.contains("\"roots\": []")) {
                    System.err.println("ROOTS-FAIL: " + line);
                    System.exit(42);
                }
                continue;
            }
            String id = extractId(line);
            switch (method) {
                case "initialize" -> respond(out, id,
                    "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"serverInfo\":{\"name\":\"fake-mcp\",\"version\":\"0.0.1\"}}");
                case "notifications/initialized" ->
                    out.println("{\"jsonrpc\":\"2.0\",\"id\":9001,\"method\":\"roots/list\",\"params\":{}}");
                case "ping" -> respond(out, id, "{}");
                case "tools/list" -> respond(out, id,
                    "{\"tools\":["
                    + "{\"name\":\"echo\",\"description\":\"Echoes the message back\","
                    + "\"inputSchema\":{\"type\":\"object\","
                    + "\"properties\":{\"message\":{\"type\":\"string\",\"description\":\"Message to echo\"}},"
                    + "\"required\":[\"message\"],\"additionalProperties\":false}},"
                    + "{\"name\":\"boom\",\"description\":\"Always fails\","
                    + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}"
                    + "]}");
                case "tools/call" -> {
                    String toolName = extractString(line, "name");
                    if ("echo".equals(toolName)) {
                        String message = extractString(line, "message");
                        respond(out, id, "{\"content\":[{\"type\":\"text\",\"text\":\"ECHO:"
                            + escape(message) + "\"}],\"isError\":false}");
                    } else if ("boom".equals(toolName)) {
                        respond(out, id, "{\"content\":[{\"type\":\"text\",\"text\":\"kaboom\"}],\"isError\":true}");
                    } else {
                        error(out, id, -32602, "Unknown tool: " + toolName);
                    }
                }
                case "resources/list" -> respond(out, id,
                    "{\"resources\":[{\"uri\":\"fake://greeting\",\"name\":\"greeting\","
                    + "\"mimeType\":\"text/plain\"}]}");
                case "resources/read" -> respond(out, id,
                    "{\"contents\":[{\"uri\":\"fake://greeting\",\"mimeType\":\"text/plain\","
                    + "\"text\":\"hello from fake resource\"}]}");
                default -> error(out, id, -32601, "Method not found: " + method);
            }
        }
    }

    private static void respond(PrintWriter out, String id, String resultJson) {
        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}");
    }

    private static void error(PrintWriter out, String id, int code, String message) {
        out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":" + code
            + ",\"message\":\"" + escape(message) + "\"}}");
    }

    private static String extractId(String line) {
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(line);
        return m.find() ? m.group(1) : "0";
    }

    private static String extractString(String line, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(line);
        if (!m.find()) return null;
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
