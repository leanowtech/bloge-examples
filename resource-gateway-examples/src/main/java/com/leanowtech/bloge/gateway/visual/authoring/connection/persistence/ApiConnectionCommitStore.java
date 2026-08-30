package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.FinalizedSecretSlots;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

import java.util.Optional;

/**
 * Persistence boundary for staged and atomically committed Connection metadata.
 * Secret values, provider locators and lease lifecycle belong to the separate
 * secret coordinator; this contract accepts only a locator-free finalized-slot
 * proof when a committed Connection needs secret-backed authentication.
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

    /**
     * Promotes a staged Connection after an external secret coordinator has
     * finalized every configured slot. The proof contains only the exact
     * connection coordinate and slot set; provider locators never cross this
     * metadata boundary.
     */
    StoredApiConnection commit(CommandLease lease, FinalizedSecretSlots finalized);

    /**
     * Promotes a Connection child of an outer resource save without closing
     * the shared command journal or creating a Connection receipt. The child
     * update is provisional: implementations must require the same ambient
     * coordinator transaction to commit the outer resource journal, and must
     * reject/roll back a child-only transaction at its commit boundary. The
     * outer facade owns that composite finalization boundary.
     *
     * @param lease exact live {@code API_RESOURCE_SAVE} lease
     * @return the locally committed child view, not a published receipt
     */
    StoredApiConnection commitChild(CommandLease lease);

    /** Promotes a nested Connection child with a locator-free finalized-slot proof in the same coordinator transaction. */
    StoredApiConnection commitChild(CommandLease lease, FinalizedSecretSlots finalized);

    /**
     * Publishes a previously committed nested child after the outer resource
     * receipt has been durably committed. The receipt is the visibility fence;
     * a child must never become readable from a partially committed resource.
     * Implementations validate the supplied receipt's complete canonical
     * closure and exact persisted outer authority before exposing the child.
     */
    StoredApiConnection publishChild(CommandLease lease, CommandReceipt outerReceipt);

    /** Removes an unpublished nested child for the exact command attempt. */
    void failChild(CommandLease lease);

    /** Fenced failure removes only the exact live stage; stale failures are no-ops. */
    void fail(CommandLease lease);

    /** Reads the committed head in the exact authoring scope. */
    Optional<StoredApiConnection> findHead(AuthoringScope scope, String connectionId);

    /** Reads one committed historical revision in the exact authoring scope. */
    Optional<StoredApiConnection> findRevision(AuthoringScope scope, String connectionId, long revision);

    /**
     * Reads one committed historical revision by its exact strong ETag.
     *
     * <p>The lookup is independent of the current head, so an old ETag remains
     * usable as a replay precondition after a later revision is committed. A
     * scope or connection mismatch is an empty result; malformed ETags and
     * ambiguous committed provenance fail closed with {@code INTEGRITY}.</p>
     */
    Optional<StoredApiConnection> findRevisionByStrongEtag(AuthoringScope scope, String connectionId,
                                                            String strongEtag);
}
