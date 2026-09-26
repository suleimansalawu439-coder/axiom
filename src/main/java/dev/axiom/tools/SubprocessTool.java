package dev.axiom.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * A {@code @Tool} holder that executes subprocesses inside a sandbox:
 *
 * <ul>
 *   <li><b>No shell.</b> The command is an argv list passed straight to
 *       {@code execve} — {@code sh -c} is never involved, so shell
 *       metacharacters are inert data, not code.</li>
 *   <li><b>Working-directory confinement.</b> The process runs with its cwd
 *       inside {@code root}; an executable path containing separators must
 *       resolve inside {@code root} (path traversal is rejected).</li>
 *   <li><b>Environment allowlist.</b> The child inherits only allowlisted
 *       variables — secrets in the parent environment never leak in.</li>
 *   <li><b>Optional command allowlist.</b> Restrict which executables may run
 *       at all (e.g. only {@code git}, {@code ls}).</li>
 *   <li><b>Capture + kill.</b> Stdout/stderr are captured (bounded) and the
 *       process is forcibly destroyed on timeout.</li>
 *   <li><b>Windows Python-shim fail-fast.</b> On Windows boxes without a
 *       real Python, {@code python3}/{@code python} resolve to the
 *       install-manager stub, which hangs instead of running scripts.
 *       Its stderr signatures are detected while the process runs and
 *       the call is aborted immediately with a diagnostic — the full
 *       timeout is never waited out.</li>
 * </ul>
 *
 * <pre>{@code
 * var shell = SubprocessTool.builder(Path.of("/tmp/agent-workspace"))
 *     .allowCommands("ls", "cat", "git", "python3")
 *     .timeout(Duration.ofSeconds(30))
 *     .build();
 *
 * var agent = Axiom.agent().withModel("gpt-4o")
 *     .withTools(shell)   // exposes a single "run" tool
 *     .buildAgent();
 * }</pre>
 */
public final class SubprocessTool {

    /** Structured result of one subprocess execution. */
    public record ExecResult(int exitCode, String stdout, String stderr,
                             boolean timedOut, long durationMs) {
        public boolean succeeded() {
            return !timedOut && exitCode == 0;
        }
    }

    private static final Set<String> DEFAULT_ENV = Set.of(
        "PATH", "HOME", "LANG", "LC_ALL", "LC_CTYPE", "TMPDIR", "TEMP", "TMP", "USER");

    /**
     * Substrings (lowercase) identifying the Windows Python install-manager
     * shim — the "App execution alias" stub that opens a downloader instead
     * of running scripts when no real Python is installed. Seen in the wild:
     * "[WARNING] Failed to read unmanaged installs: ...", "Waiting for other
     * operations to complete. . .", "Python install manager was successfully
     * updated to ...".
     */
    private static final List<String> PYTHON_SHIM_SIGNATURES = List.of(
        "python install manager",
        "python was not found",
        "ms-windows-store",
        "app execution alias",
        "failed to read unmanaged installs",
        "waiting for other operations to complete");

    /**
     * Returns the first known Windows-Python-shim signature found in
     * {@code output} (case-insensitive), or {@code null} if none matches.
     * Package-visible for unit tests — the live path can only be exercised
     * against a real shim, which the test suite must not depend on.
     */
    static String findPythonShimSignature(String output) {
        if (output == null || output.isEmpty()) return null;
        String lower = output.toLowerCase(java.util.Locale.ROOT);
        for (String sig : PYTHON_SHIM_SIGNATURES) {
            if (lower.contains(sig)) return sig;
        }
        return null;
    }

    /** True for executable names that may resolve to the Windows Python shim. */
    static boolean isPythonExecutable(String exeName) {
        if (exeName == null) return false;
        String n = exeName.toLowerCase(java.util.Locale.ROOT);
        if (n.endsWith(".exe")) n = n.substring(0, n.length() - 4);
        return n.equals("python") || n.equals("pythonw") || n.startsWith("python3");
    }

