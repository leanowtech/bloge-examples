package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.Optional;

/**
 * Signing authority for persisted run evidence. Enterprise deployments can replace this with KMS/HSM.
 */
public interface VisualEvidenceSigner {

    VisualRunEvidenceSeal seal(String materialFingerprint);

    Verification verify(VisualRunEvidenceSeal seal, String actualMaterialFingerprint);

    Optional<VerificationKey> key(String keyId);

    boolean available();

    record Verification(boolean valid, String status, String reason) {
        public static Verification unavailable(String reason) {
            return new Verification(false, "UNAVAILABLE", reason == null ? "" : reason);
        }
    }

    record VerificationKey(String schemaVersion, String keyId, String algorithm, String encodedPublicKey,
                           Instant createdAt, String state, String provider) {
        public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.evidenceVerificationKey.v1";

        public VerificationKey {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
            keyId = keyId == null ? "" : keyId;
            algorithm = algorithm == null ? "" : algorithm;
            encodedPublicKey = encodedPublicKey == null ? "" : encodedPublicKey;
            createdAt = createdAt == null ? Instant.EPOCH : createdAt;
            state = state == null ? "UNKNOWN" : state;
            provider = provider == null ? "" : provider;
        }
    }

    static VisualEvidenceSigner unavailable() {
        return UnavailableVisualEvidenceSigner.INSTANCE;
    }
}
