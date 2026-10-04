package dev.axiom.bench.gaia;

import dev.axiom.capabilities.Capability;
import dev.axiom.llm.ProxyConfig;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * A {@code @Tool} holder that fetches a URL over plain HTTP(S) and returns
 * its text content. No browser, no JavaScript: this is a static fetch for
 * GAIA-style tasks whose answers live on ordinary web pages.
 *
 * <p>HTML is stripped to visible text (scripts/styles removed); output is
 * truncated to a bounded size so a huge page can't blow up the model's
 * context. The default cap is 8,000 characters, overridable with
 * {@code -Daxiom.webfetch.maxChars=N}; truncation is marked with how many
 * characters were omitted so the agent knows content was cut.
 * Declared {@code idempotent} — fetching is a pure read.
 */
public final class WebFetchTool {

    private static final int MAX_BYTES = 200_000;

    /** Default cap on returned text; override with -Daxiom.webfetch.maxChars=N. */
    static final int DEFAULT_MAX_TEXT_CHARS = 8_000;
    static final String MAX_CHARS_PROPERTY = "axiom.webfetch.maxChars";

    /** Effective text cap: the system property override, or the default. */
    static int maxTextChars() {
        String v = System.getProperty(MAX_CHARS_PROPERTY);
        if (v != null) {
            try {
                int n = Integer.parseInt(v.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignored) {
                // fall through to the default
            }
        }
        return DEFAULT_MAX_TEXT_CHARS;
    }

    /**
     * Truncate to the effective cap, marking how many characters were
     * omitted. Package-visible for unit tests.
     */
    static String truncateToCap(String text) {
        int cap = maxTextChars();
        if (text.length() <= cap) return text;
        int omitted = text.length() - cap;
        return text.substring(0, cap) + "[truncated: " + omitted + " chars omitted]";
    }

    private static final Pattern SCRIPT_STYLE =
        Pattern.compile("(?is)<(script|style)[^>]*>.*?</\\1>");
    private static final Pattern BLOCKS =
        Pattern.compile("(?is)<(br|p|div|li|h[1-6]|tr|blockquote|section|article|header|footer|hr)[^>]*>");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    /** Horizontal whitespace only — newlines are preserved by htmlToText. */
    private static final Pattern HWS = Pattern.compile("[ \\t\\x0B\\f\\r]+");

    private final HttpClient http;

    /** Maximum redirects to follow; each hop is SSRF-validated. */
    private static final int MAX_REDIRECTS = 5;

    public WebFetchTool() {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            // NEVER follow redirects automatically: each hop must pass
            // SSRF validation. Redirects are handled manually in fetch().
            .followRedirects(HttpClient.Redirect.NEVER);
        ProxyConfig.configureClient(builder);
        this.http = builder.build();
    }

    @Tool(name = "web_fetch",
          description = "Fetch a web page over HTTP(S) and return its visible "
              + "text content (HTML tags stripped). Use ONLY when you already "
              + "have the exact URL. If you need to FIND a page, use web_search "
              + "first — Do NOT guess URLs. No JavaScript is executed.",
          capabilities = {Capability.NETWORK},
          idempotent = true,
          timeoutSeconds = 45)
    public String fetch(
            @ToolParam(description = "http(s) URL to fetch",
                       example = "https://en.wikipedia.org/wiki/Abuja") String url) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return "ERROR: not a valid URI: " + url;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return "ERROR: only http(s) URLs are allowed: " + url;
        }
        // SSRF guard: validate the initial destination.
        String blocked = SsrfGuard.validate(uri);
        if (blocked != null) {
            return "ERROR: blocked by SSRF guard: " + blocked;
        }
        try {
            // Manual redirect handling: each hop is SSRF-validated.
            URI current = uri;
            HttpResponse<byte[]> res = null;
            for (int i = 0; i <= MAX_REDIRECTS; i++) {
                HttpRequest req = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "axiom-bench/0.12.0 (+benchmark harness)")
                    .GET()
                    .build();
                res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int status = res.statusCode();
                if (status >= 300 && status < 400) {
                    if (i == MAX_REDIRECTS) {
                        return "ERROR: too many redirects fetching " + url;
                    }
                    String loc = res.headers().firstValue("location").orElse(null);
                    if (loc == null) {
                        return "ERROR: redirect without location fetching " + url;
                    }
                    URI next = current.resolve(loc);
                    String nextScheme = next.getScheme();
                    if (!"http".equalsIgnoreCase(nextScheme)
                            && !"https".equalsIgnoreCase(nextScheme)) {
                        return "ERROR: redirect to non-http(s) URL blocked: " + loc;
                    }
                    String hopBlocked = SsrfGuard.validate(next);
                    if (hopBlocked != null) {
                        return "ERROR: redirect blocked by SSRF guard: " + hopBlocked;
                    }
                    current = next;
                    continue;
                }
                break;
            }
            if (res == null) {
                return "ERROR: no response fetching " + url;
            }
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                return "ERROR: HTTP " + res.statusCode() + " fetching " + url;
            }
            byte[] body = res.body();
            if (body.length > MAX_BYTES) {
                body = java.util.Arrays.copyOf(body, MAX_BYTES);
            }
            String contentType = res.headers()
                .firstValue("content-type").orElse("").toLowerCase();
            String text;
            if (contentType.contains("html") || looksLikeHtml(body)) {
                text = htmlToText(new String(body, java.nio.charset.StandardCharsets.UTF_8));
            } else {
                text = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            }
            // NOTE: no whitespace collapsing here — htmlToText already
            // normalizes while preserving line breaks and indentation,
            // which carry signal (e.g. poem stanza layout).
            text = truncateToCap(text.strip());
            return text.isEmpty() ? "ERROR: no text content at " + url : text;
        } catch (Exception e) {
            return "ERROR: fetch failed for " + url + ": " + e;
        }
    }

    private static boolean looksLikeHtml(byte[] body) {
        int n = Math.min(body.length, 512);
        String head = new String(body, 0, n, java.nio.charset.StandardCharsets.UTF_8)
            .toLowerCase();
        return head.contains("<html") || head.contains("<!doctype html");
    }

    /** Strip scripts/styles/tags; keep crude but predictable text.
     *
     * <p>Line structure is preserved: block elements become newlines and
     * leading whitespace (indentation) survives. This matters for tasks
     * whose answer lives in the page's visual layout (e.g. which stanza
     * of a poem has indented lines) — collapsing everything to one line
     * would destroy the signal. */
    static String htmlToText(String html) {
        String t = SCRIPT_STYLE.matcher(html).replaceAll("\n");
        t = BLOCKS.matcher(t).replaceAll("\n");
        t = TAGS.matcher(t).replaceAll(" ");
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'")
             .replace("&#x27;", "'").replace("&#x60;", "`").replace("&nbsp;", " ");
        // Collapse horizontal whitespace per line, but keep newlines and
        // leading indentation; squeeze runs of blank lines to one.
        String[] lines = t.split("\n");
        StringBuilder sb = new StringBuilder();
        boolean prevBlank = true; // suppress leading blank lines
        for (String line : lines) {
            int i = 0;
            while (i < line.length()
                && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
            String indent = line.substring(0, i);
            String rest = HWS.matcher(line.substring(i)).replaceAll(" ").strip();
            if (rest.isEmpty()) {
                if (!prevBlank) sb.append('\n');
                prevBlank = true;
            } else {
                sb.append(indent).append(rest).append('\n');
                prevBlank = false;
            }
        }
        return sb.toString().strip();
    }
}
