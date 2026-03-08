package com.leanowtech.bloge.examples.catering;

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
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates sequential sub-graph execution with branching in a restaurant order pipeline.
 * <p>
 * Main graph: receiveOrder → paymentVerification → kitchenDispatch (sub-graph)
 *             → qualityCheck → branch(dineIn/delivery) → orderComplete
 * <p>
 * Sub-graph A (kitchen-dispatch): dishValidation → ingredientCheck → stationAssignment
 *             → cookingTimeEstimate → queueForPickup
 * Sub-graph B (delivery-coordination): riderMatching → routeCalculation → etaEstimation
 *             → realtimeTrackingSetup
 */
public class RestaurantOrderPipelineExample {

    // --- Main graph records ---

    public record OrderRequest(String orderId, String restaurantId, List<String> items,
                               String orderType, String customerAddress) {}
    public record ReceivedOrder(String orderId, String restaurantId, List<String> items,
                                String orderType, String customerAddress, String status) {}
    public record PaymentInput(String orderId, double amount) {}
    public record PaymentResult(String orderId, String transactionId, boolean verified) {}
    public record QualityCheckInput(String orderId, String orderType, String queuePosition) {}
    public record QualityCheckResult(String orderId, String orderType, double qualityScore, boolean passed) {}
    public record DineInInput(String orderId, String tableNumber) {}
    public record DineInReady(String orderId, String tableNumber, String status) {}
    public record OrderCompleteInput(String orderId, String completionChannel, String details) {}
    public record OrderCompletion(String orderId, String completionChannel, String timestamp, String status) {}

    // --- Kitchen dispatch sub-graph records ---

    public record DishValidationInput(String orderId, List<String> items) {}
    public record DishValidationResult(List<String> validatedDishes, boolean allValid) {}
    public record IngredientCheckInput(String orderId, List<String> dishes) {}
    public record IngredientCheckResult(boolean allAvailable, String notes) {}
    public record StationAssignmentInput(String orderId, int dishCount) {}
    public record StationAssignmentResult(String station, String chefName) {}
    public record CookingTimeInput(String orderId, String station) {}
    public record CookingTimeResult(int estimatedMinutes) {}
    public record QueueInput(String orderId, int estimatedMinutes) {}
    public record QueueResult(String queuePosition, String estimatedReady) {}

    // --- Delivery coordination sub-graph records ---

    public record RiderMatchInput(String orderId, String deliveryZone) {}
    public record RiderMatchResult(String riderId, String riderName) {}
    public record RouteCalcInput(String orderId, String riderId, String customerAddress) {}
    public record RouteCalcResult(double distanceKm, String routeSummary) {}
    public record EtaInput(String orderId, double distanceKm) {}
    public record EtaResult(int etaMinutes, String estimatedArrival) {}
    public record TrackingSetupInput(String orderId, String riderId) {}
    public record TrackingSetupResult(String trackingId, String trackingUrl) {}

