package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Immutable persistence envelope for one Agent-TDD-only authoring asset.
 *
 * <p>Canonical libraries and graphs do not use this envelope: they remain in the existing Resource
 * Gateway registries. This type stores only the new overlays introduced by Agent TDD, such as
 * golden case sets, Agent instructions, proposals and execution evidence.</p>
 *
 * @param scopeKey server-derived tenant and environment scope
 * @param kind stable overlay kind
 * @param assetRef stable asset reference within the scope
 * @param revision monotonic stored revision
 * @param fingerprint canonical payload fingerprint
 * @param data immutable JSON payload
 * @param updatedAt server persistence timestamp
 */
public record AgentTddStoredAsset(
        String scopeKey,
        String kind,
        String assetRef,
        long revision,
        String fingerprint,
        JsonNode data,
        Instant updatedAt
) {
    /** Normalizes coordinates and requires a persisted payload. */
    public AgentTddStoredAsset {
        scopeKey = normalized(scopeKey);
        kind = normalized(kind);
        assetRef = normalized(assetRef);
        fingerprint = normalized(fingerprint);
        data = data == null ? null : data.deepCopy();
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        if (scopeKey.isBlank() || kind.isBlank() || assetRef.isBlank() || data == null) {
            throw new IllegalArgumentException("Stored Agent TDD assets require scope, kind, reference and data");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
