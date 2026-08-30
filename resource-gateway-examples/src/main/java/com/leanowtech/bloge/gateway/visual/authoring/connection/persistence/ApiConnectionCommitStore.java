package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

import java.util.Optional;

/**
 * Persistence boundary for staged and atomically committed Connection metadata.
 * A production adapter should join the outer transaction and persist pending
 * secret leases through a future {@code PendingSecretStore}; this contract
 * deliberately does not claim or resolve secrets itself.
 */
public interface ApiConnectionCommitStore {
    /** Stages an invisible revision after applying pure authority decisions. */
    StagedApiConnection stage(CommandLease lease, String connectionId, ApiConnectionCommand command,
                              PreparedSecretBinding... prepared);

    /** Promotes the exact live stage and returns its committed payload-free view. */
    StoredApiConnection commit(CommandLease lease);

    /** Fenced failure removes only the exact live stage; stale failures are no-ops. */
    void fail(CommandLease lease);

    /** Reads the committed head in the exact authoring scope. */
    Optional<StoredApiConnection> findHead(AuthoringScope scope, String connectionId);

    /** Reads one committed historical revision in the exact authoring scope. */
    Optional<StoredApiConnection> findRevision(AuthoringScope scope, String connectionId, long revision);
}
