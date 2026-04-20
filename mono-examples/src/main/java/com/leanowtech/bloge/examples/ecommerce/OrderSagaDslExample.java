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
 * DSL counterpart of {@link OrderSagaExample}.
 *
 * <p>This variant keeps the compensation topology in a checked-in {@code .bloge} resource
 * while binding the concrete operator implementations through the registry.</p>
 */
public final class OrderSagaDslExample {

    static final Operator<Map<String, Object>, Map<String, Object>> RESERVE_INVENTORY =
            (input, ctx) -> Map.of("reservationId", "res-1");

    static final Operator<Map<String, Object>, Map<String, Object>> RELEASE_INVENTORY =
            (input, ctx) -> Map.of("action", "release", "resourceId", input.get("reservationId"));

    static final Operator<Map<String, Object>, Map<String, Object>> CHARGE_PAYMENT =
            (input, ctx) -> Map.of("chargeId", "chg-1");

    static final Operator<Map<String, Object>, Map<String, Object>> REFUND_PAYMENT =
            (input, ctx) -> Map.of("action", "refund", "resourceId", input.get("chargeId"));

    static final Operator<Map<String, Object>, String> SHIP_ORDER = (input, ctx) -> {
        if (Boolean.TRUE.equals(input.get("failShipping"))) {
            throw new IllegalStateException("shipping service unavailable");
        }
        return "shipped";
    };

    private OrderSagaDslExample() {
    }

    /**
     * Registers the operator set used by the checked-in DSL example.
     */
    static DefaultOperatorRegistry createRegistry() {
        var registry = new DefaultOperatorRegistry();
        registry.register("ReserveInventoryOperator", RESERVE_INVENTORY);
        registry.register("ReleaseInventoryOperator", RELEASE_INVENTORY);
        registry.register("ChargePaymentOperator", CHARGE_PAYMENT);
        registry.register("RefundPaymentOperator", REFUND_PAYMENT);
        registry.register("ShipOrderOperator", SHIP_ORDER);
        return registry;
    }

    /**
     * Loads the checked-in DSL saga resource with the example operator registry.
     */
    public static Graph loadGraph() {
        var registry = createRegistry();
        return ExampleDslResources.loadGraph("/bloge/order-saga.bloge", registry);
    }

    /**
     * Executes the external DSL saga with optional shipping failure.
     */
    public static GraphResult execute(boolean failShipping) {
        var registry = createRegistry();

        return GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build()
                .execute(
                        ExampleDslResources.loadGraph("/bloge/order-saga.bloge", registry),
                        new GraphContext(Map.of("failShipping", failShipping))
                );
    }

    public static void main(String[] args) {
        GraphResult result = execute(true);
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Compensations: " + result.compensationResults());
    }
}
