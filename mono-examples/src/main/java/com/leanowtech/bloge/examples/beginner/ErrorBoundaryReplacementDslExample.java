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
 * DSL version of {@link ErrorBoundaryReplacementExample}.
 *
 * <p>Compiles {@code error-boundary-replacement.bloge} and executes it with map-based operators.
 * The DSL resource declares a fallback with a {@code failed: true} marker and a
 * {@code branch on chargePayment.output.failed} that routes to manual review or normal success.</p>
 */
public final class ErrorBoundaryReplacementDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> CHARGE_PAYMENT = (input, ctx) -> {
        if (Boolean.TRUE.equals(input.get("simulateFailure"))) {
            throw new IllegalStateException("Payment gateway rejected order " + input.get("orderId"));
        }
        return Map.of("approved", true, "failed", false, "note", "Payment authorized for " + input.get("orderId"));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> MANUAL_REVIEW = (input, ctx) -> Map.of(
            "orderId", ctx.graphContext().get("orderId", String.class),
            "reason", input.get("note"),
            "resolution", "Routed to manual review because the payment gateway was unavailable"
    );

    static final Operator<Map<String, Object>, Map<String, Object>> NORMAL_SUCCESS = (input, ctx) -> Map.of(
            "orderId", ctx.graphContext().get("orderId", String.class),
            "confirmationNote", input.get("note")
    );

    private ErrorBoundaryReplacementDslExample() {}

    /**
     * Executes the DSL graph with the specified failure simulation flag.
     */
    public static GraphResult execute(String orderId, double amount, boolean simulateFailure) {
        var registry = new DefaultOperatorRegistry();
        registry.register("ChargePaymentOperator", CHARGE_PAYMENT);
        registry.register("ManualReviewOperator", MANUAL_REVIEW);
        registry.register("NormalSuccessOperator", NORMAL_SUCCESS);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();

        return engine.execute(
                ExampleDslResources.loadGraph("/bloge/error-boundary-replacement.bloge", registry),
                new GraphContext(Map.of(
                        "orderId", orderId,
                        "amount", amount,
                        "simulateFailure", simulateFailure
                ))
        );
    }

    public static void main(String[] args) {
        System.out.println("=== DSL: failure → manual review ===");
        GraphResult failed = execute("ORDER-EB-DSL-100", 99.9, true);
        @SuppressWarnings("unchecked")
        Map<String, Object> review = (Map<String, Object>) failed.results().getRaw("manualReviewPath");
        System.out.println("Success: " + failed.isSuccess());
        System.out.println("Resolution: " + review.get("resolution"));

        System.out.println("\n=== DSL: success → normal path ===");
        GraphResult ok = execute("ORDER-EB-DSL-101", 49.9, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> success = (Map<String, Object>) ok.results().getRaw("normalSuccessPath");
        System.out.println("Success: " + ok.isSuccess());
        System.out.println("Confirmation: " + success.get("confirmationNote"));
    }
}
