package dev.axiom.bench.gaia;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebFetchToolTest {

    @Test
    void htmlToTextStripsTagsAndScripts() {
        String html = "<html><head><script>evil()</script><style>.x{}</style></head>"
            + "<body><h1>Title</h1><p>Hello <b>world</b> &amp; friends</p></body></html>";
        String text = WebFetchTool.htmlToText(html);
        assertFalse(text.contains("<"));
        assertFalse(text.contains("evil()"));
        assertTrue(text.contains("Title"));
        assertTrue(text.contains("Hello"));
        assertTrue(text.contains("& friends"));
    }

    @Test
    void fetchRejectsNonHttp() {
        var tool = new WebFetchTool();
        assertTrue(tool.fetch("file:///etc/passwd").startsWith("ERROR:"));
        assertTrue(tool.fetch("not a url").startsWith("ERROR:"));
    }

    @Test
    void truncateAddsMarkerWithOmittedCount() {
        String text = "x".repeat(10_000);
        String out = WebFetchTool.truncateToCap(text);
        String marker = "[truncated: 2000 chars omitted]";
        assertTrue(out.endsWith(marker), "tail: " + out.substring(out.length() - 60));
        assertEquals(8_000 + marker.length(), out.length());
        assertEquals(text.substring(0, 8_000), out.substring(0, 8_000));
    }

    @Test
    void truncateLeavesShortTextUntouched() {
        assertEquals("hello", WebFetchTool.truncateToCap("hello"));
        String exact = "x".repeat(WebFetchTool.DEFAULT_MAX_TEXT_CHARS);
        assertEquals(exact, WebFetchTool.truncateToCap(exact));
    }

    @Test
    void maxCharsOverrideIsRespected() {
        System.setProperty(WebFetchTool.MAX_CHARS_PROPERTY, "100");
        try {
            assertEquals(100, WebFetchTool.maxTextChars());
            String out = WebFetchTool.truncateToCap("x".repeat(250));
            assertTrue(out.endsWith("[truncated: 150 chars omitted]"), out);
            assertEquals(100, out.indexOf("[truncated:"));
        } finally {
            System.clearProperty(WebFetchTool.MAX_CHARS_PROPERTY);
        }
        assertEquals(WebFetchTool.DEFAULT_MAX_TEXT_CHARS, WebFetchTool.maxTextChars());
    }

    @Test
    void invalidOverrideFallsBackToDefault() {
        for (String bad : new String[]{"bogus", "-5", "0", ""}) {
            System.setProperty(WebFetchTool.MAX_CHARS_PROPERTY, bad);
            try {
                assertEquals(WebFetchTool.DEFAULT_MAX_TEXT_CHARS,
                    WebFetchTool.maxTextChars(), "for value <" + bad + ">");
            } finally {
                System.clearProperty(WebFetchTool.MAX_CHARS_PROPERTY);
            }
        }
    }
}
