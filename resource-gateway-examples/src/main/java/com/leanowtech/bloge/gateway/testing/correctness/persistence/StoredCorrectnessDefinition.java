package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessDefinition;

import static com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint.fingerprint;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Integrity-addressed persisted Correctness Definition revision. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredCorrectnessDefinition(
        String schemaVersion,
        String definitionFingerprint,
        CorrectnessDefinition definition
) {
    public static final String SCHEMA_VERSION = "bloge.storedCorrectnessDefinition.v1";

    public StoredCorrectnessDefinition {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported stored Definition schemaVersion");
        }
        if (definitionFingerprint == null
                || !definitionFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact Definition fingerprint is required");
        }
        if (definition == null || definition.revision() < 1) {
            throw new IllegalArgumentException("Persisted Definition revision is required");
        }
    }

    public static StoredCorrectnessDefinition verified(
            ObjectMapper mapper,
            CorrectnessDefinition definition
    ) {
        return new StoredCorrectnessDefinition(
                SCHEMA_VERSION, fingerprint(mapper, definition), definition);
    }
}
