package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringCommandClaimStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ClaimResult;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.List;
import java.util.Optional;

/**
 * Same-database Connection authoring module for the standalone facade.
 *
 * <p>The constructor creates both the generic command claim authority and the
 * Connection projection authority from one {@link DataSource}; callers cannot
 * accidentally pair unrelated claim and metadata stores. The same adapter
 * exposes nested child staging so Resource composition cannot be paired with
 * a different Connection authority.</p>
 */
public final class JdbcApiConnectionAuthoringStore
        implements ApiConnectionAuthoringStore {
    private final AuthoringCommandClaimStore claims;
    private final JdbcApiConnectionCommitStore connections;

    /**
     * Creates a lifecycle-complete adapter over one database source.
     *
     * @param dataSource shared JDBC source for claims and Connection rows
     * @param mapper canonical JSON mapper
     * @param leaseDuration command claim lease duration
     * @param connectionDecisions Connection authority decisions
     * @param clock database-facing wall-clock dependency
     */
    public JdbcApiConnectionAuthoringStore(DataSource dataSource, ObjectMapper mapper,
                                           Duration leaseDuration, ApiConnectionDecisions connectionDecisions,
                                           Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        Objects.requireNonNull(connectionDecisions, "connectionDecisions");
        Objects.requireNonNull(clock, "clock");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        this.claims = new com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.JdbcAuthoringCommandClaimStore(
                jdbc, transactions, mapper, leaseDuration);
        this.connections = new JdbcApiConnectionCommitStore(jdbc, transactions, mapper, connectionDecisions, clock);
    }

    /** Claims through the same command authority used by the Resource protocol. */
    @Override public ClaimResult claim(CommandKey key, String requestFingerprint, ExpectedRevision expectedRevision) {
        return claims.claim(key, requestFingerprint, expectedRevision);
    }

    /** Stages the Auth.None application-facade path without secret bindings. */
    @Override public StagedApiConnection stage(CommandLease lease, String connectionId,
                                               ExpectedRevision connectionExpected,
                                               ApiConnectionCommand command) {
        return connections.stage(lease, connectionId, connectionExpected, command);
    }

    @Override public StagedApiConnection stage(CommandLease lease, String connectionId,
                                                ExpectedRevision connectionExpected,
                                                ApiConnectionCommand command,
                                                com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding... prepared) {
        return connections.stage(lease, connectionId, connectionExpected, command, prepared);
    }

    @Override public StoredApiConnection commit(CommandLease lease) { return connections.commit(lease); }
    @Override public StoredApiConnection commit(CommandLease lease,
                                                  com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.FinalizedSecretSlots finalized) {
        return connections.commit(lease, finalized);
    }
    @Override public StoredApiConnection commitChild(CommandLease lease) { return connections.commitChild(lease); }
    @Override public StoredApiConnection commitChild(CommandLease lease,
                                                       com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.FinalizedSecretSlots finalized) {
        return connections.commitChild(lease, finalized);
    }
    @Override public StoredApiConnection publishChild(CommandLease lease, CommandReceipt receipt) {
        return connections.publishChild(lease, receipt);
    }
    @Override public void failChild(CommandLease lease) { connections.failChild(lease); }
    @Override public void fail(CommandLease lease) { connections.fail(lease); }
    @Override public Optional<StagedApiConnection> findStaged(CommandLease lease) {
        return connections.findStaged(lease);
    }
    @Override public Optional<StoredApiConnection> findHead(AuthoringScope scope, String connectionId) {
        return connections.findHead(scope, connectionId);
    }
    @Override public List<StoredApiConnection> listHeads(AuthoringScope scope) {
        return connections.listHeads(scope);
    }
    @Override public Optional<StoredApiConnection> findRevision(AuthoringScope scope, String connectionId,
                                                                 long revision) {
        return connections.findRevision(scope, connectionId, revision);
    }
    @Override public Optional<StoredApiConnection> findRevisionByStrongEtag(AuthoringScope scope, String connectionId,
                                                                               String strongEtag) {
        return connections.findRevisionByStrongEtag(scope, connectionId, strongEtag);
    }

    @Override public StoredApiConnection resolveReplay(AuthoringScope scope, String connectionId,
                                                        CommandReceipt receipt) {
        return connections.resolveReplay(scope, connectionId, receipt);
    }
}
