package com.leanowtech.bloge.gateway.testing.correctness.coverage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;

import java.time.Instant;

/** Payload-free durable result of one idempotent Coverage freeze command. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CoverageFreezeReceipt(
        String schemaVersion,
        EnterpriseScope scope,
        String idempotencyKeyFingerprint,
        String requestFingerprint,
        ExactAssetRef inventoryRef,
        int obligationCount,
        int waivedCount,
        int retiredCount,
        String actorId,
        Instant createdAt
) {
    public static final String SCHEMA_VERSION = "bloge.coverageFreezeReceipt.v1";

    public CoverageFreezeReceipt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported Coverage freeze receipt schemaVersion");
        }
        if (scope == null || inventoryRef == null || createdAt == null
                || !exact(idempotencyKeyFingerprint) || !exact(requestFingerprint)) {
            throw new IllegalArgumentException("Complete Coverage freeze receipt coordinates are required");
        }
        actorId = actorId == null ? "" : actorId.trim();
        if (actorId.isEmpty() || obligationCount < 1 || waivedCount < 0 || retiredCount < 0
                || waivedCount + retiredCount > obligationCount) {
            throw new IllegalArgumentException("Valid Coverage freeze receipt metadata is required");
        }
    }

    private static boolean exact(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }
}
