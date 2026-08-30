package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringCommandClaimStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;

import java.util.Optional;

/**
 * Lifecycle-complete Connection authoring boundary.
 *
 * <p>A facade accepts this single seam rather than unrelated claim and
 * projection stores. The public seam contains only the operations needed by
 * the standalone Connection save tracer; nested Resource composition remains
 * on {@link ApiConnectionCommitStore}. Implementations therefore own one
 * command authority for claim, stage, commit, failure, and historical reads;
 * a JDBC implementation must construct all delegates over the same {@code
 * DataSource}. This shape prevents a valid claim from being paired
 * accidentally with a different in-memory journal.</p>
 */
public interface ApiConnectionAuthoringStore extends AuthoringCommandClaimStore {
    /** Stages the exact Connection command under its outer claim. */
    StagedApiConnection stage(CommandLease lease, String connectionId, ExpectedRevision connectionExpected,
                              ApiConnectionCommand command, PreparedSecretBinding... prepared);

    /** Commits the exact live Connection stage. */
    StoredApiConnection commit(CommandLease lease);

    /** Fails and fences the exact live Connection attempt. */
    void fail(CommandLease lease);

    /** Reads the current committed head in the exact authoring scope. */
    Optional<StoredApiConnection> findHead(AuthoringScope scope, String connectionId);

    /** Reads one committed historical revision by exact persisted strong ETag. */
    Optional<StoredApiConnection> findRevisionByStrongEtag(AuthoringScope scope, String connectionId,
                                                            String strongEtag);
}
