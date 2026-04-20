package com.leanowtech.bloge.examples.antipatterns;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.exception.NonRetryableException;
import com.leanowtech.bloge.core.exception.RetryableException;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Shows why a blanket fallback is dangerous.
 *
 * <p>The bad graph converts every failure into a manual-review response, even when the root cause
 * is a permanent validation bug. The corrected graph marks permanent failures as non-retryable so
 * they bypass fallback, while transient failures remain retryable and can still fall back safely.</p>
 */
public final class OverBroadFallbackExample {

    public record PaymentCommand(String orderId, String failureMode) {
    }

    public record SettlementResult(String status, String note) {
    }

    static final Operator<PaymentCommand, SettlementResult> BAD_AUTHORIZE_PAYMENT = (input, ctx) -> switch (input.failureMode()) {
        case "validation" -> throw new IllegalArgumentException("Card token is malformed");
        case "timeout" -> throw new RetryableException("Payment gateway timed out");
        default -> new SettlementResult("authorized", "Payment approved");
    };

    static final Operator<PaymentCommand, SettlementResult> GOOD_AUTHORIZE_PAYMENT = (input, ctx) -> switch (input.failureMode()) {
        case "validation" -> throw new NonRetryableException("Card token is malformed");
        case "timeout" -> throw new RetryableException("Payment gateway timed out");
        default -> new SettlementResult("authorized", "Payment approved");
    };

    private OverBroadFallbackExample() {
    }

    /**
     * Bad graph that masks every exception type behind the same fallback response.
     */
    public static Graph buildBadGraph() {
        return Graph.builder("overBroadFallbackBad")
                .node("authorizePayment", BAD_AUTHORIZE_PAYMENT)
                    .input((results, ctx) -> new PaymentCommand(
                            ctx.get("orderId", String.class),
                            ctx.get("failureMode", String.class)))
                    .timeout(Duration.ofSeconds(1))
                    .fallback(() -> new SettlementResult("manual-review", "Fallback hid the real failure mode"))
                .build();
    }

    /**
     * Corrected graph that keeps the fallback for transient failures but surfaces permanent validation issues.
     */
    public static Graph buildCorrectedGraph() {
        return Graph.builder("overBroadFallbackCorrected")
                .node("authorizePayment", GOOD_AUTHORIZE_PAYMENT)
                    .input((results, ctx) -> new PaymentCommand(
                            ctx.get("orderId", String.class),
                            ctx.get("failureMode", String.class)))
                    .retry(2, Duration.ofMillis(20), BackoffStrategy.EXPONENTIAL)
                    .timeout(Duration.ofSeconds(1))
                    .fallback(() -> new SettlementResult("manual-review", "Transient gateway issue queued for manual review"))
                .build();
    }

    public static GraphResult executeBadScenario(String failureMode) {
        return execute(buildBadGraph(), failureMode, BAD_AUTHORIZE_PAYMENT);
    }

    public static GraphResult executeCorrectedScenario(String failureMode) {
        return execute(buildCorrectedGraph(), failureMode, GOOD_AUTHORIZE_PAYMENT);
    }

    private static GraphResult execute(Graph graph, String failureMode, Operator<PaymentCommand, SettlementResult> operator) {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(
                graph,
                new GraphContext(Map.of(
                        "orderId", "ORDER-900",
                        "failureMode", failureMode
                )),
                Map.of("authorizePayment", operator)
        );
    }

    public static void main(String[] args) {
        GraphResult bad = executeBadScenario("validation");
        GraphResult corrected = executeCorrectedScenario("validation");
        System.out.println("Bad graph success: " + bad.isSuccess());
        System.out.println("Corrected graph errors: " + corrected.errors().size());
    }
}
