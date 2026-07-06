package com.leanowtech.bloge.gateway.visual.testing;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Generated editable operator contract-test suite draft.
 *
 * @param schemaVersion response schema version
 * @param operatorRef visual operator reference
 * @param suite generated suite request
 * @param diagnostics generation diagnostics
 */
public record VisualOperatorContractTestDraftResponse(
        String schemaVersion,
        String operatorRef,
        VisualOperatorContractTestSuiteRequest suite,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorContractTestDraft.v1";

    /**
     * Creates a draft response.
     */
    public VisualOperatorContractTestDraftResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        operatorRef = operatorRef == null ? "" : operatorRef;
        suite = suite == null ? new VisualOperatorContractTestSuiteRequest(operatorRef, List.of()) : suite;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
