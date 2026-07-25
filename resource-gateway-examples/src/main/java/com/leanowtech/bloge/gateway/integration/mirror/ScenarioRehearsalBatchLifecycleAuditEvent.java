package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free durable fact for one committed Scenario batch transition.
 *
 * <p>The event captures only enterprise scope, immutable batch coordinates, closed queue state,
 * worker fencing coordinates, stable reason codes, and content addresses. Scenario fixtures,
 * graph context, node input/output, request payloads, credentials, exception text, and stack
 * traces are not representable.</p>
 *
 * @param sequence database-assigned append sequence, or zero before persistence
 * @param occurredAt database-authoritative commit time, or {@code null} before persistence
 * @param scope complete enterprise scope
 * @param jobId stable batch job identity
 * @param requestId caller-owned idempotency identity
 * @param manifestFingerprint immutable exact-plan closure
 * @param transition committed lifecycle transition
 * @param jobStatus integrity-verified job status after the transition
 * @param itemIndex affected manifest item index, or {@code -1}
 * @param itemStatus affected item status, or {@link ItemStatus#NONE}
 * @param attemptCount affected item attempt count, or zero
 * @param leaseOwner opaque worker-attempt identity, or blank
 * @param leaseEpoch monotonic worker fence, or zero before a claim
 * @param evidenceBundleFingerprint child or terminal batch evidence content address, or blank
 * @param reasonCode stable transition reason, or blank
 */
public record ScenarioRehearsalBatchLifecycleAuditEvent(
        long sequence,
        Instant occurredAt,
        CapabilitySnapshot.Scope scope,
        String jobId,
        String requestId,
        String manifestFingerprint,
        Transition transition,
        ScenarioRehearsalBatchJob.Status jobStatus,
        int itemIndex,
        ItemStatus itemStatus,
        int attemptCount,
        String leaseOwner,
        long leaseEpoch,
        String evidenceBundleFingerprint,
        String reasonCode
) {
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REASON_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Bounded state changes that matter to scheduling, correctness, and governance. */
    public enum Transition {
        /** First immutable request and manifest admission. */
        ADMITTED,
        /** One pending item entered an owner-and-epoch execution fence. */
        CLAIMED,
        /** One running item reached a durable terminal interpretation. */
        ITEM_TERMINALIZED,
        /** One retryable attempt returned to the queue under the same manifest item. */
        ITEM_RETRY_SCHEDULED,
        /** One exactly replayable cancellation intent was accepted. */
        CANCELLATION_REQUESTED,
        /** Complete terminal material entered the durable evidence-finalization outbox. */
        FINALIZATION_QUEUED,
        /** The complete item closure and signed batch evidence became terminal atomically. */
        TERMINALIZED
    }

    /** Item status vocabulary with an explicit no-item value for job-level transitions. */
    public enum ItemStatus {
        NONE,
        PENDING,
        RUNNING,
        PASSED,
        FAILED,
        INDETERMINATE,
        CANCELLED;

        static ItemStatus from(ScenarioRehearsalBatchItemPage.Status status) {
            return status == null
                    ? NONE
                    : valueOf(status.name());
        }
    }

    /** Enforces the closed payload-free transition contract. */
    public ScenarioRehearsalBatchLifecycleAuditEvent {
        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "Scenario batch lifecycle sequence must be non-negative");
        }
        if (sequence > 0 && occurredAt == null) {
            throw new IllegalArgumentException(
                    "Persisted Scenario batch lifecycle events require database time");
        }
        scope = Objects.requireNonNull(scope, "scope");
        jobId = bounded(jobId, 512, "jobId");
        requestId = bounded(requestId, 256, "requestId");
        manifestFingerprint = fingerprint(
                manifestFingerprint, "manifestFingerprint", true);
        transition = Objects.requireNonNull(transition, "transition");
        jobStatus = Objects.requireNonNull(jobStatus, "jobStatus");
        itemStatus = Objects.requireNonNull(itemStatus, "itemStatus");
        if (itemIndex < -1
                || itemIndex >= ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES) {
            throw new IllegalArgumentException(
                    "Scenario batch lifecycle item index is outside policy bounds");
        }
        if (attemptCount < 0 || leaseEpoch < 0) {
            throw new IllegalArgumentException(
                    "Scenario batch lifecycle attempt and lease coordinates must be non-negative");
        }
        leaseOwner = optional(leaseOwner, 512, "leaseOwner");
        evidenceBundleFingerprint = fingerprint(
                evidenceBundleFingerprint,
                "evidenceBundleFingerprint",
                false);
        reasonCode = optional(reasonCode, 255, "reasonCode")
                .toUpperCase(java.util.Locale.ROOT);
        if (!reasonCode.isBlank()
                && !REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "Scenario batch lifecycle reason code is invalid");
        }
        if (leaseOwner.isBlank() != (leaseEpoch == 0)) {
            throw new IllegalArgumentException(
                    "Scenario batch lifecycle lease owner and epoch are inconsistent");
        }
        requireShape(
                transition,
                jobStatus,
                itemIndex,
                itemStatus,
                attemptCount,
                leaseOwner,
                leaseEpoch,
                evidenceBundleFingerprint,
                reasonCode);
    }

    /**
     * Returns this fact with database-assigned persistence coordinates.
     *
     * @param assignedSequence positive append sequence
     * @param databaseTime database-authoritative occurrence time
     * @return immutable persisted event
     */
    public ScenarioRehearsalBatchLifecycleAuditEvent persisted(
            long assignedSequence,
            Instant databaseTime) {
        if (assignedSequence < 1 || databaseTime == null) {
            throw new IllegalArgumentException(
                    "Scenario batch lifecycle persistence coordinates are required");
        }
        return new ScenarioRehearsalBatchLifecycleAuditEvent(
                assignedSequence,
                databaseTime,
                scope,
                jobId,
                requestId,
                manifestFingerprint,
                transition,
                jobStatus,
                itemIndex,
                itemStatus,
                attemptCount,
                leaseOwner,
                leaseEpoch,
                evidenceBundleFingerprint,
                reasonCode);
    }

    private static void requireShape(
            Transition transition,
            ScenarioRehearsalBatchJob.Status jobStatus,
            int itemIndex,
            ItemStatus itemStatus,
            int attemptCount,
            String leaseOwner,
            long leaseEpoch,
            String evidenceFingerprint,
            String reasonCode) {
        boolean identifiesItem = itemIndex >= 0;
        if (identifiesItem != (itemStatus != ItemStatus.NONE)) {
            throw new IllegalArgumentException(
                    "Scenario batch lifecycle item coordinates are inconsistent");
        }
        switch (transition) {
            case ADMITTED -> require(
                    jobStatus == ScenarioRehearsalBatchJob.Status.QUEUED
                            && !identifiesItem
                            && attemptCount == 0
                            && leaseOwner.isBlank()
                            && leaseEpoch == 0
                            && evidenceFingerprint.isBlank()
                            && reasonCode.isBlank(),
                    "admission audit coordinates are inconsistent");
            case CLAIMED -> require(
                    jobStatus == ScenarioRehearsalBatchJob.Status.RUNNING
                            && identifiesItem
                            && itemStatus == ItemStatus.RUNNING
                            && attemptCount > 0
                            && !leaseOwner.isBlank()
                            && leaseEpoch > 0
                            && evidenceFingerprint.isBlank()
                            && reasonCode.isBlank(),
                    "claim audit coordinates are inconsistent");
            case ITEM_TERMINALIZED -> require(
                    (jobStatus == ScenarioRehearsalBatchJob.Status.RUNNING
                            || jobStatus
                            == ScenarioRehearsalBatchJob.Status.CANCEL_REQUESTED)
                            && identifiesItem
                            && terminal(itemStatus)
                            && attemptCount > 0
                            && !leaseOwner.isBlank()
                            && leaseEpoch > 0,
                    "item terminal audit coordinates are inconsistent");
            case ITEM_RETRY_SCHEDULED -> require(
                    jobStatus == ScenarioRehearsalBatchJob.Status.QUEUED
                            && identifiesItem
                            && itemStatus == ItemStatus.PENDING
                            && attemptCount > 0
                            && !leaseOwner.isBlank()
                            && leaseEpoch > 0
                            && evidenceFingerprint.isBlank()
                            && !reasonCode.isBlank(),
                    "item retry audit coordinates are inconsistent");
            case CANCELLATION_REQUESTED -> require(
                    (jobStatus
                            == ScenarioRehearsalBatchJob.Status.CANCEL_REQUESTED
                            || jobStatus
                            == ScenarioRehearsalBatchJob.Status.CANCELLED)
                            && !identifiesItem
                            && attemptCount == 0
                            && evidenceFingerprint.isBlank()
                            && !reasonCode.isBlank(),
                    "cancellation audit coordinates are inconsistent");
            case FINALIZATION_QUEUED -> require(
                    jobStatus
                            == ScenarioRehearsalBatchJob.Status
                            .FINALIZING_EVIDENCE
                            && !identifiesItem
                            && attemptCount == 0
                            && evidenceFingerprint.isBlank(),
                    "finalization queue audit coordinates are inconsistent");
            case TERMINALIZED -> require(
                    jobStatus.terminal()
                            && !identifiesItem
                            && attemptCount == 0
                            && !evidenceFingerprint.isBlank()
                            && (jobStatus
                            == ScenarioRehearsalBatchJob.Status.SUCCEEDED
                            || !reasonCode.isBlank()),
                    "terminal batch audit coordinates are inconsistent");
        }
    }

    private static boolean terminal(ItemStatus status) {
        return status == ItemStatus.PASSED
                || status == ItemStatus.FAILED
                || status == ItemStatus.INDETERMINATE
                || status == ItemStatus.CANCELLED;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String fingerprint(
            String value,
            String field,
            boolean required) {
        String normalized = optional(value, 71, field);
        if ((required && normalized.isBlank())
                || (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches())) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String bounded(
            String value,
            int maximum,
            String field) {
        String normalized = optional(value, maximum, field);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }

    private static String optional(
            String value,
            int maximum,
            String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " exceeds its bound");
        }
        return normalized;
    }
}
