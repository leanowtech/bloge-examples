package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;

/**
 * Optimistically fenced lifecycle command for one exact capability snapshot revision.
 *
 * @param schemaVersion command protocol version
 * @param expectedRevision latest revision the caller reviewed
 * @param target requested governed lifecycle
 * @param expiresAt optional approval certification expiry
 * @param revocationRef immutable governance decision reference required for REVOKED
 */
public record CapabilityLifecycleTransitionRequest(
        String schemaVersion,
        long expectedRevision,
        CapabilitySnapshot.Lifecycle target,
        Instant expiresAt,
        String revocationRef
) {
    /** Current lifecycle command protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.capabilityLifecycleTransition.v1";

    /** Validates optimistic and lifecycle coordinates. */
    public CapabilityLifecycleTransitionRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        if (expectedRevision < 1) {
            throw new IllegalArgumentException("expectedRevision must be positive");
        }
        target = java.util.Objects.requireNonNull(target, "target");
        revocationRef = revocationRef == null ? "" : revocationRef.trim();
        if (target == CapabilitySnapshot.Lifecycle.REVOKED && revocationRef.isBlank()) {
            throw new IllegalArgumentException("REVOKED transition requires revocationRef");
        }
        if (target != CapabilitySnapshot.Lifecycle.REVOKED && !revocationRef.isBlank()) {
            throw new IllegalArgumentException("revocationRef is only valid for REVOKED transition");
        }
    }
}
