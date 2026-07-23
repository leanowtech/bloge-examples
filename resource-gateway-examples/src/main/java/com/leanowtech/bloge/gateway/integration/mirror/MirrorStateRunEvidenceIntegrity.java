package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;

/** Canonical sealing and independent fingerprint verification for state run evidence. */
public final class MirrorStateRunEvidenceIntegrity {
    /** Maximum canonical bytes admitted for one payload-free state evidence value. */
    public static final int MAXIMUM_CANONICAL_BYTES = 32 * 1024 * 1024;

    private MirrorStateRunEvidenceIntegrity() {
    }

    /**
     * Canonically fingerprints one complete state evidence value.
     *
     * @param mapper canonical protocol mapper
     * @param evidence unsealed or resealed payload-free state evidence
     * @return sealed immutable state evidence
     */
    public static MirrorStateRunEvidence seal(
            ObjectMapper mapper, MirrorStateRunEvidence evidence) {
        Objects.requireNonNull(mapper, "mapper");
        MirrorStateRunEvidence material =
                Objects.requireNonNull(evidence, "evidence")
                        .withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                mapper, material, MAXIMUM_CANONICAL_BYTES));
    }

    /**
     * Recomputes and verifies the attached state evidence fingerprint.
     *
     * @param mapper canonical protocol mapper
     * @param evidence sealed state evidence
     */
    public static void verify(
            ObjectMapper mapper, MirrorStateRunEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.stateEvidenceFingerprint().isBlank()
                || !evidence.stateEvidenceFingerprint().equals(
                seal(mapper, evidence).stateEvidenceFingerprint())) {
            throw new IllegalArgumentException(
                    "mirror state run-evidence fingerprint mismatch");
        }
    }

    /**
     * Returns the exact nested evidence reference used by workbook projections.
     *
     * @param evidence sealed state evidence
     * @return immutable {@code MIRROR_STATE_RUN_EVIDENCE} reference
     */
    public static MirrorArtifactRef reference(
            MirrorStateRunEvidence evidence) {
        if (evidence == null
                || evidence.stateEvidenceFingerprint().isBlank()) {
            throw new IllegalArgumentException(
                    "mirror state run evidence must be sealed");
        }
        return new MirrorArtifactRef(
                "MIRROR_STATE_RUN_EVIDENCE", evidence.runId(), 1,
                evidence.stateEvidenceFingerprint());
    }
}
