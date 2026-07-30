package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.FixtureReceipt;

/**
 * Internal encrypted fixture envelope. Public authoring contracts never expose this type.
 */
public record StoredAuthoringFixture(
        String schemaVersion,
        TestingArtifactScope scope,
        FixtureReceipt descriptor,
        String state,
        boolean payloadAvailable,
        String protectedPayload,
        String recordFingerprint
) {
    public static final String SCHEMA_VERSION =
            "bloge.storedVisualAuthoringFixture.v1";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String EXPIRED = "EXPIRED";

    public StoredAuthoringFixture {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        state = state == null || state.isBlank()
                ? AVAILABLE : state.trim().toUpperCase(java.util.Locale.ROOT);
        protectedPayload = protectedPayload == null ? "" : protectedPayload;
        recordFingerprint = recordFingerprint == null ? "" : recordFingerprint.trim();
    }

    public StoredAuthoringFixture withRecordFingerprint(String fingerprint) {
        return new StoredAuthoringFixture(
                schemaVersion, scope, descriptor, state, payloadAvailable,
                protectedPayload, fingerprint);
    }

    public StoredAuthoringFixture expired() {
        return new StoredAuthoringFixture(
                schemaVersion, scope, descriptor, EXPIRED, false, "", "");
    }
}
