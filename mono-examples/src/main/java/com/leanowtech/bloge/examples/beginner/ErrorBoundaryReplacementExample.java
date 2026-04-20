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
 * Validates the BPMN Error Boundary replacement pattern described in the evolution plan §3.2.
 *
 * <p>The core idea: a node's {@code fallback} value becomes its normal output when the operator
 * fails, so downstream nodes and {@code branch on} routing cannot distinguish real output from
 * fallback output. This lets a single {@code fallback + branch} combination replace BPMN's
 * Error Boundary Event Sub-Process without any new runtime primitives.</p>
 *
 * <h3>Three graphs are provided:</h3>
 * <ol>
 *   <li><b>Strict graph</b> — no fallback; the error surfaces via {@link GraphResult#errors()}
 *       and downstream nodes are never scheduled.</li>
 *   <li><b>Fallback-with-branch graph</b> — the failing node produces a marker value
 *       {@code {approved: false, failed: true, note: "…"}} on failure, and a downstream
 *       {@code branch on chargePayment.output.failed} routes to either
 *       {@code manualReviewPath} or {@code normalSuccessPath}.</li>
 *   <li><b>Success graph</b> — the same fallback-with-branch graph executed against a
 *       non-failing operator, proving the normal-success path routes correctly.</li>
 * </ol>
 *
 * @see <a href="../../../../../../docs/implements-plan/bloge-evolution-to-ai-native-graph-engine-plan.md">
 *      Evolution plan §3.2: Error Boundary replacement via fallback + branch</a>
 */
public final class ErrorBoundaryReplacementExample {

    /** Input to the simulated payment operator. */
    public record PaymentRequest(String orderId, double amount, boolean simulateFailure) {}

    /** Output of the payment operator (or its fallback). */
    public record PaymentReceipt(boolean approved, boolean failed, String note) {}

    /** Summary emitted by the manual-review branch. */
    public record ManualReviewSummary(String orderId, String reason, String resolution) {}

    /** Summary emitted by the normal-success branch. */
    public record SuccessSummary(String orderId, String confirmationNote) {}

    // --- Operators ---

    static final Operator<PaymentRequest, PaymentReceipt> CHARGE_PAYMENT = (input, ctx) -> {
        if (input.simulateFailure()) {
            throw new IllegalStateException("Payment gateway rejected order " + input.orderId());
        }
        Thread.sleep(25);
        return new PaymentReceipt(true, false, "Payment authorized for " + input.orderId());
    };

    static final Operator<PaymentReceipt, ManualReviewSummary> MANUAL_REVIEW = (input, ctx) ->
            new ManualReviewSummary(
                    ctx.graphContext().get("orderId", String.class),
                    input.note(),
                    "Routed to manual review because the payment gateway was unavailable"
            );

    static final Operator<PaymentReceipt, SuccessSummary> NORMAL_SUCCESS = (input, ctx) ->
            new SuccessSummary(
                    ctx.graphContext().get("orderId", String.class),
                    input.note()
            );

    private ErrorBoundaryReplacementExample() {}

    /**
     * Graph with no fallback — the failure propagates and downstream nodes are never scheduled.
     */
    public static Graph buildStrictGraph() {
        return Graph.builder("errorBoundaryStrict")
                .node("chargePayment", CHARGE_PAYMENT)
                    .input((results, ctx) -> new PaymentRequest(
                            ctx.get("orderId", String.class),
                            ctx.get("amount", Double.class),
                            ctx.get("simulateFailure", Boolean.class)))
                    .timeout(Duration.ofSeconds(1))
                .node("manualReviewPath", MANUAL_REVIEW)
                    .input((results, ctx) -> results.get("chargePayment", PaymentReceipt.class))
                .node("normalSuccessPath", NORMAL_SUCCESS)
                    .input((results, ctx) -> results.get("chargePayment", PaymentReceipt.class))
                .branch("chargePayment")
                    .on("failed")
                    .when(val -> Boolean.TRUE.equals(val), "manualReviewPath")
                    .otherwise("normalSuccessPath")
                .build();
    }

    /**
     * Graph with fallback that produces a marker value {@code {failed: true}} and a branch
     * that routes to manual review on failure or to normal success otherwise.
     *
     * <p>This is the canonical Error Boundary replacement pattern: the fallback marker value
     * flows downstream exactly like a real output, and the branch routes on the marker field.</p>
     */
    public static Graph buildFallbackBranchGraph() {
        return Graph.builder("errorBoundaryFallbackBranch")
                .node("chargePayment", CHARGE_PAYMENT)
                    .input((results, ctx) -> new PaymentRequest(
                            ctx.get("orderId", String.class),
                            ctx.get("amount", Double.class),
                            ctx.get("simulateFailure", Boolean.class)))
                    .retry(1, Duration.ofMillis(25), BackoffStrategy.EXPONENTIAL)
                    .timeout(Duration.ofSeconds(1))
                    .fallback(() -> new PaymentReceipt(false, true, "Gateway unavailable; queued for manual review"))
                .node("manualReviewPath", MANUAL_REVIEW)
                    .input((results, ctx) -> results.get("chargePayment", PaymentReceipt.class))
                .node("normalSuccessPath", NORMAL_SUCCESS)
                    .input((results, ctx) -> results.get("chargePayment", PaymentReceipt.class))
                .branch("chargePayment")
                    .on("failed")
                    .when(val -> Boolean.TRUE.equals(val), "manualReviewPath")
                    .otherwise("normalSuccessPath")
                .build();
    }

    // --- Execution helpers ---

    /**
     * Executes the strict (no-fallback) graph so callers can verify the error surfaces.
     */
    public static GraphResult executeStrictFailure(String orderId, double amount) {
        return execute(buildStrictGraph(), orderId, amount, true);
    }

    /**
     * Executes the fallback-branch graph with a failing operator — should route to manual review.
     */
    public static GraphResult executeFallbackFailure(String orderId, double amount) {
        return execute(buildFallbackBranchGraph(), orderId, amount, true);
    }

    /**
     * Executes the fallback-branch graph with a succeeding operator — should route to normal success.
     */
    public static GraphResult executeFallbackSuccess(String orderId, double amount) {
        return execute(buildFallbackBranchGraph(), orderId, amount, false);
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
                        "manualReviewPath", MANUAL_REVIEW,
                        "normalSuccessPath", NORMAL_SUCCESS
                )
        );
    }

    public static void main(String[] args) {
        System.out.println("=== Strict graph (no fallback) ===");
        GraphResult strict = executeStrictFailure("ORDER-EB-100", 99.9);
        System.out.println("Success: " + strict.isSuccess());
        System.out.println("Errors: " + strict.errors().size());

        System.out.println("\n=== Fallback-branch graph (failure → manual review) ===");
        GraphResult fallbackFail = executeFallbackFailure("ORDER-EB-101", 99.9);
        ManualReviewSummary review = fallbackFail.getOutput("manualReviewPath", ManualReviewSummary.class);
        System.out.println("Success: " + fallbackFail.isSuccess());
        System.out.println("Manual review resolution: " + review.resolution());

        System.out.println("\n=== Fallback-branch graph (success → normal path) ===");
        GraphResult fallbackOk = executeFallbackSuccess("ORDER-EB-102", 49.9);
        SuccessSummary success = fallbackOk.getOutput("normalSuccessPath", SuccessSummary.class);
        System.out.println("Success: " + fallbackOk.isSuccess());
        System.out.println("Confirmation: " + success.confirmationNote());
    }
}
