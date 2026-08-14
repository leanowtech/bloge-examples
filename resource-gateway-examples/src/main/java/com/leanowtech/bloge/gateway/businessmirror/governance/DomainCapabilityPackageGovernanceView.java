package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Instant;
import java.util.Objects;

/** Freshness-aware read model joining current RG facts with the cached ANEKE projection. */
public record DomainCapabilityPackageGovernanceView(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        String packageId,
        MirrorArtifactRef currentPackageSnapshotRef,
        MirrorArtifactRef currentEvidenceIndexRef,
        MirrorArtifactRef currentRegistryIngestBundleRef,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        DomainCapabilityPackageGovernanceProjection projection,
        Freshness freshness,
        String reasonCode,
        Instant evaluatedAt
) {
    public static final String SCHEMA_VERSION =
            "toolStudio.domainCapabilityPackageGovernanceView.v1";

    public DomainCapabilityPackageGovernanceView {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported Package governance view version");
        }
        scope = Objects.requireNonNull(scope, "scope");
        packageId = PackageRegistryIngestBundle.identifier(packageId, "packageId");
        currentPackageSnapshotRef = Objects.requireNonNull(
                currentPackageSnapshotRef, "currentPackageSnapshotRef");
        currentEvidenceIndexRef = Objects.requireNonNull(
                currentEvidenceIndexRef, "currentEvidenceIndexRef");
        currentRegistryIngestBundleRef = Objects.requireNonNull(
                currentRegistryIngestBundleRef, "currentRegistryIngestBundleRef");
        freshness = Objects.requireNonNull(freshness, "freshness");
        reasonCode = reasonCode == null ? "" : reasonCode.trim();
        evaluatedAt = Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        if (freshness == Freshness.CURRENT && projection == null
                || freshness != Freshness.CURRENT && reasonCode.isBlank()) {
            throw new IllegalArgumentException("Package governance freshness is incomplete");
        }
    }

    public enum Freshness {
        CURRENT,
        MISSING,
        STALE,
        EXPIRED,
        UNVERIFIABLE
    }
}
