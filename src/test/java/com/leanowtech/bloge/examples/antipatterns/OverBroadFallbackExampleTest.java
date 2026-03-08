package com.leanowtech.bloge.examples.antipatterns;

import com.leanowtech.bloge.core.exception.NonRetryableException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OverBroadFallbackExampleTest {

    @Test
    void badGraph_masksValidationFailure() {
        var result = OverBroadFallbackExample.executeBadScenario("validation");

        assertTrue(result.isSuccess());
        assertEquals("manual-review",
                result.getOutput("authorizePayment", OverBroadFallbackExample.SettlementResult.class).status());
        assertTrue(result.errors().isEmpty());
    }

    @Test
    void correctedGraph_surfacesValidationFailure() {
        var result = OverBroadFallbackExample.executeCorrectedScenario("validation");

        assertFalse(result.isSuccess());
        assertEquals(1, result.errors().size());
        assertInstanceOf(NonRetryableException.class, result.errors().get(0).exception());
    }

    @Test
    void correctedGraph_stillFallsBackForTransientFailure() {
        var result = OverBroadFallbackExample.executeCorrectedScenario("timeout");

        assertTrue(result.isSuccess());
        assertEquals("manual-review",
                result.getOutput("authorizePayment", OverBroadFallbackExample.SettlementResult.class).status());
    }
}
