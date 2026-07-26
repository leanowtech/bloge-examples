package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.util.Objects;

/**
 * Dedicated trust boundary for online baseline observation signatures.
 *
 * <p>A Resource Gateway deployment must bind this interface to the independently governed
 * regional TEE authority. It must not reuse the local Resource Gateway evidence signer merely
 * because both authorities currently use Ed25519.</p>
 */
public interface OnlineReadOnlyShadowBaselineEvidenceAuthority {
    /**
     * Signs exact domain-separated observation material.
     *
     * @param materialFingerprint canonical signature material fingerprint
     * @return detached regional authority seal
     */
    VisualRunEvidenceSeal seal(
            String materialFingerprint);

    /**
     * Verifies one detached seal against exact observation material.
     *
     * @param seal untrusted regional authority seal
     * @param actualMaterialFingerprint recomputed signature material
     * @return bounded cryptographic verification outcome
     */
    VisualEvidenceSigner.Verification verify(
            VisualRunEvidenceSeal seal,
            String actualMaterialFingerprint);

    /**
     * Reports whether the independently governed trust role can currently decide.
     *
     * @return true only while signing or verification authority is usable
     */
    boolean available();

    /**
     * Adapts an exact signer for a sidecar producer or protocol test.
     *
     * @param delegate exact signing and verification authority
     * @return role-separated online baseline authority
     */
    static OnlineReadOnlyShadowBaselineEvidenceAuthority from(
            VisualEvidenceSigner delegate) {
        VisualEvidenceSigner exact =
                Objects.requireNonNull(delegate, "delegate");
        return new OnlineReadOnlyShadowBaselineEvidenceAuthority() {
            @Override
            public VisualRunEvidenceSeal seal(
                    String materialFingerprint) {
                return exact.seal(materialFingerprint);
            }

            @Override
            public VisualEvidenceSigner.Verification verify(
                    VisualRunEvidenceSeal seal,
                    String actualMaterialFingerprint) {
                return exact.verify(
                        seal, actualMaterialFingerprint);
            }

            @Override
            public boolean available() {
                return exact.available();
            }
        };
    }

    /**
     * Returns an authority that can neither sign nor verify.
     *
     * @return fail-closed authority
     */
    static OnlineReadOnlyShadowBaselineEvidenceAuthority
    unavailable() {
        return from(VisualEvidenceSigner.unavailable());
    }
}
