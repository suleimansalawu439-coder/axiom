package dev.axiom.demo;

import dev.axiom.Axiom;
import dev.axiom.agent.AgentEvent;
import dev.axiom.agent.AgentResult;
import dev.axiom.llm.OpenAiCompatibleClient;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import java.nio.file.*;
import java.util.*;

/**
 * Demo: a CLI assistant with local tools (calculator, notes, sandboxed files).
 *
 * <p>Run with:
 * <pre>
 *   export OPENAI_API_KEY=sk-...
 *   # optional: AXIOM_MODEL=gpt-4o  AXIOM_BASE_URL=https://api.openai.com/v1
 *   java -cp target/axiom-0.1.0.jar:... dev.axiom.demo.DemoAgent "What is 17*23, and save the answer as a note?"
 * </pre>
 * Any OpenAI-compatible endpoint works (Ollama, vLLM, LM Studio...).
 */
public class DemoAgent {

    // ------------------------------------------------------------------
    // Tools
    // ------------------------------------------------------------------

    public static class Calculator {
        @Tool(description = "Add two numbers")
        public double add(@ToolParam(description = "First number") double a,
                          @ToolParam(description = "Second number") double b) {
            return a + b;
        }

        @Tool(description = "Subtract b from a")
        public double subtract(@ToolParam(description = "First number") double a,
                               @ToolParam(description = "Second number") double b) {
            return a - b;
        }

        @Tool(description = "Multiply two numbers")
        public double multiply(@ToolParam(description = "First number") double a,
                               @ToolParam(description = "Second number") double b) {
            return a * b;
        }

        @Tool(description = "Divide a by b")
        public double divide(@ToolParam(description = "Numerator") double a,
                             @ToolParam(description = "Denominator (must not be zero)") double b) {
            if (b == 0) throw new IllegalArgumentException("Division by zero");
            return a / b;
        }
    }

    public static class Notes {
        private final Map<String, String> notes = new LinkedHashMap<>();

        @Tool(description = "Save a named note for later")
        public String saveNote(@ToolParam(description = "Note title") String title,
                               @ToolParam(description = "Note content") String content) {
            notes.put(title, content);
            return "Saved note '" + title + "'";
        }

        @Tool(description = "List all saved note titles")
        public List<String> listNotes() {
            return new ArrayList<>(notes.keySet());
        }

        @Tool(description = "Read a saved note by title")
        public String readNote(@ToolParam(description = "Note title") String title) {
            return notes.getOrDefault(title, "No note titled '" + title + "'");
        }
    }

    public static class Files {
        private final Path sandbox;

        public Files(Path sandbox) {
            this.sandbox = sandbox.toAbsolutePath().normalize();
        }

        @Tool(description = "List files in the sandbox directory")
        public List<String> listFiles() throws Exception {
            try (var stream = java.nio.file.Files.list(sandbox)) {
                return stream.map(p -> p.getFileName().toString()).sorted().toList();
            }
        }

        @Tool(description = "Read a text file from the sandbox directory")
        public String readFile(@ToolParam(description = "File name (no path traversal allowed)") String name)
                throws Exception {
            Path target = sandbox.resolve(name).normalize();
            if (!target.startsWith(sandbox)) throw new SecurityException("Path traversal blocked: " + name);
            return java.nio.file.Files.readString(target);
        }
    }

    // ------------------------------------------------------------------
    // Main
    // ------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        String task = args.length > 0 ? String.join(" ", args) : readStdin();
        if (task.isBlank()) {
            System.err.println("Usage: DemoAgent \"<task>\"");
            System.exit(1);
        }

        String baseUrl = System.getenv().getOrDefault("AXIOM_BASE_URL", "https://api.openai.com/v1");
        String model = System.getenv().getOrDefault("AXIOM_MODEL", "gpt-4o");
        String apiKey = System.getenv().getOrDefault("OPENAI_API_KEY", "");

        Path sandbox = Paths.get("demo-sandbox");
        java.nio.file.Files.createDirectories(sandbox);

        var agent = Axiom.agent()
            .withClient(new OpenAiCompatibleClient(baseUrl, apiKey, model))
            .withTools(new Calculator(), new Notes(), new Files(sandbox))
            .withSystemPrompt("You are Axiom, a helpful assistant with tools. "
                + "Think step by step. Use tools when they help answer accurately. "
                + "Always give a clear final answer.")
            .onEvent(DemoAgent::printEvent)
            .buildAgent();

        System.out.println("▶ Task: " + task + "\n");
        AgentResult result = agent.run(task);
        System.out.println("\n✓ Answer: " + result.output());
        System.out.printf("  (%d iterations, %d tool calls, %d tokens)%n",
            result.iterations(), result.toolCallsMade(), result.tokenUsage().totalTokens());
    }

    private static void printEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.ToolCallStarted s ->
                System.out.println("  ⚙ calling " + s.call().name() + s.call().arguments());
            case AgentEvent.ToolCallFinished f ->
                System.out.println("  ✓ " + f.call().name() + " → "
                    + truncate(f.result(), 120) + " [" + f.durationMs() + "ms]");
            case AgentEvent.ApprovalRequested a ->
                System.out.println("  ⏸ approval requested: " + a.toolName());
            default -> { /* quiet for the rest */ }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        String flat = s.replace("\n", " ");
        return flat.length() > max ? flat.substring(0, max) + "…" : flat;
    }

    private static String readStdin() {
        try (var scanner = new Scanner(System.in)) {
            System.out.print("Task: ");
            return scanner.hasNextLine() ? scanner.nextLine() : "";
        }
    }
}
