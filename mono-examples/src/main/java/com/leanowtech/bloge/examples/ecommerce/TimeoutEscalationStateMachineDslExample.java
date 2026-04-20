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
 * DSL version of {@link TimeoutEscalationStateMachineExample}.
 *
 * <p>Compiles {@code timeout-escalation-state-machine.bloge} and drives the same
 * approval-timeout-escalation scenario. Note that the DSL uses a 24h timeout for
 * production-realistic values; the Java API test overrides this with a short duration.</p>
 */
public final class TimeoutEscalationStateMachineDslExample {

    private static final String DSL_RESOURCE = "/bloge/ecommerce/timeout-escalation-state-machine.bloge";

    private TimeoutEscalationStateMachineDslExample() {}

    /**
     * Compiles the DSL state machine and registers the required operators.
     */
    public static StateMachineDef compile(DefaultOperatorRegistry registry) {
        TimeoutEscalationStateMachineExample.registerOperators(registry);
        return new StateMachineDslCompiler(registry)
                .compile(ExampleDslResources.readResource(DSL_RESOURCE));
    }

    public static void main(String[] args) {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        StateMachineDef def = compile(registry);
        StateMachineExecutor executor = StateMachineExecutor.builder(
                        GraphEngine.builder().registry(registry).build())
                .build();

        StateMachineResult result = executor.execute(def, Map.of(
                "requestId", "REQ-DSL-001",
                "requester", "alice"
        ));
        System.out.println("Initial: " + result.status() + " @ " + result.instance().currentStateId());

        result = executor.signal(result.instance(), def, "approve",
                Map.of("approvedBy", "bob"));
        System.out.println("After approve: " + result.status() + " @ " + result.instance().currentStateId());
        System.out.println("State outputs: " + new LinkedHashMap<>(result.instance().stateOutputsSnapshot()));
    }
}
