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
import com.leanowtech.bloge.ext.model.SessionStateSnapshot;
import com.leanowtech.bloge.ext.model.SessionStatus;
import com.leanowtech.bloge.state.builder.StateMachineBuilder;
import com.leanowtech.bloge.state.engine.StateMachineOperator;
import com.leanowtech.bloge.state.model.StateMachineDef;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java fluent example showing a session phase that delegates to a nested state machine.
 */
@SuppressWarnings({"preview", "unchecked"})
public final class OrderSessionWithStateMachineExample {

    static final Operator<Map<String, Object>, Map<String, Object>> COLLECT_ORDER_INFO = (input, ctx) -> Map.of(
            "orderId", input.get("orderId"),
            "amount", input.get("amount"),
            "status", "draft"
    );

    static final Operator<Map<String, Object>, Map<String, Object>> CHARGE_PAYMENT = (input, ctx) -> Map.of(
            "orderId", input.get("orderId"),
            "amount", input.get("amount"),
            "paymentStatus", "confirmed"
    );

    static final Operator<Map<String, Object>, Map<String, Object>> SHIP_ORDER = (input, ctx) -> {
        String orderId = String.valueOf(input.get("orderId"));
        return Map.of(
                "orderId", orderId,
                "shipmentId", "SHIP-" + orderId,
                "shipped", true
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> NOTIFY_CUSTOMER = (input, ctx) -> Map.of(
            "orderId", input.get("orderId"),
            "finalState", input.get("finalState"),
            "shipmentId", input.get("shipmentId"),
            "notified", true
    );

    private OrderSessionWithStateMachineExample() {
    }

    /**
     * Builds the nested state machine executed during the ordering phase.
     *
     * @return immutable nested state-machine definition
     */
    public static StateMachineDef buildOrderFlow() {
        Graph draftGraph = Graph.builder("orderDraft")
                .node("collectInfo", COLLECT_ORDER_INFO)
                .input((results, ctx) -> Map.of(
                        "orderId", ctx.get("orderId", String.class),
                        "amount", ctx.get("amount")
                ))
                .build();

        Graph pendingPaymentGraph = Graph.builder("pendingPayment")
                .node("chargePayment", CHARGE_PAYMENT)
                .input((results, ctx) -> Map.of(
                        "orderId", ctx.get("orderId", String.class),
                        "amount", ctx.get("amount")
                ))
                .build();

        Graph processingGraph = Graph.builder("orderProcessing")
                .node("shipOrder", SHIP_ORDER)
                .input((results, ctx) -> Map.of(
                        "orderId", ctx.get("orderId", String.class)
                ))
                .build();

        return StateMachineBuilder.create("orderFlow")
                .maxTransitions(20)
                .maxStateVisits(5)
                .state("draft").initial()
                    .graph(draftGraph)
                    .on("submit").goTo("pendingPayment")
                    .done()
                .state("pendingPayment")
                    .graph(pendingPaymentGraph)
                    .on("payment_confirmed").goTo("processing")
                    .on("payment_failed").goTo("draft")
                    .done()
                .state("processing")
                    .graph(processingGraph)
                    .on("*").goTo("shipped")
                    .done()
                .state("shipped").terminal().done()
                .build();
    }

    /**
     * Builds the full session graph that embeds {@link #buildOrderFlow()} as its ordering phase.
     *
     * @return immutable session definition
     */
    public static SessionGraph buildSessionGraph() {
        Graph orderingGraph = Graph.builder("orderSessionOrdering")
                .suspendNode("orderFlow", new StateMachineOperator("__state_machine__:orderFlow", buildOrderFlow()))
                .build();

        Graph fulfillmentGraph = Graph.builder("orderSessionFulfillment")
                .node("notifyCustomer", NOTIFY_CUSTOMER)
                .input((results, ctx) -> {
                    Map<String, Object> ordering = asMap(ctx.get("ordering"));
                    Map<String, Object> output = asMap(ordering.get("output"));
                    Map<String, Object> orderFlow = asMap(output.get("orderFlow"));
                    Map<String, Object> stateMachine = asMap(orderFlow.get("stateMachine"));
                    Map<String, Object> processing = asMap(orderFlow.get("processing"));
                    Map<String, Object> processingOutput = asMap(processing.get("output"));
                    Map<String, Object> shipOrder = asMap(processingOutput.get("shipOrder"));
                    return Map.of(
                            "orderId", ctx.get("orderId", String.class),
                            "finalState", stateMachine.get("currentStateId"),
                            "shipmentId", shipOrder.get("shipmentId")
                    );
                })
                .build();

        return SessionGraph.builder("orderWorkflow")
                .idleTimeout(Duration.ofHours(72))
                .maxTotalRounds(10)
                .phase(PhaseBuilder.once("ordering").graph(orderingGraph).then("fulfillment").build())
                .phase(PhaseBuilder.once("fulfillment").graph(fulfillmentGraph).build())
                .build();
    }

    /**
     * Creates an executor for the example using the supplied snapshot sink and operator registry.
     *
     * @param store in-memory snapshot sink used by the example
     * @param registry operator registry for the enclosing graph engine
     * @return session executor
     */
    public static SessionExecutor newExecutor(ExampleSessionSnapshotStore store, DefaultOperatorRegistry registry) {
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .build();
        return SessionExecutor.builder(engine)
                .snapshotCallbacks(java.util.List.of(store))
                .build();
    }

    /**
     * Registers the example's leaf operators into the supplied registry.
     *
     * @param registry operator registry to update
     */
    public static void registerOperators(DefaultOperatorRegistry registry) {
        registry.register("CollectOrderInfoOperator", COLLECT_ORDER_INFO);
        registry.register("ChargePaymentOperator", CHARGE_PAYMENT);
        registry.register("ShipOrderOperator", SHIP_ORDER);
        registry.register("NotifyCustomerOperator", NOTIFY_CUSTOMER);
    }

    /**
     * Waits until the session reaches the requested status in the captured snapshot stream.
     *
     * @param store snapshot sink
     * @param sessionId session identifier
     * @param status desired status
     * @param timeout wait timeout
     * @return the matching snapshot
     * @throws InterruptedException when the wait is interrupted
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
     * Executes the sample flow using the builder-based session graph.
     *
     * @param args command-line arguments (unused)
     * @throws Exception when execution fails
     */
    public static void main(String[] args) throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registerOperators(registry);
        ExampleSessionSnapshotStore store = new ExampleSessionSnapshotStore();
        try (SessionExecutor executor = newExecutor(store, registry)) {
            SessionHandle handle = executor.start(buildSessionGraph(), new GraphContext(Map.of(
                    "orderId", "ORD-1001",
                    "amount", 149.99,
                    "sessionId", "ORDER-SESSION-001"
            )));
            awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("event", "submit", "submittedBy", "web"));
            awaitStatus(store, handle.sessionId(), SessionStatus.SUSPENDED, Duration.ofSeconds(2));
            handle.signal(Map.of("event", "payment_confirmed", "receivedBy", "gateway"));
            SessionStateSnapshot completed = awaitStatus(store, handle.sessionId(), SessionStatus.COMPLETED, Duration.ofSeconds(3));
            System.out.println("Order workflow outputs: " + new LinkedHashMap<>(completed.phaseOutputs()));
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
