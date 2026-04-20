package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleSessionSnapshotStore;
import com.leanowtech.bloge.ext.engine.SessionExecutor;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.ext.model.SessionHandle;
import com.leanowtech.bloge.ext.model.SessionStateSnapshot;
import com.leanowtech.bloge.ext.model.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates the Interruptible Scope replacement pattern (evolution plan §4.2).
 *
 * <ul>
 *   <li><b>Cancel path</b>: signal with {@code action=cancel} redirects to the cancelled phase;
 *       the refund and notification nodes execute, proving cancelled-phase nodes run to completion.</li>
 *   <li><b>Confirm path</b>: signal with {@code action=confirm} reaches the completed phase.</li>
 *   <li><b>DSL variant</b>: identical cancel/confirm semantics compiled from {@code .bloge}.</li>
 * </ul>
 */
@SuppressWarnings("preview")
class OrderCancellationSessionExampleTest {

    // --- Java API: cancel path ---

    @Test
    @Timeout(10)
    void javaApi_cancelSignal_routesToCancelledPhase() throws Exception {
        ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
        try (SessionExecutor executor = OrderCancellationSessionExample.newExecutor(store)) {
            SessionHandle handle = executor.start(
                    OrderCancellationSessionExample.buildSessionGraph(),
                    new GraphContext(Map.of("orderId", "ORD-T-001", "customerId", "CUST-T-1")));

            OrderCancellationSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(3));

            handle.signal(Map.of("action", "cancel"));

            SessionStateSnapshot completed = OrderCancellationSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));

            Map<String, Object> phaseOutputs = completed.phaseOutputs();

            // Processing phase ran
            assertTrue(phaseOutputs.containsKey("processing"), "processing phase should have outputs");
            // Cancelled phase ran
            assertTrue(phaseOutputs.containsKey("cancelled"), "cancelled phase should have executed");
            // Completed phase did NOT run
            assertFalse(phaseOutputs.containsKey("completed"), "completed phase should not have executed");

            // Verify cancelled-phase nodes actually executed — notifyCancel is the leaf node
            // and depends on refund, so its presence proves the full cancelled-phase graph ran
            Map<String, Object> cancelledOutputs = asMap(phaseOutputs.get("cancelled"));
            Map<String, Object> notifyCancel = asMap(cancelledOutputs.get("notifyCancel"));
            assertEquals(true, notifyCancel.get("notified"), "Notify cancel node should have executed");
            assertTrue(String.valueOf(notifyCancel.get("message")).contains("cancelled"),
                    "Cancellation message should confirm the order was cancelled");
        }
    }

    // --- Java API: confirm path ---

    @Test
    @Timeout(10)
    void javaApi_confirmSignal_routesToCompletedPhase() throws Exception {
        ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
        try (SessionExecutor executor = OrderCancellationSessionExample.newExecutor(store)) {
            SessionHandle handle = executor.start(
                    OrderCancellationSessionExample.buildSessionGraph(),
                    new GraphContext(Map.of("orderId", "ORD-T-002", "customerId", "CUST-T-2")));

            OrderCancellationSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(3));

            handle.signal(Map.of("action", "confirm"));

            SessionStateSnapshot completed = OrderCancellationSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));

            Map<String, Object> phaseOutputs = completed.phaseOutputs();

            assertTrue(phaseOutputs.containsKey("processing"));
            assertTrue(phaseOutputs.containsKey("completed"), "completed phase should have executed");
            assertFalse(phaseOutputs.containsKey("cancelled"), "cancelled phase should not have executed");

            Map<String, Object> completedOutputs = asMap(phaseOutputs.get("completed"));
            Map<String, Object> confirmation = asMap(completedOutputs.get("sendConfirmation"));
            assertEquals(true, confirmation.get("confirmed"));
        }
    }

    // --- DSL: cancel path ---

    @Test
    @Timeout(10)
    void dsl_cancelSignal_routesToCancelledPhase() throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
        SessionGraph sessionGraph = OrderCancellationSessionDslExample.buildSessionGraph(registry);
        try (SessionExecutor executor = OrderCancellationSessionDslExample.newExecutor(registry, store)) {
            SessionHandle handle = executor.start(sessionGraph,
                    new GraphContext(Map.of("orderId", "ORD-DSL-T-001", "customerId", "CUST-DSL-1")));

            OrderCancellationSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(3));

            handle.signal(Map.of("action", "cancel"));

            SessionStateSnapshot completed = OrderCancellationSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));

            Map<String, Object> phaseOutputs = completed.phaseOutputs();

            assertTrue(phaseOutputs.containsKey("cancelled"), "cancelled phase should have executed");
            assertFalse(phaseOutputs.containsKey("completed"), "completed phase should not have executed");

            // notifyCancel depends on refund, so its presence proves the full graph ran
            Map<String, Object> cancelledOutputs = asMap(phaseOutputs.get("cancelled"));
            Map<String, Object> notifyCancel = asMap(cancelledOutputs.get("notifyCancel"));
            assertEquals(true, notifyCancel.get("notified"));
        }
    }

    // --- DSL: confirm path ---

    @Test
    @Timeout(10)
    void dsl_confirmSignal_routesToCompletedPhase() throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
        SessionGraph sessionGraph = OrderCancellationSessionDslExample.buildSessionGraph(registry);
        try (SessionExecutor executor = OrderCancellationSessionDslExample.newExecutor(registry, store)) {
            SessionHandle handle = executor.start(sessionGraph,
                    new GraphContext(Map.of("orderId", "ORD-DSL-T-002", "customerId", "CUST-DSL-2")));

            OrderCancellationSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(3));

            handle.signal(Map.of("action", "confirm"));

            SessionStateSnapshot completed = OrderCancellationSessionExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));

            Map<String, Object> phaseOutputs = completed.phaseOutputs();

            assertTrue(phaseOutputs.containsKey("completed"), "completed phase should have executed");
            assertFalse(phaseOutputs.containsKey("cancelled"), "cancelled phase should not have executed");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), v));
            return normalized;
        }
        return Map.of();
    }
}
