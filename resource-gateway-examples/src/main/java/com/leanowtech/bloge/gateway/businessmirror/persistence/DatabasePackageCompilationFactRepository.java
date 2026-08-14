package com.leanowtech.bloge.gateway.businessmirror.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationFactRepository;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetLinkClosure;
import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageSnapshot;
import com.leanowtech.bloge.gateway.businessmirror.domain.PackageReadinessReport;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.Optional;

/** H2/PostgreSQL append-only store for Package compilation facts. */
public final class DatabasePackageCompilationFactRepository
        implements PackageCompilationFactRepository {
    private static final String SCOPE_COLUMNS = """
            tenant_id VARCHAR(255) NOT NULL,
            organization_id VARCHAR(255) NOT NULL,
            project_id VARCHAR(255) NOT NULL,
            environment_id VARCHAR(255) NOT NULL,
            region_id VARCHAR(128) NOT NULL,
            """;
    private static final String COORDINATE_KEY = """
            tenant_id, organization_id, project_id, environment_id, region_id,
            package_id, compilation_revision
            """;
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_compilation_heads (
                %s
                package_id VARCHAR(512) NOT NULL,
                next_revision BIGINT NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, package_id)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_PACKAGE_LOCKS = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_compilation_locks (
                %s
                package_id VARCHAR(512) NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id, region_id, package_id)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_COMPILATIONS = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_compilations (
                %s
                package_id VARCHAR(512) NOT NULL,
                compilation_revision BIGINT NOT NULL,
                request_fingerprint VARCHAR(80) NOT NULL,
                source_draft_revision BIGINT NOT NULL,
                source_draft_fingerprint VARCHAR(80) NOT NULL,
                readiness_status VARCHAR(32) NOT NULL,
                authority_generation VARCHAR(512) NOT NULL,
                completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (%s)
            )
            """.formatted(SCOPE_COLUMNS, COORDINATE_KEY);
    private static final String CREATE_READINESS = factTable("readiness_reports");
    private static final String CREATE_CLOSURES = factTable("asset_link_closures");
    private static final String CREATE_SNAPSHOTS = factTable("snapshots");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Dialect dialect;

    public DatabasePackageCompilationFactRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.dialect = detectDialect(jdbc);
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_PACKAGE_LOCKS);
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_COMPILATIONS);
        jdbc.execute(CREATE_READINESS);
        jdbc.execute(CREATE_CLOSURES);
        jdbc.execute(CREATE_SNAPSHOTS);
    }

    @Override
    public long reserveRevision(CapabilitySnapshot.Scope scope, String packageId) {
        CapabilitySnapshot.Scope exact = java.util.Objects.requireNonNull(scope, "scope");
        String id = required(packageId, "packageId");
        jdbc.update(dialect.packageLockAdmissionSql,
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), id);
        jdbc.queryForObject("""
                        SELECT package_id FROM business_mirror_package_compilation_locks
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                        FOR UPDATE
                        """,
                String.class, exact.tenantId(), exact.organizationId(), exact.projectId(),
                exact.environmentId(), exact.region(), id);
        jdbc.update(dialect.headAdmissionSql,
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), id, 1L);
        Long revision = jdbc.queryForObject("""
                        SELECT next_revision FROM business_mirror_package_compilation_heads
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                        FOR UPDATE
                        """,
                Long.class, exact.tenantId(), exact.organizationId(), exact.projectId(),
                exact.environmentId(), exact.region(), id);
        if (revision == null || revision < 1) {
            throw new IllegalStateException("Package compilation revision allocator is corrupt");
        }
        int updated = jdbc.update("""
                        UPDATE business_mirror_package_compilation_heads SET next_revision = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND next_revision = ?
                        """,
                revision + 1, exact.tenantId(), exact.organizationId(), exact.projectId(),
                exact.environmentId(), exact.region(), id, revision);
        if (updated != 1) {
            throw new IllegalStateException("Package compilation revision reservation lost its fence");
        }
        return revision;
    }

    @Override
    public void append(CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt) {
        CapabilitySnapshot.Scope exact = java.util.Objects.requireNonNull(scope, "scope");
        PackageCompilationReceipt value = java.util.Objects.requireNonNull(receipt, "receipt");
        if (!exact.equals(value.readiness().scope())) {
            throw new IllegalArgumentException("Compilation fact scope does not match repository scope");
        }
        Object[] coordinate = coordinate(exact, value.packageId(), value.compilationRevision());
        insertFact("business_mirror_package_readiness_reports", coordinate,
                value.readiness().fingerprint(), serialize(value.readiness()));
        insertFact("business_mirror_package_asset_link_closures", coordinate,
                value.businessAssetLinkClosure().fingerprint(),
                serialize(value.businessAssetLinkClosure()));
        if (value.snapshot() != null) {
            insertFact("business_mirror_package_snapshots", coordinate,
                    value.snapshot().fingerprint(), serialize(value.snapshot()));
        }
        jdbc.update("""
                        INSERT INTO business_mirror_package_compilations (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            package_id, compilation_revision, request_fingerprint,
                            source_draft_revision, source_draft_fingerprint, readiness_status,
                            authority_generation, completed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), value.packageId(), value.compilationRevision(),
                value.requestFingerprint(), value.sourceDraftRevision(),
                value.sourceDraftFingerprint(), value.readiness().status().name(),
                value.authorityGeneration(), java.sql.Timestamp.from(value.completedAt()));
    }

    @Override
    public Optional<PackageCompilationReceipt> find(
            CapabilitySnapshot.Scope scope, String packageId, long compilationRevision) {
        if (compilationRevision < 1) {
            return Optional.empty();
        }
        CapabilitySnapshot.Scope exact = java.util.Objects.requireNonNull(scope, "scope");
        String id = required(packageId, "packageId");
        return jdbc.query("""
                        SELECT c.request_fingerprint, c.source_draft_revision,
                               c.source_draft_fingerprint, c.readiness_status,
                               c.authority_generation, c.completed_at,
                               r.fact_fingerprint AS readiness_fingerprint,
                               r.fact_json AS readiness_json,
                               l.fact_fingerprint AS closure_fingerprint,
                               l.fact_json AS closure_json,
                               s.fact_fingerprint AS snapshot_fingerprint,
                               s.fact_json AS snapshot_json
                        FROM business_mirror_package_compilations c
                        JOIN business_mirror_package_readiness_reports r
                          USING (tenant_id, organization_id, project_id, environment_id,
                                 region_id, package_id, compilation_revision)
                        JOIN business_mirror_package_asset_link_closures l
                          USING (tenant_id, organization_id, project_id, environment_id,
                                 region_id, package_id, compilation_revision)
                        LEFT JOIN business_mirror_package_snapshots s
                          USING (tenant_id, organization_id, project_id, environment_id,
                                 region_id, package_id, compilation_revision)
                        WHERE c.tenant_id = ? AND c.organization_id = ? AND c.project_id = ?
                          AND c.environment_id = ? AND c.region_id = ?
                          AND c.package_id = ? AND c.compilation_revision = ?
                        """,
                (rs, row) -> read(exact, id, compilationRevision,
                        rs.getString("request_fingerprint"),
                        rs.getLong("source_draft_revision"),
                        rs.getString("source_draft_fingerprint"),
                        rs.getString("readiness_status"),
                        rs.getString("authority_generation"),
                        rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("readiness_fingerprint"), rs.getString("readiness_json"),
                        rs.getString("closure_fingerprint"), rs.getString("closure_json"),
                        rs.getString("snapshot_fingerprint"), rs.getString("snapshot_json")),
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), id, compilationRevision).stream().flatMap(Optional::stream).findFirst();
    }

    private Optional<PackageCompilationReceipt> read(
            CapabilitySnapshot.Scope scope,
            String packageId,
            long revision,
            String requestFingerprint,
            long sourceDraftRevision,
            String sourceDraftFingerprint,
            String readinessStatus,
            String authorityGeneration,
            java.time.Instant completedAt,
            String readinessFingerprint,
            String readinessJson,
            String closureFingerprint,
            String closureJson,
            String snapshotFingerprint,
            String snapshotJson) {
        try {
            PackageReadinessReport readiness = mapper.readValue(
                    readinessJson, PackageReadinessReport.class);
            BusinessAssetLinkClosure closure = mapper.readValue(
                    closureJson, BusinessAssetLinkClosure.class);
            DomainCapabilityPackageSnapshot snapshot = snapshotJson == null ? null
                    : mapper.readValue(snapshotJson, DomainCapabilityPackageSnapshot.class);
            PackageCompilationReceipt receipt = new PackageCompilationReceipt("", requestFingerprint,
                    packageId, sourceDraftRevision, sourceDraftFingerprint, revision, readiness,
                    closure, snapshot, authorityGeneration, completedAt);
            if (!readiness.fingerprint().equals(readinessFingerprint)
                    || !closure.fingerprint().equals(closureFingerprint)
                    || snapshot != null && !snapshot.fingerprint().equals(snapshotFingerprint)
                    || !readiness.status().name().equals(readinessStatus)
                    || !scope.equals(readiness.scope())) {
                throw new IllegalStateException("Stored Package compilation fact index drifted");
            }
            readiness.verify(mapper);
            closure.verify(mapper);
            if (snapshot != null) {
                snapshot.verify(mapper);
            }
            return Optional.of(receipt);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Package compilation facts", failure);
        }
    }

    private void insertFact(String table, Object[] coordinate, String fingerprint, String json) {
        String sql = "INSERT INTO " + table + " (" + COORDINATE_KEY
                + ", fact_fingerprint, fact_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        Object[] arguments = java.util.Arrays.copyOf(coordinate, 9);
        arguments[7] = fingerprint;
        arguments[8] = json;
        jdbc.update(sql, arguments);
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Package compilation fact", failure);
        }
    }

    private static Object[] coordinate(
            CapabilitySnapshot.Scope scope, String packageId, long revision) {
        return new Object[]{scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), packageId, revision};
    }

    private static String factTable(String suffix) {
        return """
                CREATE TABLE IF NOT EXISTS business_mirror_package_%s (
                    %s
                    package_id VARCHAR(512) NOT NULL,
                    compilation_revision BIGINT NOT NULL,
                    fact_fingerprint VARCHAR(80) NOT NULL,
                    fact_json TEXT NOT NULL,
                    PRIMARY KEY (%s)
                )
                """.formatted(suffix, SCOPE_COLUMNS, COORDINATE_KEY);
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return exact;
    }

    private static Dialect detectDialect(JdbcTemplate jdbc) {
        if (jdbc.getDataSource() == null) {
            throw new IllegalStateException("Package compilation repository requires a JDBC DataSource");
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
                INSERT INTO business_mirror_package_compilation_locks (
                    tenant_id, organization_id, project_id, environment_id, region_id, package_id
                ) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, """
                INSERT INTO business_mirror_package_compilation_heads (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    package_id, next_revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """),
        H2("""
                MERGE INTO business_mirror_package_compilation_locks (
                    tenant_id, organization_id, project_id, environment_id, region_id, package_id
                ) KEY (tenant_id, organization_id, project_id, environment_id, region_id, package_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, """
                MERGE INTO business_mirror_package_compilation_heads AS target
                USING (VALUES (?, ?, ?, ?, ?, ?, ?)) AS incoming (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    package_id, next_revision
                ) ON target.tenant_id = incoming.tenant_id
                   AND target.organization_id = incoming.organization_id
                   AND target.project_id = incoming.project_id
                   AND target.environment_id = incoming.environment_id
                   AND target.region_id = incoming.region_id
                   AND target.package_id = incoming.package_id
                WHEN NOT MATCHED THEN INSERT (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    package_id, next_revision
                ) VALUES (
                    incoming.tenant_id, incoming.organization_id, incoming.project_id,
                    incoming.environment_id, incoming.region_id, incoming.package_id,
                    incoming.next_revision
                )
                """);

        private final String packageLockAdmissionSql;
        private final String headAdmissionSql;

        Dialect(String packageLockAdmissionSql, String headAdmissionSql) {
            this.packageLockAdmissionSql = packageLockAdmissionSql;
            this.headAdmissionSql = headAdmissionSql;
        }
    }
}
