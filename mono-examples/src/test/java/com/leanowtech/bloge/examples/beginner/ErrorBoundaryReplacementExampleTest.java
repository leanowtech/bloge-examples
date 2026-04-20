package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.NodeStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the Error Boundary replacement pattern (evolution plan §3.2).
 *
 * <ul>
 *   <li>Strict graph: failure surfaces through {@code GraphResult.errors()}.</li>
 *   <li>Fallback-branch graph (failure): marker {@code {failed: true}} routes to manual review;
 *       the normal-success path is skipped.</li>
 *   <li>Fallback-branch graph (success): real output {@code {failed: false}} routes to normal
 *       success; the manual-review path is skipped.</li>
 *   <li>DSL graph: same fallback-branch behaviour compiled from {@code .bloge} resource.</li>
 * </ul>
 */
class ErrorBoundaryReplacementExampleTest {

    // --- Java API: strict graph ---

    @Test
    void strictGraph_failureSurfacesAsError() {
        GraphResult result = ErrorBoundaryReplacementExample.executeStrictFailure("EB-001", 49.9);

        assertFalse(result.isSuccess());
        assertEquals(NodeStatus.FAILED, result.getStatus("chargePayment"));
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).exception().getMessage().contains("rejected"));
    }

    // --- Java API: fallback-branch graph (failure path) ---

    @Test
    void fallbackBranch_failure_routesToManualReview() {
        GraphResult result = ErrorBoundaryReplacementExample.executeFallbackFailure("EB-002", 99.9);

        assertTrue(result.isSuccess(), "Graph should succeed because fallback supplies a value");
        assertEquals(NodeStatus.COMPLETED, result.getStatus("chargePayment"));

        // Manual review path executed
        ErrorBoundaryReplacementExample.ManualReviewSummary review =
                result.getOutput("manualReviewPath", ErrorBoundaryReplacementExample.ManualReviewSummary.class);
        assertNotNull(review, "Manual review path should have executed");
        assertEquals("EB-002", review.orderId());
        assertTrue(review.resolution().contains("manual review"));

        // Normal success path was skipped
        assertEquals(NodeStatus.SKIPPED, result.getStatus("normalSuccessPath"));
    }

    // --- Java API: fallback-branch graph (success path) ---

    @Test
    void fallbackBranch_success_routesToNormalSuccess() {
        GraphResult result = ErrorBoundaryReplacementExample.executeFallbackSuccess("EB-003", 49.9);

        assertTrue(result.isSuccess());
        assertEquals(NodeStatus.COMPLETED, result.getStatus("chargePayment"));

        // Normal success path executed
        ErrorBoundaryReplacementExample.SuccessSummary success =
                result.getOutput("normalSuccessPath", ErrorBoundaryReplacementExample.SuccessSummary.class);
        assertNotNull(success, "Normal success path should have executed");
        assertEquals("EB-003", success.orderId());
        assertTrue(success.confirmationNote().contains("authorized"));

        // Manual review path was skipped
        assertEquals(NodeStatus.SKIPPED, result.getStatus("manualReviewPath"));
    }

    // --- DSL: failure path ---

    @Test
    void dsl_failure_routesToManualReview() {
        GraphResult result = ErrorBoundaryReplacementDslExample.execute("EB-DSL-001", 99.9, true);

        assertTrue(result.isSuccess());
        assertEquals(NodeStatus.COMPLETED, result.getStatus("chargePayment"));

        @SuppressWarnings("unchecked")
        Map<String, Object> review = (Map<String, Object>) result.results().getRaw("manualReviewPath");
        assertNotNull(review, "Manual review path should have executed");
        assertTrue(String.valueOf(review.get("resolution")).contains("manual review"));

        assertEquals(NodeStatus.SKIPPED, result.getStatus("normalSuccessPath"));
    }

    // --- DSL: success path ---

    @Test
    void dsl_success_routesToNormalSuccess() {
        GraphResult result = ErrorBoundaryReplacementDslExample.execute("EB-DSL-002", 49.9, false);

        assertTrue(result.isSuccess());
        assertEquals(NodeStatus.COMPLETED, result.getStatus("chargePayment"));

        @SuppressWarnings("unchecked")
        Map<String, Object> success = (Map<String, Object>) result.results().getRaw("normalSuccessPath");
        assertNotNull(success, "Normal success path should have executed");
        assertTrue(String.valueOf(success.get("confirmationNote")).contains("authorized"));

        assertEquals(NodeStatus.SKIPPED, result.getStatus("manualReviewPath"));
    }
}
