package com.leanowtech.bloge.gateway.testing.correctness.fixture;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactSchemaRef;

import java.time.Instant;

/** Payload-free durable receipt for an idempotent Fixture Owner approval. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FixtureApprovalReceipt(
        String schemaVersion,
        EnterpriseScope scope,
        String idempotencyKeyFingerprint,
        String requestFingerprint,
        ExactAssetRef fixtureAssetRef,
        ExactAssetRef materialRef,
        ExactSchemaRef schemaRef,
        String reviewCommentFingerprint,
        String actorId,
        Instant createdAt
) {
    public static final String SCHEMA_VERSION = "bloge.fixtureApprovalReceipt.v1";

    public FixtureApprovalReceipt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion) || scope == null || fixtureAssetRef == null
                || materialRef == null || schemaRef == null || createdAt == null
                || !"FIXTURE_ASSET".equals(fixtureAssetRef.kind())
                || !"FIXTURE_MATERIAL".equals(materialRef.kind())) {
            throw new IllegalArgumentException("Complete Fixture approval coordinates are required");
        }
        idempotencyKeyFingerprint = fingerprint(
                idempotencyKeyFingerprint, "idempotencyKeyFingerprint");
        requestFingerprint = fingerprint(requestFingerprint, "requestFingerprint");
        reviewCommentFingerprint = fingerprint(
                reviewCommentFingerprint, "reviewCommentFingerprint");
        actorId = required(actorId, "actorId");
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be an exact fingerprint");
        }
        return normalized;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
