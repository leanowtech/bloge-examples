package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.ResilienceConfig;
import com.leanowtech.bloge.core.model.SagaConfig;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Small Java-API saga example that demonstrates declarative compensation.
 *
 * <p>The graph reserves inventory, charges a payment, and then attempts shipping.
 * When shipping fails, BLOGE compensates the already-completed charge and inventory
 * steps in reverse topological order.</p>
 */
public final class OrderSagaExample {

    public record Reservation(String reservationId) {}
    public record Charge(String chargeId) {}
    public record CompensationAck(String action, String resourceId) {}

    static final Operator<Void, Reservation> RESERVE_INVENTORY =
            (input, ctx) -> new Reservation("res-1");

    static final Operator<Reservation, CompensationAck> RELEASE_INVENTORY =
            (input, ctx) -> new CompensationAck("release", input.reservationId());

    static final Operator<Void, Charge> CHARGE_PAYMENT =
            (input, ctx) -> new Charge("chg-1");

    static final Operator<Charge, CompensationAck> REFUND_PAYMENT =
            (input, ctx) -> new CompensationAck("refund", input.chargeId());

    static final Operator<Void, String> SHIP_ORDER = (input, ctx) -> {
        if (Boolean.TRUE.equals(ctx.graphContext().get("failShipping"))) {
            throw new IllegalStateException("shipping service unavailable");
        }
        return "shipped";
    };

    private OrderSagaExample() {
    }

    /**
     * Builds the compensation-aware order saga.
     */
    public static Graph buildGraph() {
        return Graph.builder("orderSaga")
                .executionSettings(new Graph.GraphExecutionSettings(
                        SchemaValidationLevel.WARN,
                        SagaConfig.backward().maxRetries(2)
                ))
                .node("reserveInventory", RESERVE_INVENTORY)
                .compensate(RELEASE_INVENTORY, (results, ctx) ->
                        results.get("reserveInventory", Reservation.class))
                .node("chargePayment", CHARGE_PAYMENT)
                .dependsOn("reserveInventory")
                .compensate(REFUND_PAYMENT, (results, ctx) ->
                                results.get("chargePayment", Charge.class),
                        new ResilienceConfig(
                                1,
                                Duration.ofMillis(250),
                                BackoffStrategy.FIXED,
                                null,
                                null,
                                null,
                                Set.of()
                        ))
                .node("shipOrder", SHIP_ORDER)
                .dependsOn("chargePayment")
                .build();
    }

    /**
     * Executes the saga with optional shipping failure.
     */
    public static GraphResult execute(boolean failShipping) {
        return GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build()
                .execute(buildGraph(), new GraphContext(Map.of("failShipping", failShipping)));
    }

    public static void main(String[] args) {
        GraphResult result = execute(true);
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Compensations: " + result.compensationResults());
    }
}
