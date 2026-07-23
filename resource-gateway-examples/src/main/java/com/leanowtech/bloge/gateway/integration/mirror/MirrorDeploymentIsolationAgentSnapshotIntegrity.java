package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.Objects;

/** Canonical content-addressing boundary for deployment-agent cache snapshots. */
public final class MirrorDeploymentIsolationAgentSnapshotIntegrity {
    /** Maximum canonical snapshot size. */
    public static final int MAXIMUM_SNAPSHOT_BYTES = 4 * 1024 * 1024;

    private final ObjectMapper mapper;
    private final MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity;
    private final MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity;

    /**
     * Creates the independent cache-snapshot verifier.
     *
     * @param mapper canonical protocol mapper
     * @param authorityIntegrity authority publication content-addressing boundary
     * @param bundleIntegrity attestation bundle content-addressing boundary
     */
    public MirrorDeploymentIsolationAgentSnapshotIntegrity(
            ObjectMapper mapper,
            MirrorDeploymentIsolationAuthorityKeySetIntegrity authorityIntegrity,
            MirrorDeploymentIsolationAttestationBundleIntegrity bundleIntegrity) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.authorityIntegrity = Objects.requireNonNull(
                authorityIntegrity, "authorityIntegrity");
        this.bundleIntegrity = Objects.requireNonNull(bundleIntegrity, "bundleIntegrity");
    }

    /**
     * Creates one canonical cache generation from already verified trust artifacts.
     *
     * @param cacheGeneration next positive local generation
     * @param refreshedAt trusted acceptance instant
     * @param validUntil exclusive local positive-admission deadline
     * @param authorityPublication verified authority publication, or null for denial-only state
     * @param attestationBundle exact active or revoked current bundle
     * @return canonical immutable cache snapshot
     */
    public MirrorDeploymentIsolationAgentSnapshot snapshot(
            long cacheGeneration,
            Instant refreshedAt,
            Instant validUntil,
            MirrorDeploymentIsolationAuthorityKeySetPublication authorityPublication,
            MirrorDeploymentIsolationAttestationBundle attestationBundle) {
        if (authorityPublication != null
                && !authorityIntegrity.canonicalFingerprintVerified(authorityPublication)
                || !bundleIntegrity.canonicalBundleVerified(attestationBundle)) {
            throw new IllegalArgumentException(
                    "canonical deployment isolation trust artifacts are required");
        }
        String fingerprint = fingerprint(cacheGeneration, refreshedAt, validUntil,
                authorityPublication, attestationBundle);
        return new MirrorDeploymentIsolationAgentSnapshot("", fingerprint, cacheGeneration,
                refreshedAt, validUntil, authorityPublication, attestationBundle);
    }

    /**
     * Recomputes every nested and complete cache fingerprint.
     *
     * @param snapshot untrusted decoded cache snapshot
     * @return true only when every content address is exact
     */
    public boolean canonicalSnapshotVerified(
            MirrorDeploymentIsolationAgentSnapshot snapshot) {
        if (snapshot == null
                || snapshot.authorityPublication() != null
                && !authorityIntegrity.canonicalFingerprintVerified(
                snapshot.authorityPublication())
                || !bundleIntegrity.canonicalBundleVerified(snapshot.attestationBundle())) {
            return false;
        }
        try {
            return snapshot.snapshotFingerprint().equals(fingerprint(
                    snapshot.cacheGeneration(), snapshot.refreshedAt(), snapshot.validUntil(),
                    snapshot.authorityPublication(), snapshot.attestationBundle()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private String fingerprint(
            long cacheGeneration,
            Instant refreshedAt,
            Instant validUntil,
            MirrorDeploymentIsolationAuthorityKeySetPublication authorityPublication,
            MirrorDeploymentIsolationAttestationBundle attestationBundle) {
        return VisualBundleFingerprint.fromCanonicalValue(mapper,
                new FingerprintMaterial(
                        MirrorDeploymentIsolationAgentSnapshot.SCHEMA_VERSION, "",
                        cacheGeneration, refreshedAt, validUntil, authorityPublication,
                        attestationBundle), MAXIMUM_SNAPSHOT_BYTES);
    }

    private record FingerprintMaterial(
            String schemaVersion,
            String snapshotFingerprint,
            long cacheGeneration,
            Instant refreshedAt,
            Instant validUntil,
            MirrorDeploymentIsolationAuthorityKeySetPublication authorityPublication,
            MirrorDeploymentIsolationAttestationBundle attestationBundle) {
    }
}
