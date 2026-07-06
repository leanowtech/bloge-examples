package com.leanowtech.bloge.gateway.gateway;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request to generate an editable schema/mock table-test draft for one resource graph.
 *
 * @param schemaVersion request schema version
 * @param graphName resource graph name
 * @param caseName generated row display name
 * @param outputNode optional terminal output-node override
 * @param contextOverrides caller-provided context sample overrides
 * @param resourcePayloadOverrides mock payload overrides keyed by resource id
 */
public record GatewayGraphContractTestDraftRequest(
        String schemaVersion,
        String graphName,
        String caseName,
        String outputNode,
        Map<String, Object> contextOverrides,
        Map<String, Object> resourcePayloadOverrides
) {
    public static final String SCHEMA_VERSION = "bloge.gatewayGraphContractTestDraftRequest.v1";

    /**
     * Creates a draft request.
     */
    public GatewayGraphContractTestDraftRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        graphName = graphName == null ? "" : graphName.trim();
        caseName = caseName == null || caseName.isBlank() ? "Generated graph contract mock row" : caseName.trim();
        outputNode = outputNode == null ? "" : outputNode.trim();
        contextOverrides = contextOverrides == null || contextOverrides.isEmpty()
                ? Map.of()
                : new LinkedHashMap<>(contextOverrides);
        resourcePayloadOverrides = resourcePayloadOverrides == null || resourcePayloadOverrides.isEmpty()
                ? Map.of()
                : new LinkedHashMap<>(resourcePayloadOverrides);
    }

    /**
     * Convenience constructor for current-version callers.
     */
    public GatewayGraphContractTestDraftRequest(String graphName,
                                                String caseName,
                                                String outputNode,
                                                Map<String, Object> contextOverrides,
                                                Map<String, Object> resourcePayloadOverrides) {
        this(SCHEMA_VERSION, graphName, caseName, outputNode, contextOverrides, resourcePayloadOverrides);
    }
}
