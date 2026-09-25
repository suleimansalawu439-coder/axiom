package dev.axiom.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Native Model Context Protocol client: JSON-RPC 2.0 over stdio, implemented
 * directly on Jackson with no external SDK.
 *
 * <p>Typical use:
 * <pre>{@code
 * try (McpClient client = McpClient.spawn(List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"))) {
 *     client.initialize();
 *     for (McpTool t : client.listTools()) System.out.println(t.name());
 *     McpToolResult r = client.callTool("read_file", Map.of("path", "/tmp/notes.txt"));
 * }
 * }</pre>
 *
 * <p>Wire details: one JSON-RPC message per line on stdin/stdout (the MCP
 * stdio framing); server log output must go to stderr, which is drained and
 * discarded. Server-initiated {@code roots/list} requests are answered with
 * an empty list; other server requests get a method-not-found error.
 */
public final class McpClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(McpClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Process process; // null when constructed from raw streams (tests)
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final AtomicLong nextId = new AtomicLong(0);
    private final ConcurrentMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Thread readerThread;
    private final Thread stderrDrainer;
    private volatile boolean closed = false;
    private volatile boolean initialized = false;

    private Duration responseTimeout = Duration.ofSeconds(30);
    private String protocolVersion = "2024-11-05";

    /** Wrap an already-running server process. */
    public McpClient(Process process) {
        this(process, process.getInputStream(), process.getOutputStream());
    }

    /** Wrap raw streams (used by tests to simulate a server). */
    public McpClient(InputStream serverStdout, OutputStream serverStdin) {
        this(null, serverStdout, serverStdin);
    }

    private McpClient(Process process, InputStream serverStdout, OutputStream serverStdin) {
        this.process = process;
        this.reader = new BufferedReader(new InputStreamReader(serverStdout));
        this.writer = new BufferedWriter(new OutputStreamWriter(serverStdin));
        this.readerThread = Thread.ofPlatform().daemon().name("axiom-mcp-reader").start(this::readLoop);
        if (process != null) {
            this.stderrDrainer = Thread.ofPlatform().daemon().name("axiom-mcp-stderr")
                .start(() -> drain(process.getErrorStream()));
        } else {
            this.stderrDrainer = null;
        }
    }

    /** Spawn an MCP server process and connect to it over stdio. */
    public static McpClient spawn(List<String> command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(false).start();
            return new McpClient(p);
        } catch (IOException e) {
            throw new McpException("Failed to spawn MCP server " + command + ": " + e.getMessage(), e);
        }
    }

    public McpClient withResponseTimeout(Duration timeout) {
        this.responseTimeout = timeout;
        return this;
    }

    public McpClient withProtocolVersion(String version) {
        this.protocolVersion = version;
        return this;
    }

    // ------------------------------------------------------------------
    // Protocol
    // ------------------------------------------------------------------

    /** MCP handshake. Idempotent — subsequent calls are no-ops. */
    public synchronized McpServerInfo initialize() {
        if (initialized) return lastServerInfo;
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", protocolVersion);
        ObjectNode caps = mapper.createObjectNode();
        caps.set("roots", mapper.createObjectNode().put("listChanged", true));
        params.set("capabilities", caps);
        ObjectNode clientInfo = mapper.createObjectNode();
        clientInfo.put("name", "axiom");
        clientInfo.put("version", "0.2.0");
        params.set("clientInfo", clientInfo);

        JsonNode result = request("initialize", params);
        sendNotification("notifications/initialized", null);
        initialized = true;
        JsonNode info = result.path("serverInfo");
        lastServerInfo = new McpServerInfo(
            info.path("name").asText("unknown"),
            info.path("version").asText("unknown"),
            result.path("protocolVersion").asText(protocolVersion));
        log.debug("MCP initialized: {}", lastServerInfo);
        return lastServerInfo;
    }

    private volatile McpServerInfo lastServerInfo;

    public void ping() {
        request("ping", null);
    }

    public List<McpTool> listTools() {
        ensureInitialized();
        JsonNode result = request("tools/list", null);
        List<McpTool> tools = new ArrayList<>();
        for (JsonNode t : result.path("tools")) {
            Map<String, Object> schema = schemaToMap(t.path("inputSchema"));
            tools.add(new McpTool(
                t.path("name").asText(),
                t.path("description").asText(""),
                schema));
        }
        return List.copyOf(tools);
    }

    public McpToolResult callTool(String name, Map<String, Object> arguments) {
        ensureInitialized();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", mapper.valueToTree(arguments == null ? Map.of() : arguments));
        JsonNode result = request("tools/call", params);
        boolean isError = result.path("isError").asBoolean(false);
        List<McpToolResult.McpContentBlock> blocks = new ArrayList<>();
        for (JsonNode c : result.path("content")) {
            String type = c.path("type").asText("text");
            String text = c.path("text").asText(null);
            String mimeType = c.path("mimeType").asText(null);
            String uri = c.path("resource").path("uri").asText(null);
            if (text == null && "resource".equals(type)) {
                text = c.path("resource").path("text").asText(null);
            }
            blocks.add(new McpToolResult.McpContentBlock(type, text, mimeType, uri));
        }
        McpToolResult toolResult = new McpToolResult(List.copyOf(blocks), isError);
        if (isError) {
            throw new McpException("MCP tool '" + name + "' reported an error: " + toolResult.asText());
        }
        return toolResult;
    }

    public List<McpResource> listResources() {
        ensureInitialized();
        JsonNode result = request("resources/list", null);
        List<McpResource> out = new ArrayList<>();
        for (JsonNode r : result.path("resources")) {
            out.add(new McpResource(
                r.path("uri").asText(),
                r.path("name").asText(""),
                r.path("mimeType").asText(null)));
        }
        return List.copyOf(out);
    }

    public List<McpResourceContent> readResource(String uri) {
        ensureInitialized();
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);
        JsonNode result = request("resources/read", params);
        List<McpResourceContent> out = new ArrayList<>();
        for (JsonNode c : result.path("contents")) {
            out.add(new McpResourceContent(
                c.path("uri").asText(uri),
                c.path("mimeType").asText(null),
                c.path("text").asText(null)));
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    // JSON-RPC plumbing
    // ------------------------------------------------------------------

    private void ensureInitialized() {
        if (!initialized) initialize();
    }

    private JsonNode request(String method, JsonNode params) {
        long id = nextId.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("jsonrpc", "2.0");
            msg.put("id", id);
            msg.put("method", method);
            if (params != null) msg.set("params", params);
            send(msg);
        } catch (Exception e) {
            pending.remove(id);
            throw new McpException("Failed to send MCP request '" + method + "': " + e.getMessage(), e);
        }
        try {
            return future.get(responseTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new McpException(
                "MCP request '" + method + "' timed out after " + responseTimeout.getSeconds() + "s", e);
        } catch (ExecutionException e) {
            throw new McpException("MCP request '" + method + "' failed: "
                + e.getCause().getMessage(), e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP request '" + method + "' interrupted", e);
        }
    }

    private void sendNotification(String method, JsonNode params) {
        try {
            ObjectNode msg = mapper.createObjectNode();
            msg.put("jsonrpc", "2.0");
            msg.put("method", method);
            if (params != null) msg.set("params", params);
            send(msg);
        } catch (Exception e) {
            throw new McpException("Failed to send MCP notification '" + method + "': " + e.getMessage(), e);
        }
    }

    private synchronized void send(JsonNode msg) throws IOException {
        writer.write(mapper.writeValueAsString(msg));
        writer.newLine();
        writer.flush();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> schemaToMap(JsonNode schema) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return Map.of("type", "object");
        }
        // Deep-strip nulls: Map.copyOf (used downstream) rejects null values,
        // and nulls carry no meaning in a tool-call schema.
        return (Map<String, Object>) stripNulls(mapper.convertValue(schema, Map.class));
    }

    @SuppressWarnings("unchecked")
    private static Object stripNulls(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> {
                if (v != null) out.put(String.valueOf(k), stripNulls(v));
            });
            return out;
        }
        if (o instanceof List<?> l) {
            return l.stream().filter(Objects::nonNull).map(McpClient::stripNulls).toList();
        }
        return o;
    }

    /** Reader loop: correlates responses by id, answers server requests, ignores notifications. */
    private void readLoop() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode msg;
                try {
                    msg = mapper.readTree(line);
                } catch (Exception e) {
                    log.warn("Ignoring non-JSON line from MCP server: {}", truncate(line));
                    continue;
                }
                dispatch(msg);
            }
        } catch (IOException e) {
            if (!closed) log.debug("MCP reader ended: {}", e.getMessage());
        } finally {
            McpException end = new McpException("MCP server closed its stdout");
            pending.forEach((id, f) -> f.completeExceptionally(end));
            pending.clear();
        }
    }

    private void dispatch(JsonNode msg) {
        JsonNode id = msg.get("id");
        if (id != null && (msg.has("result") || msg.has("error"))) {
            // Response to one of our requests.
            CompletableFuture<JsonNode> f = pending.remove(id.asLong(-1));
            if (f == null) {
                log.warn("MCP response for unknown id {}", id);
                return;
            }
            if (msg.has("error")) {
                JsonNode err = msg.get("error");
                f.completeExceptionally(new McpException(
                    "MCP error %s: %s".formatted(err.path("code").asText(), err.path("message").asText())));
            } else {
                f.complete(msg.get("result"));
            }
            return;
        }
        JsonNode method = msg.get("method");
        if (method != null && id != null) {
            // Server-initiated request: answer minimally, never block the protocol.
            answerServerRequest(id, method.asText());
            return;
        }
        // Notification from server (e.g. notifications/tools/list_changed): nothing to do.
        log.debug("MCP notification: {}", method == null ? "?" : method.asText());
    }

    private void answerServerRequest(JsonNode id, String method) {
        try {
            ObjectNode resp = mapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", id);
            if ("roots/list".equals(method)) {
                ObjectNode result = mapper.createObjectNode();
                result.set("roots", mapper.createArrayNode());
                resp.set("result", result);
            } else {
                ObjectNode err = mapper.createObjectNode();
                err.put("code", -32601);
                err.put("message", "Method not found: " + method);
                resp.set("error", err);
            }
            send(resp);
        } catch (IOException e) {
            log.warn("Failed to answer MCP server request '{}': {}", method, e.getMessage());
        }
    }

    private static void drain(InputStream in) {
        try (in) {
            byte[] buf = new byte[8192];
            while (in.read(buf) >= 0) { /* discard server stderr */ }
        } catch (IOException ignored) {
        }
    }

    private static String truncate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            writer.close(); // EOF on stdin: well-behaved servers exit.
        } catch (IOException ignored) {
        }
        if (process != null) {
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        readerThread.interrupt();
    }

    /** For diagnostics: the raw server process, null for stream-backed clients. */
    public Process process() {
        return process;
    }
}
