package com.leanowtech.bloge.gateway.testing.api;

import java.time.Instant;
import java.util.Map;

/** Credential-free security fact for attempted test-control boundary violations. */
public record TestSecurityEvent(
        long sequence,
        Instant occurredAt,
        String correlationId,
        String tenantId,
        String environmentId,
        String actorId,
        String eventType,
        String outcome,
        String reasonCode,
        Map<String, Object> facts
) {
    public TestSecurityEvent {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
    }

    public TestSecurityEvent withSequence(long value) {
        return new TestSecurityEvent(value, occurredAt, correlationId, tenantId, environmentId,
                actorId, eventType, outcome, reasonCode, facts);
    }
}
