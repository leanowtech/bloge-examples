package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;

/**
 * Persisted cryptographic seal over the immutable material of one run record.
 */
public record VisualRunEvidenceSeal(
        String schemaVersion,
        String materialFingerprint,
        String algorithm,
        String keyId,
        Instant signedAt,
        String signature
) {
    public static final String SCHEMA_VERSION = "bloge.visualRunEvidenceSeal.v1";

    public VisualRunEvidenceSeal {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        materialFingerprint = materialFingerprint == null ? "" : materialFingerprint;
        algorithm = algorithm == null ? "" : algorithm;
        keyId = keyId == null ? "" : keyId;
        signedAt = signedAt == null ? Instant.EPOCH : signedAt;
        signature = signature == null ? "" : signature;
    }

    public boolean signed() {
        return !materialFingerprint.isBlank() && !keyId.isBlank() && !signature.isBlank();
    }

    public static VisualRunEvidenceSeal unsigned() {
        return new VisualRunEvidenceSeal("", "", "", "", Instant.EPOCH, "");
    }
}
