package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorLayer;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates parallel sub-graph execution in an order pipeline.
 * <p>
 * Main graph: validateOrder → [paymentProcessing ∥ inventoryFulfillment] → confirmOrder → notifyCustomer
 * <p>
 * Sub-graph A (payment-processing): fraudDetection → paymentGateway → paymentConfirmation → receiptGeneration
 * Sub-graph B (inventory-fulfillment): inventoryCheck → warehouseAllocation → shipmentCreation
 */
public class OrderFullPipelineExample {

    // --- Main graph records ---

    public record OrderRequest(String orderId, double amount, String customerId) {}
    public record ValidatedOrder(String orderId, double amount, String customerId, String status) {}
    public record ConfirmOrderInput(String orderId, String receiptId, String trackingId) {}
    public record OrderConfirmation(String orderId, String status, String receiptId, String trackingId) {}
    public record NotifyInput(String orderId, String customerId, String message) {}
    public record Notification(String orderId, String customerId, String channel, String message) {}

    // --- Payment sub-graph records ---

    public record FraudInput(String orderId, double amount, String customerId) {}
    public record FraudResult(double fraudScore, boolean passed) {}
    public record PaymentInput(String orderId, double amount) {}
    public record PaymentResult(String transactionId, String status) {}
    public record ConfirmPaymentInput(String transactionId) {}
    public record PaymentConfirmation(boolean confirmed, String confirmationId) {}
    public record ReceiptInput(String orderId, String transactionId, double amount) {}
    public record ReceiptResult(String receiptId, String receiptUrl) {}

    // --- Inventory sub-graph records ---

    public record InventoryInput(String orderId, String customerId) {}
    public record InventoryResult(boolean available, String warehouseId) {}
    public record AllocationInput(String orderId, String warehouseId) {}
    public record AllocationResult(String allocationId, String estimatedShipDate) {}
    public record ShipmentInput(String orderId, String allocationId) {}
    public record ShipmentResult(String trackingId, String carrier) {}

