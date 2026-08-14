package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * ANEKE-owned, signed governance projection cached by Resource Gateway for one exact Package.
 *
 * <p>The projection never mutates a Resource Gateway draft or snapshot. Its external generation,
 * source cursor, validity window, and exact Package/ingest/gate references make staleness explicit
 * and keep ANEKE as the sole registry and publish-gate authority.</p>
 */
public record DomainCapabilityPackageGovernanceProjection(
        String schemaVersion,
        String projectionFingerprint,
        String projectionId,
        long revision,
        long externalGeneration,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef packageSnapshotRef,
        MirrorArtifactRef registryIngestBundleRef,
        MirrorArtifactRef evidenceIndexRef,
        MirrorArtifactRef registryRecordRef,
        Status status,
        @JsonInclude(JsonInclude.Include.ALWAYS) MirrorArtifactRef gateDecisionRef,
        String sourceCursorFingerprint,
        Instant producedAt,
        Instant validFrom,
        Instant expiresAt,
        String issuer,
        VisualRunEvidenceSeal projectionSeal
) {
    /** Current ANEKE Package governance projection wire version. */
    public static final String SCHEMA_VERSION =
            "toolStudio.domainCapabilityPackageGovernanceProjection.v1";
    /** Artifact kind used by audit and change-event references. */
    public static final String ARTIFACT_KIND = "DOMAIN_CAPABILITY_PACKAGE_GOVERNANCE_PROJECTION";
    /** Maximum lifetime of one cached external projection. */
    public static final Duration MAXIMUM_LIFETIME = Duration.ofDays(7);

    /** Validates exact external-governance coordinates and lifecycle semantics. */
    public DomainCapabilityPackageGovernanceProjection {
        schemaVersion = version(schemaVersion);
        projectionFingerprint = optionalFingerprint(
                projectionFingerprint, "projectionFingerprint");
        projectionId = PackageRegistryIngestBundle.identifier(projectionId, "projectionId");
        if (revision < 1 || externalGeneration < 1) {
            throw new IllegalArgumentException(
                    "projection revision and external generation must be positive");
        }
        scope = Objects.requireNonNull(scope, "scope");
        packageSnapshotRef = requireKind(
                packageSnapshotRef, "DOMAIN_CAPABILITY_PACKAGE", "packageSnapshotRef");
        registryIngestBundleRef = requireKind(registryIngestBundleRef,
                PackageRegistryIngestBundle.ARTIFACT_KIND, "registryIngestBundleRef");
        evidenceIndexRef = requireKind(
                evidenceIndexRef, "PACKAGE_EVIDENCE_INDEX", "evidenceIndexRef");
        registryRecordRef = requireKind(
                registryRecordRef, "ANEKE_PACKAGE_REGISTRY_RECORD", "registryRecordRef");
        status = Objects.requireNonNull(status, "status");
        gateDecisionRef = gateDecisionRef == null ? null : requireKind(
                gateDecisionRef, "ANEKE_PACKAGE_GATE_DECISION", "gateDecisionRef");
        if (status == Status.UNDER_REVIEW && gateDecisionRef != null
                || status != Status.UNDER_REVIEW && gateDecisionRef == null) {
            throw new IllegalArgumentException(
                    "governance status and gateDecisionRef are inconsistent");
        }
        if (!packageSnapshotRef.id().equals(evidenceIndexRef.id())
                || packageSnapshotRef.revision() != registryIngestBundleRef.revision()) {
            throw new IllegalArgumentException("governance projection Package refs do not close");
        }
        sourceCursorFingerprint = PackageRegistryIngestBundle.fingerprint(
                sourceCursorFingerprint, "sourceCursorFingerprint");
        producedAt = Objects.requireNonNull(producedAt, "producedAt");
        validFrom = Objects.requireNonNull(validFrom, "validFrom");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (validFrom.isBefore(producedAt) || !expiresAt.isAfter(validFrom)
                || Duration.between(producedAt, expiresAt).compareTo(MAXIMUM_LIFETIME) > 0) {
            throw new IllegalArgumentException("governance projection window is invalid");
        }
        issuer = PackageRegistryIngestBundle.identifier(issuer, "issuer");
        projectionSeal = Objects.requireNonNull(projectionSeal, "projectionSeal");
        if (!projectionSeal.signed()
                || projectionSeal.signedAt().isBefore(producedAt)
                || !projectionSeal.signedAt().isBefore(expiresAt)) {
            throw new IllegalArgumentException("governance projection must be signed");
        }
    }

    /** @return exact immutable projection reference */
    public MirrorArtifactRef artifactRef() {
        if (projectionFingerprint.isBlank()) {
            throw new IllegalStateException("governance projection is not content-addressed");
        }
        return new MirrorArtifactRef(
                ARTIFACT_KIND, projectionId, revision, projectionFingerprint);
    }

    /** Closed ANEKE governance lifecycle; no Resource Gateway-invented success state exists. */
    public enum Status {
        UNDER_REVIEW,
        ACCEPTED,
        REJECTED,
        CERTIFIED,
        SUSPENDED,
        REVOKED
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value, String kind, String field) {
        if (value == null || !kind.equals(value.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return value;
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException("unsupported Package governance projection version");
        }
        return exact;
    }

    private static String optionalFingerprint(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (!exact.isBlank()) {
            PackageRegistryIngestBundle.fingerprint(exact, field);
        }
        return exact;
    }
}
