package com.leanowtech.bloge.gateway.visual.authoring.connection.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionSpec;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionView;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionCommitStoreException.Code;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import java.util.UUID;

/**
 * JDBC-backed, transactionally fenced API Connection authority.
 *
 * <p>V001 through V003 must already be installed. The adapter keeps staged
 * revisions invisible, persists opaque secret handles only, and publishes the
 * binding, receipt and head in one database transaction.</p>
 */
public final class JdbcApiConnectionCommitStore implements ApiConnectionCommitStore {
    private static final String RECEIPT_SCHEMA = "bloge.apiConnectionView.v1";
    private static final String BASE_REVISION_COLUMNS = "r.tenant_id, r.project_id, r.environment_id, "
            + "r.connection_id, r.revision, r.command_id, r.attempt_no, r.attempt_token, r.state, "
            + "r.view_json, r.metadata_fingerprint, r.base_url, r.defaults_headers_json, r.timeout_ms, "
            + "r.auth_kind, r.basic_username, r.api_key_header, r.strong_etag";
    private static final String REVISION_COLUMNS = BASE_REVISION_COLUMNS + ", "
            + "j.receipt_schema, j.receipt_json, j.receipt_fingerprint, j.receipt_etag";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final ApiConnectionDecisions decisions;
    private final Clock clock;

    /** Creates a store whose JDBC and transaction collaborators share one source. */
    public JdbcApiConnectionCommitStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                        ObjectMapper mapper, ApiConnectionDecisions decisions,
                                        Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy();
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.clock = Objects.requireNonNull(clock, "clock");
        DataSource jdbcSource = jdbc.getDataSource();
        if (jdbcSource != null
                && transactions.getTransactionManager() instanceof DataSourceTransactionManager manager
                && manager.getDataSource() != jdbcSource) {
            throw new IllegalArgumentException("JDBC and transaction DataSources must be identical");
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
        ensureJournal(lease);
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

        deleteCommandStages(lease.commandId());
        insertIdentity(lease.key().scope(), connectionId);
        String etag = opaqueEtag();
        insertRevision(lease, next, etag);
        insertPendingSecretLeases(lease, connectionId, next, command);
        return new StagedApiConnection(lease, next, connectionExpected, etag);
    }

    private void ensureJournal(CommandLease lease) {
        CommandKey key = lease.key();
        JournalRow prior = jdbc.query("""
                        SELECT command_id, request_fingerprint, status, attempt_no, attempt_token,
                               lease_until, expected_mode, expected_revision
                          FROM rg_authoring_command_journal
                         WHERE tenant_id=? AND project_id=? AND environment_id=? AND actor_id=?
                           AND endpoint=? AND target_id=? AND idempotency_key=?
                         FOR UPDATE
                        """, journalRowMapper(), key.scope().tenantId(), key.scope().projectId(),
                key.scope().environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                key.idempotencyKey()).stream().findFirst().orElse(null);
        if (prior == null) {
            requireIncomingLeaseLive(lease);
            insertJournal(lease);
            return;
        }
        if (sameLease(prior, lease)) {
            requireIncomingLeaseLive(lease);
            return;
        }
        boolean sameCommand = prior.commandId().equals(lease.commandId());
        boolean higherAttempt = lease.attemptNo() > prior.attemptNo();
        boolean takeoverAllowed = sameCommand && higherAttempt
                && prior.requestFingerprint().equals(lease.requestFingerprint())
                && !"COMMITTED".equals(prior.status())
                && !prior.leaseUntil().isAfter(databaseNow());
        if (!takeoverAllowed) fail(Code.LEASE_FENCED);
        deleteCommandStages(lease.commandId());
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
        return rows.stream().findFirst().orElse(null);
    }

