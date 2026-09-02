package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

/** Idempotent projection port for governed Fixture usage from committed invocation evidence. */
@FunctionalInterface
public interface SimulationFixtureUsageRecorder {
    /** Records the exact {@code runId + invocationKey + asset} coordinate at most once. */
    void record(AuthoringScope scope, String runId, String invocationKey,
                SimulationRunV2.FixtureAssetRef asset);

    /** No-op authority used when a host has not enabled governed usage projection. */
    static SimulationFixtureUsageRecorder none() { return (scope, run, invocation, asset) -> { }; }
}
