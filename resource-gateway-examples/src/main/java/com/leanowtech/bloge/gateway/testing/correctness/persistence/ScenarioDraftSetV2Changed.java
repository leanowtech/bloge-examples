package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.time.Instant;

/** Payload-free Scenario v2 revision event for Matrix and fulfillment invalidation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScenarioDraftSetV2Changed(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef scenarioDraftSetRef,
        ExactTargetRef target,
        ExactAssetRef contractRef,
        int caseCount,
        int exploratoryCount,
        int reviewReadyCount,
        int canonicalCount,
        int retiredCount,
        String actorId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "bloge.scenarioDraftSetChanged.v2";

    public ScenarioDraftSetV2Changed {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Scenario v2 event schemaVersion");
        }
        eventId = required(eventId, "eventId");
        if (scope == null || scenarioDraftSetRef == null || target == null
                || contractRef == null) {
            throw new IllegalArgumentException("Complete Scenario v2 coordinates are required");
        }
        actorId = required(actorId, "actorId");
        if (caseCount < 0 || exploratoryCount < 0 || reviewReadyCount < 0
                || canonicalCount < 0 || retiredCount < 0
                || exploratoryCount + reviewReadyCount + canonicalCount + retiredCount
                        != caseCount
                || occurredAt == null) {
            throw new IllegalArgumentException("Scenario v2 event counts are invalid");
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
