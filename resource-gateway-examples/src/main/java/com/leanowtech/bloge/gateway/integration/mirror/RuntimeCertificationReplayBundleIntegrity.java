package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.Objects;

/** Canonical producer and verifier for payload-free runtime-certification replay bundles. */
public final class RuntimeCertificationReplayBundleIntegrity {
    /** Maximum canonical replay-bundle size. */
    public static final int MAXIMUM_BUNDLE_BYTES = 32 * 1024 * 1024;

    private final ObjectMapper mapper;

    /** @param mapper canonical protocol mapper */
    public RuntimeCertificationReplayBundleIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** @return content-addressed immutable replay bundle */
    public RuntimeCertificationReplayBundle address(Material material) {
        Objects.requireNonNull(material, "material");
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(mapper,
                new FingerprintMaterial(RuntimeCertificationReplayBundle.SCHEMA_VERSION,
                        "", material), MAXIMUM_BUNDLE_BYTES);
        return bundle(material, fingerprint);
    }

    /** @return whether the complete bundle content address and constituent closure are canonical */
    public boolean canonicalVerified(RuntimeCertificationReplayBundle bundle) {
        if (bundle == null) {
            return false;
        }
        try {
            return bundle.bundleFingerprint().equals(
                    address(Material.from(bundle)).bundleFingerprint());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static RuntimeCertificationReplayBundle bundle(
            Material material, String fingerprint) {
        return new RuntimeCertificationReplayBundle(
                "", fingerprint, material.bundleId(), material.revision(),
                material.manifest(), material.authorization(), material.report(),
                material.regionalContract(), material.regionalCertification(),
                material.isolationDecision(), material.exportedAt(), material.exporter());
    }

    /** Replay-bundle fields before content addressing. */
    public record Material(
            String bundleId,
            long revision,
            RuntimeCertificationManifest manifest,
            RuntimeCertificationExecutionAuthorization authorization,
            RuntimeCertificationReport report,
            RegionalDataPlaneDeploymentContract regionalContract,
            RegionalDataPlaneCertification regionalCertification,
            MirrorDeploymentIsolationAttestationBundle isolationDecision,
            Instant exportedAt,
            String exporter
    ) {
        private static Material from(RuntimeCertificationReplayBundle value) {
            return new Material(value.bundleId(), value.revision(), value.manifest(),
                    value.authorization(), value.report(), value.regionalContract(),
                    value.regionalCertification(), value.isolationDecision(),
                    value.exportedAt(), value.exporter());
        }
    }

    private record FingerprintMaterial(
            String schemaVersion,
            String bundleFingerprint,
            Material material) {
    }
}
