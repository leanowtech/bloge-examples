package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;

/** Integrity-addressed persisted Business Oracle revision. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredBusinessOracle(
        String schemaVersion,
        String oracleFingerprint,
        BusinessOracle oracle
) {
    public static final String SCHEMA_VERSION = "bloge.storedBusinessOracle.v1";

    public StoredBusinessOracle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported stored Oracle schemaVersion");
        }
        if (oracleFingerprint == null
                || !oracleFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact Oracle fingerprint is required");
        }
        if (oracle == null || oracle.revision() < 1) {
            throw new IllegalArgumentException("Persisted Oracle revision is required");
        }
    }

    public static StoredBusinessOracle verified(ObjectMapper mapper, BusinessOracle oracle) {
        return new StoredBusinessOracle(
                SCHEMA_VERSION,
                CorrectnessProtocolFingerprint.fingerprint(mapper, oracle),
                oracle);
    }
}
