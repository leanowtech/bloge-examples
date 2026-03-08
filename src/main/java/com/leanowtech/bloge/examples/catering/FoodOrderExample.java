package com.leanowtech.bloge.examples.catering;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Catering order workflow example using typed Java operators.
 *
 * <p>This example demonstrates a restaurant fulfillment pipeline with concurrent checks
 * (inventory, kitchen load, delivery ETA), an acceptance decision, and branch-based
 * continuation into either full fulfillment or alternative suggestions.
 *
 * <p>Graph layout:
 * <pre>
 * validateOrder
 *   -> checkInventory + checkKitchenLoad + estimateDelivery
 *   -> decideAcceptance
 *      -> (accepted=true)  acceptOrder -> notifyKitchen -> assignRider -> processPayment
 *      -> (accepted=false) suggestAlternatives
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the graph with sample catering input.
 */
public class FoodOrderExample {

    public record OrderItem(String dishId, String name, int quantity) {}
    public record OrderRequest(String orderId, String restaurantId, List<OrderItem> items, String deliveryAddress) {}
    public record ValidatedOrder(String orderId, String restaurantId, List<OrderItem> items, double totalAmount) {}

    public record InventoryQuery(String restaurantId, List<OrderItem> items) {}
    public record InventoryStatus(boolean allAvailable, List<String> unavailableItems) {}

    public record KitchenQuery(String restaurantId) {}
    public record KitchenLoad(int currentOrders, int maxCapacity, int estimatedWaitMinutes) {}

    public record DeliveryQuery(String restaurantId, String deliveryAddress) {}
    public record DeliveryEstimate(double distance, int estimatedMinutes, double deliveryFee) {}

    public record AcceptanceInput(InventoryStatus inventory, KitchenLoad kitchen, DeliveryEstimate delivery) {}
    public record AcceptanceDecision(boolean accepted, String reason) {}

    public record AcceptInput(ValidatedOrder order, DeliveryEstimate delivery) {}
    public record AcceptResult(String orderId, int estimatedMinutes, double total) {}

    public record KitchenNotifyInput(String orderId, List<OrderItem> items) {}
    public record NotifyResult(String kitchenOrderId) {}

    public record RiderInput(String orderId, String deliveryAddress) {}
    public record RiderAssignment(String riderId, String riderName) {}

    public record PaymentInput(String orderId, double amount) {}
    public record PaymentResult(String transactionId, String status) {}

    public record AlternativeInput(ValidatedOrder order, List<String> unavailableItems) {}
    public record AlternativeSuggestion(List<String> suggestions, String reason) {}

    @SuppressWarnings("unchecked")
    static final Operator<OrderRequest, ValidatedOrder> VALIDATE_ORDER = (input, ctx) -> {
        Thread.sleep(30);
        double totalAmount = input.items().stream().mapToDouble(i -> i.quantity() * 12.5).sum();
        return new ValidatedOrder(input.orderId(), input.restaurantId(), input.items(), totalAmount);
    };

    static final Operator<InventoryQuery, InventoryStatus> CHECK_INVENTORY = (input, ctx) -> {
        Thread.sleep(60);
        return new InventoryStatus(true, List.of());
    };

    static final Operator<KitchenQuery, KitchenLoad> CHECK_KITCHEN_LOAD = (input, ctx) -> {
        Thread.sleep(40);
        return new KitchenLoad(8, 15, 20);
    };

    static final Operator<DeliveryQuery, DeliveryEstimate> ESTIMATE_DELIVERY = (input, ctx) -> {
        Thread.sleep(80);
        return new DeliveryEstimate(3.5, 25, 5.0);
    };

    static final Operator<AcceptanceInput, AcceptanceDecision> DECIDE_ACCEPTANCE = (input, ctx) -> {
        Thread.sleep(20);
        boolean accepted = input.inventory().allAvailable()
                && input.kitchen().currentOrders() < input.kitchen().maxCapacity()
                && input.delivery().estimatedMinutes() < 60;
        String reason = accepted ? "All checks passed" : "Order cannot be fulfilled";
        return new AcceptanceDecision(accepted, reason);
    };

