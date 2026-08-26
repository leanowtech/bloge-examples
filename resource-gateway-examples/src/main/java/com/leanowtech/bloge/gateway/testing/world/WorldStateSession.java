package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.HashSet;
import java.util.function.Function;

/** One isolated, run-scoped state machine for a Scenario execution. */
public final class WorldStateSession implements AutoCloseable {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final int MAX_TRANSACTIONS = 4_096;
    private static final int MAX_STATE_BYTES = 256 * 1024;

    public record Binding(
            String scenarioFingerprint,
            String worldFingerprint,
            String graphArtifactFingerprint,
            String runId
    ) {
        public Binding {
            scenarioFingerprint = fingerprint(scenarioFingerprint);
            worldFingerprint = fingerprint(worldFingerprint);
            graphArtifactFingerprint = fingerprint(graphArtifactFingerprint);
            runId = runId(runId);
        }

        private static String fingerprint(String value) {
            if (value == null || !value.matches("sha256:[0-9a-f]{64}")) {
                throw new WorldModelException(WorldModelException.Code.STATE_BINDING_INVALID);
            }
            return value.trim();
        }

        private static String runId(String value) {
            if (value == null || value.isBlank() || value.trim().length() > 256) {
                throw new WorldModelException(WorldModelException.Code.STATE_BINDING_INVALID);
            }
            return value.trim();
        }
    }

    public record StateTransition<T>(T result, Map<String, ?> writes) {
        public StateTransition {
            if (writes == null) {
                throw new WorldModelException(WorldModelException.Code.STATE_WRITESET_INVALID);
            }
            try {
                writes = freeze(writes);
            } catch (WorldModelException invalid) {
                throw invalid;
            } catch (RuntimeException invalid) {
                throw new WorldModelException(WorldModelException.Code.STATE_WRITESET_INVALID);
            }
        }

        private static Map<String, Object> freeze(Map<String, ?> source) {
            try {
                @SuppressWarnings("unchecked") Map<String, Object> result =
                        (Map<String, Object>) (Map<?, ?>) ProtocolJsonValue.freeze(source);
                return result;
            } catch (RuntimeException invalid) {
                throw new WorldModelException(WorldModelException.Code.STATE_WRITESET_INVALID);
            }
        }
    }

    private final WorldStateSpec stateSpec;
    private final StateAccessPlan stateAccessPlan;
    private final Binding binding;
    private final String stateSpecFingerprint;
    private Map<String, Object> state;
    private long revision;
    private List<WorldStateTransactionObservation> observations;
    private boolean closed;
    private boolean transitionInProgress;

    public WorldStateSession(WorldStateSpec stateSpec,
                             Map<String, ?> initialOverrides,
                             Binding binding) {
        this(stateSpec, initialOverrides, binding, StateAccessPlan.empty());
        if (stateSpec != null && !stateSpec.isEmpty()) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
    }

