package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.Objects;

/** Canonical content-addressing boundary for local attestation status and atomic bundles. */
public final class MirrorDeploymentIsolationAttestationBundleIntegrity {
    /** Maximum canonical status-publication size. */
    public static final int MAXIMUM_STATUS_BYTES = 256 * 1024;
    /** Maximum canonical atomic-bundle size. */
    public static final int MAXIMUM_BUNDLE_BYTES = 2 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity;

    /**
     * Creates the canonical local distribution integrity boundary.
     *
     * @param mapper canonical protocol mapper
     * @param attestationIntegrity external attestation content-addressing verifier
     */
    public MirrorDeploymentIsolationAttestationBundleIntegrity(
            ObjectMapper mapper,
            MirrorDeploymentIsolationAttestationIntegrity attestationIntegrity) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.attestationIntegrity = Objects.requireNonNull(
                attestationIntegrity, "attestationIntegrity");
    }

    /**
     * Creates the initial active status atomically admitted with an attestation.
     *
     * @param scope complete enterprise scope
     * @param authorityKeySetRef exact verified authority publication
     * @param attestation exact externally signed proof
     * @param acceptedAt trusted control-plane acceptance time
     * @return canonical active status publication
     */
    public MirrorDeploymentIsolationAttestationStatusPublication activeStatus(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef authorityKeySetRef,
            MirrorDeploymentIsolationAttestation attestation,
            Instant acceptedAt) {
        Objects.requireNonNull(attestation, "attestation");
        var material = new MirrorDeploymentIsolationAttestationStatusPublication.Material(
                scope, attestation.material().deployment(), authorityKeySetRef,
                attestation.artifactRef(), 1, "",
                MirrorDeploymentIsolationAttestationStatusPublication.State.ACTIVE,
                MirrorDeploymentIsolationAttestationStatusPublication.Reason.ACCEPTED,
                acceptedAt);
        return status(material);
    }

    /**
     * Creates the only permitted successor of an active status.
     *
     * @param active exact current active status
     * @param reason closed non-accepted revocation reason
     * @param revokedAt trusted control-plane revocation time
     * @return canonical irreversible revoked status
     */
    public MirrorDeploymentIsolationAttestationStatusPublication revokedStatus(
            MirrorDeploymentIsolationAttestationStatusPublication active,
            MirrorDeploymentIsolationAttestationStatusPublication.Reason reason,
            Instant revokedAt) {
        Objects.requireNonNull(active, "active");
        if (!canonicalStatusVerified(active)
                || active.material().state()
                != MirrorDeploymentIsolationAttestationStatusPublication.State.ACTIVE) {
            throw new IllegalArgumentException("an exact canonical active status is required");
        }
        var prior = active.material();
        if (revokedAt == null || revokedAt.isBefore(prior.effectiveAt())) {
            throw new IllegalArgumentException(
                    "revocation time must not precede active status");
        }
        var material = new MirrorDeploymentIsolationAttestationStatusPublication.Material(
                prior.scope(), prior.deployment(), prior.authorityKeySetRef(),
                prior.attestationRef(), 2, active.statusFingerprint(),
                MirrorDeploymentIsolationAttestationStatusPublication.State.REVOKED,
                reason, revokedAt);
        return status(material);
    }

    /**
     * Creates one canonical atomic current bundle.
     *
     * @param scope complete enterprise scope
     * @param authorityKeySetRef exact authority publication used at ingest
     * @param attestation externally signed attestation
     * @param status exact current local status
     * @return canonical atomic bundle
     */
    public MirrorDeploymentIsolationAttestationBundle bundle(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef authorityKeySetRef,
            MirrorDeploymentIsolationAttestation attestation,
            MirrorDeploymentIsolationAttestationStatusPublication status) {
        if (!attestationIntegrity.canonicalFingerprintVerified(attestation)
                || !canonicalStatusVerified(status)) {
            throw new IllegalArgumentException(
                    "canonical attestation and status are required");
        }
        String fingerprint = bundleFingerprint(scope, authorityKeySetRef, attestation, status);
        return new MirrorDeploymentIsolationAttestationBundle("", fingerprint, scope,
                authorityKeySetRef, attestation, status);
    }

    /**
     * Recomputes every nested and complete bundle fingerprint.
     *
     * @param bundle untrusted decoded bundle
     * @return true only when attestation, status, and bundle content addresses are exact
     */
    public boolean canonicalBundleVerified(MirrorDeploymentIsolationAttestationBundle bundle) {
        if (bundle == null || !attestationIntegrity.canonicalFingerprintVerified(
                bundle.attestation()) || !canonicalStatusVerified(bundle.status())) {
            return false;
        }
        try {
            return bundle.bundleFingerprint().equals(bundleFingerprint(bundle.scope(),
                    bundle.authorityKeySetRef(), bundle.attestation(), bundle.status()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /**
     * Recomputes one local status fingerprint.
     *
     * @param status untrusted decoded status publication
     * @return true only when the status content address is exact
     */
    public boolean canonicalStatusVerified(
            MirrorDeploymentIsolationAttestationStatusPublication status) {
        if (status == null) {
            return false;
        }
        try {
            return status.statusFingerprint().equals(
                    statusFingerprint(status.material()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private MirrorDeploymentIsolationAttestationStatusPublication status(
            MirrorDeploymentIsolationAttestationStatusPublication.Material material) {
        return new MirrorDeploymentIsolationAttestationStatusPublication("",
                statusFingerprint(material), material);
    }

    private String statusFingerprint(
            MirrorDeploymentIsolationAttestationStatusPublication.Material material) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new StatusFingerprintMaterial(
                        MirrorDeploymentIsolationAttestationStatusPublication.SCHEMA_VERSION,
                        "", material), MAXIMUM_STATUS_BYTES);
    }

    private String bundleFingerprint(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef authorityKeySetRef,
            MirrorDeploymentIsolationAttestation attestation,
            MirrorDeploymentIsolationAttestationStatusPublication status) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new BundleFingerprintMaterial(
                        MirrorDeploymentIsolationAttestationBundle.SCHEMA_VERSION, "",
                        scope, authorityKeySetRef, attestation, status), MAXIMUM_BUNDLE_BYTES);
    }

    private record StatusFingerprintMaterial(
            String schemaVersion,
            String statusFingerprint,
            MirrorDeploymentIsolationAttestationStatusPublication.Material material) {
    }

    private record BundleFingerprintMaterial(
            String schemaVersion,
            String bundleFingerprint,
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef authorityKeySetRef,
            MirrorDeploymentIsolationAttestation attestation,
            MirrorDeploymentIsolationAttestationStatusPublication status) {
    }
}