    /** Fail-fast diagnostic shown to the agent when the shim is detected. */
    static String pythonShimDiagnostic(String exeName, String signature) {
        return "ERROR: '" + exeName + "' on this machine is the Windows Python "
            + "install-manager shim, not a real Python interpreter — it opens a "
            + "downloader instead of running scripts, so the call was aborted "
            + "instead of hanging on the timeout. Install a real Python 3 from "
            + "https://www.python.org/downloads/ and make sure '" + exeName + "' "
            + "is on PATH, then retry. "
            + "(Detected shim output: \"" + signature + "\".)";
    }

    private final Path root;
    private final Set<String> allowedEnv;
    private final Set<String> allowedCommands;
    private final Duration timeout;
    private final long maxOutputBytes;

    private SubprocessTool(Builder b) {
        this.root = b.root.toAbsolutePath().normalize();
        this.allowedEnv = Set.copyOf(b.allowedEnv);
        this.allowedCommands = Set.copyOf(b.allowedCommands);
        this.timeout = b.timeout;
        this.maxOutputBytes = b.maxOutputBytes;
    }

    /**
     * Run a command. The executable allowlist, path confinement, and env
     * allowlist are enforced before the process starts; violations raise
     * {@link SecurityException}, which the agent reports back to the LLM.
     */
    @Tool(description = "Run a command in the sandboxed workspace. No shell is used: "
        + "pass the command as a list of arguments, e.g. [\"ls\", \"-la\"]. "
        + "Stdout/stderr are captured and returned; long-running commands are killed on timeout.",
        requiresApproval = true, timeoutSeconds = 120)
    public ExecResult run(
            @ToolParam(description = "Command and arguments, e.g. [\"ls\", \"-la\"]. "
                + "The first element is the executable; shell syntax is NOT interpreted.",
                example = "[\"ls\", \"-la\"]")
            List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must be a non-empty argument list");
        }
        List<String> argv = new ArrayList<>(command);
        String exe = argv.get(0);
        if (exe == null || exe.isBlank()) {
            throw new IllegalArgumentException("command executable must not be blank");
        }

        // 1. Command allowlist (matched on the executable's file name).
        String exeName = Path.of(exe).getFileName().toString();
        if (!allowedCommands.isEmpty() && !allowedCommands.contains(exeName)) {
            throw new SecurityException(
                "Command '%s' is not on the allowlist: %s".formatted(exeName, allowedCommands));
        }

        // 2. Path confinement: executables with separators must stay inside root.
        if (exe.contains("/") || exe.contains("\\")) {
            Path resolved = root.resolve(exe).normalize();
            if (!resolved.startsWith(root)) {
                throw new SecurityException(
                    "Executable escapes the sandbox root: " + exe);
            }
            argv.set(0, resolved.toString());
        }

        // 3. Scrubbed environment.
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.directory(root.toFile());
        Map<String, String> env = pb.environment();
        Map<String, String> parent = System.getenv();
        env.clear();
        for (String key : allowedEnv) {
            String value = parent.get(key);
            if (value != null) env.put(key, value);
        }

