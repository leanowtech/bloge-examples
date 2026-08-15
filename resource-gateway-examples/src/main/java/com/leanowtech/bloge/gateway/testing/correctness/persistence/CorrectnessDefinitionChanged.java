package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.time.Instant;

/** Payload-free outbox event used to invalidate and rebuild correctness projections. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CorrectnessDefinitionChanged(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef definitionRef,
        ExactTargetRef target,
        String lifecycle,
        String actorId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "bloge.correctnessDefinitionChanged.v1";

    public CorrectnessDefinitionChanged {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Definition changed schemaVersion");
        }
        if (eventId == null || eventId.isBlank() || scope == null || definitionRef == null
                || target == null || lifecycle == null || lifecycle.isBlank()
                || actorId == null || actorId.isBlank() || occurredAt == null) {
            throw new IllegalArgumentException("Complete Definition changed coordinates are required");
        }
        eventId = eventId.trim();
        lifecycle = lifecycle.trim();
        actorId = actorId.trim();
    }
}
