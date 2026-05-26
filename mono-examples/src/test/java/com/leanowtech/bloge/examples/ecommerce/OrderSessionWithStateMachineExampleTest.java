package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleSessionSnapshotStore;
import com.leanowtech.bloge.ext.engine.SessionExecutor;
import com.leanowtech.bloge.ext.model.PhaseDef;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.ext.model.SessionHandle;
import com.leanowtech.bloge.ext.model.SessionStateSnapshot;
import com.leanowtech.bloge.ext.model.SessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("preview")
class OrderSessionWithStateMachineExampleTest {

    @Test
    void dslCompile_embedsSyntheticStateMachineOperatorRef() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        SessionGraph sessionGraph = OrderSessionWithStateMachineDslExample.compile(registry);

        PhaseDef ordering = sessionGraph.phaseIndex().get("ordering");
        assertEquals(1, ordering.graph().nodes().size());
        assertEquals(
                ReservedKeys.extensionOperatorRef("state_machine", "orderFlow"),
                ordering.graph().nodes().get("orderFlow").operatorRef()
        );
    }

    @Test
    @Timeout(10)
    void javaApi_nestedStateMachineSuspendsAndResumes() throws Exception {
        SessionStateSnapshot checkpoint = executeJavaApi();
        Map<String, Object> orderingOutput = asMap(checkpoint.phaseOutputs().get("ordering"));
        Map<String, Object> orderFlow = asMap(orderingOutput.get("orderFlow"));
        Map<String, Object> stateMachine = asMap(orderFlow.get("stateMachine"));
        Map<String, Object> draft = asMap(orderFlow.get("draft"));
        Map<String, Object> draftOutput = asMap(draft.get("output"));
        Map<String, Object> collectInfo = asMap(draftOutput.get("collectInfo"));
        Map<String, Object> fulfillmentOutput = asMap(checkpoint.phaseOutputs().get("fulfillment"));
        Map<String, Object> notifyCustomer = asMap(fulfillmentOutput.get("notifyCustomer"));

        assertEquals(SessionStatus.COMPLETED, checkpoint.status());
        assertEquals("shipped", stateMachine.get("currentStateId"));
        assertEquals("ORD-3001", collectInfo.get("orderId"));
        assertEquals("shipped", notifyCustomer.get("finalState"));
        assertEquals("SHIP-ORD-3001", notifyCustomer.get("shipmentId"));
        assertTrue(Boolean.TRUE.equals(notifyCustomer.get("notified")));
    }

    @Test
    @Timeout(10)
    void dsl_nestedStateMachineSuspendsAndResumes() throws Exception {
        SessionStateSnapshot checkpoint = executeDsl();
        Map<String, Object> orderingOutput = asMap(checkpoint.phaseOutputs().get("ordering"));
        Map<String, Object> orderFlow = asMap(orderingOutput.get("orderFlow"));
        Map<String, Object> stateMachine = asMap(orderFlow.get("stateMachine"));
        Map<String, Object> draft = asMap(orderFlow.get("draft"));
        Map<String, Object> draftOutput = asMap(draft.get("output"));
        Map<String, Object> collectInfo = asMap(draftOutput.get("collectInfo"));
        Map<String, Object> fulfillmentOutput = asMap(checkpoint.phaseOutputs().get("fulfillment"));
        Map<String, Object> notifyCustomer = asMap(fulfillmentOutput.get("notifyCustomer"));

        assertEquals(SessionStatus.COMPLETED, checkpoint.status());
        assertEquals("shipped", stateMachine.get("currentStateId"));
        assertEquals("ORD-4001", collectInfo.get("orderId"));
        assertEquals("shipped", notifyCustomer.get("finalState"));
        assertEquals("SHIP-ORD-4001", notifyCustomer.get("shipmentId"));
        assertTrue(Boolean.TRUE.equals(notifyCustomer.get("notified")));
    }

    @Test
    @Timeout(10)
    void javaApi_globalCancelSignalCompletesWithoutShipping() throws Exception {
        SessionStateSnapshot checkpoint = executeJavaApiCancelled();
        Map<String, Object> orderingOutput = asMap(checkpoint.phaseOutputs().get("ordering"));
        Map<String, Object> orderFlow = asMap(orderingOutput.get("orderFlow"));
        Map<String, Object> stateMachine = asMap(orderFlow.get("stateMachine"));
        Map<String, Object> fulfillmentOutput = asMap(checkpoint.phaseOutputs().get("fulfillment"));
        Map<String, Object> notifyCustomer = asMap(fulfillmentOutput.get("notifyCustomer"));

        assertEquals(SessionStatus.COMPLETED, checkpoint.status());
        assertEquals("cancelled", stateMachine.get("currentStateId"));
        assertFalse(orderFlow.containsKey("processing"));
        assertEquals("cancelled", notifyCustomer.get("finalState"));
        assertEquals("not-shipped", notifyCustomer.get("shipmentId"));
    }

    @Test
    @Timeout(10)
    void dsl_globalCancelSignalCompletesWithoutShipping() throws Exception {
        SessionStateSnapshot checkpoint = executeDslCancelled();
        Map<String, Object> orderingOutput = asMap(checkpoint.phaseOutputs().get("ordering"));
        Map<String, Object> orderFlow = asMap(orderingOutput.get("orderFlow"));
        Map<String, Object> stateMachine = asMap(orderFlow.get("stateMachine"));
        Map<String, Object> fulfillmentOutput = asMap(checkpoint.phaseOutputs().get("fulfillment"));
        Map<String, Object> notifyCustomer = asMap(fulfillmentOutput.get("notifyCustomer"));

        assertEquals(SessionStatus.COMPLETED, checkpoint.status());
        assertEquals("cancelled", stateMachine.get("currentStateId"));
        assertFalse(orderFlow.containsKey("processing"));
        assertEquals("cancelled", notifyCustomer.get("finalState"));
        assertEquals("not-shipped", notifyCustomer.get("shipmentId"));
    }

    private SessionStateSnapshot executeJavaApi() throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        OrderSessionWithStateMachineExample.registerOperators(registry);
        ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
        try (SessionExecutor executor = OrderSessionWithStateMachineExample.newExecutor(store, registry)) {
            SessionHandle handle = executor.start(
                    OrderSessionWithStateMachineExample.buildSessionGraph(),
                    new GraphContext(Map.of(
                            "orderId", "ORD-3001",
                            "amount", 149.99,
                            "sessionId", "ORDER-SESSION-JAVA-001"
                    ))
            );
            OrderSessionWithStateMachineExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("event", "submit", "submittedBy", "web"));
            OrderSessionWithStateMachineExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("event", "payment_confirmed", "receivedBy", "gateway"));
            return OrderSessionWithStateMachineExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
        }
    }

            private SessionStateSnapshot executeJavaApiCancelled() throws Exception {
            DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
            OrderSessionWithStateMachineExample.registerOperators(registry);
            ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
            try (SessionExecutor executor = OrderSessionWithStateMachineExample.newExecutor(store, registry)) {
                SessionHandle handle = executor.start(
                    OrderSessionWithStateMachineExample.buildSessionGraph(),
                    new GraphContext(Map.of(
                        "orderId", "ORD-3002",
                        "amount", 149.99,
                        "sessionId", "ORDER-SESSION-JAVA-CANCEL"
                    ))
                );
                OrderSessionWithStateMachineExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
                handle.signal(Map.of("event", "submit", "submittedBy", "web"));
                OrderSessionWithStateMachineExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
                handle.signal(Map.of("event", "cancel", "reason", "customer request"));
                return OrderSessionWithStateMachineExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
            }
            }

    private SessionStateSnapshot executeDsl() throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        SessionGraph sessionGraph = OrderSessionWithStateMachineDslExample.compile(registry);
        ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
        try (SessionExecutor executor = OrderSessionWithStateMachineExample.newExecutor(store, registry)) {
            SessionHandle handle = executor.start(
                    sessionGraph,
                    new GraphContext(Map.of(
                            "orderId", "ORD-4001",
                            "amount", 89.50,
                            "sessionId", "ORDER-SESSION-DSL-001"
                    ))
            );
            OrderSessionWithStateMachineExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("event", "submit", "submittedBy", "api"));
            OrderSessionWithStateMachineExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("event", "payment_confirmed", "receivedBy", "gateway"));
            return OrderSessionWithStateMachineExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
        }
    }

            private SessionStateSnapshot executeDslCancelled() throws Exception {
            DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
            SessionGraph sessionGraph = OrderSessionWithStateMachineDslExample.compile(registry);
            ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
            try (SessionExecutor executor = OrderSessionWithStateMachineExample.newExecutor(store, registry)) {
                SessionHandle handle = executor.start(
                    sessionGraph,
                    new GraphContext(Map.of(
                        "orderId", "ORD-4002",
                        "amount", 89.50,
                        "sessionId", "ORDER-SESSION-DSL-CANCEL"
                    ))
                );
                OrderSessionWithStateMachineExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
                handle.signal(Map.of("event", "submit", "submittedBy", "api"));
                OrderSessionWithStateMachineExample.awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
                handle.signal(Map.of("event", "cancel", "reason", "customer request"));
                return OrderSessionWithStateMachineExample.awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
            }
            }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, entry) -> normalized.put(String.valueOf(key), entry));
            return normalized;
        }
        return Map.of();
    }
}
