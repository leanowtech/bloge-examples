package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Signing authority for persisted run evidence. Enterprise deployments can replace this with KMS/HSM.
 */
public interface VisualEvidenceSigner {

    VisualRunEvidenceSeal seal(String materialFingerprint);

    Verification verify(VisualRunEvidenceSeal seal, String actualMaterialFingerprint);

    Optional<VerificationKey> key(String keyId);

    default KeyResolution resolveKey(String keyId) {
        return key(keyId)
                .map(KeyResolution::available)
                .orElseGet(() -> KeyResolution.notFound("Evidence verification key was not found."));
    }

    boolean available();

    default Descriptor descriptor() {
        return new Descriptor("", getClass().getSimpleName(), "", available(),
                available() ? "HEALTHY" : "UNAVAILABLE", "", false, true, 0,
                null, null, 0, 0, Map.of());
    }

    record Verification(boolean valid, String status, String reason) {
        public static Verification unavailable(String reason) {
            return new Verification(false, "UNAVAILABLE", reason == null ? "" : reason);
        }
    }

    enum KeyResolutionStatus {
        AVAILABLE,
        NOT_FOUND,
        PROVIDER_UNAVAILABLE
    }

    record KeyResolution(KeyResolutionStatus status, VerificationKey key, String reason) {
        public KeyResolution {
            status = status == null ? KeyResolutionStatus.PROVIDER_UNAVAILABLE : status;
            reason = reason == null ? "" : reason;
        }

        public static KeyResolution available(VerificationKey key) {
            return new KeyResolution(KeyResolutionStatus.AVAILABLE, key, "");
        }

        public static KeyResolution notFound(String reason) {
            return new KeyResolution(KeyResolutionStatus.NOT_FOUND, null, reason);
        }

        public static KeyResolution providerUnavailable(String reason) {
            return new KeyResolution(KeyResolutionStatus.PROVIDER_UNAVAILABLE, null, reason);
        }
    }

    record Descriptor(String schemaVersion,
                      String providerType,
                      String providerName,
                      boolean available,
                      String state,
                      String activeKeyId,
                      boolean managedKeyCustody,
                      boolean privateKeyExportable,
                      int verificationKeyCount,
                      Instant lastSuccessfulRefreshAt,
                      Instant snapshotExpiresAt,
                      long successfulSignatureCount,
                      long failedSignatureCount,
                      Map<String, Object> properties) {
        public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.evidenceSignerDescriptor.v1";

        public Descriptor {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
            providerType = providerType == null ? "" : providerType;
            providerName = providerName == null ? "" : providerName;
            state = state == null ? "UNKNOWN" : state;
            activeKeyId = activeKeyId == null ? "" : activeKeyId;
            verificationKeyCount = Math.max(0, verificationKeyCount);
            successfulSignatureCount = Math.max(0, successfulSignatureCount);
            failedSignatureCount = Math.max(0, failedSignatureCount);
            properties = properties == null ? Map.of() : new LinkedHashMap<>(properties);
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
