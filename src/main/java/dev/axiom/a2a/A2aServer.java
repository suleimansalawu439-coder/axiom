package dev.axiom.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.axiom.Axiom;
import dev.axiom.a2a.A2aTypes.A2aTask;
import dev.axiom.a2a.A2aTypes.Artifact;
import dev.axiom.a2a.A2aTypes.Part;
import dev.axiom.a2a.A2aTypes.States;
import dev.axiom.a2a.A2aTypes.TaskStatus;
import dev.axiom.agent.AgentResult;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Minimal A2A v1.0 server endpoint for an Axiom agent, built on the JDK's
 * {@code com.sun.net.httpserver} — no framework dependencies.
 *
 * <ul>
 *   <li>{@code GET /.well-known/agent-card.json} — agent card publication.</li>
 *   <li>{@code POST /} with JSON-RPC {@code message/send} — runs the agent on
 *       the message's text parts and returns a task whose artifact holds the
 *       final answer.</li>
 *   <li>{@code POST /} with JSON-RPC {@code message/stream} — same, but
 *       status/artifact updates stream back as Server-Sent Events.</li>
 *   <li>{@code POST /} with JSON-RPC {@code tasks/get} /
 *       {@code tasks/cancel} — task lookup and cancellation.</li>
 * </ul>
 *
 * <pre>{@code
 * AgentCard card = AgentCard.simple("researcher", "Researches topics",
 *     "http://localhost:8080", "research a topic and return a brief");
 * try (A2aServer server = A2aServer.serve(agent, card, 8080)) {
 *     // ... visible to any A2A client, Axiom or foreign ...
 * }
 * }</pre>
 */
