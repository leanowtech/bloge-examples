package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActivatedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.SecretOperationContext;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * PostgreSQL/H2-compatible durable implementation of {@link PendingSecretStore}.
 *
 * <p>The store persists provider preparation metadata as opaque handles only.
 * Provider lifecycle calls are deliberately outside this class and outside the
 * JDBC transaction. {@link #commitBindings(PendingSecretBatch, List)} is the
 * one explicit composite boundary: it requires the caller's exact
 * {@link DataSource} transaction so connection metadata, bindings and the
 * outer resource receipt can commit or roll back together.</p>
 */
public final class JdbcPendingSecretStore implements PendingSecretStore {
    private static final String TABLE = "rg_api_connection_pending_secret_leases";
    private static final String OUTCOMES = "rg_api_connection_pending_secret_outcomes";
    private static final Duration RECOVERY_CLAIM_TTL = Duration.ofSeconds(30);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String recoveryClaimOwner = UUID.randomUUID().toString();

    /** Creates a store whose JDBC template and transaction manager share one data source. */
    public JdbcPendingSecretStore(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        DataSource source = jdbc.getDataSource();
        DataSource transactionSource = transactions.getTransactionManager() instanceof DataSourceTransactionManager manager
                ? manager.getDataSource() : null;
        if (source == null || source != transactionSource) {
            throw new IllegalArgumentException("jdbc and transaction manager must share one DataSource");
        }
    }

    /** Creates a store with the standard Spring JDBC transaction manager. */
    public JdbcPendingSecretStore(DataSource dataSource, Clock clock) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource")),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), clock);
    }

    /** {@inheritDoc} */
    @Override public void stage(PendingSecretBatch batch) {
        requireBatch(batch);
        execute(() -> { stageInTransaction(batch); return null; });
    }

    private void stageInTransaction(PendingSecretBatch batch) {
        PendingSecretLease lease = batch.lease();
        Journal journal = journal(lease, true);
        if (!journal.exact(lease)) {
            if (journal.sameAuthority(lease, coordinate(batch))) fail(PendingSecretStoreException.Code.LEASE_FENCED);
            fail(PendingSecretStoreException.Code.INTEGRITY);
        }
        journal.requirePreparing();
        Instant databaseNow = databaseNow();
        if (!journal.live(databaseNow)) fail(PendingSecretStoreException.Code.LEASE_EXPIRED);
        List<Row> existing = rows(lease, true);
        if (!existing.isEmpty()) {
            if (existing.stream().anyMatch(row -> !"PENDING".equals(row.status()))) {
                fail(PendingSecretStoreException.Code.RECOVERY_STATE);
            }
            PendingSecretBatch stored = restore(existing);
            if (stored.equals(batch)) return;
            fail(PendingSecretStoreException.Code.INTEGRITY);
        }
        if (outcome(lease) != null) fail(PendingSecretStoreException.Code.INTEGRITY);
        Map<String, ActiveSecretBinding> retained = retained(batch);
        for (PendingSecretOperation operation : batch.operations()) {
            if (operation instanceof PendingSecretOperation.Prepared prepared
                    && !prepared.prepared().leaseUntil().isAfter(databaseNow)) {
                fail(PendingSecretStoreException.Code.LEASE_EXPIRED);
            }
            insertRow(batch, operation, retained.get(operation.slot()));
        }
    }

    /** {@inheritDoc} */
    @Override public Optional<PendingSecretBatch> findExact(PendingSecretLease lease) {
        if (lease == null) return Optional.empty();
        try {
            List<Row> rows = rows(lease, false);
            if (rows.isEmpty()) return Optional.empty();
            Journal journal = journal(lease, false);
            if (!journal.exact(lease)) return Optional.empty();
            PendingSecretBatch stored = restore(rows);
            return stored.lease().equals(lease) ? Optional.of(stored) : Optional.empty();
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    /** {@inheritDoc} */
    @Override public FinalizedSecretSlots prepareFinalization(PendingSecretBatch batch,
                                                               List<ActivatedSecretSlot> activated) {
        requireBatch(batch);
        return execute(() -> validateAndProof(batch, canonical(activated), false));
    }

    /**
     * Finalizes active bindings in the ambient coordinator transaction. Starting
     * a private transaction here would permit metadata and resource receipts to
     * diverge, so a missing exact transaction is a protocol error.
     */
    @Override public FinalizedSecretSlots commitBindings(PendingSecretBatch batch,
                                                          List<ActivatedSecretSlot> activated) {
        requireBatch(batch);
        requireAmbientTransaction();
        try {
            List<ActivatedSecretSlot> outputs = canonical(activated);
            Outcome done = outcome(batch.lease());
            String fingerprint = fingerprint(batch, outputs);
            if (done != null) {
                if (done.fingerprint().equals(fingerprint) && "COMMITTED".equals(done.outcome())) {
                    return proof(batch, done.slots());
                }
                if ("ABORTED".equals(done.outcome())) fail(PendingSecretStoreException.Code.RECOVERY_STATE);
                fail(PendingSecretStoreException.Code.INTEGRITY);
            }
            validateAndProof(batch, outputs, true);
            Map<String, ActiveSecretBinding> retained = retained(batch);
            requireBindingOwnership(batch);
            for (PendingSecretOperation operation : batch.operations()) {
                ActiveSecretBinding binding = operation instanceof PendingSecretOperation.Retained
                        ? Objects.requireNonNull(retained.get(operation.slot()))
                        : activated(outputs, operation.slot(), batch.lease().commandLease().commandId());
                jdbc.update("""
                        MERGE INTO rg_api_connection_secret_bindings AS target
                        USING (VALUES (?, ?, ?, ?, ?, 'COMMITTED', ?, ?, ?, ?)) AS source
                               (tenant_id, project_id, environment_id, connection_id, revision,
                                revision_state, slot, provider_id, active_locator, command_id)
                          ON target.tenant_id=source.tenant_id AND target.project_id=source.project_id
                         AND target.environment_id=source.environment_id AND target.connection_id=source.connection_id
                         AND target.revision=source.revision AND target.slot=source.slot
                        WHEN MATCHED THEN UPDATE SET provider_id=source.provider_id,
                             active_locator=source.active_locator, command_id=source.command_id,
                             updated_at=CURRENT_TIMESTAMP
                        WHEN NOT MATCHED THEN INSERT
                             (tenant_id, project_id, environment_id, connection_id, revision,
                              revision_state, slot, provider_id, active_locator, command_id)
                        VALUES (source.tenant_id, source.project_id, source.environment_id, source.connection_id,
                                source.revision, source.revision_state, source.slot, source.provider_id,
                                source.active_locator, source.command_id)
                        """, scope(batch).tenantId(), scope(batch).projectId(), scope(batch).environmentId(),
                        coordinate(batch).connectionId(), coordinate(batch).revision(), operation.slot(),
                        binding.providerId(), binding.activeLocator(), batch.lease().commandLease().commandId());
            }
            jdbc.update("INSERT INTO " + OUTCOMES
                    + " (command_id, attempt_no, attempt_token, outcome, outcome_fingerprint, slots_csv) VALUES (?, ?, ?, 'COMMITTED', ?, ?)",
                    batch.lease().commandLease().commandId(), batch.lease().commandLease().attemptNo(),
                    batch.lease().commandLease().attemptToken(), fingerprint, slotsCsv(batch.operations()));
            deleteRows(batch.lease());
            return proof(batch, batch.operations().stream().map(PendingSecretOperation::slot).toList());
        } catch (PendingSecretStoreException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw persistence(failure);
        }
    }

    /** {@inheritDoc} */
    @Override public void markAbortRequired(PendingSecretLease lease) {
        if (lease == null) fail(PendingSecretStoreException.Code.LEASE_FENCED);
        execute(() -> { markAbortInTransaction(lease); return null; });
    }

    private void markAbortInTransaction(PendingSecretLease lease) {
        Outcome done = outcome(lease);
        if (done != null) {
            if ("ABORTED".equals(done.outcome())) return;
            fail(PendingSecretStoreException.Code.RECOVERY_STATE);
        }
        Journal journal = journal(lease, true);
        journal.requirePreparing();
        List<Row> rows = rows(lease, true);
        if (rows.isEmpty()) fail(PendingSecretStoreException.Code.STAGE_MISSING);
        if (!journal.exact(lease)) fail(PendingSecretStoreException.Code.LEASE_FENCED);
        if (!restore(rows).lease().equals(lease)) fail(PendingSecretStoreException.Code.LEASE_FENCED);
        if (rows.stream().anyMatch(row -> !"ABORT_REQUIRED".equals(row.status())
                && !"PENDING".equals(row.status()))) fail(PendingSecretStoreException.Code.RECOVERY_STATE);
        jdbc.update("UPDATE " + TABLE + " SET status='ABORT_REQUIRED', updated_at=CURRENT_TIMESTAMP"
                + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", lease.commandLease().commandId(),
                lease.commandLease().attemptNo(), lease.commandLease().attemptToken());
    }

    /** {@inheritDoc} */
    @Override public List<SecretAbortCandidate> claimRecoveryDue(int attemptLimit) {
        if (attemptLimit < 1) fail(PendingSecretStoreException.Code.INTEGRITY);
        return execute(() -> {
            Instant now = databaseNow();
            List<BatchKey> keys = jdbc.query("""
                    SELECT p.command_id, p.attempt_no, p.attempt_token
                      FROM rg_api_connection_pending_secret_leases p
                     WHERE EXISTS (SELECT 1 FROM rg_authoring_command_journal j
                                     WHERE j.command_id=p.command_id
                                       AND j.attempt_no=p.attempt_no
                                       AND j.attempt_token=p.attempt_token
                                       AND j.status='PREPARING')
                     GROUP BY p.command_id, p.attempt_no, p.attempt_token
                     HAVING MIN(p.lease_until) <= ? OR SUM(CASE WHEN p.status='ABORT_REQUIRED' THEN 1 ELSE 0 END) = COUNT(*)
                    ORDER BY MIN(CASE WHEN p.status='ABORT_REQUIRED' THEN 0 ELSE 1 END),
                              MIN(p.lease_until), p.command_id, p.attempt_no, p.attempt_token, MIN(p.updated_at)
                     LIMIT ?
                    """, (row, ignored) -> new BatchKey(row.getString(1), row.getInt(2), row.getString(3)),
                    timestamp(now), attemptLimit);
            List<SecretAbortCandidate> result = new ArrayList<>();
            for (BatchKey key : keys) {
                Journal journal = journal(key, true);
                journal.requirePreparing();
                List<Row> rows = rows(key, true);
                if (rows.isEmpty()) continue;
                if (rows.stream().anyMatch(row -> !sameClaim(rows, row))) {
                    fail(PendingSecretStoreException.Code.INTEGRITY);
                }
                boolean due = rows.stream().allMatch(row -> "ABORT_REQUIRED".equals(row.status()))
                        || rows.stream().anyMatch(row -> !row.leaseUntil().isAfter(now));
                if (!due) continue;
                Row first = rows.getFirst();
                if (first.recoveryClaimUntil() != null && first.recoveryClaimUntil().isAfter(now)) continue;
                String token = UUID.randomUUID().toString();
                Instant claimUntil = now.plus(RECOVERY_CLAIM_TTL);
                int updated = jdbc.update("UPDATE " + TABLE + " SET status='ABORT_REQUIRED', recovery_claim_owner=?,"
                                + " recovery_claim_token=?, recovery_claim_until=?, updated_at=CURRENT_TIMESTAMP"
                                + " WHERE command_id=? AND attempt_no=? AND attempt_token=?"
                                + " AND (recovery_claim_until IS NULL OR recovery_claim_until <= ?)",
                        recoveryClaimOwner, token, timestamp(claimUntil), key.commandId(), key.attemptNo(),
                        key.attemptToken(), timestamp(now));
                if (updated != rows.size()) continue;
                result.add(new SecretAbortCandidate(restore(rows), token));
            }
            return List.copyOf(result);
        });
    }

    /** {@inheritDoc} */
    @Override public void completeAbort(SecretAbortCandidate candidate) {
        if (candidate == null) fail(PendingSecretStoreException.Code.INTEGRITY);
        execute(() -> { completeAbortInTransaction(candidate); return null; });
    }

    private void completeAbortInTransaction(SecretAbortCandidate candidate) {
        if (candidate.recoveryClaimToken() == null) fail(PendingSecretStoreException.Code.RECOVERY_STATE);
        PendingSecretLease lease = candidate.batch().lease();
        Outcome done = outcome(lease);
        if (done != null) {
            if ("ABORTED".equals(done.outcome()) && done.fingerprint().equals(fingerprint(candidate.batch(), List.of()))
                    && candidate.recoveryClaimToken().equals(done.recoveryClaimToken())) return;
            if ("COMMITTED".equals(done.outcome())) fail(PendingSecretStoreException.Code.RECOVERY_STATE);
            fail(PendingSecretStoreException.Code.INTEGRITY);
        }
        List<Row> rows = rows(lease, true);
        if (rows.isEmpty()) fail(PendingSecretStoreException.Code.STAGE_MISSING);
        Journal journal = journal(lease, true);
        journal.requirePreparing();
        PendingSecretBatch stored = restore(rows);
        if (!stored.equals(candidate.batch())) fail(PendingSecretStoreException.Code.LEASE_FENCED);
        if (!rows.stream().allMatch(row -> "ABORT_REQUIRED".equals(row.status()))
                || rows.stream().anyMatch(row -> !candidate.recoveryClaimToken().equals(row.recoveryClaimToken())
                || row.recoveryClaimUntil() == null || !row.recoveryClaimUntil().isAfter(databaseNow()))) {
            fail(PendingSecretStoreException.Code.RECOVERY_STATE);
        }
        String fingerprint = fingerprint(candidate.batch(), List.of());
        jdbc.update("DELETE FROM rg_api_connection_secret_bindings WHERE tenant_id=? AND project_id=?"
                        + " AND environment_id=? AND connection_id=? AND revision=? AND command_id=?",
                scope(candidate.batch()).tenantId(), scope(candidate.batch()).projectId(), scope(candidate.batch()).environmentId(),
                coordinate(candidate.batch()).connectionId(), coordinate(candidate.batch()).revision(), lease.commandLease().commandId());
        jdbc.update("INSERT INTO " + OUTCOMES
                        + " (command_id, attempt_no, attempt_token, outcome, outcome_fingerprint, slots_csv, recovery_claim_token) VALUES (?, ?, ?, 'ABORTED', ?, ?, ?)",
                lease.commandLease().commandId(), lease.commandLease().attemptNo(), lease.commandLease().attemptToken(),
                fingerprint, slotsCsv(candidate.batch().operations()), candidate.recoveryClaimToken());
        deleteRows(lease);
    }

    /** {@inheritDoc} */
    @Override public Optional<ActiveSecretBinding> findActive(ConnectionRevisionCoordinate coordinate, String slot) {
        if (coordinate == null || slot == null) return Optional.empty();
        PendingSecretOperation.SlotRules.require(slot);
        return jdbc.query("""
                SELECT provider_id, active_locator, command_id FROM rg_api_connection_secret_bindings
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND connection_id=? AND revision=? AND slot=?
                """, (row, ignored) -> new ActiveSecretBinding(row.getString(1), row.getString(2), row.getString(3)),
                coordinate.scope().tenantId(), coordinate.scope().projectId(), coordinate.scope().environmentId(),
                coordinate.connectionId(), coordinate.revision(), slot).stream().findFirst();
    }

    private FinalizedSecretSlots validateAndProof(PendingSecretBatch batch, List<ActivatedSecretSlot> outputs,
                                                   boolean requirePending) {
        Outcome done = outcome(batch.lease());
        if (done != null) {
            if ("COMMITTED".equals(done.outcome()) && done.fingerprint().equals(fingerprint(batch, outputs))) {
                return proof(batch, done.slots());
            }
            fail("ABORTED".equals(done.outcome()) ? PendingSecretStoreException.Code.RECOVERY_STATE
                    : PendingSecretStoreException.Code.INTEGRITY);
        }
        Journal journal = journal(batch.lease(), requirePending);
        if (!journal.exact(batch.lease())) {
            if (journal.sameAuthority(batch.lease(), coordinate(batch))) {
                fail(PendingSecretStoreException.Code.LEASE_FENCED);
            }
            fail(PendingSecretStoreException.Code.INTEGRITY);
        }
        journal.requirePreparing();
        List<Row> rows = rows(batch.lease(), requirePending);
        if (rows.isEmpty()) fail(PendingSecretStoreException.Code.STAGE_MISSING);
        PendingSecretBatch stored = restore(rows);
        if (!stored.equals(batch)) fail(PendingSecretStoreException.Code.INTEGRITY);
        if (!stored.lease().equals(batch.lease())) fail(PendingSecretStoreException.Code.LEASE_FENCED);
        if (rows.stream().anyMatch(row -> !"PENDING".equals(row.status()))) fail(PendingSecretStoreException.Code.RECOVERY_STATE);
        if (rows.stream().anyMatch(row -> !row.leaseUntil().isAfter(databaseNow()))) fail(PendingSecretStoreException.Code.LEASE_EXPIRED);
        validateActivation(batch, outputs);
        return proof(batch, batch.operations().stream().map(PendingSecretOperation::slot).toList());
    }

    private void validateActivation(PendingSecretBatch batch, List<ActivatedSecretSlot> outputs) {
        Set<String> expected = batch.operations().stream().filter(op -> op instanceof PendingSecretOperation.Prepared)
                .map(PendingSecretOperation::slot).collect(Collectors.toSet());
        Set<String> actual = new HashSet<>();
        for (ActivatedSecretSlot output : outputs) {
            if (!actual.add(output.slot()) || !expected.contains(output.slot())) fail(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
            PendingSecretOperation.Prepared prepared = (PendingSecretOperation.Prepared) batch.operation(output.slot());
            if (!prepared.prepared().providerId().equals(output.activated().providerId())
                    || !prepared.prepared().leaseId().equals(output.activated().leaseId())) fail(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
        }
        if (!actual.equals(expected)) fail(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
    }

    private Map<String, ActiveSecretBinding> retained(PendingSecretBatch batch) {
        Map<String, ActiveSecretBinding> result = new HashMap<>();
        for (PendingSecretOperation operation : batch.operations()) {
            if (!(operation instanceof PendingSecretOperation.Retained retained)) continue;
            ConnectionRevisionCoordinate source = retained.source();
            ConnectionRevisionCoordinate target = coordinate(batch);
            if (!source.scope().equals(target.scope()) || !source.connectionId().equals(target.connectionId())
                    || source.revision() != target.revision() - 1) fail(PendingSecretStoreException.Code.INTEGRITY);
            ActiveSecretBinding binding = findActive(source, operation.slot()).orElse(null);
            if (binding == null) fail(PendingSecretStoreException.Code.STAGE_MISSING);
            result.put(operation.slot(), binding);
        }
        return result;
    }

    /** Fences a competing command before any active binding can be overwritten. */
    private void requireBindingOwnership(PendingSecretBatch batch) {
        String commandId = batch.lease().commandLease().commandId();
        for (PendingSecretOperation operation : batch.operations()) {
            List<String> owners = jdbc.query("SELECT command_id FROM rg_api_connection_secret_bindings"
                            + " WHERE tenant_id=? AND project_id=? AND environment_id=? AND connection_id=?"
                            + " AND revision=? AND slot=? FOR UPDATE", (row, ignored) -> row.getString(1),
                    scope(batch).tenantId(), scope(batch).projectId(), scope(batch).environmentId(),
                    coordinate(batch).connectionId(), coordinate(batch).revision(), operation.slot());
            if (!owners.isEmpty() && !commandId.equals(owners.getFirst())) {
                fail(PendingSecretStoreException.Code.LEASE_FENCED);
            }
        }
    }

    private void insertRow(PendingSecretBatch batch, PendingSecretOperation operation, ActiveSecretBinding retained) {
        PendingSecretLease lease = batch.lease();
        PreparedExternalSecret prepared = operation instanceof PendingSecretOperation.Prepared p ? p.prepared() : null;
        SecretOperationContext context = prepared == null ? null : prepared.context();
        Instant until = prepared == null || lease.commandLease().leaseUntil().compareTo(prepared.leaseUntil()) <= 0
                ? lease.commandLease().leaseUntil() : prepared.leaseUntil();
        ConnectionRevisionCoordinate source = operation instanceof PendingSecretOperation.Retained r ? r.source() : null;
        jdbc.update("INSERT INTO " + TABLE + " (tenant_id, project_id, environment_id, connection_id, revision,"
                        + " command_id, attempt_no, attempt_token, slot, source_mode, provider_id, lease_id, opaque_handle,"
                        + " status, lease_until, provider_lease_until, source_tenant_id, source_project_id, source_environment_id, source_connection_id, source_revision,"
                        + " child_expected_mode, child_expected_revision, context_tenant_id, context_project_id, context_environment_id,"
                        + " context_actor_id, context_purpose, context_connection_id, context_revision, context_command_id,"
                        + " context_attempt_no, context_attempt_token)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                scope(batch).tenantId(), scope(batch).projectId(), scope(batch).environmentId(), coordinate(batch).connectionId(),
                coordinate(batch).revision(), lease.commandLease().commandId(), lease.commandLease().attemptNo(),
                lease.commandLease().attemptToken(), operation.slot(), operation.mode().name(),
                prepared == null ? null : prepared.providerId(), prepared == null ? null : prepared.leaseId(),
                prepared == null ? null : prepared.opaqueLocator(), timestamp(until),
                prepared == null ? null : timestamp(prepared.leaseUntil()),
                source == null ? null : source.scope().tenantId(), source == null ? null : source.scope().projectId(),
                source == null ? null : source.scope().environmentId(), source == null ? null : source.connectionId(),
                source == null ? null : source.revision(), mode(lease.connectionExpected()), revision(lease.connectionExpected()),
                context == null ? null : context.scope().tenantId(), context == null ? null : context.scope().projectId(),
                context == null ? null : context.scope().environmentId(), context == null ? null : context.actorId(),
                context == null ? null : context.purpose(), context == null ? null : context.connectionId(),
                context == null ? null : context.revision(), context == null ? null : context.commandId(),
                context == null ? null : context.attemptNo(), context == null ? null : context.attemptToken());
    }

    private List<Row> rows(PendingSecretLease lease, boolean lock) {
        CommandLease command = lease.commandLease();
        return rows(new BatchKey(command.commandId(), command.attemptNo(), command.attemptToken()), lock);
    }

    private List<Row> rows(BatchKey key, boolean lock) {
        return jdbc.query("SELECT tenant_id, project_id, environment_id, connection_id, revision, command_id, attempt_no,"
                        + " attempt_token, slot, source_mode, provider_id, lease_id, opaque_handle, status, lease_until, provider_lease_until,"
                        + " source_tenant_id, source_project_id, source_environment_id, source_connection_id, source_revision,"
                        + " child_expected_mode, child_expected_revision, recovery_claim_owner, recovery_claim_token, recovery_claim_until,"
                        + " context_tenant_id, context_project_id, context_environment_id, context_actor_id, context_purpose,"
                        + " context_connection_id, context_revision, context_command_id, context_attempt_no, context_attempt_token"
                        + " FROM " + TABLE + " WHERE command_id=? AND attempt_no=? AND attempt_token=?"
                        + " ORDER BY slot"
                        + (lock ? " FOR UPDATE" : ""), this::row, key.commandId(), key.attemptNo(), key.attemptToken());
    }

    private PendingSecretBatch restore(List<Row> rows) {
        Row first = rows.getFirst();
        Journal journal = journal(new BatchKey(first.commandId(), first.attemptNo(), first.attemptToken()), false);
        PendingSecretLease lease = journal.lease(first);
        if (rows.stream().anyMatch(row -> !lease.equals(journal.lease(row)))) fail(PendingSecretStoreException.Code.INTEGRITY);
        return restoreWithLease(lease, rows);
    }

    private PendingSecretBatch restoreWithLease(PendingSecretLease lease, List<Row> rows) {
        List<PendingSecretOperation> operations = rows.stream().sorted(Comparator.comparing(Row::slot)).map(row -> {
            if ("KEEP_EXISTING".equals(row.sourceMode())) {
                if (row.sourceRevision() == null) fail(PendingSecretStoreException.Code.INTEGRITY);
                return new PendingSecretOperation.Retained(row.slot(), new ConnectionRevisionCoordinate(
                        new AuthoringScope(row.sourceTenant(), row.sourceProject(), row.sourceEnvironment()),
                        row.sourceConnection(), row.sourceRevision()));
            }
            if (row.contextPurpose() == null || row.contextRevision() == null) fail(PendingSecretStoreException.Code.INTEGRITY);
            SecretOperationContext context = new SecretOperationContext(new AuthoringScope(row.contextTenant(),
                    row.contextProject(), row.contextEnvironment()), row.contextActorId(), row.contextPurpose(),
                    row.contextConnectionId(), row.contextRevision(), row.contextCommandId(), row.contextAttemptNo(),
                    row.contextAttemptToken(), row.slot());
            return new PendingSecretOperation.Prepared(row.slot(), SecretSourceMode.valueOf(row.sourceMode()),
                    new PreparedExternalSecret(row.providerId(), row.leaseId(), row.opaqueHandle(), row.providerLeaseUntil(), context));
        }).map(operation -> (PendingSecretOperation) operation).toList();
        return new PendingSecretBatch(lease, operations);
    }

    private Journal journal(PendingSecretLease lease, boolean lock) {
        return jdbc.query("SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id,"
                        + " idempotency_key, request_fingerprint, lease_until, expected_mode, expected_revision,"
                        + " command_id, attempt_no, attempt_token, status FROM rg_authoring_command_journal WHERE command_id=?"
                        + (lock ? " FOR UPDATE" : ""), (row, ignored) -> new Journal(row), lease.commandLease().commandId())
                .stream().findFirst().orElseThrow(() -> failure(PendingSecretStoreException.Code.STAGE_MISSING));
    }

    private Journal journal(BatchKey key, boolean lock) {
        return jdbc.query("SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id,"
                        + " idempotency_key, request_fingerprint, lease_until, expected_mode, expected_revision,"
                        + " command_id, attempt_no, attempt_token, status FROM rg_authoring_command_journal WHERE command_id=?"
                        + (lock ? " FOR UPDATE" : ""), (row, ignored) -> new Journal(row), key.commandId())
                .stream().findFirst().orElseThrow(() -> failure(PendingSecretStoreException.Code.STAGE_MISSING));
    }

    private Outcome outcome(PendingSecretLease lease) {
        return jdbc.query("SELECT outcome, outcome_fingerprint, slots_csv, recovery_claim_token FROM " + OUTCOMES
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", (row, ignored) ->
                        new Outcome(row.getString(1), row.getString(2), row.getString(3), row.getString(4)),
                lease.commandLease().commandId(), lease.commandLease().attemptNo(), lease.commandLease().attemptToken())
                .stream().findFirst().orElse(null);
    }

    private void deleteRows(PendingSecretLease lease) {
        jdbc.update("DELETE FROM " + TABLE + " WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                lease.commandLease().commandId(), lease.commandLease().attemptNo(), lease.commandLease().attemptToken());
    }

    private FinalizedSecretSlots proof(PendingSecretBatch batch, String slots) {
        Set<String> names = slots.isBlank() ? Set.of() : Set.of(slots.split(","));
        return FinalizedSecretSlots.from(batch.lease(), names);
    }

    private static FinalizedSecretSlots proof(PendingSecretBatch batch, List<String> slots) {
        return FinalizedSecretSlots.from(batch.lease(), new HashSet<>(slots));
    }

    private static ActiveSecretBinding activated(List<ActivatedSecretSlot> outputs, String slot, String commandId) {
        return outputs.stream().filter(value -> value.slot().equals(slot)).findFirst()
                .map(value -> new ActiveSecretBinding(value.activated().providerId(), value.activated().activeLocator(), commandId))
                .orElseThrow();
    }

    private static List<ActivatedSecretSlot> canonical(List<ActivatedSecretSlot> values) {
        return values == null ? List.of() : values.stream().sorted(Comparator.comparing(ActivatedSecretSlot::slot)).toList();
    }

    private static String slotsCsv(List<PendingSecretOperation> operations) {
        return operations.stream().map(PendingSecretOperation::slot).sorted().collect(Collectors.joining(","));
    }

    private static String fingerprint(PendingSecretBatch batch, List<ActivatedSecretSlot> outputs) {
        StringBuilder value = new StringBuilder();
        append(value, batch.lease().coordinate().scope().tenantId());
        append(value, batch.lease().coordinate().scope().projectId());
        append(value, batch.lease().coordinate().scope().environmentId());
        append(value, batch.lease().coordinate().connectionId());
        append(value, Long.toString(batch.lease().coordinate().revision()));
        CommandLease command = batch.lease().commandLease();
        append(value, command.commandId());
        append(value, Integer.toString(command.attemptNo()));
        append(value, command.attemptToken());
        append(value, command.key().scope().tenantId());
        append(value, command.key().scope().projectId());
        append(value, command.key().scope().environmentId());
        append(value, command.key().actorId());
        append(value, command.key().endpoint().name());
        append(value, command.key().targetId());
        append(value, command.key().idempotencyKey());
        append(value, command.requestFingerprint());
        append(value, command.leaseUntil().toString());
        append(value, mode(command.expectedRevision()));
        append(value, Objects.toString(revision(command.expectedRevision()), ""));
        append(value, mode(batch.lease().connectionExpected()));
        append(value, Objects.toString(revision(batch.lease().connectionExpected()), ""));
        for (PendingSecretOperation operation : batch.operations()) {
            append(value, operation.slot());
            append(value, operation.mode().name());
            if (operation instanceof PendingSecretOperation.Prepared prepared) {
                PreparedExternalSecret p = prepared.prepared();
                append(value, p.providerId()); append(value, p.leaseId()); append(value, p.opaqueLocator());
                append(value, p.leaseUntil().toString());
                SecretOperationContext c = p.context();
                append(value, c.scope().tenantId()); append(value, c.scope().projectId()); append(value, c.scope().environmentId());
                append(value, c.actorId()); append(value, c.purpose()); append(value, c.connectionId());
                append(value, Long.toString(c.revision())); append(value, c.commandId());
                append(value, Integer.toString(c.attemptNo())); append(value, c.attemptToken()); append(value, c.slot());
            } else if (operation instanceof PendingSecretOperation.Retained retained) {
                append(value, retained.source().scope().tenantId()); append(value, retained.source().scope().projectId());
                append(value, retained.source().scope().environmentId()); append(value, retained.source().connectionId());
                append(value, Long.toString(retained.source().revision()));
            }
        }
        for (ActivatedSecretSlot output : outputs) {
            append(value, output.slot()); append(value, output.activated().providerId());
            append(value, output.activated().leaseId()); append(value, output.activated().activeLocator());
        }
        return "sha256:" + HexFormat.of().formatHex(digest(value.toString())).toLowerCase(java.util.Locale.ROOT);
    }

    private static void append(StringBuilder target, String value) {
        String safe = Objects.requireNonNull(value, "fingerprint value");
        target.append(safe.length()).append(':').append(safe);
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private PendingSecretBatch requireBatch(PendingSecretBatch batch) {
        if (batch == null) fail(PendingSecretStoreException.Code.INTEGRITY);
        PendingSecretLease lease = batch.lease();
        CommandLease command = lease.commandLease();
        if (command.key().endpoint() != AuthoringEndpoint.API_CONNECTION_SAVE
                && command.key().endpoint() != AuthoringEndpoint.API_RESOURCE_SAVE) fail(PendingSecretStoreException.Code.INTEGRITY);
        if (command.key().endpoint() == AuthoringEndpoint.API_CONNECTION_SAVE
                && (!command.key().targetId().equals(lease.coordinate().connectionId())
                || !command.expectedRevision().equals(lease.connectionExpected()))) fail(PendingSecretStoreException.Code.INTEGRITY);
        if (command.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE
                && (!(lease.connectionExpected() instanceof ExpectedRevision.Create)
                || lease.coordinate().revision() != 1
                || batch.operations().stream().anyMatch(op -> op instanceof PendingSecretOperation.Retained))) {
            fail(PendingSecretStoreException.Code.INTEGRITY);
        }
        if (lease.connectionExpected() instanceof ExpectedRevision.Create) {
            if (lease.coordinate().revision() != 1 || batch.operations().stream()
                    .anyMatch(operation -> operation instanceof PendingSecretOperation.Retained)) {
                fail(PendingSecretStoreException.Code.INTEGRITY);
            }
        } else if (lease.connectionExpected() instanceof ExpectedRevision.Match match) {
            if (lease.coordinate().revision() != match.revision() + 1) fail(PendingSecretStoreException.Code.INTEGRITY);
        } else {
            fail(PendingSecretStoreException.Code.INTEGRITY);
        }
        for (PendingSecretOperation operation : batch.operations()) {
            if (operation instanceof PendingSecretOperation.Prepared prepared) {
                SecretOperationContext context = prepared.prepared().context();
                if (!lease.coordinate().scope().equals(context.scope())
                        || !lease.coordinate().connectionId().equals(context.connectionId())
                        || lease.coordinate().revision() != context.revision()
                        || !command.commandId().equals(context.commandId())
                        || command.attemptNo() != context.attemptNo()
                        || !command.attemptToken().equals(context.attemptToken())
                        || !command.key().actorId().equals(context.actorId())) {
                    fail(PendingSecretStoreException.Code.INTEGRITY);
                }
            }
        }
        return batch;
    }

    private void requireAmbientTransaction() {
        DataSource source = jdbc.getDataSource();
        if (source == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(source)) fail(PendingSecretStoreException.Code.TRANSACTION_REQUIRED);
    }

    private <T> T execute(java.util.function.Supplier<T> work) {
        try { return transactions.execute(status -> work.get()); }
        catch (PendingSecretStoreException failure) { throw failure; }
        catch (RuntimeException failure) { throw persistence(failure); }
    }

    private static PendingSecretStoreException failure(PendingSecretStoreException.Code code) { return new PendingSecretStoreException(code); }
    private static void fail(PendingSecretStoreException.Code code) { throw failure(code); }
    private static void fail(String ignored) { fail(PendingSecretStoreException.Code.INTEGRITY); }
    private static RuntimeException persistence(RuntimeException failure) {
        if (failure instanceof PendingSecretStoreException) return failure;
        return failure(PendingSecretStoreException.Code.INTEGRITY);
    }
    private AuthoringScope scope(PendingSecretBatch batch) { return batch.lease().coordinate().scope(); }
    private ConnectionRevisionCoordinate coordinate(PendingSecretBatch batch) { return batch.lease().coordinate(); }
    private java.sql.Timestamp timestamp(Instant instant) { return java.sql.Timestamp.from(instant); }
    private Instant databaseNow() {
        java.sql.Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", java.sql.Timestamp.class);
        return Objects.requireNonNull(now, "database clock").toInstant();
    }
    private static boolean sameClaim(List<Row> rows, Row row) {
        Row first = rows.getFirst();
        return Objects.equals(first.recoveryClaimOwner(), row.recoveryClaimOwner())
                && Objects.equals(first.recoveryClaimToken(), row.recoveryClaimToken())
                && Objects.equals(first.recoveryClaimUntil(), row.recoveryClaimUntil());
    }
    private Row row(ResultSet rs, int ignored) throws java.sql.SQLException { return new Row(rs); }

    private record BatchKey(String commandId, int attemptNo, String attemptToken) { }
    private record Outcome(String outcome, String fingerprint, String slots, String recoveryClaimToken) { }
    private record Row(String tenant, String project, String environment, String connectionId, long revision,
                       String commandId, int attemptNo, String attemptToken, String slot, String sourceMode,
                       String providerId, String leaseId, String opaqueHandle, String status, Instant leaseUntil,
                       Instant providerLeaseUntil, String sourceTenant, String sourceProject, String sourceEnvironment, String sourceConnection,
                       Long sourceRevision, String childExpectedMode, Long childExpectedRevision,
                       String recoveryClaimOwner, String recoveryClaimToken, Instant recoveryClaimUntil,
                       String contextTenant, String contextProject, String contextEnvironment, String contextActorId,
                       String contextPurpose, String contextConnectionId, Long contextRevision, String contextCommandId,
                       Integer contextAttemptNo, String contextAttemptToken) {
        Row(ResultSet rs) throws java.sql.SQLException { this(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getTimestamp(15).toInstant(),
                rs.getTimestamp(16) == null ? null : rs.getTimestamp(16).toInstant(),
                rs.getString(17), rs.getString(18), rs.getString(19), rs.getString(20), rs.getObject(21, Long.class),
                rs.getString(22), rs.getObject(23, Long.class), rs.getString(24), rs.getString(25),
                rs.getTimestamp(26) == null ? null : rs.getTimestamp(26).toInstant(),
                rs.getString(27), rs.getString(28), rs.getString(29), rs.getString(30), rs.getString(31),
                rs.getString(32), rs.getObject(33, Long.class), rs.getString(34), rs.getObject(35, Integer.class),
                rs.getString(36)); }
    }
    private final class Journal {
        private final String tenant, project, environment, actorId, endpoint, targetId, idempotency, requestFingerprint;
        private final Instant leaseUntil; private final String expectedMode; private final Long expectedRevision;
        private final String commandId, attemptToken, status; private final int attemptNo;
        Journal(ResultSet rs) throws java.sql.SQLException { tenant=rs.getString(1); project=rs.getString(2); environment=rs.getString(3);
            actorId=rs.getString(4); endpoint=rs.getString(5); targetId=rs.getString(6); idempotency=rs.getString(7);
            requestFingerprint=rs.getString(8); leaseUntil=rs.getTimestamp(9).toInstant(); expectedMode=rs.getString(10);
            expectedRevision=rs.getObject(11, Long.class); commandId=rs.getString(12); attemptNo=rs.getInt(13);
            attemptToken=rs.getString(14); status=rs.getString(15); }
        boolean exact(PendingSecretLease lease) { CommandLease c=lease.commandLease(); CommandKey k=c.key();
            return tenant.equals(k.scope().tenantId())&&project.equals(k.scope().projectId())&&environment.equals(k.scope().environmentId())
                    &&actorId.equals(k.actorId())&&endpoint.equals(k.endpoint().name())&&targetId.equals(k.targetId())
                    &&idempotency.equals(k.idempotencyKey())&&requestFingerprint.equals(c.requestFingerprint())
                    &&commandId.equals(c.commandId())&&attemptNo==c.attemptNo()&&attemptToken.equals(c.attemptToken())
                    &&leaseUntil.equals(c.leaseUntil())&&expectedMode.equals(mode(c.expectedRevision()))&&Objects.equals(expectedRevision, revision(c.expectedRevision())); }
        boolean sameAuthority(PendingSecretLease lease, ConnectionRevisionCoordinate coordinate) {
            CommandKey k = lease.commandLease().key();
            return commandId.equals(lease.commandLease().commandId()) && tenant.equals(k.scope().tenantId())
                    && project.equals(k.scope().projectId()) && environment.equals(k.scope().environmentId())
                    && actorId.equals(k.actorId()) && endpoint.equals(k.endpoint().name())
                    && targetId.equals(k.targetId()) && idempotency.equals(k.idempotencyKey())
                    && requestFingerprint.equals(lease.commandLease().requestFingerprint())
                    && tenant.equals(coordinate.scope().tenantId()) && project.equals(coordinate.scope().projectId())
                    && environment.equals(coordinate.scope().environmentId())
                    && (endpoint.equals(AuthoringEndpoint.API_RESOURCE_SAVE.name()) || targetId.equals(coordinate.connectionId()));
        }
        boolean live(Instant now) { return leaseUntil.isAfter(now); }
        /** Keeps all pending mutations behind the journal's single live state. */
        void requirePreparing() {
            if (!"PREPARING".equals(status)) fail(PendingSecretStoreException.Code.RECOVERY_STATE);
        }
        /**
         * Reconstructs the outer command lease from journal authority and the
         * child CAS from the row.  Resource saves intentionally use different
         * values for those two fences.
         */
        PendingSecretLease lease(Row row) { AuthoringScope s = new AuthoringScope(tenant, project, environment);
            CommandKey key = new CommandKey(s, actorId, AuthoringEndpoint.valueOf(endpoint), targetId, idempotency);
            ExpectedRevision outerExpected = expected(expectedMode, expectedRevision);
            ExpectedRevision childExpected = expected(row.childExpectedMode(), row.childExpectedRevision());
            return new PendingSecretLease(new CommandLease(commandId, attemptNo, attemptToken, key,
                    requestFingerprint, leaseUntil, outerExpected),
                    new ConnectionRevisionCoordinate(s, row.connectionId(), row.revision()), childExpected); }
    }
    private static ExpectedRevision expected(String mode, Long revision) {
        if ("CREATE".equals(mode) && revision == null) return ExpectedRevision.create();
        if ("MATCH".equals(mode) && revision != null && revision > 0) return ExpectedRevision.match(revision);
        fail(PendingSecretStoreException.Code.INTEGRITY);
        return null;
    }
    private static String mode(ExpectedRevision e) { return e instanceof ExpectedRevision.Create ? "CREATE" : "MATCH"; }
    private static Long revision(ExpectedRevision e) { return e instanceof ExpectedRevision.Match m ? (long)m.revision() : null; }
}
