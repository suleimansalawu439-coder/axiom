package dev.axiom.a2a;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.a2a.A2aTypes.A2aTask;
import dev.axiom.tools.ToolDefinition;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A2A v1.0 client: discover agents via their agent card, send tasks, poll or
 * subscribe for results, and expose a remote agent as a local Axiom
 * {@link ToolDefinition} so agents can delegate to each other.
 *
 * <pre>{@code
 * A2aClient client = new A2aClient();
 * AgentCard card = client.getAgentCard("http://localhost:8080");
 * A2aTask task = client.sendTask("http://localhost:8080", "Summarize solid-state batteries");
 * System.out.println(task.artifactText().orElse("(none)"));
 *
 * // Or delegate from inside another Axiom agent:
 * var agent = Axiom.agent().withModel("gpt-4o")
 *     .withToolDefinitions(A2aClient.asTool("http://localhost:8080",
 *         "remote_researcher", "Delegates research to the remote agent"))
 *     .buildAgent();
 * }</pre>
 */
public final class A2aClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http;

    public A2aClient() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();
    }

    /** Fetch and parse the agent card of any A2A agent (Axiom or foreign). */
    public AgentCard getAgentCard(String baseUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create(norm(baseUrl) + "/.well-known/agent-card.json"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
            HttpResponse<String> resp =
                http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new A2aException("Agent card fetch failed with HTTP " + resp.statusCode());
            }
            Map<String, Object> m = MAPPER.readValue(resp.body(), new TypeReference<>() {});
            return AgentCard.fromJson(m);
        } catch (A2aException e) {
            throw e;
        } catch (Exception e) {
            throw new A2aException("Agent card fetch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Send a task and block until it reaches a terminal state. Uses
     * {@code message/send} then polls {@code tasks/get}.
     */
    public A2aTask sendTask(String baseUrl, String text) {
        return sendTask(baseUrl, text, Duration.ofMinutes(5));
    }

    public A2aTask sendTask(String baseUrl, String text, Duration timeout) {
        Map<String, Object> result = rpc(baseUrl, "message/send",
            Map.of("message", Map.of(
                "messageId", UUID.randomUUID().toString(),
                "role", "user",
                "parts", List.of(Map.of("kind", "text", "text", text)))));
        A2aTask task = A2aTask.fromJson(result);
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (!task.isTerminal()) {
            if (System.currentTimeMillis() > deadline) {
                throw new A2aException("Timed out waiting for task " + task.id());
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new A2aException("Interrupted while waiting for task " + task.id(), e);
            }
            task = getTask(baseUrl, task.id());
        }
        return task;
    }

    /** Look up a task by id ({@code tasks/get}). */
    public A2aTask getTask(String baseUrl, String taskId) {
        return A2aTask.fromJson(rpc(baseUrl, "tasks/get", Map.of("id", taskId)));
    }

    /** Best-effort cancellation ({@code tasks/cancel}); true when accepted. */
    public boolean cancelTask(String baseUrl, String taskId) {
        try {
            rpc(baseUrl, "tasks/cancel", Map.of("id", taskId));
            return true;
        } catch (A2aException e) {
            return false;
        }
    }

    /**
     * Subscribe to a task via {@code message/stream}: returns the SSE event
     * payloads (status-update / artifact-update) in arrival order.
     */
    public List<Map<String, Object>> streamTask(String baseUrl, String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", UUID.randomUUID().toString());
        body.put("method", "message/stream");
        body.put("params", Map.of("message", Map.of(
            "messageId", UUID.randomUUID().toString(),
            "role", "user",
            "parts", List.of(Map.of("kind", "text", "text", text)))));
        try {
            String json = MAPPER.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(norm(baseUrl) + "/"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HttpResponse<java.io.InputStream> resp =
                http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new A2aException("message/stream failed with HTTP " + resp.statusCode());
            }
            try (var reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                return parseSseEvents(reader);
            }
        } catch (A2aException e) {
            throw e;
        } catch (Exception e) {
            throw new A2aException("message/stream failed: " + e.getMessage(), e);
        }
    }

    /**
     * Parse SSE {@code data:} lines into their JSON payloads. Pure function —
     * unit-testable without a socket.
     */
    static List<Map<String, Object>> parseSseEvents(BufferedReader reader) throws Exception {
        List<Map<String, Object>> events = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            String t = line.trim();
            if (!t.startsWith("data:")) continue;
            String data = t.substring(5).trim();
            if (data.equals("[DONE]")) break;
            Map<String, Object> envelope =
                MAPPER.readValue(data, new TypeReference<>() {});
            Object result = envelope.get("result");
            if (result instanceof Map<?, ?> rm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> event = (Map<String, Object>) rm;
                events.add(event);
            }
        }
        return events;
    }

    /**
     * Expose a remote A2A agent as a local tool: an Axiom agent with this
     * tool delegates by sending an A2A task and returning the artifact text.
     */
    public static ToolDefinition asTool(String baseUrl, String toolName, String description) {
        A2aClient client = new A2aClient();
        return asTool(client::sendTask, baseUrl, toolName, description);
    }

    /**
     * Same, with an explicit task sender — lets tests (and custom transports)
     * substitute the HTTP round trip. The function receives
     * {@code (baseUrl, message)} and returns the terminal task.
     */
    public static ToolDefinition asTool(
            java.util.function.BiFunction<String, String, A2aTask> sender,
            String baseUrl, String toolName, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("message", Map.of("type", "string",
            "description", "The task to delegate to the remote agent."));
        schema.put("properties", props);
        schema.put("required", List.of("message"));
        schema.put("additionalProperties", false);
        return ToolDefinition.of(toolName, description, schema, false, 300, args -> {
            Object m = args.get("message");
            if (m == null) throw new IllegalArgumentException("message is required");
            A2aTask task = sender.apply(baseUrl, String.valueOf(m));
            return task.artifactText().orElse("(remote agent returned no artifact)");
        });
    }

    // ------------------------------------------------------------------
    // JSON-RPC
    // ------------------------------------------------------------------

    /** Raw JSON-RPC call; returns the {@code result} object or throws {@link A2aException}. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> rpc(String baseUrl, String method, Map<String, Object> params) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", UUID.randomUUID().toString());
            body.put("method", method);
            body.put("params", params);
            HttpRequest req = HttpRequest.newBuilder(URI.create(norm(baseUrl) + "/"))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
            HttpResponse<String> resp =
                http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new A2aException("JSON-RPC HTTP " + resp.statusCode() + ": " + method);
            }
            return extractResult(resp.body(), method);
        } catch (A2aException e) {
            throw e;
        } catch (Exception e) {
            throw new A2aException("A2A call " + method + " failed: " + e.getMessage(), e);
        }
    }

    /**
     * Parse a JSON-RPC response body into its {@code result} object.
     * Pure function — unit-testable without a socket.
     */
    static Map<String, Object> extractResult(String body, String method) throws Exception {
        Map<String, Object> envelope =
            MAPPER.readValue(body, new TypeReference<>() {});
        if (envelope.get("error") instanceof Map<?, ?> em) {
            Map<String, Object> err = (Map<String, Object>) em;
            Object code = err.get("code");
            throw new A2aException(code instanceof Number n ? n.intValue() : -1,
                String.valueOf(err.get("message")));
        }
        Object result = envelope.get("result");
        if (!(result instanceof Map<?, ?> rm)) {
            throw new A2aException("Malformed JSON-RPC response for " + method);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) rm;
        return typed;
    }

    private static String norm(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
