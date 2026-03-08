package com.leanowtech.bloge.examples.logistics;

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
 * Demonstrates parallel sub-graph execution in an international shipment pipeline,
 * including a branch inside a sub-graph for customs approval/rejection.
 * <p>
 * Main graph: receiveRequest → validateAddress → [customsClearance ∥ routeOptimization]
 *             → bookingConfirmation → trackingSetup → sendNotification
 * <p>
 * Sub-graph A (customs-clearance, 5 nodes + branch):
 *   documentPreparation → hsCodeClassification → dutyCalculation → customsDeclaration
 *   → branch(clearanceApproved | clearanceRejected)
 * <p>
 * Sub-graph B (route-optimization, 4 nodes):
 *   carrierQuery → rateComparison → transitTimeEstimation → optimalRouteSelection
 */
public class InternationalShipmentExample {

    // --- Main graph records ---

    public record ShipmentRequest(String shipmentId, String originCountry, String destinationCountry,
                                  double weightKg, String commodityType) {}
    public record ReceivedRequest(String shipmentId, String originCountry, String destinationCountry,
                                  double weightKg, String commodityType, String status) {}
    public record AddressInput(String shipmentId, String originCountry, String destinationCountry) {}
    public record ValidatedAddress(String shipmentId, String originPort, String destinationPort,
                                   boolean addressValid) {}
    public record BookingInput(String shipmentId, String carrier, String route, String clearanceRef) {}
    public record BookingConfirmation(String shipmentId, String bookingRef, String carrier,
                                      String estimatedDeparture) {}
    public record TrackingInput(String shipmentId, String bookingRef) {}
    public record TrackingResult(String shipmentId, String trackingNumber, String trackingUrl) {}
    public record NotifyInput(String shipmentId, String trackingNumber, String message) {}
    public record Notification(String shipmentId, String channel, String message) {}

    // --- Customs clearance sub-graph records ---

    public record DocPrepInput(String shipmentId, String commodityType, String originCountry) {}
    public record DocPrepResult(String shipmentId, String invoiceRef, String packingListRef,
                                String certificateOfOrigin) {}
    public record HsCodeInput(String commodityType, String destinationCountry) {}
    public record HsCodeResult(String hsCode, String description, String chapter) {}
    public record DutyCalcInput(String hsCode, double weightKg, String destinationCountry) {}
    public record DutyCalcResult(double dutyRate, double dutyAmount, String currency) {}
    public record CustomsDeclInput(String shipmentId, String hsCode, double dutyAmount,
                                   String invoiceRef) {}
    public record CustomsDeclResult(String declarationId, String status, String reviewNotes) {}
    public record ClearanceApprovedInput(String declarationId) {}
    public record ClearanceApprovedResult(String clearanceRef, String approvalDate, String status) {}
    public record ClearanceRejectedInput(String declarationId, String reviewNotes) {}
    public record ClearanceRejectedResult(String rejectionRef, String reason, String status) {}

    // --- Route optimization sub-graph records ---

    public record CarrierQueryInput(String originPort, String destinationPort, double weightKg) {}
    public record CarrierQueryResult(List<String> carrierIds, List<Double> rates,
                                     List<Integer> transitDays) {}
    public record RateCompInput(List<String> carrierIds, List<Double> rates) {}
    public record RateCompResult(String bestCarrierId, double bestRate, double savings) {}
    public record TransitTimeInput(String carrierId, String originPort, String destinationPort) {}
    public record TransitTimeResult(int transitDays, String routeDescription, List<String> legs) {}
    public record OptimalRouteInput(String carrierId, double rate, int transitDays,
                                    String routeDescription) {}
    public record OptimalRouteResult(String selectedCarrier, String selectedRoute, double cost,
                                     int estimatedDays) {}

