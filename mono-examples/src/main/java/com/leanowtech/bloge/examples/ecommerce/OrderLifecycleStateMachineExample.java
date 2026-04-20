package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.state.builder.StateMachineBuilder;
import com.leanowtech.bloge.state.engine.StateMachineExecutor;
import com.leanowtech.bloge.state.engine.StateMachineResult;
import com.leanowtech.bloge.state.model.StateMachineDef;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Java fluent state-machine example for an order lifecycle.
 *
 * <p>The machine starts in {@code draft}, waits for a {@code submit} event, pauses again in
 * {@code pendingReview}, and finally auto-transitions from {@code processing} to
 * {@code completed} after the fulfillment graph runs.
 */
public final class OrderLifecycleStateMachineExample {

    static final Operator<Map<String, Object>, Map<String, Object>> INIT_ORDER = (input, ctx) -> Map.of(
            "orderId", input.get("orderId"),
            "customerId", input.get("customerId"),
            "status", "draft"
    );

    static final Operator<Map<String, Object>, Map<String, Object>> REVIEW_ORDER = (input, ctx) -> Map.of(
            "orderId", input.get("orderId"),
            "reviewer", "ops-queue",
            "recommendedAction", "approve"
    );

    static final Operator<Map<String, Object>, Map<String, Object>> FULFILL_ORDER = (input, ctx) -> Map.of(
            "orderId", input.get("orderId"),
            "shipmentId", "SHIP-1001",
            "success", true
    );

    private OrderLifecycleStateMachineExample() {
    }

    /**
     * Builds the sample state machine with namespaced per-state DAGs.
     *
     * @return immutable state-machine definition
     */
    public static StateMachineDef buildStateMachine() {
        Graph draftGraph = Graph.builder("orderDraft")
                .node("initOrder", INIT_ORDER)
                .input((results, ctx) -> Map.of(
                        "orderId", ctx.get("orderId", String.class),
                        "customerId", ctx.get("customerId", String.class)))
                .build();

        Graph reviewGraph = Graph.builder("orderReview")
                .node("reviewOrder", REVIEW_ORDER)
                .input((results, ctx) -> {
                    Map<String, Object> draft = asMap(ctx.get("draft"));
                    Map<String, Object> output = asMap(draft.get("output"));
                    Map<String, Object> initOrder = asMap(output.get("initOrder"));
                    return Map.of("orderId", initOrder.get("orderId"));
                })
                .build();

        Graph processingGraph = Graph.builder("orderProcessing")
                .node("fulfillOrder", FULFILL_ORDER)
                .input((results, ctx) -> {
                    Map<String, Object> review = asMap(ctx.get("pendingReview"));
                    Map<String, Object> output = asMap(review.get("output"));
                    Map<String, Object> reviewOrder = asMap(output.get("reviewOrder"));
                    return Map.of("orderId", reviewOrder.get("orderId"));
                })
                .build();

        return StateMachineBuilder.create("orderLifecycle")
                .maxTransitions(25)
                .maxStateVisits(5)
                .state("draft").initial()
                    .graph(draftGraph)
                    .on("submit").goTo("pendingReview")
                    .done()
                .state("pendingReview")
                    .graph(reviewGraph)
                    .on("approve").goTo("processing")
                    .on("reject").goTo("draft")
                    .timeout(Duration.ofHours(24)).onTimeout("draft")
                    .done()
                .state("processing")
                    .graph(processingGraph)
                    .on("*").goTo("completed")
                    .done()
                .state("completed").terminal().done()
                .build();
    }

    /**
     * Executes the sample flow using the builder-based state machine.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("InitOrderOperator", INIT_ORDER);
        registry.register("ReviewOrderOperator", REVIEW_ORDER);
        registry.register("FulfillmentOperator", FULFILL_ORDER);

        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .build();
        StateMachineExecutor executor = StateMachineExecutor.builder(engine).build();
        StateMachineDef def = buildStateMachine();

        StateMachineResult result = executor.execute(def, Map.of(
                "orderId", "ORD-1001",
                "customerId", "CUST-42"
        ));
        System.out.println("After execute: " + describe(result));

        result = executor.signal(result.instance(), def, "submit", Map.of("submittedBy", "alice"));
        System.out.println("After submit: " + describe(result));

        result = executor.signal(result.instance(), def, "approve", Map.of("reviewedBy", "ops-queue"));
        System.out.println("After approve: " + describe(result));
        System.out.println("State outputs: " + new LinkedHashMap<>(result.instance().stateOutputsSnapshot()));
    }

    private static String describe(StateMachineResult result) {
        return result.status() + " state=" + result.instance().currentStateId()
                + " awaited=" + result.awaitedEvents();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, entry) -> normalized.put(String.valueOf(key), entry));
            return normalized;
        }
        return Map.of();
    }
}
