package com.leanowtech.bloge.graphengine.model;

import com.leanowtech.bloge.core.schema.SchemaDescriptor;

import java.time.Instant;

/**
 * Product-layer view of one external event a suspended graph instance is still waiting to receive.
 *
 * @param nodeId node currently awaiting the signal
 * @param eventName runtime event name matched by the durable event matcher
 * @param correlationKey correlation key field used to route the event
 * @param expectedValue expected correlation value for the waiting matcher
 * @param optional whether the runtime treats the waiting matcher as non-blocking
 * @param signalSchema optional aggregated payload schema declared by the compiled await node
 * @param waitingSince time the matcher entered the waiting state
 * @param timeoutAt optional wait timeout deadline
 */
public record GraphPendingSignal(
        String nodeId,
        String eventName,
        String correlationKey,
        String expectedValue,
        boolean optional,
        SchemaDescriptor signalSchema,
        Instant waitingSince,
        Instant timeoutAt
) {
    public GraphPendingSignal {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        if (eventName == null || eventName.isBlank()) {
            throw new IllegalArgumentException("eventName must not be blank");
        }
        if (correlationKey == null || correlationKey.isBlank()) {
            throw new IllegalArgumentException("correlationKey must not be blank");
        }
    }
}