    // --- Main graph operators ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ecommerce", "order"},
            description = "Validates the incoming order request", owner = "ecommerce-team")
    static final Operator<OrderRequest, ValidatedOrder> VALIDATE_ORDER = (input, ctx) -> {
        Thread.sleep(30);
        return new ValidatedOrder(input.orderId(), input.amount(), input.customerId(), "VALIDATED");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ecommerce", "order"},
            description = "Confirms order after payment and inventory processing", owner = "ecommerce-team")
    static final Operator<ConfirmOrderInput, OrderConfirmation> CONFIRM_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        return new OrderConfirmation(input.orderId(), "CONFIRMED", input.receiptId(), input.trackingId());
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"ecommerce", "notification"},
            description = "Sends order confirmation notification to customer", owner = "ecommerce-team")
    static final Operator<NotifyInput, Notification> NOTIFY_CUSTOMER = (input, ctx) -> {
        Thread.sleep(25);
        return new Notification(input.orderId(), input.customerId(), "email", input.message());
    };

    // --- Payment sub-graph operators ---

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"ecommerce", "fraud"},
            description = "Detects fraudulent transactions", owner = "payments-team")
    static final Operator<FraudInput, FraudResult> FRAUD_DETECTION = (input, ctx) -> {
        Thread.sleep(60);
        double score = input.amount() > 1000 ? 0.8 : 0.1;
        return new FraudResult(score, score < 0.5);
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"ecommerce", "payment"},
            description = "Processes payment through external gateway", owner = "payments-team")
    static final Operator<PaymentInput, PaymentResult> PAYMENT_GATEWAY = (input, ctx) -> {
        Thread.sleep(100);
        return new PaymentResult("TXN-" + input.orderId(), "SUCCESS");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ecommerce", "payment"},
            description = "Confirms payment transaction", owner = "payments-team")
    static final Operator<ConfirmPaymentInput, PaymentConfirmation> PAYMENT_CONFIRMATION = (input, ctx) -> {
        Thread.sleep(30);
        return new PaymentConfirmation(true, "CONF-" + input.transactionId());
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ecommerce", "payment"},
            description = "Generates payment receipt", owner = "payments-team")
    static final Operator<ReceiptInput, ReceiptResult> RECEIPT_GENERATION = (input, ctx) -> {
        Thread.sleep(20);
        return new ReceiptResult("RCT-" + input.orderId(), "https://receipts.example.com/RCT-" + input.orderId());
    };

    // --- Inventory sub-graph operators ---

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"ecommerce", "inventory"},
            description = "Checks product inventory availability", owner = "warehouse-team")
    static final Operator<InventoryInput, InventoryResult> INVENTORY_CHECK = (input, ctx) -> {
        Thread.sleep(50);
        return new InventoryResult(true, "WH-EAST-01");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"ecommerce", "inventory"},
            description = "Allocates stock from warehouse", owner = "warehouse-team")
    static final Operator<AllocationInput, AllocationResult> WAREHOUSE_ALLOCATION = (input, ctx) -> {
        Thread.sleep(40);
        return new AllocationResult("ALLOC-" + input.orderId(), "2025-01-20");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"ecommerce", "shipping"},
            description = "Creates shipment and assigns tracking", owner = "warehouse-team")
    static final Operator<ShipmentInput, ShipmentResult> SHIPMENT_CREATION = (input, ctx) -> {
        Thread.sleep(35);
        return new ShipmentResult("TRACK-" + input.orderId(), "FastShip");
    };

    // --- Sub-graph construction ---

    public static Graph buildPaymentSubGraph() {
        return Graph.builder("payment-processing")
                .node("fraudDetection", FRAUD_DETECTION)
                    .input((results, ctx) -> new FraudInput(
                            ctx.get("orderId", String.class),
                            ctx.get("amount", Double.class),
                            ctx.get("customerId", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("paymentGateway", PAYMENT_GATEWAY)
                    .dependsOn("fraudDetection")
                    .input((results, ctx) -> new PaymentInput(
                            ctx.get("orderId", String.class),
                            ctx.get("amount", Double.class)))
                    .retry(2, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                    .timeout(Duration.ofSeconds(10))
                .node("paymentConfirmation", PAYMENT_CONFIRMATION)
                    .dependsOn("paymentGateway")
                    .input((results, ctx) -> new ConfirmPaymentInput(
                            results.get("paymentGateway", PaymentResult.class).transactionId()))
                .node("receiptGeneration", RECEIPT_GENERATION)
                    .dependsOn("paymentConfirmation")
                    .input((results, ctx) -> new ReceiptInput(
                            ctx.get("orderId", String.class),
                            results.get("paymentGateway", PaymentResult.class).transactionId(),
                            ctx.get("amount", Double.class)))
                .build();
    }

    public static Graph buildInventorySubGraph() {
        return Graph.builder("inventory-fulfillment")
                .node("inventoryCheck", INVENTORY_CHECK)
                    .input((results, ctx) -> new InventoryInput(
                            ctx.get("orderId", String.class),
                            ctx.get("customerId", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("warehouseAllocation", WAREHOUSE_ALLOCATION)
                    .dependsOn("inventoryCheck")
                    .input((results, ctx) -> new AllocationInput(
                            ctx.get("orderId", String.class),
                            results.get("inventoryCheck", InventoryResult.class).warehouseId()))
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.FIXED)
                .node("shipmentCreation", SHIPMENT_CREATION)
                    .dependsOn("warehouseAllocation")
                    .input((results, ctx) -> new ShipmentInput(
                            ctx.get("orderId", String.class),
                            results.get("warehouseAllocation", AllocationResult.class).allocationId()))
                .build();
    }

    @SuppressWarnings({"preview", "unchecked"})
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Register sub-graph operators (resolved by operatorRef = node ID for lambdas)
        registry.register("fraudDetection", FRAUD_DETECTION);
        registry.register("paymentGateway", PAYMENT_GATEWAY);
        registry.register("paymentConfirmation", PAYMENT_CONFIRMATION);
        registry.register("receiptGeneration", RECEIPT_GENERATION);
        registry.register("inventoryCheck", INVENTORY_CHECK);
        registry.register("warehouseAllocation", WAREHOUSE_ALLOCATION);
        registry.register("shipmentCreation", SHIPMENT_CREATION);

        // Build sub-graphs
        Graph paymentGraph = buildPaymentSubGraph();
        Graph inventoryGraph = buildInventorySubGraph();

        // Wrap as SubGraphOperators
        SubGraphOperator paymentSubGraph = new SubGraphOperator(paymentGraph, registry);
        SubGraphOperator inventorySubGraph = new SubGraphOperator(inventoryGraph, registry);

        // Build main graph
        Graph mainGraph = Graph.builder("orderFullPipeline")
                .node("validateOrder", VALIDATE_ORDER)
                    .input((results, ctx) -> new OrderRequest(
                            ctx.get("orderId", String.class),
                            ctx.get("amount", Double.class),
                            ctx.get("customerId", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("paymentProcessing", paymentSubGraph)
                    .dependsOn("validateOrder")
                    .input((results, ctx) -> {
                        var validated = results.get("validateOrder", ValidatedOrder.class);
                        return Map.of(
                                "orderId", validated.orderId(),
                                "amount", validated.amount(),
                                "customerId", validated.customerId());
                    })
                    .timeout(Duration.ofSeconds(30))
                .node("inventoryFulfillment", inventorySubGraph)
                    .dependsOn("validateOrder")
                    .input((results, ctx) -> {
                        var validated = results.get("validateOrder", ValidatedOrder.class);
                        return Map.of(
                                "orderId", validated.orderId(),
                                "customerId", validated.customerId());
                    })
                    .timeout(Duration.ofSeconds(30))
                .node("confirmOrder", CONFIRM_ORDER)
                    .dependsOn("paymentProcessing", "inventoryFulfillment")
                    .input((results, ctx) -> {
                        var paymentOut = (Map<String, Object>) results.getRaw("paymentProcessing");
                        var receiptResult = (ReceiptResult) paymentOut.get("receiptGeneration");
                        var inventoryOut = (Map<String, Object>) results.getRaw("inventoryFulfillment");
                        var shipmentResult = (ShipmentResult) inventoryOut.get("shipmentCreation");
                        return new ConfirmOrderInput(
                                ctx.get("orderId", String.class),
                                receiptResult.receiptId(),
                                shipmentResult.trackingId());
                    })
                .node("notifyCustomer", NOTIFY_CUSTOMER)
                    .dependsOn("confirmOrder")
                    .input((results, ctx) -> {
                        var confirmation = results.get("confirmOrder", OrderConfirmation.class);
                        return new NotifyInput(
                                confirmation.orderId(),
                                ctx.get("customerId", String.class),
                                "Order " + confirmation.orderId() + " confirmed. Tracking: " + confirmation.trackingId());
                    })
                .build();

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

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "validateOrder", VALIDATE_ORDER,
                "paymentProcessing", paymentSubGraph,
                "inventoryFulfillment", inventorySubGraph,
                "confirmOrder", CONFIRM_ORDER,
                "notifyCustomer", NOTIFY_CUSTOMER
        ));

        // Print results
        System.out.println("\n═══ Order Full Pipeline Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("confirmOrder") == NodeStatus.COMPLETED) {
            OrderConfirmation confirmation = result.getOutput("confirmOrder", OrderConfirmation.class);
            System.out.println("Order confirmed: " + confirmation);
        }

        if (result.getStatus("notifyCustomer") == NodeStatus.COMPLETED) {
            Notification notification = result.getOutput("notifyCustomer", Notification.class);
            System.out.println("Customer notified: " + notification);
        }

        if (result.getStatus("paymentProcessing") == NodeStatus.COMPLETED) {
            System.out.println("Payment sub-graph output: " + result.results().getRaw("paymentProcessing"));
        }

        if (result.getStatus("inventoryFulfillment") == NodeStatus.COMPLETED) {
            System.out.println("Inventory sub-graph output: " + result.results().getRaw("inventoryFulfillment"));
        }
    }
}
