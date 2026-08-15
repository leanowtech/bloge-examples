package com.leanowtech.bloge.gateway.testing.correctness.publication;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;

/** Integrity-addressed immutable correctness Publication manifest. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredCorrectnessPublication(
        String schemaVersion,
        String publicationFingerprint,
        CorrectnessPublication publication
) {
    public static final String SCHEMA_VERSION = "bloge.storedCorrectnessPublication.v1";

    public StoredCorrectnessPublication {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported stored Publication schemaVersion");
        }
        if (publicationFingerprint == null
                || !publicationFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact Publication fingerprint is required");
        }
        if (publication == null) {
            throw new IllegalArgumentException("Publication manifest is required");
        }
    }

    public static StoredCorrectnessPublication verified(
            ObjectMapper mapper,
            CorrectnessPublication publication
    ) {
        return new StoredCorrectnessPublication(
                SCHEMA_VERSION,
                CorrectnessProtocolFingerprint.fingerprint(mapper, publication),
                publication);
    }
}
