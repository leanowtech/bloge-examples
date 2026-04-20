package com.leanowtech.bloge.examples.statemachine;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;
import com.leanowtech.bloge.state.compiler.StateMachineDslCompiler;
import com.leanowtech.bloge.state.engine.StateMachineExecutor;
import com.leanowtech.bloge.state.engine.StateMachineResult;
import com.leanowtech.bloge.state.model.StateMachineDef;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DSL-backed variant of {@link OrderStateMachineExample}.
 *
 * <p>Compiles the {@code order-state-machine.bloge} resource and runs the same
 * order lifecycle flow — PENDING → CONFIRMED → SHIPPED → DELIVERED — with a
 * CANCEL detour to demonstrate global transitions from DSL source.
 *
 * @see OrderStateMachineExample
 */
public final class OrderStateMachineDslExample {

    private static final String DSL_RESOURCE = "/bloge/statemachine/order-state-machine.bloge";

    private OrderStateMachineDslExample() {
    }

    /**
     * Compiles the example DSL resource into a {@link StateMachineDef}.
     *
     * @param registry operator registry (no operators needed for this graph-less example)
     * @return compiled state machine definition
     */
    public static StateMachineDef compile(DefaultOperatorRegistry registry) {
        return new StateMachineDslCompiler(registry)
                .compile(ExampleDslResources.readResource(DSL_RESOURCE));
    }

    /**
     * Executes the order lifecycle from DSL source with audit listener.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        StateMachineDef def = compile(registry);

        OrderStateMachineExample.AuditListener audit = new OrderStateMachineExample.AuditListener();
        StateMachineExecutor executor = StateMachineExecutor.builder(
                        GraphEngine.builder().registry(registry).build())
                .listeners(List.of(audit))
                .build();

        // Start the machine
        StateMachineResult result = executor.execute(def, Map.of(
                "orderId", "ORD-DSL-001",
                "customerId", "CUST-DSL-42"
        ));
        System.out.println("After execute: " + describe(result));

        // Signal CONFIRM
        result = executor.signal(result.instance(), def, "CONFIRM", Map.of("confirmedBy", "api"));
        System.out.println("After CONFIRM: " + describe(result));

        // Signal SHIP
        result = executor.signal(result.instance(), def, "SHIP", Map.of("carrier", "UPS"));
        System.out.println("After SHIP: " + describe(result));

        // Signal DELIVER → terminal
        result = executor.signal(result.instance(), def, "DELIVER", Map.of("deliveredAt", "2025-02-01"));
        System.out.println("After DELIVER: " + describe(result));
        System.out.println("Final context: " + new LinkedHashMap<>(result.instance().sharedContext()));

        // --- Cancel scenario from DSL ---
        System.out.println("\n--- Cancel scenario (DSL) ---");
        result = executor.execute(def, Map.of("orderId", "ORD-DSL-002", "customerId", "CUST-DSL-99"));
        result = executor.signal(result.instance(), def, "CANCEL", Map.of("reason", "out of stock"));
        System.out.println("After CANCEL: " + describe(result));
    }

    /**
     * Formats a result for display.
     */
    private static String describe(StateMachineResult result) {
        return result.status() + " state=" + result.instance().currentStateId()
                + " awaited=" + result.awaitedEvents();
    }
}
