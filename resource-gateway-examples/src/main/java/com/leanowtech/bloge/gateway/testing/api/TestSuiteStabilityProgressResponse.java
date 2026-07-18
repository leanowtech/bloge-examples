package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityStatisticalPolicy;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Payload-free public projection of one active, recoverable, or completed stability parent.
 *
 * <p>The projection intentionally omits owner ids, lease epochs, source run ids, fixture data,
 * business payloads, and evidence bodies. Terminal evidence remains available from the dedicated
 * stability execution resource.</p>
 *
 * @param schemaVersion exact progress protocol version
 * @param stabilityRunId deterministic parent identity
 * @param status current database-observed lifecycle
 * @param suiteRef exact immutable suite revision
 * @param plannedAttempts precommitted horizon
 * @param completedAttempts durably checkpointed source attempts
 * @param createdAt parent start time
 * @param updatedAt latest durable progress or terminal time
 */
public record TestSuiteStabilityProgressResponse(
        String schemaVersion,
        String stabilityRunId,
        Status status,
        TestSuiteExecutionRequest.SuiteRef suiteRef,
        int plannedAttempts,
        int completedAttempts,
        Instant createdAt,
        Instant updatedAt
) {
    /** Current payload-free progress protocol. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityProgress.v1";
    private static final Pattern STABILITY_RUN_ID = Pattern.compile("stability-[a-f0-9]{64}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Public lifecycle deliberately separates a live owner from takeover-ready progress. */
    public enum Status {
        /** One database-clock-live owner may schedule the next attempt. */
        RUNNING,
        /** Durable progress exists without a live owner and may be claimed by an exact retry. */
        RECOVERABLE,
        /** Signed terminal stability evidence exists. */
        COMPLETED
    }

    /** Enforces a bounded self-consistent public projection. */
    public TestSuiteStabilityProgressResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        stabilityRunId = normalized(stabilityRunId);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !STABILITY_RUN_ID.matcher(stabilityRunId).matches()
                || status == null || suiteRef == null || suiteRef.suiteId().isBlank()
                || suiteRef.revision() < 1
                || !FINGERPRINT.matcher(normalized(suiteRef.fingerprint())).matches()
                || plannedAttempts < TestSuiteStabilityEvidence.MIN_ATTEMPTS
                || plannedAttempts > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS
                || completedAttempts < 0 || completedAttempts > plannedAttempts
                || status == Status.COMPLETED && completedAttempts != plannedAttempts
                || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "A complete bounded suite-stability progress response is required");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
