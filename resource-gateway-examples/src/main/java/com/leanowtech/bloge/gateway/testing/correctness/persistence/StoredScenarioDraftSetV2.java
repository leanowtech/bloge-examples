package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;

/** Integrity-addressed persisted governed Scenario Draft Set revision. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredScenarioDraftSetV2(
        String schemaVersion,
        String scenarioDraftSetFingerprint,
        ScenarioDraftSetV2 scenarioDraftSet
) {
    public static final String SCHEMA_VERSION = "bloge.storedScenarioDraftSet.v2";

    public StoredScenarioDraftSetV2 {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported stored Scenario v2 schemaVersion");
        }
        if (scenarioDraftSetFingerprint == null
                || !scenarioDraftSetFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact Scenario v2 fingerprint is required");
        }
        if (scenarioDraftSet == null || scenarioDraftSet.revision() < 1) {
            throw new IllegalArgumentException("Persisted Scenario v2 revision is required");
        }
    }

    public static StoredScenarioDraftSetV2 verified(
            ObjectMapper mapper,
            ScenarioDraftSetV2 scenarioDraftSet
    ) {
        return new StoredScenarioDraftSetV2(
                SCHEMA_VERSION,
                CorrectnessProtocolFingerprint.fingerprint(mapper, scenarioDraftSet),
                scenarioDraftSet);
    }
}
