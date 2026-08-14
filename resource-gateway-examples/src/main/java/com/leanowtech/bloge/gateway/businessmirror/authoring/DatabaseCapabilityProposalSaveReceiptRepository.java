package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;

/** H2/PostgreSQL-compatible Proposal command lock and restart-safe receipt journal. */
public final class DatabaseCapabilityProposalSaveReceiptRepository
        implements CapabilityProposalSaveReceiptRepository {
    private static final String CREATE_LOCKS = """
            CREATE TABLE IF NOT EXISTS business_mirror_proposal_save_locks (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                idempotency_key VARCHAR(160) NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, idempotency_key)
            )
            """;
    private static final String CREATE_RECEIPTS = """
            CREATE TABLE IF NOT EXISTS business_mirror_proposal_save_receipts (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                idempotency_key VARCHAR(160) NOT NULL,
                request_fingerprint VARCHAR(80) NOT NULL,
                receipt_json TEXT NOT NULL,
                completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, idempotency_key)
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Dialect dialect;

    public DatabaseCapabilityProposalSaveReceiptRepository(
            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.dialect = detectDialect(jdbc);
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_LOCKS);
        jdbc.execute(CREATE_RECEIPTS);
    }

    @Override
    public <T> T withCommandLock(
            CapabilitySnapshot.Scope scope, String idempotencyKey, Supplier<T> operation) {
        jdbc.update(dialect.lockAdmissionSql,
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), idempotencyKey);
        jdbc.queryForObject("""
                        SELECT idempotency_key FROM business_mirror_proposal_save_locks
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND idempotency_key = ?
                        FOR UPDATE
                        """,
                String.class, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), idempotencyKey);
        return operation.get();
    }

    @Override
    public Optional<CapabilityProposalSaveReceipt> find(
            CapabilitySnapshot.Scope scope, String idempotencyKey) {
        return jdbc.query("""
                        SELECT request_fingerprint, receipt_json, completed_at
                        FROM business_mirror_proposal_save_receipts
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND idempotency_key = ?
                        """,
                (rs, row) -> read(rs.getString("receipt_json"), scope,
                        rs.getString("request_fingerprint"),
                        rs.getTimestamp("completed_at").toInstant()),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), idempotencyKey).stream().flatMap(Optional::stream).findFirst();
    }

    @Override
    public void save(CapabilitySnapshot.Scope scope,
                     String idempotencyKey,
                     CapabilityProposalSaveReceipt receipt) {
        jdbc.update("""
                        INSERT INTO business_mirror_proposal_save_receipts (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            idempotency_key, request_fingerprint, receipt_json, completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), idempotencyKey, receipt.requestFingerprint(), serialize(receipt),
                java.sql.Timestamp.from(receipt.completedAt()));
    }

    private Optional<CapabilityProposalSaveReceipt> read(
            String json,
            CapabilitySnapshot.Scope scope,
            String requestFingerprint,
            java.time.Instant completedAt) {
        try {
            CapabilityProposalSaveReceipt receipt =
                    mapper.readValue(json, CapabilityProposalSaveReceipt.class);
            if (!receipt.requestFingerprint().equals(requestFingerprint)
                    || !receipt.completedAt().equals(completedAt)
                    || !receipt.result().scope().equals(scope)) {
                throw new IllegalStateException(
                        "Stored Capability Proposal receipt integrity check failed");
            }
            return Optional.of(receipt);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Capability Proposal receipt", failure);
        }
    }

    private String serialize(CapabilityProposalSaveReceipt receipt) {
        try {
            return mapper.writeValueAsString(receipt);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Capability Proposal receipt", failure);
        }
    }

    private static Dialect detectDialect(JdbcTemplate jdbc) {
        if (jdbc.getDataSource() == null) {
            throw new IllegalStateException("Proposal receipt repository requires a JDBC DataSource");
        }
        try (var connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgresql")) {
                return Dialect.POSTGRESQL;
            }
            if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("h2")) {
                return Dialect.H2;
            }
            throw new IllegalStateException("Unsupported Proposal receipt database: " + product);
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to inspect Proposal receipt database", failure);
        }
    }

    private enum Dialect {
        POSTGRESQL("""
                INSERT INTO business_mirror_proposal_save_locks (
                    tenant_id, organization_id, project_id, environment_id, region_id, idempotency_key
                ) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """),
        H2("""
                MERGE INTO business_mirror_proposal_save_locks (
                    tenant_id, organization_id, project_id, environment_id, region_id, idempotency_key
                ) KEY (tenant_id, organization_id, project_id, environment_id, region_id, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?)
                """);

        private final String lockAdmissionSql;

        Dialect(String lockAdmissionSql) {
            this.lockAdmissionSql = lockAdmissionSql;
        }
    }
}
