package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;

import java.util.List;
import java.util.Map;

/**
 * DSL version of {@link ErrorHandlingShowcaseExample}.
 */
public final class ErrorHandlingShowcaseDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> CHARGE_PAYMENT = (input, ctx) -> {
        if (Boolean.TRUE.equals(input.get("simulateFailure"))) {
            throw new IllegalStateException("Payment gateway rejected order " + input.get("orderId"));
        }
        return Map.of("approved", true, "note", "Payment authorized");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SUMMARIZE_OUTCOME = (input, ctx) -> Map.of(
            "orderId", ctx.graphContext().get("orderId", String.class),
            "approved", input.get("approved"),
            "resolution", input.get("note")
    );

    private ErrorHandlingShowcaseDslExample() {
    }

    /**
     * Executes the DSL graph. The DSL resource is configured with a fallback so the graph remains successful.
     */
    public static GraphResult execute(String orderId, double amount, boolean simulateFailure) {
        var registry = new DefaultOperatorRegistry();
        registry.register("ChargePaymentOperator", CHARGE_PAYMENT);
        registry.register("SummarizeOutcomeOperator", SUMMARIZE_OUTCOME);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();

        return engine.execute(
                ExampleDslResources.loadGraph("/bloge/error-handling-showcase.bloge", registry),
                new GraphContext(Map.of(
                        "orderId", orderId,
                        "amount", amount,
                        "simulateFailure", simulateFailure
                ))
        );
    }

    public static void main(String[] args) {
        GraphResult result = execute("ORDER-100", 49.9, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.results().getRaw("summarizeOutcome");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Resolution: " + summary.get("resolution"));
    }
}