    // --- Main graph operators ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"catering", "order"},
            description = "Receives and validates the incoming restaurant order", owner = "catering-team")
    static final Operator<OrderRequest, ReceivedOrder> RECEIVE_ORDER = (input, ctx) -> {
        Thread.sleep(30);
        return new ReceivedOrder(input.orderId(), input.restaurantId(), input.items(),
                input.orderType(), input.customerAddress(), "RECEIVED");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"catering", "payment"},
            description = "Verifies payment for the order", owner = "payments-team")
    static final Operator<PaymentInput, PaymentResult> PAYMENT_VERIFICATION = (input, ctx) -> {
        Thread.sleep(80);
        return new PaymentResult(input.orderId(), "TXN-" + input.orderId(), true);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"catering", "quality"},
            description = "Performs quality check on prepared dishes", owner = "catering-team")
    static final Operator<QualityCheckInput, QualityCheckResult> QUALITY_CHECK = (input, ctx) -> {
        Thread.sleep(40);
        return new QualityCheckResult(input.orderId(), input.orderType(), 9.2, true);
    };

    static final Operator<DineInInput, DineInReady> PREPARE_DINE_IN = (input, ctx) -> {
        Thread.sleep(25);
        return new DineInReady(input.orderId(), input.tableNumber(), "TABLE_READY");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"catering", "order"},
            description = "Finalizes the order and records completion", owner = "catering-team")
    static final Operator<OrderCompleteInput, OrderCompletion> ORDER_COMPLETE = (input, ctx) -> {
        Thread.sleep(15);
        return new OrderCompletion(input.orderId(), input.completionChannel(),
                "2025-01-18T19:45:00Z", "COMPLETED");
    };

    // --- Kitchen dispatch sub-graph operators ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"catering", "kitchen"},
            description = "Validates dishes exist on the restaurant menu", owner = "kitchen-team")
    static final Operator<DishValidationInput, DishValidationResult> DISH_VALIDATION = (input, ctx) -> {
        Thread.sleep(20);
        return new DishValidationResult(input.items(), true);
    };

    static final Operator<IngredientCheckInput, IngredientCheckResult> INGREDIENT_CHECK = (input, ctx) -> {
        Thread.sleep(35);
        return new IngredientCheckResult(true, "All ingredients in stock");
    };

    static final Operator<StationAssignmentInput, StationAssignmentResult> STATION_ASSIGNMENT = (input, ctx) -> {
        Thread.sleep(15);
        return new StationAssignmentResult("GRILL-A", "Chef Marco");
    };

    static final Operator<CookingTimeInput, CookingTimeResult> COOKING_TIME_ESTIMATE = (input, ctx) -> {
        Thread.sleep(10);
        return new CookingTimeResult(25);
    };

    static final Operator<QueueInput, QueueResult> QUEUE_FOR_PICKUP = (input, ctx) -> {
        Thread.sleep(10);
        return new QueueResult("Q-" + input.orderId(), "19:30");
    };

    // --- Delivery coordination sub-graph operators ---

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"catering", "delivery"},
            description = "Matches an available rider for delivery", owner = "delivery-team")
    static final Operator<RiderMatchInput, RiderMatchResult> RIDER_MATCHING = (input, ctx) -> {
        Thread.sleep(60);
        return new RiderMatchResult("RIDER-77", "Carlos");
    };

    static final Operator<RouteCalcInput, RouteCalcResult> ROUTE_CALCULATION = (input, ctx) -> {
        Thread.sleep(45);
        return new RouteCalcResult(3.7, "Via Main St → Oak Ave → Elm St");
    };

    static final Operator<EtaInput, EtaResult> ETA_ESTIMATION = (input, ctx) -> {
        Thread.sleep(20);
        int eta = (int) (input.distanceKm() * 4) + 5;
        return new EtaResult(eta, "19:55");
    };

    static final Operator<TrackingSetupInput, TrackingSetupResult> REALTIME_TRACKING_SETUP = (input, ctx) -> {
        Thread.sleep(15);
        return new TrackingSetupResult("TRK-" + input.orderId(),
                "https://track.example.com/TRK-" + input.orderId());
    };

    // --- Sub-graph construction ---

    public static Graph buildKitchenDispatchSubGraph() {
        return Graph.builder("kitchen-dispatch")
                .node("dishValidation", DISH_VALIDATION)
                    .input((results, ctx) -> new DishValidationInput(
                            ctx.get("orderId", String.class),
                            ctx.get("items", List.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("ingredientCheck", INGREDIENT_CHECK)
                    .dependsOn("dishValidation")
                    .input((results, ctx) -> new IngredientCheckInput(
                            ctx.get("orderId", String.class),
                            results.get("dishValidation", DishValidationResult.class).validatedDishes()))
                .node("stationAssignment", STATION_ASSIGNMENT)
                    .dependsOn("ingredientCheck")
                    .input((results, ctx) -> new StationAssignmentInput(
                            ctx.get("orderId", String.class),
                            results.get("dishValidation", DishValidationResult.class).validatedDishes().size()))
                .node("cookingTimeEstimate", COOKING_TIME_ESTIMATE)
                    .dependsOn("stationAssignment")
                    .input((results, ctx) -> new CookingTimeInput(
                            ctx.get("orderId", String.class),
                            results.get("stationAssignment", StationAssignmentResult.class).station()))
                .node("queueForPickup", QUEUE_FOR_PICKUP)
                    .dependsOn("cookingTimeEstimate")
                    .input((results, ctx) -> new QueueInput(
                            ctx.get("orderId", String.class),
                            results.get("cookingTimeEstimate", CookingTimeResult.class).estimatedMinutes()))
                .build();
    }

    public static Graph buildDeliveryCoordinationSubGraph() {
        return Graph.builder("delivery-coordination")
                .node("riderMatching", RIDER_MATCHING)
                    .input((results, ctx) -> new RiderMatchInput(
                            ctx.get("orderId", String.class),
                            ctx.get("customerAddress", String.class).split(",")[0].trim()))
                    .retry(3, Duration.ofMillis(500), BackoffStrategy.JITTER)
                    .timeout(Duration.ofSeconds(10))
                .node("routeCalculation", ROUTE_CALCULATION)
                    .dependsOn("riderMatching")
                    .input((results, ctx) -> new RouteCalcInput(
                            ctx.get("orderId", String.class),
                            results.get("riderMatching", RiderMatchResult.class).riderId(),
                            ctx.get("customerAddress", String.class)))
                .node("etaEstimation", ETA_ESTIMATION)
                    .dependsOn("routeCalculation")
                    .input((results, ctx) -> new EtaInput(
                            ctx.get("orderId", String.class),
                            results.get("routeCalculation", RouteCalcResult.class).distanceKm()))
                .node("realtimeTrackingSetup", REALTIME_TRACKING_SETUP)
                    .dependsOn("etaEstimation")
                    .input((results, ctx) -> new TrackingSetupInput(
                            ctx.get("orderId", String.class),
                            results.get("riderMatching", RiderMatchResult.class).riderId()))
                .build();
    }

    @SuppressWarnings({"preview", "unchecked"})
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Register sub-graph operators (resolved by operatorRef = node ID for lambdas)
        registry.register("dishValidation", DISH_VALIDATION);
        registry.register("ingredientCheck", INGREDIENT_CHECK);
        registry.register("stationAssignment", STATION_ASSIGNMENT);
        registry.register("cookingTimeEstimate", COOKING_TIME_ESTIMATE);
        registry.register("queueForPickup", QUEUE_FOR_PICKUP);
        registry.register("riderMatching", RIDER_MATCHING);
        registry.register("routeCalculation", ROUTE_CALCULATION);
        registry.register("etaEstimation", ETA_ESTIMATION);
        registry.register("realtimeTrackingSetup", REALTIME_TRACKING_SETUP);

        // Build sub-graphs
        Graph kitchenGraph = buildKitchenDispatchSubGraph();
        Graph deliveryGraph = buildDeliveryCoordinationSubGraph();

        // Wrap as SubGraphOperators
        SubGraphOperator kitchenSubGraph = new SubGraphOperator(kitchenGraph, registry);
        SubGraphOperator deliverySubGraph = new SubGraphOperator(deliveryGraph, registry);

        // Build main graph
        Graph mainGraph = Graph.builder("restaurantOrderPipeline")
                .node("receiveOrder", RECEIVE_ORDER)
                    .input((results, ctx) -> new OrderRequest(
                            ctx.get("orderId", String.class),
                            ctx.get("restaurantId", String.class),
                            ctx.get("items", List.class),
                            ctx.get("orderType", String.class),
                            ctx.get("customerAddress", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("paymentVerification", PAYMENT_VERIFICATION)
                    .dependsOn("receiveOrder")
                    .input((results, ctx) -> new PaymentInput(
                            results.get("receiveOrder", ReceivedOrder.class).orderId(),
                            ctx.get("totalAmount", Double.class)))
                    .timeout(Duration.ofSeconds(10))
                .node("kitchenDispatch", kitchenSubGraph)
                    .dependsOn("paymentVerification")
                    .input((results, ctx) -> {
                        var received = results.get("receiveOrder", ReceivedOrder.class);
                        return Map.of(
                                "orderId", received.orderId(),
                                "restaurantId", received.restaurantId(),
                                "items", received.items());
                    })
                    .timeout(Duration.ofSeconds(30))
                .node("qualityCheck", QUALITY_CHECK)
                    .dependsOn("kitchenDispatch")
                    .input((results, ctx) -> {
                        var received = results.get("receiveOrder", ReceivedOrder.class);
                        var kitchenOut = (Map<String, Object>) results.getRaw("kitchenDispatch");
                        var queue = (QueueResult) kitchenOut.get("queueForPickup");
                        return new QualityCheckInput(
                                received.orderId(), received.orderType(), queue.queuePosition());
                    })
                .node("prepareDineIn", PREPARE_DINE_IN)
                    .dependsOn("qualityCheck")
                    .input((results, ctx) -> new DineInInput(
                            results.get("qualityCheck", QualityCheckResult.class).orderId(),
                            ctx.get("tableNumber", String.class)))
                .node("deliveryCoordination", deliverySubGraph)
                    .dependsOn("qualityCheck")
                    .input((results, ctx) -> {
                        var received = results.get("receiveOrder", ReceivedOrder.class);
                        return Map.of(
                                "orderId", received.orderId(),
                                "customerAddress", received.customerAddress());
                    })
                    .timeout(Duration.ofSeconds(30))
                .branch("qualityCheck")
                    .on("orderType")
                    .when(val -> "dineIn".equals(val), "prepareDineIn")
                    .otherwise("deliveryCoordination")
                .node("orderComplete", ORDER_COMPLETE)
                    .dependsOn("prepareDineIn", "deliveryCoordination")
                    .input((results, ctx) -> {
                        String orderId = ctx.get("orderId", String.class);
                        if (results.getRaw("prepareDineIn") != null) {
                            var dineIn = results.get("prepareDineIn", DineInReady.class);
                            return new OrderCompleteInput(orderId, "dineIn",
                                    "Table " + dineIn.tableNumber());
                        } else {
                            var deliveryOut = (Map<String, Object>) results.getRaw("deliveryCoordination");
                            var tracking = (TrackingSetupResult) deliveryOut.get("realtimeTrackingSetup");
                            return new OrderCompleteInput(orderId, "delivery",
                                    "Tracking: " + tracking.trackingUrl());
                        }
                    })
                .build();

        // Execute
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "orderId", "ORD-5521",
                "restaurantId", "REST-LUIGI-01",
                "items", List.of("margherita-pizza", "caesar-salad", "tiramisu"),
                "orderType", "delivery",
                "customerAddress", "42 Elm Street, Apt 7B",
                "totalAmount", 47.85,
                "tableNumber", "T-12"
        ));

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "receiveOrder", RECEIVE_ORDER,
                "paymentVerification", PAYMENT_VERIFICATION,
                "kitchenDispatch", kitchenSubGraph,
                "qualityCheck", QUALITY_CHECK,
                "prepareDineIn", PREPARE_DINE_IN,
                "deliveryCoordination", deliverySubGraph,
                "orderComplete", ORDER_COMPLETE
        ));

        // Print results
        System.out.println("\n═══ Restaurant Order Pipeline Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("orderComplete") == NodeStatus.COMPLETED) {
            OrderCompletion completion = result.getOutput("orderComplete", OrderCompletion.class);
            System.out.println("Order completed: " + completion);
        }

        if (result.getStatus("kitchenDispatch") == NodeStatus.COMPLETED) {
            System.out.println("Kitchen sub-graph output: " + result.results().getRaw("kitchenDispatch"));
        }

        if (result.getStatus("deliveryCoordination") == NodeStatus.COMPLETED) {
            System.out.println("Delivery sub-graph output: " + result.results().getRaw("deliveryCoordination"));
        }

        if (result.getStatus("prepareDineIn") == NodeStatus.COMPLETED) {
            DineInReady dineIn = result.getOutput("prepareDineIn", DineInReady.class);
            System.out.println("Dine-in ready: " + dineIn);
        }
    }
}