    public WorldStateSession(WorldStateSpec stateSpec,
                             Map<String, ?> initialOverrides,
                             Binding binding,
                             StateAccessPlan stateAccessPlan) {
        if (stateSpec == null || binding == null || initialOverrides == null) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
        if (stateAccessPlan == null) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
        this.stateSpec = stateSpec;
        this.stateAccessPlan = stateAccessPlan;
        validatePlan(stateSpec, stateAccessPlan);
        this.binding = binding;
        try {
            this.stateSpecFingerprint = stateSpec.fingerprint();
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
        this.state = initialState(stateSpec, initialOverrides);
        this.revision = 0;
        this.observations = List.of();
    }

    public Binding binding() {
        return binding;
    }

    public String stateSpecFingerprint() {
        return stateSpecFingerprint;
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized WorldStateView read(StateAccessPlan.Access access) {
        ensureOpen();
        StateAccessPlan.Access approved = approvedAccess(access);
        Map<String, Object> projected = new TreeMap<>();
        for (String key : approved.readKeys()) {
            StateKeySpec declaration = declaration(key);
            if (declaration.access() == StateKeySpec.Access.WRITE) {
                throw new WorldModelException(WorldModelException.Code.STATE_READ_NOT_ALLOWED);
            }
            projected.put(key, state.get(key));
        }
        return view(projected);
    }

    synchronized WorldStateView readAll() {
        ensureOpen();
        Map<String, Object> projected = new TreeMap<>();
        for (StateKeySpec declaration : stateSpec.declarations()) {
            if (declaration.access() != StateKeySpec.Access.WRITE) {
                projected.put(declaration.key(), state.get(declaration.key()));
            }
        }
        return view(projected);
    }

    public synchronized <T> T transition(WorldInvocationCoordinate coordinate,
                                          StateAccessPlan.Access access,
                                          Function<WorldStateView, StateTransition<T>> evaluator) {
        ensureOpen();
        ensureMutationAllowed();
        if (coordinate == null || access == null
                || evaluator == null || observations.size() >= MAX_TRANSACTIONS) {
            throw new WorldModelException(WorldModelException.Code.STATE_TRANSACTION_INVALID);
        }
        StateAccessPlan.Access approved = approvedAccess(access);
        if (!coordinate.matchesStructuralSite()
                || !coordinate.structuralInvocationSiteId().equals(approved.coordinate())
                || !coordinate.nodeId().equals(approved.nodeId())
                || observations.stream().anyMatch(observation ->
                observation.coordinate().canonicalKey().equals(coordinate.canonicalKey()))) {
            throw new WorldModelException(WorldModelException.Code.STATE_TRANSACTION_INVALID);
        }
        Set<String> readKeys = Set.copyOf(approved.readKeys());
        Set<String> writeKeys = Set.copyOf(approved.writeKeys());
        Set<String> reads = Set.copyOf(readKeys);
        Set<String> writes = Set.copyOf(writeKeys);
        for (String key : reads) {
            if (declaration(key).access() == StateKeySpec.Access.WRITE) {
                throw new WorldModelException(WorldModelException.Code.STATE_READ_NOT_ALLOWED);
            }
        }
        for (String key : writes) {
            if (!declaration(key).writes()) {
                throw new WorldModelException(WorldModelException.Code.STATE_READ_ONLY_WRITE);
            }
        }
        WorldStateView before = read(approved);
        transitionInProgress = true;
        try {
            StateTransition<T> transition;
            try {
                transition = evaluator.apply(before);
            } catch (WorldModelException failure) {
                throw failure;
            } catch (RuntimeException failure) {
                throw new WorldModelException(WorldModelException.Code.STATE_TRANSITION_FAILED);
            }
            if (transition == null) {
                throw new WorldModelException(WorldModelException.Code.STATE_TRANSITION_FAILED);
            }
            Map<String, Object> normalizedWrites = validateWrites(writes, transition.writes());
            Map<String, Object> next = new TreeMap<>(state);
            normalizedWrites.forEach(next::put);
            validateState(next);

            T detachedResult = immutableResult(transition.result());
            String readFingerprint = fingerprint(valuesFor(reads));
            String writeFingerprint = fingerprint(normalizedWrites);
            String resultFingerprint = fingerprint(detachedResult);
            WorldStateTransactionObservation observation = new WorldStateTransactionObservation(
                    coordinate, List.copyOf(reads), List.copyOf(writes), readFingerprint,
                    writeFingerprint, resultFingerprint);
            state = freezeMap(next);
            revision++;
            List<WorldStateTransactionObservation> nextObservations = new ArrayList<>(observations);
            nextObservations.add(observation);
            observations = List.copyOf(nextObservations);
            return detachedResult;
        } finally {
            transitionInProgress = false;
        }
    }

    public synchronized WorldStateSnapshot snapshot() {
        ensureOpen();
        String fingerprint = WorldStateSnapshot.fingerprint(binding, stateSpecFingerprint,
                revision, state, observations);
        return new WorldStateSnapshot(binding, stateSpecFingerprint, revision, state,
                observations, fingerprint);
    }

    public synchronized void restore(WorldStateSnapshot snapshot) {
        ensureOpen();
        ensureMutationAllowed();
        if (snapshot == null || !binding.equals(snapshot.binding())
                || !stateSpecFingerprint.equals(snapshot.stateSpecFingerprint())) {
            throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_WRONG_BINDING);
        }
        validateState(snapshot.state());
        validateObservations(snapshot.revision(), snapshot.observations());
        if (!WorldStateSnapshot.fingerprint(snapshot.binding(), snapshot.stateSpecFingerprint(),
                snapshot.revision(), snapshot.state(), snapshot.observations())
                .equals(snapshot.fingerprint())) {
            throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_TAMPERED);
        }
        state = freezeMap(snapshot.state());
        revision = snapshot.revision();
        observations = List.copyOf(snapshot.observations());
    }

