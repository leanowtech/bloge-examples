package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding;

import java.util.List;
import java.util.Optional;

/**
 * Durable seam between the authoring coordinator and external secret providers.
 *
 * <p>Every mutation is expected to join the coordinator's outer database transaction;
 * provider I/O must happen before that transaction is committed, never inside JDBC.
 * The in-memory implementation is a reference for the protocol and intentionally does
 * not pretend to provide database transactions.</p>
 */
public interface PendingSecretStore {
    /** Atomically stages every slot, or rejects a partial, drifted, or expired batch. */
    void stage(PendingSecretBatch batch);

    /** Finds one exact complete lease, including child CAS and coordinate, never a nearby retry. */
    Optional<PendingSecretBatch> findExact(PendingSecretLease lease);

    /**
     * Validates activation outputs for the exact current batch without changing
     * pending or active state, and returns the proof commit will later return.
     */
    FinalizedSecretSlots prepareFinalization(PendingSecretBatch batch, List<ActivatedSecretSlot> activated);

    /** Commits all activation outputs atomically and clears the pending batch. */
    FinalizedSecretSlots commitBindings(PendingSecretBatch batch, List<ActivatedSecretSlot> activated);

    /** Alias emphasizing that this is the local activation finalization boundary. */
    default FinalizedSecretSlots finalizeActivation(PendingSecretBatch batch, List<ActivatedSecretSlot> activated) {
        return commitBindings(batch, activated);
    }

    /** Marks an exact staged or activated batch for provider compensation. */
    void markAbortRequired(PendingSecretLease lease);

    /** Claims complete expired batches and existing abort claims atomically, bounded by batches. */
    List<SecretAbortCandidate> claimRecoveryDue(int attemptLimit);

    /** Completes one exact recovery claim; repeated completion of the same claim is idempotent. */
    void completeAbort(SecretAbortCandidate candidate);

    /** Looks up only the exact authority coordinate and slot. */
    Optional<ActiveSecretBinding> findActive(ConnectionRevisionCoordinate coordinate, String slot);
}