        long start = System.currentTimeMillis();
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start command " + argv + ": " + e.getMessage(), e);
        }

        BoundedCapture out = new BoundedCapture(process.getInputStream(), maxOutputBytes);
        BoundedCapture err = new BoundedCapture(process.getErrorStream(), maxOutputBytes);
        Thread t1 = Thread.ofPlatform().daemon().start(out);
        Thread t2 = Thread.ofPlatform().daemon().start(err);
        // Poll for completion so the Windows Python shim (which prints its
        // install-manager spam to stdout/stderr, then hangs) can be detected
        // and aborted long before the timeout expires. Only python
        // executables are eligible — the signatures are shim-specific.
        boolean pythonExe = isPythonExecutable(exeName);
        long deadline = start + timeout.toMillis();
        boolean completed = false;
        boolean shimmed = false;
        String shimSignature = null;
        boolean timedOut = false;
        int exitCode = -1;
        try {
            while (System.currentTimeMillis() < deadline) {
                if (pythonExe) {
                    String sig = findPythonShimSignature(
                        out.snapshot() + "\n" + err.snapshot());
                    if (sig != null) {
                        shimmed = true;
                        shimSignature = sig;
                        break;
                    }
                }
                if (process.waitFor(250, TimeUnit.MILLISECONDS)) {
                    completed = true;
                    exitCode = process.exitValue();
                    break;
                }
            }
            if (shimmed) {
                // Fail fast: kill the stub and report a diagnostic instead of
                // waiting out the timeout and feeding shim spam to the model.
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                t1.join(5000);
                t2.join(5000);
                return new ExecResult(127, out.text(),
                    pythonShimDiagnostic(exeName, shimSignature),
                    false, System.currentTimeMillis() - start);
            }
            boolean timedOutNow = !completed;
            if (timedOutNow) {
                process.destroyForcibly();
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                exitCode = -1;
                timedOut = true;
            }
            t1.join(5000);
            t2.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IllegalStateException("Command execution interrupted: " + argv, e);
        }
        return new ExecResult(exitCode, out.text(), err.text(), timedOut,
            System.currentTimeMillis() - start);
    }

    /** Read a stream fully, keeping at most {@code maxBytes} (rest is noted as truncated). */
    private static final class BoundedCapture implements Runnable {
        private final InputStream in;
        private final long maxBytes;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private boolean truncated = false;

        BoundedCapture(InputStream in, long maxBytes) {
            this.in = in;
            this.maxBytes = maxBytes;
        }

        @Override
        public void run() {
            try (in) {
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    synchronized (this) {
                        int room = (int) Math.max(0, maxBytes - buf.size());
                        if (room == 0) {
                            truncated = true;
                            continue;
                        }
                        buf.write(chunk, 0, Math.min(n, room));
                        if (n > room) truncated = true;
                    }
                }
            } catch (IOException ignored) {
            }
        }

        /** Thread-safe snapshot of what's been captured so far. */
        synchronized String snapshot() {
            return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
        }

        String text() {
            String s = snapshot();
            return truncated ? s + "\n[output truncated at " + maxBytes + " bytes]" : s;
        }
    }

    public static Builder builder(Path workingDirectoryRoot) {
        return new Builder(workingDirectoryRoot);
    }

    public static final class Builder {
        private final Path root;
        private Set<String> allowedEnv = new HashSet<>(DEFAULT_ENV);
        private Set<String> allowedCommands = new HashSet<>();
        private Duration timeout = Duration.ofSeconds(60);
        private long maxOutputBytes = 64 * 1024;

        private Builder(Path root) {
            this.root = Objects.requireNonNull(root);
        }

        /** Only these executable names may run; empty (default) allows all. */
        public Builder allowCommands(String... names) {
            this.allowedCommands = new HashSet<>(Arrays.asList(names));
            return this;
        }

        /** Add variables to the default environment allowlist. */
        public Builder allowEnv(String... names) {
            this.allowedEnv.addAll(Arrays.asList(names));
            return this;
        }

        /** Replace the environment allowlist entirely. */
        public Builder envAllowlist(Set<String> names) {
            this.allowedEnv = new HashSet<>(names);
            return this;
        }

        /** Kill the process after this long. */
        public Builder timeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero())
                throw new IllegalArgumentException("timeout must be positive");
            this.timeout = timeout;
            return this;
        }

        /** Cap captured stdout/stderr each at this many bytes. */
        public Builder maxOutputBytes(long maxOutputBytes) {
            if (maxOutputBytes < 1) throw new IllegalArgumentException("maxOutputBytes must be >= 1");
            this.maxOutputBytes = maxOutputBytes;
            return this;
        }

        public SubprocessTool build() {
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create sandbox root " + root, e);
            }
            if (!Files.isDirectory(root)) {
                throw new IllegalArgumentException("Sandbox root is not a directory: " + root);
            }
            return new SubprocessTool(this);
        }
    }
}
