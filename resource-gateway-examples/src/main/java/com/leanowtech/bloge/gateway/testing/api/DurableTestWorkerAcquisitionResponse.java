package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;

import java.time.Instant;
import java.util.Objects;

/**
 * Payload-free immutable outcome of one non-blocking durable worker pull.
 *
 * @param schemaVersion response protocol version
 * @param outcome {@code ACQUIRED} or bounded-observation {@code NO_WORK}
 * @param observedAt database-authority linearization time
 * @param assignment exact public recovery fence, present only for {@code ACQUIRED}
 * @param idempotentReplay whether this is the original committed result replayed
 */
public record DurableTestWorkerAcquisitionResponse(
        String schemaVersion,
        String outcome,
        Instant observedAt,
        Assignment assignment,
        boolean idempotentReplay) {

    /** Current worker pull response protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.durableTestWorkerAcquisitionResponse.v1";

    /** Enforces mutually exclusive acquired and no-work public shapes. */
    public DurableTestWorkerAcquisitionResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        outcome = normalized(outcome);
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (("ACQUIRED".equals(outcome)) != (assignment != null)
                || !("ACQUIRED".equals(outcome) || "NO_WORK".equals(outcome))) {
            throw new IllegalArgumentException(
                    "Worker acquisition response must be ACQUIRED with an assignment or NO_WORK");
        }
    }

    /** Projects an integrity-verified result without exposing the hidden dispatch. */
    public static DurableTestWorkerAcquisitionResponse from(
            DurableTestExecutionCheckpointRepository.WorkerAcquisitionResult result) {
        Objects.requireNonNull(result, "result");
        Assignment assignment = result.checkpoint() == null
                ? null : Assignment.from(result.checkpoint());
        return new DurableTestWorkerAcquisitionResponse(
                "", result.outcome().name(), result.observedAt(), assignment,
                result.idempotentReplay());
    }

    /**
     * Exact public fence used by heartbeat and terminal-recovery control calls.
     *
     * @param runId governed durable run identity
     * @param status resulting lifecycle state
     * @param ownerId server-selected worker owner
     * @param leaseEpoch positive ownership generation
     * @param revision resulting control revision
     * @param leaseExpiresAt database-authority lease expiry
     * @param checkpointFingerprint exact resulting checkpoint closure
     * @param target payload-free graph or operator target
     */
    public record Assignment(
            String runId,
            String status,
            String ownerId,
            long leaseEpoch,
            long revision,
            Instant leaseExpiresAt,
            String checkpointFingerprint,
            Target target) {

        /** Requires a complete recovery fence and exact target locator. */
        public Assignment {
            runId = normalized(runId);
            status = normalized(status);
            ownerId = normalized(ownerId);
            checkpointFingerprint = normalized(checkpointFingerprint);
            leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
            target = Objects.requireNonNull(target, "target");
            if (runId.isBlank() || !"RESUMING".equals(status) || ownerId.isBlank()
                    || leaseEpoch <= 0 || revision < 0 || checkpointFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "Worker assignment requires a complete RESUMING fence");
            }
        }

        private static Assignment from(DurableTestExecutionCheckpoint checkpoint) {
            DurableTestExecutionCheckpoint.ExecutionTargetRef target =
                    checkpoint.dependencies().target();
            if (target == null) {
                throw new IllegalArgumentException(
                        "Worker acquisition cannot expose a legacy target-less checkpoint");
            }
            DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
            return new Assignment(
                    checkpoint.runId(), lifecycle.status().name(), lifecycle.ownerId(),
                    lifecycle.leaseEpoch(), lifecycle.revision(), lifecycle.leaseExpiresAt(),
                    checkpoint.checkpointFingerprint(),
                    new Target(target.kind(), target.id(), target.fingerprint()));
        }
    }

    /** Payload-free target locator for diagnostics and deep links. */
    public record Target(String kind, String id, String fingerprint) {
        /** Normalizes an already integrity-verified target projection. */
        public Target {
            kind = normalized(kind);
            id = normalized(id);
            fingerprint = normalized(fingerprint);
            if (kind.isBlank() || id.isBlank() || fingerprint.isBlank()) {
                throw new IllegalArgumentException("A complete worker target is required");
            }
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
