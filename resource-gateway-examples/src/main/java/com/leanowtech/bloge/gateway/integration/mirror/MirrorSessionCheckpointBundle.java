package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.Objects;

/**
 * Portable signed and payload-free Session recovery checkpoint.
 *
 * @param schemaVersion checkpoint bundle protocol version
 * @param bundleFingerprint canonical complete-bundle fingerprint
 * @param payloadPolicy mandatory business-payload omission policy
 * @param checkpoint complete exact recovery fence
 * @param attestation detached signature over the checkpoint
 */
public record MirrorSessionCheckpointBundle(
        String schemaVersion,
        String bundleFingerprint,
        PayloadPolicy payloadPolicy,
        MirrorSessionCheckpoint checkpoint,
        MirrorSessionCheckpointAttestation attestation
) {
    /** Current checkpoint bundle protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorSessionCheckpointBundle.v1";

    /** Business-payload handling for checkpoint transport. */
    public enum PayloadPolicy {
        HASH_ONLY
    }

    /** Validates one complete cross-object checkpoint identity. */
    public MirrorSessionCheckpointBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror Session checkpoint bundle schemaVersion");
        }
        bundleFingerprint = MirrorStateProtocolSupport.fingerprint(
                bundleFingerprint, "bundleFingerprint");
        payloadPolicy = payloadPolicy == null
                ? PayloadPolicy.HASH_ONLY : payloadPolicy;
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        attestation = Objects.requireNonNull(attestation, "attestation");
        if (!checkpoint.checkpointId().equals(attestation.checkpointId())
                || !checkpoint.fingerprint().equals(
                attestation.checkpointFingerprint())
                || attestation.signedAt().isBefore(
                checkpoint.checkpointedAt())) {
            throw new IllegalArgumentException(
                    "checkpoint bundle identity or time closure is invalid");
        }
    }

    /** Keeps complete dependency and signature material out of generic logs. */
    @Override
    public String toString() {
        return "MirrorSessionCheckpointBundle[checkpointId="
                + checkpoint.checkpointId()
                + ", sessionId=" + checkpoint.sessionId()
                + ", stateRevision=" + checkpoint.stateRevision()
                + ", payloadPolicy=" + payloadPolicy + "]";
    }
}
