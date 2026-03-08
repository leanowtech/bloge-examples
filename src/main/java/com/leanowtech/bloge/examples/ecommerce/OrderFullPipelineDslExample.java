package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode.GraphDef;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.lexer.Lexer;
import com.leanowtech.bloge.dsl.parser.Parser;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DSL version of the full order pipeline with parallel sub-graphs.
 * <p>
 * Sub-graphs are built via Java API and registered with the DslCompiler,
 * then referenced in DSL using {@code subgraph("name")} syntax.
 */
@SuppressWarnings("preview")
public class OrderFullPipelineDslExample {

    // --- Main graph operators (Map-based for DSL) ---

    static final Operator<Map<String, Object>, Map<String, Object>> VALIDATE_ORDER = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of(
                "orderId", input.get("orderId"),
                "amount", input.get("amount"),
                "customerId", input.get("customerId"),
                "status", "VALIDATED");
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> CONFIRM_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        var payment = (Map<String, Object>) input.get("payment");
        var inventory = (Map<String, Object>) input.get("inventory");
        return Map.of(
                "orderId", input.get("orderId"),
                "status", "CONFIRMED",
                "receiptId", payment.get("receiptId"),
                "trackingId", inventory.get("trackingId"));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> NOTIFY_CUSTOMER = (input, ctx) -> {
        Thread.sleep(25);
        String trackingId = (String) input.get("trackingId");
        return Map.of(
                "orderId", input.get("orderId"),
                "customerId", input.get("customerId"),
                "channel", "email",
                "message", "Order confirmed. Tracking: " + trackingId);
    };

