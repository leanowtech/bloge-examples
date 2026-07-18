package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;

/**
 * Versioned non-blocking submission command for one durable suite-stability parent job.
 *
 * @param schemaVersion exact public command generation
 * @param execution exact existing suite-stability execution intent
 * @param priority immutable queue priority before bounded aging
 * @param deadlineAt whole-second absolute cooperative execution deadline
 */
public record TestSuiteStabilityJobSubmitRequest(
        String schemaVersion,
        TestSuiteStabilityExecutionRequest execution,
        TestSuiteStabilityJobSubmission.Priority priority,
        Instant deadlineAt) {

    /** Current asynchronous submission contract. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityJobSubmitRequest.v1";

    /** Normalizes only the discriminator; the application service owns semantic validation. */
    public TestSuiteStabilityJobSubmitRequest {
        schemaVersion = normalized(schemaVersion);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
