package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;

/** Integrity-addressed stored correctness evidence companion. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredCorrectnessEvidenceCompanion(
        String schemaVersion,
        String companionFingerprint,
        CorrectnessEvidenceCompanion companion
) {
    public static final String SCHEMA_VERSION = "bloge.storedCorrectnessEvidenceCompanion.v1";

    public StoredCorrectnessEvidenceCompanion {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported stored evidence companion schemaVersion");
        }
        if (companionFingerprint == null
                || !companionFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact evidence companion fingerprint is required");
        }
        if (companion == null) {
            throw new IllegalArgumentException("Correctness evidence companion is required");
        }
    }

    public static StoredCorrectnessEvidenceCompanion verified(
            ObjectMapper mapper,
            CorrectnessEvidenceCompanion companion
    ) {
        return new StoredCorrectnessEvidenceCompanion(
                SCHEMA_VERSION,
                CorrectnessProtocolFingerprint.derivedFingerprint(mapper, companion),
                companion);
    }
}
