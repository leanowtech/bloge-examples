package com.leanowtech.bloge.gateway.integration;

import java.time.Instant;

/** Credential-free security audit fact emitted by integration authentication. */
public record IntegrationAccessAuditRecord(
        long sequence,
        Instant occurredAt,
        String correlationId,
        String identityId,
        String tenantId,
        String environmentId,
        String operation,
        String purpose,
        String outcome,
        String reasonCode
) {
    public IntegrationAccessAuditRecord {
        sequence = Math.max(0, sequence);
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        correlationId = normalize(correlationId);
        identityId = normalize(identityId);
        tenantId = normalize(tenantId);
        environmentId = normalize(environmentId);
        operation = normalize(operation).toUpperCase();
        purpose = normalize(purpose).toUpperCase();
        outcome = normalize(outcome).toUpperCase();
        reasonCode = normalize(reasonCode);
    }

    public IntegrationAccessAuditRecord withSequence(long value) {
        return new IntegrationAccessAuditRecord(value, occurredAt, correlationId, identityId, tenantId,
                environmentId, operation, purpose, outcome, reasonCode);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
