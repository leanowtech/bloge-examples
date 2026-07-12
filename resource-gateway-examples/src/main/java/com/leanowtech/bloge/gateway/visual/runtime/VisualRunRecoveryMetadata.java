package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;

/** Immutable provenance for a run record synthesized from a durable recovery reservation. */
public record VisualRunRecoveryMetadata(
        String schemaVersion,
        String mode,
        String reservationId,
        String reservationFingerprint,
        Instant recoveredAt,
        long controlRevision,
        int attempt,
        String triggerReason
) {
    public static final String SCHEMA_VERSION = "bloge.visualRunRecoveryMetadata.v1";
    public static final String MODE_NONE = "NONE";
    public static final String MODE_OWNER_ABANDONED = "OWNER_ABANDONED";
    public static final String MODE_TERMINAL_EVIDENCE_GAP = "TERMINAL_EVIDENCE_GAP";
    public static final String MODE_CONTROL_MISSING = "CONTROL_MISSING";

    public VisualRunRecoveryMetadata {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        mode = normalize(mode, MODE_NONE);
        reservationId = reservationId == null ? "" : reservationId.trim();
        reservationFingerprint = reservationFingerprint == null ? "" : reservationFingerprint.trim();
        recoveredAt = recoveredAt == null ? Instant.EPOCH : recoveredAt;
        controlRevision = Math.max(0, controlRevision);
        attempt = Math.max(0, attempt);
        triggerReason = normalize(triggerReason, "NONE");
    }

    public static VisualRunRecoveryMetadata none() {
        return new VisualRunRecoveryMetadata("", MODE_NONE, "", "", Instant.EPOCH, 0, 0, "NONE");
    }

    public boolean recovered() {
        return !MODE_NONE.equals(mode);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank()
                ? fallback
                : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
