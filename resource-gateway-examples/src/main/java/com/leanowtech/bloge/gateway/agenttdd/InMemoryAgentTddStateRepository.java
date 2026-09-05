package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** In-memory Agent TDD overlay store for focused tests and local composition. */
public final class InMemoryAgentTddStateRepository implements AgentTddStateRepository {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Map<String, AgentTddStoredAsset> assets = new ConcurrentHashMap<>();
    private final Map<String, IdempotencyEntry> idempotency = new ConcurrentHashMap<>();

    @Override
    public Optional<AgentTddStoredAsset> find(String scopeKey, String kind, String assetRef) {
        return Optional.ofNullable(assets.get(assetKey(scopeKey, kind, assetRef)));
    }

    @Override
    public List<AgentTddStoredAsset> list(String scopeKey, String kind) {
        return assets.values().stream()
                .filter(value -> value.scopeKey().equals(scopeKey) && value.kind().equals(kind))
                .sorted(Comparator.comparing(AgentTddStoredAsset::assetRef))
                .toList();
    }

    /** Captures all selected kinds while the same monitor excludes concurrent local writes. */
    @Override
    public synchronized AssetReadSnapshot readSnapshot(String scopeKey, List<String> kinds) {
        java.util.Set<String> selectedKinds = kinds == null ? java.util.Set.of() : kinds.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(kind -> !kind.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new AssetReadSnapshot(scopeKey, assets.values().stream()
                .filter(asset -> asset.scopeKey().equals(scopeKey)
                        && selectedKinds.contains(asset.kind()))
                .toList());
    }

    @Override
    public synchronized AgentTddStoredAsset save(String scopeKey, String kind, String assetRef, JsonNode data) {
        AgentTddStoredAsset previous = assets.get(assetKey(scopeKey, kind, assetRef));
        return saveIfRevision(scopeKey, kind, assetRef, previous == null ? 0 : previous.revision(), data);
    }

    @Override
    public synchronized AgentTddStoredAsset saveIfRevision(String scopeKey,
                                                           String kind,
                                                           String assetRef,
                                                           long expectedRevision,
                                                           JsonNode data) {
        AgentTddStoredAsset previous = assets.get(assetKey(scopeKey, kind, assetRef));
        long actualRevision = previous == null ? 0 : previous.revision();
        if (actualRevision != expectedRevision) {
            throw new AgentTddToolException("GATE_REJECTED", "Asset changed after the reviewed revision.");
        }
        AgentTddStoredAsset stored = new AgentTddStoredAsset(scopeKey, kind, assetRef,
                expectedRevision + 1,
                VisualBundleFingerprint.fromCanonicalValue(new com.fasterxml.jackson.databind.ObjectMapper(),
                        data, MAX_BYTES), data, Instant.now());
        assets.put(assetKey(scopeKey, kind, assetRef), stored);
        return stored;
    }

    /** Preserves production rollback semantics for local composition and focused tests. */
    @Override
    public synchronized <T> T executeAtomically(Supplier<T> action) {
        Map<String, AgentTddStoredAsset> assetSnapshot = new LinkedHashMap<>(assets);
        Map<String, IdempotencyEntry> idempotencySnapshot = new LinkedHashMap<>(idempotency);
        try {
            return action.get();
        } catch (RuntimeException | Error failure) {
            assets.clear();
            assets.putAll(assetSnapshot);
            idempotency.clear();
            idempotency.putAll(idempotencySnapshot);
            throw failure;
        }
    }

    /** Reads the revision while the enclosing synchronized atomic unit excludes concurrent writes. */
    @Override
    public synchronized AgentTddStoredAsset lockRevision(String scopeKey,
                                                         String kind,
                                                         String assetRef,
                                                         long expectedRevision) {
        return AgentTddStateRepository.super.lockRevision(
                scopeKey, kind, assetRef, expectedRevision);
    }

    @Override
    public Optional<JsonNode> replay(String scopeKey,
                                     String operation,
                                     String idempotencyKey,
                                     String requestFingerprint) {
        IdempotencyEntry entry = idempotency.get(idempotencyKey(scopeKey, operation, idempotencyKey));
        if (entry == null) return Optional.empty();
        if (!entry.requestFingerprint().equals(requestFingerprint)) {
            throw new AgentTddToolException("IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for different request material.");
        }
        return entry.completed() ? Optional.of(entry.response().deepCopy()) : Optional.empty();
    }

    @Override
    public synchronized void record(String scopeKey,
                                    String operation,
                                    String idempotencyKey,
                                    String requestFingerprint,
                                    JsonNode response) {
        String key = idempotencyKey(scopeKey, operation, idempotencyKey);
        IdempotencyEntry previous = idempotency.get(key);
        if (previous != null) {
            requireMatchingFingerprint(previous, requestFingerprint);
            if (previous.completed()) return;
        }
        idempotency.put(key, new IdempotencyEntry(requestFingerprint, response.deepCopy(), true));
    }

    @Override
    public synchronized JsonNode executeOnce(String scopeKey,
                                             String operation,
                                             String idempotencyKey,
                                             String requestFingerprint,
                                             Supplier<JsonNode> action) {
        Optional<JsonNode> replay = replay(scopeKey, operation, idempotencyKey, requestFingerprint);
        if (replay.isPresent()) return replay.get();
        JsonNode response = action.get();
        record(scopeKey, operation, idempotencyKey, requestFingerprint, response);
        return response.deepCopy();
    }

    @Override
    public synchronized ExternalExecutionReservation reserveExternalExecution(
            String scopeKey,
            String operation,
            String idempotencyKey,
            String requestFingerprint) {
        String key = idempotencyKey(scopeKey, operation, idempotencyKey);
        IdempotencyEntry existing = idempotency.get(key);
        if (existing != null) {
            requireMatchingFingerprint(existing, requestFingerprint);
            return existing.completed()
                    ? new ExternalExecutionReservation(ExternalExecutionStatus.COMPLETED, existing.response())
                    : new ExternalExecutionReservation(ExternalExecutionStatus.IN_PROGRESS, null);
        }
        idempotency.put(key, new IdempotencyEntry(requestFingerprint, null, false));
        return new ExternalExecutionReservation(ExternalExecutionStatus.ACQUIRED, null);
    }

    @Override
    public synchronized JsonNode completeExternalExecution(String scopeKey,
                                                           String operation,
                                                           String idempotencyKey,
                                                           String requestFingerprint,
                                                           JsonNode response) {
        String key = idempotencyKey(scopeKey, operation, idempotencyKey);
        IdempotencyEntry existing = idempotency.get(key);
        if (existing == null) {
            throw new AgentTddToolException("IDEMPOTENCY_CONFLICT",
                    "The external execution reservation does not exist.");
        }
        requireMatchingFingerprint(existing, requestFingerprint);
        if (existing.completed()) return existing.response().deepCopy();
        JsonNode copy = response == null ? com.fasterxml.jackson.databind.node.NullNode.getInstance()
                : response.deepCopy();
        idempotency.put(key, new IdempotencyEntry(requestFingerprint, copy, true));
        return copy.deepCopy();
    }

    private static void requireMatchingFingerprint(IdempotencyEntry entry, String requestFingerprint) {
        if (!entry.requestFingerprint().equals(requestFingerprint)) {
            throw new AgentTddToolException("IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for different request material.");
        }
    }

    private static String assetKey(String scope, String kind, String ref) {
        return scope + '\u001f' + kind + '\u001f' + ref;
    }

    private static String idempotencyKey(String scope, String operation, String key) {
        return scope + '\u001f' + operation + '\u001f' + key;
    }

    private record IdempotencyEntry(String requestFingerprint, JsonNode response, boolean completed) { }
}
