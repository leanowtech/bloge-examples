package com.leanowtech.bloge.gateway.businessmirror.governance;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Instant;
import java.util.Objects;

/** Payload-free receipt for one accepted or idempotently replayed ANEKE projection. */
public record PackageGovernanceProjectionReceipt(
        String schemaVersion,
        MirrorArtifactRef projectionRef,
        long externalGeneration,
        DomainCapabilityPackageGovernanceProjection.Status status,
        Instant acceptedAt,
        boolean replayed
) {
    public static final String SCHEMA_VERSION =
            "toolStudio.packageGovernanceProjectionReceipt.v1";

    public PackageGovernanceProjectionReceipt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported governance receipt version");
        }
        projectionRef = Objects.requireNonNull(projectionRef, "projectionRef");
        if (externalGeneration < 1) {
            throw new IllegalArgumentException("externalGeneration must be positive");
        }
        status = Objects.requireNonNull(status, "status");
        acceptedAt = Objects.requireNonNull(acceptedAt, "acceptedAt");
    }
}
