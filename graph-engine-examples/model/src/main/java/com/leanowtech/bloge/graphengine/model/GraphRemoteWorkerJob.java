package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.operator.RemoteWorkerEnvelope;
import com.leanowtech.bloge.core.runtime.work.WorkItemStatus;
import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.Objects;

/**
 * Product-layer projection of one claimed remote-worker execution item.
 *
 * @param itemId durable work-item identifier
 * @param claimOwner current worker owner
 * @param claimToken active lease token
 * @param claimUntil lease expiry timestamp
 * @param status durable work-item status
 * @param priority work-item priority
 * @param retryCount consumed retry attempts
 * @param maxRetries configured retry budget
 * @param revision optimistic-lock revision
 * @param lastError last recorded error, when present
 * @param envelope decoded remote-worker payload
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 */
public record GraphRemoteWorkerJob(
        String itemId,
        String claimOwner,
        String claimToken,
        Instant claimUntil,
        WorkItemStatus status,
        int priority,
        int retryCount,
        int maxRetries,
        long revision,
        String lastError,
        RemoteWorkerEnvelope envelope,
        Instant createdAt,
        Instant updatedAt
) {
    public GraphRemoteWorkerJob {
        itemId = requireNonBlank(itemId, "itemId");
        status = Objects.requireNonNull(status, "status");
        if (priority < 0 || retryCount < 0 || maxRetries < 0) {
            throw new IllegalArgumentException("priority and retry counters must be >= 0");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        envelope = Objects.requireNonNull(envelope, "envelope");
        createdAt = createdAt == null ? SystemTimeSource.INSTANCE.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
