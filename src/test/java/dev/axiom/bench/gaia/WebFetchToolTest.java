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
}
