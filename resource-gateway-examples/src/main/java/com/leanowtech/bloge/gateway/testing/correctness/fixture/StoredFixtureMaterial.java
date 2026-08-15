package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.FixtureMaterialProtocolV2.Receipt;

/** Internal encrypted Fixture material envelope. Public metadata APIs never expose this type. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoredFixtureMaterial(
        String schemaVersion,
        EnterpriseScope scope,
        Receipt receipt,
        String state,
        boolean payloadAvailable,
        String protectedPayload,
        String recordFingerprint
) {
    public static final String SCHEMA_VERSION = "bloge.storedFixtureMaterial.v2";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String EXPIRED = "EXPIRED";

    public StoredFixtureMaterial {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        state = state == null || state.isBlank() ? AVAILABLE : state.trim();
        protectedPayload = protectedPayload == null ? "" : protectedPayload;
        recordFingerprint = recordFingerprint == null ? "" : recordFingerprint.trim();
    }

    StoredFixtureMaterial withRecordFingerprint(String fingerprint) {
        return new StoredFixtureMaterial(
                schemaVersion, scope, receipt, state, payloadAvailable,
                protectedPayload, fingerprint);
    }

    StoredFixtureMaterial expired() {
        return new StoredFixtureMaterial(
                schemaVersion, scope, receipt, EXPIRED, false, "", "");
    }
}
