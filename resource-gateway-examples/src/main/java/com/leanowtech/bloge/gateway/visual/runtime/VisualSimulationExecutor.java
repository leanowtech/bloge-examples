package com.leanowtech.bloge.gateway.visual.runtime;

/** Visual-owned port for executing an isolated simulation plan. */
@FunctionalInterface
public interface VisualSimulationExecutor {

    /** Executes a plan and returns the visual runtime response. */
    VisualDslRunResponse execute(VisualSimulationPlan plan);
}
