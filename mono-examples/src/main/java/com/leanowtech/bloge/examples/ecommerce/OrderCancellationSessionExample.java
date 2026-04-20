package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleSessionSnapshotStore;
import com.leanowtech.bloge.ext.builder.PhaseBuilder;
import com.leanowtech.bloge.ext.engine.SessionExecutor;
import com.leanowtech.bloge.ext.model.SessionGraph;
import com.leanowtech.bloge.ext.model.SessionHandle;
import com.leanowtech.bloge.ext.model.SessionIdentity;
import com.leanowtech.bloge.ext.model.SessionStateSnapshot;
import com.leanowtech.bloge.ext.model.SessionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates the Interruptible Scope replacement pattern described in the evolution plan §4.2.
 *
 * <p>BPMN Interruptible Event Sub-Process allows cancelling an active scope mid-execution.
 * bloge replaces this with session signals routed at phase boundaries: a signal delivered
 * while a session is suspended at a yield point redirects the session to an alternate phase
 * instead of interrupting nodes mid-execution.</p>
 *
 * <h3>Scenario: Order cancellation during processing</h3>
 * <ol>
 *   <li>{@code processing} phase — validates the order, charges payment, and ships (sequential
 *       DAG). The phase completes and the session transitions to {@code awaitDecision}.</li>
 *   <li>{@code awaitDecision} phase (round) — yields and waits for an external signal.
 *       The signal payload is processed by a router node that emits an {@code action} field.
 *       <ul>
 *         <li>{@code action == "cancel"} → session transitions to {@code cancelled} phase.</li>
 *         <li>{@code action == "confirm"} → session transitions to {@code completed} phase.</li>
 *       </ul></li>
 *   <li>{@code cancelled} phase — executes a refund node and a cancellation-notification node,
 *       proving that the cancelled-phase nodes run to completion.</li>
 *   <li>{@code completed} phase — reached only if a confirmation signal arrives.</li>
 * </ol>
 *
 * <p><b>Key semantic difference from BPMN</b>: bloge signals take effect at phase boundaries
 * (yield points), not mid-node. A payment already charged requires a refund in the cancelled
 * phase — not a pretend-it-never-happened rollback. This is intentional and matches real-world
 * order cancellation semantics.</p>
 *
 * @see <a href="../../../../../../docs/implements-plan/bloge-evolution-to-ai-native-graph-engine-plan.md">
 *      Evolution plan §4.2: Interruptible Scope replacement via session signal + phase transition</a>
 */
@SuppressWarnings({"preview", "unchecked"})
public final class OrderCancellationSessionExample {

