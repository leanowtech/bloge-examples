package com.leanowtech.bloge.gateway.businessmirror.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.domain.BusinessAssetRef;
import com.leanowtech.bloge.gateway.businessmirror.impact.BusinessAssetImpactProjection;
import com.leanowtech.bloge.gateway.businessmirror.impact.BusinessAssetImpactRepository;
import com.leanowtech.bloge.gateway.businessmirror.impact.BusinessAssetSelector;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** H2/PostgreSQL implementation of the append-only Business Asset reverse index. */
public class DatabaseBusinessAssetImpactRepository
        implements BusinessAssetImpactRepository {
    private static final int MAXIMUM_PROJECTION_BYTES = 64 * 1_048_576;
    private static final String SCOPE_COLUMNS = """
            tenant_id VARCHAR(255) NOT NULL,
            organization_id VARCHAR(255) NOT NULL,
            project_id VARCHAR(255) NOT NULL,
            environment_id VARCHAR(255) NOT NULL,
            region_id VARCHAR(128) NOT NULL,
            """;
    private static final String CREATE_LOCKS = """
            CREATE TABLE IF NOT EXISTS business_mirror_asset_impact_locks (
                %s
                package_id VARCHAR(512) NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, package_id)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS business_mirror_asset_impact_heads (
                %s
                package_id VARCHAR(512) NOT NULL,
                compilation_revision BIGINT NOT NULL,
                snapshot_fingerprint VARCHAR(80) NOT NULL,
                closure_id VARCHAR(512) NOT NULL,
                closure_revision BIGINT NOT NULL,
                closure_fingerprint VARCHAR(80) NOT NULL,
                projected_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, package_id)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_PROJECTIONS = """
            CREATE TABLE IF NOT EXISTS business_mirror_asset_impact_projections (
                %s
                package_id VARCHAR(512) NOT NULL,
                compilation_revision BIGINT NOT NULL,
                source_layer VARCHAR(64) NOT NULL,
                source_kind VARCHAR(64) NOT NULL,
                source_id VARCHAR(512) NOT NULL,
                source_authority VARCHAR(512) NOT NULL,
                source_revision BIGINT NOT NULL,
                source_fingerprint VARCHAR(80) NOT NULL,
                source_ref_json TEXT NOT NULL,
                paths_json TEXT NOT NULL,
                projection_fingerprint VARCHAR(80) NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, package_id, compilation_revision, source_layer,
                             source_kind, source_id, source_authority, source_revision,
                             source_fingerprint)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_SOURCE_INDEX = """
            CREATE INDEX IF NOT EXISTS business_mirror_asset_impact_source_idx
            ON business_mirror_asset_impact_projections (
                tenant_id, organization_id, project_id, environment_id, region_id,
                source_kind, source_id, source_authority, package_id, compilation_revision
            )
            """;
    private static final String CREATE_OUTBOX = """
            CREATE TABLE IF NOT EXISTS business_mirror_asset_impact_outbox (
                %s
                package_id VARCHAR(512) NOT NULL,
                compilation_revision BIGINT NOT NULL,
                snapshot_fingerprint VARCHAR(80) NOT NULL,
                status VARCHAR(32) NOT NULL,
                lease_owner VARCHAR(512) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                lease_expires_at TIMESTAMP WITH TIME ZONE,
                attempt_count INTEGER NOT NULL,
                last_failure_code VARCHAR(256) NOT NULL,
                available_at TIMESTAMP WITH TIME ZONE NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, package_id, compilation_revision)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_OUTBOX_INDEX = """
            CREATE INDEX IF NOT EXISTS business_mirror_asset_impact_outbox_ready_idx
            ON business_mirror_asset_impact_outbox (status, available_at, created_at)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Dialect dialect;

    public DatabaseBusinessAssetImpactRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper");
        this.dialect = detectDialect(jdbc);
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_LOCKS);
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_PROJECTIONS);
        jdbc.execute(CREATE_SOURCE_INDEX);
        jdbc.execute(CREATE_OUTBOX);
        jdbc.execute(CREATE_OUTBOX_INDEX);
    }

    @Override
    @Transactional
    public boolean enqueue(
            CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt) {
        CapabilitySnapshot.Scope exact = java.util.Objects.requireNonNull(scope, "scope");
        PackageCompilationReceipt value = java.util.Objects.requireNonNull(receipt, "receipt");
        if (value.snapshot() == null || !exact.equals(value.snapshot().scope())) {
            throw new IllegalArgumentException(
                    "Only an exact-scope Package Snapshot can enter the impact outbox");
        }
        value.snapshot().verify(mapper);
        lock(exact, value.packageId());
        JobState existing = findJob(exact, value.packageId(), value.compilationRevision(), true);
        if (existing != null) {
            requireSameJob(existing, value);
            return false;
        }
        Instant now = databaseNow();
        int inserted = jdbc.update(dialect.outboxAdmissionSql,
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), value.packageId(), value.compilationRevision(),
                value.snapshot().fingerprint(), ProjectionJobStatus.PENDING.name(), "", 0L,
                null, 0, "", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        JobState admitted = findJob(exact, value.packageId(), value.compilationRevision(), true);
        if (admitted == null) {
            throw new IllegalStateException("Business asset impact outbox admission disappeared");
        }
        requireSameJob(admitted, value);
        return inserted == 1;
    }

    @Override
    @Transactional
    public Optional<ProjectionLease> claim(String leaseOwner, Duration leaseDuration) {
        String owner = required(leaseOwner, "leaseOwner", 512);
        Duration duration = positive(leaseDuration);
        Instant now = databaseNow();
        List<JobState> candidates = jdbc.query("""
                        SELECT tenant_id, organization_id, project_id, environment_id, region_id,
                               package_id, compilation_revision, snapshot_fingerprint, status,
                               lease_owner, lease_epoch, lease_expires_at, attempt_count,
                               last_failure_code, available_at, created_at, updated_at
                        FROM business_mirror_asset_impact_outbox
                        WHERE available_at <= ? AND (
                            status = 'PENDING'
                            OR status = 'PROJECTING' AND lease_expires_at <= ?
                        )
                        ORDER BY available_at ASC, created_at ASC, package_id ASC
                        LIMIT 1 FOR UPDATE
                        """, (rs, row) -> jobState(rs), Timestamp.from(now), Timestamp.from(now));
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        JobState current = candidates.getFirst();
        long epoch = Math.addExact(current.leaseEpoch(), 1);
        int attempt = Math.addExact(current.attemptCount(), 1);
        Instant expiresAt = now.plus(duration);
        int updated = jdbc.update("""
                        UPDATE business_mirror_asset_impact_outbox
                        SET status = 'PROJECTING', lease_owner = ?, lease_epoch = ?,
                            lease_expires_at = ?, attempt_count = ?, updated_at = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND compilation_revision = ? AND lease_epoch = ?
                        """, owner, epoch, Timestamp.from(expiresAt), attempt, Timestamp.from(now),
                current.scope().tenantId(), current.scope().organizationId(),
                current.scope().projectId(), current.scope().environmentId(),
                current.scope().region(), current.packageId(), current.compilationRevision(),
                current.leaseEpoch());
        if (updated != 1) {
            throw new IllegalStateException("Business asset impact outbox lease lost its fence");
        }
        return Optional.of(new ProjectionLease(current.scope(), current.packageId(),
                current.compilationRevision(), current.snapshotFingerprint(), owner, epoch,
                attempt, expiresAt));
    }

    @Override
    @Transactional
    public boolean complete(ProjectionLease lease) {
        ProjectionLease exact = java.util.Objects.requireNonNull(lease, "lease");
        JobState current = findJob(
                exact.scope(), exact.packageId(), exact.compilationRevision(), true);
        Instant now = databaseNow();
        if (!owns(current, exact) || !now.isBefore(current.leaseExpiresAt())) {
            return false;
        }
        CapabilitySnapshot.Scope scope = exact.scope();
        return jdbc.update("""
                        UPDATE business_mirror_asset_impact_outbox
                        SET status = 'COMPLETED', lease_owner = '', lease_expires_at = NULL,
                            last_failure_code = '', updated_at = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND compilation_revision = ? AND status = 'PROJECTING'
                          AND lease_owner = ? AND lease_epoch = ?
                        """, Timestamp.from(now), scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(), exact.packageId(),
                exact.compilationRevision(), exact.leaseOwner(), exact.leaseEpoch()) == 1;
    }

    @Override
    @Transactional
    public ProjectionRelease release(
            ProjectionLease lease, String failureCode, int maximumAttempts) {
        ProjectionLease exact = java.util.Objects.requireNonNull(lease, "lease");
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            throw new IllegalArgumentException("maximumAttempts must be between 1 and 100");
        }
        JobState current = findJob(
                exact.scope(), exact.packageId(), exact.compilationRevision(), true);
        Instant now = databaseNow();
        if (!owns(current, exact)) {
            throw new IllegalStateException("Business asset impact outbox release lost its lease");
        }
        ProjectionJobStatus status = current.attemptCount() >= maximumAttempts
                ? ProjectionJobStatus.QUARANTINED : ProjectionJobStatus.PENDING;
        long delaySeconds = status == ProjectionJobStatus.QUARANTINED ? 0
                : Math.min(300L, 1L << Math.min(20, current.attemptCount() - 1));
        Instant availableAt = now.plusSeconds(delaySeconds);
        CapabilitySnapshot.Scope scope = exact.scope();
        int updated = jdbc.update("""
                        UPDATE business_mirror_asset_impact_outbox
                        SET status = ?, lease_owner = '', lease_expires_at = NULL,
                            last_failure_code = ?, available_at = ?, updated_at = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND compilation_revision = ? AND status = 'PROJECTING'
                          AND lease_owner = ? AND lease_epoch = ?
                        """, status.name(), required(failureCode, "failureCode", 256),
                Timestamp.from(availableAt), Timestamp.from(now), scope.tenantId(),
                scope.organizationId(), scope.projectId(), scope.environmentId(), scope.region(),
                exact.packageId(), exact.compilationRevision(), exact.leaseOwner(),
                exact.leaseEpoch());
        if (updated != 1) {
            throw new IllegalStateException("Business asset impact outbox release lost its fence");
        }
        return new ProjectionRelease(status, current.attemptCount(),
                status == ProjectionJobStatus.QUARANTINED ? null : availableAt);
    }

    @Override
    public ProjectionResult project(
            CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt) {
        CapabilitySnapshot.Scope exact = java.util.Objects.requireNonNull(scope, "scope");
        PackageCompilationReceipt value = java.util.Objects.requireNonNull(receipt, "receipt");
        if (value.snapshot() == null || !exact.equals(value.snapshot().scope())
                || !exact.equals(value.businessAssetLinkClosure().scope())) {
            throw new IllegalArgumentException(
                    "Only an exact-scope compiled Package Snapshot can be impact-projected");
        }
        value.snapshot().verify(mapper);
        value.businessAssetLinkClosure().verify(mapper);
        List<BusinessAssetImpactProjection.SourceImpact> projections =
                BusinessAssetImpactProjection.compile(value.businessAssetLinkClosure());
        MirrorArtifactRef closureRef = value.businessAssetLinkClosure().artifactRef();

        lock(exact, value.packageId());
        Head current = findHead(exact, value.packageId());
        if (current != null) {
            if (current.compilationRevision() > value.compilationRevision()) {
                throw new StaleProjectionException();
            }
            if (current.compilationRevision() == value.compilationRevision()) {
                if (!current.snapshotFingerprint().equals(value.snapshot().fingerprint())
                        || !current.closureFingerprint().equals(closureRef.fingerprint())) {
                    throw new ProjectionDriftException();
                }
                Counts counts = counts(exact, value.packageId(), value.compilationRevision());
                return result(value, closureRef, counts, current.projectedAt(), true);
            }
        }

        for (BusinessAssetImpactProjection.SourceImpact projection : projections) {
            insertProjection(exact, value, projection);
        }
        Instant projectedAt = databaseNow();
        if (current == null) {
            jdbc.update("""
                            INSERT INTO business_mirror_asset_impact_heads (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                package_id, compilation_revision, snapshot_fingerprint,
                                closure_id, closure_revision, closure_fingerprint, projected_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                    exact.region(), value.packageId(), value.compilationRevision(),
                    value.snapshot().fingerprint(), closureRef.id(), closureRef.revision(),
                    closureRef.fingerprint(), Timestamp.from(projectedAt));
        } else {
            int updated = jdbc.update("""
                            UPDATE business_mirror_asset_impact_heads
                            SET compilation_revision = ?, snapshot_fingerprint = ?, closure_id = ?,
                                closure_revision = ?, closure_fingerprint = ?, projected_at = ?
                            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                              AND environment_id = ? AND region_id = ? AND package_id = ?
                              AND compilation_revision = ? AND snapshot_fingerprint = ?
                            """,
                    value.compilationRevision(), value.snapshot().fingerprint(), closureRef.id(),
                    closureRef.revision(), closureRef.fingerprint(), Timestamp.from(projectedAt),
                    exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                    exact.region(), value.packageId(), current.compilationRevision(),
                    current.snapshotFingerprint());
            if (updated != 1) {
                throw new IllegalStateException("Business asset impact head lost its database fence");
            }
        }
        Counts counts = new Counts(projections.size(), projections.stream()
                .mapToInt(valueProjection -> valueProjection.paths().size()).sum());
        return result(value, closureRef, counts, projectedAt, false);
    }

    @Override
    public ImpactQuery query(
            CapabilitySnapshot.Scope scope,
            BusinessAssetSelector selector,
            String afterPackageId,
            int limit) {
        CapabilitySnapshot.Scope exact = java.util.Objects.requireNonNull(scope, "scope");
        BusinessAssetSelector target = java.util.Objects.requireNonNull(selector, "selector");
        String after = normalized(afterPackageId);
        int boundedLimit = Math.max(1, Math.min(200, limit));
        List<String> packageIds = matchingPackageIds(exact, target, after, boundedLimit + 1);
        boolean more = packageIds.size() > boundedLimit;
        if (more) {
            packageIds = List.copyOf(packageIds.subList(0, boundedLimit));
        }
        List<StoredPackageImpact> items = new ArrayList<>();
        for (String packageId : packageIds) {
            items.add(readPackage(exact, target, packageId));
        }
        List<SnapshotCoordinate> stale = staleSnapshots(exact, "", 201);
        boolean staleTruncated = stale.size() > 200;
        List<String> staleIds = stale.stream().limit(200)
                .map(SnapshotCoordinate::packageId).toList();
        return new ImpactQuery(items, more ? packageIds.getLast() : "", staleIds,
                staleTruncated, projectedThrough(exact));
    }

    @Override
    public List<SnapshotCoordinate> staleSnapshots(
            CapabilitySnapshot.Scope scope, String afterPackageId, int limit) {
        CapabilitySnapshot.Scope exact = java.util.Objects.requireNonNull(scope, "scope");
        return jdbc.query("""
                        SELECT latest.package_id, latest.compilation_revision
                        FROM (
                            SELECT package_id, MAX(compilation_revision) AS compilation_revision
                            FROM business_mirror_package_snapshots
                            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                              AND environment_id = ? AND region_id = ? AND package_id > ?
                            GROUP BY package_id
                        ) latest
                        JOIN business_mirror_package_snapshots snapshot
                          ON snapshot.tenant_id = ? AND snapshot.organization_id = ?
                         AND snapshot.project_id = ? AND snapshot.environment_id = ?
                         AND snapshot.region_id = ? AND snapshot.package_id = latest.package_id
                         AND snapshot.compilation_revision = latest.compilation_revision
                        LEFT JOIN business_mirror_asset_impact_heads head
                          ON head.tenant_id = ? AND head.organization_id = ?
                         AND head.project_id = ? AND head.environment_id = ?
                         AND head.region_id = ? AND head.package_id = latest.package_id
                        WHERE head.package_id IS NULL
                           OR head.compilation_revision <> latest.compilation_revision
                           OR head.snapshot_fingerprint <> snapshot.fact_fingerprint
                        ORDER BY latest.package_id ASC
                        LIMIT ?
                        """,
                (rs, row) -> new SnapshotCoordinate(
                        rs.getString("package_id"), rs.getLong("compilation_revision")),
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), normalized(afterPackageId),
                exact.tenantId(), exact.organizationId(), exact.projectId(), exact.environmentId(),
                exact.region(), exact.tenantId(), exact.organizationId(), exact.projectId(),
                exact.environmentId(), exact.region(), Math.max(1, Math.min(1000, limit)));
    }

    private void lock(CapabilitySnapshot.Scope scope, String packageId) {
        jdbc.update(dialect.lockAdmissionSql, scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(), packageId);
        jdbc.queryForObject("""
                        SELECT package_id FROM business_mirror_asset_impact_locks
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                        FOR UPDATE
                        """,
                String.class, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), packageId);
    }

    private Head findHead(CapabilitySnapshot.Scope scope, String packageId) {
        return jdbc.query("""
                        SELECT compilation_revision, snapshot_fingerprint, closure_id,
                               closure_revision, closure_fingerprint, projected_at
                        FROM business_mirror_asset_impact_heads
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                        """,
                (rs, row) -> new Head(rs.getLong("compilation_revision"),
                        rs.getString("snapshot_fingerprint"), rs.getString("closure_id"),
                        rs.getLong("closure_revision"), rs.getString("closure_fingerprint"),
                        rs.getTimestamp("projected_at").toInstant()),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), packageId).stream().findFirst().orElse(null);
    }

    private JobState findJob(
            CapabilitySnapshot.Scope scope,
            String packageId,
            long compilationRevision,
            boolean forUpdate) {
        String lockClause = forUpdate ? " FOR UPDATE" : "";
        return jdbc.query("""
                        SELECT tenant_id, organization_id, project_id, environment_id, region_id,
                               package_id, compilation_revision, snapshot_fingerprint, status,
                               lease_owner, lease_epoch, lease_expires_at, attempt_count,
                               last_failure_code, available_at, created_at, updated_at
                        FROM business_mirror_asset_impact_outbox
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND compilation_revision = ?
                        """ + lockClause,
                (rs, row) -> jobState(rs), scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(), packageId,
                compilationRevision).stream().findFirst().orElse(null);
    }

    private static JobState jobState(java.sql.ResultSet rs) throws SQLException {
        Timestamp leaseExpiresAt = rs.getTimestamp("lease_expires_at");
        return new JobState(new CapabilitySnapshot.Scope(rs.getString("tenant_id"),
                rs.getString("organization_id"), rs.getString("project_id"),
                rs.getString("environment_id"), rs.getString("region_id")),
                rs.getString("package_id"), rs.getLong("compilation_revision"),
                rs.getString("snapshot_fingerprint"),
                ProjectionJobStatus.valueOf(rs.getString("status")),
                rs.getString("lease_owner"), rs.getLong("lease_epoch"),
                leaseExpiresAt == null ? null : leaseExpiresAt.toInstant(),
                rs.getInt("attempt_count"), rs.getString("last_failure_code"),
                rs.getTimestamp("available_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static void requireSameJob(
            JobState existing, PackageCompilationReceipt receipt) {
        if (!existing.packageId().equals(receipt.packageId())
                || existing.compilationRevision() != receipt.compilationRevision()
                || !existing.snapshotFingerprint().equals(receipt.snapshot().fingerprint())) {
            throw new ProjectionDriftException();
        }
    }

    private static boolean owns(JobState state, ProjectionLease lease) {
        return state != null && state.status() == ProjectionJobStatus.PROJECTING
                && state.scope().equals(lease.scope())
                && state.packageId().equals(lease.packageId())
                && state.compilationRevision() == lease.compilationRevision()
                && state.snapshotFingerprint().equals(lease.snapshotFingerprint())
                && state.leaseOwner().equals(lease.leaseOwner())
                && state.leaseEpoch() == lease.leaseEpoch();
    }

    private void insertProjection(
            CapabilitySnapshot.Scope scope,
            PackageCompilationReceipt receipt,
            BusinessAssetImpactProjection.SourceImpact projection) {
        BusinessAssetRef source = projection.sourceRef();
        jdbc.update("""
                        INSERT INTO business_mirror_asset_impact_projections (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            package_id, compilation_revision, source_layer, source_kind,
                            source_id, source_authority, source_revision, source_fingerprint,
                            source_ref_json, paths_json, projection_fingerprint
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), receipt.packageId(), receipt.compilationRevision(),
                source.layer().name(), source.kind().name(), source.id(), source.authority(),
                source.revision(), source.fingerprint(), serialize(source),
                serialize(projection.paths()), ProtocolFingerprint.ofBounded(
                        mapper, projection, MAXIMUM_PROJECTION_BYTES));
    }

    private List<String> matchingPackageIds(
            CapabilitySnapshot.Scope scope,
            BusinessAssetSelector selector,
            String after,
            int limit) {
        String authorityClause = selector.authority().isBlank()
                ? "" : " AND projection.source_authority = ?";
        List<Object> arguments = new ArrayList<>(List.of(scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(), selector.kind().name(),
                selector.id()));
        if (!selector.authority().isBlank()) {
            arguments.add(selector.authority());
        }
        arguments.add(after);
        arguments.add(limit);
        return jdbc.query("""
                        SELECT DISTINCT projection.package_id
                        FROM business_mirror_asset_impact_projections projection
                        JOIN business_mirror_asset_impact_heads head
                          ON head.tenant_id = projection.tenant_id
                         AND head.organization_id = projection.organization_id
                         AND head.project_id = projection.project_id
                         AND head.environment_id = projection.environment_id
                         AND head.region_id = projection.region_id
                         AND head.package_id = projection.package_id
                         AND head.compilation_revision = projection.compilation_revision
                        WHERE projection.tenant_id = ? AND projection.organization_id = ?
                          AND projection.project_id = ? AND projection.environment_id = ?
                          AND projection.region_id = ? AND projection.source_kind = ?
                          AND projection.source_id = ? %s
                          AND projection.package_id > ?
                        ORDER BY projection.package_id ASC
                        LIMIT ?
                        """.formatted(authorityClause),
                (rs, row) -> rs.getString("package_id"), arguments.toArray());
    }

    private StoredPackageImpact readPackage(
            CapabilitySnapshot.Scope scope,
            BusinessAssetSelector selector,
            String packageId) {
        Head head = java.util.Objects.requireNonNull(findHead(scope, packageId), "impact head");
        String authorityClause = selector.authority().isBlank()
                ? "" : " AND source_authority = ?";
        List<Object> arguments = new ArrayList<>(List.of(scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(), packageId,
                head.compilationRevision(), selector.kind().name(), selector.id()));
        if (!selector.authority().isBlank()) {
            arguments.add(selector.authority());
        }
        List<BusinessAssetImpactProjection.SourceImpact> matches = jdbc.query("""
                        SELECT source_ref_json, paths_json, projection_fingerprint
                        FROM business_mirror_asset_impact_projections
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND compilation_revision = ? AND source_kind = ? AND source_id = ? %s
                        ORDER BY source_authority, source_revision, source_fingerprint
                        """.formatted(authorityClause),
                (rs, row) -> readProjection(rs.getString("source_ref_json"),
                        rs.getString("paths_json"), rs.getString("projection_fingerprint")),
                arguments.toArray());
        if (matches.isEmpty()) {
            throw new IllegalStateException("Business asset impact index changed during query");
        }
        return new StoredPackageImpact(packageId, head.compilationRevision(),
                new MirrorArtifactRef("DOMAIN_CAPABILITY_PACKAGE", packageId,
                        head.compilationRevision(), head.snapshotFingerprint()),
                new MirrorArtifactRef("BUSINESS_ASSET_LINK_CLOSURE", head.closureId(),
                        head.closureRevision(), head.closureFingerprint()), matches);
    }

    private BusinessAssetImpactProjection.SourceImpact readProjection(
            String sourceJson, String pathsJson, String storedFingerprint) {
        try {
            BusinessAssetRef source = mapper.readValue(sourceJson, BusinessAssetRef.class);
            List<BusinessAssetImpactProjection.ImpactPath> paths = mapper.readValue(pathsJson,
                    new TypeReference<>() { });
            BusinessAssetImpactProjection.SourceImpact projection =
                    new BusinessAssetImpactProjection.SourceImpact(source, paths);
            String fingerprint = ProtocolFingerprint.ofBounded(
                    mapper, projection, MAXIMUM_PROJECTION_BYTES);
            if (!fingerprint.equals(storedFingerprint)) {
                throw new IllegalStateException("Stored business asset impact projection drifted");
            }
            return projection;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode business asset impact projection", failure);
        }
    }

    private Counts counts(
            CapabilitySnapshot.Scope scope, String packageId, long compilationRevision) {
        List<Integer> pathCounts = jdbc.query("""
                        SELECT paths_json FROM business_mirror_asset_impact_projections
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND compilation_revision = ?
                        """,
                (rs, row) -> {
                    try {
                        return mapper.readTree(rs.getString("paths_json")).size();
                    } catch (JsonProcessingException failure) {
                        throw new IllegalStateException(
                                "Failed to decode business asset impact path count", failure);
                    }
                }, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), packageId, compilationRevision);
        return new Counts(pathCounts.size(), pathCounts.stream().mapToInt(Integer::intValue).sum());
    }

    private Instant projectedThrough(CapabilitySnapshot.Scope scope) {
        Timestamp value = jdbc.queryForObject("""
                        SELECT MAX(projected_at)
                        FROM business_mirror_asset_impact_heads
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                        """,
                Timestamp.class, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region());
        return value == null ? null : value.toInstant();
    }

    private ProjectionResult result(
            PackageCompilationReceipt receipt,
            MirrorArtifactRef closureRef,
            Counts counts,
            Instant projectedAt,
            boolean replayed) {
        return new ProjectionResult(receipt.packageId(), receipt.compilationRevision(),
                receipt.snapshot().fingerprint(), closureRef.fingerprint(), counts.sources(),
                counts.paths(), projectedAt, replayed);
    }

    private Instant databaseNow() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Database did not provide current time");
        }
        return value.toInstant();
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode business asset impact projection", failure);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String required(String value, String name, int maximumLength) {
        String exact = normalized(value);
        if (exact.isBlank() || exact.length() > maximumLength) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return exact;
    }

    private static Duration positive(Duration value) {
        Duration exact = java.util.Objects.requireNonNull(value, "leaseDuration");
        if (exact.isZero() || exact.isNegative() || exact.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "leaseDuration must be positive and no greater than one hour");
        }
        return exact;
    }

    private static Dialect detectDialect(JdbcTemplate jdbc) {
        if (jdbc.getDataSource() == null) {
            throw new IllegalStateException("Business asset impact repository requires a DataSource");
        }
        try (var connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product != null && product.toLowerCase(Locale.ROOT).contains("postgresql")) {
                return Dialect.POSTGRESQL;
            }
            if (product != null && product.toLowerCase(Locale.ROOT).contains("h2")) {
                return Dialect.H2;
            }
            throw new IllegalStateException("Unsupported business asset impact database: " + product);
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to inspect business asset impact database", failure);
        }
    }

    private enum Dialect {
        POSTGRESQL("""
                INSERT INTO business_mirror_asset_impact_locks (
                    tenant_id, organization_id, project_id, environment_id, region_id, package_id
                ) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, """
                INSERT INTO business_mirror_asset_impact_outbox (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    package_id, compilation_revision, snapshot_fingerprint, status,
                    lease_owner, lease_epoch, lease_expires_at, attempt_count,
                    last_failure_code, available_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """),
        H2("""
                MERGE INTO business_mirror_asset_impact_locks (
                    tenant_id, organization_id, project_id, environment_id, region_id, package_id
                ) KEY (tenant_id, organization_id, project_id, environment_id, region_id, package_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, """
                MERGE INTO business_mirror_asset_impact_outbox (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    package_id, compilation_revision, snapshot_fingerprint, status,
                    lease_owner, lease_epoch, lease_expires_at, attempt_count,
                    last_failure_code, available_at, created_at, updated_at
                ) KEY (tenant_id, organization_id, project_id, environment_id, region_id,
                       package_id, compilation_revision)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);

        private final String lockAdmissionSql;
        private final String outboxAdmissionSql;

        Dialect(String lockAdmissionSql, String outboxAdmissionSql) {
            this.lockAdmissionSql = lockAdmissionSql;
            this.outboxAdmissionSql = outboxAdmissionSql;
        }
    }

    private record Head(
            long compilationRevision,
            String snapshotFingerprint,
            String closureId,
            long closureRevision,
            String closureFingerprint,
            Instant projectedAt) {
    }

    private record Counts(int sources, int paths) {
    }

    private record JobState(
            CapabilitySnapshot.Scope scope,
            String packageId,
            long compilationRevision,
            String snapshotFingerprint,
            ProjectionJobStatus status,
            String leaseOwner,
            long leaseEpoch,
            Instant leaseExpiresAt,
            int attemptCount,
            String lastFailureCode,
            Instant availableAt,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** A newer Package projection already owns the current head. */
    public static final class StaleProjectionException extends IllegalStateException {
        public StaleProjectionException() {
            super("A newer business asset impact projection is already current");
        }
    }

    /** The same Package compilation coordinate was reused for different immutable facts. */
    public static final class ProjectionDriftException extends IllegalStateException {
        public ProjectionDriftException() {
            super("Business asset impact projection coordinate drifted");
        }
    }
}
