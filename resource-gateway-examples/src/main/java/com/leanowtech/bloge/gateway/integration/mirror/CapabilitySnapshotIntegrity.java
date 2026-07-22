package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Objects;

/**
 * Canonical content-addressing boundary for capability snapshots.
 *
 * <p>The fingerprint is computed over the complete normalized snapshot with the fingerprint field
 * blank. Verification repeats the same operation and rejects any source, contract, dependency,
 * ownership, lifecycle, provenance, or timestamp drift.</p>
 */
public final class CapabilitySnapshotIntegrity {
    /** Maximum canonical snapshot size admitted to fingerprinting. */
    public static final int MAXIMUM_CANONICAL_BYTES = 2 * 1024 * 1024;

    private CapabilitySnapshotIntegrity() {
    }

    /**
     * Attaches the canonical fingerprint to an unsealed DRAFT/REVIEWED snapshot.
     *
     * @param mapper application JSON mapper
     * @param snapshot snapshot with a blank or stale fingerprint
     * @return sealed immutable snapshot
     */
    public static CapabilitySnapshot seal(ObjectMapper mapper, CapabilitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        CapabilitySnapshot material = snapshot.withFingerprint("");
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, material, MAXIMUM_CANONICAL_BYTES);
        return material.withFingerprint(fingerprint);
    }

    /**
     * Verifies that the attached fingerprint still matches the complete snapshot.
     *
     * @param mapper application JSON mapper
     * @param snapshot sealed snapshot
     * @throws IllegalArgumentException when the snapshot is unsealed or has drifted
     */
    public static void verify(ObjectMapper mapper, CapabilitySnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.fingerprint().isBlank()) {
            throw new IllegalArgumentException("capability snapshot is not sealed");
        }
        String expected = seal(mapper, snapshot).fingerprint();
        if (!expected.equals(snapshot.fingerprint())) {
            throw new IllegalArgumentException("capability snapshot fingerprint mismatch");
        }
    }
}
