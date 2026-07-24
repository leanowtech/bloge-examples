package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.Objects;

/**
 * Canonical sealing boundary for physical mirror Session data-plane generations.
 */
public final class MirrorSessionStoreGenerationIntegrity {
    private MirrorSessionStoreGenerationIntegrity() {
    }

    /**
     * Content-addresses one immutable store generation.
     *
     * @param mapper canonical protocol mapper
     * @param generation unsealed generation
     * @return sealed generation
     */
    public static MirrorSessionStoreGeneration seal(
            ObjectMapper mapper, MirrorSessionStoreGeneration generation) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(generation, "generation");
        MirrorSessionStoreGeneration material = generation.withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.of(mapper, material));
    }

    /**
     * Verifies the generation's canonical fingerprint.
     *
     * @param mapper canonical protocol mapper
     * @param generation sealed generation
     */
    public static void verify(
            ObjectMapper mapper, MirrorSessionStoreGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        if (!generation.fingerprint().equals(
                seal(mapper, generation).fingerprint())) {
            throw new IllegalArgumentException(
                    "mirror Session store generation fingerprint mismatch");
        }
    }
}