    // --- Main graph operators ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"logistics", "shipment"},
            description = "Receives and validates the incoming shipment request", owner = "logistics-team")
    static final Operator<ShipmentRequest, ReceivedRequest> RECEIVE_REQUEST = (input, ctx) -> {
        Thread.sleep(30);
        return new ReceivedRequest(input.shipmentId(), input.originCountry(),
                input.destinationCountry(), input.weightKg(), input.commodityType(), "RECEIVED");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"logistics", "address"},
            description = "Validates origin and destination addresses and resolves ports", owner = "logistics-team")
    static final Operator<AddressInput, ValidatedAddress> VALIDATE_ADDRESS = (input, ctx) -> {
        Thread.sleep(40);
        String originPort = "CN".equals(input.originCountry()) ? "CNSHA" : "USLAX";
        String destPort = "US".equals(input.destinationCountry()) ? "USLAX" : "DEHAM";
        return new ValidatedAddress(input.shipmentId(), originPort, destPort, true);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"logistics", "booking"},
            description = "Confirms shipment booking after customs and route processing", owner = "logistics-team")
    static final Operator<BookingInput, BookingConfirmation> BOOKING_CONFIRMATION = (input, ctx) -> {
        Thread.sleep(35);
        return new BookingConfirmation(input.shipmentId(), "BK-" + input.shipmentId(),
                input.carrier(), "2025-02-15");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"logistics", "tracking"},
            description = "Sets up shipment tracking", owner = "logistics-team")
    static final Operator<TrackingInput, TrackingResult> TRACKING_SETUP = (input, ctx) -> {
        Thread.sleep(25);
        String trackingNum = "TRK-" + input.shipmentId();
        return new TrackingResult(input.shipmentId(), trackingNum,
                "https://tracking.example.com/" + trackingNum);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"logistics", "notification"},
            description = "Sends shipment notification to stakeholders", owner = "logistics-team")
    static final Operator<NotifyInput, Notification> SEND_NOTIFICATION = (input, ctx) -> {
        Thread.sleep(20);
        return new Notification(input.shipmentId(), "email", input.message());
    };

    // --- Customs clearance sub-graph operators ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"logistics", "customs"},
            description = "Prepares customs documentation", owner = "customs-team")
    static final Operator<DocPrepInput, DocPrepResult> DOC_PREP = (input, ctx) -> {
        Thread.sleep(50);
        return new DocPrepResult(input.shipmentId(), "INV-" + input.shipmentId(),
                "PKL-" + input.shipmentId(), "COO-" + input.originCountry());
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"logistics", "customs"},
            description = "Classifies commodity HS code for destination country", owner = "customs-team")
    static final Operator<HsCodeInput, HsCodeResult> HS_CODE = (input, ctx) -> {
        Thread.sleep(60);
        String hsCode = "electronics".equals(input.commodityType()) ? "8471.30" : "6204.62";
        return new HsCodeResult(hsCode, input.commodityType() + " classification", "Chapter 84");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"logistics", "customs"},
            description = "Calculates import duties based on HS code and weight", owner = "customs-team")
    static final Operator<DutyCalcInput, DutyCalcResult> DUTY_CALC = (input, ctx) -> {
        Thread.sleep(30);
        double rate = input.hsCode().startsWith("8471") ? 0.025 : 0.12;
        double dutyAmount = rate * input.weightKg() * 150.0;
        return new DutyCalcResult(rate, Math.round(dutyAmount * 100.0) / 100.0, "USD");
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"logistics", "customs"},
            description = "Submits customs declaration to authority", owner = "customs-team")
    static final Operator<CustomsDeclInput, CustomsDeclResult> CUSTOMS_DECL = (input, ctx) -> {
        Thread.sleep(80);
        return new CustomsDeclResult("DECL-" + input.shipmentId(), "approved",
                "All documents verified");
    };

    static final Operator<ClearanceApprovedInput, ClearanceApprovedResult> CLEARANCE_APPROVED = (input, ctx) -> {
        Thread.sleep(20);
        return new ClearanceApprovedResult("CLR-" + input.declarationId(), "2025-02-10", "CLEARED");
    };

    static final Operator<ClearanceRejectedInput, ClearanceRejectedResult> CLEARANCE_REJECTED = (input, ctx) -> {
        Thread.sleep(15);
        return new ClearanceRejectedResult("REJ-" + input.declarationId(),
                input.reviewNotes(), "REJECTED");
    };

    // --- Route optimization sub-graph operators ---

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"logistics", "carrier"},
            description = "Queries available carriers for the route", owner = "routing-team")
    static final Operator<CarrierQueryInput, CarrierQueryResult> CARRIER_QUERY = (input, ctx) -> {
        Thread.sleep(70);
        return new CarrierQueryResult(
                List.of("MAERSK", "MSC", "COSCO"),
                List.of(2800.0, 2650.0, 2400.0),
                List.of(18, 21, 25));
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"logistics", "carrier"},
            description = "Compares carrier rates to find best option", owner = "routing-team")
    static final Operator<RateCompInput, RateCompResult> RATE_COMPARISON = (input, ctx) -> {
        Thread.sleep(25);
        double minRate = Double.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < input.rates().size(); i++) {
            if (input.rates().get(i) < minRate) {
                minRate = input.rates().get(i);
                bestIdx = i;
            }
        }
        double maxRate = input.rates().stream().mapToDouble(d -> d).max().orElse(0);
        return new RateCompResult(input.carrierIds().get(bestIdx), minRate, maxRate - minRate);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"logistics", "routing"},
            description = "Estimates transit time for selected carrier", owner = "routing-team")
    static final Operator<TransitTimeInput, TransitTimeResult> TRANSIT_TIME = (input, ctx) -> {
        Thread.sleep(35);
        int days = "COSCO".equals(input.carrierId()) ? 25 : 18;
        return new TransitTimeResult(days,
                input.originPort() + " → " + input.destinationPort(),
                List.of(input.originPort(), "SGSIN", input.destinationPort()));
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"logistics", "routing"},
            description = "Selects optimal route based on cost and transit time", owner = "routing-team")
    static final Operator<OptimalRouteInput, OptimalRouteResult> OPTIMAL_ROUTE = (input, ctx) -> {
        Thread.sleep(20);
        return new OptimalRouteResult(input.carrierId(), input.routeDescription(),
                input.rate(), input.transitDays());
    };

    // --- Sub-graph construction ---

    public static Graph buildCustomsClearanceSubGraph() {
        return Graph.builder("customs-clearance")
                .node("documentPreparation", DOC_PREP)
                    .input((results, ctx) -> new DocPrepInput(
                            ctx.get("shipmentId", String.class),
                            ctx.get("commodityType", String.class),
                            ctx.get("originCountry", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("hsCodeClassification", HS_CODE)
                    .dependsOn("documentPreparation")
                    .input((results, ctx) -> new HsCodeInput(
                            ctx.get("commodityType", String.class),
                            ctx.get("destinationCountry", String.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.EXPONENTIAL)
                .node("dutyCalculation", DUTY_CALC)
                    .dependsOn("hsCodeClassification")
                    .input((results, ctx) -> new DutyCalcInput(
                            results.get("hsCodeClassification", HsCodeResult.class).hsCode(),
                            ctx.get("weightKg", Double.class),
                            ctx.get("destinationCountry", String.class)))
                .node("customsDeclaration", CUSTOMS_DECL)
                    .dependsOn("dutyCalculation")
                    .input((results, ctx) -> new CustomsDeclInput(
                            ctx.get("shipmentId", String.class),
                            results.get("hsCodeClassification", HsCodeResult.class).hsCode(),
                            results.get("dutyCalculation", DutyCalcResult.class).dutyAmount(),
                            results.get("documentPreparation", DocPrepResult.class).invoiceRef()))
                    .timeout(Duration.ofSeconds(10))
                    .retry(2, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                .node("clearanceApproved", CLEARANCE_APPROVED)
                    .dependsOn("customsDeclaration")
                    .input((results, ctx) -> new ClearanceApprovedInput(
                            results.get("customsDeclaration", CustomsDeclResult.class).declarationId()))
                .node("clearanceRejected", CLEARANCE_REJECTED)
                    .dependsOn("customsDeclaration")
                    .input((results, ctx) -> new ClearanceRejectedInput(
                            results.get("customsDeclaration", CustomsDeclResult.class).declarationId(),
                            results.get("customsDeclaration", CustomsDeclResult.class).reviewNotes()))
                .branch("customsDeclaration")
                    .on("status")
                    .when(val -> "approved".equals(val), "clearanceApproved")
                    .otherwise("clearanceRejected")
                .build();
    }

    public static Graph buildRouteOptimizationSubGraph() {
        return Graph.builder("route-optimization")
                .node("carrierQuery", CARRIER_QUERY)
                    .input((results, ctx) -> new CarrierQueryInput(
                            ctx.get("originPort", String.class),
                            ctx.get("destinationPort", String.class),
                            ctx.get("weightKg", Double.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.EXPONENTIAL)
                .node("rateComparison", RATE_COMPARISON)
                    .dependsOn("carrierQuery")
                    .input((results, ctx) -> {
                        var carriers = results.get("carrierQuery", CarrierQueryResult.class);
                        return new RateCompInput(carriers.carrierIds(), carriers.rates());
                    })
                .node("transitTimeEstimation", TRANSIT_TIME)
                    .dependsOn("rateComparison")
                    .input((results, ctx) -> new TransitTimeInput(
                            results.get("rateComparison", RateCompResult.class).bestCarrierId(),
                            ctx.get("originPort", String.class),
                            ctx.get("destinationPort", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("optimalRouteSelection", OPTIMAL_ROUTE)
                    .dependsOn("rateComparison", "transitTimeEstimation")
                    .input((results, ctx) -> {
                        var rate = results.get("rateComparison", RateCompResult.class);
                        var transit = results.get("transitTimeEstimation", TransitTimeResult.class);
                        return new OptimalRouteInput(rate.bestCarrierId(), rate.bestRate(),
                                transit.transitDays(), transit.routeDescription());
                    })
                .build();
    }

    @SuppressWarnings({"preview", "unchecked"})
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Register sub-graph operators (resolved by operatorRef = node ID for lambdas)
        registry.register("documentPreparation", DOC_PREP);
        registry.register("hsCodeClassification", HS_CODE);
        registry.register("dutyCalculation", DUTY_CALC);
        registry.register("customsDeclaration", CUSTOMS_DECL);
        registry.register("clearanceApproved", CLEARANCE_APPROVED);
        registry.register("clearanceRejected", CLEARANCE_REJECTED);
        registry.register("carrierQuery", CARRIER_QUERY);
        registry.register("rateComparison", RATE_COMPARISON);
        registry.register("transitTimeEstimation", TRANSIT_TIME);
        registry.register("optimalRouteSelection", OPTIMAL_ROUTE);

        // Build sub-graphs
        Graph customsGraph = buildCustomsClearanceSubGraph();
        Graph routeGraph = buildRouteOptimizationSubGraph();

        // Wrap as SubGraphOperators
        SubGraphOperator customsSubGraph = new SubGraphOperator(customsGraph, registry);
        SubGraphOperator routeSubGraph = new SubGraphOperator(routeGraph, registry);

        // Build main graph
        Graph mainGraph = Graph.builder("internationalShipment")
                .node("receiveRequest", RECEIVE_REQUEST)
                    .input((results, ctx) -> new ShipmentRequest(
                            ctx.get("shipmentId", String.class),
                            ctx.get("originCountry", String.class),
                            ctx.get("destinationCountry", String.class),
                            ctx.get("weightKg", Double.class),
                            ctx.get("commodityType", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("validateAddress", VALIDATE_ADDRESS)
                    .dependsOn("receiveRequest")
                    .input((results, ctx) -> {
                        var req = results.get("receiveRequest", ReceivedRequest.class);
                        return new AddressInput(req.shipmentId(), req.originCountry(),
                                req.destinationCountry());
                    })
                    .timeout(Duration.ofSeconds(3))
                .node("customsClearance", customsSubGraph)
                    .dependsOn("validateAddress")
                    .input((results, ctx) -> {
                        var req = results.get("receiveRequest", ReceivedRequest.class);
                        return Map.of(
                                "shipmentId", req.shipmentId(),
                                "commodityType", req.commodityType(),
                                "originCountry", req.originCountry(),
                                "destinationCountry", req.destinationCountry(),
                                "weightKg", req.weightKg());
                    })
                    .timeout(Duration.ofSeconds(30))
                .node("routeOptimization", routeSubGraph)
                    .dependsOn("validateAddress")
                    .input((results, ctx) -> {
                        var addr = results.get("validateAddress", ValidatedAddress.class);
                        var req = results.get("receiveRequest", ReceivedRequest.class);
                        return Map.of(
                                "originPort", addr.originPort(),
                                "destinationPort", addr.destinationPort(),
                                "weightKg", req.weightKg());
                    })
                    .timeout(Duration.ofSeconds(30))
                .node("bookingConfirmation", BOOKING_CONFIRMATION)
                    .dependsOn("customsClearance", "routeOptimization")
                    .input((results, ctx) -> {
                        var routeOut = (Map<String, Object>) results.getRaw("routeOptimization");
                        var optRoute = (OptimalRouteResult) routeOut.get("optimalRouteSelection");
                        var customsOut = (Map<String, Object>) results.getRaw("customsClearance");
                        String clearanceRef;
                        if (customsOut.containsKey("clearanceApproved")) {
                            var approved = (ClearanceApprovedResult) customsOut.get("clearanceApproved");
                            clearanceRef = approved.clearanceRef();
                        } else {
                            var rejected = (ClearanceRejectedResult) customsOut.get("clearanceRejected");
                            clearanceRef = rejected.rejectionRef();
                        }
                        return new BookingInput(
                                ctx.get("shipmentId", String.class),
                                optRoute.selectedCarrier(),
                                optRoute.selectedRoute(),
                                clearanceRef);
                    })
                .node("trackingSetup", TRACKING_SETUP)
                    .dependsOn("bookingConfirmation")
                    .input((results, ctx) -> {
                        var booking = results.get("bookingConfirmation", BookingConfirmation.class);
                        return new TrackingInput(booking.shipmentId(), booking.bookingRef());
                    })
                .node("sendNotification", SEND_NOTIFICATION)
                    .dependsOn("trackingSetup")
                    .input((results, ctx) -> {
                        var tracking = results.get("trackingSetup", TrackingResult.class);
                        return new NotifyInput(
                                tracking.shipmentId(),
                                tracking.trackingNumber(),
                                "Shipment " + tracking.shipmentId() + " booked. Tracking: "
                                        + tracking.trackingNumber());
                    })
                .build();

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

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "receiveRequest", RECEIVE_REQUEST,
                "validateAddress", VALIDATE_ADDRESS,
                "customsClearance", customsSubGraph,
                "routeOptimization", routeSubGraph,
                "bookingConfirmation", BOOKING_CONFIRMATION,
                "trackingSetup", TRACKING_SETUP,
                "sendNotification", SEND_NOTIFICATION
        ));

        // Print results
        System.out.println("\n═══ International Shipment Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("bookingConfirmation") == NodeStatus.COMPLETED) {
            BookingConfirmation booking = result.getOutput("bookingConfirmation", BookingConfirmation.class);
            System.out.println("Booking confirmed: " + booking);
        }

        if (result.getStatus("trackingSetup") == NodeStatus.COMPLETED) {
            TrackingResult tracking = result.getOutput("trackingSetup", TrackingResult.class);
            System.out.println("Tracking setup: " + tracking);
        }

        if (result.getStatus("sendNotification") == NodeStatus.COMPLETED) {
            Notification notification = result.getOutput("sendNotification", Notification.class);
            System.out.println("Notification sent: " + notification);
        }

        if (result.getStatus("customsClearance") == NodeStatus.COMPLETED) {
            System.out.println("Customs sub-graph output: " + result.results().getRaw("customsClearance"));
        }

        if (result.getStatus("routeOptimization") == NodeStatus.COMPLETED) {
            System.out.println("Route sub-graph output: " + result.results().getRaw("routeOptimization"));
        }
    }
}
