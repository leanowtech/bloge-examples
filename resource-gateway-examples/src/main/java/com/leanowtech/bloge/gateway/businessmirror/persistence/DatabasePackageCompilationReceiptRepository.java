package com.leanowtech.bloge.gateway.businessmirror.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceiptRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.Optional;
import java.util.function.Supplier;

/** H2/PostgreSQL command lock and restart-safe Package compilation receipt journal. */
public final class DatabasePackageCompilationReceiptRepository
        implements PackageCompilationReceiptRepository {
    private static final String CREATE_LOCKS = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_compile_locks (
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
            CREATE TABLE IF NOT EXISTS business_mirror_package_compile_receipts (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                idempotency_key VARCHAR(160) NOT NULL,
                request_fingerprint VARCHAR(80) NOT NULL,
                package_id VARCHAR(512) NOT NULL,
                compilation_revision BIGINT NOT NULL,
                receipt_json TEXT NOT NULL,
                completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, idempotency_key)
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Dialect dialect;

    public DatabasePackageCompilationReceiptRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
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
        CapabilitySnapshot.Scope exact = java.util.Objects.requireNonNull(scope, "scope");
        jdbc.update(dialect.lockAdmissionSql,
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), idempotencyKey);
        jdbc.queryForObject("""
                        SELECT idempotency_key FROM business_mirror_package_compile_locks
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND idempotency_key = ?
                        FOR UPDATE
                        """,
                String.class, exact.tenantId(), exact.organizationId(), exact.projectId(),
                exact.environmentId(), exact.region(), idempotencyKey);
        return operation.get();
    }

    @Override
    public Optional<PackageCompilationReceipt> find(
            CapabilitySnapshot.Scope scope, String idempotencyKey) {
        CapabilitySnapshot.Scope exact = java.util.Objects.requireNonNull(scope, "scope");
        return jdbc.query("""
                        SELECT request_fingerprint, package_id, compilation_revision,
                               receipt_json, completed_at
                        FROM business_mirror_package_compile_receipts
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND idempotency_key = ?
                        """,
                (rs, row) -> read(rs.getString("receipt_json"), exact,
                        rs.getString("request_fingerprint"), rs.getString("package_id"),
                        rs.getLong("compilation_revision"),
                        rs.getTimestamp("completed_at").toInstant()),
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), idempotencyKey).stream().flatMap(Optional::stream).findFirst();
    }

    @Override
    public void save(CapabilitySnapshot.Scope scope,
                     String idempotencyKey,
                     PackageCompilationReceipt receipt) {
        CapabilitySnapshot.Scope exact = java.util.Objects.requireNonNull(scope, "scope");
        if (!exact.equals(receipt.readiness().scope())) {
            throw new IllegalArgumentException("Compilation receipt scope does not match repository scope");
        }
        jdbc.update("""
                        INSERT INTO business_mirror_package_compile_receipts (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            idempotency_key, request_fingerprint, package_id,
                            compilation_revision, receipt_json, completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), idempotencyKey, receipt.requestFingerprint(), receipt.packageId(),
                receipt.compilationRevision(), serialize(receipt),
                java.sql.Timestamp.from(receipt.completedAt()));
    }

    private Optional<PackageCompilationReceipt> read(
            String json,
            CapabilitySnapshot.Scope scope,
            String requestFingerprint,
            String packageId,
            long compilationRevision,
            java.time.Instant completedAt) {
        try {
            PackageCompilationReceipt receipt = mapper.readValue(json, PackageCompilationReceipt.class);
            if (!receipt.requestFingerprint().equals(requestFingerprint)
                    || !receipt.packageId().equals(packageId)
                    || receipt.compilationRevision() != compilationRevision
                    || !receipt.completedAt().equals(completedAt)
                    || !receipt.readiness().scope().equals(scope)) {
                throw new IllegalStateException("Stored Package compilation receipt integrity check failed");
            }
            verifyFacts(receipt);
            return Optional.of(receipt);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Package compilation receipt", failure);
        }
    }

    private void verifyFacts(PackageCompilationReceipt receipt) {
        receipt.readiness().verify(mapper);
        receipt.businessAssetLinkClosure().verify(mapper);
        if (receipt.snapshot() != null) {
            receipt.snapshot().verify(mapper);
        }
    }

    private String serialize(PackageCompilationReceipt receipt) {
        try {
            return mapper.writeValueAsString(receipt);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Package compilation receipt", failure);
        }
    }

    private static Dialect detectDialect(JdbcTemplate jdbc) {
        if (jdbc.getDataSource() == null) {
            throw new IllegalStateException("Package compilation receipt repository requires a JDBC DataSource");
        }
        try (var connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("postgresql")) {
                return Dialect.POSTGRESQL;
            }
            if (product != null && product.toLowerCase(java.util.Locale.ROOT).contains("h2")) {
                return Dialect.H2;
            }
            throw new IllegalStateException("Unsupported Package compilation database: " + product);
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to inspect Package compilation database", failure);
        }
    }

    private enum Dialect {
        POSTGRESQL("""
                INSERT INTO business_mirror_package_compile_locks (
                    tenant_id, organization_id, project_id, environment_id, region_id, idempotency_key
                ) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """),
        H2("""
                MERGE INTO business_mirror_package_compile_locks (
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
