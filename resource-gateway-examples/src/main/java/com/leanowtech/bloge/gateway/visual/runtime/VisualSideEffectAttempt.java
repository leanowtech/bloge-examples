package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Sanitized, evidence-safe lifecycle of one external side-effect attempt. */
public record VisualSideEffectAttempt(String attemptId, Request request, String outcome,
                                      Receipt receipt, List<Transition> transitions) {
    public VisualSideEffectAttempt {
        attemptId = normalize(attemptId);
        outcome = normalize(outcome).isBlank()
                ? "UNKNOWN_COMMIT" : normalize(outcome).toUpperCase(Locale.ROOT);
        transitions = transitions == null ? List.of() : List.copyOf(transitions);
    }

    public record Request(String operationRef, String idempotencyKeyFingerprint, String reconcilerRef,
                          String reconciliationLookupRef, Instant startedAt, int retryAttempt) {
        public Request {
            operationRef = normalize(operationRef);
            idempotencyKeyFingerprint = normalize(idempotencyKeyFingerprint);
            reconcilerRef = normalize(reconcilerRef);
            reconciliationLookupRef = normalize(reconciliationLookupRef);
            startedAt = startedAt == null ? Instant.EPOCH : startedAt;
            retryAttempt = Math.max(0, retryAttempt);
        }

        public boolean reconcilable() {
            return !reconcilerRef.isBlank() && !reconciliationLookupRef.isBlank();
        }
    }

    public record Receipt(String receiptId, String provider, String transactionRef,
                          Instant committedAt, Proof proof) {
        public Receipt {
            receiptId = normalize(receiptId);
            provider = normalize(provider);
            transactionRef = normalize(transactionRef);
            committedAt = committedAt == null ? Instant.EPOCH : committedAt;
            proof = proof == null ? new Proof("", "") : proof;
        }
    }

    public record Proof(String reference, String fingerprint) {
        public Proof {
            reference = normalize(reference);
            fingerprint = normalize(fingerprint);
        }
    }

    public record Transition(int sequence, String outcome, Instant observedAt,
                             String reasonCode, Receipt receipt) {
        public Transition {
            sequence = Math.max(1, sequence);
            outcome = normalize(outcome).isBlank()
                    ? "UNKNOWN_COMMIT" : normalize(outcome).toUpperCase(Locale.ROOT);
            observedAt = observedAt == null ? Instant.EPOCH : observedAt;
            reasonCode = normalize(reasonCode);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
