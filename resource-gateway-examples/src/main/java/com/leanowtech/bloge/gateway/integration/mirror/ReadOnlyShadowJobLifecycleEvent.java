package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free append-only fact for one committed durable Shadow job transition.
 *
 * <p>The event deliberately contains only complete enterprise scope, job and content addresses,
 * database time, fencing coordinates, and a stable failure code. Sample payloads, normalized
 * facts, connector credentials, exception messages, and stack traces are not representable.</p>
 *
 * @param schemaVersion exact lifecycle protocol version
 * @param sequence database-assigned append sequence
 * @param occurredAt database-authoritative transition time
 * @param scope complete enterprise namespace
 * @param jobId deterministic durable job identity
 * @param requestFingerprint immutable request content address
 * @param transition committed lifecycle transition
 * @param status resulting public job status
 * @param attemptCount resulting physical-attempt count
 * @param leaseEpoch resulting monotonic fencing epoch
 * @param ownerFingerprint content address of the opaque worker owner, otherwise blank
 * @param nextEligibleAt resulting retry eligibility time
 * @param leaseExpiresAt resulting lease expiry
 * @param comparisonFingerprint terminal signed comparison content address, otherwise blank
 * @param failureCode stable bounded failure code, otherwise blank
 * @param recordFingerprint resulting public job-record content address
 */
public record ReadOnlyShadowJobLifecycleEvent(
        String schemaVersion,
        long sequence,
        Instant occurredAt,
        CapabilitySnapshot.Scope scope,
        String jobId,
        String requestFingerprint,
        Transition transition,
        ReadOnlyShadowJob.Status status,
        int attemptCount,
        long leaseEpoch,
        String ownerFingerprint,
        Instant nextEligibleAt,
        Instant leaseExpiresAt,
        String comparisonFingerprint,
        String failureCode,
        String recordFingerprint
) {
    /** Current payload-free lifecycle audit protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowJobLifecycleEvent.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern FAILURE_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");

    /** Closed durable transition vocabulary. */
    public enum Transition {
        /** A new immutable request and sampling ordinal were admitted. */
        ADMITTED,
        /** A queued job acquired its first or next non-stale worker lease. */
        CLAIMED,
        /** An expired running lease was fenced and replaced by a new owner epoch. */
        TAKEN_OVER,
        /** The current owner cooperatively renewed its unchanged epoch. */
        LEASE_RENEWED,
        /** A retryable failure released the lease and scheduled another bounded attempt. */
        RETRY_SCHEDULED,
        /** A signed v2 comparison and terminal success committed atomically. */
        SUCCEEDED,
        /** A non-retryable or exhausted attempt committed terminal failure. */
        FAILED,
        /** The database deadline prevented another complete attempt. */
        EXPIRED
    }

    /** Enforces closed transition shapes and canonical payload-free coordinates. */
    public ReadOnlyShadowJobLifecycleEvent {
        if (!SCHEMA_VERSION.equals(normalized(schemaVersion))) {
            throw new IllegalArgumentException(
                    "unsupported read-only Shadow lifecycle schemaVersion");
        }
        schemaVersion = SCHEMA_VERSION;
        if (sequence < 1) {
            throw new IllegalArgumentException(
                    "read-only Shadow lifecycle sequence must be positive");
        }
        occurredAt = Objects.requireNonNull(
                occurredAt, "occurredAt");
        scope = Objects.requireNonNull(scope, "scope");
        jobId = identifier(jobId, "jobId");
        requestFingerprint = fingerprint(
                requestFingerprint,
                false,
                "requestFingerprint");
        transition = Objects.requireNonNull(
                transition, "transition");
        status = Objects.requireNonNull(status, "status");
        if (attemptCount < 0 || attemptCount > 5
                || leaseEpoch < 0) {
            throw new IllegalArgumentException(
                    "read-only Shadow lifecycle counters are invalid");
        }
        ownerFingerprint = fingerprint(
                ownerFingerprint,
                true,
                "ownerFingerprint");
        nextEligibleAt = Objects.requireNonNull(
                nextEligibleAt, "nextEligibleAt");
        leaseExpiresAt = Objects.requireNonNull(
                leaseExpiresAt, "leaseExpiresAt");
        comparisonFingerprint = fingerprint(
                comparisonFingerprint,
                true,
                "comparisonFingerprint");
        failureCode = normalized(failureCode);
        if (!failureCode.isBlank()
                && !FAILURE_CODE.matcher(
                failureCode).matches()) {
            throw new IllegalArgumentException(
                    "read-only Shadow lifecycle failureCode is invalid");
        }
        recordFingerprint = fingerprint(
                recordFingerprint,
                false,
                "recordFingerprint");
        requireShape(
                transition,
                status,
                attemptCount,
                leaseEpoch,
                ownerFingerprint,
                comparisonFingerprint,
                failureCode);
    }

    private static void requireShape(
            Transition transition,
            ReadOnlyShadowJob.Status status,
            int attemptCount,
            long leaseEpoch,
            String ownerFingerprint,
            String comparisonFingerprint,
            String failureCode) {
        switch (transition) {
            case ADMITTED -> {
                if (status != ReadOnlyShadowJob.Status.QUEUED
                        || attemptCount != 0
                        || leaseEpoch != 0
                        || !ownerFingerprint.isBlank()
                        || !comparisonFingerprint.isBlank()
                        || !failureCode.isBlank()) {
                    invalidShape();
                }
            }
            case CLAIMED, TAKEN_OVER, LEASE_RENEWED -> {
                if (status != ReadOnlyShadowJob.Status.RUNNING
                        || attemptCount < 1
                        || leaseEpoch < 1
                        || ownerFingerprint.isBlank()
                        || !comparisonFingerprint.isBlank()
                        || !failureCode.isBlank()) {
                    invalidShape();
                }
            }
            case RETRY_SCHEDULED -> {
                if (status != ReadOnlyShadowJob.Status.QUEUED
                        || attemptCount < 1
                        || leaseEpoch < 1
                        || ownerFingerprint.isBlank()
                        || !comparisonFingerprint.isBlank()
                        || failureCode.isBlank()) {
                    invalidShape();
                }
            }
            case SUCCEEDED -> {
                if (status != ReadOnlyShadowJob.Status.SUCCEEDED
                        || attemptCount < 1
                        || leaseEpoch < 1
                        || ownerFingerprint.isBlank()
                        || comparisonFingerprint.isBlank()
                        || !failureCode.isBlank()) {
                    invalidShape();
                }
            }
            case FAILED -> {
                if (status != ReadOnlyShadowJob.Status.FAILED
                        || attemptCount > 0
                        && ownerFingerprint.isBlank()
                        || !comparisonFingerprint.isBlank()
                        || failureCode.isBlank()) {
                    invalidShape();
                }
            }
            case EXPIRED -> {
                if (status != ReadOnlyShadowJob.Status.EXPIRED
                        || !comparisonFingerprint.isBlank()
                        || failureCode.isBlank()) {
                    invalidShape();
                }
            }
        }
    }

    private static void invalidShape() {
        throw new IllegalArgumentException(
                "read-only Shadow lifecycle transition shape is invalid");
    }

    private static String identifier(
            String value,
            String field) {
        String normalized = normalized(value);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String fingerprint(
            String value,
            boolean blankAllowed,
            String field) {
        String normalized = normalized(value);
        if (blankAllowed && normalized.isBlank()) {
            return "";
        }
        if (!FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
