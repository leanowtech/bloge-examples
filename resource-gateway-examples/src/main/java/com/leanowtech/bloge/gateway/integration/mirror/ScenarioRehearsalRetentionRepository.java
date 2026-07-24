package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Signed retention, multi-hold, and aggregate-deletion boundary for Scenario evidence.
 */
public interface ScenarioRehearsalRetentionRepository {
    /** Registers one newly committed signed aggregate under its minimum retention boundary. */
    ScenarioRehearsalRetentionState register(
            ScenarioRehearsalEvidenceBundle bundle,
            Instant retainUntil);

    /** Places one independent legal hold using an idempotent governance command. */
    ScenarioRehearsalRetentionState placeHold(
            CapabilitySnapshot.Scope scope,
            String runId,
            String commandId,
            String holdId,
            String actorId,
            String reasonCode);

    /** Releases one exact active legal hold without affecting other holds. */
    ScenarioRehearsalRetentionState releaseHold(
            CapabilitySnapshot.Scope scope,
            String runId,
            String commandId,
            String holdId,
            String actorId,
            String reasonCode);

    /**
     * Deletes aggregate evidence and case progress after retention while preserving child evidence.
     *
     * @return purged state whose latest signed event is the deletion proof
     */
    ScenarioRehearsalRetentionState purge(
            CapabilitySnapshot.Scope scope,
            String runId,
            String commandId,
            String actorId,
            String reasonCode);

    /** Finds and verifies one exact retention state. */
    Optional<ScenarioRehearsalRetentionState> find(
            CapabilitySnapshot.Scope scope,
            String runId);

    /** Reads and verifies the complete signed lifecycle event chain. */
    List<ScenarioRehearsalRetentionEvent> events(
            CapabilitySnapshot.Scope scope,
            String runId);
}
