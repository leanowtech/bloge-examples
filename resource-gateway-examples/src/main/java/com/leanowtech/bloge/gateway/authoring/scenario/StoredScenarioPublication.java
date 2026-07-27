package com.leanowtech.bloge.gateway.authoring.scenario;

/**
 * Integrity-addressed envelope for one durable Scenario publication transition.
 *
 * @param schemaVersion stored-envelope protocol version
 * @param stateVersion optimistic transition version
 * @param fingerprint canonical fingerprint of the report
 * @param report detached publication report
 */
public record StoredScenarioPublication(
        String schemaVersion,
        long stateVersion,
        String fingerprint,
        ScenarioPublicationReport report
) {
    /** Current stored publication envelope protocol. */
    public static final String SCHEMA_VERSION = "bloge.storedScenarioPublication.v1";

    /** Normalizes the envelope and rejects incomplete state. */
    public StoredScenarioPublication {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        fingerprint = fingerprint == null ? "" : fingerprint.trim();
        if (stateVersion <= 0 || report == null || report.publicationId().isBlank()) {
            throw new IllegalArgumentException("Stored Scenario publication requires exact state identity");
        }
    }
}
