package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;

/**
 * Payload-free deployment-isolation trust captured around one Mirror execution.
 *
 * <p>The stable decision identity is the atomic attestation-bundle reference. Agent snapshot
 * references identify the local observations used at admission and terminal confirmation without
 * treating routine cache refresh as a different trust decision.</p>
 */
public final class MirrorDeploymentIsolationRunTrust {
    private MirrorDeploymentIsolationRunTrust() {
    }

    /**
     * Verified deployment trust captured before durable request acquisition.
     *
     * @param scope exact enterprise execution scope
     * @param decisionRef atomic authority, attestation, and status decision
     * @param authorityKeySetRef exact trusted authority publication
     * @param attestationRef exact externally signed isolation proof
     * @param statusRef exact active local status publication
     * @param admittedSnapshotRef local agent observation used for admission
     * @param admittedAt trusted admission time
     * @param validUntil exclusive local positive-admission deadline
     */
    public record Admission(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef decisionRef,
            MirrorArtifactRef authorityKeySetRef,
            MirrorArtifactRef attestationRef,
            MirrorArtifactRef statusRef,
            MirrorArtifactRef admittedSnapshotRef,
            Instant admittedAt,
            Instant validUntil) {
        /** Validates one complete active, payload-free admission coordinate. */
        public Admission {
            scope = Objects.requireNonNull(scope, "scope");
            decisionRef = kind(decisionRef,
                    MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND, "decisionRef");
            authorityKeySetRef = kind(authorityKeySetRef,
                    MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                    "authorityKeySetRef");
            attestationRef = kind(attestationRef,
                    MirrorDeploymentIsolationAttestation.ARTIFACT_KIND, "attestationRef");
            statusRef = kind(statusRef,
                    MirrorDeploymentIsolationAttestationStatusPublication.ARTIFACT_KIND,
                    "statusRef");
            admittedSnapshotRef = kind(admittedSnapshotRef,
                    MirrorDeploymentIsolationAgentSnapshot.ARTIFACT_KIND,
                    "admittedSnapshotRef");
            admittedAt = Objects.requireNonNull(admittedAt, "admittedAt");
            validUntil = Objects.requireNonNull(validUntil, "validUntil");
            if (!validUntil.isAfter(admittedAt)
                    || decisionRef.revision() != statusRef.revision()) {
                throw new IllegalArgumentException(
                        "deployment isolation run admission is inconsistent");
            }
        }
    }

    /**
     * Portable trust binding signed into terminal Mirror evidence.
     *
     * @param schemaVersion run-trust binding protocol version
     * @param decisionRef stable atomic authority, attestation, and status decision
     * @param authorityKeySetRef exact trusted authority publication
     * @param attestationRef exact externally signed isolation proof
     * @param statusRef exact active local status publication
     * @param admittedSnapshotRef local agent observation used before execution
     * @param committedSnapshotRef local agent observation used after execution
     * @param admittedAt trusted admission time
     * @param confirmedAt trusted terminal confirmation time
     */
    public record Binding(
            String schemaVersion,
            MirrorArtifactRef decisionRef,
            MirrorArtifactRef authorityKeySetRef,
            MirrorArtifactRef attestationRef,
            MirrorArtifactRef statusRef,
            MirrorArtifactRef admittedSnapshotRef,
            MirrorArtifactRef committedSnapshotRef,
            Instant admittedAt,
            Instant confirmedAt) {
        /** Current signed run-trust binding version. */
        public static final String SCHEMA_VERSION =
                "resourceGateway.mirrorDeploymentIsolationRunTrust.v1";

        /** Validates stable decision identity and monotonic local observations. */
        public Binding {
            schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                    ? SCHEMA_VERSION : schemaVersion.trim();
            decisionRef = kind(decisionRef,
                    MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND, "decisionRef");
            authorityKeySetRef = kind(authorityKeySetRef,
                    MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                    "authorityKeySetRef");
            attestationRef = kind(attestationRef,
                    MirrorDeploymentIsolationAttestation.ARTIFACT_KIND, "attestationRef");
            statusRef = kind(statusRef,
                    MirrorDeploymentIsolationAttestationStatusPublication.ARTIFACT_KIND,
                    "statusRef");
            admittedSnapshotRef = kind(admittedSnapshotRef,
                    MirrorDeploymentIsolationAgentSnapshot.ARTIFACT_KIND,
                    "admittedSnapshotRef");
            committedSnapshotRef = kind(committedSnapshotRef,
                    MirrorDeploymentIsolationAgentSnapshot.ARTIFACT_KIND,
                    "committedSnapshotRef");
            admittedAt = Objects.requireNonNull(admittedAt, "admittedAt");
            confirmedAt = Objects.requireNonNull(confirmedAt, "confirmedAt");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || confirmedAt.isBefore(admittedAt)
                    || committedSnapshotRef.revision() < admittedSnapshotRef.revision()
                    || !committedSnapshotRef.id().equals(admittedSnapshotRef.id())
                    || decisionRef.revision() != statusRef.revision()) {
                throw new IllegalArgumentException(
                        "deployment isolation run trust binding is inconsistent");
            }
        }
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value, String expected, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!expected.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " has an invalid artifact kind");
        }
        return exact;
    }
}
