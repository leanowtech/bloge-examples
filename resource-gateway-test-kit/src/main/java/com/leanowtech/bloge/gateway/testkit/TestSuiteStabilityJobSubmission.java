package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Typed durable admission result for one asynchronous suite-stability request.
 *
 * @param schemaVersion exact response generation
 * @param job payload-free retained lifecycle
 * @param idempotentReplay whether the same durable command was already retained
 * @param rawResponse defensive complete response
 */
public record TestSuiteStabilityJobSubmission(
        String schemaVersion,
        TestSuiteStabilityJob job,
        boolean idempotentReplay,
        JsonNode rawResponse) {

    /** Validates semantic response completeness. */
    public TestSuiteStabilityJobSubmission {
        schemaVersion = normalized(schemaVersion);
        if (!TestingProtocol.TEST_SUITE_STABILITY_JOB_SUBMIT_RESPONSE_V1.equals(schemaVersion)
                || job == null) {
            throw new IllegalArgumentException(
                    "Suite-stability job submission response is incomplete");
        }
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Validates and projects one authoritative admission response.
     *
     * @param response decoded response
     * @return immutable typed submission result
     */
    public static TestSuiteStabilityJobSubmission from(JsonNode response) {
        TestingProtocolSchemaValidator.require(
                response, "testSuiteStabilityJobSubmitResponse");
        try {
            return new TestSuiteStabilityJobSubmission(
                    response.path("schemaVersion").asText(),
                    TestSuiteStabilityJob.from(response.path("job")),
                    response.path("idempotentReplay").asBoolean(), response);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Suite-stability job submission response is invalid", invalid);
        }
    }

    /** Requires the retained job to match the exact caller intent. */
    void requireSubmission(TestSuiteStabilityJobRequest request) {
        job.requireSubmission(request);
    }

    /**
     * Returns the complete authorized payload-free response.
     *
     * @return defensive JSON copy
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
