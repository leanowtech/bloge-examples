package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;

/**
 * Domain-separated detached signature over one complete Session checkpoint.
 *
 * @param schemaVersion attestation protocol version
 * @param checkpointId exact checkpoint identity
 * @param checkpointFingerprint exact canonical checkpoint fingerprint
 * @param signedAt signing time included in signed material
 * @param keyId verification key identity
 * @param algorithm detached signature algorithm
 * @param signature base64 detached signature
 * @param independentlyVerifiable complete-signature claim
 */
public record MirrorSessionCheckpointAttestation(
        String schemaVersion,
        String checkpointId,
        String checkpointFingerprint,
        Instant signedAt,
        String keyId,
        String algorithm,
        String signature,
        boolean independentlyVerifiable
) {
    /** Current checkpoint-attestation protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorSessionCheckpointAttestation.v1";

    /** Validates one complete Ed25519 signature manifest. */
    public MirrorSessionCheckpointAttestation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror Session checkpoint attestation schemaVersion");
        }
        checkpointId = bounded(checkpointId, "checkpointId", 512);
        checkpointFingerprint = MirrorStateProtocolSupport.fingerprint(
                checkpointFingerprint, "checkpointFingerprint");
        signedAt = java.util.Objects.requireNonNull(signedAt, "signedAt");
        keyId = bounded(keyId, "keyId", 1_024);
        algorithm = bounded(algorithm, "algorithm", 64);
        signature = bounded(signature, "signature", 16_384);
        if (!independentlyVerifiable
                || Instant.EPOCH.equals(signedAt)
                || !"Ed25519".equals(algorithm)) {
            throw new IllegalArgumentException(
                    "checkpoint attestation requires a complete Ed25519 signature");
        }
    }

    /** Prevents detached signature material from expanding generic logs. */
    @Override
    public String toString() {
        return "MirrorSessionCheckpointAttestation[checkpointId="
                + checkpointId + ", keyId=" + keyId
                + ", algorithm=" + algorithm
                + ", signedAt=" + signedAt + "]";
    }

    private static String bounded(
            String value, String field, int maximumLength) {
        String normalized = MirrorStateProtocolSupport.required(value, field);
        if (normalized.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " exceeds its length limit");
        }
        return normalized;
    }
}
