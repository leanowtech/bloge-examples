package com.leanowtech.bloge.gateway.testing.world.fidelity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/** PostgreSQL/H2 repository for immutable calibration history and tenant-scoped drift heads. */
public final class DatabaseWorldFidelityDriftRepository implements WorldFidelityDriftRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TransactionTemplate transactions;

    public DatabaseWorldFidelityDriftRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        if (jdbc == null || jdbc.getDataSource() == null || mapper == null) throw invalid();
        this.jdbc = jdbc;
        this.mapper = mapper.copy();
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    /** Test/local schema bootstrap. Production schema comes from the versioned migration. */
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_world_fidelity_reports (
                    tenant_id VARCHAR(512) NOT NULL,
                    target_fingerprint VARCHAR(80) NOT NULL,
                    report_fingerprint VARCHAR(80) NOT NULL,
                    contract_fingerprint VARCHAR(80) NOT NULL,
                    world_slice_fingerprint VARCHAR(80) NOT NULL,
                    implementation_fingerprint VARCHAR(80) NOT NULL,
                    sample_set_fingerprint VARCHAR(80) NOT NULL,
                    outcome VARCHAR(32) NOT NULL,
                    report_projection_json TEXT NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (tenant_id, target_fingerprint, report_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_world_fidelity_drift_heads (
                    tenant_id VARCHAR(512) NOT NULL,
                    target_fingerprint VARCHAR(80) NOT NULL,
                    state VARCHAR(32) NOT NULL,
                    report_fingerprint VARCHAR(80) NOT NULL,
                    contract_fingerprint VARCHAR(80) NOT NULL,
                    world_slice_fingerprint VARCHAR(80) NOT NULL,
                    implementation_fingerprint VARCHAR(80) NOT NULL,
                    sample_set_fingerprint VARCHAR(80) NOT NULL,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (tenant_id, target_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_world_fidelity_receipts (
                    tenant_id VARCHAR(512) NOT NULL,
                    receipt_fingerprint VARCHAR(80) NOT NULL,
                    target_fingerprint VARCHAR(80) NOT NULL,
                    consumed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (tenant_id, receipt_fingerprint)
                )
                """);
    }

    @Override
    public Optional<DriftAnnotation> current(String tenantId, String targetFingerprint) {
        if (!scope(tenantId, targetFingerprint)) return Optional.empty();
        return jdbc.query("""
                SELECT state, report_fingerprint, target_fingerprint, contract_fingerprint,
                       world_slice_fingerprint, implementation_fingerprint, sample_set_fingerprint
                  FROM rg_world_fidelity_drift_heads
                 WHERE tenant_id=? AND target_fingerprint=?
                """, rs -> rs.next() ? Optional.of(annotation(rs)) : Optional.empty(), tenantId, targetFingerprint);
    }

    @Override
    public void append(String tenantId, WorldFidelityReport report) {
        if (!scope(tenantId, report == null ? null : report.targetFingerprint())
                || report == null || !report.verify(mapper)) throw invalid();
        String json;
        try {
            json = mapper.writeValueAsString(PayloadFreeReportProjection.from(report));
        } catch (Exception failure) {
            throw invalid();
        }
        try {
            jdbc.update("""
                    INSERT INTO rg_world_fidelity_reports
                        (tenant_id,target_fingerprint,report_fingerprint,contract_fingerprint,
                         world_slice_fingerprint,implementation_fingerprint,sample_set_fingerprint,
                         outcome,report_projection_json)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """, tenantId, report.targetFingerprint(), report.reportFingerprint(), report.contractFingerprint(),
                    report.worldSliceFingerprint(), report.implementationFingerprint(), report.sampleSetFingerprint(),
                    report.outcome().name(), json);
        } catch (DuplicateKeyException conflict) {
            throw WorldFidelityException.of(WorldFidelityException.Code.DRIFT_CAS_CONFLICT);
        }
    }

    @Override
    public List<WorldFidelityReport> history(String tenantId, String targetFingerprint) {
        if (!scope(tenantId, targetFingerprint)) return List.of();
        return jdbc.query("""
                SELECT report_projection_json
                  FROM rg_world_fidelity_reports
                 WHERE tenant_id=? AND target_fingerprint=?
                 ORDER BY report_fingerprint
                """, (rs, row) -> decode(rs.getString(1)), tenantId, targetFingerprint);
    }

    @Override
    public boolean compareAndSet(String tenantId, String targetFingerprint, DriftState expected,
                                 DriftAnnotation next) {
        if (!validAnnotation(tenantId, targetFingerprint, next)) return false;
        try {
            Boolean result = transactions.execute(status -> {
                DriftAnnotation actual = lockedCurrent(tenantId, targetFingerprint).orElse(null);
                if (!matches(actual, expected)) return false;
                if (actual == null) {
                    insertHead(tenantId, next);
                } else if (jdbc.update("""
                        UPDATE rg_world_fidelity_drift_heads
                           SET state=?,report_fingerprint=?,contract_fingerprint=?,world_slice_fingerprint=?,
                               implementation_fingerprint=?,sample_set_fingerprint=?,updated_at=CURRENT_TIMESTAMP
                         WHERE tenant_id=? AND target_fingerprint=? AND state=? AND report_fingerprint=?
                        """, next.state().name(), next.reportFingerprint(), next.contractFingerprint(),
                        next.worldSliceFingerprint(), next.implementationFingerprint(), next.sampleSetFingerprint(),
                        tenantId, targetFingerprint, actual.state().name(), actual.reportFingerprint()) != 1) {
                    return false;
                }
                return true;
            });
            return Boolean.TRUE.equals(result);
        } catch (DuplicateKeyException race) {
            return false;
        }
    }

    @Override
    public boolean compareAndSetAndConsumeReceipt(String tenantId, String targetFingerprint,
                                                  DriftState expected, DriftAnnotation next,
                                                  String receiptFingerprint) {
        if (!validAnnotation(tenantId, targetFingerprint, next) || !validFingerprint(receiptFingerprint)) return false;
        try {
            Boolean result = transactions.execute(status -> {
                DriftAnnotation actual = lockedCurrent(tenantId, targetFingerprint).orElse(null);
                if (!matches(actual, expected) || receiptExists(tenantId, receiptFingerprint)) return false;
                jdbc.update("INSERT INTO rg_world_fidelity_receipts(tenant_id,receipt_fingerprint,target_fingerprint) VALUES (?,?,?)",
                        tenantId, receiptFingerprint, targetFingerprint);
                if (actual == null) insertHead(tenantId, next);
                else if (jdbc.update("""
                        UPDATE rg_world_fidelity_drift_heads
                           SET state=?,report_fingerprint=?,contract_fingerprint=?,world_slice_fingerprint=?,
                               implementation_fingerprint=?,sample_set_fingerprint=?,updated_at=CURRENT_TIMESTAMP
                         WHERE tenant_id=? AND target_fingerprint=? AND state=? AND report_fingerprint=?
                        """, next.state().name(), next.reportFingerprint(), next.contractFingerprint(),
                        next.worldSliceFingerprint(), next.implementationFingerprint(), next.sampleSetFingerprint(),
                        tenantId, targetFingerprint, actual.state().name(), actual.reportFingerprint()) != 1) {
                    status.setRollbackOnly();
                    return false;
                }
                return true;
            });
            return Boolean.TRUE.equals(result);
        } catch (DuplicateKeyException race) {
            return false;
        }
    }

    @Override
    public boolean consumeReceipt(String tenantId, String receiptFingerprint) {
        if (!scope(tenantId, receiptFingerprint)) return false;
        try {
            return jdbc.update("INSERT INTO rg_world_fidelity_receipts(tenant_id,receipt_fingerprint,target_fingerprint) VALUES (?,?,?)",
                    tenantId, receiptFingerprint, "sha256:" + "0".repeat(64)) == 1;
        } catch (DuplicateKeyException alreadyConsumed) {
            return false;
        }
    }

    private Optional<DriftAnnotation> lockedCurrent(String tenantId, String targetFingerprint) {
        return jdbc.query("""
                SELECT state, report_fingerprint, target_fingerprint, contract_fingerprint,
                       world_slice_fingerprint, implementation_fingerprint, sample_set_fingerprint
                  FROM rg_world_fidelity_drift_heads
                 WHERE tenant_id=? AND target_fingerprint=? FOR UPDATE
                """, rs -> rs.next() ? Optional.of(annotation(rs)) : Optional.empty(), tenantId, targetFingerprint);
    }

    private void insertHead(String tenantId, DriftAnnotation value) {
        jdbc.update("""
                INSERT INTO rg_world_fidelity_drift_heads
                    (tenant_id,target_fingerprint,state,report_fingerprint,contract_fingerprint,
                     world_slice_fingerprint,implementation_fingerprint,sample_set_fingerprint)
                VALUES (?,?,?,?,?,?,?,?)
                """, tenantId, value.targetFingerprint(), value.state().name(), value.reportFingerprint(),
                value.contractFingerprint(), value.worldSliceFingerprint(), value.implementationFingerprint(),
                value.sampleSetFingerprint());
    }

    private boolean receiptExists(String tenantId, String receiptFingerprint) {
        return Boolean.TRUE.equals(jdbc.query(
                "SELECT 1 FROM rg_world_fidelity_receipts WHERE tenant_id=? AND receipt_fingerprint=?",
                (ResultSetExtractor<Boolean>) rs -> rs.next(), tenantId, receiptFingerprint));
    }

    private static DriftAnnotation annotation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DriftAnnotation(DriftState.valueOf(rs.getString(1)), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7));
    }

    private WorldFidelityReport decode(String json) {
        try {
            WorldFidelityReport report = mapper.readValue(json, WorldFidelityReport.class);
            if (!report.verify(mapper)) throw invalid();
            return report;
        } catch (Exception failure) {
            throw invalid();
        }
    }

    private static boolean matches(DriftAnnotation actual, DriftState expected) {
        return actual == null ? expected == null : actual.state() == expected;
    }

    private static boolean validAnnotation(String tenant, String target, DriftAnnotation next) {
        return scope(tenant, target) && next != null && target.equals(next.targetFingerprint());
    }

    private static boolean scope(String tenant, String value) {
        return tenant != null && !tenant.isBlank() && tenant.length() <= 512
                && tenant.chars().noneMatch(Character::isISOControl) && validFingerprint(value);
    }

    private static boolean validFingerprint(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    private static WorldFidelityException invalid() {
        return WorldFidelityException.of(WorldFidelityException.Code.PERSISTENCE_INVALID);
    }

    /** Keeps persistence explicitly payload-free even if the runtime report gains new fields later. */
    private record PayloadFreeReportProjection(
            String algorithmVersion,
            String requestFingerprint,
            String targetFingerprint,
            String sampleSetFingerprint,
            String implementationFingerprint,
            String worldSliceFingerprint,
            String contractFingerprint,
            String comparatorFingerprint,
            List<WorldFidelityReport.Observation> observations,
            WorldFidelityReport.Outcome outcome,
            String reportFingerprint) {
        private static PayloadFreeReportProjection from(WorldFidelityReport report) {
            return new PayloadFreeReportProjection(report.algorithmVersion(), report.requestFingerprint(),
                    report.targetFingerprint(), report.sampleSetFingerprint(), report.implementationFingerprint(),
                    report.worldSliceFingerprint(), report.contractFingerprint(), report.comparatorFingerprint(),
                    report.observations(), report.outcome(), report.reportFingerprint());
        }
    }

}
