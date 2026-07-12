package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;

/** Current materialized retention state; lifecycle events remain the authoritative audit trail. */
public record VisualRunPayloadStatus(
        String schemaVersion,
        String runId,
        String tenantId,
        String namespace,
        String environment,
        String state,
        long revision,
        String activeHoldId,
        Instant updatedAt,
        VisualPayloadRetentionDescriptor descriptor,
        VisualPayloadLifecycleEvent latestEvent
) {
    public static final String SCHEMA_VERSION = "bloge.visualRunPayloadStatus.v1";
    public static final String AVAILABLE = "AVAILABLE";
    public static final String LEGAL_HOLD = "LEGAL_HOLD";
    public static final String PURGED = "PURGED";
    public static final String NOT_RETAINED = "NOT_RETAINED";

    public VisualRunPayloadStatus {
        schemaVersion = normalize(schemaVersion, SCHEMA_VERSION);
        runId = normalize(runId, "");
        tenantId = normalize(tenantId, "");
        namespace = normalize(namespace, "");
        environment = normalize(environment, "");
        state = normalize(state, NOT_RETAINED);
        revision = Math.max(1, revision);
        activeHoldId = normalize(activeHoldId, "");
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
        descriptor = descriptor == null ? VisualPayloadRetentionDescriptor.legacyInline() : descriptor;
    }

    public boolean readable() {
        return AVAILABLE.equals(state) || LEGAL_HOLD.equals(state);
    }

    public boolean expiredAt(Instant now) {
        Instant observedAt = now == null ? Instant.now() : now;
        return AVAILABLE.equals(state) && !descriptor.expiresAt().isAfter(observedAt);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
