package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.time.Instant;

/** Payload-free proof that an exact Business Oracle revision was approved. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BusinessOracleApproved(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef oracleRef,
        ExactTargetRef target,
        String ownerId,
        int basisCount,
        String reviewerId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "bloge.businessOracleApproved.v1";

    public BusinessOracleApproved {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Oracle approved schemaVersion");
        }
        eventId = BusinessOracleChanged.required(eventId, "eventId");
        if (scope == null || oracleRef == null || target == null) {
            throw new IllegalArgumentException("Complete approved Oracle coordinates are required");
        }
        ownerId = BusinessOracleChanged.required(ownerId, "ownerId");
        reviewerId = BusinessOracleChanged.required(reviewerId, "reviewerId");
        if (basisCount < 1 || occurredAt == null) {
            throw new IllegalArgumentException("Approved Oracle basis and time are required");
        }
    }
}
