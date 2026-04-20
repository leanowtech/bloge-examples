package com.leanowtech.bloge.examples.logistics;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Shipment planning workflow combining warehouse lookup, carrier selection, route planning,
 * and mode-based dispatch.
 *
 * <p>This example demonstrates parallel planning inputs (warehouse, carrier, route),
 * cost aggregation, and branch-based dispatch selection.
 *
 * <p>Graph layout:
 * <pre>
 * fetchOrder
 *   -> lookupWarehouse + selectCarrier + optimizeRoute
 *   -> calculateCost
 *   -> decideShipMode
 *      -> dispatchExpress | dispatchStandard | dispatchConsolidated
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the graph with sample shipment input.
 */
public class ShipmentPlanningExample {

    public record OrderQuery(String orderId) {}
    public record ShipmentOrder(String orderId, String origin, String destination, double weight, boolean isUrgent, List<String> items) {}

    public record WarehouseQuery(String origin) {}
    public record WarehouseResult(String warehouseId, String location, int stock) {}

    public record CarrierQuery(String destination, double weight) {}
    public record CarrierResult(String carrierId, String name, double baseRate, int estimatedDays) {}

    public record RouteQuery(String origin, String destination) {}
    public record RouteResult(double distance, List<String> waypoints, int estimatedHours) {}

    public record CostInput(WarehouseResult warehouse, CarrierResult carrier, RouteResult route) {}
    public record CostEstimate(double shippingCost, double insurance, double total, String suggestedMode) {}

    public record ShipModeInput(CostEstimate cost, ShipmentOrder order) {}
    public record ShipModeDecision(String mode, String reason) {}

    public record DispatchInput(String orderId, String mode) {}
    public record DispatchResult(String trackingId, String mode, String estimatedArrival) {}

    static final Operator<OrderQuery, ShipmentOrder> FETCH_ORDER = (input, ctx) -> {
        Thread.sleep(50);
        return new ShipmentOrder("ORD-8001", "Shanghai", "Los Angeles", 25.5, true,
                List.of("electronics", "accessories"));
    };

    static final Operator<WarehouseQuery, WarehouseResult> LOOKUP_WAREHOUSE = (input, ctx) -> {
        Thread.sleep(60);
        return new WarehouseResult("WH-PVG-03", "Shanghai Pudong", 1200);
    };

    static final Operator<CarrierQuery, CarrierResult> SELECT_CARRIER = (input, ctx) -> {
        Thread.sleep(100);
        return new CarrierResult("CR-FAST-01", "Express Global", 45.0, 3);
    };

    static final Operator<RouteQuery, RouteResult> OPTIMIZE_ROUTE = (input, ctx) -> {
        Thread.sleep(150);
        return new RouteResult(11500.0,
                List.of("Shanghai", "Tokyo", "Honolulu", "Los Angeles"), 72);
    };

    static final Operator<CostInput, CostEstimate> CALCULATE_COST = (input, ctx) -> {
        Thread.sleep(30);
        double distanceFactor = input.route().distance() / 1000.0;
        double shippingCost = input.carrier().baseRate() * distanceFactor;
        double insurance = shippingCost * 0.05;
        double total = shippingCost + insurance;
        String suggestedMode = total > 500 ? "express" : "standard";
        return new CostEstimate(shippingCost, insurance, total, suggestedMode);
    };

    static final Operator<ShipModeInput, ShipModeDecision> DECIDE_SHIP_MODE = (input, ctx) -> {
        Thread.sleep(20);
        if (input.order().isUrgent()) {
            return new ShipModeDecision("express", "Order is marked urgent");
        } else if (input.cost().total() < 100) {
            return new ShipModeDecision("standard", "Cost is within standard threshold");
        }
        return new ShipModeDecision("consolidated", "High cost non-urgent shipment");
    };

    static final Operator<DispatchInput, DispatchResult> DISPATCH_EXPRESS = (input, ctx) -> {
        Thread.sleep(30);
        return new DispatchResult("TRK-EXP-001", "express", "3 days");
    };

    static final Operator<DispatchInput, DispatchResult> DISPATCH_STANDARD = (input, ctx) -> {
        Thread.sleep(30);
        return new DispatchResult("TRK-STD-001", "standard", "7 days");
    };

    static final Operator<DispatchInput, DispatchResult> DISPATCH_CONSOLIDATED = (input, ctx) -> {
        Thread.sleep(30);
        return new DispatchResult("TRK-CON-001", "consolidated", "14 days");
    };

