package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Strict payload-free lifecycle projection of one asynchronous suite-stability job.
 *
 * <p>The projection deliberately contains no authenticated principal, authority groups, fixture,
 * business input/output, lease, queue-policy generation, cancellation fingerprint, or row seal.
 * A successful terminal reference identifies signed stability evidence but does not itself verify
 * that evidence.</p>
 *
 * @param schemaVersion exact public job-view generation
 * @param jobId deterministic durable job identity
 * @param suiteRef exact immutable suite reference
 * @param clientRequestId caller-owned submission identity
 * @param requestFingerprint canonical nested execution fingerprint
 * @param priority immutable admitted priority
 * @param status closed durable lifecycle state
 * @param retryCount committed infrastructure retry count
 * @param nextEligibleAt earliest database time at which queued work may run
 * @param deadlineAt absolute cooperative deadline
 * @param createdAt durable creation time
 * @param updatedAt latest durable transition time
 * @param expiresAt detail-retention expiry
 * @param terminal whether no later worker transition is permitted
 * @param stabilityRunId signed terminal parent identity, only after success
 * @param evidenceFingerprint signed terminal evidence identity, only after success
 * @param failureCode bounded payload-free diagnostic, when present
 * @param rawResponse defensive complete public projection
 */
public record TestSuiteStabilityJob(
        String schemaVersion,
        String jobId,
        TestSuiteStabilityAttestation.SuiteRef suiteRef,
        String clientRequestId,
        String requestFingerprint,
        TestSuiteStabilityJobRequest.Priority priority,
        Status status,
        int retryCount,
        Instant nextEligibleAt,
        Instant deadlineAt,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        boolean terminal,
        String stabilityRunId,
        String evidenceFingerprint,
        String failureCode,
        JsonNode rawResponse) {

    /** Public durable queue lifecycle. */
    public enum Status {
        /** Accepted work waiting for a worker. */
        QUEUED(false),
        /** A fenced worker owns the active attempt. */
        RUNNING(false),
        /** Cooperative cancellation has been requested. */
        CANCEL_REQUESTED(false),
        /** Terminal publication has crossed its linearization point. */
        COMMITTING(false),
        /** Signed terminal stability evidence was published. */
        SUCCEEDED(true),
        /** Execution ended with a bounded failure. */
        FAILED(true),
        /** Cancellation reached a terminal state. */
        CANCELLED(true),
        /** The deadline elapsed before terminal publication. */
        EXPIRED(true),
        /** Infrastructure policy removed the job from ordinary scheduling. */
        QUARANTINED(true);

        private final boolean terminal;

        Status(boolean terminal) {
            this.terminal = terminal;
        }

        /**
         * Reports whether this state permits no later worker transition.
         *
         * @return whether this state is terminal
         */
        public boolean terminal() {
            return terminal;
        }
    }

    /** Validates semantic relationships beyond structural Schema validation. */
    public TestSuiteStabilityJob {
        schemaVersion = normalized(schemaVersion);
        jobId = normalized(jobId);
        clientRequestId = normalized(clientRequestId);
        requestFingerprint = normalized(requestFingerprint);
        stabilityRunId = normalized(stabilityRunId);
        evidenceFingerprint = normalized(evidenceFingerprint);
        failureCode = normalized(failureCode);
        boolean successReferences = stabilityRunId.matches("stability-[0-9a-f]{64}")
                && fingerprint(evidenceFingerprint);
        if (!TestingProtocol.TEST_SUITE_STABILITY_JOB_VIEW_V1.equals(schemaVersion)
                || !jobId.matches("stability-job-[0-9a-f]{64}")
                || suiteRef == null
                || !clientRequestId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                || !fingerprint(requestFingerprint)
                || priority == null || status == null || retryCount < 0
                || nextEligibleAt == null || deadlineAt == null || createdAt == null
                || updatedAt == null || expiresAt == null
                || deadlineAt.isBefore(createdAt) || updatedAt.isBefore(createdAt)
                || !expiresAt.isAfter(createdAt)
                || terminal != status.terminal()
                || (status == Status.SUCCEEDED) != successReferences
                || status != Status.SUCCEEDED
                && (!stabilityRunId.isBlank() || !evidenceFingerprint.isBlank())) {
            throw new IllegalArgumentException(
                    "Suite-stability job projection is incomplete or contradictory");
        }
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Validates and projects one authoritative job response.
     *
     * @param response decoded payload-free response
     * @return immutable typed job
     */
    public static TestSuiteStabilityJob from(JsonNode response) {
        TestingProtocolSchemaValidator.require(response, "testSuiteStabilityJobView");
        JsonNode suite = response.path("suiteRef");
        try {
            return new TestSuiteStabilityJob(
                    response.path("schemaVersion").asText(),
                    response.path("jobId").asText(),
                    new TestSuiteStabilityAttestation.SuiteRef(
                            suite.path("suiteId").asText(), suite.path("revision").asLong(),
                            suite.path("fingerprint").asText()),
                    response.path("clientRequestId").asText(),
                    response.path("requestFingerprint").asText(),
                    TestSuiteStabilityJobRequest.Priority.valueOf(
                            response.path("priority").asText()),
                    Status.valueOf(response.path("status").asText()),
                    response.path("retryCount").asInt(),
                    Instant.parse(response.path("nextEligibleAt").asText()),
                    Instant.parse(response.path("deadlineAt").asText()),
                    Instant.parse(response.path("createdAt").asText()),
                    Instant.parse(response.path("updatedAt").asText()),
                    Instant.parse(response.path("expiresAt").asText()),
                    response.path("terminal").asBoolean(),
                    response.path("stabilityRunId").asText(),
                    response.path("evidenceFingerprint").asText(),
                    response.path("failureCode").asText(), response);
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Suite-stability job response is invalid", invalid);
        }
    }

    /** Requires the projection to identify the requested durable job. */
    void requireJobIdentity(String expectedJobId) {
        if (!jobId.equals(normalized(expectedJobId))) {
            throw new IllegalArgumentException(
                    "Suite-stability job identity does not match the request");
        }
    }

    /** Requires immutable admission fields to match the exact submitted request. */
    void requireSubmission(TestSuiteStabilityJobRequest request) {
        TestSuiteStabilityJobRequest expected = java.util.Objects.requireNonNull(
                request, "request");
        if (!suiteRef.suiteId().equals(expected.suiteId())
                || suiteRef.revision() != expected.revision()
                || !suiteRef.fingerprint().equals(expected.fingerprint())
                || !clientRequestId.equals(expected.clientRequestId())
                || !requestFingerprint.equals(expected.executionFingerprint())
                || priority != expected.priority()
                || !deadlineAt.equals(expected.deadlineAt())) {
            throw new IllegalArgumentException(
                    "Suite-stability job does not match the submitted intent");
        }
    }

    /**
     * Returns the complete authorized payload-free projection.
     *
     * @return defensive JSON copy
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
