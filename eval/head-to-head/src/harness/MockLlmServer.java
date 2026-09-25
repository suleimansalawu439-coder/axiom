package harness;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Minimal raw-socket HTTP/1.1 mock of an OpenAI-compatible endpoint.
 *
 * Why raw sockets instead of com.sun.net.httpserver: the JDK HTTP client
 * used by both Axiom and LangChain4j sends `Upgrade: h2c` (HTTP/2
 * cleartext) on first contact, and com.sun.net.httpserver resets such
 * connections instead of answering them. A raw socket server that simply
 * ignores the upgrade headers and answers plain HTTP/1.1 behaves like
 * the real-world servers that don't speak h2c.
 *
 * Modes (one per server instance, path is ignored):
 *   sse-garbage      -> 200 text/event-stream: one valid chunk, then
 *                         `data: THIS IS NOT JSON`, then `data: [DONE]`
 *   429-quota        -> 429 + `Retry-After: 30` + quota-exhausted body,
 *                         on EVERY request
 *   429-once-then-ok -> first request: 429 + `Retry-After: 2` (plain
 *                         rate-limit body); later requests: 200 with a
 *                         minimal chat.completion JSON body
 */
public class MockLlmServer {

    private final ServerSocket socket;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final String mode;
    private final AtomicInteger attempts = new AtomicInteger();

    private MockLlmServer(String mode) throws Exception {
        this.mode = mode;
        // Bind the wildcard address, NOT 127.0.0.1: the JVM opens
        // AF_INET6 dual-stack sockets and connects to ::ffff:127.0.0.1,
        // which this sandbox RSTs against an IPv4-only localhost socket
        // (curl/python use plain IPv4 and were unaffected). A wildcard
        // bind accepts both families.
        this.socket = new ServerSocket(0, 50, null);
        Thread acceptor = new Thread(() -> {
            try {
                while (!socket.isClosed()) {
                    Socket c = socket.accept();
                    pool.submit(() -> handleQuietly(c));
                }
            } catch (Exception ignored) {
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public static MockLlmServer start(String mode) throws Exception {
        return new MockLlmServer(mode);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public int attempts() {
        return attempts.get();
    }

    public void stop() {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
        pool.shutdownNow();
    }

    private void handleQuietly(Socket c) {
        try (c) {
            attempts.incrementAndGet();
            System.err.println("[mock] accept from " + c.getRemoteSocketAddress() + " mode=" + mode);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            // read until end of headers (\r\n\r\n); headers are tiny
            int b, match = 0;
            byte[] tail = "\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
            while ((b = in.read()) != -1) {
                head.write(b);
                if (b == tail[match]) {
                    if (++match == tail.length) break;
                } else {
                    match = (b == tail[0]) ? 1 : 0;
                }
                if (head.size() > 65536) break;
            }
            String headers = head.toString(StandardCharsets.US_ASCII);
            int contentLength = 0;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            // discard the request body so pipelined clients never stall
            long remaining = contentLength;
            byte[] buf = new byte[8192];
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                remaining -= n;
            }

            int n = attempts.get();
            int status; String reason; String contentType; String extraHeaders = ""; String body;
            switch (mode) {
                case "sse-garbage" -> {
                    status = 200; reason = "OK"; contentType = "text/event-stream";
                    body = "data: {\"id\":\"1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"m\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}\n\n"
                            + "data: THIS IS NOT JSON\n\n"
                            + "data: [DONE]\n\n";
                }
                case "429-quota" -> {
                    status = 429; reason = "Too Many Requests"; contentType = "application/json";
                    extraHeaders = "Retry-After: 30\r\n";
                    body = "{\"error\":{\"message\":\"You exceeded your current quota, please check your plan and billing details.\",\"code\":429}}";
                }
                case "429-once-then-ok" -> {
                    if (n == 1) {
                        status = 429; reason = "Too Many Requests"; contentType = "application/json";
                        extraHeaders = "Retry-After: 2\r\n";
                        body = "{\"error\":{\"message\":\"Rate limit reached, slow down.\",\"code\":429}}";
                    } else {
                        status = 200; reason = "OK"; contentType = "application/json";
                        body = "{\"id\":\"1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
                                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                                + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";
                    }
                }
                default -> throw new IllegalArgumentException("unknown mode: " + mode);
            }
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            String responseHead = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + bodyBytes.length + "\r\n"
                    + extraHeaders
                    + "Connection: close\r\n\r\n";
            OutputStream out = c.getOutputStream();
            out.write(responseHead.getBytes(StandardCharsets.US_ASCII));
            out.write(bodyBytes);
            out.flush();
            System.err.println("[mock] responded " + status + " (" + bodyBytes.length + " bytes) to " + c.getRemoteSocketAddress());
        } catch (Exception e) {
            System.err.println("[mock] handler exception: " + e);
            e.printStackTrace(System.err);
        }
    }
}