    public static Graph buildGraph() {
        var builder = Graph.builder("shipmentPlanning")
                .node("fetchOrder", FETCH_ORDER)
                    .input((results, ctx) -> new OrderQuery(ctx.get("orderId", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("lookupWarehouse", LOOKUP_WAREHOUSE)
                    .dependsOn("fetchOrder")
                    .input((results, ctx) -> new WarehouseQuery(
                            results.get("fetchOrder", ShipmentOrder.class).origin()))
                    .timeout(Duration.ofSeconds(2))
                .node("selectCarrier", SELECT_CARRIER)
                    .dependsOn("fetchOrder")
                    .input((results, ctx) -> new CarrierQuery(
                            results.get("fetchOrder", ShipmentOrder.class).destination(),
                            results.get("fetchOrder", ShipmentOrder.class).weight()))
                    .retry(2, Duration.ofMillis(200), BackoffStrategy.EXPONENTIAL)
                    .fallback(ex -> new CarrierResult("DEFAULT", "Standard Post", 15.0, 7))
                .node("optimizeRoute", OPTIMIZE_ROUTE)
                    .dependsOn("fetchOrder")
                    .input((results, ctx) -> new RouteQuery(
                            results.get("fetchOrder", ShipmentOrder.class).origin(),
                            results.get("fetchOrder", ShipmentOrder.class).destination()))
                    .timeout(Duration.ofSeconds(5))
                .node("calculateCost", CALCULATE_COST)
                    .dependsOn("lookupWarehouse", "selectCarrier", "optimizeRoute")
                    .input((results, ctx) -> new CostInput(
                            results.get("lookupWarehouse", WarehouseResult.class),
                            results.get("selectCarrier", CarrierResult.class),
                            results.get("optimizeRoute", RouteResult.class)))
                .node("decideShipMode", DECIDE_SHIP_MODE)
                    .dependsOn("calculateCost")
                    .input((results, ctx) -> new ShipModeInput(
                            results.get("calculateCost", CostEstimate.class),
                            results.get("fetchOrder", ShipmentOrder.class)))
                .node("dispatchExpress", DISPATCH_EXPRESS)
                    .dependsOn("decideShipMode")
                    .input((results, ctx) -> new DispatchInput(
                            results.get("fetchOrder", ShipmentOrder.class).orderId(),
                            "express"))
                .node("dispatchStandard", DISPATCH_STANDARD)
                    .dependsOn("decideShipMode")
                    .input((results, ctx) -> new DispatchInput(
                            results.get("fetchOrder", ShipmentOrder.class).orderId(),
                            "standard"))
                .node("dispatchConsolidated", DISPATCH_CONSOLIDATED)
                    .dependsOn("decideShipMode")
                    .input((results, ctx) -> new DispatchInput(
                            results.get("fetchOrder", ShipmentOrder.class).orderId(),
                            "consolidated"))
                .branch("decideShipMode")
                    .on("mode")
                    .when(val -> "express".equals(val), "dispatchExpress")
                    .when(val -> "standard".equals(val), "dispatchStandard")
                    .otherwise("dispatchConsolidated");

        return builder.build();
    }

    @SuppressWarnings("preview")
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();

        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of(
                "orderId", "ORD-8001"
        ));

        GraphResult result = engine.executeWithOperators(graph, ctx, Map.of(
                "fetchOrder", FETCH_ORDER,
                "lookupWarehouse", LOOKUP_WAREHOUSE,
                "selectCarrier", SELECT_CARRIER,
                "optimizeRoute", OPTIMIZE_ROUTE,
                "calculateCost", CALCULATE_COST,
                "decideShipMode", DECIDE_SHIP_MODE,
                "dispatchExpress", DISPATCH_EXPRESS,
                "dispatchStandard", DISPATCH_STANDARD,
                "dispatchConsolidated", DISPATCH_CONSOLIDATED
        ));

        System.out.println("\n═══ Shipment Planning Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-22s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("dispatchExpress") == NodeStatus.COMPLETED) {
            DispatchResult dispatch = result.getOutput("dispatchExpress", DispatchResult.class);
            System.out.println("Express dispatch: " + dispatch);
        } else if (result.getStatus("dispatchStandard") == NodeStatus.COMPLETED) {
            DispatchResult dispatch = result.getOutput("dispatchStandard", DispatchResult.class);
            System.out.println("Standard dispatch: " + dispatch);
        } else if (result.getStatus("dispatchConsolidated") == NodeStatus.COMPLETED) {
            DispatchResult dispatch = result.getOutput("dispatchConsolidated", DispatchResult.class);
            System.out.println("Consolidated dispatch: " + dispatch);
        }
    }
}
