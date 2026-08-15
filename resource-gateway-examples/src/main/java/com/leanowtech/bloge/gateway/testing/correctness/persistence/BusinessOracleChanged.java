package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.time.Instant;

/** Payload-free Oracle change event used to invalidate review and Case projections. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BusinessOracleChanged(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef oracleRef,
        ExactTargetRef target,
        String lifecycle,
        String ownerId,
        int basisCount,
        int assertionSetCount,
        String actorId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "bloge.businessOracleChanged.v1";

    public BusinessOracleChanged {
        schemaVersion = version(schemaVersion);
        eventId = required(eventId, "eventId");
        if (scope == null || oracleRef == null || target == null) {
            throw new IllegalArgumentException("Complete Oracle coordinates are required");
        }
        lifecycle = required(lifecycle, "lifecycle");
        ownerId = required(ownerId, "ownerId");
        actorId = required(actorId, "actorId");
        if (basisCount < 0 || assertionSetCount < 0 || occurredAt == null) {
            throw new IllegalArgumentException("Valid Oracle event metadata is required");
        }
    }

    static String version(String value) {
        String normalized = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported Oracle changed schemaVersion");
        }
        return normalized;
    }

    static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
