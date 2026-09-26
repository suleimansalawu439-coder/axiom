package dev.axiom.bench.gaia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.axiom.capabilities.Capability;
import dev.axiom.tools.Tool;
import dev.axiom.tools.ToolParam;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@code @Tool} holder for web search, free and keyless: the Wikipedia
 * search API first, DuckDuckGo's HTML endpoint as fallback. The first real
 * GAIA run showed the agent had no way to <i>find</i> pages — it guessed
 * URLs (usually wrong) or burned python3 calls scripting urllib against
 * the Wikipedia API. This closes that gap: search, then
 * {@code web_fetch} the best result.
 *
 * <p>Output is the top results as title / URL / snippet, truncated with the
 * same cap as {@link WebFetchTool} (see {@code -Daxiom.webfetch.maxChars}).
 * Declared {@code idempotent} — searching is a pure read.
 */
public final class WebSearchTool {

    /** Injectable HTTP for unit tests — the suite never hits the network. */
    public interface HttpGetter {
        String get(String url) throws Exception;
    }

    /** Results returned per search. */
    static final int MAX_RESULTS = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern DDG_LINK = Pattern.compile(
        "<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DDG_SNIPPET = Pattern.compile(
        "<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern WS = Pattern.compile("\\s+");

    private final HttpGetter http;

    public WebSearchTool() {
        this(WebSearchTool::defaultGet);
    }

    /** Test seam: supply canned HTTP responses. */
    public WebSearchTool(HttpGetter http) {
        this.http = http;
    }

    private static String defaultGet(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) axiom-bench/0.12.0")
            .GET()
            .build();
        HttpResponse<String> res =
            client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + url);
        }
        return res.body();
    }

    @Tool(name = "web_search",
          description = "Search the web for information. Use this FIRST whenever "
              + "a question needs facts from the web and you don't already have "
              + "a URL. Returns the top results as title, URL and snippet — "
              + "then use web_fetch on the most promising URL to read the full "
              + "page. Do NOT guess URLs: a guessed URL is usually wrong. "
              + "Wikipedia is searched first, then the general web.",
          capabilities = {Capability.NETWORK},
          idempotent = true,
          timeoutSeconds = 45)
    public String search(
            @ToolParam(description = "Search query: the key facts the question asks for",
                       example = "Eliud Kipchoge marathon world record time") String query) {
        if (query == null || query.isBlank()) {
            return "ERROR: empty search query";
        }
        try {
            List<Result> results = wikipediaSearch(query);
            String source = "Wikipedia";
            if (results.isEmpty()) {
                results = duckDuckGoSearch(query);
                source = "web";
            }
            if (results.isEmpty()) {
                return "No results for \"" + query.trim()
                    + "\". Try different keywords.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(source).append(" search for \"").append(query.trim())
              .append("\" — ").append(results.size()).append(" results:\n");
            int n = 1;
            for (Result r : results) {
                sb.append(n++).append(". ").append(r.title()).append('\n')
                  .append("   ").append(r.url()).append('\n');
                if (!r.snippet().isEmpty()) {
                    sb.append("   ").append(r.snippet()).append('\n');
                }
            }
            return WebFetchTool.truncateToCap(sb.toString().strip());
        } catch (Exception e) {
            return "ERROR: search failed for \"" + query.trim() + "\": " + e.getMessage();
        }
    }

    /** One search hit. Package-visible for unit tests. */
    record Result(String title, String url, String snippet) {}

    /** Wikipedia's keyless search API; empty list when nothing matches. */
    List<Result> wikipediaSearch(String query) throws Exception {
        String url = "https://en.wikipedia.org/w/api.php?action=query&list=search"
            + "&srsearch=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8)
            + "&format=json&srlimit=" + MAX_RESULTS;
        JsonNode root = MAPPER.readTree(http.get(url));
        JsonNode hits = root.path("query").path("search");
        List<Result> out = new ArrayList<>();
        if (hits.isArray()) {
            for (JsonNode h : hits) {
                if (out.size() >= MAX_RESULTS) break;
                String title = h.path("title").asText("");
                if (title.isEmpty()) continue;
                String pageUrl = "https://en.wikipedia.org/wiki/"
                    + title.replace(' ', '_');
                String snippet = cleanText(h.path("snippet").asText(""));
                out.add(new Result(title, pageUrl, snippet));
            }
        }
        return out;
    }

    /** DuckDuckGo's keyless HTML endpoint; empty list when nothing matches. */
    List<Result> duckDuckGoSearch(String query) throws Exception {
        String url = "https://html.duckduckgo.com/html/?q="
            + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
        String html = http.get(url);
        Matcher links = DDG_LINK.matcher(html);
        Matcher snippets = DDG_SNIPPET.matcher(html);
        List<Match> linkMatches = new ArrayList<>();
        while (links.find()) {
            linkMatches.add(new Match(links.start(), links.group(1), links.group(2)));
        }
        List<Match> snippetMatches = new ArrayList<>();
        while (snippets.find()) {
            snippetMatches.add(new Match(snippets.start(), null, snippets.group(1)));
        }
        List<Result> out = new ArrayList<>();
        for (Match link : linkMatches) {
            if (out.size() >= MAX_RESULTS) break;
            String target = unwrapDdgHref(link.href());
            if (target == null) continue;
            String title = cleanText(link.text());
            if (title.isEmpty()) continue;
            // Pair with the first snippet that appears after this link.
            String snippet = "";
            for (Match s : snippetMatches) {
                if (s.pos() > link.pos()) {
                    snippet = cleanText(s.text());
                    break;
                }
            }
            out.add(new Result(title, target, snippet));
        }
        return out;
    }

    private record Match(int pos, String href, String text) {}

    /**
     * DuckDuckGo wraps result URLs as {@code //duckduckgo.com/l/?uddg=<encoded>};
     * unwrap to the real target. Returns null when the href is unusable.
     */
    static String unwrapDdgHref(String href) {
        if (href == null || href.isBlank()) return null;
        String h = href.trim();
        int uddg = h.indexOf("uddg=");
        if (uddg >= 0) {
            String enc = h.substring(uddg + 5);
            int amp = enc.indexOf('&');
            if (amp >= 0) enc = enc.substring(0, amp);
            try {
                String decoded = URLDecoder.decode(enc, StandardCharsets.UTF_8);
                if (decoded.startsWith("http")) return decoded;
                return null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (h.startsWith("http")) return h;
        if (h.startsWith("//")) return "https:" + h;
        return null;
    }

    private static String cleanText(String html) {
        String t = TAGS.matcher(html == null ? "" : html).replaceAll(" ");
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
             .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
        return WS.matcher(t.strip()).replaceAll(" ");
    }
}
