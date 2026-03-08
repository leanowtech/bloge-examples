package com.leanowtech.bloge.examples.catering;

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
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DSL version of the restaurant order pipeline with sequential sub-graphs and branching.
 * <p>
 * Sub-graphs are built via Java API and registered with the DslCompiler,
 * then referenced in DSL using {@code subgraph("name")} syntax.
 */
@SuppressWarnings("preview")
public class RestaurantOrderPipelineDslExample {

    // --- Main graph operators (Map-based for DSL) ---

    static final Operator<Map<String, Object>, Map<String, Object>> RECEIVE_ORDER = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of(
                "orderId", input.get("orderId"),
                "restaurantId", input.get("restaurantId"),
                "items", input.get("items"),
                "orderType", input.get("orderType"),
                "customerAddress", input.get("customerAddress"),
                "status", "RECEIVED");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> PAYMENT_VERIFICATION = (input, ctx) -> {
        Thread.sleep(80);
        String orderId = (String) input.get("orderId");
        return Map.of("orderId", orderId, "transactionId", "TXN-" + orderId, "verified", true);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> QUALITY_CHECK = (input, ctx) -> {
        Thread.sleep(40);
        return Map.of(
                "orderId", input.get("orderId"),
                "orderType", input.get("orderType"),
                "qualityScore", 9.2,
                "passed", true);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> PREPARE_DINE_IN = (input, ctx) -> {
        Thread.sleep(25);
        return Map.of(
                "orderId", input.get("orderId"),
                "tableNumber", input.get("tableNumber"),
                "status", "TABLE_READY");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ORDER_COMPLETE = (input, ctx) -> {
        Thread.sleep(15);
        return Map.of(
                "orderId", input.get("orderId"),
                "completionChannel", input.get("completionChannel"),
                "timestamp", "2025-01-18T19:45:00Z",
                "status", "COMPLETED");
    };

    // --- Kitchen dispatch sub-graph operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> DISH_VALIDATION = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of("validatedDishes", input.get("items"), "allValid", true);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> INGREDIENT_CHECK = (input, ctx) -> {
        Thread.sleep(35);
        return Map.of("allAvailable", true, "notes", "All ingredients in stock");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> STATION_ASSIGNMENT = (input, ctx) -> {
        Thread.sleep(15);
        return Map.of("station", "GRILL-A", "chefName", "Chef Marco");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> COOKING_TIME_ESTIMATE = (input, ctx) -> {
        Thread.sleep(10);
        return Map.of("estimatedMinutes", 25);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> QUEUE_FOR_PICKUP = (input, ctx) -> {
        Thread.sleep(10);
        String orderId = (String) input.get("orderId");
        return Map.of("queuePosition", "Q-" + orderId, "estimatedReady", "19:30");
    };

    // --- Delivery coordination sub-graph operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> RIDER_MATCHING = (input, ctx) -> {
        Thread.sleep(60);
        return Map.of("riderId", "RIDER-77", "riderName", "Carlos");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ROUTE_CALCULATION = (input, ctx) -> {
        Thread.sleep(45);
        return Map.of("distanceKm", 3.7, "routeSummary", "Via Main St → Oak Ave → Elm St");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ETA_ESTIMATION = (input, ctx) -> {
        Thread.sleep(20);
        double distance = ((Number) input.get("distanceKm")).doubleValue();
        int eta = (int) (distance * 4) + 5;
        return Map.of("etaMinutes", eta, "estimatedArrival", "19:55");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> REALTIME_TRACKING_SETUP = (input, ctx) -> {
        Thread.sleep(15);
        String orderId = (String) input.get("orderId");
        return Map.of("trackingId", "TRK-" + orderId,
                "trackingUrl", "https://track.example.com/TRK-" + orderId);
    };

    // --- Sub-graph construction (Java API, Map-based) ---

    public static Graph buildKitchenDispatchSubGraph() {
        return Graph.builder("kitchen-dispatch")
                .node("dishValidation", DISH_VALIDATION)
                    .input((results, ctx) -> Map.of(
                            "orderId", ctx.get("orderId", String.class),
                            "items", ctx.get("items", List.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("ingredientCheck", INGREDIENT_CHECK)
                    .dependsOn("dishValidation")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var dishes = (Map<String, Object>) results.getRaw("dishValidation");
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "dishes", dishes.get("validatedDishes"));
                    })
                .node("stationAssignment", STATION_ASSIGNMENT)
                    .dependsOn("ingredientCheck")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var dishes = (Map<String, Object>) results.getRaw("dishValidation");
                        var items = (List<?>) dishes.get("validatedDishes");
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "dishCount", items.size());
                    })
                .node("cookingTimeEstimate", COOKING_TIME_ESTIMATE)
                    .dependsOn("stationAssignment")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var station = (Map<String, Object>) results.getRaw("stationAssignment");
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "station", station.get("station"));
                    })
                .node("queueForPickup", QUEUE_FOR_PICKUP)
                    .dependsOn("cookingTimeEstimate")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var cooking = (Map<String, Object>) results.getRaw("cookingTimeEstimate");
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "estimatedMinutes", cooking.get("estimatedMinutes"));
                    })
                .build();
    }

    public static Graph buildDeliveryCoordinationSubGraph() {
        return Graph.builder("delivery-coordination")
                .node("riderMatching", RIDER_MATCHING)
                    .input((results, ctx) -> {
                        String address = ctx.get("customerAddress", String.class);
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "deliveryZone", address.split(",")[0].trim());
                    })
                    .retry(3, Duration.ofMillis(500), BackoffStrategy.JITTER)
                    .timeout(Duration.ofSeconds(10))
                .node("routeCalculation", ROUTE_CALCULATION)
                    .dependsOn("riderMatching")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var rider = (Map<String, Object>) results.getRaw("riderMatching");
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "riderId", rider.get("riderId"),
                                "customerAddress", ctx.get("customerAddress", String.class));
                    })
                .node("etaEstimation", ETA_ESTIMATION)
                    .dependsOn("routeCalculation")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var route = (Map<String, Object>) results.getRaw("routeCalculation");
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "distanceKm", route.get("distanceKm"));
                    })
                .node("realtimeTrackingSetup", REALTIME_TRACKING_SETUP)
                    .dependsOn("etaEstimation")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var rider = (Map<String, Object>) results.getRaw("riderMatching");
                        return Map.of(
                                "orderId", ctx.get("orderId", String.class),
                                "riderId", rider.get("riderId"));
                    })
                .build();
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // Register main graph operators
        // RECEIVE_ORDER: reads ctx.orderId/restaurantId/items/orderType/customerAddress → {orderId, ..., status}
        registry.register("ReceiveOrderOperator", RECEIVE_ORDER);
        // PAYMENT_VERIFICATION: reads receiveOrder.orderId + ctx.totalAmount → {orderId, transactionId, verified}
        registry.register("PaymentVerificationOperator", PAYMENT_VERIFICATION);
        // QUALITY_CHECK: reads receiveOrder.orderType + kitchenDispatch queue position → {orderId, orderType, qualityScore, passed}
        registry.register("QualityCheckOperator", QUALITY_CHECK);
        // PREPARE_DINE_IN: reads ctx.tableNumber → {orderId, tableNumber, status}
        registry.register("PrepareDineInOperator", PREPARE_DINE_IN);
        // ORDER_COMPLETE: reads ctx.orderId + qualityCheck.orderType → {orderId, completionChannel, timestamp, status}
        registry.register("OrderCompleteOperator", ORDER_COMPLETE);

        // Register sub-graph operators (resolved by operatorRef = node ID for lambdas)
        // DISH_VALIDATION: reads ctx.items → {validatedDishes, allValid}
        registry.register("dishValidation", DISH_VALIDATION);
        // INGREDIENT_CHECK: reads dishValidation.validatedDishes → {allAvailable, notes}
        registry.register("ingredientCheck", INGREDIENT_CHECK);
        // STATION_ASSIGNMENT: reads dishCount → {station, chefName}
        registry.register("stationAssignment", STATION_ASSIGNMENT);
        // COOKING_TIME_ESTIMATE: reads station → {estimatedMinutes}
        registry.register("cookingTimeEstimate", COOKING_TIME_ESTIMATE);
        // QUEUE_FOR_PICKUP: reads estimatedMinutes → {queuePosition, estimatedReady}
        registry.register("queueForPickup", QUEUE_FOR_PICKUP);
        // RIDER_MATCHING: reads deliveryZone → {riderId, riderName}; retry with jitter on failure
        registry.register("riderMatching", RIDER_MATCHING);
        // ROUTE_CALCULATION: reads riderId + customerAddress → {distanceKm, routeSummary}
        registry.register("routeCalculation", ROUTE_CALCULATION);
        // ETA_ESTIMATION: reads distanceKm → {etaMinutes, estimatedArrival}
        registry.register("etaEstimation", ETA_ESTIMATION);
        // REALTIME_TRACKING_SETUP: reads riderId → {trackingId, trackingUrl}
        registry.register("realtimeTrackingSetup", REALTIME_TRACKING_SETUP);

        // Build sub-graphs via Java API
        Graph kitchenGraph = buildKitchenDispatchSubGraph();
        Graph deliveryGraph = buildDeliveryCoordinationSubGraph();

        // Compile main graph from DSL with registered sub-graphs
        var compiler = new DslCompiler(registry);
        // register sub-graphs before loading main DSL
        compiler.registerSubGraph("kitchen-dispatch", kitchenGraph);
        compiler.registerSubGraph("delivery-coordination", deliveryGraph);

        String dsl = """
                graph restaurantOrderPipeline {
                  ///  receiveOrder: reads ctx order fields → {orderId, restaurantId, items, orderType, customerAddress, status}
                  node receiveOrder : ReceiveOrderOperator {
                    input {
                      orderId         = ctx.orderId
                      restaurantId    = ctx.restaurantId
                      items           = ctx.items
                      orderType       = ctx.orderType
                      customerAddress = ctx.customerAddress
                    }
                    timeout = 3s
                  }
                  ///  paymentVerification: reads receiveOrder.orderId + ctx.totalAmount → {orderId, transactionId, verified}
                  node paymentVerification : PaymentVerificationOperator {
                    depends_on = [receiveOrder]
                    input {
                      orderId     = receiveOrder.output.orderId
                      totalAmount = ctx.totalAmount
                    }
                    timeout = 10s
                  }
                  ///  kitchenDispatch: sub-graph dishValidation → ingredientCheck → stationAssignment → cookingTimeEstimate → queueForPickup
                  node kitchenDispatch : subgraph("kitchen-dispatch") {
                    depends_on = [paymentVerification]
                    input {
                      orderId      = receiveOrder.output.orderId
                      restaurantId = receiveOrder.output.restaurantId
                      items        = receiveOrder.output.items
                    }
                    timeout = 30s
                  }
                  ///  qualityCheck: reads receiveOrder.orderType + kitchenDispatch queue position → {orderId, orderType, qualityScore, passed}
                  node qualityCheck : QualityCheckOperator {
                    depends_on = [kitchenDispatch]
                    input {
                      orderId       = receiveOrder.output.orderId
                      orderType     = receiveOrder.output.orderType
                      queuePosition = kitchenDispatch.output.queueForPickup.queuePosition
                    }
                  }
                  ///  branch: "dineIn" → prepareDineIn; otherwise → deliveryCoordination sub-graph
                  branch on qualityCheck.output.orderType {
                    "dineIn"  -> prepareDineIn
                    otherwise -> deliveryCoordination
                  }
                  ///  prepareDineIn: reads ctx.tableNumber → {orderId, tableNumber, status}
                  node prepareDineIn : PrepareDineInOperator {
                    depends_on = [qualityCheck]
                    input {
                      orderId     = qualityCheck.output.orderId
                      tableNumber = ctx.tableNumber
                    }
                  }
                  ///  deliveryCoordination: sub-graph riderMatching → routeCalculation → etaEstimation → realtimeTrackingSetup
                  node deliveryCoordination : subgraph("delivery-coordination") {
                    depends_on = [qualityCheck]
                    input {
                      orderId         = receiveOrder.output.orderId
                      customerAddress = receiveOrder.output.customerAddress
                    }
                    timeout = 30s
                  }
                  ///  orderComplete: reads ctx.orderId + qualityCheck.orderType → {orderId, completionChannel, timestamp, status}
                  node orderComplete : OrderCompleteOperator {
                    depends_on = [prepareDineIn, deliveryCoordination]
                    input {
                      orderId           = ctx.orderId
                      completionChannel = qualityCheck.output.orderType
                      details           = qualityCheck.output.orderId
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
                "orderId", "ORD-5521",
                "restaurantId", "REST-LUIGI-01",
                "items", List.of("margherita-pizza", "caesar-salad", "tiramisu"),
                "orderType", "delivery",
                "customerAddress", "42 Elm Street, Apt 7B",
                "totalAmount", 47.85,
                "tableNumber", "T-12"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        // Print results
        System.out.println("\n═══ DSL Restaurant Order Pipeline Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; sub-graph nodes return Map of child-node outputs keyed by node ID
        if (result.getStatus("orderComplete") == NodeStatus.COMPLETED) {
            System.out.println("Order completed: " + result.results().getRaw("orderComplete"));
        }

        if (result.getStatus("kitchenDispatch") == NodeStatus.COMPLETED) {
            System.out.println("Kitchen sub-graph output: " + result.results().getRaw("kitchenDispatch"));
        }

        if (result.getStatus("deliveryCoordination") == NodeStatus.COMPLETED) {
            System.out.println("Delivery sub-graph output: " + result.results().getRaw("deliveryCoordination"));
        }

        if (result.getStatus("prepareDineIn") == NodeStatus.COMPLETED) {
            System.out.println("Dine-in ready: " + result.results().getRaw("prepareDineIn"));
        }
    }
}
