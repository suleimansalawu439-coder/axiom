package dev.axiom.tools;

import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Code-as-action tool (2026-10-04, GAIA research).
 *
 * <p>Instead of JSON tool calls (one round-trip per call), the model writes
 * Python code that chains multiple tool operations. Evidence: code agents use
 * ~30% fewer steps/tokens than JSON agents (55% vs 33% on GAIA, same model).
 *
 * <p>Protocol: the Python code uses the injected {@code axiom} module:
 * <pre>{@code
 * result = axiom.web_search("Polish dub actor Everybody Loves Raymond")
 * page = axiom.web_fetch("https://en.wikipedia.org/...")
 * answer = axiom.calculate("2 + 2")
 * }</pre>
 *
 * <p>The {@code axiom} module communicates with Java via a line-based protocol
 * on stdout/stdin. Each tool call prints {@code AXIOM_CALL:<tool>:<json-args>}
 * and reads the JSON-encoded result from stdin.
 */
public final class CodeActionTool {

    private final ToolRegistry registry;
    private final Path workDir;

    public CodeActionTool(ToolRegistry registry, Path workDir) {
        this.registry = registry;
        this.workDir = workDir;
    }

    @Tool(name = "code",
          description = "Execute Python code that can call agent tools via the axiom module. "
              + "Use this to chain multiple operations without round-trips. "
              + "Available: axiom.web_search(query), axiom.web_fetch(url), "
              + "axiom.calculate(expr), axiom.read_file(name), axiom.list_files(). "
              + "Example: result = axiom.web_search(\"query\"); print(result)")
    public String code(
            @ToolParam(description = "Python code using the axiom module") String pythonCode) {
        try {
            return executeCode(pythonCode);
        } catch (Exception e) {
            return "Error executing code: " + e.getMessage();
        }
    }

    private String executeCode(String pythonCode) throws IOException, InterruptedException {
        // Build the axiom module prelude.
        String prelude = """
            import json, sys

            class AxiomModule:
                def _call(self, tool, args):
                    print(f"AXIOM_CALL:{tool}:{json.dumps(args)}", flush=True)
                    line = sys.stdin.readline()
                    if not line:
                        return "Error: no response from tool"
                    try:
                        resp = json.loads(line)
                        return resp.get("result", resp.get("error", "Unknown error"))
                    except:
                        return "Error parsing tool response"

                def web_search(self, query):
                    return self._call("web_search", {"query": query})

                def web_fetch(self, url):
                    return self._call("web_fetch", {"url": url})

                def calculate(self, expr):
                    return self._call("calculate", {"expression": expr})

                def read_file(self, name):
                    return self._call("read_file", {"name": name})

                def list_files(self):
                    return self._call("list_files", {})

            axiom = AxiomModule()
            """;

        String fullCode = prelude + "\n" + pythonCode;

        Path scriptFile = workDir.resolve("axiom_code_" + System.nanoTime() + ".py");
        Files.writeString(scriptFile, fullCode);

        try {
            ProcessBuilder pb = new ProcessBuilder("python3", scriptFile.toString());
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // Mediate tool calls: read AXIOM_CALL lines, execute, write results.
            StringBuilder output = new StringBuilder();
            var stdoutReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            var stdinWriter = new java.io.BufferedWriter(
                new java.io.OutputStreamWriter(process.getOutputStream()));
            var stderrReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getErrorStream()));

            String line;
            while ((line = stdoutReader.readLine()) != null) {
                if (line.startsWith("AXIOM_CALL:")) {
                    // Parse: AXIOM_CALL:<tool>:<json-args>
                    String rest = line.substring("AXIOM_CALL:".length());
                    int colonIdx = rest.indexOf(':');
                    if (colonIdx > 0) {
                        String toolName = rest.substring(0, colonIdx);
                        String argsJson = rest.substring(colonIdx + 1);
                        String result = executeToolCall(toolName, argsJson);
                        String responseJson = "{\"result\": "
                            + jsonEscape(result) + "}\n";
                        stdinWriter.write(responseJson);
                        stdinWriter.flush();
                    }
                } else {
                    output.append(line).append("\n");
                }
            }

            // Capture stderr.
            StringBuilder stderr = new StringBuilder();
            String errLine;
            while ((errLine = stderrReader.readLine()) != null) {
                stderr.append(errLine).append("\n");
            }

            boolean finished = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: code execution timed out after 30s";
            }

            Files.deleteIfExists(scriptFile);

            String out = output.toString().trim();
            String err = stderr.toString().trim();
            if (!err.isEmpty()) {
                out += "\n[stderr]\n" + err;
            }
            return out.isEmpty() ? "(no output)" : out;

        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    @SuppressWarnings("unchecked")
    private String executeToolCall(String toolName, String argsJson) {
        try {
            // Parse JSON args (simple parser for flat objects).
            Map<String, Object> args = parseSimpleJson(argsJson);
            Object result = registry.invoke(toolName, args);
            return result == null ? "" : result.toString();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /** Minimal JSON parser for flat {"key": "value"} objects. */
    private Map<String, Object> parseSimpleJson(String json) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        json = json.trim();
        if (json.startsWith("{")) json = json.substring(1);
        if (json.endsWith("}")) json = json.substring(0, json.length() - 1);
        // Split on commas not inside quotes (simplified).
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        java.util.List<String> pairs = new java.util.ArrayList<>();
        for (char c : json.toCharArray()) {
            if (c == '"') inQuotes = !inQuotes;
            if (c == ',' && !inQuotes) {
                pairs.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        pairs.add(current.toString());
        for (String pair : pairs) {
            int colon = pair.indexOf(':');
            if (colon > 0) {
                String key = pair.substring(0, colon).trim()
                    .replaceAll("^\"|\"$", "");
                String val = pair.substring(colon + 1).trim()
                    .replaceAll("^\"|\"$", "");
                // Unescape.
                val = val.replace("\\\"", "\"").replace("\\\\", "\\");
                map.put(key, val);
            }
        }
        return map;
    }

    private String jsonEscape(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
