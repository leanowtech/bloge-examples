package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository.ActiveWorkerCandidateQuarantine;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Database-authoritative maintenance control plane for exact-checkpoint worker quarantines.
 *
 * <p>The automatic worker path owns the immutable quarantine fact. This control plane stores
 * operational ownership in a separate, cascading row so maintenance cannot silently rewrite the
 * failure reason, threshold, or checkpoint binding. Every read revalidates both the automatic
 * fact and any maintenance projection before returning payload-free metadata.</p>
 */
public final class DatabaseDurableWorkerQuarantineControlPlane {

    private static final int MAX_PAGE_SIZE = 1_000;
    private static final Pattern REASON_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DurableTestExecutionCheckpointIntegrity checkpointIntegrity;
    private final TransactionTemplate transactions;
    private final TransactionTemplate observations;

    /**
     * Creates a quarantine maintenance authority over the isolated test-runtime database.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param transactionManager transaction manager for the same datasource
     * @param objectMapper canonical protocol fingerprint mapper
     */
    public DatabaseDurableWorkerQuarantineControlPlane(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.checkpointIntegrity = new DurableTestExecutionCheckpointIntegrity(objectMapper);
        PlatformTransactionManager safeTransactionManager =
                Objects.requireNonNull(transactionManager, "transactionManager");
        this.transactions = new TransactionTemplate(safeTransactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.observations = new TransactionTemplate(safeTransactionManager);
        this.observations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.observations.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** Creates maintenance ownership state without modifying automatic quarantine facts. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_controls (
                    scope_key VARCHAR(80) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    control_state VARCHAR(32) NOT NULL,
                    claim_owner VARCHAR(255) NOT NULL,
                    claim_token VARCHAR(255) NOT NULL,
                    claim_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    control_version BIGINT NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (scope_key, run_id),
                    CONSTRAINT fk_rg_test_worker_quarantine_control
                        FOREIGN KEY (scope_key, run_id)
                        REFERENCES rg_test_durable_worker_candidate_quarantines
                            (scope_key, run_id)
                        ON DELETE CASCADE
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_claim_commands (
                    scope_key VARCHAR(80) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    claim_owner VARCHAR(255) NOT NULL,
                    claim_duration_seconds BIGINT NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    result_claim_token VARCHAR(255) NOT NULL,
                    result_version BIGINT NOT NULL,
                    result_claim_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (scope_key, client_request_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_control_queue_idx
                ON rg_test_durable_worker_quarantine_controls
                    (control_state, claim_until, scope_key, run_id)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_resolutions (
                    scope_key VARCHAR(80) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    resolution_owner VARCHAR(255) NOT NULL,
                    resolution_action VARCHAR(32) NOT NULL,
                    reason_code VARCHAR(128) NOT NULL,
                    request_fingerprint VARCHAR(80) NOT NULL,
                    result_version BIGINT NOT NULL,
                    result_acted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    result_receipt_fingerprint VARCHAR(80) NOT NULL,
                    record_fingerprint VARCHAR(80) NOT NULL,
                    PRIMARY KEY (scope_key, client_request_id),
                    CONSTRAINT uq_rg_test_worker_quarantine_receipt
                        UNIQUE (result_receipt_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_durable_worker_quarantine_history (
                    history_id VARCHAR(36) PRIMARY KEY,
                    scope_key VARCHAR(80) NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    run_id VARCHAR(255) NOT NULL,
                    checkpoint_fingerprint VARCHAR(80) NOT NULL,
                    quarantine_reason VARCHAR(64) NOT NULL,
                    consecutive_failures BIGINT NOT NULL,
                    quarantine_threshold INT NOT NULL,
                    first_observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    quarantined_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    resolution_action VARCHAR(32) NOT NULL,
                    reason_code VARCHAR(128) NOT NULL,
                    resolution_owner VARCHAR(255) NOT NULL,
                    result_version BIGINT NOT NULL,
                    acted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    receipt_fingerprint VARCHAR(80) NOT NULL UNIQUE,
                    record_fingerprint VARCHAR(80) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_worker_quarantine_history_scope_idx
                ON rg_test_durable_worker_quarantine_history
                    (scope_key, acted_at, history_id)
                """);
    }

    /**
     * Lists one bounded, payload-free quarantine page in an exact verified worker scope.
     *
     * <p>An expired claim is projected as {@link QuarantineState#AVAILABLE}; list operations do
     * not mutate durable ownership. The next claim command advances the persisted version while
     * consuming the expired fence.</p>
     *
     * @param scope tenant, organization, project, and non-production environment authority
     * @param actionableOnly whether to omit quarantines with a live maintenance owner
     * @param limit page size from 1 through 1,000
     * @return oldest quarantines first
     */
    public List<QuarantineRecord> quarantines(
            WorkerAcquisitionScope scope, boolean actionableOnly, int limit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        int safeLimit = bounded(limit);
        List<QuarantineRecord> result = observations.execute(status -> {
            Instant observedAt = databaseNow();
            String actionablePredicate = actionableOnly
                    ? """
                       AND (c.scope_key IS NULL OR c.control_state = 'AVAILABLE'
                            OR (c.control_state = 'CLAIMED' AND c.claim_until <= ?))
                       """ : "";
            String sql = quarantineSelect() + """
                    WHERE q.scope_key = ? AND q.tenant_id = ? AND q.environment_id = ?
                      AND q.organization_id = ? AND q.project_id = ?
                    """ + actionablePredicate + """
                    ORDER BY q.quarantined_at, q.run_id
                    LIMIT ?
                    """;
            Object[] arguments = actionableOnly
                    ? new Object[]{scopeKey(safeScope), safeScope.tenantId(),
                    safeScope.environmentId(), safeScope.organizationId(), safeScope.projectId(),
                    Timestamp.from(observedAt), safeLimit}
                    : new Object[]{scopeKey(safeScope), safeScope.tenantId(),
                    safeScope.environmentId(), safeScope.organizationId(), safeScope.projectId(),
                    safeLimit};
            return jdbc.query(sql, (rs, rowNumber) -> mapQuarantine(rs, observedAt), arguments);
        });
        if (result == null) {
            throw new IllegalStateException("Worker quarantine observation returned no result");
        }
        return List.copyOf(result);
    }

    /**
     * Idempotently claims one exact-checkpoint quarantine for a verified maintenance actor.
     *
     * <p>The checkpoint authority is locked and integrity-verified before the quarantine and its
     * control row. A fresh claim, its immutable command receipt, and the supplied audit mutation
     * therefore commit or roll back together. Exact retries return the original server token;
     * request-ID drift never reaches the active record.</p>
     *
     * @param scope verified tenant, organization, project, and environment authority
     * @param key exact run and checkpoint identity from the payload-free list
     * @param ownerId verified workload actor, never caller-selected request data
     * @param clientRequestId caller-stable idempotency key
     * @param claimDuration database-clock lease from one second through one hour
     * @param committedAudit transaction-bound audit mutation for a fresh claim
     * @return claimed, replayed, conflicting, stale, or non-actionable disposition
     */
    public QuarantineClaimResult claim(
            WorkerAcquisitionScope scope,
            QuarantineKey key,
            String ownerId,
            String clientRequestId,
            Duration claimDuration,
            Function<QuarantineClaim, TestRuntimeTransactionMutation> committedAudit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        QuarantineKey safeKey = Objects.requireNonNull(key, "key");
        String safeOwner = required(ownerId, "ownerId", 255);
        String safeRequestId = required(clientRequestId, "clientRequestId", 255);
        Duration safeDuration = boundedLease(claimDuration);
        Function<QuarantineClaim, TestRuntimeTransactionMutation> safeAudit =
                Objects.requireNonNull(committedAudit, "committedAudit");
        String requestFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineClaimIntent.v1"),
                Map.entry("scope", safeScope), Map.entry("key", safeKey),
                Map.entry("ownerId", safeOwner),
                Map.entry("claimDurationSeconds", safeDuration.toSeconds())));
        QuarantineClaimResult result = transactions.execute(status -> {
            Optional<StoredClaimCommand> replay = findClaimCommand(
                    safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredClaimCommand stored = replay.orElseThrow();
                requireValid(stored);
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? QuarantineClaimResult.replay(stored.claim())
                        : QuarantineClaimResult.conflict();
            }
            if (!lockExactCheckpoint(safeScope, safeKey)) {
                return QuarantineClaimResult.staleCheckpoint();
            }
            replay = findClaimCommand(safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredClaimCommand stored = replay.orElseThrow();
                requireValid(stored);
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? QuarantineClaimResult.replay(stored.claim())
                        : QuarantineClaimResult.conflict();
            }
            StoredQuarantine quarantine = findQuarantine(
                    safeScope, safeKey, true).orElse(null);
            if (quarantine == null) {
                return QuarantineClaimResult.notActionable();
            }
            requireValid(quarantine);
            Instant now = databaseNow();
            StoredControl current = findControl(safeScope, safeKey.runId(), true).orElse(null);
            if (current != null) {
                requireValid(current, quarantine);
                if (current.state() == QuarantineState.CLAIMED
                        && current.claimUntil().isAfter(now)) {
                    return QuarantineClaimResult.notActionable();
                }
            }
            long nextVersion = Math.addExact(current == null ? 0 : current.version(), 1);
            String token = UUID.randomUUID().toString();
            Instant claimUntil = now.plus(safeDuration);
            StoredControl claimed = storedControl(quarantine, QuarantineState.CLAIMED,
                    safeOwner, token, claimUntil, nextVersion);
            persistControl(current, claimed);
            QuarantineClaim claim = new QuarantineClaim(
                    safeKey, safeOwner, token, nextVersion, claimUntil);
            StoredClaimCommand command = storedClaimCommand(
                    safeScope, safeRequestId, safeKey, safeOwner, safeDuration,
                    requestFingerprint, claim, now);
            insertClaimCommand(command);
            Objects.requireNonNull(safeAudit.apply(claim), "committedAudit result").apply(jdbc);
            return QuarantineClaimResult.claimed(claim);
        });
        if (result == null) {
            throw new IllegalStateException("Worker quarantine claim returned no result");
        }
        return result;
    }

    /**
     * Resolves one exact live maintenance claim with an immutable token-free receipt.
     *
     * <p>{@link ResolutionAction#RELEASE} returns the quarantine to the actionable maintenance
     * queue while preserving worker suppression. {@link ResolutionAction#DISCARD} removes only
     * the exact checkpoint quarantine and is therefore the explicit operator override that makes
     * the unchanged checkpoint eligible again. The checkpoint, quarantine, claim, command
     * receipt, history row, and audit mutation share one database transaction.</p>
     *
     * @param scope verified worker scope
     * @param claim exact server-issued owner, token, version, and expiry fence
     * @param clientRequestId caller-stable idempotency key
     * @param action release ownership or discard the exact quarantine
     * @param reasonCode bounded non-payload operational rationale
     * @param committedAudit transaction-bound audit mutation for a fresh resolution
     * @return resolved, replayed, conflicting, stale, or fence-rejected result
     */
    public QuarantineResolutionResult resolve(
            WorkerAcquisitionScope scope,
            QuarantineClaim claim,
            String clientRequestId,
            ResolutionAction action,
            String reasonCode,
            Function<QuarantineResolutionReceipt, TestRuntimeTransactionMutation>
                    committedAudit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        QuarantineClaim safeClaim = Objects.requireNonNull(claim, "claim");
        String safeRequestId = required(clientRequestId, "clientRequestId", 255);
        ResolutionAction safeAction = Objects.requireNonNull(action, "action");
        String safeReason = required(reasonCode, "reasonCode", 128).toUpperCase(Locale.ROOT);
        if (!REASON_CODE.matcher(safeReason).matches()) {
            throw new IllegalArgumentException("Worker quarantine reasonCode is invalid");
        }
        Function<QuarantineResolutionReceipt, TestRuntimeTransactionMutation> safeAudit =
                Objects.requireNonNull(committedAudit, "committedAudit");
        String requestFingerprint = resolutionRequestFingerprint(
                safeScope, safeClaim, safeAction, safeReason);
        QuarantineResolutionResult result = transactions.execute(status -> {
            Optional<StoredResolutionCommand> replay = findResolutionCommand(
                    safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredResolutionCommand stored = replay.orElseThrow();
                requireValid(stored);
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? QuarantineResolutionResult.replay(stored.receipt())
                        : QuarantineResolutionResult.conflict();
            }
            if (!lockExactCheckpoint(safeScope, safeClaim.key())) {
                return QuarantineResolutionResult.staleCheckpoint();
            }
            replay = findResolutionCommand(safeScope, safeRequestId, true);
            if (replay.isPresent()) {
                StoredResolutionCommand stored = replay.orElseThrow();
                requireValid(stored);
                return stored.requestFingerprint().equals(requestFingerprint)
                        ? QuarantineResolutionResult.replay(stored.receipt())
                        : QuarantineResolutionResult.conflict();
            }
            StoredQuarantine quarantine = findQuarantine(
                    safeScope, safeClaim.key(), true).orElse(null);
            if (quarantine == null) {
                return QuarantineResolutionResult.fenceRejected();
            }
            requireValid(quarantine);
            StoredControl control = findControl(
                    safeScope, safeClaim.key().runId(), true).orElse(null);
            Instant now = databaseNow();
            if (control == null) {
                return QuarantineResolutionResult.fenceRejected();
            }
            requireValid(control, quarantine);
            if (control.state() != QuarantineState.CLAIMED
                    || !control.claimOwner().equals(safeClaim.ownerId())
                    || !control.claimToken().equals(safeClaim.claimToken())
                    || control.version() != safeClaim.version()
                    || !control.claimUntil().equals(safeClaim.claimUntil())
                    || !control.claimUntil().isAfter(now)) {
                return QuarantineResolutionResult.fenceRejected();
            }
            long nextVersion = Math.addExact(control.version(), 1);
            QuarantineResolutionReceipt receipt = resolutionReceipt(
                    safeScope, safeClaim.key(), safeClaim.ownerId(), safeAction, safeReason,
                    nextVersion, now);
            StoredResolutionCommand command = storedResolutionCommand(
                    safeScope, safeRequestId, safeClaim.key(), safeClaim.ownerId(), safeAction,
                    safeReason, requestFingerprint, receipt);
            StoredHistory history = storedHistory(safeScope, quarantine, receipt);
            if (safeAction == ResolutionAction.RELEASE) {
                persistControl(control, storedControl(quarantine, QuarantineState.AVAILABLE,
                        "", "", Instant.EPOCH, nextVersion));
            } else {
                deleteQuarantine(quarantine);
            }
            insertResolutionCommand(command);
            insertHistory(history);
            Objects.requireNonNull(safeAudit.apply(receipt), "committedAudit result").apply(jdbc);
            return QuarantineResolutionResult.resolved(receipt);
        });
        if (result == null) {
            throw new IllegalStateException("Worker quarantine resolution returned no result");
        }
        return result;
    }

    /**
     * Lists immutable, token-free manual action history in one verified worker scope.
     *
     * @param scope exact tenant, organization, project, and environment authority
     * @param limit page size from 1 through 1,000
     * @return newest action receipts first
     */
    public List<QuarantineHistoryRecord> history(
            WorkerAcquisitionScope scope, int limit) {
        WorkerAcquisitionScope safeScope = Objects.requireNonNull(scope, "scope");
        List<QuarantineHistoryRecord> result = observations.execute(status -> jdbc.query("""
                        SELECT history_id, scope_key, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               quarantine_reason, consecutive_failures, quarantine_threshold,
                               first_observed_at, quarantined_at, resolution_action, reason_code,
                               resolution_owner, result_version, acted_at, receipt_fingerprint,
                               record_fingerprint
                        FROM rg_test_durable_worker_quarantine_history
                        WHERE scope_key = ? AND tenant_id = ? AND environment_id = ?
                          AND organization_id = ? AND project_id = ?
                        ORDER BY acted_at DESC, history_id
                        LIMIT ?
                        """, this::mapHistory, scopeKey(safeScope), safeScope.tenantId(),
                safeScope.environmentId(), safeScope.organizationId(), safeScope.projectId(),
                bounded(limit)));
        if (result == null) {
            throw new IllegalStateException("Worker quarantine history returned no result");
        }
        result.forEach(this::requireValidHistoryRecord);
        return List.copyOf(result);
    }

    private String quarantineSelect() {
        return """
                SELECT q.scope_key, q.tenant_id, q.environment_id, q.organization_id,
                       q.project_id, q.run_id, q.checkpoint_fingerprint, q.reason,
                       q.consecutive_failures, q.quarantine_threshold, q.first_observed_at,
                       q.quarantined_at, q.record_fingerprint,
                       c.checkpoint_fingerprint AS control_checkpoint_fingerprint,
                       c.control_state, c.claim_owner, c.claim_token, c.claim_until,
                       c.control_version, c.record_fingerprint AS control_record_fingerprint
                FROM rg_test_durable_worker_candidate_quarantines q
                LEFT JOIN rg_test_durable_worker_quarantine_controls c
                  ON c.scope_key = q.scope_key AND c.run_id = q.run_id
                """;
    }

    private QuarantineRecord mapQuarantine(
            ResultSet rs, Instant observedAt) throws SQLException {
        StoredQuarantine stored = mapStoredQuarantine(rs);
        requireValid(stored);
        String controlState = rs.getString("control_state");
        if (controlState == null) {
            return stored.external(QuarantineState.AVAILABLE, "", Instant.EPOCH, 0);
        }
        StoredControl control = new StoredControl(
                stored.scopeKey(), stored.runId(),
                rs.getString("control_checkpoint_fingerprint"), controlState,
                rs.getString("claim_owner"), rs.getString("claim_token"),
                rs.getTimestamp("claim_until").toInstant(), rs.getLong("control_version"),
                rs.getString("control_record_fingerprint"));
        requireValid(control, stored);
        boolean liveClaim = control.state() == QuarantineState.CLAIMED
                && control.claimUntil().isAfter(observedAt);
        return stored.external(liveClaim ? QuarantineState.CLAIMED : QuarantineState.AVAILABLE,
                liveClaim ? control.claimOwner() : "",
                liveClaim ? control.claimUntil() : Instant.EPOCH, control.version());
    }

    private StoredQuarantine mapStoredQuarantine(ResultSet rs) throws SQLException {
        return new StoredQuarantine(
                rs.getString("scope_key"), rs.getString("tenant_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("reason"),
                rs.getLong("consecutive_failures"), rs.getInt("quarantine_threshold"),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("quarantined_at").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private Optional<StoredQuarantine> findQuarantine(
            WorkerAcquisitionScope scope, QuarantineKey key, boolean forUpdate) {
        List<StoredQuarantine> rows = jdbc.query("""
                        SELECT scope_key, tenant_id, environment_id, organization_id,
                               project_id, run_id, checkpoint_fingerprint, reason,
                               consecutive_failures, quarantine_threshold, first_observed_at,
                               quarantined_at, record_fingerprint
                        FROM rg_test_durable_worker_candidate_quarantines
                        WHERE scope_key = ? AND run_id = ? AND checkpoint_fingerprint = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""),
                (rs, rowNumber) -> mapStoredQuarantine(rs),
                scopeKey(scope), key.runId(), key.checkpointFingerprint());
        if (rows.size() > 1) {
            throw new IllegalStateException("Worker quarantine identity is not unique");
        }
        return rows.stream().findFirst();
    }

    private boolean lockExactCheckpoint(WorkerAcquisitionScope scope, QuarantineKey key) {
        List<String> rows = jdbc.query("""
                        SELECT checkpoint_json
                        FROM rg_test_durable_execution_checkpoints
                        WHERE tenant_id = ? AND environment_id = ?
                          AND organization_id = ? AND project_id = ? AND run_id = ?
                        FOR UPDATE
                        """, (rs, rowNumber) -> rs.getString(1), scope.tenantId(),
                scope.environmentId(), scope.organizationId(), scope.projectId(), key.runId());
        if (rows.isEmpty()) {
            return false;
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("Durable checkpoint identity is not unique");
        }
        try {
            DurableTestExecutionCheckpoint checkpoint = objectMapper.readValue(
                    rows.getFirst(), DurableTestExecutionCheckpoint.class);
            checkpointIntegrity.requireValid(checkpoint);
            return scope.contains(checkpoint)
                    && checkpoint.runId().equals(key.runId())
                    && checkpoint.checkpointFingerprint().equals(key.checkpointFingerprint());
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException("Stored durable checkpoint is corrupt", corrupt);
        }
    }

    private Optional<StoredControl> findControl(
            WorkerAcquisitionScope scope, String runId, boolean forUpdate) {
        List<StoredControl> rows = jdbc.query("""
                        SELECT scope_key, run_id, checkpoint_fingerprint, control_state,
                               claim_owner, claim_token, claim_until, control_version,
                               record_fingerprint
                        FROM rg_test_durable_worker_quarantine_controls
                        WHERE scope_key = ? AND run_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""), this::mapControl,
                scopeKey(scope), runId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Worker quarantine control identity is not unique");
        }
        return rows.stream().findFirst();
    }

    private StoredControl mapControl(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredControl(rs.getString("scope_key"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("control_state"),
                rs.getString("claim_owner"), rs.getString("claim_token"),
                rs.getTimestamp("claim_until").toInstant(), rs.getLong("control_version"),
                rs.getString("record_fingerprint"));
    }

    private StoredControl storedControl(
            StoredQuarantine quarantine,
            QuarantineState state,
            String owner,
            String token,
            Instant claimUntil,
            long version) {
        StoredControl unsealed = new StoredControl(quarantine.scopeKey(), quarantine.runId(),
                quarantine.checkpointFingerprint(), state.name(), owner, token, claimUntil,
                version, "");
        return unsealed.withFingerprint(controlFingerprint(unsealed));
    }

    private void persistControl(StoredControl current, StoredControl next) {
        int changed;
        if (current == null) {
            changed = jdbc.update("""
                    INSERT INTO rg_test_durable_worker_quarantine_controls (
                        scope_key, run_id, checkpoint_fingerprint, control_state,
                        claim_owner, claim_token, claim_until, control_version,
                        record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, next.scopeKey(), next.runId(), next.checkpointFingerprint(),
                    next.state().name(), next.claimOwner(), next.claimToken(),
                    Timestamp.from(next.claimUntil()), next.version(), next.recordFingerprint());
        } else {
            changed = jdbc.update("""
                    UPDATE rg_test_durable_worker_quarantine_controls
                    SET control_state = ?, claim_owner = ?, claim_token = ?, claim_until = ?,
                        control_version = ?, record_fingerprint = ?
                    WHERE scope_key = ? AND run_id = ? AND checkpoint_fingerprint = ?
                      AND control_version = ? AND record_fingerprint = ?
                    """, next.state().name(), next.claimOwner(), next.claimToken(),
                    Timestamp.from(next.claimUntil()), next.version(), next.recordFingerprint(),
                    current.scopeKey(), current.runId(), current.checkpointFingerprint(),
                    current.version(), current.recordFingerprint());
        }
        if (changed != 1) {
            throw new IllegalStateException("Worker quarantine control fence was rejected");
        }
    }

    private Optional<StoredClaimCommand> findClaimCommand(
            WorkerAcquisitionScope scope, String requestId, boolean forUpdate) {
        List<StoredClaimCommand> rows = jdbc.query("""
                        SELECT scope_key, client_request_id, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               claim_owner, claim_duration_seconds, request_fingerprint,
                               result_claim_token, result_version, result_claim_until,
                               created_at, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_claim_commands
                        WHERE scope_key = ? AND client_request_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""), this::mapClaimCommand,
                scopeKey(scope), requestId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Worker quarantine claim command is not unique");
        }
        return rows.stream().findFirst();
    }

    private StoredClaimCommand mapClaimCommand(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredClaimCommand(rs.getString("scope_key"),
                rs.getString("client_request_id"), rs.getString("tenant_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("claim_owner"),
                rs.getLong("claim_duration_seconds"), rs.getString("request_fingerprint"),
                rs.getString("result_claim_token"), rs.getLong("result_version"),
                rs.getTimestamp("result_claim_until").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getString("record_fingerprint"));
    }

    private StoredClaimCommand storedClaimCommand(
            WorkerAcquisitionScope scope,
            String requestId,
            QuarantineKey key,
            String owner,
            Duration duration,
            String requestFingerprint,
            QuarantineClaim claim,
            Instant createdAt) {
        StoredClaimCommand unsealed = new StoredClaimCommand(scopeKey(scope), requestId,
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                key.runId(), key.checkpointFingerprint(), owner, duration.toSeconds(),
                requestFingerprint, claim.claimToken(), claim.version(), claim.claimUntil(),
                createdAt, "");
        return unsealed.withFingerprint(claimCommandFingerprint(unsealed));
    }

    private void insertClaimCommand(StoredClaimCommand command) {
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_quarantine_claim_commands (
                    scope_key, client_request_id, tenant_id, environment_id,
                    organization_id, project_id, run_id, checkpoint_fingerprint,
                    claim_owner, claim_duration_seconds, request_fingerprint,
                    result_claim_token, result_version, result_claim_until,
                    created_at, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.scopeKey(), command.clientRequestId(), command.tenantId(),
                command.environmentId(), command.organizationId(), command.projectId(),
                command.runId(), command.checkpointFingerprint(), command.claimOwner(),
                command.claimDurationSeconds(), command.requestFingerprint(),
                command.resultClaimToken(), command.resultVersion(),
                Timestamp.from(command.resultClaimUntil()), Timestamp.from(command.createdAt()),
                command.recordFingerprint());
        if (inserted != 1) {
            throw new IllegalStateException("Worker quarantine claim command was not inserted");
        }
    }

    private void requireValid(StoredClaimCommand command) {
        WorkerAcquisitionScope scope;
        try {
            scope = command.scope();
            new QuarantineClaim(command.key(), command.claimOwner(),
                    command.resultClaimToken(), command.resultVersion(),
                    command.resultClaimUntil());
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored worker quarantine claim command is corrupt",
                    invalid);
        }
        if (!scopeKey(scope).equals(command.scopeKey())
                || command.claimDurationSeconds() < 1
                || command.claimDurationSeconds() > 3_600
                || !claimCommandFingerprint(command).equals(command.recordFingerprint())) {
            throw new IllegalStateException("Stored worker quarantine claim command is corrupt");
        }
    }

    private String claimCommandFingerprint(StoredClaimCommand command) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineClaimCommand.v1"),
                Map.entry("scope", command.scope()),
                Map.entry("clientRequestId", command.clientRequestId()),
                Map.entry("key", command.key()), Map.entry("ownerId", command.claimOwner()),
                Map.entry("claimDurationSeconds", command.claimDurationSeconds()),
                Map.entry("requestFingerprint", command.requestFingerprint()),
                Map.entry("resultClaimToken", command.resultClaimToken()),
                Map.entry("resultVersion", command.resultVersion()),
                Map.entry("resultClaimUntil", command.resultClaimUntil()),
                Map.entry("createdAt", command.createdAt())));
    }

    private String resolutionRequestFingerprint(
            WorkerAcquisitionScope scope,
            QuarantineClaim claim,
            ResolutionAction action,
            String reasonCode) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineResolutionIntent.v1"),
                Map.entry("scope", scope), Map.entry("key", claim.key()),
                Map.entry("ownerId", claim.ownerId()),
                Map.entry("claimToken", claim.claimToken()),
                Map.entry("claimVersion", claim.version()),
                Map.entry("claimUntil", claim.claimUntil()),
                Map.entry("action", action.name()), Map.entry("reasonCode", reasonCode)));
    }

    private QuarantineResolutionReceipt resolutionReceipt(
            WorkerAcquisitionScope scope,
            QuarantineKey key,
            String owner,
            ResolutionAction action,
            String reasonCode,
            long version,
            Instant actedAt) {
        String fingerprint = resolutionReceiptFingerprint(
                scope, key, owner, action, reasonCode, version, actedAt);
        return new QuarantineResolutionReceipt(
                key, owner, action, reasonCode, version, actedAt, fingerprint);
    }

    private String resolutionReceiptFingerprint(
            WorkerAcquisitionScope scope,
            QuarantineKey key,
            String owner,
            ResolutionAction action,
            String reasonCode,
            long version,
            Instant actedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineResolutionReceipt.v1"),
                Map.entry("scope", scope), Map.entry("key", key),
                Map.entry("ownerId", owner), Map.entry("action", action.name()),
                Map.entry("reasonCode", reasonCode), Map.entry("version", version),
                Map.entry("actedAt", actedAt)));
    }

    private Optional<StoredResolutionCommand> findResolutionCommand(
            WorkerAcquisitionScope scope, String requestId, boolean forUpdate) {
        List<StoredResolutionCommand> rows = jdbc.query("""
                        SELECT scope_key, client_request_id, tenant_id, environment_id,
                               organization_id, project_id, run_id, checkpoint_fingerprint,
                               resolution_owner, resolution_action, reason_code,
                               request_fingerprint, result_version, result_acted_at,
                               result_receipt_fingerprint, record_fingerprint
                        FROM rg_test_durable_worker_quarantine_resolutions
                        WHERE scope_key = ? AND client_request_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""), this::mapResolutionCommand,
                scopeKey(scope), requestId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Worker quarantine resolution command is not unique");
        }
        return rows.stream().findFirst();
    }

    private StoredResolutionCommand mapResolutionCommand(
            ResultSet rs, int rowNumber) throws SQLException {
        return new StoredResolutionCommand(rs.getString("scope_key"),
                rs.getString("client_request_id"), rs.getString("tenant_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("resolution_owner"),
                rs.getString("resolution_action"), rs.getString("reason_code"),
                rs.getString("request_fingerprint"), rs.getLong("result_version"),
                rs.getTimestamp("result_acted_at").toInstant(),
                rs.getString("result_receipt_fingerprint"),
                rs.getString("record_fingerprint"));
    }

    private StoredResolutionCommand storedResolutionCommand(
            WorkerAcquisitionScope scope,
            String requestId,
            QuarantineKey key,
            String owner,
            ResolutionAction action,
            String reasonCode,
            String requestFingerprint,
            QuarantineResolutionReceipt receipt) {
        StoredResolutionCommand unsealed = new StoredResolutionCommand(scopeKey(scope),
                requestId, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), key.runId(), key.checkpointFingerprint(), owner,
                action.name(), reasonCode, requestFingerprint, receipt.version(),
                receipt.actedAt(), receipt.receiptFingerprint(), "");
        return unsealed.withFingerprint(resolutionCommandFingerprint(unsealed));
    }

    private void insertResolutionCommand(StoredResolutionCommand command) {
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_quarantine_resolutions (
                    scope_key, client_request_id, tenant_id, environment_id,
                    organization_id, project_id, run_id, checkpoint_fingerprint,
                    resolution_owner, resolution_action, reason_code, request_fingerprint,
                    result_version, result_acted_at, result_receipt_fingerprint,
                    record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, command.scopeKey(), command.clientRequestId(), command.tenantId(),
                command.environmentId(), command.organizationId(), command.projectId(),
                command.runId(), command.checkpointFingerprint(), command.resolutionOwner(),
                command.resolutionAction(), command.reasonCode(), command.requestFingerprint(),
                command.resultVersion(), Timestamp.from(command.resultActedAt()),
                command.resultReceiptFingerprint(), command.recordFingerprint());
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Worker quarantine resolution command was not inserted");
        }
    }

    private void requireValid(StoredResolutionCommand command) {
        WorkerAcquisitionScope scope;
        QuarantineResolutionReceipt receipt;
        try {
            scope = command.scope();
            receipt = command.receipt();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException(
                    "Stored worker quarantine resolution command is corrupt", invalid);
        }
        String expectedReceipt = resolutionReceiptFingerprint(
                scope, receipt.key(), receipt.ownerId(), receipt.action(),
                receipt.reasonCode(), receipt.version(), receipt.actedAt());
        if (!scopeKey(scope).equals(command.scopeKey())
                || !expectedReceipt.equals(receipt.receiptFingerprint())
                || !resolutionCommandFingerprint(command).equals(command.recordFingerprint())) {
            throw new IllegalStateException(
                    "Stored worker quarantine resolution command is corrupt");
        }
    }

    private String resolutionCommandFingerprint(StoredResolutionCommand command) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineResolutionCommand.v1"),
                Map.entry("scope", command.scope()),
                Map.entry("clientRequestId", command.clientRequestId()),
                Map.entry("key", command.key()),
                Map.entry("ownerId", command.resolutionOwner()),
                Map.entry("action", command.resolutionAction()),
                Map.entry("reasonCode", command.reasonCode()),
                Map.entry("requestFingerprint", command.requestFingerprint()),
                Map.entry("resultVersion", command.resultVersion()),
                Map.entry("resultActedAt", command.resultActedAt()),
                Map.entry("resultReceiptFingerprint", command.resultReceiptFingerprint())));
    }

