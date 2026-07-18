package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Typed payload-free projection of one durable suite-stability parent execution.
 *
 * <p>This operational view deliberately contains no lease owner, epoch, source suite-run
 * identity, fixture, credential, or business payload. A terminal {@link TestSuiteStabilityRun}
 * remains the only release-evidence representation.</p>
 *
 * @param schemaVersion exact progress wire version
 * @param stabilityRunId deterministic parent identity
 * @param status database-observed lifecycle
 * @param suiteRef exact immutable suite revision
 * @param plannedAttempts precommitted horizon
 * @param completedAttempts durably checkpointed prefix length
 * @param createdAt parent creation time
 * @param updatedAt latest durable progress or terminal time
 * @param rawResponse defensive complete public projection
 */
public record TestSuiteStabilityProgress(
        String schemaVersion,
        String stabilityRunId,
        Status status,
        TestSuiteStabilityAttestation.SuiteRef suiteRef,
        int plannedAttempts,
        int completedAttempts,
        Instant createdAt,
        Instant updatedAt,
        JsonNode rawResponse
) {
    /** Public parent lifecycle without internal ownership coordinates. */
    public enum Status {
        /** A database-clock-live owner may schedule or publish. */
        RUNNING,
        /** The exact request may take over and resume the durable prefix. */
        RECOVERABLE,
        /** Signed terminal stability evidence exists. */
        COMPLETED
    }

    /** Validates semantic relationships beyond the structural JSON Schema. */
    public TestSuiteStabilityProgress {
        schemaVersion = normalized(schemaVersion);
        stabilityRunId = normalized(stabilityRunId);
        if (!TestingProtocol.TEST_SUITE_STABILITY_PROGRESS_V1.equals(schemaVersion)
                || !stabilityRunId.matches("stability-[0-9a-f]{64}")
                || status == null || suiteRef == null
                || plannedAttempts < TestSuiteStabilityStatisticalPolicy.MIN_ATTEMPTS
                || plannedAttempts > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS
                || completedAttempts < 0 || completedAttempts > plannedAttempts
                || status == Status.COMPLETED && completedAttempts != plannedAttempts
                || createdAt == null || updatedAt == null || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "Suite-stability durable progress is incomplete or contradictory");
        }
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Validates and projects one authoritative progress response.
     *
     * @param response decoded response
     * @return immutable typed projection
     */
    public static TestSuiteStabilityProgress from(JsonNode response) {
        TestingProtocolSchemaValidator.require(response, "testSuiteStabilityProgress");
        JsonNode suite = response.path("suiteRef");
        try {
            return new TestSuiteStabilityProgress(
                    response.path("schemaVersion").asText(),
                    response.path("stabilityRunId").asText(),
                    Status.valueOf(response.path("status").asText()),
                    new TestSuiteStabilityAttestation.SuiteRef(
                            suite.path("suiteId").asText(), suite.path("revision").asLong(),
                            suite.path("fingerprint").asText()),
                    response.path("plannedAttempts").asInt(),
                    response.path("completedAttempts").asInt(),
                    Instant.parse(response.path("createdAt").asText()),
                    Instant.parse(response.path("updatedAt").asText()), response);
        } catch (DateTimeParseException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Suite-stability durable progress is invalid", invalid);
        }
    }

    /** Requires the response identity to match the requested resource. */
    void requireRunIdentity(String expectedRunId) {
        if (!stabilityRunId.equals(normalized(expectedRunId))) {
            throw new IllegalArgumentException(
                    "Suite-stability progress identity does not match the request");
        }
    }

    /**
     * Returns the complete authorized projection without exposing mutable internal state.
     *
     * @return defensive copy of the authorized projection
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
