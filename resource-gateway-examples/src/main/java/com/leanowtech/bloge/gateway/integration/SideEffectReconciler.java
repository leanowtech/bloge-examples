package com.leanowtech.bloge.gateway.integration;

import java.time.Instant;

/** Provider adapter that resolves an opaque side-effect lookup reference without replaying the write. */
public interface SideEffectReconciler {

    String reconcilerRef();

    Resolution reconcile(Query query) throws Exception;

    record Query(Base base, Attempt attempt, Scope scope, String correlationId) {
        public Query {
            correlationId = normalize(correlationId);
        }
    }

    record Base(String runId, String evidenceId, String evidenceFingerprint) {
        public Base {
            runId = normalize(runId);
            evidenceId = normalize(evidenceId);
            evidenceFingerprint = normalize(evidenceFingerprint);
        }
    }

    record Attempt(String nodeId, String attemptId, String attemptFingerprint,
                   String operationRef, String idempotencyKeyFingerprint,
                   String reconciliationLookupRef) {
        public Attempt {
            nodeId = normalize(nodeId);
            attemptId = normalize(attemptId);
            attemptFingerprint = normalize(attemptFingerprint);
            operationRef = normalize(operationRef);
            idempotencyKeyFingerprint = normalize(idempotencyKeyFingerprint);
            reconciliationLookupRef = normalize(reconciliationLookupRef);
        }
    }

    record Scope(String tenantId, String namespace, String environmentId) {
        public Scope {
            tenantId = normalize(tenantId);
            namespace = normalize(namespace);
            environmentId = normalize(environmentId);
        }
    }

    record Resolution(String outcome, RunEvidenceBundle.SideEffectReceipt receipt,
                      String reasonCode, Instant observedAt) {
        public Resolution {
            outcome = normalize(outcome).toUpperCase(java.util.Locale.ROOT);
            if (!java.util.Set.of("COMMITTED", "NOT_COMMITTED").contains(outcome)) {
                throw new IllegalArgumentException("Reconciliation outcome must be COMMITTED or NOT_COMMITTED");
            }
            if ("COMMITTED".equals(outcome) && receipt == null) {
                throw new IllegalArgumentException("COMMITTED reconciliation requires a receipt");
            }
            reasonCode = normalize(reasonCode).toUpperCase(java.util.Locale.ROOT);
            if (reasonCode.isBlank()) {
                throw new IllegalArgumentException("Reconciliation reasonCode is required");
            }
            observedAt = observedAt == null ? Instant.now() : observedAt;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
