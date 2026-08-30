package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionSpec;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.FinalizedSecretSlots;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException.Code;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceSaveReceiptClosure;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandReceipt;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC-backed, transactionally fenced API Connection authority.
 *
 * <p>V001 through V010 must already be installed. V009 supplies immutable
 * command-attempt authority and V010 retargets every connection revision,
 * head, pending lease, and binding relationship to the exact attempt. The
 * adapter keeps staged revisions invisible and persists only scalar Connection
 * metadata plus the configured secret slot. Secret values, provider locators
 * and lease lifecycle remain outside this metadata authority.</p>
 */
public final class JdbcApiConnectionCommitStore implements ApiConnectionCommitStore {
    private static final String RECEIPT_SCHEMA = "bloge.apiConnectionView.v1";
    private static final String RESOURCE_RECEIPT_SCHEMA = "bloge.apiResourceSaveReceipt.v1";
    private static final String BASE_REVISION_COLUMNS = "r.tenant_id, r.project_id, r.environment_id, "
            + "r.connection_id, r.revision, r.command_id, r.attempt_no, r.attempt_token, r.state, "
            + "r.display_name, r.secret_slot, "
            + "r.view_json, r.metadata_fingerprint, r.base_url, r.defaults_headers_json, r.timeout_ms, "
            + "r.auth_kind, r.basic_username, r.api_key_header, r.strong_etag";
    private static final String REVISION_COLUMNS = BASE_REVISION_COLUMNS + ", "
            + "j.receipt_schema, j.receipt_json, j.receipt_fingerprint, j.receipt_etag";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final ApiConnectionDecisions decisions;
    /** Transaction-scoped key used to make the child durability fence idempotent. */
    private final Object childCommitFenceResourceKey = new Object();

