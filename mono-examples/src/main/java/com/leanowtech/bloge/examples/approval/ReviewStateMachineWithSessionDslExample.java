package com.leanowtech.bloge.examples.approval;

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
 * DSL-backed variant of {@link ReviewStateMachineWithSessionExample}.
 */
public final class ReviewStateMachineWithSessionDslExample {

    private static final String DSL_RESOURCE = "/bloge/approval/review-state-machine-with-session.bloge";

    private ReviewStateMachineWithSessionDslExample() {
    }

    /**
     * Compiles the example DSL resource into a nested state machine.
     *
     * @param registry operator registry used for nested graph compilation
     * @return compiled state-machine definition
     */
    public static StateMachineDef compile(DefaultOperatorRegistry registry) {
        ReviewStateMachineWithSessionExample.registerOperators(registry);
        return new StateMachineDslCompiler(registry).compile(ExampleDslResources.readResource(DSL_RESOURCE));
    }

    /**
     * Executes the DSL-backed sample flow.
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
                "applicantId", "APP-2001",
                "riskLevel", 20
        ));
        System.out.println("Review workflow: " + result.status() + " @ " + result.instance().currentStateId());
        System.out.println("State outputs: " + new LinkedHashMap<>(result.instance().stateOutputsSnapshot()));
    }
}
