package dev.axiom.bench.gaia;

import dev.axiom.capabilities.Capability;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tool holders for GAIA task runs: a workspace-scoped file reader (task
 * attachments are materialized into the run workspace) and a small
 * arithmetic evaluator. Both are pure reads/computations — declared
 * idempotent so durable resume can re-execute them safely.
 */
public final class GaiaTools {

    private GaiaTools() {}

    /** Reads files from the task's workspace (attachments land here). */
    public static final class Files {
        private final Path root;

        public Files(Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        @Tool(name = "read_file",
              description = "Read a text file from the task workspace "
                  + "(question attachments are placed here). For non-text "
                  + "files, returns an error naming the file type.",
              capabilities = {Capability.READ},
              idempotent = true)
        public String readFile(
                @ToolParam(description = "File name inside the workspace, e.g. data.csv",
                           example = "data.csv") String name) {
            try {
                Path p = root.resolve(name).normalize();
                if (!p.startsWith(root)) return "ERROR: path escapes the workspace";
                if (!java.nio.file.Files.exists(p)) return "ERROR: no such file: " + name;
                String lower = name.toLowerCase();
                boolean textish = lower.endsWith(".txt") || lower.endsWith(".csv")
                    || lower.endsWith(".json") || lower.endsWith(".md")
                    || lower.endsWith(".xml") || lower.endsWith(".html")
                    || lower.endsWith(".py") || lower.endsWith(".js")
                    || lower.endsWith(".ts") || lower.endsWith(".java");
                byte[] bytes = java.nio.file.Files.readAllBytes(p);
                if (bytes.length > 100_000) {
                    return "ERROR: file too large to read as text (" + bytes.length
                        + " bytes). Use the run tool to process it.";
                }
                if (!textish && !looksText(bytes)) {
                    return "ERROR: " + name + " is not a text file. Use the run "
                        + "tool (python3 etc.) to process it.";
                }
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "ERROR: could not read " + name + ": " + e;
            }
        }

        private static boolean looksText(byte[] b) {
            int n = Math.min(b.length, 1024);
            for (int i = 0; i < n; i++) {
                byte c = b[i];
                if (c == 0) return false;
                if (c < 0x09) return false;
                if (c > 0x0D && c < 0x20 && c != 0x1B) return false;
            }
            return true;
        }

        @Tool(name = "list_files",
              description = "List the files in the task workspace.",
              capabilities = {Capability.READ},
              idempotent = true)
        public String listFiles() {
            try (var s = java.nio.file.Files.list(root)) {
                var names = s.map(p -> p.getFileName().toString()).sorted().toList();
                return names.isEmpty() ? "(workspace is empty)" : String.join("\n", names);
            } catch (Exception e) {
                return "ERROR: could not list workspace: " + e;
            }
        }
    }

    /** Tiny arithmetic evaluator (+, -, *, /, parentheses, decimals). */
    public static final class Calc {
        @Tool(name = "calculate",
              description = "Evaluate an arithmetic expression, e.g. "
                  + "\"(17 * 23 + 5) / 2\". Supports +, -, *, /, parentheses, decimals. "
                  + "Use this for ALL arithmetic — sums, differences, products, "
                  + "percentages, unit conversions expressed as arithmetic. Do NOT "
                  + "use the run tool (python3) for arithmetic: it is slower, "
                  + "needs an interpreter installed, and wastes calls.",
              capabilities = {},
              idempotent = true)
        public String calculate(
                @ToolParam(description = "Arithmetic expression to evaluate",
                           example = "(17 * 23 + 5) / 2") String expression) {
            try {
                double v = new Parser(expression).parse();
                if (v == Math.rint(v) && Math.abs(v) < 1e15) {
                    return String.valueOf((long) v);
                }
                return String.valueOf(v);
            } catch (Exception e) {
                return "ERROR: could not evaluate <" + expression + ">: " + e.getMessage();
            }
        }

        /** Recursive-descent parser: expr := term (('+'|'-') term)*, … */
        static final class Parser {
            private final String s;
            private int i;

            Parser(String s) { this.s = s; }

            double parse() {
                double v = expr();
                skip();
                if (i != s.length()) throw new IllegalArgumentException(
                    "unexpected character at position " + i);
                return v;
            }

            private double expr() {
                double v = term();
                for (;;) {
                    skip();
                    if (match('+')) v += term();
                    else if (match('-')) v -= term();
                    else return v;
                }
            }

            private double term() {
                double v = factor();
                for (;;) {
                    skip();
                    if (match('*')) v *= factor();
                    else if (match('/')) v /= factor();
                    else return v;
                }
            }

            private double factor() {
                skip();
                if (match('(')) { double v = expr(); skip(); expect(')'); return v; }
                if (match('-')) return -factor();
                if (match('+')) return factor();
                int start = i;
                while (i < s.length()
                    && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
                if (start == i) throw new IllegalArgumentException(
                    "expected number at position " + i);
                return Double.parseDouble(s.substring(start, i));
            }

            private void skip() {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            }

            private boolean match(char c) {
                if (i < s.length() && s.charAt(i) == c) { i++; return true; }
                return false;
            }

            private void expect(char c) {
                if (!match(c)) throw new IllegalArgumentException(
                    "expected '" + c + "' at position " + i);
            }
        }
    }

    /**
     * The final-answer tool. The agent calls this exactly once, with ONLY
     * the answer — a short string, a number, or a comma-separated list, no
     * explanation, no preamble, no quotes. Invoking it is terminal: the run
     * ends immediately and the passed answer is committed as the run's
     * output (wired via {@code withTerminalTools("answer")}).
     *
     * <p>Why a tool instead of chat text: the first real GAIA run showed the
     * model computing the right value and then rambling around it in prose
     * (or answering twice with different magnitudes), failing the
     * quasi-exact-match scorer on formatting rather than reasoning.
     * Committing through a tool forces one clean answer.
     */
    public static final class Final {
        @Tool(name = "answer",
              description = "Submit your final answer and END the run. Call this "
                  + "exactly once, with ONLY the answer: a short string, a number, "
                  + "or a comma-separated list. No explanation, no preamble, no "
                  + "quotes around it. Do NOT write the answer in chat text "
                  + "instead — the run's score is whatever you pass here.",
              capabilities = {},
              idempotent = true)
        public String answer(
                @ToolParam(description = "The final answer, exactly as it should be scored",
                           example = "17") String answer) {
            return "Answer recorded: " + answer;
        }
    }
}