    /** Creates a store whose JDBC and transaction collaborators share one source. */
    public JdbcApiConnectionCommitStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                        ObjectMapper mapper, ApiConnectionDecisions decisions,
                                        Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        Objects.requireNonNull(clock, "clock");
        DataSource jdbcSource = jdbc.getDataSource();
        DataSource transactionSource = transactions.getTransactionManager()
                instanceof DataSourceTransactionManager manager ? manager.getDataSource() : null;
        if (jdbcSource == null || transactionSource == null || transactionSource != jdbcSource) {
            throw new IllegalArgumentException("jdbc and transaction manager must share the same DataSource");
        }
    }

    /** Creates a JDBC template and transaction template sharing {@code dataSource}. */
    public JdbcApiConnectionCommitStore(DataSource dataSource, ObjectMapper mapper,
                                        ApiConnectionDecisions decisions, Clock clock) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), mapper, decisions, clock);
    }

    /** {@inheritDoc} */
    @Override
    public StagedApiConnection stage(CommandLease lease, String connectionId,
                                     ExpectedRevision connectionExpected,
                                     ApiConnectionCommand command,
                                     PreparedSecretBinding... prepared) {
        requireLease(lease);
        requireConnectionId(connectionId);
        if (connectionExpected == null || command == null) fail(Code.INTEGRITY);
        requireConnectionLeaseShape(lease, connectionId, connectionExpected);
        try {
            return requireResult(transactions.execute(
                    status -> stageInTransaction(lease, connectionId, connectionExpected, command, prepared)));
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    private StagedApiConnection stageInTransaction(CommandLease lease, String connectionId,
                                                   ExpectedRevision connectionExpected,
                                                   ApiConnectionCommand command,
                                                   PreparedSecretBinding[] prepared) {
        if (lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE) {
            // The outer Resource store owns this journal; a child must never create or recover it.
            requireLiveJournal(lease, false);
        } else {
            ensureJournal(lease);
        }
        RevisionRow existing = stagedRevision(lease);
        if (existing != null) {
            ApiConnectionSpec base = connectionExpected instanceof ExpectedRevision.Match match
                    ? committedSpecAt(lease.key().scope(), connectionId, match.revision()) : null;
            ApiConnectionSpec next = nextSpec(lease.key().scope(), base, connectionId, command,
                    connectionExpected, prepared);
            return validateExistingStage(lease, connectionId, connectionExpected, next, existing);
        }
        ApiConnectionSpec current = committedSpec(lease.key().scope(), connectionId);
        ApiConnectionSpec next = nextSpec(lease.key().scope(), current, connectionId, command,
                connectionExpected, prepared);

        deleteCommandStages(lease);
        insertIdentity(lease.key().scope(), connectionId);
        String etag = opaqueEtag();
        insertRevision(lease, next, etag);
        return new StagedApiConnection(lease, next, connectionExpected, etag);
    }

    private void ensureJournal(CommandLease lease) {
        CommandKey key = lease.key();
        List<JournalRow> journalRows = jdbc.query("""
                        SELECT command_id, request_fingerprint, status, attempt_no, attempt_token,
                               lease_until, expected_mode, expected_revision
                          FROM rg_authoring_command_journal
                         WHERE tenant_id=? AND project_id=? AND environment_id=? AND actor_id=?
                           AND endpoint=? AND target_id=? AND idempotency_key=?
                         FOR UPDATE
                        """, journalRowMapper(), key.scope().tenantId(), key.scope().projectId(),
                key.scope().environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                key.idempotencyKey());
        if (journalRows.size() > 1) fail(Code.INTEGRITY);
        JournalRow prior = journalRows.isEmpty() ? null : journalRows.getFirst();
        if (prior == null) {
            requireIncomingLeaseLive(lease);
            insertJournal(lease);
            insertAttempt(lease);
            return;
        }
        if (sameLease(prior, lease)) {
            requireIncomingLeaseLive(lease);
            requireAttempt(lease);
            return;
        }
        if (!prior.expectedMode().equals(expectedMode(lease.expectedRevision()))
                || !Objects.equals(prior.expectedRevision(), expectedRevision(lease.expectedRevision()))) {
            fail(Code.LEASE_FENCED);
        }
        boolean sameCommand = prior.commandId().equals(lease.commandId());
        boolean higherAttempt = lease.attemptNo() > prior.attemptNo();
        boolean takeoverAllowed = sameCommand && higherAttempt
                && prior.requestFingerprint().equals(lease.requestFingerprint())
                && !"COMMITTED".equals(prior.status())
                && !prior.leaseUntil().isAfter(databaseNow());
        if (!takeoverAllowed) fail(Code.LEASE_FENCED);
        if ("PREPARING".equals(prior.status()) && jdbc.update("UPDATE rg_authoring_command_attempts"
                + " SET status='SUPERSEDED', updated_at=CURRENT_TIMESTAMP WHERE command_id=?"
                + " AND attempt_no=? AND attempt_token=? AND status='PREPARING'", prior.commandId(),
                prior.attemptNo(), prior.attemptToken()) != 1) {
            fail(Code.LEASE_FENCED);
        }
        insertAttempt(lease);
        deleteCommandStages(prior.commandId(), prior.attemptNo(), prior.attemptToken());
        replaceJournal(lease);
    }

    private void insertJournal(CommandLease lease) {
        CommandKey key = lease.key();
        try {
            if (jdbc.update("""
                            INSERT INTO rg_authoring_command_journal
                                (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                                 idempotency_key, command_id, request_fingerprint, status, attempt_no,
                                 attempt_token, lease_until, expected_mode, expected_revision)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING', ?, ?, ?, ?, ?)
                            """, key.scope().tenantId(), key.scope().projectId(),
                    key.scope().environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                    key.idempotencyKey(), lease.commandId(), lease.requestFingerprint(), lease.attemptNo(),
                    lease.attemptToken(), timestamp(lease.leaseUntil()), expectedMode(lease.expectedRevision()),
                    expectedRevision(lease.expectedRevision())) != 1) {
                fail(Code.INTEGRITY);
            }
        } catch (DuplicateKeyException ex) {
            fail(Code.LEASE_FENCED);
        }
    }

    /** Inserts one immutable lease authority before the mutable journal pointer advances. */
    private void insertAttempt(CommandLease lease) {
        if (jdbc.update("""
                        INSERT INTO rg_authoring_command_attempts
                            (tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                             idempotency_key, command_id, request_fingerprint, status, attempt_no,
                             attempt_token, lease_until, expected_mode, expected_revision)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING', ?, ?, ?, ?, ?)
                        """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), lease.key().actorId(), lease.key().endpoint().name(),
                lease.key().targetId(), lease.key().idempotencyKey(), lease.commandId(), lease.requestFingerprint(),
                lease.attemptNo(), lease.attemptToken(), timestamp(lease.leaseUntil()),
                expectedMode(lease.expectedRevision()), expectedRevision(lease.expectedRevision())) != 1) {
            fail(Code.INTEGRITY);
        }
    }

    private void replaceJournal(CommandLease lease) {
        if (jdbc.update("""
                        UPDATE rg_authoring_command_journal
                           SET status='PREPARING', attempt_no=?, attempt_token=?, lease_until=?,
                               expected_mode=?, expected_revision=?, receipt_schema=NULL, receipt_json=NULL,
                               receipt_fingerprint=NULL, receipt_etag=NULL, failure_code=NULL,
                               updated_at=CURRENT_TIMESTAMP
                         WHERE command_id=?
                        """, lease.attemptNo(), lease.attemptToken(), timestamp(lease.leaseUntil()),
                expectedMode(lease.expectedRevision()), expectedRevision(lease.expectedRevision()),
                lease.commandId()) != 1) {
            fail(Code.LEASE_FENCED);
        }
    }

    private boolean sameLease(JournalRow prior, CommandLease lease) {
        return "PREPARING".equals(prior.status())
                && prior.commandId().equals(lease.commandId())
                && prior.requestFingerprint().equals(lease.requestFingerprint())
                && prior.attemptNo() == lease.attemptNo()
                && prior.attemptToken().equals(lease.attemptToken())
                && prior.leaseUntil().equals(lease.leaseUntil())
                && prior.expectedMode().equals(expectedMode(lease.expectedRevision()))
                && Objects.equals(prior.expectedRevision(), expectedRevision(lease.expectedRevision()));
    }

    private void requireIncomingLeaseLive(CommandLease lease) {
        if (!lease.leaseUntil().isAfter(databaseNow())) fail(Code.LEASE_EXPIRED);
    }

    private static void requireConnectionLeaseShape(CommandLease lease, String connectionId,
                                                    ExpectedRevision connectionExpected) {
        if (lease.key().endpoint() == AuthoringEndpoint.API_CONNECTION_SAVE) {
            if (!connectionId.equals(lease.key().targetId())
                    || !lease.expectedRevision().equals(connectionExpected)) fail(Code.INTEGRITY);
        } else if (lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE) {
            if (!(connectionExpected instanceof ExpectedRevision.Create)) fail(Code.INTEGRITY);
        } else {
            fail(Code.INTEGRITY);
        }
    }

    private ApiConnectionSpec nextSpec(AuthoringScope scope, ApiConnectionSpec current, String connectionId,
                                       ApiConnectionCommand command, ExpectedRevision expected,
                                       PreparedSecretBinding[] prepared) {
        try {
            return decisions.next(scope, Optional.ofNullable(current), connectionId, command, expected, prepared);
        } catch (ApiConnectionAuthoringException ex) {
            if (ex.code() == ApiConnectionAuthoringException.Code.VALIDATION) fail(Code.INTEGRITY);
            fail(Code.CAS_MISMATCH);
            return null;
        }
    }

    private RevisionRow stagedRevision(CommandLease lease) {
        List<RevisionRow> rows = jdbc.query("""
                        SELECT %s
                          FROM rg_api_connection_revisions r
                         WHERE r.command_id=? AND r.attempt_no=? AND r.attempt_token=? AND r.state='STAGED'
                        """.formatted(BASE_REVISION_COLUMNS + ", NULL, NULL, NULL, NULL"), revisionRowMapper(),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
        if (rows.size() > 1) fail(Code.INTEGRITY);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StagedApiConnection validateExistingStage(CommandLease lease, String connectionId,
                                                      ExpectedRevision connectionExpected,
                                                      ApiConnectionSpec next, RevisionRow staged) {
        try {
            ApiConnectionSpec stored = restoreSpec(staged);
            if (!staged.connectionId().equals(connectionId)
                    || !staged.fingerprint().equals(next.fingerprint())
                    || staged.revision() != next.revision()
                    || connectionRevisionMismatch(connectionExpected, staged.revision())
                    ) {
                fail(Code.INTEGRITY);
            }
            return new StagedApiConnection(lease, stored, connectionExpected, staged.strongEtag());
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (Exception ex) {
            fail(Code.INTEGRITY);
            return null;
        }
    }

    /**
     * Removes only abandoned staged metadata after takeover. Secret
     * preparation belongs to the outer secret coordinator and is deliberately
     * absent from this metadata store.
     */
    private void deleteCommandStages(CommandLease lease) {
        deleteCommandStages(lease.commandId(), lease.attemptNo(), lease.attemptToken());
    }

    private void deleteCommandStages(String commandId, int attemptNo, String attemptToken) {
        // A pending-secret row keeps the child revision as immutable recovery
        // provenance.  Retain that staged revision until the exact old
        // attempt is recovered; deleting it would violate the V009 attempt FK
        // and make the old provider compensation unverifiable.
        if (jdbc.update("DELETE FROM rg_api_connection_revisions r WHERE r.command_id=? AND r.attempt_no=? "
                + "AND r.attempt_token=? AND r.state='STAGED'"
                + " AND NOT EXISTS (SELECT 1 FROM rg_api_connection_pending_secret_leases p"
                + " WHERE p.tenant_id=r.tenant_id AND p.project_id=r.project_id"
                + " AND p.environment_id=r.environment_id AND p.connection_id=r.connection_id"
                + " AND p.revision=r.revision AND p.command_id=r.command_id"
                + " AND p.attempt_no=r.attempt_no AND p.attempt_token=r.attempt_token)", commandId,
                attemptNo, attemptToken) < 0) {
            fail(Code.INTEGRITY);
        }
    }

    private void insertIdentity(AuthoringScope scope, String connectionId) {
        jdbc.update("""
                        MERGE INTO rg_api_connection_identities AS target
                        USING (VALUES (?, ?, ?, ?)) AS source(tenant_id, project_id, environment_id, connection_id)
                          ON target.tenant_id=source.tenant_id AND target.project_id=source.project_id
                         AND target.environment_id=source.environment_id AND target.connection_id=source.connection_id
                        WHEN NOT MATCHED THEN INSERT (tenant_id, project_id, environment_id, connection_id)
                        VALUES (source.tenant_id, source.project_id, source.environment_id, source.connection_id)
                        """, scope.tenantId(), scope.projectId(), scope.environmentId(), connectionId);
    }

    private void insertRevision(CommandLease lease, ApiConnectionSpec spec, String etag) {
        try {
            if (jdbc.update("""
                            INSERT INTO rg_api_connection_revisions
                                (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                                 state, attempt_no, attempt_token, display_name, secret_slot, view_json,
                                 metadata_fingerprint, base_url,
                                 defaults_headers_json, timeout_ms, auth_kind, basic_username,
                                 api_key_header, strong_etag)
                            VALUES (?, ?, ?, ?, ?, ?, 'STAGED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                    lease.key().scope().environmentId(), spec.connectionId(), (long) spec.revision(),
                    lease.commandId(), lease.attemptNo(), lease.attemptToken(),
                    spec.displayName(), secretSlot(spec), mapper.writeValueAsString(spec.view()), spec.fingerprint(), spec.baseUrl(),
                    mapper.writeValueAsString(spec.defaults() == null ? Map.of() : spec.defaults().headers()),
                    effectiveTimeout(spec), spec.authKind(), spec.username(), spec.apiKeyHeader(), etag) != 1) {
                fail(Code.INTEGRITY);
            }
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (Exception ex) {
            fail(Code.INTEGRITY);
        }
    }

    private static boolean connectionRevisionMismatch(ExpectedRevision expected, long revision) {
        return expected instanceof ExpectedRevision.Create && revision != 1
                || expected instanceof ExpectedRevision.Match match && match.revision() != revision - 1L;
    }

    private static int effectiveTimeout(ApiConnectionSpec spec) {
        Integer timeout = spec.defaults() == null ? null : spec.defaults().timeoutMs();
        return timeout == null ? ApiConnectionCommand.Defaults.DEFAULT_TIMEOUT_MS : timeout;
    }

    private static String secretSlot(ApiConnectionSpec spec) {
        if (spec.secretSlots().isEmpty()) return null;
        if (spec.secretSlots().size() != 1) fail(Code.INTEGRITY);
        return spec.secretSlots().iterator().next();
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", (row, ignored) -> timestamp(row, 1));
    }

    /** {@inheritDoc} */
    @Override
    public StoredApiConnection commit(CommandLease lease) {
        requireLease(lease);
        if (lease.key().endpoint() != AuthoringEndpoint.API_CONNECTION_SAVE) fail(Code.INTEGRITY);
        try {
            return requireResult(transactions.execute(status -> commitInTransaction(lease, false, null)));
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    /** {@inheritDoc} */
    @Override
    public StoredApiConnection commit(CommandLease lease, FinalizedSecretSlots finalized) {
        requireLease(lease);
        if (lease.key().endpoint() != AuthoringEndpoint.API_CONNECTION_SAVE) fail(Code.INTEGRITY);
        try {
            return requireResult(transactions.execute(status -> commitInTransaction(lease, false, finalized)));
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    /**
     * Commits only a nested Connection metadata revision. The outer resource
     * facade remains responsible for the shared journal and composite receipt.
     */
    @Override
    public StoredApiConnection commitChild(CommandLease lease) {
        requireLease(lease);
        if (lease.key().endpoint() != AuthoringEndpoint.API_RESOURCE_SAVE) fail(Code.INTEGRITY);
        requireAmbientTransaction();
        try {
            StoredApiConnection committed = commitInTransaction(lease, true, null);
            registerChildCommitFence(lease);
            return committed;
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    /** {@inheritDoc} */
    @Override
    public StoredApiConnection commitChild(CommandLease lease, FinalizedSecretSlots finalized) {
        requireLease(lease);
        if (lease.key().endpoint() != AuthoringEndpoint.API_RESOURCE_SAVE) fail(Code.INTEGRITY);
        requireAmbientTransaction();
        try {
            StoredApiConnection committed = commitInTransaction(lease, true, finalized);
            registerChildCommitFence(lease);
            return committed;
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    /**
     * Publishes a child only after the outer resource receipt is committed.
     * Publication closes the child journal; it deliberately does not mutate the
     * already committed child head or revision.
     */
    @Override
    public StoredApiConnection publishChild(CommandLease lease, CommandReceipt outerReceipt) {
        requireLease(lease);
        if (lease.key().endpoint() != AuthoringEndpoint.API_RESOURCE_SAVE) fail(Code.INTEGRITY);
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                requireAmbientTransaction();
                return publishChildInTransaction(lease, outerReceipt);
            }
            return requireResult(transactions.execute(status -> publishChildInTransaction(lease, outerReceipt)));
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    /** Removes a child committed inside an outer transaction when that workflow aborts. */
    @Override
    public void failChild(CommandLease lease) {
        if (lease == null) return;
        if (lease.key().endpoint() != AuthoringEndpoint.API_RESOURCE_SAVE) fail(Code.INTEGRITY);
        try {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                requireAmbientTransaction();
                failChildInTransaction(lease);
            } else {
                transactions.executeWithoutResult(status -> failChildInTransaction(lease));
            }
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    private StoredApiConnection publishChildInTransaction(CommandLease lease, CommandReceipt outerReceipt) {
        CommittedOuterReceipt committedOuter = requireCommittedOuterReceipt(lease);
        RevisionRow child = committedChildRevision(lease);
        if (child == null) fail(Code.STAGE_MISSING);
        validateOuterReceipt(outerReceipt, child, lease, lease.key().targetId());
        validateResourceReceiptAuthority(new CommandReceipt(committedOuter.schema(), committedOuter.body(),
                committedOuter.fingerprint(), committedOuter.etag()), child, lease, lease.key().targetId());
        if (outerReceipt == null || !outerReceipt.schemaVersion().equals(committedOuter.schema())
                || !outerReceipt.body().equals(committedOuter.body())
                || !outerReceipt.bodyFingerprint().equals(committedOuter.fingerprint())
                || !outerReceipt.strongEtag().equals(committedOuter.etag())) fail(Code.INTEGRITY);
        ApiConnectionSpec authority = restoreSpec(child);
        return new StoredApiConnection(child.scope(), authority.view(), child.fingerprint(),
                child.strongEtag(), child.commandId());
    }

    private CommittedOuterReceipt requireCommittedOuterReceipt(CommandLease lease) {
        CommandKey key = lease.key();
        Long exact = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM rg_authoring_command_journal
                         WHERE command_id=? AND tenant_id=? AND project_id=? AND environment_id=?
                           AND actor_id=? AND endpoint=? AND target_id=? AND idempotency_key=?
                           AND request_fingerprint=? AND attempt_no=? AND attempt_token=? AND lease_until=?
                           AND expected_mode=? AND expected_revision IS NOT DISTINCT FROM ?
                           AND status='COMMITTED'
                        """, Long.class, lease.commandId(), key.scope().tenantId(), key.scope().projectId(),
                key.scope().environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                key.idempotencyKey(), lease.requestFingerprint(), lease.attemptNo(), lease.attemptToken(),
                timestamp(lease.leaseUntil()), expectedMode(lease.expectedRevision()), expectedRevision(lease.expectedRevision()));
        if (exact == null || exact != 1) fail(Code.LEASE_FENCED);
                List<CommittedOuterReceipt> rows = jdbc.query("""
                        SELECT receipt_schema, receipt_json, receipt_fingerprint, receipt_etag
                          FROM rg_authoring_command_journal
                         WHERE command_id=? AND attempt_no=? AND attempt_token=? AND status='COMMITTED'
                        """, (row, ignored) -> new CommittedOuterReceipt(row.getString(1),
                parseJson(row.getString(2)), row.getString(3), row.getString(4)), lease.commandId(),
                lease.attemptNo(), lease.attemptToken());
        if (rows.isEmpty()) fail(Code.STAGE_MISSING);
        if (rows.size() != 1) fail(Code.INTEGRITY);
        CommittedOuterReceipt receipt = rows.getFirst();
        if (!RESOURCE_RECEIPT_SCHEMA.equals(receipt.schema()) || receipt.body() == null
                || !AuthoringFingerprints.of(receipt.body()).equals(receipt.fingerprint())) fail(Code.INTEGRITY);
        return receipt;
    }

    private RevisionRow committedChildRevision(CommandLease lease) {
        String columns = BASE_REVISION_COLUMNS + ", NULL, NULL, NULL, NULL";
        List<RevisionRow> rows = jdbc.query("SELECT " + columns + " FROM rg_api_connection_revisions r"
                        + " WHERE r.command_id=? AND r.attempt_no=? AND r.attempt_token=?"
                        + " AND r.state='COMMITTED'", revisionRowMapper(), lease.commandId(),
                lease.attemptNo(), lease.attemptToken());
        if (rows.size() > 1) fail(Code.INTEGRITY);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateOuterReceipt(CommandReceipt receipt, RevisionRow child, CommandLease lease,
                                      String resourceId) {
        try {
            ApiResourceSaveReceiptClosure.require(receipt, resourceId, child.connectionId(), child.revision());
            validateResourceReceiptAuthority(receipt, child, lease, resourceId);
        } catch (RuntimeException ex) {
            fail(Code.INTEGRITY);
        }
    }

    private void validateResourceReceiptAuthority(CommandReceipt receipt, RevisionRow child,
                                                  CommandLease lease, String resourceId) {
        validateResourceReceiptAuthority(receipt, child.scope(), lease.commandId(), child.attemptNo(),
                child.attemptToken(), resourceId, child.connectionId(), child.revision());
    }

    /**
     * Binds the child receipt's resource reference to the committed resource
     * row for the exact outer command and authoring scope. A receipt body is
     * not an authority for its own resource revision or specification digest.
     */
    private void validateResourceReceiptAuthority(CommandReceipt receipt, AuthoringScope scope,
                                                 String commandId, int attemptNo, String attemptToken,
                                                 String resourceId,
                                                 String connectionId, long connectionRevision) {
        ApiResourceSaveReceiptClosure.require(receipt, resourceId, connectionId, connectionRevision);
        List<ResourceAuthorityRow> rows = jdbc.query("""
                        SELECT resource_id, revision, spec_fingerprint, connection_id
                         FROM rg_api_resource_revisions
                         WHERE tenant_id=? AND project_id=? AND environment_id=?
                           AND command_id=? AND attempt_no=? AND attempt_token=? AND state='COMMITTED'
                        """, (row, ignored) -> new ResourceAuthorityRow(row.getString(1), row.getLong(2),
                        row.getString(3), row.getString(4)), scope.tenantId(), scope.projectId(),
                scope.environmentId(), commandId, attemptNo, attemptToken);
        if (rows.size() != 1) fail(Code.INTEGRITY);
        ResourceAuthorityRow authority = rows.getFirst();
        JsonNode resource = receipt.body().get("resource");
        if (!authority.resourceId().equals(resourceId)
                || !authority.resourceId().equals(resource.path("resourceId").asText(null))
                || authority.revision() != resource.path("revision").asLong()
                || !authority.specFingerprint().equals(resource.path("fingerprint").asText(null))
                || !authority.connectionId().equals(connectionId)) {
            fail(Code.INTEGRITY);
        }
    }

    private JsonNode parseJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (Exception ex) {
            fail(Code.INTEGRITY);
            return null;
        }
    }

    private void failChildInTransaction(CommandLease lease) {
        String status = jdbc.query("SELECT status FROM rg_authoring_command_journal WHERE command_id=? FOR UPDATE",
                (row, ignored) -> row.getString(1), lease.commandId()).stream().findFirst().orElse(null);
        if (status == null || "COMMITTED".equals(status) || "FAILED".equals(status)) return;
        Long exactChild = jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions "
                        + "WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                Long.class, lease.commandId(), lease.attemptNo(), lease.attemptToken());
        if (exactChild == null || exactChild == 0) return;
        requireLiveJournal(lease, false);
        if (jdbc.update("DELETE FROM rg_api_connection_heads WHERE tenant_id=? AND project_id=? "
                + "AND environment_id=? AND command_id=? AND attempt_no=? AND attempt_token=?"
                + " AND revision_state='COMMITTED'",
                lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), lease.commandId(), lease.attemptNo(), lease.attemptToken()) < 0
                || jdbc.update("DELETE FROM rg_api_connection_revisions WHERE tenant_id=? AND project_id=? "
                + "AND environment_id=? AND command_id=? AND attempt_no=? AND attempt_token=? "
                + "AND state IN ('STAGED','COMMITTED')", lease.key().scope().tenantId(),
                lease.key().scope().projectId(), lease.key().scope().environmentId(), lease.commandId(),
                lease.attemptNo(), lease.attemptToken()) < 0) fail(Code.INTEGRITY);
    }

    private void requireAmbientTransaction() {
        DataSource source = jdbc.getDataSource();
        if (source == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)) {
            fail(Code.INTEGRITY);
        }
    }

    /**
     * A child head is provisional until the outer resource journal reaches its
     * exact committed attempt.  Registering this check with Spring makes a
     * child-only transaction fail during {@code beforeCommit}; the JDBC
     * transaction manager then rolls back the child revision and head.
     */
    private void registerChildCommitFence(CommandLease lease) {
        requireAmbientTransaction();
        @SuppressWarnings("unchecked")
        Map<String, CommandLease> registered = (Map<String, CommandLease>)
                TransactionSynchronizationManager.getResource(childCommitFenceResourceKey);
        if (registered == null) {
            registered = new LinkedHashMap<>();
            TransactionSynchronizationManager.bindResource(childCommitFenceResourceKey, registered);
        }
        String fence = lease.commandId() + "\u0000" + lease.attemptNo() + "\u0000" + lease.attemptToken();
        Map<String, CommandLease> holder = registered;
        if (holder.putIfAbsent(fence, lease) == null) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void beforeCommit(boolean readOnly) {
                    requireCommittedOuterJournal(lease);
                }

                @Override
                public void afterCompletion(int status) {
                    // Spring normally clears resources after callbacks.  This
                    // store owns the key, so remove only the holder installed
                    // by this transaction; a suspended/replaced holder must
                    // remain untouched.
                    if (TransactionSynchronizationManager.hasResource(childCommitFenceResourceKey)
                            && TransactionSynchronizationManager.getResource(childCommitFenceResourceKey)
                            == holder) {
                        TransactionSynchronizationManager.unbindResource(childCommitFenceResourceKey);
                    }
                }
            });
        }
    }

    /** Package-private lifecycle seam used by same-thread transaction tests. */
    boolean childCommitFenceBoundForCurrentTransaction() {
        return TransactionSynchronizationManager.hasResource(childCommitFenceResourceKey);
    }

    /** Verifies the complete current outer authority, including immutable status. */
    private void requireCommittedOuterJournal(CommandLease lease) {
        CommandKey key = lease.key();
        Long exact = jdbc.queryForObject("""
                        SELECT COUNT(*)
                          FROM rg_authoring_command_journal j
                          JOIN rg_authoring_command_attempts a
                            ON a.command_id=j.command_id
                           AND a.attempt_no=j.attempt_no
                           AND a.attempt_token=j.attempt_token
                         WHERE j.command_id=? AND j.tenant_id=? AND j.project_id=?
                           AND j.environment_id=? AND j.actor_id=? AND j.endpoint=?
                           AND j.target_id=? AND j.idempotency_key=?
                           AND j.request_fingerprint=? AND j.status='COMMITTED'
                           AND j.attempt_no=? AND j.attempt_token=? AND j.lease_until=?
                           AND j.expected_mode=? AND j.expected_revision IS NOT DISTINCT FROM ?
                           AND a.status='COMMITTED'
                        """, Long.class, lease.commandId(), key.scope().tenantId(), key.scope().projectId(),
                key.scope().environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                key.idempotencyKey(), lease.requestFingerprint(), lease.attemptNo(), lease.attemptToken(),
                timestamp(lease.leaseUntil()), expectedMode(lease.expectedRevision()),
                expectedRevision(lease.expectedRevision()));
        if (exact == null || exact != 1) fail(Code.INTEGRITY);
    }

    private StoredApiConnection commitInTransaction(CommandLease lease, boolean child,
                                                    FinalizedSecretSlots finalized) {
        requireLiveJournal(lease, true);
        RevisionRow staged = stagedRevision(lease);
        if (staged == null) fail(Code.STAGE_MISSING);
        jdbc.query("SELECT revision FROM rg_api_connection_revisions WHERE tenant_id=? AND project_id=? "
                        + "AND environment_id=? AND connection_id=? ORDER BY revision FOR UPDATE",
                (row, ignored) -> row.getLong(1), staged.tenantId(), staged.projectId(),
                staged.environmentId(), staged.connectionId());
        Long head = jdbc.query("""
                        SELECT revision FROM rg_api_connection_heads
                         WHERE tenant_id=? AND project_id=? AND environment_id=? AND connection_id=?
                         FOR UPDATE
                        """, (row, ignored) -> row.getLong(1), staged.tenantId(), staged.projectId(),
                staged.environmentId(), staged.connectionId()).stream().findFirst().orElse(null);
        ExpectedRevision expected = connectionExpected(lease);
        boolean casFailure = expected instanceof ExpectedRevision.Create && head != null
                || expected instanceof ExpectedRevision.Match match
                && (head == null || head != match.revision());
        if (casFailure) fail(Code.CAS_MISMATCH);

        ApiConnectionSpec authority = restoreSpec(staged);
        validateFinalizedSlots(staged, lease, finalized);
        String authorityFingerprint = decisions.fingerprint(authority);
        if (!staged.fingerprint().equals(authorityFingerprint)) fail(Code.INTEGRITY);

        String viewJson;
        String receiptFingerprint;
        ApiConnectionView canonicalView = authority.view();
        try {
            viewJson = mapper.writeValueAsString(canonicalView);
            receiptFingerprint = AuthoringFingerprints.of(mapper.readTree(viewJson));
        } catch (Exception ex) {
            fail(Code.INTEGRITY);
            return null;
        }
        if (jdbc.update("""
                        UPDATE rg_api_connection_revisions SET state='COMMITTED'
                         WHERE tenant_id=? AND project_id=? AND environment_id=? AND connection_id=?
                           AND revision=? AND command_id=? AND attempt_no=? AND attempt_token=?
                           AND state='STAGED' AND metadata_fingerprint=?
                        """, staged.tenantId(), staged.projectId(),
                staged.environmentId(), staged.connectionId(), staged.revision(), staged.commandId(),
                staged.attemptNo(), staged.attemptToken(), staged.fingerprint()) != 1) {
            fail(Code.CAS_MISMATCH);
        }
        if (!child && jdbc.update("""
                        UPDATE rg_authoring_command_attempts
                           SET status='COMMITTED', updated_at=CURRENT_TIMESTAMP
                         WHERE command_id=? AND attempt_no=? AND attempt_token=? AND status='PREPARING'
                        """, lease.commandId(), lease.attemptNo(), lease.attemptToken()) != 1) {
            fail(Code.LEASE_FENCED);
        }
        if (!child && jdbc.update("""
                        UPDATE rg_authoring_command_journal SET status='COMMITTED', receipt_schema=?,
                             receipt_json=?, receipt_fingerprint=?, receipt_etag=?, failure_code=NULL,
                             updated_at=CURRENT_TIMESTAMP
                         WHERE command_id=? AND status='PREPARING'
                        """, RECEIPT_SCHEMA, viewJson, receiptFingerprint, staged.strongEtag(),
                lease.commandId()) != 1) {
            fail(Code.INTEGRITY);
        }
        if (jdbc.update("""
                        MERGE INTO rg_api_connection_heads AS target
                        USING (VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)) AS source
                               (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                                attempt_no, attempt_token, strong_etag)
                          ON target.tenant_id=source.tenant_id AND target.project_id=source.project_id
                         AND target.environment_id=source.environment_id AND target.connection_id=source.connection_id
                        WHEN MATCHED THEN UPDATE SET revision=source.revision, command_id=source.command_id,
                             attempt_no=source.attempt_no, attempt_token=source.attempt_token,
                             strong_etag=source.strong_etag, revision_state='COMMITTED', updated_at=CURRENT_TIMESTAMP
                        WHEN NOT MATCHED THEN INSERT
                            (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                              attempt_no, attempt_token, strong_etag, revision_state)
                        VALUES (source.tenant_id, source.project_id, source.environment_id, source.connection_id,
                                source.revision, source.command_id, source.attempt_no, source.attempt_token,
                                source.strong_etag, 'COMMITTED')
                        """, staged.tenantId(), staged.projectId(), staged.environmentId(),
                staged.connectionId(), staged.revision(), lease.commandId(), lease.attemptNo(), lease.attemptToken(),
                staged.strongEtag()) != 1) {
            fail(Code.INTEGRITY);
        }
        return new StoredApiConnection(staged.scope(), canonicalView, authorityFingerprint,
                staged.strongEtag(), lease.commandId());
    }

    private static void validateFinalizedSlots(RevisionRow staged, CommandLease lease,
                                               FinalizedSecretSlots finalized) {
        Set<String> expected = staged.secretSlot() == null || staged.secretSlot().isBlank()
                ? Set.of() : Set.of(staged.secretSlot());
        if (expected.isEmpty() && finalized != null || !expected.isEmpty() && finalized == null) fail(Code.INTEGRITY);
        if (finalized != null && (!finalized.lease().commandLease().equals(lease)
                || !finalized.coordinate().scope().equals(staged.scope())
                || !finalized.coordinate().connectionId().equals(staged.connectionId())
                || finalized.coordinate().revision() != staged.revision()
                || !finalized.slots().equals(expected))) fail(Code.INTEGRITY);
    }


    /** {@inheritDoc} */
    @Override
    public void fail(CommandLease lease) {
        if (lease == null) return;
        try {
            transactions.executeWithoutResult(status -> failInTransaction(lease));
        } catch (ApiConnectionCommitStoreException ex) {
            if (ex.code() == Code.LEASE_EXPIRED || ex.code() == Code.LEASE_FENCED) return;
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    private void failInTransaction(CommandLease lease) {
        String status = jdbc.query("SELECT status FROM rg_authoring_command_journal WHERE command_id=? FOR UPDATE",
                (row, ignored) -> row.getString(1), lease.commandId()).stream().findFirst().orElse(null);
        if ("COMMITTED".equals(status) || "FAILED".equals(status)) return;
        requireLiveJournal(lease, false);
        if (jdbc.update("DELETE FROM rg_api_connection_revisions WHERE command_id=? AND attempt_no=? "
                + "AND attempt_token=? AND state='STAGED'", lease.commandId(), lease.attemptNo(),
                lease.attemptToken()) < 0) fail(Code.INTEGRITY);
        if (jdbc.update("""
                        UPDATE rg_authoring_command_attempts
                           SET status='FAILED', updated_at=CURRENT_TIMESTAMP
                         WHERE command_id=? AND attempt_no=? AND attempt_token=? AND status='PREPARING'
                        """, lease.commandId(), lease.attemptNo(), lease.attemptToken()) != 1) {
            fail(Code.LEASE_FENCED);
        }
        if (jdbc.update("""
                        UPDATE rg_authoring_command_journal SET status='FAILED', receipt_schema=NULL,
                             receipt_json=NULL, receipt_fingerprint=NULL, receipt_etag=NULL,
                             failure_code='INTERNAL', updated_at=CURRENT_TIMESTAMP
                         WHERE command_id=? AND status='PREPARING'
                        """, lease.commandId()) != 1) {
            fail(Code.INTEGRITY);
        }
    }

    private void requireLiveJournal(CommandLease lease, boolean forCommit) {
        CommandKey key = lease.key();
        Long exactCount = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM rg_authoring_command_journal
                         WHERE command_id=? AND tenant_id=? AND project_id=? AND environment_id=?
                           AND actor_id=? AND endpoint=? AND target_id=? AND idempotency_key=?
                           AND request_fingerprint=? AND attempt_no=? AND attempt_token=? AND lease_until=?
                           AND expected_mode=? AND expected_revision IS NOT DISTINCT FROM ?
                        """, Long.class, lease.commandId(), key.scope().tenantId(), key.scope().projectId(),
                key.scope().environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                key.idempotencyKey(), lease.requestFingerprint(), lease.attemptNo(), lease.attemptToken(),
                timestamp(lease.leaseUntil()), expectedMode(lease.expectedRevision()),
                expectedRevision(lease.expectedRevision()));
        if (exactCount == null || exactCount == 0) fail(Code.LEASE_FENCED);
        String status = jdbc.queryForObject(
                "SELECT status FROM rg_authoring_command_journal WHERE command_id=? FOR UPDATE",
                String.class, lease.commandId());
        if (status == null) fail(Code.LEASE_FENCED);
        if ("COMMITTED".equals(status)) {
            fail(forCommit ? Code.STAGE_MISSING : Code.INTEGRITY);
        }
        if ("FAILED".equals(status)) {
            fail(forCommit ? Code.STAGE_MISSING : Code.LEASE_FENCED);
        }
        requireAttempt(lease);
        Instant expiry = jdbc.queryForObject("SELECT lease_until FROM rg_authoring_command_journal "
                + "WHERE command_id=?", (row, ignored) -> timestamp(row, 1), lease.commandId());
        if (expiry == null || !expiry.isAfter(databaseNow())) fail(Code.LEASE_EXPIRED);
    }

    /** Verifies the immutable V009 authority, not merely the mutable journal row. */
    private void requireAttempt(CommandLease lease) {
        List<AttemptAuthority> rows = jdbc.query("""
                        SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id,
                               idempotency_key, request_fingerprint, status, attempt_no, attempt_token,
                               lease_until, expected_mode, expected_revision
                          FROM rg_authoring_command_attempts
                         WHERE command_id=? AND attempt_no=? AND attempt_token=?
                         FOR UPDATE
                        """, (row, ignored) -> new AttemptAuthority(
                row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5),
                row.getString(6), row.getString(7), row.getString(8), row.getString(9), row.getInt(10),
                row.getString(11), timestamp(row, 12), row.getString(13), nullableLong(row, 14)),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
        if (rows.size() != 1) fail(Code.LEASE_FENCED);
        AttemptAuthority authority = rows.getFirst();
        if (!"PREPARING".equals(authority.status())
                || !authority.tenantId().equals(lease.key().scope().tenantId())
                || !authority.projectId().equals(lease.key().scope().projectId())
                || !authority.environmentId().equals(lease.key().scope().environmentId())
                || !authority.actorId().equals(lease.key().actorId())
                || !authority.endpoint().equals(lease.key().endpoint().name())
                || !authority.targetId().equals(lease.key().targetId())
                || !authority.idempotencyKey().equals(lease.key().idempotencyKey())
                || !authority.requestFingerprint().equals(lease.requestFingerprint())
                || authority.attemptNo() != lease.attemptNo()
                || !authority.attemptToken().equals(lease.attemptToken())
                || !authority.leaseUntil().equals(lease.leaseUntil())
                || !authority.expectedMode().equals(expectedMode(lease.expectedRevision()))
                || !Objects.equals(authority.expectedRevision(), expectedRevision(lease.expectedRevision()))) {
            fail(Code.LEASE_FENCED);
        }
    }


    /** Reads the committed head after checking persisted receipt and metadata integrity. */
    @Override
    public Optional<StoredApiConnection> findHead(AuthoringScope scope, String connectionId) {
        return read(requireScope(scope), requireConnectionId(connectionId), null);
    }

    /** Reads one committed historical revision after checking persisted integrity. */
    @Override
    public Optional<StoredApiConnection> findRevision(AuthoringScope scope, String connectionId,
                                                      long revision) {
        if (revision < 1) return Optional.empty();
        return read(requireScope(scope), requireConnectionId(connectionId), revision);
    }

    private Optional<StoredApiConnection> read(AuthoringScope scope, String connectionId, Long revision) {
        String sql = "SELECT " + REVISION_COLUMNS + """
                  FROM rg_api_connection_revisions r
                  JOIN rg_authoring_command_attempts a
                    ON a.command_id=r.command_id AND a.attempt_no=r.attempt_no
                   AND a.attempt_token=r.attempt_token AND a.status='COMMITTED'
                  JOIN rg_authoring_command_journal j
                    ON j.command_id=r.command_id AND j.attempt_no=r.attempt_no
                   AND j.attempt_token=r.attempt_token AND j.status='COMMITTED'
                """;
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.projectId(),
                scope.environmentId(), connectionId));
        if (revision == null) {
            sql += """

                  JOIN rg_api_connection_heads h
                    ON h.tenant_id=r.tenant_id AND h.project_id=r.project_id
                   AND h.environment_id=r.environment_id AND h.connection_id=r.connection_id
                   AND h.revision=r.revision AND h.command_id=r.command_id
                   AND h.attempt_no=r.attempt_no AND h.attempt_token=r.attempt_token
                   AND h.strong_etag=r.strong_etag AND h.revision_state=r.state
                """;
        }
        sql += " WHERE r.tenant_id=? AND r.project_id=? AND r.environment_id=? AND r.connection_id=? "
                + "AND r.state='COMMITTED'";
        if (revision != null) args.add(revision);
        if (revision != null) sql += " AND r.revision=?";
        List<RevisionRow> rows = jdbc.query(sql, revisionRowMapper(), args.toArray());
        if (rows.isEmpty()) return Optional.empty();
        if (rows.size() != 1) fail(Code.INTEGRITY);
        return Optional.of(stored(rows.getFirst()));
    }

    private StoredApiConnection stored(RevisionRow row) {
        try {
            ApiConnectionView view = mapper.readValue(row.viewJson(), ApiConnectionView.class);
            ApiConnectionSpec authority = restoreSpec(row);
            ApiConnectionView canonicalView = authority.view();
            JsonNode receipt = mapper.readTree(row.receiptJson());
            JournalReference journal = journalReference(row.commandId(), row.attemptNo(), row.attemptToken());
            if (AuthoringEndpoint.API_CONNECTION_SAVE.name().equals(journal.endpoint())
                    && !journal.targetId().equals(row.connectionId())) {
                fail(Code.INTEGRITY);
            }
            boolean connectionReceiptClosure = AuthoringEndpoint.API_CONNECTION_SAVE.name().equals(journal.endpoint())
                    && RECEIPT_SCHEMA.equals(row.receiptSchema())
                    && row.receiptEtag().equals(row.strongEtag())
                    && receipt.equals(mapper.readTree(row.viewJson()));
            boolean resourceReceiptClosure = false;
            if (AuthoringEndpoint.API_RESOURCE_SAVE.name().equals(journal.endpoint())
                    && RESOURCE_RECEIPT_SCHEMA.equals(row.receiptSchema())) {
                validateResourceReceiptAuthority(new CommandReceipt(row.receiptSchema(), receipt,
                        row.receiptFingerprint(), row.receiptEtag()), row.scope(), row.commandId(),
                        row.attemptNo(), row.attemptToken(), journal.targetId(), row.connectionId(), row.revision());
                resourceReceiptClosure = true;
            }
            if (view == null || !view.equals(canonicalView)
                    || !ApiConnectionSpec.SCHEMA_VERSION.equals(authority.schemaVersion())
                    || !row.fingerprint().equals(decisions.fingerprint(authority))
                    || !(connectionReceiptClosure || resourceReceiptClosure)
                    || !AuthoringFingerprints.of(mapper.readTree(row.receiptJson()))
                            .equals(row.receiptFingerprint())
                    || receipt.isMissingNode()) {
                fail(Code.INTEGRITY);
            }
            return new StoredApiConnection(row.scope(), canonicalView, row.fingerprint(), row.strongEtag(),
                    row.commandId());
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw persistenceFailure(ex);
        }
    }

    private JournalReference journalReference(String commandId, int attemptNo, String attemptToken) {
        List<JournalReferenceRow> rows = jdbc.query("""
                        SELECT j.endpoint, j.target_id, a.endpoint, a.target_id
                          FROM rg_authoring_command_journal j
                          JOIN rg_authoring_command_attempts a
                            ON a.command_id=j.command_id AND a.attempt_no=j.attempt_no
                           AND a.attempt_token=j.attempt_token
                         WHERE j.command_id=? AND j.attempt_no=? AND j.attempt_token=?
                           AND j.status='COMMITTED' AND a.status='COMMITTED'
                        """, (row, ignored) -> new JournalReferenceRow(row.getString(1), row.getString(2),
                row.getString(3), row.getString(4)), commandId, attemptNo, attemptToken);
        if (rows.size() != 1) fail(Code.INTEGRITY);
        JournalReferenceRow row = rows.getFirst();
        if (!row.journalEndpoint().equals(row.authorityEndpoint())
                || !row.journalTargetId().equals(row.authorityTargetId())) {
            fail(Code.INTEGRITY);
        }
        return new JournalReference(row.authorityEndpoint(), row.authorityTargetId());
    }

    private ApiConnectionSpec committedSpec(AuthoringScope scope, String connectionId) {
        String sql = "SELECT " + BASE_REVISION_COLUMNS + ", NULL, NULL, NULL, NULL "
                + "FROM rg_api_connection_heads h JOIN rg_api_connection_revisions r "
                + "ON r.tenant_id=h.tenant_id AND r.project_id=h.project_id "
                + "AND r.environment_id=h.environment_id AND r.connection_id=h.connection_id "
                + "AND r.revision=h.revision AND r.command_id=h.command_id "
                + "AND r.attempt_no=h.attempt_no AND r.attempt_token=h.attempt_token "
                + "AND r.strong_etag=h.strong_etag AND r.state='COMMITTED' "
                + "WHERE h.tenant_id=? AND h.project_id=? AND h.environment_id=? AND h.connection_id=?";
        List<RevisionRow> rows = jdbc.query(sql, revisionRowMapper(), scope.tenantId(), scope.projectId(),
                scope.environmentId(), connectionId);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) fail(Code.INTEGRITY);
        RevisionRow row = rows.getFirst();
        try {
            ApiConnectionSpec spec = restoreSpec(row);
            if (!row.fingerprint().equals(decisions.fingerprint(spec))) fail(Code.INTEGRITY);
            return spec;
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw persistenceFailure(ex);
        }
    }

    /** Reads the exact CAS base so a replay does not depend on a newer head. */
    private ApiConnectionSpec committedSpecAt(AuthoringScope scope, String connectionId, long revision) {
        String sql = "SELECT " + BASE_REVISION_COLUMNS + ", NULL, NULL, NULL, NULL"
                + " FROM rg_api_connection_revisions r"
                + " JOIN rg_authoring_command_attempts a"
                + " ON a.command_id=r.command_id AND a.attempt_no=r.attempt_no"
                + " AND a.attempt_token=r.attempt_token AND a.status='COMMITTED'"
                + " JOIN rg_authoring_command_journal j"
                + " ON j.command_id=r.command_id AND j.attempt_no=r.attempt_no"
                + " AND j.attempt_token=r.attempt_token AND j.status='COMMITTED'"
                + " WHERE r.tenant_id=? AND r.project_id=? AND r.environment_id=?"
                + " AND r.connection_id=? AND r.revision=? AND r.state='COMMITTED'";
        List<RevisionRow> rows = jdbc.query(sql, revisionRowMapper(), scope.tenantId(), scope.projectId(),
                scope.environmentId(), connectionId, revision);
        if (rows.isEmpty()) return null;
        if (rows.size() != 1) fail(Code.INTEGRITY);
        RevisionRow row = rows.getFirst();
        return restoreSpec(row);
    }

    private ApiConnectionSpec restoreSpec(RevisionRow row) {
        try {
            ApiConnectionView view = mapper.readValue(row.viewJson(), ApiConnectionView.class);
            ApiConnectionSpec authority = ApiConnectionSpec.restore(ApiConnectionSpec.SCHEMA_VERSION,
                    row.scope(), row.connectionId(),
                    Math.toIntExact(row.revision()), row.fingerprint(), row.displayName(), row.baseUrl(),
                    row.authKind(), row.basicUsername(), row.apiKeyHeader(), defaults(row),
                    row.secretSlot() == null || row.secretSlot().isBlank() ? Set.of() : Set.of(row.secretSlot()));
            if (!view.equals(authority.view())) fail(Code.INTEGRITY);
            return authority;
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw persistenceFailure(ex);
        }
    }

    private ApiConnectionCommand.Defaults defaults(RevisionRow row) throws Exception {
        JsonNode headers = mapper.readTree(row.defaultsHeadersJson());
        if (!headers.isObject()) fail(Code.INTEGRITY);
        Map<String, String> values = new LinkedHashMap<>();
        headers.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return new ApiConnectionCommand.Defaults(row.timeoutMs(), values);
    }

    private static ExpectedRevision connectionExpected(CommandLease lease) {
        return lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE
                ? ExpectedRevision.create() : lease.expectedRevision();
    }

    private static <T> T requireResult(T value) {
        if (value == null) fail(Code.INTEGRITY);
        return value;
    }

    private static RuntimeException persistenceFailure(Exception failure) {
        return new ApiConnectionCommitStoreException(Code.INTEGRITY);
    }

    private static void requireLease(CommandLease lease) {
        if (lease == null || lease.key() == null) fail(Code.LEASE_FENCED);
    }

    private static AuthoringScope requireScope(AuthoringScope scope) {
        if (scope == null) fail(Code.INTEGRITY);
        return scope;
    }

    private static String requireConnectionId(String value) {
        if (value == null || value.isBlank() || value.length() > 128) fail(Code.INTEGRITY);
        return value;
    }

    private static void fail(Code code) {
        throw new ApiConnectionCommitStoreException(code);
    }

    private static String expectedMode(ExpectedRevision expected) {
        return expected instanceof ExpectedRevision.Create ? "CREATE" : "MATCH";
    }

    private static Long expectedRevision(ExpectedRevision expected) {
        return expected instanceof ExpectedRevision.Match match ? match.revision() : null;
    }

    private static String opaqueEtag() {
        return "\"" + UUID.randomUUID() + "\"";
    }

    private static OffsetDateTime timestamp(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant timestamp(ResultSet row, int column) throws SQLException {
        Object value = row.getObject(column);
        if (value instanceof OffsetDateTime offset) return offset.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        return Instant.parse(String.valueOf(value));
    }

    private record JournalRow(String commandId, String requestFingerprint, String status, int attemptNo,
                              String attemptToken, Instant leaseUntil, String expectedMode,
                              Long expectedRevision) { }

    private record AttemptAuthority(String tenantId, String projectId, String environmentId, String actorId,
                                    String endpoint, String targetId, String idempotencyKey,
                                    String requestFingerprint, String status, int attemptNo, String attemptToken,
                                    Instant leaseUntil, String expectedMode, Long expectedRevision) { }

    private record CommittedOuterReceipt(String schema, JsonNode body, String fingerprint, String etag) { }

    private record JournalReference(String endpoint, String targetId) { }

    private record JournalReferenceRow(String journalEndpoint, String journalTargetId,
                                       String authorityEndpoint, String authorityTargetId) { }

    private record ResourceAuthorityRow(String resourceId, long revision, String specFingerprint,
                                        String connectionId) { }

    private static RowMapper<JournalRow> journalRowMapper() {
        return (row, ignored) -> new JournalRow(row.getString(1), row.getString(2), row.getString(3),
                row.getInt(4), row.getString(5), timestamp(row, 6), row.getString(7), nullableLong(row, 8));
    }

    private static RowMapper<RevisionRow> revisionRowMapper() {
        return (row, ignored) -> new RevisionRow(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), row.getLong(5), row.getString(6), row.getInt(7), row.getString(8),
                row.getString(9), row.getString(10), row.getString(11), row.getString(12), row.getString(13),
                row.getString(14), row.getString(15), row.getInt(16), row.getString(17), row.getString(18),
                row.getString(19), row.getString(20), row.getString(21), row.getString(22), row.getString(23), row.getString(24));
    }

    private static Long nullableLong(ResultSet row, int column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private record RevisionRow(String tenantId, String projectId, String environmentId, String connectionId,
                               long revision, String commandId, int attemptNo, String attemptToken,
                               String state, String displayName, String secretSlot, String viewJson,
                               String fingerprint, String baseUrl, String defaultsHeadersJson, int timeoutMs, String authKind,
                               String basicUsername, String apiKeyHeader, String strongEtag,
                               String receiptSchema, String receiptJson, String receiptFingerprint,
                               String receiptEtag) {
        private AuthoringScope scope() {
            return new AuthoringScope(tenantId, projectId, environmentId);
        }
    }
}
