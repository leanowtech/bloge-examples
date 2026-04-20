package com.leanowtech.bloge.examples.logistics;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode.GraphDef;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.lexer.Lexer;
import com.leanowtech.bloge.dsl.parser.Parser;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class InternationalShipmentReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ReceiveRequestOperator", InternationalShipmentDslExample.RECEIVE_REQUEST);
        registry.register("ValidateAddressOperator", InternationalShipmentDslExample.VALIDATE_ADDRESS);
        registry.register("BookingConfirmationOperator", InternationalShipmentDslExample.BOOKING_CONFIRMATION);
        registry.register("TrackingSetupOperator", InternationalShipmentDslExample.TRACKING_SETUP);
        registry.register("SendNotificationOperator", InternationalShipmentDslExample.SEND_NOTIFICATION);
        registry.register("documentPreparation", InternationalShipmentDslExample.DOC_PREP);
        registry.register("hsCodeClassification", InternationalShipmentDslExample.HS_CODE);
        registry.register("dutyCalculation", InternationalShipmentDslExample.DUTY_CALC);
        registry.register("customsDeclaration", InternationalShipmentDslExample.CUSTOMS_DECL);
        registry.register("clearanceApproved", InternationalShipmentDslExample.CLEARANCE_APPROVED);
        registry.register("clearanceRejected", InternationalShipmentDslExample.CLEARANCE_REJECTED);
        registry.register("carrierQuery", InternationalShipmentDslExample.CARRIER_QUERY);
        registry.register("rateComparison", InternationalShipmentDslExample.RATE_COMPARISON);
        registry.register("transitTimeEstimation", InternationalShipmentDslExample.TRANSIT_TIME);
        registry.register("optimalRouteSelection", InternationalShipmentDslExample.OPTIMAL_ROUTE);
        var compiler = new DslCompiler(registry);
        compiler.registerSubGraph("customs-clearance", InternationalShipmentDslExample.buildCustomsClearanceSubGraph());
        compiler.registerSubGraph("route-optimization", InternationalShipmentDslExample.buildRouteOptimizationSubGraph());

        var tokens = new Lexer(DSL).tokenize();
        GraphDef ast = new Parser(tokens).parse();
        return compiler.compile(ast);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String orderId = ReplHelper.promptString(scanner, "orderId", "SHP-2025-00318");
        String origin = ReplHelper.promptString(scanner, "origin", "CN");
        String destination = ReplHelper.promptString(scanner, "destination", "US");
        return Map.of(
                "shipmentId", orderId,
                "originCountry", origin,
                "destinationCountry", destination,
                "weightKg", 450.0,
                "commodityType", "electronics"
        );
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();

        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            boolean runAgain;
            do {
                ReplHelper.header("International Shipment REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
