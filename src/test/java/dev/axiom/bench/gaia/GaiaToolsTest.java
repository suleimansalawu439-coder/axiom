package dev.axiom.bench.gaia;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GaiaToolsTest {

    @Test
    void calcBasicArithmetic() {
        var calc = new GaiaTools.Calc();
        assertEquals("396", calc.calculate("17 * 23 + 5"));
        assertEquals("50", calc.calculate("6 * 7 + 8"));
        assertEquals("10", calc.calculate("(17 * 23 + 5) / 39.6"));
    }

    @Test
    void calcDecimalsAndNegatives() {
        var calc = new GaiaTools.Calc();
        assertEquals("2.5", calc.calculate("5 / 2"));
        assertEquals("-3", calc.calculate("-(1 + 2)"));
        assertEquals("7", calc.calculate("  3.5  *  2 "));
    }

    @Test
    void calcBadExpressionIsErrorObservation() {
        var calc = new GaiaTools.Calc();
        assertTrue(calc.calculate("2 +").startsWith("ERROR:"));
        assertTrue(calc.calculate("hello").startsWith("ERROR:"));
    }

    @Test
    void filesReadAndList() throws Exception {
        Path dir = Files.createTempDirectory("gaia-tools-test");
        Files.writeString(dir.resolve("data.txt"), "hello world");
        var files = new GaiaTools.Files(dir);
        assertEquals("hello world", files.readFile("data.txt"));
        assertEquals("data.txt", files.listFiles().strip());
        assertTrue(files.readFile("missing.txt").startsWith("ERROR:"));
        assertTrue(files.readFile("../escape.txt").startsWith("ERROR:"));
    }

    @Test
    void filesBinaryIsErrorObservation() throws Exception {
        Path dir = Files.createTempDirectory("gaia-tools-test-bin");
        byte[] bin = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF};
        Files.write(dir.resolve("blob.bin"), bin);
        var files = new GaiaTools.Files(dir);
        assertTrue(files.readFile("blob.bin").startsWith("ERROR:"));
    }
}
