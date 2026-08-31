package com.leanowtech.bloge.gateway.visual.authoring.simulation;

/** Simulation response plus exact idempotency replay evidence. */
public record SimulationExecutionResult(SimulationRun run, boolean replayed) {
    public SimulationExecutionResult {
        if (run == null) throw new IllegalArgumentException("run is required");
    }
}
