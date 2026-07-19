package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityStatisticalPolicy;

import java.time.Instant;
import java.util.List;
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
 * @param terminalReason v2 terminal boundary; absent while active and in historical v1
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
        @JsonInclude(JsonInclude.Include.NON_NULL)
        TestSuiteStabilityEvidence.StatisticalStopReason terminalReason,
        Instant createdAt,
        Instant updatedAt
) {
    /** Historical progress protocol requiring every terminal to fill its planned horizon. */
    public static final String SCHEMA_VERSION_V1 = "bloge.testSuiteStabilityProgress.v1";
    /** Current progress protocol exposing fixed or sequential terminal reasons. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityProgress.v2";
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

    /**
     * Backward source-compatible constructor for active and fixed-horizon projections.
     *
     * <p>An explicit v1 value preserves the historical shape. Blank/current completed values are
     * emitted as v2 fixed-horizon terminals.</p>
     */
    public TestSuiteStabilityProgressResponse(
            String schemaVersion,
            String stabilityRunId,
            Status status,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            int plannedAttempts,
            int completedAttempts,
            Instant createdAt,
            Instant updatedAt) {
        this(schemaVersion, stabilityRunId, status, suiteRef, plannedAttempts, completedAttempts,
                status == Status.COMPLETED
                        && !SCHEMA_VERSION_V1.equals(normalized(schemaVersion))
                        ? TestSuiteStabilityEvidence.StatisticalStopReason.FIXED_HORIZON_REACHED
                        : null,
                createdAt, updatedAt);
    }

    /** Enforces a bounded self-consistent public projection. */
    public TestSuiteStabilityProgressResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        stabilityRunId = normalized(stabilityRunId);
        boolean historical = SCHEMA_VERSION_V1.equals(schemaVersion);
        boolean terminalCoordinatesValid = status != Status.COMPLETED
                ? terminalReason == null
                : historical
                ? terminalReason == null && completedAttempts == plannedAttempts
                : validV2Terminal(terminalReason, completedAttempts, plannedAttempts);
        if (!List.of(SCHEMA_VERSION_V1, SCHEMA_VERSION).contains(schemaVersion)
                || !STABILITY_RUN_ID.matcher(stabilityRunId).matches()
                || status == null || suiteRef == null || suiteRef.suiteId().isBlank()
                || suiteRef.revision() < 1
                || !FINGERPRINT.matcher(normalized(suiteRef.fingerprint())).matches()
                || plannedAttempts < TestSuiteStabilityEvidence.MIN_ATTEMPTS
                || plannedAttempts > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS
                || completedAttempts < 0 || completedAttempts > plannedAttempts
                || !terminalCoordinatesValid
                || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "A complete bounded suite-stability progress response is required");
        }
    }

    private static boolean validV2Terminal(
            TestSuiteStabilityEvidence.StatisticalStopReason terminalReason,
            int completedAttempts,
            int plannedAttempts) {
        if (terminalReason == null) {
            return false;
        }
        return switch (terminalReason) {
            case FIXED_HORIZON_REACHED, MAXIMUM_HORIZON_REACHED ->
                    completedAttempts == plannedAttempts;
            case E_VALUE_THRESHOLD_REACHED, CENSORING_OBSERVED ->
                    completedAttempts >= 1 && completedAttempts <= plannedAttempts;
        };
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
