package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeFact;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Immutable fact emitted when an integration-visible aggregate changes. */
public record IntegrationChangeEvent(
        String schemaVersion,
        long streamSequence,
        String eventId,
        String eventType,
        String tenantId,
        String namespace,
        String environmentId,
        Aggregate aggregate,
        Instant occurredAt,
        Instant publishedAt,
        String payloadRef,
        String traceId,
        String eventFingerprint
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.integrationEvent.v1";
    public static final String GLOBAL_SCOPE = "*";

    public IntegrationChangeEvent {
        schemaVersion = normalize(schemaVersion).isBlank() ? SCHEMA_VERSION : normalize(schemaVersion);
        streamSequence = Math.max(0, streamSequence);
        eventId = normalize(eventId).isBlank() ? "evt-" + UUID.randomUUID() : normalize(eventId);
        eventType = normalize(eventType).toUpperCase();
        tenantId = scope(tenantId);
        namespace = normalize(namespace);
        environmentId = scope(environmentId);
        aggregate = aggregate == null ? Aggregate.empty() : aggregate;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        publishedAt = publishedAt == null ? occurredAt : publishedAt;
        payloadRef = normalize(payloadRef);
        traceId = normalize(traceId);
        eventFingerprint = normalize(eventFingerprint).isBlank()
                ? fingerprint(eventId, eventType, tenantId, namespace, environmentId, aggregate, occurredAt,
                payloadRef, traceId)
                : normalize(eventFingerprint);
    }

    public static IntegrationChangeEvent pending(String eventType,
                                                 String tenantId,
                                                 String namespace,
                                                 String environmentId,
                                                 Aggregate aggregate,
                                                 String payloadRef,
                                                 String traceId) {
        Instant now = Instant.now();
        return new IntegrationChangeEvent("", 0, "", eventType, tenantId, namespace, environmentId, aggregate,
                now, now, payloadRef, traceId, "");
    }

    public static IntegrationChangeEvent from(VisualChangeFact fact) {
        if (fact == null) {
            throw new IllegalArgumentException("Visual change fact is required");
        }
        VisualChangeFact.Aggregate source = fact.aggregate();
        return new IntegrationChangeEvent("", 0, "", fact.eventType(), fact.tenantId(), fact.namespace(),
                fact.environmentId(), new Aggregate(source.kind(), source.id(), source.sequence(),
                source.fingerprint()), fact.occurredAt(), Instant.now(), fact.payloadRef(), fact.traceId(), "");
    }

    public IntegrationChangeEvent withStreamSequence(long sequence) {
        return new IntegrationChangeEvent(schemaVersion, sequence, eventId, eventType, tenantId, namespace,
                environmentId, aggregate, occurredAt, publishedAt, payloadRef, traceId, eventFingerprint);
    }

    public boolean fingerprintVerified() {
        return eventFingerprint.equals(fingerprint(eventId, eventType, tenantId, namespace, environmentId,
                aggregate, occurredAt, payloadRef, traceId));
    }

    private static String fingerprint(String eventId,
                                      String eventType,
                                      String tenantId,
                                      String namespace,
                                      String environmentId,
                                      Aggregate aggregate,
                                      Instant occurredAt,
                                      String payloadRef,
                                      String traceId) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("eventId", eventId);
        material.put("eventType", eventType);
        material.put("tenantId", tenantId);
        material.put("namespace", namespace);
        material.put("environmentId", environmentId);
        material.put("aggregate", aggregate);
        material.put("occurredAt", occurredAt);
        material.put("payloadRef", payloadRef);
        material.put("traceId", traceId);
        return VisualBundleFingerprint.fromMaterial(material);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String scope(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? GLOBAL_SCOPE : normalized;
    }

    /** Aggregate-local ordering and immutable content identity. */
    public record Aggregate(String kind, String id, long sequence, String fingerprint) {
        public Aggregate {
            kind = normalize(kind).toUpperCase();
            id = normalize(id);
            sequence = Math.max(0, sequence);
            fingerprint = normalize(fingerprint);
        }

        static Aggregate empty() {
            return new Aggregate("", "", 0, "");
        }
    }
}
