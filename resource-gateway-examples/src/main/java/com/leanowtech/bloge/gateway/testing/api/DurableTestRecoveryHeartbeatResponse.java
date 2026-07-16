package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;

import java.time.Instant;
import java.util.Objects;

/**
 * Payload-free public projection of one durable-recovery heartbeat result.
 *
 * @param schemaVersion heartbeat response protocol version
 * @param runId governed durable run identity
 * @param status resulting recovery lifecycle status
 * @param ownerId unchanged server-owned recovery owner
 * @param leaseEpoch unchanged positive ownership generation
 * @param revision successor control revision
 * @param leaseExpiresAt database-authority successor lease deadline
 * @param checkpointFingerprint exact successor control-checkpoint identity
 * @param idempotentReplay whether an earlier committed heartbeat result was replayed
 */
public record DurableTestRecoveryHeartbeatResponse(
        String schemaVersion,
        String runId,
        String status,
        String ownerId,
        long leaseEpoch,
        long revision,
        Instant leaseExpiresAt,
        String checkpointFingerprint,
        boolean idempotentReplay
) {
    /** Current public recovery-heartbeat response protocol. */
    public static final String SCHEMA_VERSION =
            "bloge.durableTestRecoveryHeartbeatResponse.v1";

    /** Requires a complete non-terminal successor fence and emits no internal dispatch material. */
    public DurableTestRecoveryHeartbeatResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        runId = normalized(runId);
        status = normalized(status);
        ownerId = normalized(ownerId);
        checkpointFingerprint = normalized(checkpointFingerprint);
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || runId.isBlank()
                || !DurableTestExecutionCheckpoint.Status.RESUMING.name().equals(status)
                || ownerId.isBlank()
                || leaseEpoch <= 0
                || revision < 0
                || checkpointFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "A complete RESUMING recovery-heartbeat result is required");
        }
    }

    /**
     * Projects the verified repository result without exposing its successor dispatch.
     *
     * @param result immutable heartbeat command result
     * @return payload-free successor fence
     */
    public static DurableTestRecoveryHeartbeatResponse from(
            DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult result) {
        Objects.requireNonNull(result, "result");
        DurableTestExecutionCheckpoint checkpoint = result.checkpoint();
        DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
        return new DurableTestRecoveryHeartbeatResponse(
                "", checkpoint.runId(), lifecycle.status().name(), lifecycle.ownerId(),
                lifecycle.leaseEpoch(), lifecycle.revision(), lifecycle.leaseExpiresAt(),
                checkpoint.checkpointFingerprint(), result.idempotentReplay());
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
