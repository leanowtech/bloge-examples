package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** JDBC V013 authority for synchronous immutable Simulation runs. */
public final class JdbcSimulationRunStore implements SimulationRunStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final Duration leaseDuration;

    /** Creates the store over the application transaction manager and configured mapper. */
    public JdbcSimulationRunStore(JdbcTemplate jdbc, TransactionTemplate transactions,
                                  ObjectMapper mapper, Duration leaseDuration) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        if (!(transactions.getTransactionManager() instanceof DataSourceTransactionManager manager)
                || jdbc.getDataSource() == null || manager.getDataSource() != jdbc.getDataSource()) {
            throw new IllegalArgumentException("Simulation store and transaction manager must share one DataSource");
        }
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy().findAndRegisterModules();
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.leaseDuration = leaseDuration;
    }

    @Override public Claim claim(AuthoringScope scope, String idempotencyKey, String requestFingerprint,
                                 Supplier<String> runIdFactory, Instant startedAt) {
        validate(scope, idempotencyKey, requestFingerprint, runIdFactory, startedAt);
        try {
            return require(transactions.execute(status -> claimInTransaction(scope, idempotencyKey,
                    requestFingerprint, runIdFactory, startedAt)));
        } catch (DuplicateKeyException race) {
            return require(transactions.execute(status -> existingClaim(scope, idempotencyKey,
                    requestFingerprint, databaseNow(), true)));
        } catch (SimulationFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        }
    }

    @Override public SimulationRun complete(AuthoringScope scope, String idempotencyKey,
                                             String requestFingerprint, SimulationRun run) {
        if (scope == null || run == null || run.status() == SimulationRun.Status.RUNNING) {
            throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        }
        try {
            return require(transactions.execute(status -> {
                String json = encode(run);
                int updated = jdbc.update("""
                        UPDATE rg_authoring_simulation_runs
                           SET status=?, run_json=?, ended_at=?
                         WHERE tenant_id=? AND project_id=? AND environment_id=?
                           AND idempotency_key=? AND request_fingerprint=? AND run_id=?
                           AND status='RUNNING' AND run_json IS NULL
                        """, run.status().name(), json, Timestamp.from(requireEnded(run)),
                        scope.tenantId(), scope.projectId(), scope.environmentId(), idempotencyKey,
                        requestFingerprint, run.runId());
                if (updated == 1) return run;
                Row prior = rowByKey(scope, idempotencyKey, false)
                        .orElseThrow(() -> new SimulationFailure(SimulationFailure.Code.INTEGRITY));
                if (!prior.fingerprint().equals(requestFingerprint) || !prior.runId().equals(run.runId())
                        || prior.runJson() == null) {
                    throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
                }
                SimulationRun replay = decode(prior);
                if (!replay.equals(run)) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
                return replay;
            }));
        } catch (SimulationFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        }
    }

    @Override public Optional<SimulationRun> find(AuthoringScope scope, String runId) {
        if (scope == null || runId == null || runId.isBlank()) return Optional.empty();
        try {
            List<Row> rows = jdbc.query("""
                    SELECT idempotency_key, run_id, request_fingerprint, status, lease_until, run_json
                      FROM rg_authoring_simulation_runs
                     WHERE tenant_id=? AND project_id=? AND environment_id=? AND run_id=?
                       AND status<>'RUNNING' AND run_json IS NOT NULL
                    """, (rs, row) -> new Row(scope, rs.getString(1), rs.getString(2),
                    rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(), rs.getString(6)),
                    scope.tenantId(), scope.projectId(),
                    scope.environmentId(), runId);
            if (rows.size() > 1) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
            return rows.isEmpty() ? Optional.empty() : Optional.of(decode(rows.getFirst()));
        } catch (SimulationFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        }
    }

    private Claim claimInTransaction(AuthoringScope scope, String key, String fingerprint,
                                     Supplier<String> ids, Instant startedAt) {
        Instant now = databaseNow();
        Optional<Row> existing = rowByKey(scope, key, true);
        if (existing.isPresent()) return existingClaim(existing.get(), fingerprint, now);
        String runId = ids.get();
        if (runId == null || runId.isBlank()) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        jdbc.update("""
                INSERT INTO rg_authoring_simulation_runs
                    (tenant_id, project_id, environment_id, run_id, idempotency_key,
                     request_fingerprint, status, run_json, lease_until, started_at, ended_at)
                VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', NULL, ?, ?, NULL)
                """, scope.tenantId(), scope.projectId(), scope.environmentId(), runId, key, fingerprint,
                Timestamp.from(now.plus(leaseDuration)), Timestamp.from(startedAt));
        return new Claim.Acquired(runId);
    }

    private Claim existingClaim(AuthoringScope scope, String key, String fingerprint,
                                Instant now, boolean lock) {
        Row row = rowByKey(scope, key, lock)
                .orElseThrow(() -> new SimulationFailure(SimulationFailure.Code.INTEGRITY));
        return existingClaim(row, fingerprint, now);
    }

    private Claim existingClaim(Row row, String fingerprint, Instant now) {
        if (!row.fingerprint().equals(fingerprint)) return new Claim.Conflict();
        if (row.runJson() != null) return new Claim.Replay(decode(row));
        if (row.leaseUntil().isAfter(now)) return new Claim.Busy(row.runId());
        int updated = jdbc.update("""
                UPDATE rg_authoring_simulation_runs
                   SET lease_until=?
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND idempotency_key=?
                   AND status='RUNNING' AND run_json IS NULL AND lease_until<=CURRENT_TIMESTAMP
                """, Timestamp.from(now.plus(leaseDuration)), row.scope().tenantId(), row.scope().projectId(),
                row.scope().environmentId(), row.idempotencyKey());
        if (updated != 1) return new Claim.Busy(row.runId());
        return new Claim.Acquired(row.runId());
    }

    private Optional<Row> rowByKey(AuthoringScope scope, String key, boolean lock) {
        String sql = """
                SELECT run_id, request_fingerprint, status, lease_until, run_json
                  FROM rg_authoring_simulation_runs
                 WHERE tenant_id=? AND project_id=? AND environment_id=? AND idempotency_key=?
                """ + (lock ? " FOR UPDATE" : "");
        List<Row> rows = jdbc.query(sql, (rs, row) -> new Row(scope, key, rs.getString(1),
                rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)),
                scope.tenantId(), scope.projectId(), scope.environmentId(), key);
        if (rows.size() > 1) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        return rows.stream().findFirst();
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        return value.toInstant();
    }

    private SimulationRun decode(String json) {
        try {
            return mapper.readValue(json, SimulationRun.class);
        } catch (Exception failure) {
            throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        }
    }

    private SimulationRun decode(Row row) {
        SimulationRun run = decode(row.runJson());
        if (!SimulationRun.SCHEMA_VERSION.equals(run.schemaVersion())
                || !row.runId().equals(run.runId()) || !row.status().equals(run.status().name())) {
            throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        }
        return run;
    }

    private String encode(SimulationRun run) {
        try {
            return mapper.writeValueAsString(run);
        } catch (Exception failure) {
            throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        }
    }

    private static Instant requireEnded(SimulationRun run) {
        if (run.endedAt() == null) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        return run.endedAt();
    }

    private static <T> T require(T value) {
        if (value == null) throw new SimulationFailure(SimulationFailure.Code.INTEGRITY);
        return value;
    }

    private static void validate(AuthoringScope scope, String key, String fingerprint,
                                 Supplier<String> ids, Instant startedAt) {
        if (scope == null || key == null || key.isBlank() || key.length() > 160
                || fingerprint == null || !fingerprint.matches("sha256:[0-9a-f]{64}")
                || ids == null || startedAt == null) {
            throw new SimulationFailure(SimulationFailure.Code.VALIDATION);
        }
    }

    private record Row(AuthoringScope scope, String idempotencyKey, String runId,
                       String fingerprint, String status, Instant leaseUntil, String runJson) { }
}
