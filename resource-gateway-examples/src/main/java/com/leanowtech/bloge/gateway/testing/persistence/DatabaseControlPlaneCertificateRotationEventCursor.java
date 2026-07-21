package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationEventCursor;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateRotationEventPage;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Database-backed exact page cursor for one certificate-rotation serving slot.
 *
 * <p>A stable scope/instance lock serializes old and new process starts for the same serving slot.
 * The row stores its deployment baseline, committed head, optional staged successor, and canonical
 * whole-record fingerprint. Startup drift, direct row mutation, page gaps, predecessor forks, and
 * competing staged pages fail closed. The implementation intentionally stores no certificate
 * material, authority signature, event body, secret reference, or transport credential.</p>
 */
public final class DatabaseControlPlaneCertificateRotationEventCursor
        implements ControlPlaneCertificateRotationEventCursor {

    private static final String RECORD_SCHEMA =
            "bloge.controlPlaneCertificateRotationEventCursorRecord.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final String deploymentScopeId;
    private final String instanceId;
    private final long baselineSequence;
    private final String baselinePageFingerprint;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates one durable cursor bound to an immutable deployment baseline.
     *
     * @param jdbc testing-control-plane JDBC facade
     * @param objectMapper canonical record fingerprint mapper
     * @param deploymentScopeId exact signed-event deployment scope
     * @param instanceId stable serving slot represented by this cursor
     * @param baselineSequence deployment-pinned source sequence
     * @param baselinePageFingerprint deployment-pinned page-chain head
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseControlPlaneCertificateRotationEventCursor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            String deploymentScopeId,
            String instanceId,
            long baselineSequence,
            String baselinePageFingerprint,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.deploymentScopeId = normalized(deploymentScopeId);
        this.instanceId = normalized(instanceId);
        this.baselineSequence = baselineSequence;
        this.baselinePageFingerprint = normalized(baselinePageFingerprint);
        if (!IDENTIFIER.matcher(this.deploymentScopeId).matches()
                || !IDENTIFIER.matcher(this.instanceId).matches()
                || baselineSequence < 0
                || !FINGERPRINT.matcher(this.baselinePageFingerprint).matches()) {
            throw invalid("Certificate rotation event cursor configuration is invalid");
        }
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        mutations = new TransactionTemplate(manager);
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }

    /** Creates the additive lock and cursor tables and verifies any existing durable head. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_rotation_event_cursor_locks (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    instance_id VARCHAR(255) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, instance_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_rotation_event_cursors (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    instance_id VARCHAR(255) NOT NULL,
                    baseline_sequence BIGINT NOT NULL,
                    baseline_page_fingerprint VARCHAR(71) NOT NULL,
                    committed_sequence BIGINT NOT NULL,
                    committed_page_fingerprint VARCHAR(71) NOT NULL,
                    staged_sequence BIGINT NOT NULL,
                    staged_previous_page_fingerprint VARCHAR(71) NOT NULL,
                    staged_page_fingerprint VARCHAR(71) NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, instance_id)
                )
                """);
        mutations.executeWithoutResult(status -> {
            lock();
            CursorRow current = current();
            if (current == null) {
                persist(new CursorRow(deploymentScopeId, instanceId,
                        baselineSequence, baselinePageFingerprint,
                        baselineSequence, baselinePageFingerprint, 0, "", "", ""));
            } else {
                validate(current);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public StageResult stage(ControlPlaneCertificateRotationEventPage page) {
        ControlPlaneCertificateRotationEventPage required = Objects.requireNonNull(page, "page");
        if (!required.fingerprintVerified(objectMapper)
                || !deploymentScopeId.equals(required.material().deploymentScopeId())) {
            throw invalid("Certificate rotation event page fingerprint or scope is invalid");
        }
        StageResult result = mutations.execute(status -> {
            lock();
            CursorRow current = requireCurrent();
            var material = required.material();
            if (material.sequence() == current.committedSequence()
                    && required.pageFingerprint().equals(
                    current.committedPageFingerprint())) {
                return new StageResult(StageStatus.ALREADY_COMMITTED, snapshot(current));
            }
            if (current.stagedSequence() > 0) {
                StageStatus outcome = material.sequence() == current.stagedSequence()
                        && material.previousPageFingerprint().equals(
                        current.stagedPreviousPageFingerprint())
                        && required.pageFingerprint().equals(current.stagedPageFingerprint())
                        ? StageStatus.REPLAYED : StageStatus.CONFLICT;
                return new StageResult(outcome, snapshot(current));
            }
            if (current.committedSequence() == Long.MAX_VALUE
                    || material.sequence() != current.committedSequence() + 1
                    || !material.previousPageFingerprint().equals(
                    current.committedPageFingerprint())) {
                return new StageResult(StageStatus.CONFLICT, snapshot(current));
            }
            CursorRow staged = new CursorRow(deploymentScopeId, instanceId,
                    baselineSequence, baselinePageFingerprint,
                    current.committedSequence(), current.committedPageFingerprint(),
                    material.sequence(), material.previousPageFingerprint(),
                    required.pageFingerprint(), "");
            persist(staged);
            return new StageResult(StageStatus.STAGED, snapshot(requireCurrent()));
        });
        return Objects.requireNonNull(result, "certificate rotation event stage result");
    }

    /** {@inheritDoc} */
    @Override
    public CommitResult commit(String pageFingerprint) {
        String requested = normalized(pageFingerprint);
        if (!FINGERPRINT.matcher(requested).matches()) {
            throw invalid("Certificate rotation event page fingerprint is invalid");
        }
        CommitResult result = mutations.execute(status -> {
            lock();
            CursorRow current = requireCurrent();
            if (current.committedPageFingerprint().equals(requested)
                    && current.stagedSequence() == 0) {
                return new CommitResult(CommitStatus.REPLAYED, snapshot(current));
            }
            if (current.stagedSequence() == 0
                    || !current.stagedPageFingerprint().equals(requested)) {
                return new CommitResult(CommitStatus.CONFLICT, snapshot(current));
            }
            CursorRow committed = new CursorRow(deploymentScopeId, instanceId,
                    baselineSequence, baselinePageFingerprint,
                    current.stagedSequence(), current.stagedPageFingerprint(),
                    0, "", "", "");
            persist(committed);
            return new CommitResult(CommitStatus.COMMITTED, snapshot(requireCurrent()));
        });
        return Objects.requireNonNull(result, "certificate rotation event commit result");
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        Snapshot result = reads.execute(status -> snapshot(requireCurrent()));
        return Objects.requireNonNull(result, "certificate rotation event cursor snapshot");
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return true;
    }

    private void lock() {
        jdbc.update("""
                MERGE INTO rg_cp_cert_rotation_event_cursor_locks
                    (deployment_scope_id, instance_id)
                    KEY (deployment_scope_id, instance_id) VALUES (?, ?)
                """, deploymentScopeId, instanceId);
        jdbc.queryForObject("""
                SELECT instance_id FROM rg_cp_cert_rotation_event_cursor_locks
                WHERE deployment_scope_id = ? AND instance_id = ? FOR UPDATE
                """, String.class, deploymentScopeId, instanceId);
    }

    private CursorRow requireCurrent() {
        CursorRow current = current();
        if (current == null) {
            throw new IllegalStateException(
                    "Certificate rotation event cursor is not initialized");
        }
        return validate(current);
    }

    private CursorRow current() {
        List<CursorRow> rows = jdbc.query("""
                SELECT deployment_scope_id, instance_id,
                       baseline_sequence, baseline_page_fingerprint,
                       committed_sequence, committed_page_fingerprint,
                       staged_sequence, staged_previous_page_fingerprint,
                       staged_page_fingerprint, record_fingerprint
                FROM rg_cp_cert_rotation_event_cursors
                WHERE deployment_scope_id = ? AND instance_id = ?
                """, this::row, deploymentScopeId, instanceId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate certificate rotation event cursor");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private CursorRow validate(CursorRow row) {
        if (!row.valid(objectMapper, deploymentScopeId, instanceId,
                baselineSequence, baselinePageFingerprint)) {
            throw new IllegalStateException(
                    "Certificate rotation event cursor is corrupt or its baseline drifted");
        }
        return row;
    }

    private void persist(CursorRow candidate) {
        String recordFingerprint = ProtocolFingerprint.of(objectMapper, candidate.material());
        int updated = jdbc.update("""
                UPDATE rg_cp_cert_rotation_event_cursors
                SET baseline_sequence = ?, baseline_page_fingerprint = ?,
                    committed_sequence = ?, committed_page_fingerprint = ?,
                    staged_sequence = ?, staged_previous_page_fingerprint = ?,
                    staged_page_fingerprint = ?, record_fingerprint = ?
                WHERE deployment_scope_id = ? AND instance_id = ?
                """, candidate.baselineSequence(), candidate.baselinePageFingerprint(),
                candidate.committedSequence(), candidate.committedPageFingerprint(),
                candidate.stagedSequence(), candidate.stagedPreviousPageFingerprint(),
                candidate.stagedPageFingerprint(), recordFingerprint,
                deploymentScopeId, instanceId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO rg_cp_cert_rotation_event_cursors (
                        deployment_scope_id, instance_id,
                        baseline_sequence, baseline_page_fingerprint,
                        committed_sequence, committed_page_fingerprint,
                        staged_sequence, staged_previous_page_fingerprint,
                        staged_page_fingerprint, record_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, deploymentScopeId, instanceId,
                    candidate.baselineSequence(), candidate.baselinePageFingerprint(),
                    candidate.committedSequence(), candidate.committedPageFingerprint(),
                    candidate.stagedSequence(), candidate.stagedPreviousPageFingerprint(),
                    candidate.stagedPageFingerprint(), recordFingerprint);
        }
    }

    private Snapshot snapshot(CursorRow row) {
        return new Snapshot(Snapshot.SCHEMA_VERSION, deploymentScopeId, instanceId,
                row.baselineSequence(), row.baselinePageFingerprint(),
                row.committedSequence(), row.committedPageFingerprint(),
                row.stagedSequence(), row.stagedPreviousPageFingerprint(),
                row.stagedPageFingerprint());
    }

    private CursorRow row(ResultSet result, int rowNumber) throws SQLException {
        return new CursorRow(
                result.getString("deployment_scope_id"),
                result.getString("instance_id"),
                result.getLong("baseline_sequence"),
                result.getString("baseline_page_fingerprint"),
                result.getLong("committed_sequence"),
                result.getString("committed_page_fingerprint"),
                result.getLong("staged_sequence"),
                result.getString("staged_previous_page_fingerprint"),
                result.getString("staged_page_fingerprint"),
                result.getString("record_fingerprint"));
    }

    private record CursorRecordMaterial(
            String schemaVersion,
            String deploymentScopeId,
            String instanceId,
            long baselineSequence,
            String baselinePageFingerprint,
            long committedSequence,
            String committedPageFingerprint,
            long stagedSequence,
            String stagedPreviousPageFingerprint,
            String stagedPageFingerprint) {
    }

    private record CursorRow(
            String deploymentScopeId,
            String instanceId,
            long baselineSequence,
            String baselinePageFingerprint,
            long committedSequence,
            String committedPageFingerprint,
            long stagedSequence,
            String stagedPreviousPageFingerprint,
            String stagedPageFingerprint,
            String recordFingerprint) {

        private CursorRecordMaterial material() {
            return new CursorRecordMaterial(RECORD_SCHEMA, deploymentScopeId, instanceId,
                    baselineSequence, baselinePageFingerprint,
                    committedSequence, committedPageFingerprint,
                    stagedSequence, stagedPreviousPageFingerprint, stagedPageFingerprint);
        }

        private boolean valid(
                ObjectMapper objectMapper,
                String expectedScope,
                String expectedInstance,
                long expectedBaselineSequence,
                String expectedBaselineFingerprint) {
            boolean staged = stagedSequence > 0;
            boolean common = expectedScope.equals(deploymentScopeId)
                    && expectedInstance.equals(instanceId)
                    && baselineSequence == expectedBaselineSequence
                    && expectedBaselineFingerprint.equals(baselinePageFingerprint)
                    && committedSequence >= baselineSequence
                    && FINGERPRINT.matcher(committedPageFingerprint).matches();
            boolean stageValid = staged
                    ? stagedSequence == committedSequence + 1
                    && committedPageFingerprint.equals(stagedPreviousPageFingerprint)
                    && FINGERPRINT.matcher(stagedPageFingerprint).matches()
                    : stagedSequence == 0 && stagedPreviousPageFingerprint.isBlank()
                    && stagedPageFingerprint.isBlank();
            return common && stageValid
                    && ProtocolFingerprint.of(objectMapper, material())
                    .equals(recordFingerprint);
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
