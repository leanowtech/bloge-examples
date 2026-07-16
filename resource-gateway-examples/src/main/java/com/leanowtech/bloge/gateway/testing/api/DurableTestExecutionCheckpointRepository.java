package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * Trusted persistence boundary for a durable test control closure and its engine-state mutation.
 *
 * <p>The mutation receives the transaction-bound test-runtime {@link JdbcTemplate}. Implementations
 * must commit or roll back the mutation and checkpoint row together. Callers must not perform
 * network I/O or use another datasource inside the callback: those effects cannot participate in
 * this local transaction.</p>
 */
public interface DurableTestExecutionCheckpointRepository {

    /** Creates revision zero and atomically writes the associated engine-state closure. */
    DurableTestExecutionCheckpoint create(DurableTestExecutionCheckpoint checkpoint,
                                            BoundEngineStateMutation engineStateMutation);

    /** Advances exactly one revision under the expected owner/epoch/revision fence. */
    DurableTestExecutionCheckpoint advance(DurableTestExecutionCheckpoint checkpoint,
                                             Fence expectedFence,
                                             BoundEngineStateMutation engineStateMutation);

    /** Resolves a run only within the verified tenant and environment scope. */
    Optional<DurableTestExecutionCheckpoint> find(String tenantId, String environmentId,
                                                   String runId);

    /** Resolves a BLOGE execution only within the verified tenant and environment scope. */
    Optional<DurableTestExecutionCheckpoint> findByEngineExecutionId(
            String tenantId, String environmentId, String engineExecutionId);

    /** Exact compare-and-set fence held by the caller. */
    record Fence(String ownerId, long leaseEpoch, long revision) {
        /** Rejects incomplete or impossible fence values. */
        public Fence {
            ownerId = ownerId == null ? "" : ownerId.trim();
            if (ownerId.isBlank() || leaseEpoch <= 0 || revision < 0) {
                throw new IllegalArgumentException("Complete owner, lease epoch, and revision are required");
            }
        }
    }

    /**
     * Engine mutation that declares the exact formal engine-state value covered by its writes.
     *
     * <p>Repositories validate this binding before opening a transaction. This prevents a caller
     * from committing one frozen BLOGE closure under another checkpoint reference, boundary, or
     * state version even when both values are individually well formed.</p>
     */
    interface BoundEngineStateMutation {
        /** @return exact BLOGE execution identity whose rows are mutated */
        String engineExecutionId();

        /** @return exact control-plane engine state represented by this mutation */
        DurableTestExecutionCheckpoint.EngineState engineState();

        /** Applies one idempotent engine-state transition through the transaction-bound JDBC facade. */
        void apply(JdbcTemplate jdbc);
    }
}