    public synchronized List<WorldStateTransactionObservation> observations() {
        ensureOpen();
        return observations.stream().sorted(java.util.Comparator
                .comparing(value -> value.coordinate().canonicalKey())).toList();
    }

    private WorldStateView view(Map<String, Object> values) {
        return new WorldStateView(values, revision, fingerprint(values));
    }

    private Map<String, Object> initialState(WorldStateSpec spec, Map<String, ?> overrides) {
        if (overrides == null) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
        Map<String, Object> values = new TreeMap<>();
        for (StateKeySpec declaration : spec.declarations()) {
            values.put(declaration.key(), ProtocolJsonValue.freeze(declaration.defaultValue()));
        }
        for (Map.Entry<String, ?> override : overrides.entrySet()) {
            StateKeySpec declaration = declaration(spec, override.getKey());
            if (!declaration.accepts(override.getValue())) {
                throw new WorldModelException(WorldModelException.Code.STATE_SCHEMA_MISMATCH);
            }
            values.put(override.getKey(), ProtocolJsonValue.freeze(override.getValue()));
        }
        validateState(values);
        return freezeMap(values);
    }

    private Map<String, Object> validateWrites(Set<String> declaredKeys,
                                                Map<String, ?> writes) {
        Map<String, Object> normalized = new TreeMap<>();
        for (Map.Entry<String, ?> entry : writes.entrySet()) {
            if (!declaredKeys.contains(entry.getKey())) {
                throw new WorldModelException(WorldModelException.Code.STATE_UNKNOWN_WRITE);
            }
            StateKeySpec declaration = declaration(entry.getKey());
            if (!declaration.accepts(entry.getValue())) {
                throw new WorldModelException(WorldModelException.Code.STATE_SCHEMA_MISMATCH);
            }
            normalized.put(entry.getKey(), ProtocolJsonValue.freeze(entry.getValue()));
        }
        if (!declaredKeys.containsAll(normalized.keySet())) {
            throw new WorldModelException(WorldModelException.Code.STATE_WRITESET_INVALID);
        }
        return normalized;
    }

