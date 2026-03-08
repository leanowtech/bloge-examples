package com.leanowtech.bloge.examples.antipatterns;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircularDependencyExampleTest {

    @Test
    void linter_detectsCycle() {
        var diagnostics = CircularDependencyExample.diagnostics();

        assertFalse(diagnostics.isEmpty());
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.ruleId().equals("no-cycle")));
        assertTrue(diagnostics.stream().anyMatch(diagnostic -> diagnostic.message().contains("cycle")));
    }
}
