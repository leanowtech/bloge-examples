package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Thread-safe v2 reference authority for module and transport tests. */
public final class InMemorySimulationRunV2Store implements SimulationRunV2Store {
    private final Map<Key, Entry> commands = new HashMap<>();
    private final Map<RunKey, SimulationRunV2> runs = new HashMap<>();

    @Override public synchronized Claim claim(AuthoringScope scope, String idempotencyKey,
                                               String requestFingerprint, Supplier<String> runIdFactory,
                                               Instant startedAt) {
        require(scope, idempotencyKey, requestFingerprint, runIdFactory, startedAt);
        Key key = new Key(scope, idempotencyKey);
        Entry prior = commands.get(key);
        if (prior == null) {
            String runId = runIdFactory.get();
            if (runId == null || runId.isBlank()) throw failure(SimulationFailure.Code.INTEGRITY);
            commands.put(key, new Entry(requestFingerprint, runId, null));
            return new Claim.Acquired(runId);
        }
        if (!prior.requestFingerprint().equals(requestFingerprint)) return new Claim.Conflict();
        return prior.run() == null ? new Claim.Busy(prior.runId()) : new Claim.Replay(prior.run());
    }

    @Override public synchronized SimulationRunV2 complete(
            AuthoringScope scope, String idempotencyKey, String requestFingerprint, SimulationRunV2 run) {
        if (run == null || run.status() == SimulationRunV2.Status.RUNNING
                || !run.requestFingerprint().equals(requestFingerprint)) {
            throw failure(SimulationFailure.Code.INTEGRITY);
        }
        Key key = new Key(scope, idempotencyKey);
        Entry prior = commands.get(key);
        if (prior == null || !prior.requestFingerprint().equals(requestFingerprint)
                || !prior.runId().equals(run.runId())) {
            throw failure(SimulationFailure.Code.INTEGRITY);
        }
        if (prior.run() != null) {
            if (!prior.run().equals(run)) throw failure(SimulationFailure.Code.INTEGRITY);
            return prior.run();
        }
        commands.put(key, new Entry(requestFingerprint, run.runId(), run));
        SimulationRunV2 collision = runs.putIfAbsent(new RunKey(scope, run.runId()), run);
        if (collision != null && !collision.equals(run)) throw failure(SimulationFailure.Code.INTEGRITY);
        return run;
    }

    @Override public synchronized Optional<SimulationRunV2> find(AuthoringScope scope, String runId) {
        if (scope == null || runId == null || runId.isBlank()) return Optional.empty();
        return Optional.ofNullable(runs.get(new RunKey(scope, runId)));
    }

    private static void require(AuthoringScope scope, String key, String fingerprint,
                                Supplier<String> ids, Instant startedAt) {
        if (scope == null || key == null || key.isBlank() || key.length() > 160
                || fingerprint == null || !fingerprint.matches("sha256:[0-9a-f]{64}")
                || ids == null || startedAt == null) {
            throw failure(SimulationFailure.Code.VALIDATION);
        }
    }

    private static SimulationFailure failure(SimulationFailure.Code code) {
        return new SimulationFailure(code);
    }

    private record Key(AuthoringScope scope, String idempotencyKey) { }
    private record RunKey(AuthoringScope scope, String runId) { }
    private record Entry(String requestFingerprint, String runId, SimulationRunV2 run) { }
}
