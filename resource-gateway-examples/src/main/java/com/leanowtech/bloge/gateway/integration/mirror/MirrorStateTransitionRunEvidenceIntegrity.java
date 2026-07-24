package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;

/**
 * Canonical sealing and independent fingerprint verification for read/write state evidence.
 */
public final class MirrorStateTransitionRunEvidenceIntegrity {
    /** Maximum canonical state-evidence bytes admitted to hashing. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            64 * 1024 * 1024;

    private MirrorStateTransitionRunEvidenceIntegrity() {
    }

    /**
     * Seals one complete read/write state-evidence value.
     *
     * @param mapper canonical protocol mapper
     * @param evidence unsealed evidence
     * @return sealed immutable evidence
     */
    public static MirrorStateTransitionRunEvidence seal(
            ObjectMapper mapper,
            MirrorStateTransitionRunEvidence evidence) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(evidence, "evidence");
        MirrorStateTransitionRunEvidence material =
                evidence.withFingerprint("");
        String fingerprint = ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_CANONICAL_BYTES);
        MirrorStateTransitionRunEvidence sealed =
                material.withFingerprint(fingerprint);
        verify(mapper, sealed);
        return sealed;
    }

    /**
     * Recomputes the canonical fingerprint and rejects any altered closure.
     *
     * @param mapper canonical protocol mapper
     * @param evidence sealed evidence
     */
    public static void verify(
            ObjectMapper mapper,
            MirrorStateTransitionRunEvidence evidence) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(evidence, "evidence");
        String expected = ProtocolFingerprint.ofBounded(
                mapper, evidence.withFingerprint(""),
                MAXIMUM_CANONICAL_BYTES);
        if (!expected.equals(
                evidence.stateEvidenceFingerprint())) {
            throw new IllegalArgumentException(
                    "mirror state transition evidence fingerprint mismatch");
        }
    }

    /**
     * Returns the exact payload-free artifact reference for sealed evidence.
     *
     * @param evidence verified state evidence
     * @return exact state-evidence reference
     */
    public static MirrorArtifactRef reference(
            MirrorStateTransitionRunEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        return new MirrorArtifactRef(
                "MIRROR_STATE_RUN_EVIDENCE",
                evidence.runId(), 2,
                evidence.stateEvidenceFingerprint());
    }
}
