package com.leanowtech.bloge.examples.catering;

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

public class RestaurantOrderPipelineReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ReceiveOrderOperator", RestaurantOrderPipelineDslExample.RECEIVE_ORDER);
        registry.register("PaymentVerificationOperator", RestaurantOrderPipelineDslExample.PAYMENT_VERIFICATION);
        registry.register("QualityCheckOperator", RestaurantOrderPipelineDslExample.QUALITY_CHECK);
        registry.register("PrepareDineInOperator", RestaurantOrderPipelineDslExample.PREPARE_DINE_IN);
        registry.register("OrderCompleteOperator", RestaurantOrderPipelineDslExample.ORDER_COMPLETE);
        registry.register("dishValidation", RestaurantOrderPipelineDslExample.DISH_VALIDATION);
        registry.register("ingredientCheck", RestaurantOrderPipelineDslExample.INGREDIENT_CHECK);
        registry.register("stationAssignment", RestaurantOrderPipelineDslExample.STATION_ASSIGNMENT);
        registry.register("cookingTimeEstimate", RestaurantOrderPipelineDslExample.COOKING_TIME_ESTIMATE);
        registry.register("queueForPickup", RestaurantOrderPipelineDslExample.QUEUE_FOR_PICKUP);
        registry.register("riderMatching", RestaurantOrderPipelineDslExample.RIDER_MATCHING);
        registry.register("routeCalculation", RestaurantOrderPipelineDslExample.ROUTE_CALCULATION);
        registry.register("etaEstimation", RestaurantOrderPipelineDslExample.ETA_ESTIMATION);
        registry.register("realtimeTrackingSetup", RestaurantOrderPipelineDslExample.REALTIME_TRACKING_SETUP);
        var compiler = new DslCompiler(registry);
        compiler.registerSubGraph("kitchen-dispatch", RestaurantOrderPipelineDslExample.buildKitchenDispatchSubGraph());
        compiler.registerSubGraph("delivery-coordination", RestaurantOrderPipelineDslExample.buildDeliveryCoordinationSubGraph());

        var tokens = new Lexer(DSL).tokenize();
        GraphDef ast = new Parser(tokens).parse();
        return compiler.compile(ast);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String orderId = ReplHelper.promptString(scanner, "orderId", "ORD-5521");
        String restaurantId = ReplHelper.promptString(scanner, "restaurantId", "REST-LUIGI-01");
        String tableType = ReplHelper.promptString(scanner, "tableType (dine-in/delivery)", "delivery");
        String orderType = "dine-in".equalsIgnoreCase(tableType) ? "dineIn" : "delivery";
        return Map.of(
                "orderId", orderId,
                "restaurantId", restaurantId,
                "items", List.of("margherita-pizza", "caesar-salad", "tiramisu"),
                "orderType", orderType,
                "customerAddress", "42 Elm Street, Apt 7B",
                "totalAmount", 47.85,
                "tableNumber", "T-12"
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
                ReplHelper.header("Restaurant Order Pipeline REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
