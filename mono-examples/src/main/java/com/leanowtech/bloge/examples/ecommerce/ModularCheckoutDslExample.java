package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates DSL {@code import} declarations with reusable checkout sub-graphs.
 *
 * <p>The root checkout graph imports sibling {@code payment-flow.bloge} and
 * {@code inventory-check.bloge} resources, then invokes those imported graphs by alias. This keeps
 * the root graph focused on orchestration while each imported graph owns its local business steps.</p>
 */
@SuppressWarnings({"preview", "unchecked"})
public final class ModularCheckoutDslExample {

    private static final String DSL_RESOURCE = "/bloge/modular/checkout.bloge";

    static final String NODE_LOAD_CART = "loadCart";
    static final String NODE_PAYMENT = "payment";
    static final String NODE_INVENTORY = "inventory";
    static final String NODE_ASSEMBLE_CHECKOUT = "assembleCheckout";

    private ModularCheckoutDslExample() {
    }

    static final Operator<Map<String, Object>, Map<String, Object>> LOAD_CART = (input, ctx) -> Map.of(
            "checkoutId", input.get("checkoutId"),
            "orderId", "ORD-IMPORT-1001",
            "customerId", input.get("customerId"),
            "sku", "SKU-BOOK-42",
            "quantity", 2,
            "totalAmount", 84.50,
            "paymentMethod", "card"
    );

    static final Operator<Map<String, Object>, Map<String, Object>> AUTHORIZE_PAYMENT = (input, ctx) -> Map.of(
            "orderId", input.get("orderId"),
            "authorizationId", "AUTH-" + input.get("orderId"),
            "status", "AUTHORIZED",
            "amount", input.get("totalAmount")
    );

    static final Operator<Map<String, Object>, Map<String, Object>> BUILD_PAYMENT_RESULT = (input, ctx) -> {
        Map<String, Object> authorization = (Map<String, Object>) input.get("authorization");
        return Map.of(
                "paymentStatus", authorization.get("status"),
                "authCode", authorization.get("authorizationId"),
                "captureRequired", true
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> LOOKUP_STOCK = (input, ctx) -> Map.of(
            "sku", input.get("sku"),
            "availableQuantity", 8,
            "warehouse", "WH-EAST"
    );

    static final Operator<Map<String, Object>, Map<String, Object>> RESERVE_STOCK = (input, ctx) -> {
        Map<String, Object> stock = (Map<String, Object>) input.get("stock");
        int requested = ((Number) input.get("quantity")).intValue();
        int available = ((Number) stock.get("availableQuantity")).intValue();
        return Map.of(
                "reserved", available >= requested,
                "reservationId", "RSV-" + input.get("orderId"),
                "warehouse", stock.get("warehouse"),
                "reservedQuantity", requested
        );
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ASSEMBLE_CHECKOUT = (input, ctx) -> {
        Map<String, Object> payment = (Map<String, Object>) input.get("payment");
        Map<String, Object> inventory = (Map<String, Object>) input.get("inventory");
        boolean approved = "AUTHORIZED".equals(payment.get("paymentStatus"))
                && Boolean.TRUE.equals(inventory.get("reserved"));
        return Map.of(
                "checkoutId", input.get("checkoutId"),
                "orderId", input.get("orderId"),
                "status", approved ? "READY_TO_CAPTURE" : "BLOCKED",
                "paymentStatus", payment.get("paymentStatus"),
                "reservationId", inventory.get("reservationId"),
                "captureRequired", payment.get("captureRequired")
        );
    };

    /**
     * Registers all operators needed by the root graph and its imported sub-graphs.
     *
     * @param registry operator registry used for compile and execution
     */
    public static void registerOperators(DefaultOperatorRegistry registry) {
        registry.register("CheckoutCartOperator", LOAD_CART);
        registry.register("PaymentAuthorizationOperator", AUTHORIZE_PAYMENT);
        registry.register("PaymentResultOperator", BUILD_PAYMENT_RESULT);
        registry.register("InventoryLookupOperator", LOOKUP_STOCK);
        registry.register("InventoryReservationOperator", RESERVE_STOCK);
        registry.register("CheckoutAssemblerOperator", ASSEMBLE_CHECKOUT);
    }

    /**
     * Compiles the root checkout graph and resolves its imported classpath resources.
     *
     * @param registry operator registry used for compile and execution
     * @return compiled root graph
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        registerOperators(registry);
        return ExampleDslResources.loadGraphWithClasspathImports(DSL_RESOURCE, registry);
    }

    /**
     * Executes the modular checkout graph.
     *
     * @param checkoutId checkout identifier for the example run
     * @param customerId customer identifier for the example run
     * @return graph result with payment and inventory imported sub-graph outputs
     */
    public static GraphResult execute(String checkoutId, String customerId) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of(
                "checkoutId", checkoutId,
                "customerId", customerId
        )));
    }

    /**
     * Reads the checkout summary from the terminal root node.
     *
     * @param result graph result
     * @return checkout summary map
     */
    public static Map<String, Object> checkoutSummary(GraphResult result) {
        return (Map<String, Object>) result.results().getRaw(NODE_ASSEMBLE_CHECKOUT);
    }

    /**
     * Reads the imported payment graph output from the root graph result.
     *
     * @param result graph result
     * @return payment imported sub-graph output
     */
    public static Map<String, Object> paymentOutput(GraphResult result) {
        return (Map<String, Object>) result.results().getRaw(NODE_PAYMENT);
    }

    /**
     * Reads the imported inventory graph output from the root graph result.
     *
     * @param result graph result
     * @return inventory imported sub-graph output
     */
    public static Map<String, Object> inventoryOutput(GraphResult result) {
        return (Map<String, Object>) result.results().getRaw(NODE_INVENTORY);
    }
}