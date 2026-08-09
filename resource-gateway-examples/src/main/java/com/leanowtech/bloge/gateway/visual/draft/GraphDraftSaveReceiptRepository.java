package com.leanowtech.bloge.gateway.visual.draft;

import java.util.Optional;
import java.util.function.Supplier;

/** Transactional command lock and durable receipt authority for Graph draft saves. */
public interface GraphDraftSaveReceiptRepository {

    <T> T withCommandLock(GraphDraftSaveScope scope, String idempotencyKey, Supplier<T> operation);

    Optional<StoredGraphDraftSaveReceipt> find(GraphDraftSaveScope scope, String idempotencyKey);

    void save(GraphDraftSaveScope scope, String idempotencyKey, StoredGraphDraftSaveReceipt receipt);
}
