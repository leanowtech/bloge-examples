package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Server-authoritative canonical Scenario set and payload-free materialization receipt.
 *
 * @param schemaVersion result protocol version
 * @param draftSet materialized canonical Scenario set
 * @param receipt exact immutable receipt
 */
public record ScenarioImportMaterializationResult(
        String schemaVersion,
        ScenarioDraftSet draftSet,
        JsonNode receipt
) {
    /** Current result protocol version. */
    public static final String SCHEMA_VERSION = "bloge.scenarioImportMaterializationResult.v1";

    /** Applies the current protocol version. */
    public ScenarioImportMaterializationResult {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
    }
}
