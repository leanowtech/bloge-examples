package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;

import java.time.Instant;

/** Payload-free Fixture descriptor event used for usage and stale projection repair. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FixtureAssetChanged(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef fixtureAssetRef,
        ExactAssetRef materialRef,
        String schemaId,
        String schemaFingerprint,
        String lifecycle,
        String classification,
        String actorId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "bloge.fixtureAssetChanged.v1";

    public FixtureAssetChanged {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion) || scope == null || fixtureAssetRef == null
                || materialRef == null || occurredAt == null) {
            throw new IllegalArgumentException("Complete Fixture change coordinates are required");
        }
        eventId = required(eventId, "eventId");
        schemaId = required(schemaId, "schemaId");
        schemaFingerprint = fingerprint(schemaFingerprint);
        lifecycle = required(lifecycle, "lifecycle");
        classification = required(classification, "classification");
        actorId = required(actorId, "actorId");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String fingerprint(String value) {
        String normalized = required(value, "fingerprint");
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact fingerprint is required");
        }
        return normalized;
    }
}