    private StagedApiConnection validateExistingStage(CommandLease lease, String connectionId,
                                                      ExpectedRevision connectionExpected,
                                                      ApiConnectionSpec next, RevisionRow staged) {
        List<PendingSecretLease> leases = pendingLeases(lease);
        try {
            ApiConnectionSpec stored = restoreSpec(staged, references(staged.scope(), leases));
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

    private void deleteCommandStages(String commandId) {
        if (jdbc.update("DELETE FROM rg_api_connection_pending_secret_leases WHERE command_id=?", commandId) < 0
                || jdbc.update("DELETE FROM rg_api_connection_revisions WHERE command_id=? AND state='STAGED'",
                commandId) < 0) {
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
                                 state, attempt_no, attempt_token, view_json, metadata_fingerprint, base_url,
                                 defaults_headers_json, timeout_ms, auth_kind, basic_username,
                                 api_key_header, strong_etag)
                            VALUES (?, ?, ?, ?, ?, ?, 'STAGED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                    lease.key().scope().environmentId(), spec.connectionId(), (long) spec.revision(),
                    lease.commandId(), lease.attemptNo(), lease.attemptToken(),
                    mapper.writeValueAsString(spec.view()), spec.fingerprint(), spec.baseUrl(),
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

    private void insertPendingSecretLeases(CommandLease lease, String connectionId,
                                           ApiConnectionSpec spec, ApiConnectionCommand command) {
        if (spec.authKind().equals("NONE")) return;
        String slot = authSlot(spec.authKind());
        ApiConnectionCommand.SecretWrite write = secretWrite(command.auth());
        String sourceMode = write instanceof ApiConnectionCommand.SecretWrite.Value ? "VALUE"
                : write instanceof ApiConnectionCommand.SecretWrite.SecretRef ? "SECRET_REF" : "KEEP_EXISTING";
        SecretReference reference = spec.secretBindings().get(slot);
        if (reference == null) fail(Code.INTEGRITY);
        PendingSecretLease pending = new PendingSecretLease(connectionId, slot, sourceMode,
                providerId(reference.ref()), UUID.randomUUID().toString(), reference.ref(), lease.leaseUntil());
        if (jdbc.update("""
                        INSERT INTO rg_api_connection_pending_secret_leases
                            (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                             attempt_no, attempt_token, slot, source_mode, provider_id, lease_id,
                             opaque_handle, status, lease_until)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                        """, lease.key().scope().tenantId(), lease.key().scope().projectId(),
                lease.key().scope().environmentId(), pending.connectionId(), spec.revision(),
                lease.commandId(), lease.attemptNo(), lease.attemptToken(), pending.slot(),
                pending.sourceMode(), pending.providerId(), pending.leaseId(), pending.opaqueHandle(),
                timestamp(pending.leaseUntil())) != 1) {
            fail(Code.INTEGRITY);
        }
    }

    private List<PendingSecretLease> pendingLeases(CommandLease lease) {
        return jdbc.query("""
                        SELECT connection_id, revision, slot, source_mode, provider_id, lease_id,
                               opaque_handle, lease_until
                          FROM rg_api_connection_pending_secret_leases
                         WHERE command_id=? AND attempt_no=? AND attempt_token=? AND status='PENDING'
                         ORDER BY slot
                        """, (row, ignored) -> new PendingSecretLease(row.getString(1), row.getString(3),
                row.getString(4), row.getString(5), row.getString(6), row.getString(7), timestamp(row, 8)),
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
    }

    private Map<String, SecretReference> references(AuthoringScope scope, List<PendingSecretLease> leases) {
        Map<String, SecretReference> result = new LinkedHashMap<>();
        for (PendingSecretLease lease : leases) {
            result.put(lease.slot(), new SecretReference(scope, lease.opaqueHandle()));
        }
        return result;
    }

    private static ApiConnectionCommand.SecretWrite secretWrite(ApiConnectionCommand.Auth auth) {
        if (auth instanceof ApiConnectionCommand.Auth.Bearer bearer) return bearer.token();
        if (auth instanceof ApiConnectionCommand.Auth.Basic basic) return basic.password();
        if (auth instanceof ApiConnectionCommand.Auth.ApiKey apiKey) return apiKey.value();
        fail(Code.INTEGRITY);
        return null;
    }

    private static String providerId(String reference) {
        String value = reference.substring("vault://".length());
        int end = value.indexOf('/');
        String provider = end < 0 ? value : value.substring(0, end);
        if (provider.isBlank() || provider.length() > 128) fail(Code.INTEGRITY);
        return provider;
    }

    private static boolean connectionRevisionMismatch(ExpectedRevision expected, long revision) {
        return expected instanceof ExpectedRevision.Create && revision != 1
                || expected instanceof ExpectedRevision.Match match && match.revision() != revision - 1L;
    }

    private static int effectiveTimeout(ApiConnectionSpec spec) {
        Integer timeout = spec.defaults() == null ? null : spec.defaults().timeoutMs();
        return timeout == null ? ApiConnectionCommand.Defaults.DEFAULT_TIMEOUT_MS : timeout;
    }

    private static String authSlot(String authKind) {
        return switch (authKind) {
            case "BEARER" -> "token";
            case "BASIC" -> "password";
            case "API_KEY" -> "value";
            default -> {
                fail(Code.INTEGRITY);
                yield "";
            }
        };
    }

    private Instant databaseNow() {
        Instant database = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", (row, ignored) -> timestamp(row, 1));
        // The database is the lower-bound authority; the injected clock may only make
        // a lease fail sooner, which keeps deterministic tests from weakening expiry.
        return database.isAfter(clock.instant()) ? database : clock.instant();
    }

    /**
     * A prepared locator is not an active provider binding. Until an outer
     * secret coordinator supplies an explicit activation result, leave the
     * row recoverable and refuse to publish it as a Connection binding.
     */
    private void rejectUnactivatedSecrets(CommandLease lease) {
        Integer pending = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM rg_api_connection_pending_secret_leases
                         WHERE command_id=? AND attempt_no=? AND attempt_token=? AND status='PENDING'
                        """, Integer.class, lease.commandId(), lease.attemptNo(), lease.attemptToken());
        if (pending == null || pending == 0) return;
        try {
            transactions.executeWithoutResult(status -> {
                requireLiveJournal(lease, true);
                jdbc.update("""
                                UPDATE rg_api_connection_pending_secret_leases SET status='ABORT_REQUIRED',
                                       updated_at=CURRENT_TIMESTAMP
                                 WHERE command_id=? AND attempt_no=? AND attempt_token=? AND status='PENDING'
                                """, lease.commandId(), lease.attemptNo(), lease.attemptToken());
            });
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
        fail(Code.INTEGRITY);
    }

    /** {@inheritDoc} */
    @Override
    public StoredApiConnection commit(CommandLease lease) {
        requireLease(lease);
        if (lease.key().endpoint() != AuthoringEndpoint.API_CONNECTION_SAVE) fail(Code.INTEGRITY);
        rejectUnactivatedSecrets(lease);
        try {
            return requireResult(transactions.execute(status -> commitInTransaction(lease, false)));
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
        rejectUnactivatedSecrets(lease);
        try {
            return requireResult(transactions.execute(status -> commitInTransaction(lease, true)));
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    private StoredApiConnection commitInTransaction(CommandLease lease, boolean child) {
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

        ApiConnectionSpec current = committedSpec(lease.key().scope(), staged.connectionId());
        List<PendingSecretLease> pending = pendingLeases(lease);
        ApiConnectionSpec next = nextSpec(lease.key().scope(), current, staged.connectionId(),
                restoreCommand(staged, pending), expected, preparedBindings(staged.scope(), pending));
        ApiConnectionSpec authority = restoreSpec(staged, references(staged.scope(), pending));
        if (!staged.connectionId().equals(next.connectionId())
                || !next.fingerprint().equals(staged.fingerprint())
                || next.revision() != staged.revision()
                || !authority.fingerprint().equals(next.fingerprint())
                || authority.secretBindings().size() != pending.size()) {
            fail(Code.INTEGRITY);
        }

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
                           AND revision=? AND command_id=? AND state='STAGED'
                        """, staged.tenantId(), staged.projectId(), staged.environmentId(),
                staged.connectionId(), staged.revision(), lease.commandId()) != 1) {
            fail(Code.CAS_MISMATCH);
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
                        USING (VALUES (?, ?, ?, ?, ?, ?, ?)) AS source
                               (tenant_id, project_id, environment_id, connection_id, revision, command_id, strong_etag)
                          ON target.tenant_id=source.tenant_id AND target.project_id=source.project_id
                         AND target.environment_id=source.environment_id AND target.connection_id=source.connection_id
                        WHEN MATCHED THEN UPDATE SET revision=source.revision, command_id=source.command_id,
                             strong_etag=source.strong_etag, revision_state='COMMITTED', updated_at=CURRENT_TIMESTAMP
                        WHEN NOT MATCHED THEN INSERT
                             (tenant_id, project_id, environment_id, connection_id, revision, command_id,
                              strong_etag, revision_state)
                        VALUES (source.tenant_id, source.project_id, source.environment_id, source.connection_id,
                                source.revision, source.command_id, source.strong_etag, 'COMMITTED')
                        """, staged.tenantId(), staged.projectId(), staged.environmentId(),
                staged.connectionId(), staged.revision(), lease.commandId(), staged.strongEtag()) != 1) {
            fail(Code.INTEGRITY);
        }
        return new StoredApiConnection(staged.scope(), canonicalView, authority.fingerprint(),
                staged.strongEtag(), lease.commandId());
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
        if (jdbc.update("DELETE FROM rg_api_connection_pending_secret_leases "
                + "WHERE command_id=? AND attempt_no=? AND attempt_token=?", lease.commandId(),
                lease.attemptNo(), lease.attemptToken()) < 0) fail(Code.INTEGRITY);
        if (jdbc.update("DELETE FROM rg_api_connection_revisions WHERE command_id=? AND attempt_no=? "
                + "AND attempt_token=? AND state='STAGED'", lease.commandId(), lease.attemptNo(),
                lease.attemptToken()) < 0) fail(Code.INTEGRITY);
        if (jdbc.update("""
                        UPDATE rg_authoring_command_journal SET status='FAILED', receipt_schema=NULL,
                             receipt_json=NULL, receipt_fingerprint=NULL, receipt_etag=NULL,
                             failure_code='INTERNAL', updated_at=CURRENT_TIMESTAMP
                         WHERE command_id=? AND status='PREPARING'
                        """, lease.commandId()) != 1) {
            fail(Code.INTEGRITY);
        }
    }

    /** Marks one exact opaque provider lease for provider-controlled abort. */
    public void failSecretLease(CommandLease lease, String slot) {
        requireLease(lease);
        requireSlot(slot);
        try {
            transactions.executeWithoutResult(status -> {
                requireLiveJournal(lease, true);
                if (jdbc.update("""
                                UPDATE rg_api_connection_pending_secret_leases SET status='ABORT_REQUIRED',
                                     updated_at=CURRENT_TIMESTAMP
                                 WHERE command_id=? AND attempt_no=? AND attempt_token=? AND slot=?
                                   AND status='PENDING'
                                """, lease.commandId(), lease.attemptNo(), lease.attemptToken(), slot) != 1) {
                    fail(Code.STAGE_MISSING);
                }
            });
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    /** Deletes one exact aborted opaque provider lease after external cleanup succeeds. */
    public void cleanupSecretLease(CommandLease lease, String slot) {
        requireLease(lease);
        requireSlot(slot);
        try {
            transactions.executeWithoutResult(status -> {
                requireLiveJournal(lease, true);
                if (jdbc.update("""
                                DELETE FROM rg_api_connection_pending_secret_leases
                                 WHERE command_id=? AND attempt_no=? AND attempt_token=? AND slot=?
                                   AND status='ABORT_REQUIRED'
                                """, lease.commandId(), lease.attemptNo(), lease.attemptToken(), slot) != 1) {
                    fail(Code.STAGE_MISSING);
                }
            });
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw persistenceFailure(ex);
        }
    }

    private void requireLiveJournal(CommandLease lease, boolean forCommit) {
        CommandKey key = lease.key();
        Boolean exact = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM rg_authoring_command_journal
                         WHERE command_id=? AND tenant_id=? AND project_id=? AND environment_id=?
                           AND actor_id=? AND endpoint=? AND target_id=? AND idempotency_key=?
                           AND request_fingerprint=? AND attempt_no=? AND attempt_token=? AND lease_until=?
                           AND expected_mode=? AND expected_revision IS NOT DISTINCT FROM ?
                        """, Long.class, lease.commandId(), key.scope().tenantId(), key.scope().projectId(),
                key.scope().environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                key.idempotencyKey(), lease.requestFingerprint(), lease.attemptNo(), lease.attemptToken(),
                timestamp(lease.leaseUntil()), expectedMode(lease.expectedRevision()),
                expectedRevision(lease.expectedRevision())) > 0;
        if (Boolean.FALSE.equals(exact)) fail(Code.LEASE_FENCED);
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
        Instant expiry = jdbc.queryForObject("SELECT lease_until FROM rg_authoring_command_journal "
                + "WHERE command_id=?", (row, ignored) -> timestamp(row, 1), lease.commandId());
        if (expiry == null || !expiry.isAfter(databaseNow())) fail(Code.LEASE_EXPIRED);
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
                  JOIN rg_authoring_command_journal j
                    ON j.command_id=r.command_id AND j.status='COMMITTED'
                """;
        List<Object> args = new ArrayList<>(List.of(scope.tenantId(), scope.projectId(),
                scope.environmentId(), connectionId));
        if (revision == null) {
            sql += """

                  JOIN rg_api_connection_heads h
                    ON h.tenant_id=r.tenant_id AND h.project_id=r.project_id
                   AND h.environment_id=r.environment_id AND h.connection_id=r.connection_id
                   AND h.revision=r.revision AND h.command_id=r.command_id
                   AND h.strong_etag=r.strong_etag AND h.revision_state=r.state
                """;
        }
        sql += " WHERE r.tenant_id=? AND r.project_id=? AND r.environment_id=? AND r.connection_id=? "
                + "AND r.state='COMMITTED'";
        if (revision != null) args.add(revision);
        if (revision != null) sql += " AND r.revision=?";
        return jdbc.query(sql, revisionRowMapper(), args.toArray()).stream().findFirst()
                .map(row -> stored(row, bindingRows(scope, connectionId, row.revision())));
    }

    private List<BindingRow> bindingRows(AuthoringScope scope, String connectionId, long revision) {
        return jdbc.query("""
                        SELECT slot, provider_id, active_locator
                          FROM rg_api_connection_secret_bindings
                         WHERE tenant_id=? AND project_id=? AND environment_id=? AND connection_id=?
                           AND revision=? AND revision_state='COMMITTED'
                         ORDER BY slot
                        """, (row, ignored) -> new BindingRow(row.getString(1), row.getString(2),
                row.getString(3)), scope.tenantId(), scope.projectId(), scope.environmentId(),
                connectionId, revision);
    }

    private StoredApiConnection stored(RevisionRow row, List<BindingRow> bindings) {
        try {
            ApiConnectionView view = mapper.readValue(row.viewJson(), ApiConnectionView.class);
            Map<String, SecretReference> refs = new LinkedHashMap<>();
            bindings.forEach(binding -> refs.put(binding.slot(),
                    new SecretReference(row.scope(), binding.activeLocator())));
            ApiConnectionSpec authority = restoreSpec(row, refs);
            ApiConnectionView canonicalView = authority.view();
            if (view == null || !view.equals(canonicalView)
                    || !RECEIPT_SCHEMA.equals(row.receiptSchema())
                    || !ApiConnectionSpec.SCHEMA_VERSION.equals(authority.schemaVersion())
                    || !row.fingerprint().equals(decisions.fingerprint(authority))
                    || bindings.size() != authority.secretBindings().size()
                    || !row.receiptEtag().equals(row.strongEtag())
                    || !AuthoringFingerprints.of(mapper.readTree(row.receiptJson()))
                            .equals(row.receiptFingerprint())
                    || !mapper.readTree(row.receiptJson()).equals(mapper.readTree(row.viewJson()))) {
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

    private ApiConnectionSpec committedSpec(AuthoringScope scope, String connectionId) {
        String sql = "SELECT r.tenant_id, r.project_id, r.environment_id, r.connection_id, r.revision, "
                + "r.command_id, r.attempt_no, r.attempt_token, r.state, r.view_json, "
                + "r.metadata_fingerprint, r.base_url, r.defaults_headers_json, r.timeout_ms, r.auth_kind, "
                + "r.basic_username, r.api_key_header, r.strong_etag, NULL, NULL, NULL, NULL "
                + "FROM rg_api_connection_heads h JOIN rg_api_connection_revisions r "
                + "ON r.tenant_id=h.tenant_id AND r.project_id=h.project_id "
                + "AND r.environment_id=h.environment_id AND r.connection_id=h.connection_id "
                + "AND r.revision=h.revision AND r.command_id=h.command_id "
                + "AND r.strong_etag=h.strong_etag AND r.state='COMMITTED' "
                + "WHERE h.tenant_id=? AND h.project_id=? AND h.environment_id=? AND h.connection_id=?";
        List<RevisionRow> rows = jdbc.query(sql, revisionRowMapper(), scope.tenantId(), scope.projectId(),
                scope.environmentId(), connectionId);
        if (rows.isEmpty()) return null;
        RevisionRow row = rows.getFirst();
        Map<String, SecretReference> refs = new LinkedHashMap<>();
        bindingRows(scope, connectionId, row.revision())
                .forEach(binding -> refs.put(binding.slot(),
                        new SecretReference(scope, binding.activeLocator())));
        try {
            ApiConnectionSpec spec = restoreSpec(row, refs);
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
                + " FROM rg_api_connection_revisions r JOIN rg_authoring_command_journal j"
                + " ON j.command_id=r.command_id AND j.status='COMMITTED'"
                + " WHERE r.tenant_id=? AND r.project_id=? AND r.environment_id=?"
                + " AND r.connection_id=? AND r.revision=? AND r.state='COMMITTED'";
        List<RevisionRow> rows = jdbc.query(sql, revisionRowMapper(), scope.tenantId(), scope.projectId(),
                scope.environmentId(), connectionId, revision);
        if (rows.isEmpty()) return null;
        RevisionRow row = rows.getFirst();
        Map<String, SecretReference> refs = new LinkedHashMap<>();
        bindingRows(scope, connectionId, row.revision()).forEach(binding -> refs.put(binding.slot(),
                new SecretReference(scope, binding.activeLocator())));
        return restoreSpec(row, refs);
    }

    private ApiConnectionCommand restoreCommand(RevisionRow row, List<PendingSecretLease> leases) {
        try {
            ApiConnectionView view = mapper.readValue(row.viewJson(), ApiConnectionView.class);
            ApiConnectionCommand.Auth auth;
            if (row.authKind().equals("NONE")) {
                auth = ApiConnectionCommand.Auth.none();
            } else {
                String handle = leases.stream()
                        .filter(lease -> lease.slot().equals(authSlot(row.authKind())))
                        .map(PendingSecretLease::opaqueHandle)
                        .findFirst().orElseThrow();
                ApiConnectionCommand.SecretWrite write = switch (leases.stream()
                        .filter(lease -> lease.slot().equals(authSlot(row.authKind())))
                        .map(PendingSecretLease::sourceMode).findFirst().orElseThrow()) {
                    case "VALUE" -> ApiConnectionCommand.SecretWrite.value("previously-prepared");
                    case "SECRET_REF" -> ApiConnectionCommand.SecretWrite.secretRef(handle);
                    case "KEEP_EXISTING" -> ApiConnectionCommand.SecretWrite.keepExisting();
                    default -> {
                        fail(Code.INTEGRITY);
                        yield null;
                    }
                };
                auth = switch (row.authKind()) {
                    case "BEARER" -> ApiConnectionCommand.Auth.bearer(write);
                    case "BASIC" -> ApiConnectionCommand.Auth.basic(row.basicUsername(), write);
                    case "API_KEY" -> ApiConnectionCommand.Auth.apiKey(row.apiKeyHeader(), write);
                    default -> {
                        fail(Code.INTEGRITY);
                        yield null;
                    }
                };
            }
            return new ApiConnectionCommand(view.displayName(), row.baseUrl(), auth, defaults(row));
        } catch (ApiConnectionCommitStoreException ex) {
            throw ex;
        } catch (Exception ex) {
            throw persistenceFailure(ex);
        }
    }

    private PreparedSecretBinding[] preparedBindings(AuthoringScope scope, List<PendingSecretLease> leases) {
        return leases.stream()
                .map(lease -> new PreparedSecretBinding(lease.slot(),
                        new SecretReference(scope, lease.opaqueHandle())))
                .toArray(PreparedSecretBinding[]::new);
    }

    private ApiConnectionSpec restoreSpec(RevisionRow row, Map<String, SecretReference> references) {
        try {
            ApiConnectionView view = mapper.readValue(row.viewJson(), ApiConnectionView.class);
            ApiConnectionSpec authority = ApiConnectionSpec.restore(ApiConnectionSpec.SCHEMA_VERSION,
                    row.scope(), row.connectionId(),
                    Math.toIntExact(row.revision()), row.fingerprint(), view.displayName(), row.baseUrl(),
                    row.authKind(), row.basicUsername(), row.apiKeyHeader(), defaults(row), references);
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

    private static void requireSlot(String value) {
        if (!List.of("token", "password", "value").contains(value)) fail(Code.INTEGRITY);
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

    private static RowMapper<JournalRow> journalRowMapper() {
        return (row, ignored) -> new JournalRow(row.getString(1), row.getString(2), row.getString(3),
                row.getInt(4), row.getString(5), timestamp(row, 6), row.getString(7), nullableLong(row, 8));
    }

    private static RowMapper<RevisionRow> revisionRowMapper() {
        return (row, ignored) -> new RevisionRow(row.getString(1), row.getString(2), row.getString(3),
                row.getString(4), row.getLong(5), row.getString(6), row.getInt(7), row.getString(8),
                row.getString(9), row.getString(10), row.getString(11), row.getString(12), row.getString(13),
                row.getInt(14), row.getString(15), row.getString(16), row.getString(17), row.getString(18),
                row.getString(19), row.getString(20), row.getString(21), row.getString(22));
    }

    private static Long nullableLong(ResultSet row, int column) throws SQLException {
        long value = row.getLong(column);
        return row.wasNull() ? null : value;
    }

    private record PendingSecretLease(String connectionId, String slot, String sourceMode, String providerId,
                                      String leaseId, String opaqueHandle, Instant leaseUntil) { }
    private record BindingRow(String slot, String providerId, String activeLocator) { }
    private record RevisionRow(String tenantId, String projectId, String environmentId, String connectionId,
                               long revision, String commandId, int attemptNo, String attemptToken,
                               String state, String viewJson, String fingerprint, String baseUrl,
                               String defaultsHeadersJson, int timeoutMs, String authKind,
                               String basicUsername, String apiKeyHeader, String strongEtag,
                               String receiptSchema, String receiptJson, String receiptFingerprint,
                               String receiptEtag) {
        private AuthoringScope scope() {
            return new AuthoringScope(tenantId, projectId, environmentId);
        }
    }
}
