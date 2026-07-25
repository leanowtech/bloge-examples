package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free public projection of one durable read-only Shadow job.
 *
 * <p>The projection exposes durable lifecycle and sample-budget coordinates, but never carries
 * request/response payloads, source values, credentials, worker identity, or exception text.
 * {@code recordFingerprint} covers every public field so duplicated database indexes cannot
 * silently change scheduling or evidence interpretation.</p>
 *
 * @param schemaVersion exact durable-job projection version
 * @param jobId server-derived content-stable job identity
 * @param requestId caller idempotency identity
 * @param requestFingerprint canonical immutable request fingerprint
 * @param scope complete enterprise namespace
 * @param status durable lifecycle state
 * @param attemptCount physical worker attempts already claimed
 * @param maximumAttempts immutable server-owned retry bound
 * @param nextEligibleAt database-clock claim time
 * @param deadlineAt absolute database-clock execution deadline
 * @param leaseEpoch monotonic fencing epoch, zero before the first claim
 * @param leaseExpiresAt current or latest lease expiry
 * @param comparisonRef exact signed comparison after success
 * @param failureCode bounded machine-readable diagnostic
 * @param createdAt database admission time
 * @param updatedAt latest durable transition time
 * @param completedAt terminal transition time, otherwise {@code null}
 * @param recordFingerprint canonical mutable-projection fingerprint
 */
public record ReadOnlyShadowJob(
        String schemaVersion,
        String jobId,
        String requestId,
        String requestFingerprint,
        CapabilitySnapshot.Scope scope,
        Status status,
        int attemptCount,
        int maximumAttempts,
        Instant nextEligibleAt,
        Instant deadlineAt,
        long leaseEpoch,
        Instant leaseExpiresAt,
        MirrorArtifactRef comparisonRef,
        String failureCode,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt,
        String recordFingerprint
) {
    /** Current durable Shadow job projection protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowJob.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Closed durable lifecycle vocabulary. */
    public enum Status {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        EXPIRED;

        /** @return whether no later worker may advance the job */
        public boolean terminal() {
            return switch (this) {
                case SUCCEEDED, FAILED, EXPIRED -> true;
                case QUEUED, RUNNING -> false;
            };
        }
    }

    /** Validates lifecycle, lease, evidence, and bounded diagnostic correspondence. */
    public ReadOnlyShadowJob {
        schemaVersion = version(schemaVersion);
        jobId = identifier(jobId, "jobId");
        requestId = identifier(requestId, "requestId");
        requestFingerprint = fingerprint(
                requestFingerprint, "requestFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        status = Objects.requireNonNull(status, "status");
        nextEligibleAt = Objects.requireNonNull(
                nextEligibleAt, "nextEligibleAt");
        deadlineAt = Objects.requireNonNull(
                deadlineAt, "deadlineAt");
        leaseExpiresAt = Objects.requireNonNull(
                leaseExpiresAt, "leaseExpiresAt");
        failureCode = code(failureCode);
        createdAt = Objects.requireNonNull(
                createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(
                updatedAt, "updatedAt");
        recordFingerprint = optionalFingerprint(
                recordFingerprint);
        if (attemptCount < 0
                || maximumAttempts < 1
                || maximumAttempts > 5
                || attemptCount > maximumAttempts
                || leaseEpoch < 0
                || nextEligibleAt.isBefore(createdAt)
                || nextEligibleAt.isAfter(deadlineAt)
                || deadlineAt.isBefore(createdAt)
                || leaseExpiresAt.isBefore(createdAt)
                || leaseExpiresAt.isAfter(deadlineAt)
                || updatedAt.isBefore(createdAt)
                || status.terminal() != (completedAt != null)
                || completedAt != null
                && completedAt.isBefore(createdAt)
                || status == Status.QUEUED
                && attemptCount >= maximumAttempts
                || status == Status.RUNNING
                && (attemptCount < 1
                || leaseEpoch < 1
                || !leaseExpiresAt.isAfter(updatedAt))
                || status == Status.SUCCEEDED
                && (comparisonRef == null
                || !ReadOnlyShadowComparison.ARTIFACT_KIND.equals(
                comparisonRef.kind())
                || !failureCode.isBlank())
                || status != Status.SUCCEEDED
                && comparisonRef != null
                || (status == Status.FAILED
                || status == Status.EXPIRED)
                && failureCode.isBlank()
                || !status.terminal()
                && completedAt != null) {
            throw new IllegalArgumentException(
                    "read-only Shadow job lifecycle is inconsistent");
        }
    }

    /** @return identical projection carrying a replacement integrity fingerprint */
    public ReadOnlyShadowJob withRecordFingerprint(
            String value) {
        return new ReadOnlyShadowJob(
                schemaVersion,
                jobId,
                requestId,
                requestFingerprint,
                scope,
                status,
                attemptCount,
                maximumAttempts,
                nextEligibleAt,
                deadlineAt,
                leaseEpoch,
                leaseExpiresAt,
                comparisonRef,
                failureCode,
                createdAt,
                updatedAt,
                completedAt,
                value);
    }

    /** Omits scope, worker fencing coordinates, and evidence identifiers from generic logs. */
    @Override
    public String toString() {
        return "ReadOnlyShadowJob[jobId=" + jobId
                + ", status=" + status
                + ", attemptCount=" + attemptCount + "]";
    }

    private static String version(String value) {
        String normalized = value == null
                ? "" : value.trim();
        if (normalized.isEmpty()) {
            normalized = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported read-only Shadow job schemaVersion");
        }
        return normalized;
    }

    private static String identifier(
            String value,
            String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String fingerprint(
            String value,
            String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String optionalFingerprint(
            String value) {
        String normalized = value == null
                ? "" : value.trim();
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "recordFingerprint must be blank or canonical SHA-256");
        }
        return normalized;
    }

    private static String code(String value) {
        String normalized = value == null
                ? "" : value.trim().toUpperCase(
                java.util.Locale.ROOT);
        if (!normalized.isBlank()
                && !CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "failureCode is invalid");
        }
        return normalized;
    }
}
