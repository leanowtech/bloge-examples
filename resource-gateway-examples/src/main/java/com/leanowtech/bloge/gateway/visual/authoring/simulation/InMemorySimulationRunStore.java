package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** Thread-safe reference store used by domain tests and local adapters. */
public final class InMemorySimulationRunStore implements SimulationRunStore {
    private final Map<Key, Entry> commands = new HashMap<>();
    private final Map<RunKey, SimulationRun> runs = new HashMap<>();

    @Override public synchronized Claim claim(AuthoringScope scope, String idempotencyKey,
                                               String requestFingerprint, Supplier<String> runIdFactory,
                                               Instant startedAt) {
        require(scope, idempotencyKey, requestFingerprint, runIdFactory, startedAt);
        Key key = new Key(scope, idempotencyKey);
        Entry prior = commands.get(key);
        if (prior == null) {
            String runId = runIdFactory.get();
            if (runId == null || runId.isBlank()) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
            commands.put(key, new Entry(requestFingerprint, runId, null));
            return new Claim.Acquired(runId);
        }
        if (!prior.requestFingerprint().equals(requestFingerprint)) return new Claim.Conflict();
        return prior.run() == null ? new Claim.Busy(prior.runId()) : new Claim.Replay(prior.run());
    }

    @Override public synchronized SimulationRun complete(AuthoringScope scope, String idempotencyKey,
                                                          String requestFingerprint, SimulationRun run) {
        if (run == null) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        Entry prior = commands.get(new Key(scope, idempotencyKey));
        if (prior == null || !prior.requestFingerprint().equals(requestFingerprint)
                || !prior.runId().equals(run.runId())) {
            throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        }
        if (prior.run() != null) {
            if (!prior.run().equals(run)) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
            return prior.run();
        }
        commands.put(new Key(scope, idempotencyKey), new Entry(requestFingerprint, run.runId(), run));
        SimulationRun collision = runs.putIfAbsent(new RunKey(scope, run.runId()), run);
        if (collision != null && !collision.equals(run)) {
            throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        }
        return run;
    }

    @Override public synchronized Optional<SimulationRun> find(AuthoringScope scope, String runId) {
        if (scope == null || runId == null || runId.isBlank()) return Optional.empty();
        return Optional.ofNullable(runs.get(new RunKey(scope, runId)));
    }

    private static void require(AuthoringScope scope, String key, String fingerprint,
                                Supplier<String> runIdFactory, Instant startedAt) {
        if (scope == null || key == null || key.isBlank() || key.length() > 160
                || fingerprint == null || !fingerprint.matches("sha256:[0-9a-f]{64}")
                || runIdFactory == null || startedAt == null) {
            throw new SimulationFailure(SimulationFailure.Code.VALIDATION);
        }
    }

    private record Key(AuthoringScope scope, String idempotencyKey) { }
    private record RunKey(AuthoringScope scope, String runId) { }
    private record Entry(String requestFingerprint, String runId, SimulationRun run) { }
}
