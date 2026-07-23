package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Atomic read-only deployment-agent view of current Mirror isolation trust.
 *
 * <p>The deployment agent writes this object as one crash-safe cache generation. Runtime
 * consumers must verify the snapshot fingerprint and hard freshness deadline before using an
 * active bundle. A revoked snapshot is always a denial, even after its refresh deadline.</p>
 *
 * @param schemaVersion cache protocol version
 * @param snapshotFingerprint canonical fingerprint of the complete snapshot
 * @param cacheGeneration positive local atomic-cache generation
 * @param refreshedAt trusted instant at which the remote view was accepted
 * @param validUntil exclusive hard freshness deadline for positive admission
 * @param authorityPublication verified authority publication; required while active
 * @param attestationBundle exact current attestation and local status
 */
public record MirrorDeploymentIsolationAgentSnapshot(
        String schemaVersion,
        String snapshotFingerprint,
        long cacheGeneration,
        Instant refreshedAt,
        Instant validUntil,
        MirrorDeploymentIsolationAuthorityKeySetPublication authorityPublication,
        MirrorDeploymentIsolationAttestationBundle attestationBundle
) {
    /** Current deployment-agent cache protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationAgentSnapshot.v1";
    /** Artifact kind used by exact cache-generation references. */
    public static final String ARTIFACT_KIND =
            "DEPLOYMENT_ISOLATION_AGENT_SNAPSHOT";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates structural and cross-artifact coordinates without trusting fingerprints. */
    public MirrorDeploymentIsolationAgentSnapshot {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        snapshotFingerprint = normalized(snapshotFingerprint);
        refreshedAt = Objects.requireNonNull(refreshedAt, "refreshedAt");
        validUntil = Objects.requireNonNull(validUntil, "validUntil");
        attestationBundle = Objects.requireNonNull(attestationBundle, "attestationBundle");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(snapshotFingerprint).matches()
                || cacheGeneration < 1 || !validUntil.isAfter(refreshedAt)
                || attestationBundle.active() && authorityPublication == null
                || authorityPublication != null
                && !authorityPublication.artifactRef().equals(
                attestationBundle.authorityKeySetRef())) {
            throw new IllegalArgumentException(
                    "deployment isolation agent snapshot is invalid");
        }
    }

    /**
     * Creates the content-addressed reference for this local generation.
     *
     * @return exact content-addressed local cache-generation reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(ARTIFACT_KIND,
                attestationBundle.attestation().material().attestationId(),
                cacheGeneration, snapshotFingerprint);
    }

    /**
     * Reports whether the remote trust status is an irreversible denial.
     *
     * @return whether the remote trust status is an irreversible denial
     */
    public boolean revoked() {
        return !attestationBundle.active();
    }

    /**
     * Reports whether this snapshot may positively admit work at one trusted instant.
     *
     * <p>Independent cryptographic verification remains mandatory after deserialization.</p>
     *
     * @param now trusted current time
     * @return true only for an active snapshot before every local and signed deadline
     */
    public boolean usableAt(Instant now) {
        Instant exact = Objects.requireNonNull(now, "now");
        if (!attestationBundle.active() || authorityPublication == null
                || !exact.isBefore(validUntil)) {
            return false;
        }
        return exact.isBefore(authorityPublication.material().expiresAt())
                && exact.isBefore(attestationBundle.attestation().material().expiresAt());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
