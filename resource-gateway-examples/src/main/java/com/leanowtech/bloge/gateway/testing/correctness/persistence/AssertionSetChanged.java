package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;

import java.time.Instant;

/** Payload-free Assertion Set change event used by readiness projections. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AssertionSetChanged(
        String schemaVersion,
        String eventId,
        EnterpriseScope scope,
        ExactAssetRef assertionSetRef,
        ExactTargetRef target,
        ExactAssetRef oracleRef,
        String lifecycle,
        int assertionCount,
        boolean compatibilitySupported,
        String evaluatorVersion,
        String actorId,
        Instant occurredAt
) {
    public static final String SCHEMA_VERSION = "bloge.assertionSetChanged.v1";

    public AssertionSetChanged {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Assertion Set event schemaVersion");
        }
        eventId = BusinessOracleChanged.required(eventId, "eventId");
        if (scope == null || assertionSetRef == null || target == null || oracleRef == null) {
            throw new IllegalArgumentException("Complete Assertion Set coordinates are required");
        }
        lifecycle = BusinessOracleChanged.required(lifecycle, "lifecycle");
        evaluatorVersion = evaluatorVersion == null ? "" : evaluatorVersion.trim();
        actorId = BusinessOracleChanged.required(actorId, "actorId");
        if (assertionCount < 0 || occurredAt == null) {
            throw new IllegalArgumentException("Valid Assertion Set event metadata is required");
        }
        if (compatibilitySupported && evaluatorVersion.isEmpty()) {
            throw new IllegalArgumentException("Supported Assertion Set requires evaluatorVersion");
        }
    }
}
