package com.leanowtech.bloge.examples.catering;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.IntStream;

public class FoodOrderReplExample {

    private static final String DSL = """

                graph foodOrderProcess {
                  ///  validateOrder: reads ctx.orderId/restaurantId/items → {orderId, restaurantId, items, totalAmount}
                  node validateOrder : ValidateOrderOperator {
                    input {
                      orderId       = ctx.orderId
                      restaurantId  = ctx.restaurantId
                      items         = ctx.items
                      deliveryAddress = ctx.deliveryAddress
                    }
                    timeout = 2s
                  }
                  ///  checkInventory/checkKitchenLoad/estimateDelivery run in parallel after validateOrder
                  node checkInventory : CheckInventoryOperator {
                    depends_on = [validateOrder]
                    input {
                      restaurantId = validateOrder.output.restaurantId
                      items        = validateOrder.output.items
                    }
                    timeout = 3s
                  }
                  node checkKitchenLoad : CheckKitchenLoadOperator {
                    depends_on = [validateOrder]
                    input {
                      restaurantId = validateOrder.output.restaurantId
                    }
                    timeout = 2s
                  }
                  node estimateDelivery : EstimateDeliveryOperator {
                    depends_on = [validateOrder]
                    input {
                      restaurantId    = validateOrder.output.restaurantId
                      deliveryAddress = ctx.deliveryAddress
                    }
                    timeout = 3s
                  }
                  ///  decideAcceptance: aggregates inventory + kitchen + delivery results → {accepted, reason}
                  node decideAcceptance : DecideAcceptanceOperator {
                    depends_on = [checkInventory, checkKitchenLoad, estimateDelivery]
                    input {
                      inventory = checkInventory.output
                      kitchen   = checkKitchenLoad.output
                      delivery  = estimateDelivery.output
                    }
                  }
                  ///  branch: true path → acceptOrder; false path → suggestAlternatives
                  branch on decideAcceptance.output.accepted {
                    true  -> acceptOrder
                    false -> suggestAlternatives
                  }
                  ///  acceptOrder: combines order total with delivery fee → {orderId, estimatedMinutes, total}
                  node acceptOrder : AcceptOrderOperator {
                    depends_on = [decideAcceptance]
                    input {
                      order    = validateOrder.output
                      delivery = estimateDelivery.output
                    }
                  }
                  ///  suggestAlternatives: returns substitute dish options when order cannot be fulfilled
                  node suggestAlternatives : SuggestAlternativesOperator {
                    depends_on = [decideAcceptance]
                    input {
                      order            = validateOrder.output
                      unavailableItems = checkInventory.output.unavailableItems
                    }
                  }
                  ///  notifyKitchen: dispatches accepted order items to kitchen → {kitchenOrderId}
                  node notifyKitchen : NotifyKitchenOperator {
                    depends_on = [acceptOrder]
                    input {
                      orderId = acceptOrder.output.orderId
                      items   = validateOrder.output.items
                    }
                  }
                  ///  assignRider: matches a delivery rider; fallback to self-pickup if none available
                  node assignRider : AssignRiderOperator {
                    depends_on = [acceptOrder]
                    input {
                      orderId         = acceptOrder.output.orderId
                      deliveryAddress = ctx.deliveryAddress
                    }
                    fallback = { riderId: "SELF-PICKUP", riderName: "Self Pickup" }
                  }
                  ///  processPayment: charges the accepted order total → {transactionId, status}
                  node processPayment : ProcessPaymentOperator {
                    depends_on = [acceptOrder]
                    input {
                      orderId = acceptOrder.output.orderId
                      amount  = acceptOrder.output.total
                    }
                    timeout = 5s
                  }
                }
                
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ValidateOrderOperator", FoodOrderDslExample.VALIDATE_ORDER);
        registry.register("CheckInventoryOperator", FoodOrderDslExample.CHECK_INVENTORY);
        registry.register("CheckKitchenLoadOperator", FoodOrderDslExample.CHECK_KITCHEN_LOAD);
        registry.register("EstimateDeliveryOperator", FoodOrderDslExample.ESTIMATE_DELIVERY);
        registry.register("DecideAcceptanceOperator", FoodOrderDslExample.DECIDE_ACCEPTANCE);
        registry.register("AcceptOrderOperator", FoodOrderDslExample.ACCEPT_ORDER);
        registry.register("NotifyKitchenOperator", FoodOrderDslExample.NOTIFY_KITCHEN);
        registry.register("AssignRiderOperator", FoodOrderDslExample.ASSIGN_RIDER);
        registry.register("ProcessPaymentOperator", FoodOrderDslExample.PROCESS_PAYMENT);
        registry.register("SuggestAlternativesOperator", FoodOrderDslExample.SUGGEST_ALTERNATIVES);
        return new GraphLoader(registry).load(DSL);
    }

    private static Map<String, Object> promptContext(Scanner scanner) {
        String orderId = ReplHelper.promptString(scanner, "orderId", "FO-2024-001");
        String restaurantId = ReplHelper.promptString(scanner, "restaurantId", "REST-088");
        List<String> itemNames = ReplHelper.promptList(scanner, "items (comma separated)", List.of("Kung Pao Chicken", "Fried Rice"));
        String deliveryAddress = ReplHelper.promptString(scanner, "deliveryAddress", "123 Main St");
        List<Map<String, Object>> items = IntStream.range(0, itemNames.size())
                .mapToObj(i -> Map.<String, Object>of(
                        "dishId", "DISH-" + String.format("%03d", i + 1),
                        "name", itemNames.get(i),
                        "quantity", 1))
                .toList();
        return Map.of(
                "orderId", orderId,
                "restaurantId", restaurantId,
                "items", items,
                "deliveryAddress", deliveryAddress
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
                ReplHelper.header("Food Order REPL");
                Map<String, Object> values = promptContext(scanner);
                GraphResult result = engine.execute(graph, new GraphContext(values));
                ReplHelper.printResult(result);
                runAgain = ReplHelper.askRunAgain(scanner);
            } while (runAgain);
        }
    }
}
