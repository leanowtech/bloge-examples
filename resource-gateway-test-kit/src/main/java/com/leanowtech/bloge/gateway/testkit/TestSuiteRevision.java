package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Payload-safe identity projection of one immutable governed test-suite revision.
 *
 * @param suiteId stable suite identifier
 * @param revision immutable positive revision
 * @param fingerprint full suite content fingerprint
 * @param targetKind {@code GRAPH} or {@code OPERATOR}
 * @param targetId registered target identifier
 * @param targetFingerprint frozen target dependency fingerprint
 * @param caseCount number of governed cases in this revision
 * @param tenantId verified tenant scope
 * @param organizationId verified organization scope
 * @param projectId verified project scope
 * @param environmentId verified environment scope
 * @param region verified region scope
 * @param createdAt authoritative registry creation timestamp
 * @param createdBy verified registering actor
 * @param rawResponse defensive complete registry response for explicit authorized inspection
 */
public record TestSuiteRevision(
        String suiteId,
        long revision,
        String fingerprint,
        String targetKind,
        String targetId,
        String targetFingerprint,
        int caseCount,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String createdAt,
        String createdBy,
        JsonNode rawResponse
) {
    /** Normalizes scalar fields and protects the decoded response from caller mutation. */
    public TestSuiteRevision {
        suiteId = normalized(suiteId);
        fingerprint = normalized(fingerprint);
        targetKind = normalized(targetKind);
        targetId = normalized(targetId);
        targetFingerprint = normalized(targetFingerprint);
        tenantId = normalized(tenantId);
        organizationId = normalized(organizationId);
        projectId = normalized(projectId);
        environmentId = normalized(environmentId);
        region = normalized(region);
        createdAt = normalized(createdAt);
        createdBy = normalized(createdBy);
        if (suiteId.isBlank() || revision < 1 || !fingerprint(fingerprint)
                || targetKind.isBlank() || targetId.isBlank() || !fingerprint(targetFingerprint)
                || caseCount < 1) {
            throw new IllegalArgumentException("Stored test-suite identity is incomplete");
        }
        rawResponse = rawResponse == null ? null : rawResponse.deepCopy();
    }

    /**
     * Source-compatible constructor for historical v1 client projections.
     *
     * @param suiteId stable suite identifier
     * @param revision immutable suite revision
     * @param fingerprint canonical complete-suite fingerprint
     * @param targetKind graph or operator target kind
     * @param targetId registered target identifier
     * @param targetFingerprint frozen target dependency fingerprint
     * @param caseCount number of governed cases in this revision
     * @param createdAt authoritative registry creation timestamp
     * @param createdBy verified registering actor
     * @param rawResponse defensive complete registry response
     */
    public TestSuiteRevision(
            String suiteId,
            long revision,
            String fingerprint,
            String targetKind,
            String targetId,
            String targetFingerprint,
            int caseCount,
            String createdAt,
            String createdBy,
            JsonNode rawResponse) {
        this(suiteId, revision, fingerprint, targetKind, targetId,
                targetFingerprint, caseCount, "", "", "", "", "",
                createdAt, createdBy, rawResponse);
    }

    /**
     * Projects a {@code bloge.storedTestSuite.v2} response into stable identity fields.
     *
     * @param response decoded registry response
     * @return immutable suite revision projection
     */
    public static TestSuiteRevision from(JsonNode response) {
        TestingProtocolSchemaValidator.require(response, "storedTestSuite");
        JsonNode suite = response.path("suite");
        if (!response.path("suiteId").asText().equals(suite.path("suiteId").asText())
                || response.path("revision").asLong() != suite.path("revision").asLong()) {
            throw new IllegalArgumentException("Stored test-suite response identity is inconsistent");
        }
        JsonNode target = suite.path("target");
        return new TestSuiteRevision(response.path("suiteId").asText(), response.path("revision").asLong(),
                response.path("fingerprint").asText(), target.path("kind").asText(),
                target.path("id").asText(), target.path("fingerprint").asText(),
                suite.path("cases").size(), response.path("tenantId").asText(),
                response.path("organizationId").asText(), response.path("projectId").asText(),
                response.path("environmentId").asText(), response.path("region").asText(),
                response.path("createdAt").asText(),
                response.path("createdBy").asText(), response);
    }

    /**
     * Returns a log-safe exact-reference summary.
     *
     * @return suite id, revision, and fingerprint joined without suite payloads
     */
    public String exactRef() {
        return suiteId + "@" + revision + "#" + fingerprint;
    }

    /**
     * Requires this stored revision to match the exact request identity.
     *
     * @param expectedSuiteId requested or registered suite id
     * @param expectedRevision requested immutable revision
     */
    void requireIdentity(String expectedSuiteId, long expectedRevision) {
        if (!suiteId.equals(normalized(expectedSuiteId)) || revision != expectedRevision) {
            throw new IllegalArgumentException("Stored test-suite response identity does not match the request");
        }
    }

    /**
     * Returns the authorized complete response without exposing mutable state.
     *
     * @return defensive copy of the authorized complete response
     */
    @Override
    public JsonNode rawResponse() {
        return rawResponse == null ? null : rawResponse.deepCopy();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }
}
