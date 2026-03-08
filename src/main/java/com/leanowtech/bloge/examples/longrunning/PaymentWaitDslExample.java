package com.leanowtech.bloge.examples.longrunning;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * DSL version of the payment-wait long-running example.
 *
 * <p>Demonstrates how the {@code await} DSL block translates to a
 * suspendable operator in the Java execution model,
 * and shows the full suspend → signal (via runtime node output) → resume lifecycle
 * using {@code GraphLoader} and an inline DSL string.
 */
@SuppressWarnings("preview")
public class PaymentWaitDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> VALIDATE_ORDER = (input, ctx) -> {
        Thread.sleep(30);
        int count = ((List<?>) input.get("items")).size();
        double total = count * 29.99;
        System.out.printf("  [validateOrder] %d items, total=%.2f%n", count, total);
        return Map.of("customerId", input.get("customerId"),
                "items", input.get("items"), "total", total);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CREATE_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        String orderId = "ORD-DSL-001";
        ctx.graphContext().put("orderId", orderId);
        System.out.println("  [createOrder]   orderId=" + orderId);
        return Map.of("orderId", orderId, "customerId", input.get("customerId"),
                "total", input.get("total"), "status", "pending");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> INITIATE_PAYMENT = (input, ctx) -> {
        Thread.sleep(40);
        System.out.println("  [initiatePayment] txId=TXN-DSL-001 status=pending");
        return Map.of("transactionId", "TXN-DSL-001", "status", "pending");
    };

    static final SuspendableOperator<Map<String, Object>, Map<String, Object>> AWAIT_PAYMENT = (input, ctx) -> {
        String orderId = ctx.graphContext().get("orderId", String.class);
        System.out.println("  [awaitPayment]  SUSPENDING — payment.confirmed orderId=" + orderId);
        return OperatorResult.suspend("payment.confirmed:" + orderId, null, java.time.Duration.ofSeconds(2));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> FULFILL_ORDER = (input, ctx) -> {
        Thread.sleep(50);
        System.out.println("  [fulfillOrder]  shipmentId=SHIP-DSL-001 txn=" + input.get("paymentRef"));
        return Map.of("shipmentId", "SHIP-DSL-001", "estimatedDelivery", "2026-03-05");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CANCEL_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        System.out.println("  [cancelOrder]   reason=" + input.get("reason"));
        return Map.of("cancelledAt", Instant.now().toString(), "refundId", "REF-DSL-001");
    };

    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();
        registry.register("ValidateOrderOperator",   VALIDATE_ORDER);
        registry.register("CreateOrderOperator",     CREATE_ORDER);
        registry.register("InitiatePaymentOperator", INITIATE_PAYMENT);
        // Represents the `await awaitPayment { event "payment.confirmed" ... }` DSL block
        registry.registerRaw("AwaitPaymentOperator",    AWAIT_PAYMENT);
        registry.register("FulfillOrderOperator",    FULFILL_ORDER);
        registry.register("CancelOrderOperator",     CANCEL_ORDER);

        var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
        var engine = runtime.engine();
        var loader = new GraphLoader(registry);

        String dsl = """
                graph paymentWait {

                  node validateOrder : ValidateOrderOperator {
                    input {
                      customerId = ctx.customerId
                      items      = ctx.items
                    }
                    timeout = 3s
                  }

                  node createOrder : CreateOrderOperator {
                    depends_on = [validateOrder]
                    input {
                      customerId = ctx.customerId
                      items      = validateOrder.output.items
                      total      = validateOrder.output.total
                    }
                  }

                  node initiatePayment : InitiatePaymentOperator {
                    depends_on = [createOrder]
                    input {
                      orderId = createOrder.output.orderId
                      total   = validateOrder.output.total
                    }
                    timeout = 5s
                  }

                  /// Represents: await awaitPayment { event "payment.confirmed" where orderId = createOrder.output.orderId }
                  node awaitPayment : AwaitPaymentOperator {
                    depends_on = [initiatePayment]
                    input {
                      transactionId = initiatePayment.output.transactionId
                    }
                  }

                  node fulfillOrder : FulfillOrderOperator {
                    depends_on = [awaitPayment]
                    input {
                      orderId    = createOrder.output.orderId
                      paymentRef = awaitPayment.output.transactionId
                      items      = validateOrder.output.items
                    }
                  }

                  node cancelOrder : CancelOrderOperator {
                    depends_on = [awaitPayment]
                    input {
                      orderId    = createOrder.output.orderId
                      customerId = ctx.customerId
                      reason     = awaitPayment.output.reason
                    }
                  }
                }
                """;

        Graph graph = loader.load(dsl);

        var ctx = new GraphContext(Map.of(
                "customerId", "CUST-DSL-88",
                "items", List.of("SKU-A", "SKU-B")
        ));

        System.out.println("\n═══ Phase 1 (DSL): Execute until payment suspension ═══");
        GraphResult phase1 = engine.execute(graph, ctx);

        System.out.printf("%nSuspended: %s  executionId: %s%n",
                phase1.isSuspended(), phase1.executionId());
        for (var e : phase1.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", e.getKey(), e.getValue());
        }

        String execId = phase1.executionId();
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) phase1.results().getRaw("createOrder");
        String orderId = order != null ? String.valueOf(order.get("orderId")) : "N/A";
        System.out.println("Awaiting payment for order: " + orderId);
        runtime.registerOrCorrelation(execId, "awaitPayment",
                LongRunningRuntimeExampleSupport.event("payment.confirmed", "orderId", orderId));

        // Simulate payment gateway callback after 150 ms
        System.out.println("\n═══ Phase 2 (DSL): Payment confirmed by gateway ═══");
        Thread.sleep(150);

        Map<String, Object> paymentPayload = LongRunningRuntimeExampleSupport.payload(
                "transactionId", "TXN-DSL-001",
                "status", "confirmed",
                "reason", null
        );
        engine.publishEvent("payment.confirmed", orderId, paymentPayload);
        runtime.saveNodeOutput(execId, "paymentWait", "awaitPayment", paymentPayload);
        System.out.println("Payment confirmation runtime node output saved");

        System.out.println("\n═══ Phase 3 (DSL): Resume after payment ═══");
        GraphResult phase3 = engine.resume(graph, execId, ctx);

        System.out.println("\n═══ Final DSL Result ═══");
        System.out.println("Success: " + phase3.isSuccess());
        for (var e : phase3.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", e.getKey(), e.getValue());
        }
        if (phase3.getStatus("fulfillOrder") == NodeStatus.COMPLETED) {
            System.out.println("Fulfillment: " + phase3.results().getRaw("fulfillOrder"));
        }
    }
}
