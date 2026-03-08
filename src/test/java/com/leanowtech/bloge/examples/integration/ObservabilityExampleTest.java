package com.leanowtech.bloge.examples.integration;

import com.leanowtech.bloge.examples.integration.observability.ObservabilityExample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that the manual observability example actually emits metrics.
 */
class ObservabilityExampleTest {

    @Test
    void observedScenarioEmitsGraphAndRetryMetrics() {
        var snapshot = ObservabilityExample.executeObservedScenario("ALERT-42", "critical", true);

        assertTrue(snapshot.success());
        assertEquals("vip-ops", snapshot.queue());
        assertEquals(1L, snapshot.graphExecutions());
        assertEquals(1L, snapshot.routeNodeExecutions());
        assertTrue(snapshot.retryCount() >= 1.0);
        assertTrue(snapshot.graphMeanDurationMs() >= 0.0);
    }
}