    static final Operator<AcceptInput, AcceptResult> ACCEPT_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        double total = input.order().totalAmount() + input.delivery().deliveryFee();
        int estimatedMinutes = input.delivery().estimatedMinutes();
        return new AcceptResult(input.order().orderId(), estimatedMinutes, total);
    };

    static final Operator<KitchenNotifyInput, NotifyResult> NOTIFY_KITCHEN = (input, ctx) -> {
        Thread.sleep(30);
        return new NotifyResult("KO-2024-001");
    };

    static final Operator<RiderInput, RiderAssignment> ASSIGN_RIDER = (input, ctx) -> {
        Thread.sleep(50);
        return new RiderAssignment("RDR-007", "Driver Wang");
    };

    static final Operator<PaymentInput, PaymentResult> PROCESS_PAYMENT = (input, ctx) -> {
        Thread.sleep(40);
        return new PaymentResult("TXN-2024-001", "SUCCESS");
    };

    static final Operator<AlternativeInput, AlternativeSuggestion> SUGGEST_ALTERNATIVES = (input, ctx) -> {
        Thread.sleep(30);
        return new AlternativeSuggestion(
                List.of("Dish B instead of Dish A", "Combo Special C"),
                "Some items unavailable");
    };

    @SuppressWarnings("unchecked")
    /**
     * Builds the food ordering workflow graph, including acceptance branching.
     *
     * @return configured graph instance
     */
    public static Graph buildGraph() {
        var builder = Graph.builder("foodOrderProcess")
                .node("validateOrder", VALIDATE_ORDER)
                    .input((results, ctx) -> new OrderRequest(
                            ctx.get("orderId", String.class),
                            ctx.get("restaurantId", String.class),
                            ctx.get("items", List.class),
                            ctx.get("deliveryAddress", String.class)))
                    .timeout(Duration.ofSeconds(2))
                .node("checkInventory", CHECK_INVENTORY)
                    .dependsOn("validateOrder")
                    .input((results, ctx) -> new InventoryQuery(
                            results.get("validateOrder", ValidatedOrder.class).restaurantId(),
                            results.get("validateOrder", ValidatedOrder.class).items()))
                    .timeout(Duration.ofSeconds(3))
                .node("checkKitchenLoad", CHECK_KITCHEN_LOAD)
                    .dependsOn("validateOrder")
                    .input((results, ctx) -> new KitchenQuery(
                            results.get("validateOrder", ValidatedOrder.class).restaurantId()))
                    .timeout(Duration.ofSeconds(2))
                .node("estimateDelivery", ESTIMATE_DELIVERY)
                    .dependsOn("validateOrder")
                    .input((results, ctx) -> new DeliveryQuery(
                            results.get("validateOrder", ValidatedOrder.class).restaurantId(),
                            ctx.get("deliveryAddress", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("decideAcceptance", DECIDE_ACCEPTANCE)
                    .dependsOn("checkInventory", "checkKitchenLoad", "estimateDelivery")
                    .input((results, ctx) -> new AcceptanceInput(
                            results.get("checkInventory", InventoryStatus.class),
                            results.get("checkKitchenLoad", KitchenLoad.class),
                            results.get("estimateDelivery", DeliveryEstimate.class)))
                .node("acceptOrder", ACCEPT_ORDER)
                    .dependsOn("decideAcceptance")
                    .input((results, ctx) -> new AcceptInput(
                            results.get("validateOrder", ValidatedOrder.class),
                            results.get("estimateDelivery", DeliveryEstimate.class)))
                .node("suggestAlternatives", SUGGEST_ALTERNATIVES)
                    .dependsOn("decideAcceptance")
                    .input((results, ctx) -> new AlternativeInput(
                            results.get("validateOrder", ValidatedOrder.class),
                            results.get("checkInventory", InventoryStatus.class).unavailableItems()))
                .node("notifyKitchen", NOTIFY_KITCHEN)
                    .dependsOn("acceptOrder")
                    .input((results, ctx) -> new KitchenNotifyInput(
                            results.get("acceptOrder", AcceptResult.class).orderId(),
                            results.get("validateOrder", ValidatedOrder.class).items()))
                .node("assignRider", ASSIGN_RIDER)
                    .dependsOn("acceptOrder")
                    .input((results, ctx) -> new RiderInput(
                            results.get("acceptOrder", AcceptResult.class).orderId(),
                            ctx.get("deliveryAddress", String.class)))
                    .fallback(ex -> new RiderAssignment("SELF-PICKUP", "Self Pickup"))
                .node("processPayment", PROCESS_PAYMENT)
                    .dependsOn("acceptOrder")
                    .input((results, ctx) -> new PaymentInput(
                            results.get("acceptOrder", AcceptResult.class).orderId(),
                            results.get("acceptOrder", AcceptResult.class).total()))
                    .timeout(Duration.ofSeconds(5))
                .branch("decideAcceptance")
                    .on("accepted")
                    .when(val -> Boolean.TRUE.equals(val), "acceptOrder")
                    .otherwise("suggestAlternatives");

        return builder.build();
    }

    @SuppressWarnings("preview")
    /**
     * Executes the food ordering graph with a sample restaurant order payload.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();

        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of(
                "orderId", "FO-2024-001",
                "restaurantId", "REST-088",
                "items", List.of(
                        new OrderItem("DISH-001", "Kung Pao Chicken", 2),
                        new OrderItem("DISH-002", "Fried Rice", 1)),
                "deliveryAddress", "123 Main St"
        ));

        GraphResult result = engine.executeWithOperators(graph, ctx, Map.of(
                "validateOrder", VALIDATE_ORDER,
                "checkInventory", CHECK_INVENTORY,
                "checkKitchenLoad", CHECK_KITCHEN_LOAD,
                "estimateDelivery", ESTIMATE_DELIVERY,
                "decideAcceptance", DECIDE_ACCEPTANCE,
                "acceptOrder", ACCEPT_ORDER,
                "suggestAlternatives", SUGGEST_ALTERNATIVES,
                "notifyKitchen", NOTIFY_KITCHEN,
                "assignRider", ASSIGN_RIDER,
                "processPayment", PROCESS_PAYMENT
        ));

        System.out.println("\n═══ Food Order Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-22s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("acceptOrder") == NodeStatus.COMPLETED) {
            AcceptResult accept = result.getOutput("acceptOrder", AcceptResult.class);
            System.out.println("Order accepted: " + accept);
            System.out.println("Kitchen notified: " + result.getOutput("notifyKitchen", NotifyResult.class));
            System.out.println("Rider assigned: " + result.getOutput("assignRider", RiderAssignment.class));
            System.out.println("Payment processed: " + result.getOutput("processPayment", PaymentResult.class));
        } else if (result.getStatus("suggestAlternatives") == NodeStatus.COMPLETED) {
            AlternativeSuggestion alt = result.getOutput("suggestAlternatives", AlternativeSuggestion.class);
            System.out.println("Alternatives suggested: " + alt);
        }
    }
}
