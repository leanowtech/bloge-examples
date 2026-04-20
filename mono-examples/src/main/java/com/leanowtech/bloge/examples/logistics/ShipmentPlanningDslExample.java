package com.leanowtech.bloge.examples.logistics;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.util.List;
import java.util.Map;

/**
 * DSL shipment-planning workflow example.
 *
 * <p>This example compiles logistics planning from DSL and executes it through
 * registered Map-based operators for routing, costing, and dispatch branching.
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
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings("preview")
public class ShipmentPlanningDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> FETCH_ORDER = (input, ctx) -> {
        Thread.sleep(50);
        return Map.of(
                "orderId", "ORD-8001",
                "origin", "Shanghai",
                "destination", "Los Angeles",
                "weight", 25.5,
                "isUrgent", true,
                "items", List.of("electronics", "accessories")
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> LOOKUP_WAREHOUSE = (input, ctx) -> {
        Thread.sleep(60);
        return Map.of("warehouseId", "WH-PVG-03", "location", "Shanghai Pudong", "stock", 1200);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SELECT_CARRIER = (input, ctx) -> {
        Thread.sleep(100);
        return Map.of("carrierId", "CR-FAST-01", "name", "Express Global", "baseRate", 45.0, "estimatedDays", 3);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> OPTIMIZE_ROUTE = (input, ctx) -> {
        Thread.sleep(150);
        return Map.of(
                "distance", 11500.0,
                "waypoints", List.of("Shanghai", "Tokyo", "Honolulu", "Los Angeles"),
                "estimatedHours", 72
        );
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> CALCULATE_COST = (input, ctx) -> {
        Thread.sleep(30);
        var carrier = (Map<String, Object>) input.get("carrier");
        var route = (Map<String, Object>) input.get("route");
        double baseRate = ((Number) carrier.get("baseRate")).doubleValue();
        double distance = ((Number) route.get("distance")).doubleValue();
        double distanceFactor = distance / 1000.0;
        double shippingCost = baseRate * distanceFactor;
        double insurance = shippingCost * 0.05;
        double total = shippingCost + insurance;
        return Map.of("shippingCost", shippingCost, "insurance", insurance, "total", total, "suggestedMode", "express");
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> DECIDE_SHIP_MODE = (input, ctx) -> {
        Thread.sleep(20);
        var order = (Map<String, Object>) input.get("order");
        var cost = (Map<String, Object>) input.get("cost");
        boolean isUrgent = (Boolean) order.get("isUrgent");
        double total = ((Number) cost.get("total")).doubleValue();
        if (isUrgent) {
            return Map.of("mode", "express", "reason", "Order is marked urgent");
        } else if (total < 100) {
            return Map.of("mode", "standard", "reason", "Cost is within standard threshold");
        }
        return Map.of("mode", "consolidated", "reason", "High cost non-urgent shipment");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DISPATCH_EXPRESS = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of("trackingId", "TRK-EXP-001", "mode", "express", "estimatedArrival", "3 days");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DISPATCH_STANDARD = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of("trackingId", "TRK-STD-001", "mode", "standard", "estimatedArrival", "7 days");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DISPATCH_CONSOLIDATED = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of("trackingId", "TRK-CON-001", "mode", "consolidated", "estimatedArrival", "14 days");
    };

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        // ── Operator Registrations ─────────────────────────────────────────────
        // FETCH_ORDER: reads ctx.orderId → returns {orderId, origin, destination, weight, isUrgent, items}
        registry.register("FetchOrderOperator", FETCH_ORDER);
        // LOOKUP_WAREHOUSE: reads origin → returns {warehouseId, location, stock}
        registry.register("LookupWarehouseOperator", LOOKUP_WAREHOUSE);
        // SELECT_CARRIER: reads destination, weight → returns {carrierId, name, baseRate, estimatedDays}; retries 2×
        registry.register("SelectCarrierOperator", SELECT_CARRIER);
        // OPTIMIZE_ROUTE: reads origin, destination → returns {distance, waypoints, estimatedHours}
        registry.register("OptimizeRouteOperator", OPTIMIZE_ROUTE);
        // CALCULATE_COST: fan-in of warehouse+carrier+route → returns {shippingCost, insurance, total, suggestedMode}
        registry.register("CalculateCostOperator", CALCULATE_COST);
        // DECIDE_SHIP_MODE: reads cost.total, order.isUrgent → returns {mode, reason}
        registry.register("DecideShipModeOperator", DECIDE_SHIP_MODE);
        // DISPATCH_EXPRESS: reads orderId, mode → returns {trackingId, mode, estimatedArrival}
        registry.register("DispatchExpressOperator", DISPATCH_EXPRESS);
        // DISPATCH_STANDARD: reads orderId, mode → returns {trackingId, mode, estimatedArrival}
        registry.register("DispatchStandardOperator", DISPATCH_STANDARD);
        // DISPATCH_CONSOLIDATED: reads orderId, mode → returns {trackingId, mode, estimatedArrival}
        registry.register("DispatchConsolidatedOperator", DISPATCH_CONSOLIDATED);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph shipmentPlanning {
                  ///  fetchOrder: reads ctx.orderId → {orderId, origin, destination, weight, isUrgent, items}
                  node fetchOrder : FetchOrderOperator {
                    input { orderId = ctx.orderId }
                    timeout = 3s
                  }
                  ///  parallel fan-out: lookupWarehouse, selectCarrier, optimizeRoute run concurrently
                  node lookupWarehouse : LookupWarehouseOperator {
                    depends_on = [fetchOrder]
                    input {
                      origin = fetchOrder.output.origin
                    }
                    timeout = 2s
                  }
                  node selectCarrier : SelectCarrierOperator {
                    depends_on = [fetchOrder]
                    input {
                      destination = fetchOrder.output.destination
                      weight      = fetchOrder.output.weight
                    }
                    retry = { attempts: 2, backoff: 200ms, strategy: exponential }
                    fallback = { carrierId: "DEFAULT", name: "Standard Post", baseRate: 15.0, estimatedDays: 7 }
                  }
                  node optimizeRoute : OptimizeRouteOperator {
                    depends_on = [fetchOrder]
                    input {
                      origin      = fetchOrder.output.origin
                      destination = fetchOrder.output.destination
                    }
                    timeout = 5s
                  }
                  ///  parallel fan-in: all 3 planning nodes run concurrently; calculateCost waits for all
                  node calculateCost : CalculateCostOperator {
                    depends_on = [lookupWarehouse, selectCarrier, optimizeRoute]
                    input {
                      warehouse = lookupWarehouse.output
                      carrier   = selectCarrier.output
                      route     = optimizeRoute.output
                    }
                  }
                  ///  decideShipMode: reads cost.total, order.isUrgent → {mode, reason}
                  node decideShipMode : DecideShipModeOperator {
                    depends_on = [calculateCost]
                    input {
                      cost  = calculateCost.output
                      order = fetchOrder.output
                    }
                  }
                  ///  branch on mode: express → dispatchExpress, standard → dispatchStandard, otherwise → dispatchConsolidated
                  branch on decideShipMode.output.mode {
                    "express"  -> dispatchExpress
                    "standard" -> dispatchStandard
                    otherwise  -> dispatchConsolidated
                  }
                  ///  branch outcomes: only one dispatch node will execute
                  node dispatchExpress : DispatchExpressOperator {
                    depends_on = [decideShipMode]
                    input {
                      orderId = fetchOrder.output.orderId
                      mode    = "express"
                    }
                  }
                  node dispatchStandard : DispatchStandardOperator {
                    depends_on = [decideShipMode]
                    input {
                      orderId = fetchOrder.output.orderId
                      mode    = "standard"
                    }
                  }
                  node dispatchConsolidated : DispatchConsolidatedOperator {
                    depends_on = [decideShipMode]
                    input {
                      orderId = fetchOrder.output.orderId
                      mode    = "consolidated"
                    }
                  }
                }
                """;

        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "orderId", "ORD-8001"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Shipment Planning Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-22s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; cast to Map<String,Object> if typed access is needed
        if (result.getStatus("dispatchExpress") == NodeStatus.COMPLETED) {
            System.out.println("Express dispatch: " + result.results().getRaw("dispatchExpress"));
        } else if (result.getStatus("dispatchStandard") == NodeStatus.COMPLETED) {
            System.out.println("Standard dispatch: " + result.results().getRaw("dispatchStandard"));
        } else if (result.getStatus("dispatchConsolidated") == NodeStatus.COMPLETED) {
            System.out.println("Consolidated dispatch: " + result.results().getRaw("dispatchConsolidated"));
        }
    }
}
