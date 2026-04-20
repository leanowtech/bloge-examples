package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.NodeStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorHandlingShowcaseExampleTest {

    GraphResult executeFailureScenario() {
        return ErrorHandlingShowcaseExample.executeFailureScenario("ORDER-100", 49.9);
    }

    GraphResult executeFallbackScenario() {
        return ErrorHandlingShowcaseExample.executeFallbackScenario("ORDER-100", 49.9);
    }

    GraphResult executeDslScenario() {
        return ErrorHandlingShowcaseDslExample.execute("ORDER-100", 49.9, true);
    }

    @Test
    void strictGraph_surfacesErrors() {
        GraphResult result = executeFailureScenario();

        assertFalse(result.isSuccess());
        assertEquals(NodeStatus.FAILED, result.getStatus("chargePayment"));
        assertEquals(1, result.errors().size());
        assertEquals("chargePayment", result.errors().get(0).nodeId());
        assertTrue(result.errors().get(0).exception().getMessage().contains("rejected"));
    }

    @Test
    void fallbackGraph_returnsManualReviewSummary() {
        GraphResult result = executeFallbackScenario();
        ErrorHandlingShowcaseExample.OutcomeSummary summary =
                result.getOutput("summarizeOutcome", ErrorHandlingShowcaseExample.OutcomeSummary.class);

        assertTrue(result.isSuccess());
        assertEquals(NodeStatus.COMPLETED, result.getStatus("chargePayment"));
        assertEquals("ORDER-100", summary.orderId());
        assertFalse(summary.approved());
        assertEquals("Gateway unavailable; queued for manual review", summary.resolution());
    }

    @Test
    void dslGraph_usesFallbackForSameFailure() {
        GraphResult result = executeDslScenario();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.results().getRaw("summarizeOutcome");

        assertTrue(result.isSuccess());
        assertEquals(NodeStatus.COMPLETED, result.getStatus("chargePayment"));
        assertEquals(Boolean.FALSE, summary.get("approved"));
        assertEquals("Gateway unavailable; queued for manual review", summary.get("resolution"));
    }
}