    private void validateState(Map<String, ?> values) {
        if (values.size() != stateSpec.declarations().size()) {
            throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
        }
        for (StateKeySpec declaration : stateSpec.declarations()) {
            if (!values.containsKey(declaration.key()) || !declaration.accepts(values.get(declaration.key()))) {
                throw new WorldModelException(WorldModelException.Code.STATE_SCHEMA_MISMATCH);
            }
        }
        try {
            ProtocolFingerprint.ofBounded(MAPPER, values, MAX_STATE_BYTES);
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.LIMIT_EXCEEDED);
        }
    }

    private void validateObservations(long snapshotRevision,
                                     List<WorldStateTransactionObservation> values) {
        if (values == null || values.size() > MAX_TRANSACTIONS
                || snapshotRevision != values.size()) {
            throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_INVALID);
        }
        Set<String> coordinates = new HashSet<>();
        for (WorldStateTransactionObservation observation : values) {
            if (observation == null || !coordinates.add(observation.coordinate().canonicalKey())) {
                throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_INVALID);
            }
            StateAccessPlan.Access access = stateAccessPlan.access(
                    observation.coordinate().structuralInvocationSiteId());
            if (access == null
                    || !observation.coordinate().matchesStructuralSite()
                    || !access.nodeId().equals(observation.coordinate().nodeId())
                    || !access.readKeys().equals(observation.readKeys())
                    || !access.writeKeys().equals(observation.writeKeys())) {
                throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_INVALID);
            }
            for (String key : observation.readKeys()) {
                if (declaration(key).access() == StateKeySpec.Access.WRITE) {
                    throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_INVALID);
                }
            }
            for (String key : observation.writeKeys()) {
                if (!declaration(key).writes()) {
                    throw new WorldModelException(WorldModelException.Code.STATE_SNAPSHOT_INVALID);
                }
            }
        }
    }

    private StateAccessPlan.Access approvedAccess(StateAccessPlan.Access requested) {
        if (requested == null) {
            throw new WorldModelException(WorldModelException.Code.STATE_TRANSACTION_INVALID);
        }
        StateAccessPlan.Access approved = stateAccessPlan.access(requested.coordinate());
        if (approved == null || !approved.equals(requested)) {
            throw new WorldModelException(WorldModelException.Code.STATE_ACCESS_PLAN_MISMATCH);
        }
        return approved;
    }

    private static void validatePlan(WorldStateSpec spec, StateAccessPlan plan) {
        try {
            for (StateAccessPlan.Access access : plan.accesses()) {
                for (String key : access.readKeys()) {
                    StateKeySpec declaration = planDeclaration(spec, key);
                    if (declaration.access() == StateKeySpec.Access.WRITE) {
                        throw planMismatch();
                    }
                }
                for (String key : access.writeKeys()) {
                    if (!planDeclaration(spec, key).writes()) {
                        throw planMismatch();
                    }
                }
            }
        } catch (WorldModelException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw planMismatch();
        }
    }

    private static StateKeySpec planDeclaration(WorldStateSpec spec, String key) {
        try {
            return declaration(spec, key);
        } catch (RuntimeException invalid) {
            throw planMismatch();
        }
    }

    private static WorldModelException planMismatch() {
        return new WorldModelException(WorldModelException.Code.STATE_ACCESS_PLAN_MISMATCH);
    }

    private StateKeySpec declaration(String key) {
        return declaration(stateSpec, key);
    }

    private static StateKeySpec declaration(WorldStateSpec spec, String key) {
        if (key == null) {
            throw new WorldModelException(WorldModelException.Code.STATE_UNKNOWN_WRITE);
        }
        return spec.declarations().stream().filter(value -> value.key().equals(key)).findFirst()
                .orElseThrow(() -> new WorldModelException(WorldModelException.Code.STATE_UNKNOWN_WRITE));
    }

    private Map<String, Object> valuesFor(Set<String> keys) {
        Map<String, Object> result = new TreeMap<>();
        keys.forEach(key -> result.put(key, state.get(key)));
        return result;
    }

    private String fingerprint(Object value) {
        try {
            return ProtocolFingerprint.of(MAPPER, value);
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.STATE_TRANSITION_FAILED);
        }
    }

    private static Map<String, Object> freezeMap(Map<String, ?> source) {
        @SuppressWarnings("unchecked") Map<String, Object> frozen =
                (Map<String, Object>) (Map<?, ?>) ProtocolJsonValue.freeze(source);
        return frozen;
    }

    @SuppressWarnings("unchecked")
    private static <T> T immutableResult(T result) {
        try {
            return (T) ProtocolJsonValue.freeze(result);
        } catch (RuntimeException invalid) {
            throw new WorldModelException(WorldModelException.Code.STATE_TRANSITION_FAILED);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new WorldModelException(WorldModelException.Code.STATE_SESSION_CLOSED);
        }
    }

    private void ensureMutationAllowed() {
        if (transitionInProgress) {
            throw new WorldModelException(WorldModelException.Code.STATE_TRANSACTION_INVALID);
        }
    }

    @Override
    public synchronized void close() {
        ensureOpen();
        ensureMutationAllowed();
        closed = true;
        state = Map.of();
        observations = List.of();
    }
}
