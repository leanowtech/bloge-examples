package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Untrusted import command whose raw source is admitted only for one bounded materialization.
 *
 * <p>The source text is never retained, logged, or returned. The service independently parses it
 * and verifies every client-supplied fingerprint before accepting the resulting Scenarios.</p>
 *
 * @param schemaVersion request protocol version
 * @param sourceText raw bounded CSV or JSON snapshot
 * @param plan exact browser-authored materialization plan
 * @param draftSet canonical base Scenario set
 * @param templateScenarioId optional Scenario copied for dependency/assertion structure
 */
public record ScenarioImportMaterializationRequest(
        String schemaVersion,
        String sourceText,
        JsonNode plan,
        ScenarioDraftSet draftSet,
        String templateScenarioId
) {
    /** Current request protocol version. */
    public static final String SCHEMA_VERSION = "bloge.scenarioImportMaterializationRequest.v1";

    /** Keeps diagnostics and accidental logging payload-free. */
    @Override
    public String toString() {
        return "ScenarioImportMaterializationRequest[schemaVersion=" + normalized(schemaVersion)
                + ", sourceBytes=" + (sourceText == null ? 0 : sourceText.length())
                + ", planPresent=" + (plan != null)
                + ", draftSetId=" + (draftSet == null ? "" : draftSet.scenarioDraftSetId())
                + "]";
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
