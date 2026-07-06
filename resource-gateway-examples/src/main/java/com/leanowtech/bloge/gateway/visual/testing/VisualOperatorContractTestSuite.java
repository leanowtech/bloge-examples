package com.leanowtech.bloge.gateway.visual.testing;

import java.util.List;

/**
 * Stored operator contract-test suite asset.
 *
 * @param schemaVersion suite schema version
 * @param suiteId stable suite id
 * @param displayName human-readable suite name
 * @param description suite description
 * @param tags suite tags for CI/report grouping
 * @param request table suite request
 */
public record VisualOperatorContractTestSuite(
        String schemaVersion,
        String suiteId,
        String displayName,
        String description,
        List<String> tags,
        VisualOperatorContractTestSuiteRequest request
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorContractTestSuite.v1";

    /**
     * Creates a stored suite.
     */
    public VisualOperatorContractTestSuite {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        suiteId = suiteId == null ? "" : suiteId.trim();
        displayName = displayName == null || displayName.isBlank() ? suiteId : displayName.trim();
        description = description == null ? "" : description;
        tags = tags == null ? List.of() : List.copyOf(tags);
        request = request == null ? new VisualOperatorContractTestSuiteRequest("", List.of()) : request;
    }

    /**
     * Convenience constructor for current-version suites.
     */
    public VisualOperatorContractTestSuite(String suiteId,
                                           String displayName,
                                           String description,
                                           List<String> tags,
                                           VisualOperatorContractTestSuiteRequest request) {
        this(SCHEMA_VERSION, suiteId, displayName, description, tags, request);
    }

    /**
     * @param suiteId replacement suite id
     * @return copy with path-derived suite id
     */
    public VisualOperatorContractTestSuite withSuiteId(String suiteId) {
        return new VisualOperatorContractTestSuite(
                schemaVersion,
                suiteId,
                displayName,
                description,
                tags,
                request);
    }
}
