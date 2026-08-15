package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureAssetDescriptor;

/** Integrity-addressed, payload-free Fixture catalog revision. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredFixtureAsset(
        String schemaVersion,
        String descriptorFingerprint,
        FixtureAssetDescriptor descriptor
) {
    public static final String SCHEMA_VERSION = "bloge.storedFixtureAsset.v1";

    public StoredFixtureAsset {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported stored Fixture descriptor schemaVersion");
        }
        if (descriptorFingerprint == null
                || !descriptorFingerprint.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact Fixture descriptor fingerprint is required");
        }
        if (descriptor == null || descriptor.revision() < 1) {
            throw new IllegalArgumentException("Persisted Fixture descriptor revision is required");
        }
    }

    public static StoredFixtureAsset verified(
            ObjectMapper mapper,
            FixtureAssetDescriptor descriptor
    ) {
        return new StoredFixtureAsset(
                SCHEMA_VERSION,
                CorrectnessProtocolFingerprint.fingerprint(mapper, descriptor),
                descriptor);
    }

    public com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef
            exactRef() {
        return new com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol
                .ExactAssetRef("FIXTURE_ASSET", descriptor.fixtureAssetId(), descriptor.revision(),
                descriptorFingerprint);
    }
}
