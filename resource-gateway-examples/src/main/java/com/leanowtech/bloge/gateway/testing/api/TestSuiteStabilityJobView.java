package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free public lifecycle projection of one integrity-verified stability job.
 *
 * <p>The projection intentionally excludes the authenticated principal, suite metadata, lease
 * owner and epoch, cancellation fingerprint, queue policy generation, and row-integrity seal.</p>
 *
 * @param schemaVersion exact public projection generation
 * @param jobId deterministic queue identity
 * @param suiteRef immutable suite revision selected by the caller
 * @param clientRequestId caller-owned submission idempotency identity
 * @param requestFingerprint canonical execution-intent fingerprint
 * @param priority immutable submitted priority
 * @param status closed queue lifecycle state
 * @param retryCount committed infrastructure retry count
 * @param nextEligibleAt earliest database time at which queued work may run
 * @param deadlineAt absolute cooperative execution deadline
 * @param createdAt database creation time
 * @param updatedAt database last-transition time
 * @param expiresAt retained detail expiry
 * @param terminal whether the state permits no later worker transition
 * @param stabilityRunId signed terminal parent identity, only after success
 * @param evidenceFingerprint signed terminal evidence identity, only after success
 * @param failureCode bounded payload-free diagnostic, when present
 */
public record TestSuiteStabilityJobView(
        String schemaVersion,
        String jobId,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        String clientRequestId,
        String requestFingerprint,
        TestSuiteStabilityJobSubmission.Priority priority,
        TestSuiteStabilityJobRecord.Status status,
        int retryCount,
        Instant nextEligibleAt,
        Instant deadlineAt,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        boolean terminal,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String stabilityRunId,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String evidenceFingerprint,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        String failureCode) {

    /** Current payload-free job projection contract. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityJobView.v1";

    private static final Pattern JOB_ID = Pattern.compile("stability-job-[a-f0-9]{64}");
    private static final Pattern STABILITY_RUN_ID = Pattern.compile("stability-[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Verifies all state-dependent fields before a projection may cross the HTTP boundary. */
    public TestSuiteStabilityJobView {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION
                : normalized(schemaVersion);
        jobId = normalized(jobId);
        suiteRef = Objects.requireNonNull(suiteRef, "suiteRef");
        clientRequestId = normalized(clientRequestId);
        requestFingerprint = normalized(requestFingerprint);
        priority = Objects.requireNonNull(priority, "priority");
        status = Objects.requireNonNull(status, "status");
        nextEligibleAt = Objects.requireNonNull(nextEligibleAt, "nextEligibleAt");
        deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        stabilityRunId = normalized(stabilityRunId);
        evidenceFingerprint = normalized(evidenceFingerprint);
        failureCode = normalized(failureCode);
        boolean succeeded = status == TestSuiteStabilityJobRecord.Status.SUCCEEDED;
        boolean hasTerminalReference = STABILITY_RUN_ID.matcher(stabilityRunId).matches()
                && FINGERPRINT.matcher(evidenceFingerprint).matches();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !JOB_ID.matcher(jobId).matches()
                || suiteRef.suiteId().isBlank() || suiteRef.revision() <= 0
                || !FINGERPRINT.matcher(suiteRef.fingerprint()).matches()
                || !IDENTIFIER.matcher(clientRequestId).matches()
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || retryCount < 0
                || deadlineAt.isBefore(createdAt)
                || updatedAt.isBefore(createdAt)
                || !expiresAt.isAfter(createdAt)
                || terminal != status.terminal()
                || succeeded != hasTerminalReference
                || !succeeded && (!stabilityRunId.isBlank()
                || !evidenceFingerprint.isBlank())
                || !failureCode.isBlank() && !CODE.matcher(failureCode).matches()) {
            throw new IllegalArgumentException("Invalid suite-stability job projection");
        }
    }

    /**
     * Builds the only public projection from an integrity-verified durable row.
     *
     * @param job retained durable job
     * @return payload-free lifecycle view
     */
    public static TestSuiteStabilityJobView from(TestSuiteStabilityJobRecord job) {
        TestSuiteStabilityJobRecord source = Objects.requireNonNull(job, "job");
        return new TestSuiteStabilityJobView("", source.jobId(), source.request().suiteRef(),
                source.request().clientRequestId(), source.requestFingerprint(),
                source.priority(), source.status(), source.retryCount(), source.nextEligibleAt(),
                source.deadlineAt(), source.createdAt(), source.updatedAt(), source.expiresAt(),
                source.status().terminal(), source.terminalStabilityRunId(),
                source.terminalEvidenceFingerprint(), source.failureCode());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
