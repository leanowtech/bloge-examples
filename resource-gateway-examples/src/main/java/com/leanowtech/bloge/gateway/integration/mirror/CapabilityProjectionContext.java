package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;

/**
 * Caller- and policy-owned coordinates used while projecting an authoritative asset.
 *
 * <p>The context contains no business payload. It freezes ownership, lifecycle, security, approval,
 * and creation time so a projection is reproducible and cannot infer governance facts from ambient
 * thread state.</p>
 *
 * @param revision positive capability snapshot revision
 * @param tenantId owning tenant
 * @param organizationId owning organization or business unit
 * @param projectId optional project namespace
 * @param environmentId deployment environment namespace
 * @param region optional residency/execution region
 * @param purpose authorized projection purpose
 * @param ownership accountable owner metadata
 * @param lifecycle initial governed lifecycle
 * @param classification maximum data classification
 * @param allowedRegions explicit region allowlist; empty remains unresolved
 * @param payloadRetentionAllowed whether policy permits governed payload retention
 * @param approvedBy approving actor for reviewed/active snapshots
 * @param approvedAt approval time for reviewed/active snapshots
 * @param expiresAt certification expiry
 * @param createdAt deterministic snapshot creation time
 */
public record CapabilityProjectionContext(
        long revision,
        String tenantId,
        String organizationId,
        String projectId,
        String environmentId,
        String region,
        String purpose,
        CapabilitySnapshot.Ownership ownership,
        CapabilitySnapshot.Lifecycle lifecycle,
        CapabilityContract.DataClassification classification,
        List<String> allowedRegions,
        boolean payloadRetentionAllowed,
        String approvedBy,
        Instant approvedAt,
        Instant expiresAt,
        Instant createdAt
) {
    /** Validates explicit governance coordinates. */
    public CapabilityProjectionContext {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        tenantId = required(tenantId, "tenantId");
        organizationId = required(organizationId, "organizationId");
        projectId = projectId == null ? "" : projectId.trim();
        environmentId = required(environmentId, "environmentId");
        region = region == null ? "" : region.trim();
        purpose = required(purpose, "purpose");
        ownership = ownership == null ? CapabilitySnapshot.Ownership.unassigned() : ownership;
        lifecycle = lifecycle == null ? CapabilitySnapshot.Lifecycle.DRAFT : lifecycle;
        classification = classification == null
                ? CapabilityContract.DataClassification.RESTRICTED : classification;
        allowedRegions = allowedRegions == null ? List.of() : allowedRegions.stream()
                .map(value -> value == null ? "" : value.trim()).filter(value -> !value.isEmpty())
                .distinct().sorted().toList();
        approvedBy = approvedBy == null ? "" : approvedBy.trim();
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        if ((approvedAt == null) != approvedBy.isBlank()) {
            throw new IllegalArgumentException("approvedBy and approvedAt must be supplied together");
        }
        if ((lifecycle == CapabilitySnapshot.Lifecycle.REVIEWED
                || lifecycle == CapabilitySnapshot.Lifecycle.ACTIVE) && approvedBy.isBlank()) {
            throw new IllegalArgumentException("reviewed and active projections require approval coordinates");
        }
        if (lifecycle == CapabilitySnapshot.Lifecycle.DRAFT && !approvedBy.isBlank()) {
            throw new IllegalArgumentException("draft projections must not carry approval coordinates");
        }
        if (expiresAt != null && expiresAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("expiresAt must not precede createdAt");
        }
    }

    /** Builds owner-declared provenance for a direct registry projection. */
    public ArtifactProvenance ownerProvenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(), tenantId,
                purpose, null, null, null, null, List.of(), approvedBy, approvedAt, expiresAt, "");
    }

    /** @return exact enterprise namespace sealed into the projected snapshot */
    public CapabilitySnapshot.Scope scope() {
        return new CapabilitySnapshot.Scope(tenantId, organizationId, projectId, environmentId, region);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
