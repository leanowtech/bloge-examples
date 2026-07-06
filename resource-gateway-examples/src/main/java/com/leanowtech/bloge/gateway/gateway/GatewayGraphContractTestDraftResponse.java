package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;

import java.util.List;

/**
 * Editable graph contract-test draft generated from formal graph/resource schemas.
 *
 * @param schemaVersion response schema version
 * @param graphName resource graph name
 * @param contract formal graph input/output contract
 * @param suite generated table-test suite request
 * @param diagnostics generation diagnostics
 */
public record GatewayGraphContractTestDraftResponse(
        String schemaVersion,
        String graphName,
        GatewayGraphContract contract,
        GatewayGraphContractTestSuiteRequest suite,
        List<VisualDiagnostic> diagnostics
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContractTestDraft.v1";

    /**
     * Creates a draft response.
     */
    public GatewayGraphContractTestDraftResponse {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        graphName = graphName == null ? "" : graphName;
        suite = suite == null ? new GatewayGraphContractTestSuiteRequest(graphName, List.of()) : suite;
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }

    /**
     * Convenience constructor for current-version callers.
     */
    public GatewayGraphContractTestDraftResponse(String graphName,
                                                 GatewayGraphContract contract,
                                                 GatewayGraphContractTestSuiteRequest suite,
                                                 List<VisualDiagnostic> diagnostics) {
        this(SCHEMA_VERSION, graphName, contract, suite, diagnostics);
    }
}
