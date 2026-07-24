package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free public projection of one durable Scenario rehearsal batch.
 *
 * <p>The projection exposes scheduling and correctness counters without leaking TestSuite input,
 * fixture values, child payloads, or worker identities. Item detail is served separately through
 * a bounded page. {@code recordFingerprint} protects the complete mutable projection from silent
 * database corruption; terminal signed evidence is a separate portable artifact.</p>
 *
 * @param schemaVersion public job protocol version
 * @param jobId server-derived stable batch identity
 * @param requestId caller idempotency identity
 * @param requestFingerprint exact submitted command fingerprint
 * @param manifestFingerprint ordered plan and child-request closure fingerprint
 * @param scope complete enterprise namespace
 * @param status durable lifecycle state
 * @param failureMode immutable non-passing item policy
 * @param priority immutable base scheduling priority
 * @param maximumItemAttempts immutable per-item retry bound
 * @param summary server-derived item counters
 * @param deadlineAt absolute database-clock deadline
 * @param failureCode bounded structural terminal or retry diagnostic
 * @param cancellationRequestId first accepted cancellation idempotency key
 * @param cancellationReasonCode bounded cancellation reason
 * @param createdAt database admission time
 * @param updatedAt latest durable transition time
 * @param completedAt terminal time, otherwise null
 * @param recordFingerprint canonical mutable projection fingerprint
 */
public record ScenarioRehearsalBatchJob(
        String schemaVersion,
        String jobId,
        String requestId,
        String requestFingerprint,
        String manifestFingerprint,
        CapabilitySnapshot.Scope scope,
        Status status,
        ScenarioRehearsalBatchPolicy.FailureMode failureMode,
        ScenarioRehearsalBatchPolicy.Priority priority,
        int maximumItemAttempts,
        Summary summary,
        Instant deadlineAt,
        String failureCode,
        String cancellationRequestId,
        String cancellationReasonCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        String recordFingerprint
) {
    /** Current public durable-batch job version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalBatchJob.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Closed durable lifecycle vocabulary. */
    public enum Status {
        QUEUED,
        RUNNING,
        CANCEL_REQUESTED,
        SUCCEEDED,
        PARTIAL,
        FAILED,
        CANCELLED,
        EXPIRED,
        QUARANTINED;

        /** @return whether no worker may later advance the job */
        public boolean terminal() {
            return switch (this) {
                case SUCCEEDED, PARTIAL, FAILED, CANCELLED, EXPIRED, QUARANTINED -> true;
                case QUEUED, RUNNING, CANCEL_REQUESTED -> false;
            };
        }
    }

    /**
     * Derived bounded item counters.
     *
     * @param totalItems exact manifest size
     * @param completedItems terminal item count
     * @param passedItems passing aggregate count
     * @param failedItems failing aggregate or exhausted-infrastructure count
     * @param indeterminateItems indeterminate aggregate count
     * @param cancelledItems unexecuted cancellation or deadline count
     */
    public record Summary(
            int totalItems,
            int completedItems,
            int passedItems,
            int failedItems,
            int indeterminateItems,
            int cancelledItems
    ) {
        /** Rejects impossible or unbounded counters. */
        public Summary {
            if (totalItems < 1
                    || totalItems > ScenarioRehearsalBatchRequest.MAXIMUM_ENTRIES
                    || completedItems < 0
                    || completedItems > totalItems
                    || passedItems < 0
                    || failedItems < 0
                    || indeterminateItems < 0
                    || cancelledItems < 0
                    || passedItems + failedItems
                    + indeterminateItems + cancelledItems
                    != completedItems) {
                throw new IllegalArgumentException(
                        "Scenario batch summary counters are inconsistent");
            }
        }
    }

    /** Validates protocol identity, lifecycle correspondence, and bounded diagnostics. */
    public ScenarioRehearsalBatchJob {
        schemaVersion = version(schemaVersion);
        jobId = identifier(jobId, "jobId");
        requestId = identifier(requestId, "requestId");
        requestFingerprint = fingerprint(
                requestFingerprint, "requestFingerprint");
        manifestFingerprint = fingerprint(
                manifestFingerprint, "manifestFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        status = Objects.requireNonNull(status, "status");
        failureMode = Objects.requireNonNull(
                failureMode, "failureMode");
        priority = Objects.requireNonNull(priority, "priority");
        if (maximumItemAttempts < 1
                || maximumItemAttempts
                > 5) {
            throw new IllegalArgumentException(
                    "maximumItemAttempts is invalid");
        }
        summary = Objects.requireNonNull(summary, "summary");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        failureCode = code(failureCode, "failureCode");
        cancellationRequestId = optionalIdentifier(
                cancellationRequestId, "cancellationRequestId");
        cancellationReasonCode = code(
                cancellationReasonCode, "cancellationReasonCode");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        recordFingerprint = optionalFingerprint(
                recordFingerprint, "recordFingerprint");
        boolean hasCancellation =
                !cancellationRequestId.isBlank()
                        && !cancellationReasonCode.isBlank();
        boolean noCancellation =
                cancellationRequestId.isBlank()
                        && cancellationReasonCode.isBlank();
        if (deadlineAt.isBefore(createdAt)
                || updatedAt.isBefore(createdAt)
                || status.terminal() != (completedAt != null)
                || completedAt != null
                && completedAt.isBefore(createdAt)
                || !(hasCancellation || noCancellation)
                || (status == Status.CANCEL_REQUESTED
                || status == Status.CANCELLED)
                && !hasCancellation
                || status.terminal()
                && summary.completedItems() != summary.totalItems()
                || !status.terminal()
                && summary.completedItems() == summary.totalItems()) {
            throw new IllegalArgumentException(
                    "Scenario rehearsal batch lifecycle is inconsistent");
        }
    }

    /** @return identical projection carrying a replacement integrity fingerprint */
    public ScenarioRehearsalBatchJob withRecordFingerprint(
            String value) {
        return new ScenarioRehearsalBatchJob(
                schemaVersion, jobId, requestId,
                requestFingerprint, manifestFingerprint,
                scope, status, failureMode, priority,
                maximumItemAttempts, summary, deadlineAt,
                failureCode, cancellationRequestId,
                cancellationReasonCode, createdAt, updatedAt,
                completedAt, value);
    }

    private static String version(String value) {
        String normalized = normalized(value);
        if (normalized.isBlank()) {
            normalized = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario batch job schemaVersion");
        }
        return normalized;
    }

    private static String identifier(String value, String field) {
        String normalized = normalized(value);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String optionalIdentifier(
            String value, String field) {
        String normalized = normalized(value);
        if (!normalized.isBlank()
                && !IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = normalized(value);
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value, String field) {
        String normalized = normalized(value);
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String code(String value, String field) {
        String normalized = normalized(value).toUpperCase(
                java.util.Locale.ROOT);
        if (!normalized.isBlank()
                && !CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
