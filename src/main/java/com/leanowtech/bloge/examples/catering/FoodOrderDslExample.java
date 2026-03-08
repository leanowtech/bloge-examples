package com.leanowtech.bloge.examples.catering;

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
 * DSL-driven catering order workflow example.
 *
 * <p>This example expresses the same orchestration as the typed variant through BLOGE DSL,
 * while binding each node to a Java operator via {@link GraphLoader} and
 * {@link DefaultOperatorRegistry}.
 *
 * <p>Graph layout:
 * <pre>
 * validateOrder
 *   -> checkInventory + checkKitchenLoad + estimateDelivery
 *   -> decideAcceptance
 *      -> (true)  acceptOrder -> notifyKitchen -> assignRider -> processPayment
 *      -> (false) suggestAlternatives
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings("preview")
public class FoodOrderDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> VALIDATE_ORDER = (input, ctx) -> {
        Thread.sleep(30);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) input.get("items");
        double totalAmount = items.stream()
                .mapToDouble(i -> ((Number) i.get("quantity")).intValue() * 12.5)
                .sum();
        return Map.of(
                "orderId", input.get("orderId"),
                "restaurantId", input.get("restaurantId"),
                "items", items,
                "totalAmount", totalAmount);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CHECK_INVENTORY = (input, ctx) -> {
        Thread.sleep(60);
        return Map.of("allAvailable", true, "unavailableItems", List.of());
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CHECK_KITCHEN_LOAD = (input, ctx) -> {
        Thread.sleep(40);
        return Map.of("currentOrders", 8, "maxCapacity", 15, "estimatedWaitMinutes", 20);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ESTIMATE_DELIVERY = (input, ctx) -> {
        Thread.sleep(80);
        return Map.of("distance", 3.5, "estimatedMinutes", 25, "deliveryFee", 5.0);
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> DECIDE_ACCEPTANCE = (input, ctx) -> {
        Thread.sleep(20);
        var inventory = (Map<String, Object>) input.get("inventory");
        var kitchen = (Map<String, Object>) input.get("kitchen");
        var delivery = (Map<String, Object>) input.get("delivery");
        boolean accepted = (Boolean) inventory.get("allAvailable")
                && ((Number) kitchen.get("currentOrders")).intValue() < ((Number) kitchen.get("maxCapacity")).intValue()
                && ((Number) delivery.get("estimatedMinutes")).intValue() < 60;
        String reason = accepted ? "All checks passed" : "Order cannot be fulfilled";
        return Map.of("accepted", accepted, "reason", reason);
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> ACCEPT_ORDER = (input, ctx) -> {
        Thread.sleep(20);
        var order = (Map<String, Object>) input.get("order");
        var delivery = (Map<String, Object>) input.get("delivery");
        double total = ((Number) order.get("totalAmount")).doubleValue() + ((Number) delivery.get("deliveryFee")).doubleValue();
        return Map.of(
                "orderId", order.get("orderId"),
                "estimatedMinutes", delivery.get("estimatedMinutes"),
                "total", total);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> NOTIFY_KITCHEN = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of("kitchenOrderId", "KO-2024-001");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ASSIGN_RIDER = (input, ctx) -> {
        Thread.sleep(50);
        return Map.of("riderId", "RDR-007", "riderName", "Driver Wang");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> PROCESS_PAYMENT = (input, ctx) -> {
        Thread.sleep(40);
        return Map.of("transactionId", "TXN-2024-001", "status", "SUCCESS");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SUGGEST_ALTERNATIVES = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of(
                "suggestions", List.of("Dish B instead of Dish A", "Combo Special C"),
                "reason", "Some items unavailable");
    };

    /**
     * Loads and executes the food-order DSL graph using sample request data.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // VALIDATE_ORDER: reads ctx.orderId/restaurantId/items → {orderId, restaurantId, items, totalAmount}
        registry.register("ValidateOrderOperator", VALIDATE_ORDER);
        // CHECK_INVENTORY: reads validateOrder.restaurantId/items → {allAvailable, unavailableItems}
        registry.register("CheckInventoryOperator", CHECK_INVENTORY);
        // CHECK_KITCHEN_LOAD: reads validateOrder.restaurantId → {currentOrders, maxCapacity, estimatedWaitMinutes}
        registry.register("CheckKitchenLoadOperator", CHECK_KITCHEN_LOAD);
        // ESTIMATE_DELIVERY: reads ctx.deliveryAddress + validateOrder.restaurantId → {distance, estimatedMinutes, deliveryFee}
        registry.register("EstimateDeliveryOperator", ESTIMATE_DELIVERY);
        // DECIDE_ACCEPTANCE: aggregates inventory/kitchen/delivery results → {accepted, reason}
        registry.register("DecideAcceptanceOperator", DECIDE_ACCEPTANCE);
        // ACCEPT_ORDER: combines order total with delivery fee → {orderId, estimatedMinutes, total}
        registry.register("AcceptOrderOperator", ACCEPT_ORDER);
        // NOTIFY_KITCHEN: dispatches accepted order items to kitchen → {kitchenOrderId}
        registry.register("NotifyKitchenOperator", NOTIFY_KITCHEN);
        // ASSIGN_RIDER: matches a delivery rider for the order → {riderId, riderName}
        registry.register("AssignRiderOperator", ASSIGN_RIDER);
        // PROCESS_PAYMENT: charges the accepted order total → {transactionId, status}
        registry.register("ProcessPaymentOperator", PROCESS_PAYMENT);
        // SUGGEST_ALTERNATIVES: returns substitute dish options when order is rejected → {suggestions, reason}
        registry.register("SuggestAlternativesOperator", SUGGEST_ALTERNATIVES);

        var loader = new GraphLoader(registry);

        String dsl = """
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

        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "orderId", "FO-2024-001",
                "restaurantId", "REST-088",
                "items", List.of(
                        Map.of("dishId", "DISH-001", "name", "Kung Pao Chicken", "quantity", 2),
                        Map.of("dishId", "DISH-002", "name", "Fried Rice", "quantity", 1)),
                "deliveryAddress", "123 Main St"
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Food Order Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-22s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; cast to Map for structured access
        if (result.getStatus("acceptOrder") == NodeStatus.COMPLETED) {
            System.out.println("Order accepted: " + result.results().getRaw("acceptOrder"));
            System.out.println("Kitchen notified: " + result.results().getRaw("notifyKitchen"));
            System.out.println("Rider assigned: " + result.results().getRaw("assignRider"));
            System.out.println("Payment processed: " + result.results().getRaw("processPayment"));
        } else if (result.getStatus("suggestAlternatives") == NodeStatus.COMPLETED) {
            System.out.println("Alternatives suggested: " + result.results().getRaw("suggestAlternatives"));
        }
    }
}
