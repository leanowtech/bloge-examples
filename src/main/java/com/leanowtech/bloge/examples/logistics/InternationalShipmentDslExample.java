package com.leanowtech.bloge.examples.logistics;

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
 * DSL version of the international shipment pipeline with parallel sub-graphs.
 * <p>
 * Sub-graphs are built via Java API and registered with the DslCompiler,
 * then referenced in DSL using {@code subgraph("name")} syntax.
 */
@SuppressWarnings("preview")
public class InternationalShipmentDslExample {

    // --- Main graph operators (Map-based for DSL) ---

    static final Operator<Map<String, Object>, Map<String, Object>> RECEIVE_REQUEST = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of(
                "shipmentId", input.get("shipmentId"),
                "originCountry", input.get("originCountry"),
                "destinationCountry", input.get("destinationCountry"),
                "weightKg", input.get("weightKg"),
                "commodityType", input.get("commodityType"),
                "status", "RECEIVED");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> VALIDATE_ADDRESS = (input, ctx) -> {
        Thread.sleep(40);
        String origin = (String) input.get("originCountry");
        String originPort = "CN".equals(origin) ? "CNSHA" : "USLAX";
        String dest = (String) input.get("destinationCountry");
        String destPort = "US".equals(dest) ? "USLAX" : "DEHAM";
        return Map.of(
                "shipmentId", input.get("shipmentId"),
                "originPort", originPort,
                "destinationPort", destPort,
                "addressValid", true);
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> BOOKING_CONFIRMATION = (input, ctx) -> {
        Thread.sleep(35);
        var route = (Map<String, Object>) input.get("route");
        var customs = (Map<String, Object>) input.get("customs");
        String clearanceRef = customs.containsKey("clearanceRef")
                ? (String) customs.get("clearanceRef")
                : (String) customs.get("rejectionRef");
        return Map.of(
                "shipmentId", input.get("shipmentId"),
                "bookingRef", "BK-" + input.get("shipmentId"),
                "carrier", route.get("selectedCarrier"),
                "estimatedDeparture", "2025-02-15");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> TRACKING_SETUP = (input, ctx) -> {
        Thread.sleep(25);
        String shipmentId = (String) input.get("shipmentId");
        String trackingNum = "TRK-" + shipmentId;
        return Map.of(
                "shipmentId", shipmentId,
                "trackingNumber", trackingNum,
                "trackingUrl", "https://tracking.example.com/" + trackingNum);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SEND_NOTIFICATION = (input, ctx) -> {
        Thread.sleep(20);
        String trackingNumber = (String) input.get("trackingNumber");
        return Map.of(
                "shipmentId", input.get("shipmentId"),
                "channel", "email",
                "message", "Shipment booked. Tracking: " + trackingNumber);
    };

    // --- Customs clearance sub-graph operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> DOC_PREP = (input, ctx) -> {
        Thread.sleep(50);
        String shipmentId = (String) input.get("shipmentId");
        return Map.of(
                "shipmentId", shipmentId,
                "invoiceRef", "INV-" + shipmentId,
                "packingListRef", "PKL-" + shipmentId,
                "certificateOfOrigin", "COO-" + input.get("originCountry"));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> HS_CODE = (input, ctx) -> {
        Thread.sleep(60);
        String commodity = (String) input.get("commodityType");
        String hsCode = "electronics".equals(commodity) ? "8471.30" : "6204.62";
        return Map.of("hsCode", hsCode, "description", commodity + " classification", "chapter", "Chapter 84");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DUTY_CALC = (input, ctx) -> {
        Thread.sleep(30);
        String hsCode = (String) input.get("hsCode");
        double weightKg = ((Number) input.get("weightKg")).doubleValue();
        double rate = hsCode.startsWith("8471") ? 0.025 : 0.12;
        double dutyAmount = rate * weightKg * 150.0;
        return Map.of("dutyRate", rate, "dutyAmount", Math.round(dutyAmount * 100.0) / 100.0, "currency", "USD");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CUSTOMS_DECL = (input, ctx) -> {
        Thread.sleep(80);
        String shipmentId = (String) input.get("shipmentId");
        return Map.of("declarationId", "DECL-" + shipmentId, "status", "approved",
                "reviewNotes", "All documents verified");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CLEARANCE_APPROVED = (input, ctx) -> {
        Thread.sleep(20);
        String declId = (String) input.get("declarationId");
        return Map.of("clearanceRef", "CLR-" + declId, "approvalDate", "2025-02-10", "status", "CLEARED");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CLEARANCE_REJECTED = (input, ctx) -> {
        Thread.sleep(15);
        String declId = (String) input.get("declarationId");
        return Map.of("rejectionRef", "REJ-" + declId, "reason", input.get("reviewNotes"), "status", "REJECTED");
    };

    // --- Route optimization sub-graph operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> CARRIER_QUERY = (input, ctx) -> {
        Thread.sleep(70);
        return Map.of(
                "carrierIds", List.of("MAERSK", "MSC", "COSCO"),
                "rates", List.of(2800.0, 2650.0, 2400.0),
                "transitDays", List.of(18, 21, 25));
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> RATE_COMPARISON = (input, ctx) -> {
        Thread.sleep(25);
        var carrierIds = (List<String>) input.get("carrierIds");
        var rates = (List<Double>) input.get("rates");
        double minRate = Double.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < rates.size(); i++) {
            if (rates.get(i) < minRate) {
                minRate = rates.get(i);
                bestIdx = i;
            }
        }
        double maxRate = rates.stream().mapToDouble(d -> d).max().orElse(0);
        return Map.of("bestCarrierId", carrierIds.get(bestIdx), "bestRate", minRate, "savings", maxRate - minRate);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> TRANSIT_TIME = (input, ctx) -> {
        Thread.sleep(35);
        String carrierId = (String) input.get("carrierId");
        String originPort = (String) input.get("originPort");
        String destPort = (String) input.get("destinationPort");
        int days = "COSCO".equals(carrierId) ? 25 : 18;
        return Map.of(
                "transitDays", days,
                "routeDescription", originPort + " → " + destPort,
                "legs", List.of(originPort, "SGSIN", destPort));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> OPTIMAL_ROUTE = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "selectedCarrier", input.get("carrierId"),
                "selectedRoute", input.get("routeDescription"),
                "cost", input.get("rate"),
                "estimatedDays", input.get("transitDays"));
    };

    // --- Sub-graph construction (Java API, Map-based) ---

    public static Graph buildCustomsClearanceSubGraph() {
        return Graph.builder("customs-clearance")
                .node("documentPreparation", DOC_PREP)
                    .input((results, ctx) -> Map.of(
                            "shipmentId", ctx.get("shipmentId", String.class),
                            "commodityType", ctx.get("commodityType", String.class),
                            "originCountry", ctx.get("originCountry", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("hsCodeClassification", HS_CODE)
                    .dependsOn("documentPreparation")
                    .input((results, ctx) -> Map.of(
                            "commodityType", ctx.get("commodityType", String.class),
                            "destinationCountry", ctx.get("destinationCountry", String.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.EXPONENTIAL)
                .node("dutyCalculation", DUTY_CALC)
                    .dependsOn("hsCodeClassification")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var hsResult = (Map<String, Object>) results.getRaw("hsCodeClassification");
                        return Map.of(
                                "hsCode", hsResult.get("hsCode"),
                                "weightKg", ctx.get("weightKg", Double.class),
                                "destinationCountry", ctx.get("destinationCountry", String.class));
                    })
                .node("customsDeclaration", CUSTOMS_DECL)
                    .dependsOn("dutyCalculation")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var hsResult = (Map<String, Object>) results.getRaw("hsCodeClassification");
                        @SuppressWarnings("unchecked")
                        var dutyResult = (Map<String, Object>) results.getRaw("dutyCalculation");
                        @SuppressWarnings("unchecked")
                        var docResult = (Map<String, Object>) results.getRaw("documentPreparation");
                        return Map.of(
                                "shipmentId", ctx.get("shipmentId", String.class),
                                "hsCode", hsResult.get("hsCode"),
                                "dutyAmount", dutyResult.get("dutyAmount"),
                                "invoiceRef", docResult.get("invoiceRef"));
                    })
                    .timeout(Duration.ofSeconds(10))
                    .retry(2, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                .node("clearanceApproved", CLEARANCE_APPROVED)
                    .dependsOn("customsDeclaration")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var decl = (Map<String, Object>) results.getRaw("customsDeclaration");
                        return Map.of("declarationId", decl.get("declarationId"));
                    })
                .node("clearanceRejected", CLEARANCE_REJECTED)
                    .dependsOn("customsDeclaration")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var decl = (Map<String, Object>) results.getRaw("customsDeclaration");
                        return Map.of(
                                "declarationId", decl.get("declarationId"),
                                "reviewNotes", decl.get("reviewNotes"));
                    })
                .branch("customsDeclaration")
                    .on("status")
                    .when(val -> "approved".equals(val), "clearanceApproved")
                    .otherwise("clearanceRejected")
                .build();
    }

    public static Graph buildRouteOptimizationSubGraph() {
        return Graph.builder("route-optimization")
                .node("carrierQuery", CARRIER_QUERY)
                    .input((results, ctx) -> Map.of(
                            "originPort", ctx.get("originPort", String.class),
                            "destinationPort", ctx.get("destinationPort", String.class),
                            "weightKg", ctx.get("weightKg", Double.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.EXPONENTIAL)
                .node("rateComparison", RATE_COMPARISON)
                    .dependsOn("carrierQuery")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var carriers = (Map<String, Object>) results.getRaw("carrierQuery");
                        return Map.of(
                                "carrierIds", carriers.get("carrierIds"),
                                "rates", carriers.get("rates"));
                    })
                .node("transitTimeEstimation", TRANSIT_TIME)
                    .dependsOn("rateComparison")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var rate = (Map<String, Object>) results.getRaw("rateComparison");
                        return Map.of(
                                "carrierId", rate.get("bestCarrierId"),
                                "originPort", ctx.get("originPort", String.class),
                                "destinationPort", ctx.get("destinationPort", String.class));
                    })
                    .timeout(Duration.ofSeconds(5))
                .node("optimalRouteSelection", OPTIMAL_ROUTE)
                    .dependsOn("rateComparison", "transitTimeEstimation")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var rate = (Map<String, Object>) results.getRaw("rateComparison");
                        @SuppressWarnings("unchecked")
                        var transit = (Map<String, Object>) results.getRaw("transitTimeEstimation");
                        return Map.of(
                                "carrierId", rate.get("bestCarrierId"),
                                "rate", rate.get("bestRate"),
                                "transitDays", transit.get("transitDays"),
                                "routeDescription", transit.get("routeDescription"));
                    })
                .build();
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // Register main graph operators
        // RECEIVE_REQUEST: reads ctx shipment fields → returns {shipmentId, originCountry, destinationCountry, weightKg, commodityType}
        registry.register("ReceiveRequestOperator", RECEIVE_REQUEST);
        // VALIDATE_ADDRESS: reads shipmentId, originCountry, destinationCountry → returns {originPort, destinationPort, addressValid}
        registry.register("ValidateAddressOperator", VALIDATE_ADDRESS);
        // BOOKING_CONFIRMATION: reads shipmentId, route, customs → returns {bookingRef, carrier, estimatedDeparture}
        registry.register("BookingConfirmationOperator", BOOKING_CONFIRMATION);
        // TRACKING_SETUP: reads shipmentId, bookingRef → returns {trackingNumber, trackingUrl}
        registry.register("TrackingSetupOperator", TRACKING_SETUP);
        // SEND_NOTIFICATION: reads shipmentId, trackingNumber → returns {channel, message}
        registry.register("SendNotificationOperator", SEND_NOTIFICATION);

        // Register sub-graph operators (resolved by operatorRef = node ID for lambdas)
        // DOC_PREP: reads shipmentId, commodityType, originCountry → returns {invoiceRef, packingListRef, certificateOfOrigin}
        registry.register("documentPreparation", DOC_PREP);
        // HS_CODE: reads commodityType, destinationCountry → returns {hsCode, description, chapter}
        registry.register("hsCodeClassification", HS_CODE);
        // DUTY_CALC: reads hsCode, weightKg, destinationCountry → returns {dutyRate, dutyAmount, currency}
        registry.register("dutyCalculation", DUTY_CALC);
        // CUSTOMS_DECL: reads shipmentId, hsCode, dutyAmount, invoiceRef → returns {declarationId, status, reviewNotes}
        registry.register("customsDeclaration", CUSTOMS_DECL);
        // CLEARANCE_APPROVED: reads declarationId → returns {clearanceRef, approvalDate, status}
        registry.register("clearanceApproved", CLEARANCE_APPROVED);
        // CLEARANCE_REJECTED: reads declarationId, reviewNotes → returns {rejectionRef, reason, status}
        registry.register("clearanceRejected", CLEARANCE_REJECTED);
        // CARRIER_QUERY: reads originPort, destinationPort, weightKg → returns {carrierIds, rates, transitDays}
        registry.register("carrierQuery", CARRIER_QUERY);
        // RATE_COMPARISON: reads carrierIds, rates → returns {bestCarrierId, bestRate, savings}
        registry.register("rateComparison", RATE_COMPARISON);
        // TRANSIT_TIME: reads carrierId, originPort, destinationPort → returns {transitDays, routeDescription, legs}
        registry.register("transitTimeEstimation", TRANSIT_TIME);
        // OPTIMAL_ROUTE: reads carrierId, rate, transitDays, routeDescription → returns {selectedCarrier, selectedRoute, cost, estimatedDays}
        registry.register("optimalRouteSelection", OPTIMAL_ROUTE);

        // Build sub-graphs via Java API
        Graph customsGraph = buildCustomsClearanceSubGraph();
        Graph routeGraph = buildRouteOptimizationSubGraph();

        // Compile main graph from DSL with registered sub-graphs
        // register sub-graphs before loading main DSL; registerSubGraph() must precede compile()
        var compiler = new DslCompiler(registry);
        // sub-graph last-node output becomes the sub-graph node's output in the parent graph
        compiler.registerSubGraph("customs-clearance", customsGraph);
        compiler.registerSubGraph("route-optimization", routeGraph);

        String dsl = """
                graph internationalShipment {
                  ///  receiveRequest: reads ctx shipment fields → {shipmentId, originCountry, destinationCountry, weightKg, commodityType}
                  node receiveRequest : ReceiveRequestOperator {
                    input {
                      shipmentId         = ctx.shipmentId
                      originCountry      = ctx.originCountry
                      destinationCountry = ctx.destinationCountry
                      weightKg           = ctx.weightKg
                      commodityType      = ctx.commodityType
                    }
                    timeout = 3s
                  }
                  ///  validateAddress: reads originCountry, destinationCountry → {originPort, destinationPort, addressValid}
                  node validateAddress : ValidateAddressOperator {
                    depends_on = [receiveRequest]
                    input {
                      shipmentId         = receiveRequest.output.shipmentId
                      originCountry      = receiveRequest.output.originCountry
                      destinationCountry = receiveRequest.output.destinationCountry
                    }
                    timeout = 3s
                  }
                  ///  parallel sub-graphs: customsClearance and routeOptimization run concurrently after validateAddress
                  node customsClearance : subgraph("customs-clearance") {
                    depends_on = [validateAddress]
                    input {
                      shipmentId         = receiveRequest.output.shipmentId
                      commodityType      = receiveRequest.output.commodityType
                      originCountry      = receiveRequest.output.originCountry
                      destinationCountry = receiveRequest.output.destinationCountry
                      weightKg           = receiveRequest.output.weightKg
                    }
                    timeout = 30s
                  }
                  ///  routeOptimization: runs route-optimization sub-graph; last node is optimalRouteSelection
                  node routeOptimization : subgraph("route-optimization") {
                    depends_on = [validateAddress]
                    input {
                      originPort      = validateAddress.output.originPort
                      destinationPort = validateAddress.output.destinationPort
                      weightKg        = receiveRequest.output.weightKg
                    }
                    timeout = 30s
                  }
                  ///  bookingConfirmation: fan-in of customsClearance+routeOptimization → {bookingRef, carrier, estimatedDeparture}
                  node bookingConfirmation : BookingConfirmationOperator {
                    depends_on = [customsClearance, routeOptimization]
                    input {
                      shipmentId = receiveRequest.output.shipmentId
                      route      = routeOptimization.output.optimalRouteSelection
                      customs    = customsClearance.output.clearanceApproved
                    }
                  }
                  ///  trackingSetup: reads shipmentId, bookingRef → {trackingNumber, trackingUrl}
                  node trackingSetup : TrackingSetupOperator {
                    depends_on = [bookingConfirmation]
                    input {
                      shipmentId = bookingConfirmation.output.shipmentId
                      bookingRef = bookingConfirmation.output.bookingRef
                    }
                  }
                  ///  sendNotification: reads shipmentId, trackingNumber → {channel, message}
                  node sendNotification : SendNotificationOperator {
                    depends_on = [trackingSetup]
                    input {
                      shipmentId     = trackingSetup.output.shipmentId
                      trackingNumber = trackingSetup.output.trackingNumber
                    }
                  }
                }
                """;

        var tokens = new Lexer(dsl).tokenize();
        GraphDef ast = new Parser(tokens).parse();
        // compile DSL; operators resolved by PascalCase name; sub-graphs resolved by registered name
        Graph graph = compiler.compile(ast);

        // Execute
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "shipmentId", "SHP-2025-00318",
                "originCountry", "CN",
                "destinationCountry", "US",
                "weightKg", 450.0,
                "commodityType", "electronics"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        // Print results
        System.out.println("\n═══ DSL International Shipment Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; cast to Map<String,Object> if typed access is needed
        if (result.getStatus("bookingConfirmation") == NodeStatus.COMPLETED) {
            System.out.println("Booking confirmed: " + result.results().getRaw("bookingConfirmation"));
        }

        if (result.getStatus("trackingSetup") == NodeStatus.COMPLETED) {
            System.out.println("Tracking setup: " + result.results().getRaw("trackingSetup"));
        }

        if (result.getStatus("sendNotification") == NodeStatus.COMPLETED) {
            System.out.println("Notification sent: " + result.results().getRaw("sendNotification"));
        }

        if (result.getStatus("customsClearance") == NodeStatus.COMPLETED) {
            System.out.println("Customs sub-graph output: " + result.results().getRaw("customsClearance"));
        }

        if (result.getStatus("routeOptimization") == NodeStatus.COMPLETED) {
            System.out.println("Route sub-graph output: " + result.results().getRaw("routeOptimization"));
        }
    }
}
