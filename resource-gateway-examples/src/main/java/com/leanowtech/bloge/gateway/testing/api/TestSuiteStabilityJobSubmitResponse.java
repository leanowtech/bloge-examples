package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/**
 * Non-blocking queue admission result.
 *
 * @param schemaVersion exact response generation
 * @param job payload-free retained job projection
 * @param idempotentReplay whether the serialized durable command already existed
 */
public record TestSuiteStabilityJobSubmitResponse(
        String schemaVersion,
        TestSuiteStabilityJobView job,
        boolean idempotentReplay) {

    /** Current queue admission response contract. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityJobSubmitResponse.v1";

    /** Normalizes the response discriminator and requires a concrete job. */
    public TestSuiteStabilityJobSubmitResponse {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION
                : normalized(schemaVersion);
        job = Objects.requireNonNull(job, "job");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported stability-job response version");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
