package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Atomic current view of an accepted isolation attestation and its local trust status.
 *
 * <p>The bundle binds the externally signed proof to the exact full enterprise scope, authority
 * publication generation, and append-only local status observed in one repository transaction.
 * Consumers must verify {@link #bundleFingerprint()} and must never cache the attestation and
 * status independently.</p>
 *
 * @param schemaVersion bundle protocol version
 * @param bundleFingerprint canonical fingerprint of the complete atomic view
 * @param scope complete owning enterprise scope
 * @param authorityKeySetRef exact authority publication used to admit the proof
 * @param attestation externally signed deployment-isolation proof
 * @param status current append-only local status publication
 * @param regionalDataPlaneCertificationRef exact regional data-plane certification for v2 bundles
 */
public record MirrorDeploymentIsolationAttestationBundle(
        String schemaVersion,
        String bundleFingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef authorityKeySetRef,
        MirrorDeploymentIsolationAttestation attestation,
        MirrorDeploymentIsolationAttestationStatusPublication status,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        MirrorArtifactRef regionalDataPlaneCertificationRef
) {
    /** Legacy isolation-only atomic bundle protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationAttestationBundle.v1";
    /** Regional data-plane certified atomic bundle protocol version. */
    public static final String REGIONAL_DATA_PLANE_SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationAttestationBundle.v2";
    /** Artifact kind used by exact bundle references. */
    public static final String ARTIFACT_KIND = "DEPLOYMENT_ISOLATION_ATTESTATION_BUNDLE";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates cross-record coordinates without treating fingerprints as trusted. */
    public MirrorDeploymentIsolationAttestationBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? regionalDataPlaneCertificationRef == null
                ? SCHEMA_VERSION : REGIONAL_DATA_PLANE_SCHEMA_VERSION
                : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                && !REGIONAL_DATA_PLANE_SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported deployment isolation attestation bundle schemaVersion");
        }
        bundleFingerprint = fingerprint(bundleFingerprint, "bundleFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        authorityKeySetRef = Objects.requireNonNull(authorityKeySetRef, "authorityKeySetRef");
        attestation = Objects.requireNonNull(attestation, "attestation");
        status = Objects.requireNonNull(status, "status");
        var material = status.material();
        if (!scope.equals(material.scope())
                || !authorityKeySetRef.equals(material.authorityKeySetRef())
                || !attestation.artifactRef().equals(material.attestationRef())
                || !attestation.material().deployment().equals(material.deployment())) {
            throw new IllegalArgumentException(
                    "deployment isolation attestation bundle coordinates are inconsistent");
        }
        if (SCHEMA_VERSION.equals(schemaVersion)
                && regionalDataPlaneCertificationRef != null
                || REGIONAL_DATA_PLANE_SCHEMA_VERSION.equals(schemaVersion)
                && (regionalDataPlaneCertificationRef == null
                || !RegionalDataPlaneCertification.ARTIFACT_KIND.equals(
                regionalDataPlaneCertificationRef.kind()))) {
            throw new IllegalArgumentException(
                    "deployment isolation bundle regional certification is inconsistent");
        }
    }

    /**
     * Preserves the v1 constructor for isolation-only producers and consumers.
     */
    public MirrorDeploymentIsolationAttestationBundle(
            String schemaVersion,
            String bundleFingerprint,
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef authorityKeySetRef,
            MirrorDeploymentIsolationAttestation attestation,
            MirrorDeploymentIsolationAttestationStatusPublication status) {
        this(schemaVersion, bundleFingerprint, scope, authorityKeySetRef,
                attestation, status, null);
    }

    /**
     * Returns an exact reference whose id includes the attestation revision and whose revision is
     * the local status revision.
     *
     * @return content-addressed atomic bundle reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(ARTIFACT_KIND,
                attestation.material().attestationId() + "#revision:"
                        + attestation.material().revision(),
                status.material().statusRevision(), bundleFingerprint);
    }

    /**
     * Reports whether this view is currently active before independent time and authority checks.
     *
     * @return true only for the initial non-revoked status
     */
    public boolean active() {
        return status.material().state()
                == MirrorDeploymentIsolationAttestationStatusPublication.State.ACTIVE;
    }

    private static String fingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