    // --- Operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> VALIDATE_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "orderId", input.get("orderId"),
                "valid", true,
                "customerId", input.get("customerId")
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CHARGE_PAYMENT = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "orderId", input.get("orderId"),
                "chargeId", "CHG-" + input.get("orderId"),
                "charged", true
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SHIP_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "orderId", input.get("orderId"),
                "shipmentId", "SHIP-" + input.get("orderId"),
                "shipped", true
        );
    };

    /** Routes the incoming signal to an action field for phase-transition evaluation. */
    static final Operator<Map<String, Object>, Map<String, Object>> DECISION_ROUTER = (input, ctx) -> {
        String action = String.valueOf(input.getOrDefault("action", "confirm"));
        return Map.of(
                "action", action,
                "done", true
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> REFUND = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "orderId", input.get("orderId"),
                "refundId", "REF-" + input.get("orderId"),
                "refunded", true
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> NOTIFY_CANCEL = (input, ctx) -> Map.of(
            "orderId", input.get("orderId"),
            "notified", true,
            "message", "Order " + input.get("orderId") + " has been cancelled and refunded"
    );

    static final Operator<Map<String, Object>, Map<String, Object>> SEND_CONFIRMATION = (input, ctx) -> Map.of(
            "orderId", input.get("orderId"),
            "confirmed", true,
            "message", "Order " + input.get("orderId") + " completed successfully"
    );

    private OrderCancellationSessionExample() {}

    /**
     * Builds the order-cancellation session graph.
     *
     * <p>Phases: {@code processing → awaitDecision → (cancelled | completed)}.
     * The {@code awaitDecision} round phase yields at a signal boundary and evaluates the
     * signal payload to route to either the {@code cancelled} or {@code completed} phase.</p>
     */
    public static SessionGraph buildSessionGraph() {
        Graph processingGraph = Graph.builder("orderProcessing")
                .node("validateOrder", VALIDATE_ORDER)
                    .input((results, ctx) -> Map.of(
                            "orderId", ctx.get("orderId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
                .node("chargePayment", CHARGE_PAYMENT)
                    .dependsOn("validateOrder")
                    .input((results, ctx) -> Map.of(
                            "orderId", ctx.get("orderId", String.class)))
                .node("shipOrder", SHIP_ORDER)
                    .dependsOn("chargePayment")
                    .input((results, ctx) -> Map.of(
                            "orderId", ctx.get("orderId", String.class)))
                .build();

        Graph decisionGraph = Graph.builder("orderDecision")
                .node("decisionRouter", DECISION_ROUTER)
                    .input((results, ctx) -> roundInput(ctx))
                .build();

        Graph cancelledGraph = Graph.builder("orderCancelled")
                .node("refund", REFUND)
                    .input((results, ctx) -> Map.of(
                            "orderId", ctx.get("orderId", String.class)))
                .node("notifyCancel", NOTIFY_CANCEL)
                    .dependsOn("refund")
                    .input((results, ctx) -> Map.of(
                            "orderId", ctx.get("orderId", String.class)))
                .build();

        Graph completedGraph = Graph.builder("orderCompleted")
                .node("sendConfirmation", SEND_CONFIRMATION)
                    .input((results, ctx) -> Map.of(
                            "orderId", ctx.get("orderId", String.class)))
                .build();

        return SessionGraph.builder("orderCancellationSession")
                .idleTimeout(Duration.ofMinutes(5))
                .maxTotalRounds(5)
                .phase(PhaseBuilder.once("processing").graph(processingGraph).then("awaitDecision").build())
                .phase(PhaseBuilder.round("awaitDecision")
                        .graph(decisionGraph)
                        .maxRounds(1)
                        .yieldOn("decisionRouter")
                        .until(out -> Boolean.TRUE.equals(asMap(out.get("decisionRouter")).get("done")))
                        .transition(
                                out -> "cancel".equals(asMap(out.get("decisionRouter")).get("action")),
                                "decisionRouter.action == cancel",
                                "cancelled")
                        .then("completed")
                        .build())
                .phase(PhaseBuilder.once("cancelled").graph(cancelledGraph).build())
                .phase(PhaseBuilder.once("completed").graph(completedGraph).build())
                .build();
    }

    /**
     * Creates a new session executor backed by the given in-memory snapshot sink.
     */
    public static SessionExecutor newExecutor(ExampleSessionSnapshotStore store) {
        GraphEngine engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .build();
        return SessionExecutor.builder(engine)
                .snapshotCallbacks(java.util.List.of(store))
                .build();
    }

    /**
     * Registers the map-based operators used by the DSL variant of this example.
     */
    public static void registerOperators(DefaultOperatorRegistry registry) {
        registry.register("ValidateOrderOperator", VALIDATE_ORDER);
        registry.register("ChargePaymentOperator", CHARGE_PAYMENT);
        registry.register("ShipOrderOperator", SHIP_ORDER);
        registry.register("DecisionRouterOperator", DECISION_ROUTER);
        registry.register("RefundOperator", REFUND);
        registry.register("NotifyCancelOperator", NOTIFY_CANCEL);
        registry.register("SendConfirmationOperator", SEND_CONFIRMATION);
    }

    /**
     * Polls the in-memory snapshot sink until the session reaches the expected status.
     */
    public static SessionStateSnapshot awaitStatus(ExampleSessionSnapshotStore store,
                                                   String sessionId,
                                                   SessionStatus status,
                                                   Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        SessionStateSnapshot latest = null;
        while (Instant.now().isBefore(deadline)) {
            SessionStateSnapshot snapshot = store.load(sessionId).orElse(null);
            if (snapshot != null) {
                latest = snapshot;
            }
            if (snapshot != null && snapshot.status() == status) {
                return snapshot;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for " + status + " session=" + sessionId
                + ", lastStatus=" + (latest == null ? "none" : latest.status()));
    }

    /**
     * Demonstrates the cancellation flow: start → signal cancel → verify cancelled-phase outputs.
     */
    public static void main(String[] args) throws Exception {
        ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
        SessionGraph sessionGraph = buildSessionGraph();
        try (SessionExecutor executor = newExecutor(store)) {
            SessionHandle handle = executor.start(
                    sessionGraph,
                    new GraphContext(Map.of(
                            "orderId", "ORD-CANCEL-001",
                            "customerId", "CUST-42"
                    )),
                    SessionIdentity.of("default", "user-123")
            );

            // Wait for the processing phase to complete and session to yield at awaitDecision
            awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(3));

            // Send the cancellation signal — this routes to the cancelled phase
            executor.signal(handle.sessionId(), Map.of("action", "cancel"), "user-123");

            SessionStateSnapshot completed = awaitStatus(
                    store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
            System.out.println("Session outputs: " + new LinkedHashMap<>(completed.phaseOutputs()));
        }
    }

    private static Map<String, Object> roundInput(GraphContext ctx) {
        Object round = ctx.get("round");
        if (!(round instanceof Map<?, ?> roundMap)) {
            return Map.of();
        }
        Object input = roundMap.get("input");
        return asMap(input);
    }

    static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((k, v) -> normalized.put(String.valueOf(k), v));
            return normalized;
        }
        return Map.of();
    }
}
