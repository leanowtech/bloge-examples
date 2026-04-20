package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.work.WorkItemType;
import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.Objects;

/**
 * Product-layer governance view of one dead-lettered work item.
 *
 * @param itemId work-item identifier
 * @param instanceId owning instance identifier
 * @param definitionKey owning definition key, when the instance projection is available
 * @param versionId owning version identifier, when the instance projection is available
 * @param tenantId tenant scope
 * @param namespace namespace scope
 * @param businessKey optional business correlation key
 * @param shardId optional shard identifier
 * @param itemType durable work-item type
 * @param nodeId runtime node identifier
 * @param waitId wait identifier, when applicable
 * @param taskId task identifier, when applicable
 * @param priority work-item priority
 * @param retryCount consumed retry attempts
 * @param maxRetries configured retry budget
 * @param payload serialized payload snapshot
 * @param payloadRef external payload reference, when used
 * @param lastError last processing error captured before dead-lettering
 * @param deadLetterReason governance reason recorded for the dead-letter transition
 * @param firstSeenAt original work-item creation time
 * @param deadLetteredAt dead-letter timestamp
 */
public record GraphDeadLetter(
        String itemId,
        String instanceId,
        String definitionKey,
        String versionId,
        String tenantId,
        String namespace,
        String businessKey,
        String shardId,
        WorkItemType itemType,
        String nodeId,
        String waitId,
        String taskId,
        int priority,
        int retryCount,
        int maxRetries,
        String payload,
        String payloadRef,
        String lastError,
        String deadLetterReason,
        Instant firstSeenAt,
        Instant deadLetteredAt
) {
    public GraphDeadLetter {
        itemId = requireNonBlank(itemId, "itemId");
        instanceId = requireNonBlank(instanceId, "instanceId");
        tenantId = resolveScopeValue(tenantId, ExecutionIdentity.DEFAULT_TENANT, "tenantId");
        namespace = resolveScopeValue(namespace, ExecutionIdentity.DEFAULT_NAMESPACE, "namespace");
        itemType = Objects.requireNonNull(itemType, "itemType");
        if (priority < 0 || retryCount < 0 || maxRetries < 0) {
            throw new IllegalArgumentException("priority and retry counters must be >= 0");
        }
        firstSeenAt = firstSeenAt == null ? SystemTimeSource.INSTANCE.now() : firstSeenAt;
        deadLetteredAt = deadLetteredAt == null ? firstSeenAt : deadLetteredAt;
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    private static String resolveScopeValue(String value, String fallback, String fieldName) {
        if (value == null) {
            return fallback;
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
