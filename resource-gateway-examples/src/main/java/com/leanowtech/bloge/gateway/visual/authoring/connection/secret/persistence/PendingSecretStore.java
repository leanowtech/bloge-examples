package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

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

    /** Finds one exact command attempt and coordinate, never a nearby retry. */
    Optional<PendingSecretBatch> findExact(CommandLease lease, ConnectionRevisionCoordinate coordinate);

    /** Commits all activation outputs atomically and clears the pending batch. */
    void commitBindings(PendingSecretBatch batch, List<ActivatedSecretSlot> activated);

    /** Alias emphasizing that this is the local activation finalization boundary. */
    default void finalizeActivation(PendingSecretBatch batch, List<ActivatedSecretSlot> activated) {
        commitBindings(batch, activated);
    }

    /** Marks an exact staged or activated batch for provider compensation. */
    void markAbortRequired(PendingSecretLease lease);

    /** Returns complete expired/abort-required batches in stable command-batch order. */
    List<SecretAbortCandidate> findRecoveryDue(int commandLimit);

    /** Completes one exact abort candidate; repeated completion is idempotent. */
    void completeAbort(SecretAbortCandidate candidate);

    /** Looks up only the exact authority coordinate and slot. */
    Optional<ActiveSecretBinding> findActive(ConnectionRevisionCoordinate coordinate, String slot);
}
