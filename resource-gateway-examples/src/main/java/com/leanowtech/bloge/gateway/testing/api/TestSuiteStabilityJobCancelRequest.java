package com.leanowtech.bloge.gateway.testing.api;

/**
 * Versioned idempotent cancellation command for one durable suite-stability job.
 *
 * @param schemaVersion exact public command generation
 * @param clientRequestId caller-stable cancellation idempotency identity
 */
public record TestSuiteStabilityJobCancelRequest(
        String schemaVersion,
        String clientRequestId) {

    /** Current cancellation command contract. */
    public static final String SCHEMA_VERSION = "bloge.testSuiteStabilityJobCancelRequest.v1";

    /** Normalizes command strings without manufacturing a missing protocol version. */
    public TestSuiteStabilityJobCancelRequest {
        schemaVersion = normalized(schemaVersion);
        clientRequestId = normalized(clientRequestId);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
