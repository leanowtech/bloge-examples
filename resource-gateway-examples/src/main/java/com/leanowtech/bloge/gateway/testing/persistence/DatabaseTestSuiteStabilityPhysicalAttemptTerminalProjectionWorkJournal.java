package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Database adapter for transaction-bound physical terminal projection-work registration.
 *
 * <p>The adapter returns a mutation instead of opening its own transaction. The observation
 * reconciliation journal applies that mutation through its already enlisted JDBC facade so the
 * terminal target transition and work registration commit or roll back together. Reads recompute
 * both trigger identity and whole-row integrity before returning a scoped entry.</p>
 */
public final class DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
        implements TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal {

    private static final String ENTRY_FINGERPRINT_SCHEMA =
            "bloge.testSuiteStabilityPhysicalAttemptTerminalProjectionWorkRecord.v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Creates a work journal over the isolated test-runtime datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal(
            JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Creates the forward-compatible work lifecycle table and due-work index. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_stability_attempt_terminal_projection_work (
                    attempt_id VARCHAR(96) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(32) NOT NULL,
                    work_id VARCHAR(128) NOT NULL,
                    trigger_fingerprint VARCHAR(71) NOT NULL,
                    trigger_json CLOB NOT NULL,
                    observation_command_id VARCHAR(128) NOT NULL,
                    reconciliation_result_fingerprint VARCHAR(71) NOT NULL,
                    work_status VARCHAR(32) NOT NULL,
                    next_attempt_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(36) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_claimed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    execution_attempts BIGINT NOT NULL,
                    consecutive_proof_pending INTEGER NOT NULL,
                    consecutive_unavailable INTEGER NOT NULL,
                    last_result_kind VARCHAR(32) NOT NULL,
                    last_failure_reason VARCHAR(64) NOT NULL,
                    projection_id VARCHAR(128) NOT NULL,
                    last_result_fingerprint VARCHAR(71) NOT NULL,
                    registered_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT uq_rg_test_stability_attempt_terminal_projection_work_id
                        UNIQUE (work_id),
                    CONSTRAINT uq_rg_test_stability_attempt_terminal_projection_observation
                        UNIQUE (observation_command_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_stability_attempt_terminal_projection_work_due
                ON rg_test_stability_attempt_terminal_projection_work (
                    work_status, next_attempt_at, lease_until, attempt_id
                )
                """);
    }

    /** {@inheritDoc} */
    @Override
    public TestRuntimeTransactionMutation boundRegister(Trigger trigger) {
        Trigger exact = requireTrigger(trigger);
        String triggerJson = encode(exact);
        return transactionJdbc -> register(
                Objects.requireNonNull(transactionJdbc, "transactionJdbc"),
                exact, triggerJson);
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Entry> find(String tenantId, String environmentId, String attemptId) {
        String tenant = required(tenantId, "tenantId");
        String environment = required(environmentId, "environmentId");
        String attempt = required(attemptId, "attemptId");
        List<Entry> rows = jdbc.query("""
                SELECT attempt_id, tenant_id, environment_id, work_id,
                       trigger_fingerprint, trigger_json, observation_command_id,
                       reconciliation_result_fingerprint, work_status, next_attempt_at,
                       lease_owner, lease_token, lease_epoch, lease_claimed_at, lease_until,
                       execution_attempts, consecutive_proof_pending,
                       consecutive_unavailable, last_result_kind, last_failure_reason,
                       projection_id, last_result_fingerprint, registered_at, updated_at,
                       record_fingerprint
                FROM rg_test_stability_attempt_terminal_projection_work
                WHERE tenant_id = ? AND environment_id = ? AND attempt_id = ?
                """, this::map, tenant, environment, attempt);
        if (rows.size() > 1) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
        return rows.stream().findFirst().map(this::validate);
    }

    private void register(JdbcTemplate transactionJdbc, Trigger trigger, String triggerJson) {
        Optional<Entry> retained = find(transactionJdbc, trigger.attemptId());
        if (retained.isPresent()) {
            if (!validate(retained.orElseThrow()).trigger().equals(trigger)) {
                throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
            }
            return;
        }
        Instant now = databaseNow(transactionJdbc);
        Entry entry = fingerprinted(new Entry(
                Entry.SCHEMA_VERSION, trigger, Status.READY, now,
                "", "", 0, Instant.EPOCH, Instant.EPOCH,
                0, 0, 0, ResultKind.NONE,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason.NONE,
                "", "", now, now, placeholderFingerprint()));
        try {
            insert(transactionJdbc, entry, triggerJson);
        } catch (DuplicateKeyException duplicate) {
            Optional<Entry> winner = find(transactionJdbc, trigger.attemptId());
            if (winner.isPresent() && validate(winner.orElseThrow()).trigger().equals(trigger)) {
                return;
            }
            throw conflict(ConflictReason.IDEMPOTENCY_CONFLICT);
        }
    }

    private Optional<Entry> find(JdbcTemplate target, String attemptId) {
        List<Entry> rows = target.query("""
                SELECT attempt_id, tenant_id, environment_id, work_id,
                       trigger_fingerprint, trigger_json, observation_command_id,
                       reconciliation_result_fingerprint, work_status, next_attempt_at,
                       lease_owner, lease_token, lease_epoch, lease_claimed_at, lease_until,
                       execution_attempts, consecutive_proof_pending,
                       consecutive_unavailable, last_result_kind, last_failure_reason,
                       projection_id, last_result_fingerprint, registered_at, updated_at,
                       record_fingerprint
                FROM rg_test_stability_attempt_terminal_projection_work
                WHERE attempt_id = ?
                """, this::map, attemptId);
        if (rows.size() > 1) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
        return rows.stream().findFirst();
    }

    private void insert(JdbcTemplate target, Entry entry, String triggerJson) {
        target.update("""
                INSERT INTO rg_test_stability_attempt_terminal_projection_work (
                    attempt_id, tenant_id, environment_id, work_id,
                    trigger_fingerprint, trigger_json, observation_command_id,
                    reconciliation_result_fingerprint, work_status, next_attempt_at,
                    lease_owner, lease_token, lease_epoch, lease_claimed_at, lease_until,
                    execution_attempts, consecutive_proof_pending,
                    consecutive_unavailable, last_result_kind, last_failure_reason,
                    projection_id, last_result_fingerprint, registered_at, updated_at,
                    record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, entry.trigger().attemptId(), entry.trigger().tenantId(),
                entry.trigger().environmentId(), entry.trigger().workId(),
                entry.trigger().triggerFingerprint(), triggerJson,
                entry.trigger().observationCommandId(),
                entry.trigger().reconciliationResultFingerprint(), entry.status().name(),
                Timestamp.from(entry.nextAttemptAt()), entry.leaseOwner(), entry.leaseToken(),
                entry.leaseEpoch(), Timestamp.from(entry.leaseClaimedAt()),
                Timestamp.from(entry.leaseUntil()), entry.executionAttempts(),
                entry.consecutiveProofPending(), entry.consecutiveUnavailable(),
                entry.lastResultKind().name(), entry.lastFailureReason().name(),
                entry.projectionId(), entry.lastResultFingerprint(),
                Timestamp.from(entry.registeredAt()), Timestamp.from(entry.updatedAt()),
                entry.recordFingerprint());
    }

    private Entry map(ResultSet rs, int row) throws SQLException {
        try {
            Trigger trigger = decode(rs.getString("trigger_json"));
            if (!trigger.attemptId().equals(rs.getString("attempt_id"))
                    || !trigger.tenantId().equals(rs.getString("tenant_id"))
                    || !trigger.environmentId().equals(rs.getString("environment_id"))
                    || !trigger.workId().equals(rs.getString("work_id"))
                    || !trigger.triggerFingerprint().equals(
                    rs.getString("trigger_fingerprint"))
                    || !trigger.observationCommandId().equals(
                    rs.getString("observation_command_id"))
                    || !trigger.reconciliationResultFingerprint().equals(
                    rs.getString("reconciliation_result_fingerprint"))) {
                throw conflict(ConflictReason.INTEGRITY_FAILURE);
            }
            return new Entry(Entry.SCHEMA_VERSION, trigger,
                    Status.valueOf(rs.getString("work_status")),
                    rs.getTimestamp("next_attempt_at").toInstant(),
                    rs.getString("lease_owner"), rs.getString("lease_token"),
                    rs.getLong("lease_epoch"),
                    rs.getTimestamp("lease_claimed_at").toInstant(),
                    rs.getTimestamp("lease_until").toInstant(),
                    rs.getLong("execution_attempts"),
                    rs.getInt("consecutive_proof_pending"),
                    rs.getInt("consecutive_unavailable"),
                    ResultKind.valueOf(rs.getString("last_result_kind")),
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                            .valueOf(rs.getString("last_failure_reason")),
                    rs.getString("projection_id"),
                    rs.getString("last_result_fingerprint"),
                    rs.getTimestamp("registered_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getString("record_fingerprint"));
        } catch (RuntimeException invalid) {
            if (invalid instanceof ConflictException conflict) {
                throw conflict;
            }
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
    }

    private Entry validate(Entry entry) {
        try {
            Trigger trigger = requireTrigger(entry.trigger());
            String expected = entryFingerprint(new Entry(
                    entry.schemaVersion(), trigger, entry.status(), entry.nextAttemptAt(),
                    entry.leaseOwner(), entry.leaseToken(), entry.leaseEpoch(),
                    entry.leaseClaimedAt(), entry.leaseUntil(), entry.executionAttempts(),
                    entry.consecutiveProofPending(), entry.consecutiveUnavailable(),
                    entry.lastResultKind(), entry.lastFailureReason(), entry.projectionId(),
                    entry.lastResultFingerprint(), entry.registeredAt(), entry.updatedAt(),
                    placeholderFingerprint()));
            if (!entry.recordFingerprint().equals(expected)) {
                throw conflict(ConflictReason.INTEGRITY_FAILURE);
            }
            return entry;
        } catch (RuntimeException invalid) {
            if (invalid instanceof ConflictException conflict) {
                throw conflict;
            }
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
    }

    private Trigger requireTrigger(Trigger trigger) {
        Trigger exact = Objects.requireNonNull(trigger, "trigger");
        String expected = ProtocolFingerprint.of(objectMapper, exact.canonicalMaterial());
        if (!expected.equals(exact.triggerFingerprint())) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
        return exact;
    }

    private Entry fingerprinted(Entry entry) {
        return new Entry(entry.schemaVersion(), entry.trigger(), entry.status(),
                entry.nextAttemptAt(), entry.leaseOwner(), entry.leaseToken(),
                entry.leaseEpoch(), entry.leaseClaimedAt(), entry.leaseUntil(),
                entry.executionAttempts(), entry.consecutiveProofPending(),
                entry.consecutiveUnavailable(), entry.lastResultKind(),
                entry.lastFailureReason(), entry.projectionId(),
                entry.lastResultFingerprint(), entry.registeredAt(), entry.updatedAt(),
                entryFingerprint(entry));
    }

    private String entryFingerprint(Entry entry) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", ENTRY_FINGERPRINT_SCHEMA);
        material.put("triggerFingerprint", entry.trigger().triggerFingerprint());
        material.put("status", entry.status());
        material.put("nextAttemptAt", entry.nextAttemptAt());
        material.put("leaseOwner", entry.leaseOwner());
        material.put("leaseToken", entry.leaseToken());
        material.put("leaseEpoch", entry.leaseEpoch());
        material.put("leaseClaimedAt", entry.leaseClaimedAt());
        material.put("leaseUntil", entry.leaseUntil());
        material.put("executionAttempts", entry.executionAttempts());
        material.put("consecutiveProofPending", entry.consecutiveProofPending());
        material.put("consecutiveUnavailable", entry.consecutiveUnavailable());
        material.put("lastResultKind", entry.lastResultKind());
        material.put("lastFailureReason", entry.lastFailureReason());
        material.put("projectionId", entry.projectionId());
        material.put("lastResultFingerprint", entry.lastResultFingerprint());
        material.put("registeredAt", entry.registeredAt());
        material.put("updatedAt", entry.updatedAt());
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private Instant databaseNow(JdbcTemplate target) {
        Timestamp value = target.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Database clock returned no value");
        }
        return value.toInstant().truncatedTo(ChronoUnit.MILLIS);
    }

    private String encode(Trigger trigger) {
        try {
            return objectMapper.writeValueAsString(trigger);
        } catch (JsonProcessingException invalid) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
    }

    private Trigger decode(String value) {
        try {
            return objectMapper.readValue(value, Trigger.class);
        } catch (JsonProcessingException invalid) {
            throw conflict(ConflictReason.INTEGRITY_FAILURE);
        }
    }

    private static String placeholderFingerprint() {
        return "sha256:" + "0".repeat(64);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static ConflictException conflict(ConflictReason reason) {
        return new ConflictException(reason);
    }
}
