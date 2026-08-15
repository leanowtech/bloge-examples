package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.time.Instant;

/** Payload-free draft change event used to invalidate Coverage projections. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverageInventoryChanged(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef inventoryRef,
        ExactTargetRef target,
        String lifecycle,
        int obligationCount,
        String actorId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "bloge.coverageInventoryChanged.v1";

    public CoverageInventoryChanged {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Inventory changed schemaVersion");
        }
        eventId = required(eventId, "eventId");
        if (scope == null || inventoryRef == null || target == null) {
            throw new IllegalArgumentException("Complete Inventory changed coordinates are required");
        }
        lifecycle = required(lifecycle, "lifecycle");
        actorId = required(actorId, "actorId");
        if (obligationCount < 0 || occurredAt == null) {
            throw new IllegalArgumentException("Valid Inventory changed metadata is required");
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
