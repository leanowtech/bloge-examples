package com.leanowtech.bloge.gateway.gateway;

import java.util.List;

/**
 * Stored resource graph contract-test suite.
 *
 * @param schemaVersion stored suite schema version
 * @param suiteId stable suite id
 * @param displayName human-readable name
 * @param description optional description
 * @param tags suite tags for batch selection and reporting
 * @param request table-driven suite request
 * @param coveragePolicy minimum evidence policy
 */
public record GatewayGraphContractTestSuite(
        String schemaVersion,
        String suiteId,
        String displayName,
        String description,
        List<String> tags,
        GatewayGraphContractTestSuiteRequest request,
        GatewayGraphContractTestCoveragePolicy coveragePolicy
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContractTestSuite.v1";

    /**
     * Creates a stored suite.
     */
    public GatewayGraphContractTestSuite {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        suiteId = suiteId == null ? "" : suiteId.trim();
        displayName = displayName == null || displayName.isBlank() ? suiteId : displayName.trim();
        description = description == null ? "" : description;
        tags = tags == null
                ? List.of()
                : tags.stream()
                        .filter(tag -> tag != null && !tag.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
        request = request == null ? new GatewayGraphContractTestSuiteRequest("", List.of()) : request;
        coveragePolicy = coveragePolicy == null
                ? GatewayGraphContractTestCoveragePolicy.none()
                : coveragePolicy;
    }

    /**
     * Convenience constructor.
     */
    public GatewayGraphContractTestSuite(String suiteId,
                                         String displayName,
                                         String description,
                                         List<String> tags,
                                         GatewayGraphContractTestSuiteRequest request,
                                         GatewayGraphContractTestCoveragePolicy coveragePolicy) {
        this(SCHEMA_VERSION, suiteId, displayName, description, tags, request, coveragePolicy);
    }

    /**
     * @param suiteId replacement suite id
     * @return this suite with a path-derived id
     */
    public GatewayGraphContractTestSuite withSuiteId(String suiteId) {
        return new GatewayGraphContractTestSuite(
                schemaVersion,
                suiteId,
                displayName,
                description,
                tags,
                request,
                coveragePolicy);
    }
}
