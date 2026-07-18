package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Integrity-checked durable state of one asynchronous suite-stability parent job.
 *
 * @param jobId governed queue identity
 * @param request exact stability execution intent
 * @param requestFingerprint canonical request fingerprint
 * @param classification frozen suite classification
 * @param principal credential-free authenticated principal snapshot
 * @param priority immutable submitted priority
 * @param status queue lifecycle state
 * @param retryCount committed infrastructure retry count
 * @param nextEligibleAt earliest database time at which the job may be claimed
 * @param deadlineAt absolute cooperative execution deadline
 * @param createdAt database creation time
 * @param updatedAt database last-transition time
 * @param expiresAt terminal retention expiry, or deadline plus retention while active
 * @param terminalStabilityRunId completed signed analysis id, otherwise blank
 * @param terminalEvidenceFingerprint completed signed analysis fingerprint, otherwise blank
 * @param failureCode bounded terminal or retry diagnostic, otherwise blank
 * @param cancellationRequestId caller cancellation idempotency key, otherwise blank
 * @param cancellationFingerprint canonical cancellation command fingerprint, otherwise blank
 * @param recordFingerprint complete mutable-row integrity fingerprint
 */
public record TestSuiteStabilityJobRecord(
        String jobId,
        TestSuiteStabilityExecutionRequest request,
        String requestFingerprint,
        String classification,
        TestSuiteStabilityJobPrincipal principal,
        TestSuiteStabilityJobSubmission.Priority priority,
        Status status,
        int retryCount,
        Instant nextEligibleAt,
        Instant deadlineAt,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        String terminalStabilityRunId,
        String terminalEvidenceFingerprint,
        String failureCode,
        String cancellationRequestId,
        String cancellationFingerprint,
        String recordFingerprint) {

    /** Closed queue lifecycle vocabulary used by persistence, protocol, and telemetry. */
    public enum Status {
        QUEUED,
        RUNNING,
        CANCEL_REQUESTED,
        COMMITTING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        EXPIRED,
        QUARANTINED;

        /** @return whether no later worker transition is permitted */
        public boolean terminal() {
            return switch (this) {
                case SUCCEEDED, FAILED, CANCELLED, EXPIRED, QUARANTINED -> true;
                case QUEUED, RUNNING, CANCEL_REQUESTED, COMMITTING -> false;
            };
        }
    }

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Validates state-dependent terminal, cancellation, and integrity fields. */
    public TestSuiteStabilityJobRecord {
        jobId = normalized(jobId);
        request = java.util.Objects.requireNonNull(request, "request");
        requestFingerprint = normalized(requestFingerprint);
        classification = normalized(classification).toUpperCase(Locale.ROOT);
        principal = java.util.Objects.requireNonNull(principal, "principal");
        priority = java.util.Objects.requireNonNull(priority, "priority");
        status = java.util.Objects.requireNonNull(status, "status");
        nextEligibleAt = java.util.Objects.requireNonNull(nextEligibleAt, "nextEligibleAt");
        deadlineAt = java.util.Objects.requireNonNull(deadlineAt, "deadlineAt");
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = java.util.Objects.requireNonNull(updatedAt, "updatedAt");
        expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
        terminalStabilityRunId = normalized(terminalStabilityRunId);
        terminalEvidenceFingerprint = normalized(terminalEvidenceFingerprint);
        failureCode = normalized(failureCode).toUpperCase(Locale.ROOT);
        cancellationRequestId = normalized(cancellationRequestId);
        cancellationFingerprint = normalized(cancellationFingerprint);
        recordFingerprint = normalized(recordFingerprint);
        boolean succeeded = status == Status.SUCCEEDED;
        boolean completeTerminalReference = !terminalStabilityRunId.isBlank()
                && FINGERPRINT.matcher(terminalEvidenceFingerprint).matches();
        boolean noTerminalReference = terminalStabilityRunId.isBlank()
                && terminalEvidenceFingerprint.isBlank();
        boolean cancelled = status == Status.CANCEL_REQUESTED || status == Status.CANCELLED;
        if (jobId.isBlank() || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED")
                .contains(classification)
                || retryCount < 0 || deadlineAt.isBefore(createdAt)
                || updatedAt.isBefore(createdAt) || !expiresAt.isAfter(createdAt)
                || (succeeded && !completeTerminalReference)
                || (!succeeded && !noTerminalReference)
                || (!failureCode.isBlank() && !CODE.matcher(failureCode).matches())
                || cancelled != (!cancellationRequestId.isBlank()
                && FINGERPRINT.matcher(cancellationFingerprint).matches())
                || !FINGERPRINT.matcher(recordFingerprint).matches()) {
            throw new IllegalArgumentException("Invalid suite-stability job record");
        }
    }

    /** @return tenant carrying this job */
    public String tenantId() {
        return principal.tenantId();
    }

    /** @return environment carrying this job */
    public String environmentId() {
        return principal.environmentId();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
