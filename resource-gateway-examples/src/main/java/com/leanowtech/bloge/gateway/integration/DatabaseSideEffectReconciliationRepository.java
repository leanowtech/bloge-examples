package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** H2-backed lease/fencing authority and append-only reconciliation evidence store. */
public final class DatabaseSideEffectReconciliationRepository
        implements SideEffectReconciliationRepository {
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS visual_side_effect_reconciliation_heads (
                run_id VARCHAR(255) NOT NULL,
                attempt_id VARCHAR(255) NOT NULL,
                tenant_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                request_id VARCHAR(255) NOT NULL UNIQUE,
                request_fingerprint VARCHAR(96) NOT NULL,
                state VARCHAR(32) NOT NULL,
                owner_token VARCHAR(255) NOT NULL,
                lease_until VARCHAR(64) NOT NULL,
                record_json CLOB,
                updated_at VARCHAR(64) NOT NULL,
                PRIMARY KEY (run_id, attempt_id)
            )
            """;
    private static final String CREATE_RECORDS = """
            CREATE TABLE IF NOT EXISTS visual_side_effect_reconciliations (
                reconciliation_id VARCHAR(255) PRIMARY KEY,
                request_id VARCHAR(255) NOT NULL UNIQUE,
                run_id VARCHAR(255) NOT NULL,
                attempt_id VARCHAR(255) NOT NULL,
                sequence BIGINT NOT NULL,
                record_fingerprint VARCHAR(96) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                record_json CLOB NOT NULL,
                UNIQUE (run_id, attempt_id, sequence)
            )
            """;
    private static final String SELECT_HEAD_FOR_UPDATE = """
            SELECT request_id, request_fingerprint, state, owner_token, lease_until, record_json
            FROM visual_side_effect_reconciliation_heads
            WHERE run_id = ? AND attempt_id = ?
            FOR UPDATE
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IntegrationChangeEventOutbox outbox;
    private final TransactionTemplate transactions;

    public DatabaseSideEffectReconciliationRepository(JdbcTemplate jdbc,
                                                       ObjectMapper objectMapper,
                                                       IntegrationChangeEventOutbox outbox,
                                                       PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.outbox = outbox;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void init() {
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_RECORDS);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_side_effect_reconciliation_run "
                + "ON visual_side_effect_reconciliations (run_id, sequence)");
    }

    @Override
    public Claim claim(ClaimRequest request) {
        Claim result = transactions.execute(status -> claimInTransaction(request));
        if (result == null) {
            throw new IllegalStateException("Reconciliation claim transaction returned no result");
        }
        return result;
    }

    private Claim claimInTransaction(ClaimRequest request) {
        Head requestHead = headByRequest(request.requestId()).orElse(null);
        if (requestHead != null
                && (!request.runId().equals(requestHead.runId)
                || !request.attemptId().equals(requestHead.attemptId))) {
            return Claim.existing(ClaimStatus.REQUEST_CONFLICT, requestHead.record);
        }
        Head head = headForUpdate(request.runId(), request.attemptId()).orElse(null);
        if (head == null) {
            try {
                jdbc.update("""
                                INSERT INTO visual_side_effect_reconciliation_heads
                                    (run_id, attempt_id, tenant_id, environment_id, request_id,
                                     request_fingerprint, state, owner_token, lease_until, record_json, updated_at)
                                VALUES (?, ?, ?, ?, ?, ?, 'CLAIMED', ?, ?, NULL, ?)
                                """,
                        request.runId(), request.attemptId(), request.tenantId(), request.environmentId(),
                        request.requestId(), request.requestFingerprint(), request.ownerToken(),
                        request.leaseUntil().toString(), request.claimedAt().toString());
                return Claim.acquired(request.ownerToken(), request.leaseUntil());
            } catch (DuplicateKeyException race) {
                head = headForUpdate(request.runId(), request.attemptId()).orElse(null);
                if (head == null) {
                    Head conflicting = headByRequest(request.requestId()).orElse(null);
                    return Claim.existing(ClaimStatus.REQUEST_CONFLICT,
                            conflicting == null ? null : conflicting.record);
                }
            }
        }
        if (head.record != null) {
            ClaimStatus status = head.requestFingerprint.equals(request.requestFingerprint())
                    ? ClaimStatus.RESOLVED : ClaimStatus.TARGET_CONFLICT;
            return Claim.existing(status, head.record);
        }
        if (head.requestId.equals(request.requestId())
                && !head.requestFingerprint.equals(request.requestFingerprint())) {
            return Claim.existing(ClaimStatus.REQUEST_CONFLICT, null);
        }
        if (head.leaseUntil.isAfter(request.claimedAt())) {
            return Claim.pending(head.leaseUntil);
        }
        jdbc.update("""
                        UPDATE visual_side_effect_reconciliation_heads
                        SET request_id = ?, request_fingerprint = ?, state = 'CLAIMED', owner_token = ?,
                            lease_until = ?, updated_at = ?
                        WHERE run_id = ? AND attempt_id = ?
                        """,
                request.requestId(), request.requestFingerprint(), request.ownerToken(),
                request.leaseUntil().toString(), request.claimedAt().toString(),
                request.runId(), request.attemptId());
        return Claim.acquired(request.ownerToken(), request.leaseUntil());
    }

    @Override
    public SideEffectReconciliationRecord complete(String runId,
                                                   String attemptId,
                                                   String ownerToken,
                                                   SideEffectReconciliationRecord record) {
        SideEffectReconciliationRecord result = transactions.execute(status -> {
            Head head = headForUpdate(runId, attemptId).orElseThrow(() ->
                    new IllegalStateException("Reconciliation claim does not exist"));
            if (head.record != null) {
                if (head.record.requestFingerprint().equals(record.requestFingerprint())) {
                    return head.record;
                }
                throw new IllegalStateException("Side-effect attempt is already reconciled");
            }
            if (!"CLAIMED".equals(head.state) || !head.ownerToken.equals(ownerToken)) {
                throw new IllegalStateException("Reconciliation claim is no longer owned");
            }
            if (!record.fingerprintVerified()) {
                throw new IllegalArgumentException("Reconciliation record fingerprint is invalid");
            }
            String json = write(record);
            jdbc.update("""
                            INSERT INTO visual_side_effect_reconciliations
                                (reconciliation_id, request_id, run_id, attempt_id, sequence,
                                 record_fingerprint, created_at, record_json)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    record.reconciliationId(), record.requestId(), runId, attemptId,
                    record.chain().sequence(), record.recordFingerprint(),
                    record.resolution().observedAt().toString(), json);
            int updated = jdbc.update("""
                            UPDATE visual_side_effect_reconciliation_heads
                            SET state = 'RESOLVED', owner_token = '', lease_until = ?, record_json = ?, updated_at = ?
                            WHERE run_id = ? AND attempt_id = ? AND owner_token = ? AND state = 'CLAIMED'
                            """,
                    Instant.EPOCH.toString(), json, Instant.now().toString(), runId, attemptId, ownerToken);
            if (updated != 1) {
                throw new IllegalStateException("Reconciliation fencing token was rejected");
            }
            outbox.append(IntegrationChangeEvent.pending(
                    "SIDE_EFFECT_RECONCILED",
                    record.baseEvidence().tenantId(), record.baseEvidence().namespace(),
                    record.baseEvidence().environmentId(),
                    new IntegrationChangeEvent.Aggregate("SIDE_EFFECT_RECONCILIATION",
                            record.reconciliationId(), record.chain().sequence(), record.recordFingerprint()),
                    "/api/integration/runs/" + runId + "/side-effects/reconciliations",
                    record.actor().correlationId()));
            return record;
        });
        if (result == null) {
            throw new IllegalStateException("Reconciliation completion transaction returned no result");
        }
        return result;
    }

    @Override
    public Optional<SideEffectReconciliationRecord> find(String runId, String attemptId) {
        return jdbc.query("""
                        SELECT record_json FROM visual_side_effect_reconciliations
                        WHERE run_id = ? AND attempt_id = ?
                        ORDER BY sequence DESC LIMIT 1
                        """, (rs, rowNum) -> read(rs.getString("record_json")), runId, attemptId)
                .stream().findFirst();
    }

    @Override
    public Optional<SideEffectReconciliationRecord> findByRequestId(String requestId) {
        return jdbc.query("SELECT record_json FROM visual_side_effect_reconciliations WHERE request_id = ?",
                        (rs, rowNum) -> read(rs.getString("record_json")), requestId)
                .stream().findFirst();
    }

    @Override
    public List<SideEffectReconciliationRecord> forRun(String runId) {
        return jdbc.query("""
                        SELECT record_json FROM visual_side_effect_reconciliations
                        WHERE run_id = ? ORDER BY sequence ASC, reconciliation_id ASC
                        """, (rs, rowNum) -> read(rs.getString("record_json")), runId);
    }

    @Override
    public boolean available() {
        return true;
    }

    private Optional<Head> headForUpdate(String runId, String attemptId) {
        return jdbc.query(SELECT_HEAD_FOR_UPDATE, (rs, rowNum) -> new Head(
                        runId, attemptId, rs.getString("request_id"), rs.getString("request_fingerprint"),
                        rs.getString("state"), rs.getString("owner_token"),
                        Instant.parse(rs.getString("lease_until")), readNullable(rs.getString("record_json"))),
                runId, attemptId).stream().findFirst();
    }

    private Optional<Head> headByRequest(String requestId) {
        return jdbc.query("""
                        SELECT run_id, attempt_id, request_id, request_fingerprint, state,
                               owner_token, lease_until, record_json
                        FROM visual_side_effect_reconciliation_heads WHERE request_id = ?
                        """, (rs, rowNum) -> new Head(
                        rs.getString("run_id"), rs.getString("attempt_id"), rs.getString("request_id"),
                        rs.getString("request_fingerprint"), rs.getString("state"),
                        rs.getString("owner_token"), Instant.parse(rs.getString("lease_until")),
                        readNullable(rs.getString("record_json"))), requestId)
                .stream().findFirst();
    }

    private String write(SideEffectReconciliationRecord record) {
        try {
            return objectMapper.writeValueAsString(record);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize side-effect reconciliation record", exception);
        }
    }

    private SideEffectReconciliationRecord read(String json) {
        try {
            SideEffectReconciliationRecord record = objectMapper.readValue(json,
                    SideEffectReconciliationRecord.class);
            if (!record.fingerprintVerified()) {
                throw new IllegalStateException("Persisted side-effect reconciliation fingerprint is invalid");
            }
            return record;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize side-effect reconciliation record", exception);
        }
    }

    private SideEffectReconciliationRecord readNullable(String json) {
        return json == null || json.isBlank() ? null : read(json);
    }

    private record Head(String runId, String attemptId, String requestId, String requestFingerprint,
                        String state, String ownerToken, Instant leaseUntil,
                        SideEffectReconciliationRecord record) {
    }
}
