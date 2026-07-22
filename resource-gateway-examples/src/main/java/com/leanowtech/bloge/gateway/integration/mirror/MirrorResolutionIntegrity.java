package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.Objects;

/** Canonical output and artifact fingerprinting for {@link MirrorResolution}. */
public final class MirrorResolutionIntegrity {
    /** Maximum visible output admitted to canonical fingerprinting. */
    public static final int MAXIMUM_OUTPUT_BYTES = 16 * 1024 * 1024;
    /** Maximum complete resolution admitted to canonical fingerprinting. */
    public static final int MAXIMUM_RESOLUTION_BYTES = 20 * 1024 * 1024;

    private MirrorResolutionIntegrity() {
    }

    /**
     * Computes the visible output fingerprint when needed and seals the complete resolution.
     *
     * @param mapper canonical protocol mapper
     * @param resolution unsealed resolution material
     * @return exact immutable resolution carrying both canonical fingerprints
     */
    public static MirrorResolution seal(ObjectMapper mapper, MirrorResolution resolution) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(resolution, "resolution");
        String outputFingerprint = resolution.outputFingerprint();
        if (resolution.outputIncluded()) {
            String computed = VisualBundleFingerprint.fromCanonicalValue(
                    mapper, resolution.output(), MAXIMUM_OUTPUT_BYTES);
            if (!outputFingerprint.isBlank() && !outputFingerprint.equals(computed)) {
                throw new IllegalArgumentException("mirror resolution output fingerprint mismatch");
            }
            outputFingerprint = computed;
        }
        MirrorResolution material = resolution.withFingerprints("", outputFingerprint);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper, material, MAXIMUM_RESOLUTION_BYTES);
        return material.withFingerprints(fingerprint, outputFingerprint);
    }

    /**
     * Verifies visible output and complete artifact identity without mutating the supplied value.
     *
     * @param mapper canonical protocol mapper
     * @param resolution sealed resolution received from storage or another process
     */
    public static void verify(ObjectMapper mapper, MirrorResolution resolution) {
        Objects.requireNonNull(resolution, "resolution");
        if (resolution.resolutionFingerprint().isBlank()) {
            throw new IllegalArgumentException("mirror resolution is not sealed");
        }
        MirrorResolution expected = seal(mapper, resolution);
        if (!expected.resolutionFingerprint().equals(resolution.resolutionFingerprint())) {
            throw new IllegalArgumentException("mirror resolution fingerprint mismatch");
        }
    }
}
