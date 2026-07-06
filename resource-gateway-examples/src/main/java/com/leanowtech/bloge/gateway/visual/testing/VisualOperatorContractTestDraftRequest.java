package com.leanowtech.bloge.gateway.visual.testing;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request to synthesize an editable operator contract-test table row from schemas.
 *
 * @param schemaVersion request schema version
 * @param operatorRef visual operator reference
 * @param caseName generated row name
 * @param includeOptionalPorts include optional input/output ports in generated mocks
 * @param inputOverrides generated input overrides keyed by port name
 * @param configOverrides generated config override values
 * @param mockedOutputOverrides generated output overrides keyed by port name
 */
public record VisualOperatorContractTestDraftRequest(
        String schemaVersion,
        String operatorRef,
        String caseName,
        boolean includeOptionalPorts,
        Map<String, Object> inputOverrides,
        Map<String, Object> configOverrides,
        Map<String, Object> mockedOutputOverrides
) {
    public static final String SCHEMA_VERSION = "bloge.visualOperatorContractTestDraftRequest.v1";

    /**
     * Creates a draft request.
     */
    public VisualOperatorContractTestDraftRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        caseName = caseName == null || caseName.isBlank() ? "generated schema mock" : caseName.trim();
        inputOverrides = inputOverrides == null ? Map.of() : new LinkedHashMap<>(inputOverrides);
        configOverrides = configOverrides == null ? Map.of() : new LinkedHashMap<>(configOverrides);
        mockedOutputOverrides = mockedOutputOverrides == null ? Map.of() : new LinkedHashMap<>(mockedOutputOverrides);
    }
}
