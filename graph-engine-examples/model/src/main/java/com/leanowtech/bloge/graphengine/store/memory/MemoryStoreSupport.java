package com.leanowtech.bloge.graphengine.store.memory;

import com.leanowtech.bloge.graphengine.store.GraphEngineErrorCode;
import com.leanowtech.bloge.graphengine.store.GraphEngineStoreException;

import java.util.List;

/**
 * Shared helpers for in-memory graph-engine metadata stores.
 */
final class MemoryStoreSupport {
    private MemoryStoreSupport() {
    }

    static void requireExpectedRevision(String entity, String id, long expectedRevision, long actualRevision) {
        if (expectedRevision != actualRevision) {
            throw new GraphEngineStoreException(
                    GraphEngineErrorCode.VERSION_CONFLICT,
                    entity + " revision mismatch for '" + id + "': expected " + expectedRevision + " but was " + actualRevision
            );
        }
    }

    static int normalizeLimit(int limit) {
        return limit <= 0 ? 50 : limit;
    }

    static <T> List<T> slice(List<T> items, int page, int size) {
        int normalizedSize = normalizeLimit(size);
        int from = Math.min(page * normalizedSize, items.size());
        int to = Math.min(from + normalizedSize, items.size());
        return List.copyOf(items.subList(from, to));
    }
}
