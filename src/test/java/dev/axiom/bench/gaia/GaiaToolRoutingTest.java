package dev.axiom.bench.gaia;

import dev.axiom.tools.SubprocessTool;
import dev.axiom.tools.ToolDefinition;
import dev.axiom.tools.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tool descriptions must route the model unambiguously: the first real
 * GAIA run showed the agent reaching for python3 to do arithmetic and
 * guessing URLs instead of searching. These tests pin the routing
 * language in every tool description.
 */
class GaiaToolRoutingTest {

    private static ToolRegistry gaiaRegistry() {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        return new ToolRegistry()
            .register(new GaiaTools.Files(root))
            .register(new GaiaTools.Calc())
            .register(new GaiaTools.Final())
            .register(new WebFetchTool())
            .register(new WebSearchTool())
            .register(SubprocessTool.builder(root)
                .allowCommands("python3", "calculate")
                .build());
    }

    private static String description(String toolName) {
        ToolDefinition def = gaiaRegistry().find(toolName)
            .orElseThrow(() -> new AssertionError("tool not registered: " + toolName));
        return def.description();
    }

    @Test
    void calculateClaimsAllArithmetic() {
        String d = description("calculate");
        assertTrue(d.contains("ALL arithmetic"), d);
        assertTrue(d.contains("Do NOT"), d);
        assertTrue(d.contains("run tool"), d);
    }

    @Test
    void runToolDeflectsArithmetic() {
        String d = description("run");
        assertTrue(d.contains("Do NOT use this for arithmetic"), d);
        assertTrue(d.toLowerCase().contains("calculator"), d);
    }

    @Test
    void webSearchIsFirstResortAndForbidsUrlGuessing() {
        String d = description("web_search");
        assertTrue(d.contains("FIRST"), d);
        assertTrue(d.contains("Do NOT guess URLs"), d);
    }

    @Test
    void webFetchRequiresKnownUrl() {
        String d = description("web_fetch");
        assertTrue(d.contains("ONLY when you already"), d);
        assertTrue(d.contains("web_search"), d);
        assertTrue(d.contains("Do NOT guess URLs"), d);
    }

    @Test
    void answerToolIsTerminalAndExclusive() {
        String d = description("answer");
        assertTrue(d.contains("END the run"), d);
        assertTrue(d.contains("exactly once"), d);
        assertTrue(d.contains("Do NOT write the answer in chat text"), d);
    }
}
