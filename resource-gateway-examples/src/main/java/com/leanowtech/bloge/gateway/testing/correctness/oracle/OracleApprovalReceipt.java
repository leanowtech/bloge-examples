package com.leanowtech.bloge.gateway.testing.correctness.oracle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;

import java.time.Instant;

/** Payload-free durable receipt for an idempotent Business Oracle approval command. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OracleApprovalReceipt(
        String schemaVersion,
        EnterpriseScope scope,
        String idempotencyKeyFingerprint,
        String requestFingerprint,
        ExactAssetRef oracleRef,
        int basisCount,
        String actorId,
        Instant createdAt
) {
    public static final String SCHEMA_VERSION = "bloge.oracleApprovalReceipt.v1";

    public OracleApprovalReceipt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Oracle approval receipt schemaVersion");
        }
        if (scope == null || oracleRef == null) {
            throw new IllegalArgumentException("Complete Oracle approval coordinates are required");
        }
        idempotencyKeyFingerprint = fingerprint(
                idempotencyKeyFingerprint, "idempotencyKeyFingerprint");
        requestFingerprint = fingerprint(requestFingerprint, "requestFingerprint");
        if (!"ORACLE".equals(oracleRef.kind())) {
            throw new IllegalArgumentException("Oracle approval result must be an ORACLE ref");
        }
        if (basisCount < 1) throw new IllegalArgumentException("basisCount must be positive");
        actorId = required(actorId, "actorId");
        if (createdAt == null) throw new IllegalArgumentException("createdAt is required");
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
