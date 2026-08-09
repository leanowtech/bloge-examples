package com.leanowtech.bloge.gateway.visual.draft;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Process-local save receipt authority for direct tests and lightweight hosts. */
public final class InMemoryGraphDraftSaveReceiptRepository implements GraphDraftSaveReceiptRepository {

    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private final Map<String, StoredGraphDraftSaveReceipt> receipts = new ConcurrentHashMap<>();

    @Override
    public <T> T withCommandLock(
            GraphDraftSaveScope scope,
            String idempotencyKey,
            Supplier<T> operation) {
        synchronized (locks.computeIfAbsent(key(scope, idempotencyKey), ignored -> new Object())) {
            return operation.get();
        }
    }

    @Override
    public Optional<StoredGraphDraftSaveReceipt> find(
            GraphDraftSaveScope scope,
            String idempotencyKey) {
        return Optional.ofNullable(receipts.get(key(scope, idempotencyKey)));
    }

    @Override
    public void save(
            GraphDraftSaveScope scope,
            String idempotencyKey,
            StoredGraphDraftSaveReceipt receipt) {
        StoredGraphDraftSaveReceipt previous = receipts.putIfAbsent(key(scope, idempotencyKey), receipt);
        if (previous != null) {
            throw new IllegalStateException("Graph draft save receipt already exists");
        }
    }

    private static String key(GraphDraftSaveScope scope, String idempotencyKey) {
        return String.join("\u001f", scope.tenantId(), scope.namespace(), scope.environment(), idempotencyKey);
    }
}
