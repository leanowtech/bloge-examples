package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSourceSnapshotRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.time.Instant;
import java.util.List;

/** Payload-free event proving that one exact denominator revision was frozen. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverageInventoryFrozen(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef inventoryRef,
        ExactTargetRef target,
        List<ExactSourceSnapshotRef> derivationSources,
        int obligationCount,
        int waivedCount,
        String reviewerId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "bloge.coverageInventoryFrozen.v1";

    public CoverageInventoryFrozen {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Inventory frozen schemaVersion");
        }
        eventId = required(eventId, "eventId");
        if (scope == null || inventoryRef == null || target == null) {
            throw new IllegalArgumentException("Complete Inventory frozen coordinates are required");
        }
        derivationSources = derivationSources == null ? List.of() : List.copyOf(derivationSources);
        if (obligationCount < 1 || waivedCount < 0 || waivedCount > obligationCount) {
            throw new IllegalArgumentException("Valid frozen denominator counts are required");
        }
        reviewerId = required(reviewerId, "reviewerId");
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt is required");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
