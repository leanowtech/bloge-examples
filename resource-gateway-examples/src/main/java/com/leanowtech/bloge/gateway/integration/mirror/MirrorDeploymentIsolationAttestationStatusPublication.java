package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Content-addressed local trust status for one exact deployment-isolation attestation.
 *
 * <p>The external authority signs the attestation itself. Resource Gateway owns this separate,
 * append-only status stream so an authenticated trust administrator can revoke one accepted
 * artifact without rewriting the external proof. Version one is intentionally irreversible:
 * revision one is {@link State#ACTIVE}; revision two may only be {@link State#REVOKED}.</p>
 *
 * @param schemaVersion status-publication protocol version
 * @param statusFingerprint canonical fingerprint of the complete status publication
 * @param material immutable full-scope status material
 */
public record MirrorDeploymentIsolationAttestationStatusPublication(
        String schemaVersion,
        String statusFingerprint,
        Material material
) {
    /** Current deployment-isolation attestation status protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorDeploymentIsolationAttestationStatus.v1";
    /** Artifact kind used by content-addressed status references. */
    public static final String ARTIFACT_KIND = "DEPLOYMENT_ISOLATION_ATTESTATION_STATUS";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates closed status syntax without claiming that its fingerprint is canonical. */
    public MirrorDeploymentIsolationAttestationStatusPublication {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported deployment isolation attestation status schemaVersion");
        }
        statusFingerprint = fingerprint(statusFingerprint, "statusFingerprint");
        material = Objects.requireNonNull(material, "material");
    }

    /**
     * Returns the exact immutable status reference.
     *
     * @return content-addressed status artifact reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(ARTIFACT_KIND,
                material.attestationRef().id() + "#revision:"
                        + material.attestationRef().revision(),
                material.statusRevision(), statusFingerprint);
    }

    /** Local lifecycle state of one exact accepted attestation. */
    public enum State {
        /** The artifact is current and may be considered by execution admission. */
        ACTIVE,
        /** The artifact is permanently denied even when signature and time remain valid. */
        REVOKED
    }

    /** Closed payload-free reasons for an irreversible status transition. */
    public enum Reason {
        /** Initial state created atomically with trusted attestation ingest. */
        ACCEPTED,
        /** A security authority or incident process revoked the exact artifact. */
        SECURITY_INCIDENT,
        /** Effective isolation policy no longer matches the accepted deployment policy. */
        POLICY_DRIFT,
        /** The immutable workload generation has been superseded. */
        DEPLOYMENT_REPLACED,
        /** The signing authority or exact signing key is no longer trusted. */
        AUTHORITY_REVOKED,
        /** An authorized operator explicitly revoked the exact artifact. */
        OPERATOR_REVOKED
    }

    /**
     * Full-scope immutable status statement.
     *
     * @param scope complete authenticated enterprise scope
     * @param deployment exact immutable workload generation
     * @param authorityKeySetRef exact authority publication used at ingest
     * @param attestationRef exact externally signed attestation
     * @param statusRevision local status revision, one for active and two for revoked
     * @param previousStatusFingerprint blank for active, exact active status for revocation
     * @param state irreversible local lifecycle state
     * @param reason closed payload-free transition reason
     * @param effectiveAt trusted control-plane transition time
     */
    public record Material(
            CapabilitySnapshot.Scope scope,
            MirrorDeploymentIsolationAttestation.DeploymentIdentity deployment,
            MirrorArtifactRef authorityKeySetRef,
            MirrorArtifactRef attestationRef,
            long statusRevision,
            String previousStatusFingerprint,
            State state,
            Reason reason,
            Instant effectiveAt
    ) {
        /** Enforces exact references and the irreversible two-state v1 lifecycle. */
        public Material {
            scope = Objects.requireNonNull(scope, "scope");
            deployment = Objects.requireNonNull(deployment, "deployment");
            authorityKeySetRef = Objects.requireNonNull(
                    authorityKeySetRef, "authorityKeySetRef");
            attestationRef = Objects.requireNonNull(attestationRef, "attestationRef");
            previousStatusFingerprint = previousStatusFingerprint == null
                    ? "" : previousStatusFingerprint.trim();
            state = Objects.requireNonNull(state, "state");
            reason = Objects.requireNonNull(reason, "reason");
            effectiveAt = Objects.requireNonNull(effectiveAt, "effectiveAt");
            if (!MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND.equals(
                    authorityKeySetRef.kind())
                    || !MirrorDeploymentIsolationAttestation.ARTIFACT_KIND.equals(
                    attestationRef.kind())) {
                throw new IllegalArgumentException(
                        "deployment isolation status artifact kinds are invalid");
            }
            boolean active = state == State.ACTIVE;
            if (active != (statusRevision == 1 && previousStatusFingerprint.isBlank()
                    && reason == Reason.ACCEPTED)) {
                throw new IllegalArgumentException(
                        "active deployment isolation status must be initial and accepted");
            }
            if (!active && (statusRevision != 2
                    || !FINGERPRINT.matcher(previousStatusFingerprint).matches()
                    || reason == Reason.ACCEPTED)) {
                throw new IllegalArgumentException(
                        "revoked deployment isolation status must follow the active status");
            }
        }
    }

    private static String fingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }
}
