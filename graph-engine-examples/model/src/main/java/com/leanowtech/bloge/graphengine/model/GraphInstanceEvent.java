package com.leanowtech.bloge.graphengine.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Simplified execution-event projection streamed by the instance SSE endpoint.
 *
 * @param instanceId      execution identifier that owns the event
 * @param sequenceNumber  monotonic per-instance sequence number
 * @param eventType       stable execution-event discriminator
 * @param timestamp       logical event timestamp
 * @param nodeId          node identifier when the event is node-scoped
 * @param operatorRef     operator reference when known
 * @param payloadJson     optional raw JSON payload associated with the event
 */
public record GraphInstanceEvent(
        String instanceId,
        long sequenceNumber,
        String eventType,
        Instant timestamp,
        String nodeId,
        String operatorRef,
        String payloadJson
) {
    public GraphInstanceEvent {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(timestamp, "timestamp");
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("sequenceNumber must be >= 1");
        }
    }
}
