package com.leanowtech.bloge.graphengine.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceDifferTest {

    @Test
    void identicalSourcesProduceEmptyDiff() {
        String source = "graph test {\n  node echo : echo {}\n}\n";
        List<String> diff = SourceDiffer.unifiedDiff(source, source, "1.0.0", "2.0.0");
        assertTrue(diff.isEmpty());
    }

    @Test
    void singleLineChangeProducesUnifiedDiff() {
        String left = "graph test {\n  node echo : echo {}\n}\n";
        String right = "graph test {\n  node step : echo {}\n}\n";

        List<String> diff = SourceDiffer.unifiedDiff(left, right, "1.0.0", "2.0.0");

        assertEquals("--- 1.0.0", diff.getFirst());
        assertEquals("+++ 2.0.0", diff.get(1));
        assertTrue(diff.stream().anyMatch(line -> line.startsWith("@@")));
        assertTrue(diff.stream().anyMatch(line -> line.equals("-  node echo : echo {}")));
        assertTrue(diff.stream().anyMatch(line -> line.equals("+  node step : echo {}")));
    }

    @Test
    void addedLinesAppearAsPlusLines() {
        String left = "line1\nline2\n";
        String right = "line1\nline2\nline3\n";

        List<String> diff = SourceDiffer.unifiedDiff(left, right, "a", "b");

        assertTrue(diff.stream().anyMatch(line -> line.equals("+line3")));
    }

    @Test
    void removedLinesAppearAsMinusLines() {
        String left = "line1\nline2\nline3\n";
        String right = "line1\nline2\n";

        List<String> diff = SourceDiffer.unifiedDiff(left, right, "a", "b");

        assertTrue(diff.stream().anyMatch(line -> line.equals("-line3")));
    }

    @Test
    void contextLinesArePrefixedWithSpace() {
        String left = "line1\nline2\nline3\n";
        String right = "line1\nchanged\nline3\n";

        List<String> diff = SourceDiffer.unifiedDiff(left, right, "a", "b");

        assertTrue(diff.stream().anyMatch(line -> line.equals(" line1")));
        assertTrue(diff.stream().anyMatch(line -> line.equals(" line3")));
        assertTrue(diff.stream().anyMatch(line -> line.equals("-line2")));
        assertTrue(diff.stream().anyMatch(line -> line.equals("+changed")));
    }
}
