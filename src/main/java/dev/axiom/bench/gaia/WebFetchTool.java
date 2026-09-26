package dev.axiom.bench.gaia;

import dev.axiom.capabilities.Capability;
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
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern WS = Pattern.compile("\\s+");

    private final HttpClient http;

    public WebFetchTool() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
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
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "axiom-bench/0.12.0 (+benchmark harness)")
                .GET()
                .build();
            HttpResponse<byte[]> res =
                http.send(req, HttpResponse.BodyHandlers.ofByteArray());
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
            text = WS.matcher(text.strip()).replaceAll(" ");
            text = truncateToCap(text);
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

    /** Strip scripts/styles/tags; keep crude but predictable text. */
    static String htmlToText(String html) {
        String t = SCRIPT_STYLE.matcher(html).replaceAll(" ");
        t = TAGS.matcher(t).replaceAll(" ");
        // crude entity decoding for the common cases
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        return t;
    }
}
