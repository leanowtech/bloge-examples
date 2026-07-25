package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Signed retention, multi-hold, and logical-deletion boundary for Scenario batch evidence.
 */
public interface ScenarioRehearsalBatchRetentionRepository {
    /** Pre-signed registration material produced outside the terminal database transaction. */
    record PreparedRegistration(
            String bundleFingerprint,
            Instant retainUntil,
            ScenarioRehearsalBatchRetentionEvent event
    ) {
        /** Enforces exact registration-event shape. */
        public PreparedRegistration {
            bundleFingerprint = required(
                    bundleFingerprint, "bundleFingerprint");
            retainUntil = java.util.Objects.requireNonNull(
                    retainUntil, "retainUntil");
            event = java.util.Objects.requireNonNull(
                    event, "event");
            if (event.type()
                    != ScenarioRehearsalBatchRetentionEvent.Type
                    .RETENTION_REGISTERED
                    || event.revision() != 1
                    || !event.retainUntil().equals(retainUntil)
                    || !event.evidenceBundleFingerprint().equals(
                    bundleFingerprint)
                    || !event.evidenceSeal().signed()) {
                throw new IllegalArgumentException(
                        "Scenario batch prepared retention registration is inconsistent");
            }
        }
    }

    /** Registers one newly committed terminal batch under its immutable retention floor. */
    ScenarioRehearsalBatchRetentionState register(
            ScenarioRehearsalBatchEvidenceBundle bundle,
            Instant retainUntil);

    /**
     * Signs deterministic registration material outside the terminal database transaction.
     *
     * @param bundle independently verified terminal batch evidence
     * @param retainUntil immutable minimum retention boundary
     * @param occurredAt database time frozen by the finalization claim
     * @param signingRequestId stable KMS idempotency identity
     * @return exact pre-signed registration material
     */
    PreparedRegistration prepareRegistration(
            ScenarioRehearsalBatchEvidenceBundle bundle,
            Instant retainUntil,
            Instant occurredAt,
            String signingRequestId);

    /** Atomically registers already signed material after exact evidence persistence. */
    ScenarioRehearsalBatchRetentionState register(
            ScenarioRehearsalBatchEvidenceBundle bundle,
            PreparedRegistration prepared);

    /** Places one independent legal hold using an idempotent governance command. */
    ScenarioRehearsalBatchRetentionState placeHold(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String commandId,
            String holdId,
            String actorId,
            String reasonCode);

    /** Releases one exact active legal hold without affecting any other hold. */
    ScenarioRehearsalBatchRetentionState releaseHold(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String commandId,
            String holdId,
            String actorId,
            String reasonCode);

    /**
     * Deletes the batch job, items, and batch evidence while preserving child evidence and audits.
     *
     * @return purged state whose latest signed event is the logical-deletion proof
     */
    ScenarioRehearsalBatchRetentionState purge(
            CapabilitySnapshot.Scope scope,
            String jobId,
            String commandId,
            String actorId,
            String reasonCode);

    /** Finds and verifies one exact batch-retention projection. */
    Optional<ScenarioRehearsalBatchRetentionState> find(
            CapabilitySnapshot.Scope scope,
            String jobId);

    /** Reads and verifies the complete signed lifecycle event chain. */
    List<ScenarioRehearsalBatchRetentionEvent> events(
            CapabilitySnapshot.Scope scope,
            String jobId);

    private static String required(
            String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }
}
