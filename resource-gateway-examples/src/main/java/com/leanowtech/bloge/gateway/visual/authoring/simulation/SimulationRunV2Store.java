package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/** Atomic idempotency and immutable evidence authority for caller-directed v2 runs. */
public interface SimulationRunV2Store {
    /** Reserves one run identity or returns the exact prior v2 outcome. */
    Claim claim(AuthoringScope scope, String idempotencyKey, String requestFingerprint,
                Supplier<String> runIdFactory, Instant startedAt);

    /** Completes only the exact acquired v2 run. */
    SimulationRunV2 complete(AuthoringScope scope, String idempotencyKey,
                             String requestFingerprint, SimulationRunV2 run);

    /** Reads one immutable v2 run only inside its exact scope. */
    Optional<SimulationRunV2> find(AuthoringScope scope, String runId);

    sealed interface Claim permits Claim.Acquired, Claim.Replay, Claim.Busy, Claim.Conflict {
        record Acquired(String runId) implements Claim { }
        record Replay(SimulationRunV2 run) implements Claim { }
        record Busy(String runId) implements Claim { }
        record Conflict() implements Claim { }
    }
}
