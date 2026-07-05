package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.spi.SystemTimeSource;

import java.time.Instant;
import java.util.List;

/**
 * Query-friendly projection of one control-plane action audit event.
 *
 * <p>The raw audit journal stores control action evidence as JSON payloads.
 * This DTO promotes the stable recovery-action fields into a first-class
 * product contract for consoles, alerting, automation, and future idempotency
 * checks.</p>
 *
 * @param instanceId owning instance identifier
 * @param definitionKey owning definition key
 * @param versionId owning version identifier
 * @param tenantId tenant scope
 * @param namespace namespace scope
 * @param nodeId synthetic control node identifier
 * @param operatorRef audit operator/source reference
 * @param actionCode control action code, such as {@code RETRY_DEAD_LETTER}
 * @param sourceActionCode recovery action source, when supplied by the caller
 * @param sourceIndicatorCode SLO/action indicator source, when supplied by the caller
 * @param reason human/operator reason, when supplied
 * @param actor human or automation actor, when supplied
 * @param requestId external incident/ticket/automation request id, when supplied
 * @param attemptStatus lifecycle stage of the control action attempt
 * @param status action result/status from the audit payload
 * @param itemId target dead-letter/work-item id, when available
 * @param itemType target work-item type, when available
 * @param targetNodeId target graph node id, when available
 * @param waitId target wait id, when available
 * @param taskId target task id, when available
 * @param targetInstanceId target instance id from the payload, when present
 * @param expectedRevision optimistic-lock revision used for the action, when present
 * @param requestedNodeIds requested node filter for instance retry
 * @param deadLetterReason source dead-letter reason, when present
 * @param candidateItemCount number of candidate work items considered
 * @param candidateItemIds candidate work-item ids
 * @param candidateNodeIds candidate node ids
 * @param restoredItemCount number of restored work items
 * @param restoredItemIds restored work-item ids
 * @param restoredNodeIds restored node ids
 * @param failurePhase failing phase, such as {@code RESTORE} or {@code DISPATCH}
 * @param failureClass exception class name, when failed
 * @param failureMessage exception message, when failed
 * @param rawInputJson original audit input payload
 * @param rawOutputJson original audit output payload
 * @param recordedAt audit event timestamp
 */
public record GraphControlActionEntry(
        String instanceId,
        String definitionKey,
        String versionId,
        String tenantId,
        String namespace,
        String nodeId,
        String operatorRef,
        String actionCode,
        String sourceActionCode,
        String sourceIndicatorCode,
        String reason,
        String actor,
        String requestId,
        AttemptStatus attemptStatus,
        String status,
        String itemId,
        String itemType,
        String targetNodeId,
        String waitId,
        String taskId,
        String targetInstanceId,
        Long expectedRevision,
        List<String> requestedNodeIds,
        String deadLetterReason,
        int candidateItemCount,
        List<String> candidateItemIds,
        List<String> candidateNodeIds,
        int restoredItemCount,
        List<String> restoredItemIds,
        List<String> restoredNodeIds,
        String failurePhase,
        String failureClass,
        String failureMessage,
        String rawInputJson,
        String rawOutputJson,
        Instant recordedAt
) {
    public GraphControlActionEntry {
        instanceId = requireNonBlank(instanceId, "instanceId");
        definitionKey = requireNonBlank(definitionKey, "definitionKey");
        versionId = requireNonBlank(versionId, "versionId");
        tenantId = resolveScopeValue(tenantId, ExecutionIdentity.DEFAULT_TENANT, "tenantId");
        namespace = resolveScopeValue(namespace, ExecutionIdentity.DEFAULT_NAMESPACE, "namespace");
        nodeId = requireNonBlank(nodeId, "nodeId");
        attemptStatus = attemptStatus == null ? AttemptStatus.UNKNOWN : attemptStatus;
        requestedNodeIds = normalizeList(requestedNodeIds);
        candidateItemIds = normalizeList(candidateItemIds);
        candidateNodeIds = normalizeList(candidateNodeIds);
        restoredItemIds = normalizeList(restoredItemIds);
        restoredNodeIds = normalizeList(restoredNodeIds);
        if (candidateItemCount < 0) {
            throw new IllegalArgumentException("candidateItemCount must be >= 0");
        }
        if (restoredItemCount < 0) {
            throw new IllegalArgumentException("restoredItemCount must be >= 0");
        }
        if (expectedRevision != null && expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be >= 0");
        }
        recordedAt = recordedAt == null ? SystemTimeSource.INSTANCE.now() : recordedAt;
    }

    /**
     * Stable lifecycle stage for one control action audit event.
     */
    public enum AttemptStatus {
        ATTEMPTED,
        SUCCEEDED,
        FAILED,
        UNKNOWN
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

    private static List<String> normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }
}
