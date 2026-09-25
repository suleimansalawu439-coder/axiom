package dev.axiom.bench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.llm.ChatMessage;
import dev.axiom.llm.ChatResponse;
import dev.axiom.llm.LlmClient;
import dev.axiom.llm.ToolCallRequest;
import dev.axiom.tools.ToolDefinition;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A scripted LLM backed by a JSON fixture file, so benchmarks run
 * deterministically offline (e.g. in CI). Fixture format:
 *
 * <pre>{@code
 * [
 *   {"content": "I'll multiply.",
 *    "tool_calls": [{"id": "c1", "name": "multiply",
 *                     "arguments": {"x": 17, "y": 23}}],
 *    "usage": {"prompt_tokens": 50, "completion_tokens": 10, "total_tokens": 60}},
 *   {"content": "396", "tool_calls": [],
 *    "usage": {"prompt_tokens": 60, "completion_tokens": 5, "total_tokens": 65}}
 * ]
 * }</pre>
 *
 * The agent still executes the scripted tool calls for real — only the
 * model's words are canned.
 */
public final class FixtureLlm implements LlmClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Deque<ChatResponse> script = new ArrayDeque<>();
    private final String name;

    private FixtureLlm(String name, List<ChatResponse> script) {
        this.name = name;
        this.script.addAll(script);
    }

    /** Load a fixture from a JSON file. */
    public static FixtureLlm load(Path fixtureFile) {
        try {
            String json = Files.readString(fixtureFile, StandardCharsets.UTF_8);
            return new FixtureLlm(fixtureFile.toString(), parse(json));
        } catch (Exception e) {
            throw new BenchException("Failed to load fixture " + fixtureFile, e);
        }
    }

    /** Load a fixture from the classpath (e.g. {@code /bench/fixtures/x.json}). */
    public static FixtureLlm loadResource(String resourcePath) {
        URL url = FixtureLlm.class.getResource(resourcePath);
        if (url == null) throw new BenchException("Missing fixture resource: " + resourcePath);
        try (var in = url.openStream()) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new FixtureLlm(resourcePath, parse(json));
        } catch (Exception e) {
            throw new BenchException("Failed to load fixture resource " + resourcePath, e);
        }
    }

    @Override
    public ChatResponse chat(List<ChatMessage> messages, List<ToolDefinition> tools,
                             LlmOptions options) {
        if (script.isEmpty()) {
            throw new AssertionError("Fixture '" + name + "' ran out of scripted responses");
        }
        return script.poll();
    }

    @Override
    public String model() {
        return "fixture";
    }

    @SuppressWarnings("unchecked")
    static List<ChatResponse> parse(String json) throws Exception {
        List<Map<String, Object>> raw = MAPPER.readValue(json, new TypeReference<>() {});
        List<ChatResponse> out = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            List<ToolCallRequest> calls = new ArrayList<>();
            Object tc = r.get("tool_calls");
            if (tc instanceof List<?> list) {
                for (Object o : list) {
                    Map<String, Object> c = (Map<String, Object>) o;
                    Object args = c.get("arguments");
                    calls.add(new ToolCallRequest(
                        String.valueOf(c.get("id")), String.valueOf(c.get("name")),
                        args instanceof Map<?, ?> am ? (Map<String, Object>) am : Map.of()));
                }
            }
            Object u = r.get("usage");
            ChatResponse.TokenUsage usage = ChatResponse.TokenUsage.empty();
            if (u instanceof Map<?, ?> um) {
                Map<String, Object> m = (Map<String, Object>) um;
                usage = new ChatResponse.TokenUsage(num(m.get("prompt_tokens")),
                    num(m.get("completion_tokens")), num(m.get("total_tokens")));
            }
            Object content = r.get("content");
            out.add(new ChatResponse(content == null ? null : String.valueOf(content),
                calls, usage));
        }
        return out;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
