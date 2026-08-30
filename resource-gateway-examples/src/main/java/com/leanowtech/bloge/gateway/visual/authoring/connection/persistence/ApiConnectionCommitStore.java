package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.ActivatedSecretSlot;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

import java.util.Optional;
import java.util.List;

/**
 * Persistence boundary for staged and atomically committed Connection metadata.
 * A production adapter should join the outer transaction and persist pending
 * secret leases through a future {@code PendingSecretStore}; this contract
 * deliberately does not claim or resolve secrets itself.
 */
public interface ApiConnectionCommitStore {
    /**
     * Stages an invisible revision after applying pure authority decisions.
     * {@code API_CONNECTION_SAVE} requires exact target and expected-revision
     * equality; nested {@code API_RESOURCE_SAVE} accepts a different resource
     * target but permits only a child {@link ExpectedRevision.Create} CAS.
     */
    StagedApiConnection stage(CommandLease lease, String connectionId, ExpectedRevision connectionExpected,
                              ApiConnectionCommand command,
                              PreparedSecretBinding... prepared);

    /** Promotes the exact live stage and returns its committed payload-free view. */
    StoredApiConnection commit(CommandLease lease);

    /** Promotes a staged Connection after exact external-secret activation outputs are supplied. */
    StoredApiConnection commitActivated(CommandLease lease, List<ActivatedSecretSlot> activated);

    /**
     * Promotes a Connection child of an outer resource save without closing
     * the shared command journal or creating a Connection receipt. The outer
     * facade owns that composite finalization boundary.
     *
     * @param lease exact live {@code API_RESOURCE_SAVE} lease
     * @return the locally committed child view, not a published receipt
     */
    StoredApiConnection commitChild(CommandLease lease);

    /** Promotes a nested Connection child after exact external-secret activation outputs are supplied. */
    StoredApiConnection commitChildActivated(CommandLease lease, List<ActivatedSecretSlot> activated);

    /** Fenced failure removes only the exact live stage; stale failures are no-ops. */
    void fail(CommandLease lease);

    /** Reads the committed head in the exact authoring scope. */
    Optional<StoredApiConnection> findHead(AuthoringScope scope, String connectionId);

    /** Reads one committed historical revision in the exact authoring scope. */
    Optional<StoredApiConnection> findRevision(AuthoringScope scope, String connectionId, long revision);
}