    private StoredHistory storedHistory(
            WorkerAcquisitionScope scope,
            StoredQuarantine quarantine,
            QuarantineResolutionReceipt receipt) {
        ActiveWorkerCandidateQuarantine active = quarantine.quarantine();
        StoredHistory unsealed = new StoredHistory(UUID.randomUUID().toString(), scopeKey(scope),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                quarantine.runId(), quarantine.checkpointFingerprint(), active.reason().name(),
                active.consecutiveFailures(), active.quarantineThreshold(),
                active.firstObservedAt(), active.quarantinedAt(), receipt.action().name(),
                receipt.reasonCode(), receipt.ownerId(), receipt.version(), receipt.actedAt(),
                receipt.receiptFingerprint(), "");
        return unsealed.withFingerprint(historyFingerprint(unsealed));
    }

    private void insertHistory(StoredHistory history) {
        int inserted = jdbc.update("""
                INSERT INTO rg_test_durable_worker_quarantine_history (
                    history_id, scope_key, tenant_id, environment_id, organization_id,
                    project_id, run_id, checkpoint_fingerprint, quarantine_reason,
                    consecutive_failures, quarantine_threshold, first_observed_at,
                    quarantined_at, resolution_action, reason_code, resolution_owner,
                    result_version, acted_at, receipt_fingerprint, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, history.historyId(), history.scopeKey(), history.tenantId(),
                history.environmentId(), history.organizationId(), history.projectId(),
                history.runId(), history.checkpointFingerprint(), history.quarantineReason(),
                history.consecutiveFailures(), history.quarantineThreshold(),
                Timestamp.from(history.firstObservedAt()), Timestamp.from(history.quarantinedAt()),
                history.resolutionAction(), history.reasonCode(), history.resolutionOwner(),
                history.resultVersion(), Timestamp.from(history.actedAt()),
                history.receiptFingerprint(), history.recordFingerprint());
        if (inserted != 1) {
            throw new IllegalStateException("Worker quarantine history was not inserted");
        }
    }

    private QuarantineHistoryRecord mapHistory(ResultSet rs, int rowNumber) throws SQLException {
        StoredHistory stored = new StoredHistory(rs.getString("history_id"),
                rs.getString("scope_key"), rs.getString("tenant_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("run_id"),
                rs.getString("checkpoint_fingerprint"), rs.getString("quarantine_reason"),
                rs.getLong("consecutive_failures"), rs.getInt("quarantine_threshold"),
                rs.getTimestamp("first_observed_at").toInstant(),
                rs.getTimestamp("quarantined_at").toInstant(),
                rs.getString("resolution_action"), rs.getString("reason_code"),
                rs.getString("resolution_owner"), rs.getLong("result_version"),
                rs.getTimestamp("acted_at").toInstant(), rs.getString("receipt_fingerprint"),
                rs.getString("record_fingerprint"));
        requireValid(stored);
        return stored.external();
    }

    private void requireValid(StoredHistory history) {
        WorkerAcquisitionScope scope;
        QuarantineHistoryRecord external;
        try {
            scope = history.scope();
            external = history.external();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored worker quarantine history is corrupt", invalid);
        }
        String expectedReceipt = resolutionReceiptFingerprint(scope, external.key(),
                external.ownerId(), external.action(), external.reasonCode(), external.version(),
                external.actedAt());
        if (!scopeKey(scope).equals(history.scopeKey())
                || !expectedReceipt.equals(history.receiptFingerprint())
                || !historyFingerprint(history).equals(history.recordFingerprint())) {
            throw new IllegalStateException("Stored worker quarantine history is corrupt");
        }
    }

    private void requireValidHistoryRecord(QuarantineHistoryRecord history) {
        Objects.requireNonNull(history, "history");
    }

    private String historyFingerprint(StoredHistory history) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineHistory.v1"),
                Map.entry("historyId", history.historyId()), Map.entry("scope", history.scope()),
                Map.entry("key", history.key()),
                Map.entry("quarantineReason", history.quarantineReason()),
                Map.entry("consecutiveFailures", history.consecutiveFailures()),
                Map.entry("quarantineThreshold", history.quarantineThreshold()),
                Map.entry("firstObservedAt", history.firstObservedAt()),
                Map.entry("quarantinedAt", history.quarantinedAt()),
                Map.entry("action", history.resolutionAction()),
                Map.entry("reasonCode", history.reasonCode()),
                Map.entry("ownerId", history.resolutionOwner()),
                Map.entry("version", history.resultVersion()),
                Map.entry("actedAt", history.actedAt()),
                Map.entry("receiptFingerprint", history.receiptFingerprint())));
    }

    private void deleteQuarantine(StoredQuarantine quarantine) {
        int deleted = jdbc.update("""
                DELETE FROM rg_test_durable_worker_candidate_quarantines
                WHERE scope_key = ? AND run_id = ? AND checkpoint_fingerprint = ?
                  AND record_fingerprint = ?
                """, quarantine.scopeKey(), quarantine.runId(),
                quarantine.checkpointFingerprint(), quarantine.recordFingerprint());
        if (deleted != 1) {
            throw new IllegalStateException("Worker quarantine changed while discarding");
        }
    }

    private void requireValid(StoredQuarantine stored) {
        WorkerAcquisitionScope scope;
        ActiveWorkerCandidateQuarantine quarantine;
        try {
            scope = stored.scope();
            quarantine = stored.quarantine();
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("Stored worker quarantine is corrupt", invalid);
        }
        String expected = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerCandidateQuarantine.v1"),
                Map.entry("scope", scope), Map.entry("runId", stored.runId()),
                Map.entry("checkpointFingerprint", stored.checkpointFingerprint()),
                Map.entry("reason", quarantine.reason().name()),
                Map.entry("consecutiveFailures", quarantine.consecutiveFailures()),
                Map.entry("quarantineThreshold", quarantine.quarantineThreshold()),
                Map.entry("firstObservedAt", quarantine.firstObservedAt()),
                Map.entry("quarantinedAt", quarantine.quarantinedAt())));
        if (!scopeKey(scope).equals(stored.scopeKey())
                || !expected.equals(stored.recordFingerprint())) {
            throw new IllegalStateException("Stored worker quarantine is corrupt");
        }
    }

    private void requireValid(StoredControl control, StoredQuarantine quarantine) {
        String expected = controlFingerprint(control);
        boolean available = control.state() == QuarantineState.AVAILABLE;
        boolean claimShapeValid = available
                ? control.claimOwner().isBlank() && control.claimToken().isBlank()
                && control.claimUntil().equals(Instant.EPOCH)
                : !control.claimOwner().isBlank() && !control.claimToken().isBlank()
                && control.claimUntil().isAfter(Instant.EPOCH);
        if (!control.scopeKey().equals(quarantine.scopeKey())
                || !control.runId().equals(quarantine.runId())
                || !control.checkpointFingerprint().equals(quarantine.checkpointFingerprint())
                || control.version() < 0 || !claimShapeValid
                || !expected.equals(control.recordFingerprint())) {
            throw new IllegalStateException("Stored worker quarantine control is corrupt");
        }
    }

    private String controlFingerprint(StoredControl control) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerQuarantineControl.v1"),
                Map.entry("scopeKey", control.scopeKey()),
                Map.entry("runId", control.runId()),
                Map.entry("checkpointFingerprint", control.checkpointFingerprint()),
                Map.entry("state", control.state().name()),
                Map.entry("claimOwner", control.claimOwner()),
                Map.entry("claimToken", control.claimToken()),
                Map.entry("claimUntil", control.claimUntil()),
                Map.entry("version", control.version())));
    }

    private String scopeKey(WorkerAcquisitionScope scope) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.durableWorkerScanScope.v1", "scope", scope));
    }

    private Instant databaseNow() {
        Instant now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP",
                (rs, rowNumber) -> rs.getTimestamp(1).toInstant());
        if (now == null) {
            throw new IllegalStateException("Test-runtime database did not provide its clock");
        }
        return now.truncatedTo(ChronoUnit.MICROS);
    }

    private static int bounded(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Worker quarantine page size must be between 1 and 1000");
        }
        return limit;
    }

    private static Duration boundedLease(Duration duration) {
        Duration safe = Objects.requireNonNull(duration, "claimDuration");
        if (safe.compareTo(Duration.ofSeconds(1)) < 0
                || safe.compareTo(Duration.ofHours(1)) > 0
                || safe.getNano() != 0) {
            throw new IllegalArgumentException(
                    "Worker quarantine claim duration must be whole seconds from 1 through 3600");
        }
        return safe;
    }

    /** Stable operational state of one automatic quarantine. */
    public enum QuarantineState {
        /** No live maintenance owner; a verified operator may claim the record. */
        AVAILABLE,
        /** A maintenance owner holds a database-clock lease. */
        CLAIMED
    }

    /** Stable result vocabulary for idempotent quarantine claims. */
    public enum ClaimDisposition {
        /** A new claim, command receipt, and audit mutation committed. */
        CLAIMED,
        /** The exact immutable command result was returned. */
        IDEMPOTENT_REPLAY,
        /** Another unexpired owner or missing quarantine prevents a claim. */
        NOT_ACTIONABLE,
        /** The request ID already identifies different intent. */
        IDEMPOTENCY_CONFLICT,
        /** The run no longer has the exact checkpoint closure named by the request. */
        STALE_CHECKPOINT
    }

    /**
     * Exact identity of one automatic worker quarantine.
     *
     * @param runId durable execution identity
     * @param checkpointFingerprint exact isolated checkpoint closure
     */
    public record QuarantineKey(String runId, String checkpointFingerprint) {
        /** Validates bounded exact-checkpoint identity. */
        public QuarantineKey {
            runId = required(runId, "runId", 255);
            checkpointFingerprint = required(
                    checkpointFingerprint, "checkpointFingerprint", 80);
        }
    }

    /**
     * Server-issued fence for one maintenance claim.
     *
     * @param key exact quarantine identity
     * @param ownerId verified maintenance actor
     * @param claimToken unguessable response-only secret
     * @param version positive monotonic control generation
     * @param claimUntil database-clock lease deadline
     */
    public record QuarantineClaim(
            QuarantineKey key,
            String ownerId,
            String claimToken,
            long version,
            Instant claimUntil) {
        /** Validates a complete owner fence. */
        public QuarantineClaim {
            key = Objects.requireNonNull(key, "key");
            ownerId = required(ownerId, "ownerId", 255);
            claimToken = required(claimToken, "claimToken", 255);
            claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
            if (version <= 0 || !claimUntil.isAfter(Instant.EPOCH)) {
                throw new IllegalArgumentException("Invalid worker quarantine claim");
            }
        }
    }

    /**
     * Result of one idempotent maintenance claim.
     *
     * @param disposition stable outcome
     * @param claim exact claim for successful or replayed outcomes, otherwise {@code null}
     */
    public record QuarantineClaimResult(
            ClaimDisposition disposition, QuarantineClaim claim) {
        /** Enforces that only successful outcomes carry a secret fence. */
        public QuarantineClaimResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            boolean carriesClaim = disposition == ClaimDisposition.CLAIMED
                    || disposition == ClaimDisposition.IDEMPOTENT_REPLAY;
            if (carriesClaim != (claim != null)) {
                throw new IllegalArgumentException("Invalid worker quarantine claim result");
            }
        }

        private static QuarantineClaimResult claimed(QuarantineClaim claim) {
            return new QuarantineClaimResult(ClaimDisposition.CLAIMED, claim);
        }

        private static QuarantineClaimResult replay(QuarantineClaim claim) {
            return new QuarantineClaimResult(ClaimDisposition.IDEMPOTENT_REPLAY, claim);
        }

        private static QuarantineClaimResult notActionable() {
            return new QuarantineClaimResult(ClaimDisposition.NOT_ACTIONABLE, null);
        }

        private static QuarantineClaimResult conflict() {
            return new QuarantineClaimResult(ClaimDisposition.IDEMPOTENCY_CONFLICT, null);
        }

        private static QuarantineClaimResult staleCheckpoint() {
            return new QuarantineClaimResult(ClaimDisposition.STALE_CHECKPOINT, null);
        }
    }

    /** Stable manual action vocabulary for an exact-checkpoint quarantine. */
    public enum ResolutionAction {
        /** Release maintenance ownership while preserving worker suppression. */
        RELEASE,
        /** Delete the exact quarantine and allow the checkpoint to be reconsidered. */
        DISCARD
    }

    /** Stable result vocabulary for idempotent quarantine resolutions. */
    public enum ResolutionDisposition {
        /** A new action, receipt, history row, and audit mutation committed. */
        RESOLVED,
        /** The exact immutable action receipt was returned. */
        IDEMPOTENT_REPLAY,
        /** Owner, token, version, expiry, or active-record fencing failed. */
        FENCE_REJECTED,
        /** The request ID already identifies different intent. */
        IDEMPOTENCY_CONFLICT,
        /** The run no longer has the exact checkpoint closure named by the claim. */
        STALE_CHECKPOINT
    }

    /**
     * Immutable token-free receipt for one manual quarantine action.
     *
     * @param key exact quarantine identity
     * @param ownerId verified maintenance actor
     * @param action release or discard
     * @param reasonCode bounded non-payload operational rationale
     * @param version resulting maintenance generation
     * @param actedAt database-clock commit time
     * @param receiptFingerprint canonical immutable receipt fingerprint
     */
    public record QuarantineResolutionReceipt(
            QuarantineKey key,
            String ownerId,
            ResolutionAction action,
            String reasonCode,
            long version,
            Instant actedAt,
            String receiptFingerprint) {
        /** Validates a complete token-free action receipt. */
        public QuarantineResolutionReceipt {
            key = Objects.requireNonNull(key, "key");
            ownerId = required(ownerId, "ownerId", 255);
            action = Objects.requireNonNull(action, "action");
            reasonCode = required(reasonCode, "reasonCode", 128).toUpperCase(Locale.ROOT);
            actedAt = Objects.requireNonNull(actedAt, "actedAt");
            receiptFingerprint = required(
                    receiptFingerprint, "receiptFingerprint", 80);
            if (!REASON_CODE.matcher(reasonCode).matches() || version <= 0) {
                throw new IllegalArgumentException("Invalid worker quarantine action receipt");
            }
        }
    }

    /**
     * Result of one idempotent quarantine resolution.
     *
     * @param disposition stable action outcome
     * @param receipt token-free receipt for resolved or replayed outcomes, otherwise {@code null}
     */
    public record QuarantineResolutionResult(
            ResolutionDisposition disposition, QuarantineResolutionReceipt receipt) {
        /** Enforces that only successful outcomes carry a receipt. */
        public QuarantineResolutionResult {
            disposition = Objects.requireNonNull(disposition, "disposition");
            boolean carriesReceipt = disposition == ResolutionDisposition.RESOLVED
                    || disposition == ResolutionDisposition.IDEMPOTENT_REPLAY;
            if (carriesReceipt != (receipt != null)) {
                throw new IllegalArgumentException("Invalid worker quarantine resolution result");
            }
        }

        private static QuarantineResolutionResult resolved(
                QuarantineResolutionReceipt receipt) {
            return new QuarantineResolutionResult(ResolutionDisposition.RESOLVED, receipt);
        }

        private static QuarantineResolutionResult replay(
                QuarantineResolutionReceipt receipt) {
            return new QuarantineResolutionResult(
                    ResolutionDisposition.IDEMPOTENT_REPLAY, receipt);
        }

        private static QuarantineResolutionResult fenceRejected() {
            return new QuarantineResolutionResult(ResolutionDisposition.FENCE_REJECTED, null);
        }

        private static QuarantineResolutionResult conflict() {
            return new QuarantineResolutionResult(
                    ResolutionDisposition.IDEMPOTENCY_CONFLICT, null);
        }

        private static QuarantineResolutionResult staleCheckpoint() {
            return new QuarantineResolutionResult(ResolutionDisposition.STALE_CHECKPOINT, null);
        }
    }

    /**
     * Immutable token-free historical evidence for one manual quarantine action.
     *
     * @param historyId opaque history identity
     * @param key exact quarantine identity at action time
     * @param quarantineReason automatic isolation reason
     * @param consecutiveFailures threshold-crossing count
     * @param quarantineThreshold policy threshold applied at isolation
     * @param firstObservedAt first same-reason observation
     * @param quarantinedAt isolation time
     * @param action manual release or discard
     * @param reasonCode bounded operational rationale
     * @param ownerId verified maintenance actor
     * @param version resulting maintenance generation
     * @param actedAt database-clock action time
     * @param receiptFingerprint immutable action receipt fingerprint
     * @param recordFingerprint whole retained-history record fingerprint
     */
    public record QuarantineHistoryRecord(
            String historyId,
            QuarantineKey key,
            WorkerCandidateDeferralReason quarantineReason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            ResolutionAction action,
            String reasonCode,
            String ownerId,
            long version,
            Instant actedAt,
            String receiptFingerprint,
            String recordFingerprint) {
        /** Validates complete retained action evidence without any claim token. */
        public QuarantineHistoryRecord {
            historyId = required(historyId, "historyId", 36);
            key = Objects.requireNonNull(key, "key");
            quarantineReason = Objects.requireNonNull(quarantineReason, "quarantineReason");
            firstObservedAt = Objects.requireNonNull(firstObservedAt, "firstObservedAt");
            quarantinedAt = Objects.requireNonNull(quarantinedAt, "quarantinedAt");
            action = Objects.requireNonNull(action, "action");
            reasonCode = required(reasonCode, "reasonCode", 128).toUpperCase(Locale.ROOT);
            ownerId = required(ownerId, "ownerId", 255);
            actedAt = Objects.requireNonNull(actedAt, "actedAt");
            receiptFingerprint = required(
                    receiptFingerprint, "receiptFingerprint", 80);
            recordFingerprint = required(recordFingerprint, "recordFingerprint", 80);
            if (quarantineThreshold < 1 || consecutiveFailures < quarantineThreshold
                    || firstObservedAt.isAfter(quarantinedAt)
                    || !REASON_CODE.matcher(reasonCode).matches() || version <= 0) {
                throw new IllegalArgumentException("Invalid worker quarantine history record");
            }
        }
    }

    /**
     * Payload-free, integrity-verified quarantine projection.
     *
     * @param runId durable execution identity
     * @param checkpointFingerprint exact poisoned checkpoint closure
     * @param reason closed automatic failure classification
     * @param consecutiveFailures observations that crossed the threshold
     * @param quarantineThreshold policy threshold in force at isolation time
     * @param firstObservedAt first same-reason database observation
     * @param quarantinedAt threshold-crossing database time
     * @param state current maintenance ownership projection
     * @param claimOwner verified live owner, blank when available
     * @param claimUntil database-clock claim deadline, epoch when available
     * @param version monotonic maintenance fence generation
     */
    public record QuarantineRecord(
            String runId,
            String checkpointFingerprint,
            WorkerCandidateDeferralReason reason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            QuarantineState state,
            String claimOwner,
            Instant claimUntil,
            long version) {
        /** Validates complete payload-free maintenance metadata. */
        public QuarantineRecord {
            runId = required(runId, "runId", 255);
            checkpointFingerprint = required(
                    checkpointFingerprint, "checkpointFingerprint", 80);
            reason = Objects.requireNonNull(reason, "reason");
            firstObservedAt = Objects.requireNonNull(firstObservedAt, "firstObservedAt");
            quarantinedAt = Objects.requireNonNull(quarantinedAt, "quarantinedAt");
            state = Objects.requireNonNull(state, "state");
            claimOwner = claimOwner == null ? "" : claimOwner.trim();
            claimUntil = Objects.requireNonNull(claimUntil, "claimUntil");
            if (consecutiveFailures < quarantineThreshold || quarantineThreshold < 1
                    || firstObservedAt.isAfter(quarantinedAt) || version < 0
                    || (state == QuarantineState.AVAILABLE
                    && (!claimOwner.isBlank() || !claimUntil.equals(Instant.EPOCH)))
                    || (state == QuarantineState.CLAIMED
                    && (claimOwner.isBlank() || !claimUntil.isAfter(Instant.EPOCH)))) {
                throw new IllegalArgumentException("Invalid worker quarantine projection");
            }
        }
    }

    private record StoredQuarantine(
            String scopeKey,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String reason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private ActiveWorkerCandidateQuarantine quarantine() {
            return new ActiveWorkerCandidateQuarantine(
                    WorkerCandidateDeferralReason.valueOf(reason), consecutiveFailures,
                    quarantineThreshold, firstObservedAt, quarantinedAt);
        }

        private QuarantineRecord external(
                QuarantineState state, String owner, Instant until, long version) {
            ActiveWorkerCandidateQuarantine active = quarantine();
            return new QuarantineRecord(runId, checkpointFingerprint, active.reason(),
                    active.consecutiveFailures(), active.quarantineThreshold(),
                    active.firstObservedAt(), active.quarantinedAt(), state, owner, until, version);
        }
    }

    private record StoredControl(
            String scopeKey,
            String runId,
            String checkpointFingerprint,
            String stateName,
            String claimOwner,
            String claimToken,
            Instant claimUntil,
            long version,
            String recordFingerprint) {
        private QuarantineState state() {
            return QuarantineState.valueOf(stateName);
        }

        private StoredControl withFingerprint(String fingerprint) {
            return new StoredControl(scopeKey, runId, checkpointFingerprint, stateName,
                    claimOwner, claimToken, claimUntil, version, fingerprint);
        }
    }

    private record StoredClaimCommand(
            String scopeKey,
            String clientRequestId,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String claimOwner,
            long claimDurationSeconds,
            String requestFingerprint,
            String resultClaimToken,
            long resultVersion,
            Instant resultClaimUntil,
            Instant createdAt,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private QuarantineKey key() {
            return new QuarantineKey(runId, checkpointFingerprint);
        }

        private QuarantineClaim claim() {
            return new QuarantineClaim(
                    key(), claimOwner, resultClaimToken, resultVersion, resultClaimUntil);
        }

        private StoredClaimCommand withFingerprint(String fingerprint) {
            return new StoredClaimCommand(scopeKey, clientRequestId, tenantId,
                    organizationId, projectId, environmentId, runId, checkpointFingerprint,
                    claimOwner, claimDurationSeconds, requestFingerprint, resultClaimToken,
                    resultVersion, resultClaimUntil, createdAt, fingerprint);
        }
    }

    private record StoredResolutionCommand(
            String scopeKey,
            String clientRequestId,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String resolutionOwner,
            String resolutionAction,
            String reasonCode,
            String requestFingerprint,
            long resultVersion,
            Instant resultActedAt,
            String resultReceiptFingerprint,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private QuarantineKey key() {
            return new QuarantineKey(runId, checkpointFingerprint);
        }

        private QuarantineResolutionReceipt receipt() {
            return new QuarantineResolutionReceipt(key(), resolutionOwner,
                    ResolutionAction.valueOf(resolutionAction), reasonCode, resultVersion,
                    resultActedAt, resultReceiptFingerprint);
        }

        private StoredResolutionCommand withFingerprint(String fingerprint) {
            return new StoredResolutionCommand(scopeKey, clientRequestId, tenantId,
                    organizationId, projectId, environmentId, runId, checkpointFingerprint,
                    resolutionOwner, resolutionAction, reasonCode, requestFingerprint,
                    resultVersion, resultActedAt, resultReceiptFingerprint, fingerprint);
        }
    }

    private record StoredHistory(
            String historyId,
            String scopeKey,
            String tenantId,
            String organizationId,
            String projectId,
            String environmentId,
            String runId,
            String checkpointFingerprint,
            String quarantineReason,
            long consecutiveFailures,
            int quarantineThreshold,
            Instant firstObservedAt,
            Instant quarantinedAt,
            String resolutionAction,
            String reasonCode,
            String resolutionOwner,
            long resultVersion,
            Instant actedAt,
            String receiptFingerprint,
            String recordFingerprint) {
        private WorkerAcquisitionScope scope() {
            return new WorkerAcquisitionScope(
                    tenantId, organizationId, projectId, environmentId);
        }

        private QuarantineKey key() {
            return new QuarantineKey(runId, checkpointFingerprint);
        }

        private QuarantineHistoryRecord external() {
            return new QuarantineHistoryRecord(historyId, key(),
                    WorkerCandidateDeferralReason.valueOf(quarantineReason),
                    consecutiveFailures, quarantineThreshold, firstObservedAt, quarantinedAt,
                    ResolutionAction.valueOf(resolutionAction), reasonCode, resolutionOwner,
                    resultVersion, actedAt, receiptFingerprint, recordFingerprint);
        }

        private StoredHistory withFingerprint(String fingerprint) {
            return new StoredHistory(historyId, scopeKey, tenantId, organizationId, projectId,
                    environmentId, runId, checkpointFingerprint, quarantineReason,
                    consecutiveFailures, quarantineThreshold, firstObservedAt, quarantinedAt,
                    resolutionAction, reasonCode, resolutionOwner, resultVersion, actedAt,
                    receiptFingerprint, fingerprint);
        }
    }

    private static String required(String value, String field, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank() || normalized.length() > maximum) {
            throw new IllegalArgumentException(field + " is required and bounded");
        }
        return normalized;
    }
}
