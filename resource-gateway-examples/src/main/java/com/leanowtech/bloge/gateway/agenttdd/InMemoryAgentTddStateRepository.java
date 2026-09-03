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
        return Optional.of(entry.response().deepCopy());
    }

    @Override
    public synchronized void record(String scopeKey,
                                    String operation,
                                    String idempotencyKey,
                                    String requestFingerprint,
                                    JsonNode response) {
        String key = idempotencyKey(scopeKey, operation, idempotencyKey);
        IdempotencyEntry previous = idempotency.get(key);
        if (previous != null && !previous.requestFingerprint().equals(requestFingerprint)) {
            throw new AgentTddToolException("IDEMPOTENCY_CONFLICT",
                    "The idempotency key was already used for different request material.");
        }
        idempotency.putIfAbsent(key, new IdempotencyEntry(requestFingerprint, response.deepCopy()));
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

    private static String assetKey(String scope, String kind, String ref) {
        return scope + '\u001f' + kind + '\u001f' + ref;
    }

    private static String idempotencyKey(String scope, String operation, String key) {
        return scope + '\u001f' + operation + '\u001f' + key;
    }

    private record IdempotencyEntry(String requestFingerprint, JsonNode response) { }
}
