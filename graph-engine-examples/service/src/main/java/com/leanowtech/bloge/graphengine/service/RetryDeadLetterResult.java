package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.spi.SystemTimeSource;
import com.leanowtech.bloge.graphengine.model.GraphControlActionEntry;

import java.time.Instant;

/**
 * Result of retrying one dead-lettered work item.
 *
 * @param itemId target work-item identifier
 * @param instanceId owning graph-engine instance identifier, when known
 * @param retriedItemCount number of work items restored by this request or its replayed result
 * @param idempotentReplay whether the result was replayed from an existing terminal control action
 * @param attemptStatus terminal control-action status represented by this result
 * @param status action result status, such as {@code RESTORED} or {@code FAILED}
 * @param requestId caller supplied request/ticket identifier, when present
 * @param failurePhase phase where the replayed action failed, when present
 * @param failureClass exception class of the replayed failure, when present
 * @param failureMessage failure message of the replayed failure, when present
 * @param recordedAt timestamp of this result or the replayed control action
 */
public record RetryDeadLetterResult(
        String itemId,
        String instanceId,
        int retriedItemCount,
        boolean idempotentReplay,
        GraphControlActionEntry.AttemptStatus attemptStatus,
        String status,
        String requestId,
        String failurePhase,
        String failureClass,
        String failureMessage,
        Instant recordedAt
) {
    public RetryDeadLetterResult {
        if (itemId == null || itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (retriedItemCount < 0) {
            throw new IllegalArgumentException("retriedItemCount must be >= 0");
        }
        attemptStatus = attemptStatus == null ? GraphControlActionEntry.AttemptStatus.UNKNOWN : attemptStatus;
        recordedAt = recordedAt == null ? SystemTimeSource.INSTANCE.now() : recordedAt;
    }

    /**
     * Compatibility constructor for directly executed successful retries.
     */
    public RetryDeadLetterResult(String itemId, String instanceId, int retriedItemCount) {
        this(
                itemId,
                instanceId,
                retriedItemCount,
                false,
                GraphControlActionEntry.AttemptStatus.SUCCEEDED,
                "RESTORED",
                null,
                null,
                null,
                null,
                null
        );
    }
}
