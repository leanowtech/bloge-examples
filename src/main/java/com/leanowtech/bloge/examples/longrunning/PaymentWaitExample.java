package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrates the long-running <em>await-payment-confirmation</em> pattern
 * using the Java Fluent API with runtime in-memory durability support.
 *
 * <p>An order is validated, created, and a payment request is initiated.
 * Execution then <em>suspends</em> waiting for the payment gateway to POST a
 * {@code "payment.confirmed"} event. The example registers a helper-backed
 * matcher, publishes the callback via {@link GraphEngine#publishEvent}, stores
 * the confirmed payment as runtime node output, and then resumes the graph.
 *
 * <h2>Graph layout</h2>
 * <pre>
 * validateOrder → createOrder → initiatePayment
 *                                    ↓
 *                           [SUSPEND awaitPayment]
 *                           (publishEvent OR timeout)
 *                                    ↓
 *                           branch → fulfillOrder / cancelOrder
 * </pre>
 *
 * <h2>Long-running lifecycle</h2>
 * <ol>
 *   <li>{@code execute()} — suspends at {@code awaitPayment} and leaves the execution parked.</li>
 *   <li>Gateway callback — publish the payment event for the order's correlation key.</li>
 *   <li>Save the confirmed payment as runtime node output, then call {@code resume()}.</li>
 * </ol>
 */
@SuppressWarnings("preview")
public class PaymentWaitExample {

    // ── Records ───────────────────────────────────────────────────────────────

    public record OrderInput(String customerId, List<String> items) {}
    public record ValidatedOrder(String customerId, List<String> items, double total) {}
    public record Order(String orderId, String customerId, double total, String status) {}
    public record PaymentRequest(String orderId, double total) {}
    public record PaymentInitiation(String transactionId, String status) {}
    public record PaymentEvent(String transactionId, String status, String reason) {}
    public record FulfillInput(String orderId, String transactionId, List<String> items) {}
    public record FulfillResult(String shipmentId, String estimatedDelivery) {}
    public record CancelInput(String orderId, String customerId, String reason) {}
    public record CancelResult(String cancelledAt, String refundId) {}

    // ── Operators ─────────────────────────────────────────────────────────────

    static final Operator<OrderInput, ValidatedOrder> VALIDATE_ORDER = (input, ctx) -> {
        Thread.sleep(30);
        double total = input.items().size() * 29.99;
        System.out.printf("  [validateOrder] %d items, total=%.2f%n", input.items().size(), total);
        return new ValidatedOrder(input.customerId(), input.items(), total);
    };

    static final Operator<ValidatedOrder, Order> CREATE_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        System.out.println("  [createOrder]   orderId=" + orderId);
        // Store orderId in context so the runtime matcher key is available.
        ctx.graphContext().put("orderId", orderId);
        return new Order(orderId, input.customerId(), input.total(), "pending");
    };

    static final Operator<PaymentRequest, PaymentInitiation> INITIATE_PAYMENT = (input, ctx) -> {
        Thread.sleep(40);
        System.out.println("  [initiatePayment] txId=TXN-001 status=pending");
        return new PaymentInitiation("TXN-001", "pending");
    };

    /**
     * Returns a suspended result to pause execution until runtime event
     * matcher state is satisfied.
     */
    static final SuspendableOperator<PaymentInitiation, PaymentEvent> AWAIT_PAYMENT = (input, ctx) -> {
        String orderId = ctx.graphContext().get("orderId", String.class);
        System.out.println("  [awaitPayment]  SUSPENDING — waiting for payment.confirmed orderId=" + orderId);
        // The suspend key matches the event routing key used in publishEvent()
        return OperatorResult.suspend("payment.confirmed:" + orderId, null, Duration.ofSeconds(2));
    };

    static final Operator<FulfillInput, FulfillResult> FULFILL_ORDER = (input, ctx) -> {
        Thread.sleep(50);
        System.out.println("  [fulfillOrder]  shipmentId=SHIP-001 txn=" + input.transactionId());
        return new FulfillResult("SHIP-001", "2026-03-01");
    };

    static final Operator<CancelInput, CancelResult> CANCEL_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [cancelOrder]   reason=" + input.reason());
        return new CancelResult(Instant.now().toString(), "REF-001");
    };

    // ── Main ──────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();
        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();

        // ── Build graph ───────────────────────────────────────────────────────
        Graph graph = Graph.builder("paymentWait")
                .node("validateOrder", VALIDATE_ORDER)
                    .input((results, ctx) -> new OrderInput(
                            ctx.get("customerId", String.class),
                            (List<String>) ctx.get("items")))
                .node("createOrder", CREATE_ORDER)
                    .dependsOn("validateOrder")
                    .input((results, ctx) ->
                            results.get("validateOrder", ValidatedOrder.class))
                .node("initiatePayment", INITIATE_PAYMENT)
                    .dependsOn("createOrder")
                    .input((results, ctx) -> {
                        Order order = results.get("createOrder", Order.class);
                        return new PaymentRequest(order.orderId(), order.total());
                    })
                .suspendNode("awaitPayment", AWAIT_PAYMENT)
                    .dependsOn("initiatePayment")
                    .input((results, ctx) ->
                            results.get("initiatePayment", PaymentInitiation.class))
                .node("fulfillOrder", FULFILL_ORDER)
                    .dependsOn("awaitPayment")
                    .input((results, ctx) -> {
                        Order order = results.get("createOrder", Order.class);
                        ValidatedOrder validated = results.get("validateOrder", ValidatedOrder.class);
                        // awaitPayment output may be a typed record (fresh run) or Map (from checkpoint)
                        Object raw = results.getRaw("awaitPayment");
                        String txnId = "N/A";
                        if (raw instanceof PaymentEvent pe) txnId = pe.transactionId();
                        else if (raw instanceof Map<?,?> m) { Object t = m.get("transactionId"); if (t instanceof String s) txnId = s; }
                        return new FulfillInput(order.orderId(), txnId, validated.items());
                    })
                .node("cancelOrder", CANCEL_ORDER)
                    .dependsOn("awaitPayment")
                    .input((results, ctx) -> {
                        Order order = results.get("createOrder", Order.class);
                        Object raw = results.getRaw("awaitPayment");
                        String reason = "timeout";
                        if (raw instanceof PaymentEvent pe) reason = pe.reason();
                        else if (raw instanceof Map<?,?> m) { Object r = m.get("reason"); if (r instanceof String s) reason = s; }
                        return new CancelInput(order.orderId(), order.customerId(), reason);
                    })
                .branch("awaitPayment")
                    .on("status")
                    .when(val -> "confirmed".equals(val), "fulfillOrder")
                    .otherwise("cancelOrder")
                .build();

        var ctx = new GraphContext(Map.of(
                "customerId", "CUST-77",
                "items", List.of("PROD-A", "PROD-B", "PROD-C")
        ));

        // ── Phase 1: execute until suspension ────────────────────────────────
        System.out.println("\n═══ Phase 1: Execute until payment suspension ═══");
        GraphResult phase1 = engine.executeWithOperators(graph, ctx, Map.of(
                "validateOrder",   VALIDATE_ORDER,
                "createOrder",     CREATE_ORDER,
                "initiatePayment", INITIATE_PAYMENT,
                "awaitPayment",    AWAIT_PAYMENT,
                "fulfillOrder",    FULFILL_ORDER,
                "cancelOrder",     CANCEL_ORDER
        ));

        System.out.printf("%nSuspended: %s  executionId: %s%n",
                phase1.isSuspended(), phase1.executionId());
        for (var e : phase1.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", e.getKey(), e.getValue());
        }

        String execId = phase1.executionId();
        Order order = phase1.getOutput("createOrder", Order.class);
        System.out.println("Waiting for payment confirmation for order: " + order.orderId());

        // ── Phase 2: register runtime matcher state and publish event ─────────
        System.out.println("\n═══ Phase 2: Payment gateway sends confirmation ═══");
        Thread.sleep(200); // simulate async gateway callback

        runtime.registerOrCorrelation(execId, "awaitPayment",
                LongRunningRuntimeExampleSupport.event("payment.confirmed", "orderId", order.orderId()));

        // Payment gateway POSTs confirmation; your webhook handler calls publishEvent()
        Map<String, Object> paymentPayload = Map.of(
                "transactionId", "TXN-001",
                "status",        "confirmed",
                "amount",        order.total(),
                "confirmedAt",   Instant.now().toString());

        System.out.println("Publishing event: payment.confirmed for orderId=" + order.orderId());
        engine.publishEvent("payment.confirmed", order.orderId(), paymentPayload);

        // Persist event as runtime node output so resume() treats awaitPayment as completed
        runtime.saveNodeOutput(execId, "paymentWait", "awaitPayment",
                new PaymentEvent("TXN-001", "confirmed", null));

        // ── Phase 3: resume ────────────────────────────────────────────────────
        System.out.println("\n═══ Phase 3: Resume after payment confirmation ═══");
        registry.register("validateOrder",   VALIDATE_ORDER);
        registry.register("createOrder",     CREATE_ORDER);
        registry.register("initiatePayment", INITIATE_PAYMENT);
        registry.registerRaw("awaitPayment",    AWAIT_PAYMENT);
        registry.register("fulfillOrder",    FULFILL_ORDER);
        registry.register("cancelOrder",     CANCEL_ORDER);

        GraphResult phase3 = engine.resume(graph, execId, ctx);

        System.out.println("\n═══ Final Result ═══");
        System.out.println("Success: " + phase3.isSuccess());
        for (var e : phase3.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", e.getKey(), e.getValue());
        }
        if (phase3.getStatus("fulfillOrder") == NodeStatus.COMPLETED) {
            FulfillResult fulfil = phase3.getOutput("fulfillOrder", FulfillResult.class);
            System.out.println("Order fulfilled: " + fulfil);
        }
    }
}
