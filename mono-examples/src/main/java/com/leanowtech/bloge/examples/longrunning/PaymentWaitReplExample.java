package com.leanowtech.bloge.examples.longrunning;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class PaymentWaitReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ValidateOrderOperator", PaymentWaitDslExample.VALIDATE_ORDER);
        registry.register("CreateOrderOperator", PaymentWaitDslExample.CREATE_ORDER);
        registry.register("InitiatePaymentOperator", PaymentWaitDslExample.INITIATE_PAYMENT);
        registry.registerRaw("AwaitPaymentOperator", PaymentWaitDslExample.AWAIT_PAYMENT);
        registry.register("FulfillOrderOperator", PaymentWaitDslExample.FULFILL_ORDER);
        registry.register("CancelOrderOperator", PaymentWaitDslExample.CANCEL_ORDER);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String customerId = ReplHelper.promptString(scanner, "customerId", "CUST-DSL-88");
        List<String> items = ReplHelper.promptList(scanner, "items (comma separated)", List.of("SKU-A", "SKU-B"));
        return Map.of(
                "customerId", customerId,
                "items", items
        );
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("Payment Wait REPL");
                Map<String, Object> values = promptContext(scanner);

                var registry = new DefaultOperatorRegistry();
                Graph graph = buildGraph(registry);
                var runtime = LongRunningRuntimeExampleSupport.runtime(registry, new LoggingListener());
                var engine = runtime.engine();

                GraphResult phase1 = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(phase1);

                if (phase1.isSuspended() || phase1.getStatus("awaitPayment") == NodeStatus.SUSPENDED) {
                    String orderId = "UNKNOWN";
                    Object rawOrder = phase1.results().getRaw("createOrder");
                    if (rawOrder instanceof Map<?, ?> m) {
                        Object o = m.get("orderId");
                        if (o instanceof String s) orderId = s;
                    }

                    System.out.print("Order created. Press Enter to simulate payment confirmation");
                    scanner.nextLine();

                    runtime.registerOrCorrelation(phase1.executionId(), "awaitPayment",
                            LongRunningRuntimeExampleSupport.event("payment.confirmed", "orderId", orderId));
                    Map<String, Object> paymentPayload = LongRunningRuntimeExampleSupport.payload(
                            "transactionId", "TXN-001",
                            "status", "confirmed",
                            "confirmedAt", Instant.now().toString(),
                            "reason", null
                    );
                    engine.publishEvent("payment.confirmed", orderId, paymentPayload);
                    runtime.saveNodeOutput(phase1.executionId(), "paymentWait", "awaitPayment", paymentPayload);

                    GraphResult phase2 = engine.resume(graph, phase1.executionId(), new GraphContext(values));
                    ReplHelper.printResult(phase2);
                }

                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
