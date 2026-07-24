package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Signed retention, multi-hold, and logical-deletion boundary for Scenario batch evidence.
 */
public interface ScenarioRehearsalBatchRetentionRepository {
    /** Registers one newly committed terminal batch under its immutable retention floor. */
    ScenarioRehearsalBatchRetentionState register(
            ScenarioRehearsalBatchEvidenceBundle bundle,
            Instant retainUntil);

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
}
