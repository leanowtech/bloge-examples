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
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

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
        if (!journal.live(clock.instant())) fail(PendingSecretStoreException.Code.LEASE_EXPIRED);
        List<Row> existing = rows(lease, true);
        if (!existing.isEmpty()) {
            if (restore(batch.lease(), existing).equals(batch)) return;
            fail(PendingSecretStoreException.Code.INTEGRITY);
        }
        if (outcome(lease) != null) fail(PendingSecretStoreException.Code.INTEGRITY);
        Map<String, ActiveSecretBinding> retained = retained(batch);
        for (PendingSecretOperation operation : batch.operations()) {
            insertRow(batch, operation, retained.get(operation.slot()));
        }
    }

    /** {@inheritDoc} */
    @Override public Optional<PendingSecretBatch> findExact(PendingSecretLease lease) {
        if (lease == null) return Optional.empty();
        try {
            List<Row> rows = rows(lease, false);
            return rows.isEmpty() ? Optional.empty() : Optional.of(restore(lease, rows));
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
        List<Row> rows = rows(lease, true);
        if (rows.isEmpty()) fail(PendingSecretStoreException.Code.STAGE_MISSING);
        if (!journal.exact(lease)) fail(PendingSecretStoreException.Code.LEASE_FENCED);
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
            Instant now = clock.instant();
            List<BatchKey> keys = jdbc.query("""
                    SELECT command_id, attempt_no, attempt_token
                      FROM rg_api_connection_pending_secret_leases
                     GROUP BY command_id, attempt_no, attempt_token
                     HAVING MIN(lease_until) <= ? OR MIN(CASE WHEN status='ABORT_REQUIRED'
                                                               THEN lease_until ELSE NULL END) IS NOT NULL
                     ORDER BY MIN(CASE WHEN status='ABORT_REQUIRED' THEN 0 ELSE 1 END),
                              MIN(lease_until), command_id, attempt_no, attempt_token, MIN(updated_at)
                    """, (row, ignored) -> new BatchKey(row.getString(1), row.getInt(2), row.getString(3)),
                    timestamp(now));
            List<SecretAbortCandidate> result = new ArrayList<>();
            for (BatchKey key : keys) {
                if (result.size() >= attemptLimit) break;
                List<Row> rows = rows(key, true);
                if (rows.isEmpty()) continue;
                boolean due = rows.stream().allMatch(row -> "ABORT_REQUIRED".equals(row.status()))
                        || rows.stream().allMatch(row -> !row.leaseUntil().isAfter(now));
                if (!due) continue;
                jdbc.update("UPDATE " + TABLE + " SET status='ABORT_REQUIRED', updated_at=CURRENT_TIMESTAMP"
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", key.commandId(), key.attemptNo(), key.attemptToken());
                result.add(new SecretAbortCandidate(restore(rows)));
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
        PendingSecretLease lease = candidate.batch().lease();
        Outcome done = outcome(lease);
        if (done != null) {
            if ("ABORTED".equals(done.outcome()) && done.fingerprint().equals(fingerprint(candidate.batch(), List.of()))) return;
            if ("COMMITTED".equals(done.outcome())) fail(PendingSecretStoreException.Code.RECOVERY_STATE);
            fail(PendingSecretStoreException.Code.INTEGRITY);
        }
        List<Row> rows = rows(lease, true);
        if (rows.isEmpty()) fail(PendingSecretStoreException.Code.STAGE_MISSING);
        if (!restore(lease, rows).equals(candidate.batch())) fail(PendingSecretStoreException.Code.LEASE_FENCED);
        if (!rows.stream().allMatch(row -> "ABORT_REQUIRED".equals(row.status()))) {
            fail(PendingSecretStoreException.Code.RECOVERY_STATE);
        }
        String fingerprint = fingerprint(candidate.batch(), List.of());
        jdbc.update("DELETE FROM rg_api_connection_secret_bindings WHERE tenant_id=? AND project_id=?"
                        + " AND environment_id=? AND connection_id=? AND revision=? AND command_id=?",
                scope(candidate.batch()).tenantId(), scope(candidate.batch()).projectId(), scope(candidate.batch()).environmentId(),
                coordinate(candidate.batch()).connectionId(), coordinate(candidate.batch()).revision(), lease.commandLease().commandId());
        jdbc.update("INSERT INTO " + OUTCOMES
                        + " (command_id, attempt_no, attempt_token, outcome, outcome_fingerprint, slots_csv) VALUES (?, ?, ?, 'ABORTED', ?, ?)",
                lease.commandLease().commandId(), lease.commandLease().attemptNo(), lease.commandLease().attemptToken(),
                fingerprint, slotsCsv(candidate.batch().operations()));
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
        List<Row> rows = rows(batch.lease(), requirePending);
        if (rows.isEmpty()) fail(PendingSecretStoreException.Code.STAGE_MISSING);
        PendingSecretBatch stored = restore(batch.lease(), rows);
        if (!stored.equals(batch)) fail(PendingSecretStoreException.Code.INTEGRITY);
        if (rows.stream().anyMatch(row -> !"PENDING".equals(row.status()))) fail(PendingSecretStoreException.Code.RECOVERY_STATE);
        if (rows.stream().anyMatch(row -> !row.leaseUntil().isAfter(clock.instant()))) fail(PendingSecretStoreException.Code.LEASE_EXPIRED);
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

    private void insertRow(PendingSecretBatch batch, PendingSecretOperation operation, ActiveSecretBinding retained) {
        PendingSecretLease lease = batch.lease();
        PreparedExternalSecret prepared = operation instanceof PendingSecretOperation.Prepared p ? p.prepared() : null;
        String sentinel = "__retained__";
        Instant until = prepared == null ? lease.commandLease().leaseUntil() : prepared.leaseUntil();
        ConnectionRevisionCoordinate source = operation instanceof PendingSecretOperation.Retained r ? r.source() : null;
        jdbc.update("INSERT INTO " + TABLE + " (tenant_id, project_id, environment_id, connection_id, revision,"
                        + " command_id, attempt_no, attempt_token, slot, source_mode, provider_id, lease_id, opaque_handle,"
                        + " status, lease_until, source_tenant_id, source_project_id, source_environment_id, source_connection_id, source_revision)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?)",
                scope(batch).tenantId(), scope(batch).projectId(), scope(batch).environmentId(), coordinate(batch).connectionId(),
                coordinate(batch).revision(), lease.commandLease().commandId(), lease.commandLease().attemptNo(),
                lease.commandLease().attemptToken(), operation.slot(), operation.mode().name(),
                prepared == null ? sentinel : prepared.providerId(), prepared == null ? sentinel : prepared.leaseId(),
                prepared == null ? sentinel : prepared.opaqueLocator(), timestamp(until),
                source == null ? null : source.scope().tenantId(), source == null ? null : source.scope().projectId(),
                source == null ? null : source.scope().environmentId(), source == null ? null : source.connectionId(),
                source == null ? null : source.revision());
    }

    private List<Row> rows(PendingSecretLease lease, boolean lock) {
        CommandLease command = lease.commandLease();
        return rows(new BatchKey(command.commandId(), command.attemptNo(), command.attemptToken()), lock);
    }

    private List<Row> rows(BatchKey key, boolean lock) {
        return jdbc.query("SELECT tenant_id, project_id, environment_id, connection_id, revision, command_id, attempt_no,"
                        + " attempt_token, slot, source_mode, provider_id, lease_id, opaque_handle, status, lease_until,"
                        + " source_tenant_id, source_project_id, source_environment_id, source_connection_id, source_revision"
                        + " FROM " + TABLE + " WHERE command_id=? AND attempt_no=? AND attempt_token=?"
                        + (lock ? " FOR UPDATE" : ""), this::row, key.commandId(), key.attemptNo(), key.attemptToken());
    }

    private PendingSecretBatch restore(PendingSecretLease lease, List<Row> rows) {
        return restoreWithLease(lease, rows);
    }

    private PendingSecretBatch restore(List<Row> rows) {
        Row first = rows.getFirst();
        Journal journal = journal(new BatchKey(first.commandId(), first.attemptNo(), first.attemptToken()), false);
        return restoreWithLease(journal.lease(first), rows);
    }

    private PendingSecretBatch restoreWithLease(PendingSecretLease lease, List<Row> rows) {
        List<PendingSecretOperation> operations = rows.stream().sorted(Comparator.comparing(Row::slot)).map(row -> {
            if ("KEEP_EXISTING".equals(row.sourceMode())) {
                if (row.sourceRevision() == null) fail(PendingSecretStoreException.Code.INTEGRITY);
                return new PendingSecretOperation.Retained(row.slot(), new ConnectionRevisionCoordinate(
                        new AuthoringScope(row.sourceTenant(), row.sourceProject(), row.sourceEnvironment()),
                        row.sourceConnection(), row.sourceRevision()));
            }
            SecretOperationContext context = new SecretOperationContext(lease.coordinate().scope(),
                    journal(lease, false).actorId, "connection-save", lease.coordinate().connectionId(),
                    lease.coordinate().revision(), lease.commandLease().commandId(), lease.commandLease().attemptNo(),
                    lease.commandLease().attemptToken(), row.slot());
            return new PendingSecretOperation.Prepared(row.slot(), SecretSourceMode.valueOf(row.sourceMode()),
                    new PreparedExternalSecret(row.providerId(), row.leaseId(), row.opaqueHandle(), row.leaseUntil(), context));
        }).map(operation -> (PendingSecretOperation) operation).toList();
        return new PendingSecretBatch(lease, operations);
    }

    private Journal journal(PendingSecretLease lease, boolean lock) {
        return jdbc.query("SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id,"
                        + " idempotency_key, request_fingerprint, lease_until, expected_mode, expected_revision,"
                        + " command_id, attempt_no, attempt_token FROM rg_authoring_command_journal WHERE command_id=?"
                        + (lock ? " FOR UPDATE" : ""), (row, ignored) -> new Journal(row), lease.commandLease().commandId())
                .stream().findFirst().orElseThrow(() -> failure(PendingSecretStoreException.Code.STAGE_MISSING));
    }

    private Journal journal(BatchKey key, boolean lock) {
        return jdbc.query("SELECT tenant_id, project_id, environment_id, actor_id, endpoint, target_id,"
                        + " idempotency_key, request_fingerprint, lease_until, expected_mode, expected_revision,"
                        + " command_id, attempt_no, attempt_token FROM rg_authoring_command_journal WHERE command_id=?"
                        + (lock ? " FOR UPDATE" : ""), (row, ignored) -> new Journal(row), key.commandId())
                .stream().findFirst().orElseThrow(() -> failure(PendingSecretStoreException.Code.STAGE_MISSING));
    }

    private Outcome outcome(PendingSecretLease lease) {
        return jdbc.query("SELECT outcome, outcome_fingerprint, slots_csv FROM " + OUTCOMES
                        + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", (row, ignored) ->
                        new Outcome(row.getString(1), row.getString(2), row.getString(3)),
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
        StringBuilder value = new StringBuilder(batch.lease().toString());
        for (PendingSecretOperation operation : batch.operations()) {
            value.append('|').append(operation.slot()).append('|').append(operation.mode());
            if (operation instanceof PendingSecretOperation.Prepared prepared) {
                value.append('|').append(prepared.prepared().providerId()).append('|').append(prepared.prepared().leaseId())
                        .append('|').append(prepared.prepared().opaqueLocator()).append('|').append(prepared.prepared().leaseUntil());
            } else if (operation instanceof PendingSecretOperation.Retained retained) value.append('|').append(retained.source());
        }
        for (ActivatedSecretSlot output : outputs) value.append('|').append(output.slot()).append('|')
                .append(output.activated().providerId()).append('|').append(output.activated().leaseId())
                .append('|').append(output.activated().activeLocator());
        return "sha256:" + HexFormat.of().formatHex(digest(value.toString()));
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
    private Row row(ResultSet rs, int ignored) throws java.sql.SQLException { return new Row(rs); }

    private record BatchKey(String commandId, int attemptNo, String attemptToken) { }
    private record Outcome(String outcome, String fingerprint, String slots) { }
    private record Row(String tenant, String project, String environment, String connectionId, long revision,
                       String commandId, int attemptNo, String attemptToken, String slot, String sourceMode,
                       String providerId, String leaseId, String opaqueHandle, String status, Instant leaseUntil,
                       String sourceTenant, String sourceProject, String sourceEnvironment, String sourceConnection,
                       Long sourceRevision) {
        Row(ResultSet rs) throws java.sql.SQLException { this(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getString(9), rs.getString(10),
                rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getTimestamp(15).toInstant(),
                rs.getString(16), rs.getString(17), rs.getString(18), rs.getString(19),
                rs.getObject(20, Long.class)); }
    }
    private final class Journal {
        private final String tenant, project, environment, actorId, endpoint, targetId, idempotency, requestFingerprint;
        private final Instant leaseUntil; private final String expectedMode; private final Long expectedRevision;
        private final String commandId, attemptToken; private final int attemptNo;
        Journal(ResultSet rs) throws java.sql.SQLException { tenant=rs.getString(1); project=rs.getString(2); environment=rs.getString(3);
            actorId=rs.getString(4); endpoint=rs.getString(5); targetId=rs.getString(6); idempotency=rs.getString(7);
            requestFingerprint=rs.getString(8); leaseUntil=rs.getTimestamp(9).toInstant(); expectedMode=rs.getString(10);
            expectedRevision=rs.getObject(11, Long.class); commandId=rs.getString(12); attemptNo=rs.getInt(13); attemptToken=rs.getString(14); }
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
        PendingSecretLease lease(Row row) { AuthoringScope s = new AuthoringScope(tenant, project, environment);
            CommandKey key = new CommandKey(s, actorId, AuthoringEndpoint.valueOf(endpoint), targetId, idempotency);
            ExpectedRevision expected = "CREATE".equals(expectedMode) ? ExpectedRevision.create() : ExpectedRevision.match(expectedRevision);
            return new PendingSecretLease(new CommandLease(commandId, attemptNo, attemptToken, key, requestFingerprint, leaseUntil, expected),
                    new ConnectionRevisionCoordinate(s, row.connectionId(), row.revision()), expected); }
    }
    private static String mode(ExpectedRevision e) { return e instanceof ExpectedRevision.Create ? "CREATE" : "MATCH"; }
    private static Long revision(ExpectedRevision e) { return e instanceof ExpectedRevision.Match m ? (long)m.revision() : null; }
}
