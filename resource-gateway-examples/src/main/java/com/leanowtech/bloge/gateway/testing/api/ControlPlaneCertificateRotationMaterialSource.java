package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deployment-owned boundary that resolves safe rotation material identifiers.
 *
 * <p>The signed event never carries filesystem paths, secret references, passwords, private
 * keys, or certificate bytes. An implementation resolves its opaque {@code materialId} behind this
 * boundary and returns both immutable public settings and an independently computed fingerprint.
 * The controller compares that fingerprint with the signed event before staging anything.</p>
 */
@FunctionalInterface
public interface ControlPlaneCertificateRotationMaterialSource {

    /**
     * One fully resolved candidate without secret values.
     *
     * @param settingsFingerprint exact fingerprint over resolved public settings and certificate
     *                            material
     * @param settings complete immutable pinned mutual-TLS settings
     */
    record ResolvedMaterial(
            String settingsFingerprint,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings) {
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Rejects ambiguous fingerprints and absent candidates. */
        public ResolvedMaterial {
            settingsFingerprint = Objects.requireNonNullElse(settingsFingerprint, "").trim();
            settings = Objects.requireNonNull(settings, "settings");
            if (!FINGERPRINT.matcher(settingsFingerprint).matches()) {
                throw new IllegalArgumentException(
                        "Control-plane certificate rotation material is invalid");
            }
        }
    }

    /**
     * Resolves one exact signed candidate.
     *
     * @param targetId stable local transport identity
     * @param generation contiguous successor generation
     * @param materialId safe deployment-owned lookup identity from the signed event
     * @return fully resolved candidate and exact settings fingerprint
     */
    ResolvedMaterial resolve(String targetId, long generation, String materialId);
}
