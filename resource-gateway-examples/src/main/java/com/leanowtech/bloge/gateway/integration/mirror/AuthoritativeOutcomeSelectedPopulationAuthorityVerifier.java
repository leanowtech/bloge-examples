package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;

/**
 * Independent customer trust boundary for authoritative outcome selected-population manifests.
 *
 * <p>An implementation resolves the exact selection policy, authority-set revision, sampling
 * frame, and external selection attestation through a governed channel. Resource Gateway invokes
 * this boundary before signing and whenever the manifest is consumed. A Resource Gateway seal
 * proves artifact custody; it cannot make a selectively submitted population complete.</p>
 */
public interface
AuthoritativeOutcomeSelectedPopulationAuthorityVerifier {
    /** @return whether the exact external selection trust chain can currently be verified */
    boolean available();

    /**
     * Verifies the independently governed selection statement and every referenced member chunk.
     *
     * @param manifest structurally closed selected-population root
     * @param chunks exact ordered chunks referenced by the root
     * @throws RuntimeException when selection policy, frame, authority, or membership is rejected
     */
    void verify(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks);
}
