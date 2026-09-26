package dev.axiom.bench.gaia;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * web_search with stubbed HTTP — the suite never touches the network.
 */
class WebSearchToolTest {

    private static final String WIKI_JSON = """
        {"query":{"search":[
          {"title":"Eliud Kipchoge","snippet":"Kenyan <span class=\\"searchmatch\\">marathon</span> runner"},
          {"title":"Marathon world record progression","snippet":"list of records"}
        ]}}""";

    private static final String WIKI_EMPTY = """
        {"query":{"search":[]}}""";

    private static final String DDG_HTML = """
        <html><body>
        <div class="result">
          <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FMoon&amp;rut=abc">Moon - Wikipedia</a>
          <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">The Moon is Earth's only natural satellite.</a>
        </div>
        <div class="result">
          <a class="result__a" href="https://example.com/moon">Moon facts</a>
        </div>
        </body></html>""";

    @Test
    void wikipediaResultsParsed() {
        var tool = new WebSearchTool(url -> {
            assertTrue(url.contains("w/api.php"), "should hit the Wikipedia API: " + url);
            assertTrue(url.contains("srsearch=marathon"), "query must be encoded: " + url);
            return WIKI_JSON;
        });

        String out = tool.search("marathon");

        assertTrue(out.contains("Wikipedia search for"), out);
        assertTrue(out.contains("Eliud Kipchoge"), out);
        assertTrue(out.contains("https://en.wikipedia.org/wiki/Eliud_Kipchoge"), out);
        // Snippet HTML is stripped, not leaked.
        assertTrue(out.contains("Kenyan marathon runner"), out);
        assertFalse(out.contains("searchmatch"), out);
    }

    @Test
    void emptyWikipediaFallsBackToDuckDuckGo() {
        var tool = new WebSearchTool(url -> {
            if (url.contains("wikipedia.org/w/api.php")) return WIKI_EMPTY;
            assertTrue(url.contains("html.duckduckgo.com"), "should fall back to DDG: " + url);
            return DDG_HTML;
        });

        String out = tool.search("moon distance");

        assertTrue(out.contains("web search for"), out);
        // uddg-wrapped href is unwrapped to the real target.
        assertTrue(out.contains("https://en.wikipedia.org/wiki/Moon"), out);
        assertTrue(out.contains("Moon - Wikipedia"), out);
        assertTrue(out.contains("Earth's only natural satellite."), out);
        // Plain http href passes through.
        assertTrue(out.contains("https://example.com/moon"), out);
    }

    @Test
    void noResultsAnywhereReportsCleanly() {
        var tool = new WebSearchTool(url ->
            url.contains("wikipedia.org") ? WIKI_EMPTY : "<html></html>");

        String out = tool.search("zzzznothing");

        assertTrue(out.startsWith("No results for"), out);
    }

    @Test
    void httpFailureReportsError() {
        var tool = new WebSearchTool(url -> {
            throw new IllegalStateException("boom");
        });

        String out = tool.search("anything");

        assertTrue(out.startsWith("ERROR: search failed"), out);
    }

    @Test
    void blankQueryRejected() {
        var tool = new WebSearchTool(url -> {
            throw new AssertionError("must not hit the network");
        });

        assertTrue(tool.search("   ").startsWith("ERROR:"));
    }

    @Test
    void outputRespectsTruncationCap() {
        String bigSnippet = "s".repeat(50_000);
        var tool = new WebSearchTool(url ->
            "{\"query\":{\"search\":[{\"title\":\"T\",\"snippet\":\"" + bigSnippet + "\"}]}}");

        String out = tool.search("big");

        assertTrue(out.contains("[truncated:"), out);
        assertTrue(out.length() <= WebFetchTool.maxTextChars() + 100, "" + out.length());
    }

    @Test
    void unwrapDdgHrefCases() {
        assertEquals("https://en.wikipedia.org/wiki/Moon",
            WebSearchTool.unwrapDdgHref("//duckduckgo.com/l/?uddg=https%3A%2F%2Fen.wikipedia.org%2Fwiki%2FMoon&rut=abc"));
        assertEquals("https://example.com/x",
            WebSearchTool.unwrapDdgHref("https://example.com/x"));
        assertEquals("https://example.com/y",
            WebSearchTool.unwrapDdgHref("//example.com/y"));
        assertNull(WebSearchTool.unwrapDdgHref("/relative/path"));
        assertNull(WebSearchTool.unwrapDdgHref(""));
        assertNull(WebSearchTool.unwrapDdgHref(null));
    }

    @Test
    void wikipediaParsingIsDirectlyTestable() throws Exception {
        var tool = new WebSearchTool(url -> WIKI_JSON);
        List<WebSearchTool.Result> results = tool.wikipediaSearch("marathon");
        assertEquals(2, results.size());
        assertEquals("Eliud Kipchoge", results.get(0).title());
        assertEquals("https://en.wikipedia.org/wiki/Eliud_Kipchoge", results.get(0).url());
        assertEquals("Kenyan marathon runner", results.get(0).snippet());
    }

    @Test
    void duckDuckGoParsingIsDirectlyTestable() throws Exception {
        var tool = new WebSearchTool(url -> DDG_HTML);
        List<WebSearchTool.Result> results = tool.duckDuckGoSearch("moon");
        assertEquals(2, results.size());
        assertEquals("https://en.wikipedia.org/wiki/Moon", results.get(0).url());
        assertEquals("The Moon is Earth's only natural satellite.", results.get(0).snippet());
        // Second result has no snippet — still returned, snippet empty.
        assertEquals("https://example.com/moon", results.get(1).url());
        assertEquals("", results.get(1).snippet());
    }
}
