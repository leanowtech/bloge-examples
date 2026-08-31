package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/** Atomic idempotency and immutable evidence authority for simulation runs. */
public interface SimulationRunStore {
    /** Reserves one run identity or returns the exact prior outcome. */
    Claim claim(AuthoringScope scope, String idempotencyKey, String requestFingerprint,
                Supplier<String> runIdFactory, Instant startedAt);
    /** Completes only the exact acquired run. */
    SimulationRun complete(AuthoringScope scope, String idempotencyKey,
                           String requestFingerprint, SimulationRun run);
    /** Reads one immutable run only inside its exact scope. */
    Optional<SimulationRun> find(AuthoringScope scope, String runId);

    sealed interface Claim permits Claim.Acquired, Claim.Replay, Claim.Busy, Claim.Conflict {
        record Acquired(String runId) implements Claim { }
        record Replay(SimulationRun run) implements Claim { }
        record Busy(String runId) implements Claim { }
        record Conflict() implements Claim { }
    }
}
