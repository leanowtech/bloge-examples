package com.leanowtech.bloge.gateway.visual.testing;

import java.util.List;

/**
 * Request to run table-driven schema contract tests for one operator definition.
 *
 * @param schemaVersion request schema version
 * @param operatorRef visual operator reference
 * @param cases table rows to validate
 */
public record VisualOperatorContractTestSuiteRequest(
        String schemaVersion,
        String operatorRef,
        List<VisualOperatorContractTestCase> cases
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorContractTestSuiteRequest.v1";

    /**
     * Creates a suite request.
     */
    public VisualOperatorContractTestSuiteRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    /**
     * Convenience constructor for current-version requests.
     */
    public VisualOperatorContractTestSuiteRequest(String operatorRef,
                                                  List<VisualOperatorContractTestCase> cases) {
        this(SCHEMA_VERSION, operatorRef, cases);
    }
}