public final class A2aServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server; // null until serve() binds
    private final Axiom.Agent agent;
    private final AgentCard card;
    private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();

    /**
     * Create an unbound server: the A2A protocol logic (card, dispatch,
     * streaming events) without any socket. Useful for embedding and for
     * tests in environments without loopback TCP.
     */
    public A2aServer(Axiom.Agent agent, AgentCard card) {
        this.agent = agent;
        this.card = card;
    }

    /**
     * Start serving. {@code port} may be 0 for an ephemeral port — read the
     * real one back with {@link #port()}.
     */
    public static A2aServer serve(Axiom.Agent agent, AgentCard card, int port) throws IOException {
        A2aServer s = new A2aServer(agent, card);
        s.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        s.server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "axiom-a2a");
            t.setDaemon(true);
            return t;
        }));
        s.server.createContext("/.well-known/agent-card.json", s::handleAgentCard);
        s.server.createContext("/", s::handleRpc);
        s.server.start();
        return s;
    }

    /** The bound port (useful when {@code serve} was given port 0). */
    public int port() {
        if (server == null) throw new IllegalStateException("server is not bound; use serve()");
        return server.getAddress().getPort();
    }

    /** Base URL of this server, e.g. {@code http://127.0.0.1:8080}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + port();
    }

    @Override
    public void close() {
        if (server != null) server.stop(0);
    }

    // ------------------------------------------------------------------
    // Protocol logic (transport-independent; unit-testable)
    // ------------------------------------------------------------------

    /**
     * The agent card as served, with {@code url} rewritten to the given base.
     */
    public Map<String, Object> cardJson(String baseUrl) {
        Map<String, Object> cardJson = new LinkedHashMap<>(card.toJson());
        cardJson.put("url", baseUrl);
        return cardJson;
    }

    /**
     * Dispatch one JSON-RPC method ({@code message/send}, {@code tasks/get},
     * {@code tasks/cancel}) and return its {@code result}. Throws
     * {@link A2aException} (with the JSON-RPC error code) on failure.
     */
    public Map<String, Object> dispatch(String method, Map<String, Object> params) {
        try {
            return switch (method) {
                case "message/send" -> handleMessageSend(params);
                case "tasks/get" -> handleTasksGet(params);
                case "tasks/cancel" -> handleTasksCancel(params);
                default -> throw new A2aException(-32601, "Method not found: " + method);
            };
        } catch (RpcException e) {
            throw new A2aException(e.code, e.getMessage());
        }
    }

    /**
     * The {@code message/stream} event payloads in order: a working
     * status-update, the artifact-update, then the final status-update.
     * The HTTP layer serializes these as SSE; tests can inspect them directly.
     */
    public List<Map<String, Object>> streamTaskEvents(Map<String, Object> params) {
        Map<String, Object> message = params.get("message") instanceof Map<?, ?> mm
            ? (Map<String, Object>) mm : Map.of();
        String text = extractText(message);
        String contextId = message.get("contextId") == null
            ? UUID.randomUUID().toString() : String.valueOf(message.get("contextId"));
        String taskId = UUID.randomUUID().toString();
        Map<String, Object> task = newTask(taskId, contextId, States.WORKING);
        tasks.put(taskId, task);

        List<Map<String, Object>> events = new ArrayList<>();
        events.add(statusUpdate(taskId, contextId, States.WORKING, false));
        AgentResult result;
        try {
            result = agent.run(text);
        } catch (Exception e) {
            setStatus(task, States.FAILED);
            events.add(statusUpdate(taskId, contextId, States.FAILED, true));
            return events;
        }
        Artifact artifact = textArtifact(result.output());
        task.put("artifacts", List.of(artifact.toJson()));
        String state = result.completed() ? States.COMPLETED : States.FAILED;
        setStatus(task, state);
        Map<String, Object> artifactEvent = new LinkedHashMap<>();
        artifactEvent.put("kind", "artifact-update");
        artifactEvent.put("taskId", taskId);
        artifactEvent.put("contextId", contextId);
        artifactEvent.put("artifact", artifact.toJson());
        events.add(artifactEvent);
        events.add(statusUpdate(taskId, contextId, state, true));
        return events;
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private void handleAgentCard(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "method not allowed"));
            return;
        }
        // The card's url always reflects where this server is actually bound.
        sendJson(ex, 200, cardJson(baseUrl()));
    }

    @SuppressWarnings("unchecked")
    private void handleRpc(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 405, Map.of("error", "method not allowed"));
            return;
        }
        Map<String, Object> req;
        try {
            req = MAPPER.readValue(ex.getRequestBody(), new TypeReference<>() {});
        } catch (Exception e) {
            sendJson(ex, 400, Map.of("error", "invalid JSON-RPC request"));
            return;
        }
        Object id = req.get("id");
        String method = String.valueOf(req.get("method"));
        Map<String, Object> params = req.get("params") instanceof Map<?, ?> pm
            ? (Map<String, Object>) pm : Map.of();

        // message/stream hijacks the connection for SSE.
        if ("message/stream".equals(method)) {
            handleMessageStream(ex, id, params);
            return;
        }
        try {
            Map<String, Object> result = dispatch(method, params);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);
            resp.put("result", result);
            sendJson(ex, 200, resp);
        } catch (A2aException e) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("code", e.code());
            err.put("message", e.getMessage());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("jsonrpc", "2.0");
            resp.put("id", id);
            resp.put("error", err);
            sendJson(ex, 200, resp);
        }
    }

    // ------------------------------------------------------------------
    // JSON-RPC methods
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> handleMessageSend(Map<String, Object> params) {
        Map<String, Object> message = params.get("message") instanceof Map<?, ?> mm
            ? (Map<String, Object>) mm : Map.of();
        String text = extractText(message);
        String contextId = message.get("contextId") == null
            ? UUID.randomUUID().toString() : String.valueOf(message.get("contextId"));

        String taskId = UUID.randomUUID().toString();
        Map<String, Object> task = newTask(taskId, contextId, States.WORKING);
        tasks.put(taskId, task);
        try {
            AgentResult result = agent.run(text);
            task.put("artifacts", List.of(textArtifact(result.output()).toJson()));
            setStatus(task, result.completed() ? States.COMPLETED : States.FAILED);
        } catch (Exception e) {
            task.put("artifacts", List.of(textArtifact("Agent failed: " + e.getMessage()).toJson()));
            setStatus(task, States.FAILED);
        }
        return task;
    }

    private Map<String, Object> handleTasksGet(Map<String, Object> params) {
        String id = String.valueOf(params.get("id"));
        Map<String, Object> task = tasks.get(id);
        if (task == null) throw new RpcException(-32001, "Task not found: " + id);
        return task;
    }

    private Map<String, Object> handleTasksCancel(Map<String, Object> params) {
        String id = String.valueOf(params.get("id"));
        Map<String, Object> task = tasks.get(id);
        if (task == null) throw new RpcException(-32001, "Task not found: " + id);
        setStatus(task, States.CANCELED);
        return task;
    }

    /**
     * SSE variant of message/send: emits {@code status-update} events, runs
     * the agent, then an {@code artifact-update} and a final status event.
     */
    private void handleMessageStream(HttpExchange ex, Object id, Map<String, Object> params)
            throws IOException {
        List<Map<String, Object>> events = streamTaskEvents(params);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            for (Map<String, Object> event : events) {
                sendSse(out, id, event);
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> newTask(String taskId, String contextId, String state) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("state", state);
        status.put("timestamp", Instant.now().toString());
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", taskId);
        task.put("contextId", contextId);
        task.put("status", status);
        task.put("artifacts", new ArrayList<>());
        return task;
    }

    private void setStatus(Map<String, Object> task, String state) {
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) task.get("status");
        status.put("state", state);
        status.put("timestamp", Instant.now().toString());
    }

    private Artifact textArtifact(String text) {
        return new Artifact(UUID.randomUUID().toString(), "result",
            List.of(new Part.TextPart(text == null ? "" : text)));
    }

    private Map<String, Object> statusUpdate(String taskId, String contextId,
                                             String state, boolean done) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", "status-update");
        e.put("taskId", taskId);
        e.put("contextId", contextId);
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("state", state);
        st.put("timestamp", Instant.now().toString());
        e.put("status", st);
        e.put("final", done);
        return e;
    }

    private void sendSse(OutputStream out, Object id, Map<String, Object> payload)
            throws IOException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", id);
        envelope.put("result", payload);
        String line = "data: " + MAPPER.writeValueAsString(envelope) + "\n\n";
        out.write(line.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @SuppressWarnings("unchecked")
    private static String extractText(Map<String, Object> message) {
        Object parts = message.get("parts");
        if (!(parts instanceof List<?> list)) return "";
        StringBuilder sb = new StringBuilder();
        for (Object o : list) {
            Map<String, Object> p = (Map<String, Object>) o;
            if ("text".equals(String.valueOf(p.get("kind"))) && p.get("text") != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(String.valueOf(p.get("text")));
            }
        }
        return sb.toString();
    }

    private void sendJson(HttpExchange ex, int status, Map<String, Object> body)
            throws IOException {
        byte[] bytes = MAPPER.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static final class RpcException extends RuntimeException {
        final int code;
        RpcException(int code, String message) {
            super(message);
            this.code = code;
        }
    }
}
