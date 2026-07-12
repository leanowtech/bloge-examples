package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.util.LinkedHashMap;

/** Append-only signed transition in a run payload's retention lifecycle. */
public record VisualPayloadLifecycleEvent(
        String schemaVersion,
        String eventId,
        String requestId,
        String runId,
        long revision,
        String type,
        Instant occurredAt,
        String actorId,
        String reason,
        String holdId,
        String payloadFingerprint,
        String previousEventFingerprint,
        VisualRunEvidenceSeal evidenceSeal
) {
    public static final String SCHEMA_VERSION = "bloge.visualPayloadLifecycleEvent.v1";
    public static final String CAPTURED = "CAPTURED";
    public static final String NOT_RETAINED = "NOT_RETAINED";
    public static final String HOLD_PLACED = "HOLD_PLACED";
    public static final String HOLD_RELEASED = "HOLD_RELEASED";
    public static final String PURGED = "PURGED";

    public VisualPayloadLifecycleEvent {
        schemaVersion = normalize(schemaVersion, SCHEMA_VERSION);
        eventId = normalize(eventId, "");
        requestId = normalize(requestId, "");
        runId = normalize(runId, "");
        revision = Math.max(1, revision);
        type = normalize(type, "UNKNOWN");
        occurredAt = occurredAt == null ? Instant.EPOCH : occurredAt;
        actorId = normalize(actorId, "");
        reason = normalize(reason, "");
        holdId = normalize(holdId, "");
        payloadFingerprint = normalize(payloadFingerprint, "");
        previousEventFingerprint = normalize(previousEventFingerprint, "");
        evidenceSeal = evidenceSeal == null ? VisualRunEvidenceSeal.unsigned() : evidenceSeal;
    }

    public String eventFingerprint() {
        LinkedHashMap<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("eventId", eventId);
        material.put("requestId", requestId);
        material.put("runId", runId);
        material.put("revision", revision);
        material.put("type", type);
        material.put("occurredAt", occurredAt);
        material.put("actorId", actorId);
        material.put("reason", reason);
        material.put("holdId", holdId);
        material.put("payloadFingerprint", payloadFingerprint);
        material.put("previousEventFingerprint", previousEventFingerprint);
        return VisualBundleFingerprint.fromMaterial(material);
    }

    public VisualPayloadLifecycleEvent withEvidenceSeal(VisualRunEvidenceSeal seal) {
        return new VisualPayloadLifecycleEvent(schemaVersion, eventId, requestId, runId, revision, type, occurredAt,
                actorId, reason, holdId, payloadFingerprint, previousEventFingerprint, seal);
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
