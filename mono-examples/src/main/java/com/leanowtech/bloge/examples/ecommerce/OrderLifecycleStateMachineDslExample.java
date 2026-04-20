package com.leanowtech.bloge.examples.ecommerce;

import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;
import com.leanowtech.bloge.state.compiler.StateMachineDslCompiler;
import com.leanowtech.bloge.state.engine.StateMachineExecutor;
import com.leanowtech.bloge.state.engine.StateMachineResult;
import com.leanowtech.bloge.state.model.StateMachineDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DSL-backed variant of {@link OrderLifecycleStateMachineExample}.
 */
public final class OrderLifecycleStateMachineDslExample {

    private static final String DSL_RESOURCE = "/bloge/order-lifecycle-state-machine.bloge";

    private OrderLifecycleStateMachineDslExample() {
    }

    /**
     * Compiles the example DSL resource into a {@link StateMachineDef}.
     *
     * @param registry operator registry used for nested state graphs
     * @return compiled state-machine definition
     */
    public static StateMachineDef compile(DefaultOperatorRegistry registry) {
        registry.register("InitOrderOperator", OrderLifecycleStateMachineExample.INIT_ORDER);
        registry.register("ReviewOrderOperator", OrderLifecycleStateMachineExample.REVIEW_ORDER);
        registry.register("FulfillmentOperator", OrderLifecycleStateMachineExample.FULFILL_ORDER);
        return new StateMachineDslCompiler(registry)
                .compile(ExampleDslResources.readResource(DSL_RESOURCE));
    }

    /**
     * Executes the example flow from DSL source.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        StateMachineDef def = compile(registry);
        StateMachineExecutor executor = StateMachineExecutor.builder(
                        GraphEngine.builder().registry(registry).build())
                .build();

        StateMachineResult result = executor.execute(def, Map.of(
                "orderId", "ORD-2001",
                "customerId", "CUST-99"
        ));
        System.out.println("After execute: " + result.status() + " @ " + result.instance().currentStateId());

        result = executor.signal(result.instance(), def, "submit", Map.of("submittedBy", "api"));
        System.out.println("After submit: " + result.status() + " @ " + result.instance().currentStateId());

        result = executor.signal(result.instance(), def, "approve", Map.of("reviewedBy", "ops"));
        System.out.println("After approve: " + result.status() + " @ " + result.instance().currentStateId());
        System.out.println("State outputs: " + new LinkedHashMap<>(result.instance().stateOutputsSnapshot()));
    }
}
