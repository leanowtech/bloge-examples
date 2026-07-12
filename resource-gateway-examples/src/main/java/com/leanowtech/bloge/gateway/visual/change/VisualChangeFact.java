package com.leanowtech.bloge.gateway.visual.change;

import java.time.Instant;

/** Protocol-neutral fact emitted by the reusable visual control plane. */
public record VisualChangeFact(
        String eventType,
        String tenantId,
        String namespace,
        String environmentId,
        Aggregate aggregate,
        Instant occurredAt,
        String payloadRef,
        String traceId
) {
    public static final String GLOBAL_SCOPE = "*";

    public VisualChangeFact {
        eventType = normalize(eventType).toUpperCase();
        tenantId = scope(tenantId);
        namespace = normalize(namespace);
        environmentId = scope(environmentId);
        aggregate = aggregate == null ? Aggregate.empty() : aggregate;
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        payloadRef = normalize(payloadRef);
        traceId = normalize(traceId);
    }

    public VisualChangeFact(String eventType,
                            String tenantId,
                            String namespace,
                            String environmentId,
                            Aggregate aggregate,
                            String payloadRef,
                            String traceId) {
        this(eventType, tenantId, namespace, environmentId, aggregate, null, payloadRef, traceId);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String scope(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? GLOBAL_SCOPE : normalized;
    }

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
