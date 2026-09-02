package com.leanowtech.bloge.gateway.visual.authoring.simulation;

/** Immutable v2 run plus whether it came from the idempotency authority. */
public record SimulationExecutionResultV2(SimulationRunV2 run, boolean replayed) {
    public SimulationExecutionResultV2 {
        if (run == null) throw new IllegalArgumentException("simulation run is required");
    }
}
