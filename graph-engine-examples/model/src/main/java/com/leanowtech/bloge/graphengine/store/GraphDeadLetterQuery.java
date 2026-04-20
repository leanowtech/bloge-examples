package com.leanowtech.bloge.graphengine.store;

import com.leanowtech.bloge.core.runtime.work.WorkItemType;

import java.time.Instant;

/**
 * Query object for tenant-scoped dead-letter inspection.
 *
 * @param tenantId tenant filter; {@code null} means the current tenant scope
 * @param namespace namespace filter; {@code null} means the current namespace scope
 * @param itemId optional dead-letter item identifier
 * @param instanceId optional instance filter
 * @param itemType optional work-item type filter
 * @param shardId optional shard filter
 * @param deadLetteredAfter optional lower bound on the dead-letter timestamp
 * @param page zero-based page index
 * @param size requested page size; non-positive values default to {@code 50}
 */
public record GraphDeadLetterQuery(
        String tenantId,
        String namespace,
        String itemId,
        String instanceId,
        WorkItemType itemType,
        String shardId,
        Instant deadLetteredAfter,
        int page,
        int size
) {
    public GraphDeadLetterQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        size = size <= 0 ? 50 : size;
    }
}
