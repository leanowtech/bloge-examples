package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;

import java.time.Instant;
import java.util.Objects;

/**
 * Payload-free outcome of one durable test execution owner claim.
 *
 * @param schemaVersion response protocol version
 * @param runId governed durable run identity
 * @param status resulting durable lifecycle status
 * @param ownerId server-selected recovery owner required for the next fence
 * @param leaseEpoch resulting positive lease generation
 * @param revision resulting checkpoint revision
 * @param leaseExpiresAt database-authority lease expiry
 * @param checkpointFingerprint exact resulting control closure
 * @param target payload-free graph or operator locator
 * @param idempotentReplay whether this response replays an earlier committed command result
 */
public record DurableTestOwnerClaimResponse(
        String schemaVersion,
        String runId,
        String status,
        String ownerId,
        long leaseEpoch,
        long revision,
        Instant leaseExpiresAt,
        String checkpointFingerprint,
        Target target,
        boolean idempotentReplay
) {
    /** Current owner-claim response protocol version. */
    public static final String SCHEMA_VERSION = "bloge.durableTestOwnerClaimResponse.v1";

    /** Creates a complete immutable payload-free response. */
    public DurableTestOwnerClaimResponse {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        runId = normalized(runId);
        status = normalized(status);
        ownerId = normalized(ownerId);
        checkpointFingerprint = normalized(checkpointFingerprint);
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        target = Objects.requireNonNull(target, "target");
        if (runId.isBlank() || ownerId.isBlank() || leaseEpoch <= 0 || revision < 0
                || checkpointFingerprint.isBlank()) {
            throw new IllegalArgumentException("A complete durable owner-claim result is required");
        }
    }

    /**
     * Creates a public projection from an integrity-verified command result.
     *
     * @param result immutable repository command result
     * @return payload-free owner-claim response
     */
    public static DurableTestOwnerClaimResponse from(
            DurableTestExecutionCheckpointRepository.LeaseClaimResult result) {
        Objects.requireNonNull(result, "result");
        DurableTestExecutionCheckpoint checkpoint = result.checkpoint();
        DurableTestExecutionCheckpoint.ExecutionTargetRef target = checkpoint.dependencies().target();
        if (target == null) {
            throw new IllegalArgumentException("A public owner claim requires a v2 execution target");
        }
        DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
        return new DurableTestOwnerClaimResponse("", checkpoint.runId(), lifecycle.status().name(),
                lifecycle.ownerId(), lifecycle.leaseEpoch(), lifecycle.revision(),
                lifecycle.leaseExpiresAt(), checkpoint.checkpointFingerprint(),
                new Target(target.kind(), target.id(), target.fingerprint()),
                result.idempotentReplay());
    }

    /**
     * Payload-free target locator needed for diagnostics and future deep links.
     *
     * @param kind graph or operator target kind
     * @param id stable target id
     * @param fingerprint exact frozen target content identity
     */
    public record Target(String kind, String id, String fingerprint) {
        /** Normalizes the already verified target projection. */
        public Target {
            kind = normalized(kind);
            id = normalized(id);
            fingerprint = normalized(fingerprint);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
