package com.leanowtech.bloge.gateway.authoring.scenario;

import java.time.Instant;

/**
 * Integrity-addressed persistence envelope for one mutable Scenario draft-set revision.
 *
 * @param schemaVersion stored-envelope protocol version
 * @param scenarioDraftSetId stable authoring asset id
 * @param revision exact mutable-asset revision
 * @param fingerprint canonical fingerprint of the complete stored draft
 * @param draftSet immutable snapshot of the authoring payload
 * @param savedAt server persistence time
 * @param savedBy verified workload actor
 */
public record StoredScenarioDraftSet(
        String schemaVersion,
        String scenarioDraftSetId,
        long revision,
        String fingerprint,
        ScenarioDraftSet draftSet,
        Instant savedAt,
        String savedBy
) {
    /** Current stored Scenario draft-set envelope version. */
    public static final String SCHEMA_VERSION = "bloge.storedScenarioDraftSet.v1";

    /** Normalizes the envelope and rejects internally inconsistent persistence identities. */
    public StoredScenarioDraftSet {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        scenarioDraftSetId = normalized(scenarioDraftSetId);
        fingerprint = normalized(fingerprint);
        savedBy = normalized(savedBy);
        if (draftSet == null) {
            throw new IllegalArgumentException("Stored Scenario draft set requires a payload");
        }
        if (!scenarioDraftSetId.equals(draftSet.scenarioDraftSetId())
                || revision != draftSet.revision()) {
            throw new IllegalArgumentException("Stored Scenario draft-set envelope identity is inconsistent");
        }
        if (savedAt == null) {
            throw new IllegalArgumentException("Stored Scenario draft set requires savedAt");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