    // --- Payment sub-graph operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> FRAUD_DETECTION = (input, ctx) -> {
        Thread.sleep(60);
        double amount = ((Number) input.get("amount")).doubleValue();
        double score = amount > 1000 ? 0.8 : 0.1;
        return Map.of("fraudScore", score, "passed", score < 0.5);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> PAYMENT_GATEWAY = (input, ctx) -> {
        Thread.sleep(100);
        return Map.of("transactionId", "TXN-" + input.get("orderId"), "status", "SUCCESS");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> PAYMENT_CONFIRMATION = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of("confirmed", true, "confirmationId", "CONF-" + input.get("transactionId"));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> RECEIPT_GENERATION = (input, ctx) -> {
        Thread.sleep(20);
        String orderId = (String) input.get("orderId");
        return Map.of("receiptId", "RCT-" + orderId, "receiptUrl", "https://receipts.example.com/RCT-" + orderId);
    };

    // --- Inventory sub-graph operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> INVENTORY_CHECK = (input, ctx) -> {
        Thread.sleep(50);
        return Map.of("available", true, "warehouseId", "WH-EAST-01");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> WAREHOUSE_ALLOCATION = (input, ctx) -> {
        Thread.sleep(40);
        return Map.of("allocationId", "ALLOC-" + input.get("orderId"), "estimatedShipDate", "2025-01-20");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SHIPMENT_CREATION = (input, ctx) -> {
        Thread.sleep(35);
        return Map.of("trackingId", "TRACK-" + input.get("orderId"), "carrier", "FastShip");
    };

    // --- Sub-graph construction (Java API, Map-based) ---

    public static Graph buildPaymentSubGraph() {
        return Graph.builder("payment-processing")
                .node("fraudDetection", FRAUD_DETECTION)
                    .input((results, ctx) -> Map.of(
                            "orderId", ctx.get("orderId", String.class),
                            "amount", ctx.get("amount", Double.class),
                            "customerId", ctx.get("customerId", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("paymentGateway", PAYMENT_GATEWAY)
                    .dependsOn("fraudDetection")
                    .input((results, ctx) -> Map.of(
                            "orderId", ctx.get("orderId", String.class),
                            "amount", ctx.get("amount", Double.class)))
                    .retry(2, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                    .timeout(Duration.ofSeconds(10))
                .node("paymentConfirmation", PAYMENT_CONFIRMATION)
                    .dependsOn("paymentGateway")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var gateway = (Map<String, Object>) results.getRaw("paymentGateway");
                        return Map.of("transactionId", gateway.get("transactionId"));
                    })
                .node("receiptGeneration", RECEIPT_GENERATION)
                    .dependsOn("paymentConfirmation")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var gateway = (Map<String, Object>) results.getRaw("paymentGateway");
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "transactionId", gateway.get("transactionId"),
                                "amount", ctx.get("amount", Double.class));
                    })
                .build();
    }

    public static Graph buildInventorySubGraph() {
        return Graph.builder("inventory-fulfillment")
                .node("inventoryCheck", INVENTORY_CHECK)
                    .input((results, ctx) -> Map.of(
                            "orderId", ctx.get("orderId", String.class),
                            "customerId", ctx.get("customerId", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("warehouseAllocation", WAREHOUSE_ALLOCATION)
                    .dependsOn("inventoryCheck")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var inv = (Map<String, Object>) results.getRaw("inventoryCheck");
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "warehouseId", inv.get("warehouseId"));
                    })
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.FIXED)
                .node("shipmentCreation", SHIPMENT_CREATION)
                    .dependsOn("warehouseAllocation")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var alloc = (Map<String, Object>) results.getRaw("warehouseAllocation");
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "allocationId", alloc.get("allocationId"));
                    })
                .build();
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // Register main graph operators
        // VALIDATE_ORDER: reads ctx.orderId/amount/customerId → {orderId, amount, customerId, status}
        registry.register("ValidateOrderOperator", VALIDATE_ORDER);
        // CONFIRM_ORDER: reads payment.receiptId + inventory.trackingId → {orderId, status, receiptId, trackingId}
        registry.register("ConfirmOrderOperator", CONFIRM_ORDER);
        // NOTIFY_CUSTOMER: reads confirmOrder.orderId/trackingId → {orderId, customerId, channel, message}
        registry.register("NotifyCustomerOperator", NOTIFY_CUSTOMER);

        // Register sub-graph operators (resolved by operatorRef = node ID for lambdas)
        // FRAUD_DETECTION: reads ctx.orderId/amount/customerId → {fraudScore, passed}
        registry.register("fraudDetection", FRAUD_DETECTION);
        // PAYMENT_GATEWAY: reads orderId/amount → {transactionId, status}; retry with exponential backoff
        registry.register("paymentGateway", PAYMENT_GATEWAY);
        // PAYMENT_CONFIRMATION: reads paymentGateway.transactionId → {confirmed, confirmationId}
        registry.register("paymentConfirmation", PAYMENT_CONFIRMATION);
        // RECEIPT_GENERATION: reads orderId/transactionId/amount → {receiptId, receiptUrl}
        registry.register("receiptGeneration", RECEIPT_GENERATION);
        // INVENTORY_CHECK: reads orderId/customerId → {available, warehouseId}
        registry.register("inventoryCheck", INVENTORY_CHECK);
        // WAREHOUSE_ALLOCATION: reads inventoryCheck.warehouseId → {allocationId, estimatedShipDate}
        registry.register("warehouseAllocation", WAREHOUSE_ALLOCATION);
        // SHIPMENT_CREATION: reads allocationId → {trackingId, carrier}
        registry.register("shipmentCreation", SHIPMENT_CREATION);

        // Build sub-graphs via Java API
        Graph paymentGraph = buildPaymentSubGraph();
        Graph inventoryGraph = buildInventorySubGraph();

        // Compile main graph from DSL with registered sub-graphs
        var compiler = new DslCompiler(registry);
        // register sub-graphs before loading main DSL
        compiler.registerSubGraph("payment-processing", paymentGraph);
        compiler.registerSubGraph("inventory-fulfillment", inventoryGraph);

        String dsl = """
                graph orderFullPipeline {
                  ///  validateOrder: reads ctx.orderId/amount/customerId → {orderId, amount, customerId, status}
                  node validateOrder : ValidateOrderOperator {
                    input {
                      orderId    = ctx.orderId
                      amount     = ctx.amount
                      customerId = ctx.customerId
                    }
                    timeout = 3s
                  }
                  ///  paymentProcessing/inventoryFulfillment run in parallel after validateOrder
                  ///  paymentProcessing: sub-graph fraudDetection → paymentGateway → paymentConfirmation → receiptGeneration
                  node paymentProcessing : subgraph("payment-processing") {
                    depends_on = [validateOrder]
                    input {
                      orderId    = validateOrder.output.orderId
                      amount     = validateOrder.output.amount
                      customerId = validateOrder.output.customerId
                    }
                    timeout = 30s
                  }
                  ///  inventoryFulfillment: sub-graph inventoryCheck → warehouseAllocation → shipmentCreation
                  node inventoryFulfillment : subgraph("inventory-fulfillment") {
                    depends_on = [validateOrder]
                    input {
                      orderId    = validateOrder.output.orderId
                      customerId = validateOrder.output.customerId
                    }
                    timeout = 30s
                  }
                  ///  confirmOrder: joins payment + inventory sub-graph outputs → {orderId, status, receiptId, trackingId}
                  node confirmOrder : ConfirmOrderOperator {
                    depends_on = [paymentProcessing, inventoryFulfillment]
                    input {
                      orderId   = validateOrder.output.orderId
                      payment   = paymentProcessing.output.receiptGeneration
                      inventory = inventoryFulfillment.output.shipmentCreation
                    }
                  }
                  ///  notifyCustomer: reads confirmOrder.orderId/trackingId → {orderId, customerId, channel, message}
                  node notifyCustomer : NotifyCustomerOperator {
                    depends_on = [confirmOrder]
                    input {
                      orderId    = confirmOrder.output.orderId
                      customerId = ctx.customerId
                      trackingId = confirmOrder.output.trackingId
                    }
                  }
                }
                """;

        // compile DSL; operators resolved by PascalCase name
        var tokens = new Lexer(dsl).tokenize();
        GraphDef ast = new Parser(tokens).parse();
        Graph graph = compiler.compile(ast);

        // Execute
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "orderId", "ORD-7890",
                "amount", 249.99,
                "customerId", "CUST-42"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        // Print results
        System.out.println("\n═══ DSL Order Full Pipeline Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; sub-graph nodes return Map of child-node outputs keyed by node ID
        if (result.getStatus("confirmOrder") == NodeStatus.COMPLETED) {
            System.out.println("Order confirmed: " + result.results().getRaw("confirmOrder"));
        }

        if (result.getStatus("notifyCustomer") == NodeStatus.COMPLETED) {
            System.out.println("Customer notified: " + result.results().getRaw("notifyCustomer"));
        }

        if (result.getStatus("paymentProcessing") == NodeStatus.COMPLETED) {
            System.out.println("Payment sub-graph output: " + result.results().getRaw("paymentProcessing"));
        }

        if (result.getStatus("inventoryFulfillment") == NodeStatus.COMPLETED) {
            System.out.println("Inventory sub-graph output: " + result.results().getRaw("inventoryFulfillment"));
        }
    }
}
