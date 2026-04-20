package com.leanowtech.bloge.examples.beginner;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Beginner-friendly example that compares two outcomes for the same failing operator:
 * one graph surfaces the error through {@link GraphResult#errors()}, while the other
 * attaches fallback behaviour and continues with a manual-review response.
 */
public final class ErrorHandlingShowcaseExample {

    /** Input handed to the simulated payment operator. */
    public record PaymentRequest(String orderId, double amount, boolean simulateFailure) {
    }

    /** Raw outcome of the payment attempt. */
    public record PaymentReceipt(boolean approved, String note) {
    }

    /** User-facing summary emitted after the payment step completes or falls back. */
    public record OutcomeSummary(String orderId, boolean approved, String resolution) {
    }

    static final Operator<PaymentRequest, PaymentReceipt> CHARGE_PAYMENT = (input, ctx) -> {
        if (input.simulateFailure()) {
            throw new IllegalStateException("Payment gateway rejected order " + input.orderId());
        }
        Thread.sleep(25);
        return new PaymentReceipt(true, "Payment authorized");
    };

    static final Operator<PaymentReceipt, OutcomeSummary> SUMMARIZE_OUTCOME = (input, ctx) ->
            new OutcomeSummary(
                    ctx.graphContext().get("orderId", String.class),
                    input.approved(),
                    input.note()
            );

    private ErrorHandlingShowcaseExample() {
    }

    /**
     * Graph that intentionally has no fallback so callers can inspect the failure via {@link GraphResult#errors()}.
     */
    public static Graph buildStrictGraph() {
        return Graph.builder("errorHandlingStrict")
                .node("chargePayment", CHARGE_PAYMENT)
                    .input((results, ctx) -> new PaymentRequest(
                            ctx.get("orderId", String.class),
                            ctx.get("amount", Double.class),
                            ctx.get("simulateFailure", Boolean.class)))
                    .timeout(Duration.ofSeconds(1))
                .node("summarizeOutcome", SUMMARIZE_OUTCOME)
                    .dependsOn("chargePayment")
                    .input((results, ctx) -> results.get("chargePayment", PaymentReceipt.class))
                .build();
    }

    /**
     * Graph that treats the same downstream failure as recoverable and substitutes a manual-review outcome.
     */
    public static Graph buildFallbackGraph() {
        return Graph.builder("errorHandlingFallback")
                .node("chargePayment", CHARGE_PAYMENT)
                    .input((results, ctx) -> new PaymentRequest(
                            ctx.get("orderId", String.class),
                            ctx.get("amount", Double.class),
                            ctx.get("simulateFailure", Boolean.class)))
                    .retry(1, Duration.ofMillis(25), BackoffStrategy.EXPONENTIAL)
                    .timeout(Duration.ofSeconds(1))
                    .fallback(() -> new PaymentReceipt(false, "Gateway unavailable; queued for manual review"))
                .node("summarizeOutcome", SUMMARIZE_OUTCOME)
                    .dependsOn("chargePayment")
                    .input((results, ctx) -> results.get("chargePayment", PaymentReceipt.class))
                .build();
    }

    /**
     * Executes the strict graph so tests or readers can inspect the resulting error collection.
     */
    public static GraphResult executeFailureScenario(String orderId, double amount) {
        return execute(buildStrictGraph(), orderId, amount, true);
    }

    /**
     * Executes the fallback graph so readers can compare it with the strict error path.
     */
    public static GraphResult executeFallbackScenario(String orderId, double amount) {
        return execute(buildFallbackGraph(), orderId, amount, true);
    }

    private static GraphResult execute(Graph graph, String orderId, double amount, boolean simulateFailure) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(
                graph,
                new GraphContext(Map.of(
                        "orderId", orderId,
                        "amount", amount,
                        "simulateFailure", simulateFailure
                )),
                Map.of(
                        "chargePayment", CHARGE_PAYMENT,
                        "summarizeOutcome", SUMMARIZE_OUTCOME
                )
        );
    }

    public static void main(String[] args) {
        GraphResult failed = executeFailureScenario("ORDER-100", 49.9);
        System.out.println("Strict graph success: " + failed.isSuccess());
        System.out.println("Strict graph errors: " + failed.errors().size());

        GraphResult recovered = executeFallbackScenario("ORDER-100", 49.9);
        OutcomeSummary summary = recovered.getOutput("summarizeOutcome", OutcomeSummary.class);
        System.out.println("Fallback graph success: " + recovered.isSuccess());
        System.out.println("Fallback resolution: " + summary.resolution());
    }
}
