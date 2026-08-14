package com.leanowtech.bloge.gateway.businessmirror.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.compilation.PackageCompilationReceipt;
import com.leanowtech.bloge.gateway.businessmirror.evidence.EvidenceOwnerTask;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceIndex;
import com.leanowtech.bloge.gateway.businessmirror.evidence.PackageEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** H2/PostgreSQL append-only Package evidence projection and owner-task journal. */
public class DatabasePackageEvidenceRepository implements PackageEvidenceRepository {
    private static final String SCOPE_COLUMNS = """
            tenant_id VARCHAR(255) NOT NULL,
            organization_id VARCHAR(255) NOT NULL,
            project_id VARCHAR(255) NOT NULL,
            environment_id VARCHAR(255) NOT NULL,
            region_id VARCHAR(128) NOT NULL,
            """;
    private static final String CREATE_LOCKS = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_evidence_locks (
                %s package_id VARCHAR(512) NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, package_id)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_SEQUENCES = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_evidence_sequences (
                %s package_id VARCHAR(512) NOT NULL, next_revision BIGINT NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, package_id)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_INDEXES = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_evidence_indexes (
                %s package_id VARCHAR(512) NOT NULL,
                compilation_revision BIGINT NOT NULL,
                projection_revision BIGINT NOT NULL,
                index_fingerprint VARCHAR(80) NOT NULL,
                snapshot_fingerprint VARCHAR(80) NOT NULL,
                domain_id VARCHAR(512) NOT NULL,
                valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
                projected_at TIMESTAMP WITH TIME ZONE NOT NULL,
                index_json TEXT NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, package_id, projection_revision),
                UNIQUE (tenant_id, organization_id, project_id, environment_id,
                        region_id, index_fingerprint)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_evidence_heads (
                %s package_id VARCHAR(512) NOT NULL,
                compilation_revision BIGINT NOT NULL,
                projection_revision BIGINT NOT NULL,
                index_fingerprint VARCHAR(80) NOT NULL,
                snapshot_fingerprint VARCHAR(80) NOT NULL,
                domain_id VARCHAR(512) NOT NULL,
                valid_until TIMESTAMP WITH TIME ZONE NOT NULL,
                projected_at TIMESTAMP WITH TIME ZONE NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, package_id)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_DOMAIN_INDEX = """
            CREATE INDEX IF NOT EXISTS business_mirror_package_evidence_domain_idx
            ON business_mirror_package_evidence_heads (
                tenant_id, organization_id, project_id, environment_id, region_id,
                domain_id, package_id)
            """;
    private static final String CREATE_OUTBOX = """
            CREATE TABLE IF NOT EXISTS business_mirror_package_evidence_outbox (
                %s package_id VARCHAR(512) NOT NULL,
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
            CREATE INDEX IF NOT EXISTS business_mirror_package_evidence_outbox_ready_idx
            ON business_mirror_package_evidence_outbox (status, available_at, created_at)
            """;
    private static final String CREATE_TASKS = """
            CREATE TABLE IF NOT EXISTS business_mirror_evidence_owner_tasks (
                %s task_id VARCHAR(1024) NOT NULL,
                task_version BIGINT NOT NULL,
                task_fingerprint VARCHAR(80) NOT NULL,
                package_id VARCHAR(512) NOT NULL,
                compilation_revision BIGINT NOT NULL,
                projection_revision BIGINT NOT NULL,
                domain_id VARCHAR(512) NOT NULL,
                reason VARCHAR(96) NOT NULL,
                status VARCHAR(32) NOT NULL,
                owner_id VARCHAR(1024) NOT NULL,
                due_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                task_json TEXT NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, task_id)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_TASK_EVENTS = """
            CREATE TABLE IF NOT EXISTS business_mirror_evidence_owner_task_events (
                %s task_id VARCHAR(1024) NOT NULL,
                task_version BIGINT NOT NULL,
                task_fingerprint VARCHAR(80) NOT NULL,
                status VARCHAR(32) NOT NULL,
                occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                task_json TEXT NOT NULL,
                PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                             region_id, task_id, task_version)
            )
            """.formatted(SCOPE_COLUMNS);
    private static final String CREATE_TASK_INDEX = """
            CREATE INDEX IF NOT EXISTS business_mirror_evidence_owner_task_domain_idx
            ON business_mirror_evidence_owner_tasks (
                tenant_id, organization_id, project_id, environment_id, region_id,
                domain_id, package_id, status, due_at)
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Dialect dialect;

    public DatabasePackageEvidenceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.dialect = detectDialect(jdbc);
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_LOCKS);
        jdbc.execute(CREATE_SEQUENCES);
        jdbc.execute(CREATE_INDEXES);
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_DOMAIN_INDEX);
        jdbc.execute(CREATE_OUTBOX);
        jdbc.execute(CREATE_OUTBOX_INDEX);
        jdbc.execute(CREATE_TASKS);
        jdbc.execute(CREATE_TASK_EVENTS);
        jdbc.execute(CREATE_TASK_INDEX);
    }

    @Override
    @Transactional
    public boolean enqueue(CapabilitySnapshot.Scope scope, PackageCompilationReceipt receipt) {
        CapabilitySnapshot.Scope exact = Objects.requireNonNull(scope, "scope");
        PackageCompilationReceipt value = Objects.requireNonNull(receipt, "receipt");
        if (value.snapshot() == null || !exact.equals(value.snapshot().scope())) {
            throw new IllegalArgumentException(
                    "Only an exact-scope Package Snapshot can enter the evidence outbox");
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
            throw new IllegalStateException("Package evidence outbox admission disappeared");
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
                        FROM business_mirror_package_evidence_outbox
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
                        UPDATE business_mirror_package_evidence_outbox
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
            throw new IllegalStateException("Package evidence lease lost its fence");
        }
        return Optional.of(new ProjectionLease(current.scope(), current.packageId(),
                current.compilationRevision(), current.snapshotFingerprint(), owner, epoch,
                attempt, expiresAt));
    }

    @Override
    @Transactional
    public ProjectionRelease release(
            ProjectionLease lease, String failureCode, int maximumAttempts) {
        ProjectionLease exact = Objects.requireNonNull(lease, "lease");
        if (maximumAttempts < 1 || maximumAttempts > 100) {
            throw new IllegalArgumentException("maximumAttempts must be between 1 and 100");
        }
        JobState current = findJob(
                exact.scope(), exact.packageId(), exact.compilationRevision(), true);
        if (!owns(current, exact)) {
            throw new IllegalStateException("Package evidence release lost its lease");
        }
        Instant now = databaseNow();
        ProjectionJobStatus status = current.attemptCount() >= maximumAttempts
                ? ProjectionJobStatus.QUARANTINED : ProjectionJobStatus.PENDING;
        long delaySeconds = status == ProjectionJobStatus.QUARANTINED ? 0
                : Math.min(300L, 1L << Math.min(20, current.attemptCount() - 1));
        Instant availableAt = now.plusSeconds(delaySeconds);
        CapabilitySnapshot.Scope scope = exact.scope();
        int updated = jdbc.update("""
                        UPDATE business_mirror_package_evidence_outbox
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
            throw new IllegalStateException("Package evidence release lost its fence");
        }
        return new ProjectionRelease(status, current.attemptCount(),
                status == ProjectionJobStatus.QUARANTINED ? null : availableAt);
    }

    @Override
    @Transactional
    public boolean complete(ProjectionLease lease) {
        ProjectionLease exact = Objects.requireNonNull(lease, "lease");
        JobState current = findJob(
                exact.scope(), exact.packageId(), exact.compilationRevision(), true);
        Instant now = databaseNow();
        if (!owns(current, exact) || !now.isBefore(current.leaseExpiresAt())) {
            return false;
        }
        CapabilitySnapshot.Scope scope = exact.scope();
        return jdbc.update("""
                        UPDATE business_mirror_package_evidence_outbox
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
    public ProjectionReservation reserveProjectionRevision(
            CapabilitySnapshot.Scope scope, String packageId, long compilationRevision) {
        CapabilitySnapshot.Scope exact = Objects.requireNonNull(scope, "scope");
        String id = required(packageId, "packageId", 512);
        if (compilationRevision < 1) {
            throw new IllegalArgumentException("compilationRevision must be positive");
        }
        lock(exact, id);
        Head head = findHead(exact, id, true);
        if (head != null && head.compilationRevision() > compilationRevision) {
            throw new StaleProjectionException();
        }
        try {
            jdbc.update("""
                            INSERT INTO business_mirror_package_evidence_sequences (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                package_id, next_revision
                            ) VALUES (?, ?, ?, ?, ?, ?, ?)
                            """, exact.tenantId(), exact.organizationId(), exact.projectId(),
                    exact.environmentId(), exact.region(), id, 1L);
        } catch (DuplicateKeyException alreadyAdmitted) {
            // The per-Package database lock makes an existing sequence the only valid conflict.
        }
        Long next = jdbc.queryForObject("""
                        SELECT next_revision FROM business_mirror_package_evidence_sequences
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                        FOR UPDATE
                        """, Long.class, exact.tenantId(), exact.organizationId(),
                exact.projectId(), exact.environmentId(), exact.region(), id);
        if (next == null || next < 1) {
            throw new IllegalStateException("Package evidence projection sequence is corrupt");
        }
        int updated = jdbc.update("""
                        UPDATE business_mirror_package_evidence_sequences SET next_revision = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND next_revision = ?
                        """, next + 1, exact.tenantId(), exact.organizationId(),
                exact.projectId(), exact.environmentId(), exact.region(), id, next);
        if (updated != 1) {
            throw new IllegalStateException("Package evidence projection sequence lost its fence");
        }
        return new ProjectionReservation(next, databaseNow());
    }

    @Override
    @Transactional
    public ProjectionResult append(PackageEvidenceIndex index, String deepLink) {
        PackageEvidenceIndex exact = Objects.requireNonNull(index, "index");
        exact.verify(mapper);
        CapabilitySnapshot.Scope scope = exact.scope();
        lock(scope, exact.packageId());
        Head current = findHead(scope, exact.packageId(), true);
        if (current != null && (current.compilationRevision() > exact.compilationRevision()
                || current.projectionRevision() > exact.projectionRevision())) {
            throw new StaleProjectionException();
        }
        Optional<PackageEvidenceIndex> existing = find(
                scope, exact.packageId(), exact.projectionRevision());
        if (existing.isPresent()) {
            if (!existing.orElseThrow().equals(exact)) {
                throw new ProjectionDriftException();
            }
            return result(exact, 0, true);
        }
        insertIndex(exact);
        if (current == null) {
            insertHead(exact);
        } else {
            updateHead(current, exact);
        }
        int taskCount = reconcileTasks(exact, required(deepLink, "deepLink", 4096));
        return result(exact, taskCount, false);
    }

    @Override
    public Optional<PackageEvidenceIndex> findCurrent(
            CapabilitySnapshot.Scope scope, String packageId) {
        CapabilitySnapshot.Scope exact = Objects.requireNonNull(scope, "scope");
        String id = required(packageId, "packageId", 512);
        return jdbc.query("""
                        SELECT i.index_json, i.index_fingerprint,
                               h.package_id AS stored_package_id,
                               h.compilation_revision AS stored_compilation_revision,
                               h.projection_revision AS stored_projection_revision,
                               h.snapshot_fingerprint AS stored_snapshot_fingerprint,
                               h.domain_id AS stored_domain_id,
                               h.valid_until AS stored_valid_until,
                               h.projected_at AS stored_projected_at
                        FROM business_mirror_package_evidence_heads h
                        JOIN business_mirror_package_evidence_indexes i
                          ON i.tenant_id = h.tenant_id
                         AND i.organization_id = h.organization_id
                         AND i.project_id = h.project_id
                         AND i.environment_id = h.environment_id
                         AND i.region_id = h.region_id
                         AND i.package_id = h.package_id
                         AND i.projection_revision = h.projection_revision
                        WHERE h.tenant_id = ? AND h.organization_id = ? AND h.project_id = ?
                          AND h.environment_id = ? AND h.region_id = ? AND h.package_id = ?
                        """, (rs, row) -> readIndex(rs, exact),
                exact.tenantId(), exact.organizationId(),
                exact.projectId(), exact.environmentId(), exact.region(), id)
                .stream().findFirst();
    }

    @Override
    public Optional<PackageEvidenceIndex> find(
            CapabilitySnapshot.Scope scope, String packageId, long projectionRevision) {
        CapabilitySnapshot.Scope exact = Objects.requireNonNull(scope, "scope");
        if (projectionRevision < 1) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT index_json, index_fingerprint,
                               package_id AS stored_package_id,
                               compilation_revision AS stored_compilation_revision,
                               projection_revision AS stored_projection_revision,
                               snapshot_fingerprint AS stored_snapshot_fingerprint,
                               domain_id AS stored_domain_id,
                               valid_until AS stored_valid_until,
                               projected_at AS stored_projected_at
                        FROM business_mirror_package_evidence_indexes
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND projection_revision = ?
                        """, (rs, row) -> readIndex(rs, exact),
                exact.tenantId(), exact.organizationId(),
                exact.projectId(), exact.environmentId(), exact.region(),
                required(packageId, "packageId", 512), projectionRevision)
                .stream().findFirst();
    }

    @Override
    public Optional<PackageEvidenceIndex> findByCompilation(
            CapabilitySnapshot.Scope scope, String packageId, long compilationRevision) {
        CapabilitySnapshot.Scope exact = Objects.requireNonNull(scope, "scope");
        if (compilationRevision < 1) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT index_json, index_fingerprint,
                               package_id AS stored_package_id,
                               compilation_revision AS stored_compilation_revision,
                               projection_revision AS stored_projection_revision,
                               snapshot_fingerprint AS stored_snapshot_fingerprint,
                               domain_id AS stored_domain_id,
                               valid_until AS stored_valid_until,
                               projected_at AS stored_projected_at
                        FROM business_mirror_package_evidence_indexes
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND compilation_revision = ?
                        ORDER BY projection_revision DESC LIMIT 1
                        """, (rs, row) -> readIndex(rs, exact),
                exact.tenantId(), exact.organizationId(), exact.projectId(),
                exact.environmentId(), exact.region(),
                required(packageId, "packageId", 512), compilationRevision)
                .stream().findFirst();
    }

    @Override
    public CurrentPage findCurrentByDomain(
            CapabilitySnapshot.Scope scope,
            String domainId,
            String afterPackageId,
            int limit) {
        CapabilitySnapshot.Scope exact = Objects.requireNonNull(scope, "scope");
        int bounded = Math.max(1, Math.min(200, limit));
        List<PackageEvidenceIndex> items = jdbc.query("""
                        SELECT i.index_json, i.index_fingerprint,
                               h.package_id AS stored_package_id,
                               h.compilation_revision AS stored_compilation_revision,
                               h.projection_revision AS stored_projection_revision,
                               h.snapshot_fingerprint AS stored_snapshot_fingerprint,
                               h.domain_id AS stored_domain_id,
                               h.valid_until AS stored_valid_until,
                               h.projected_at AS stored_projected_at
                        FROM business_mirror_package_evidence_heads h
                        JOIN business_mirror_package_evidence_indexes i
                          ON i.tenant_id = h.tenant_id
                         AND i.organization_id = h.organization_id
                         AND i.project_id = h.project_id
                         AND i.environment_id = h.environment_id
                         AND i.region_id = h.region_id
                         AND i.package_id = h.package_id
                         AND i.projection_revision = h.projection_revision
                        WHERE h.tenant_id = ? AND h.organization_id = ? AND h.project_id = ?
                          AND h.environment_id = ? AND h.region_id = ? AND h.domain_id = ?
                          AND h.package_id > ?
                        ORDER BY h.package_id ASC LIMIT ?
                        """, (rs, row) -> readIndex(rs, exact),
                exact.tenantId(), exact.organizationId(),
                exact.projectId(), exact.environmentId(), exact.region(),
                required(domainId, "domainId", 512), normalized(afterPackageId), bounded + 1);
        boolean more = items.size() > bounded;
        if (more) {
            items = List.copyOf(items.subList(0, bounded));
        }
        return new CurrentPage(items, more ? items.getLast().packageId() : "");
    }

    @Override
    public List<EvidenceOwnerTask> findTasks(
            CapabilitySnapshot.Scope scope,
            String domainId,
            String packageId,
            EvidenceOwnerTask.Status status,
            int limit) {
        CapabilitySnapshot.Scope exact = Objects.requireNonNull(scope, "scope");
        String domain = normalized(domainId);
        String packageValue = normalized(packageId);
        String statusValue = status == null ? "" : status.name();
        return jdbc.query("""
                        SELECT task_json, task_fingerprint, task_id, task_version, package_id,
                               compilation_revision, projection_revision, domain_id, reason,
                               status, owner_id, due_at, updated_at
                        FROM business_mirror_evidence_owner_tasks
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND (? = '' OR domain_id = ?)
                          AND (? = '' OR package_id = ?)
                          AND (? = '' OR status = ?)
                        ORDER BY due_at ASC, task_id ASC LIMIT ?
                        """, (rs, row) -> readTask(rs, exact),
                exact.tenantId(), exact.organizationId(),
                exact.projectId(), exact.environmentId(), exact.region(), domain, domain,
                packageValue, packageValue, statusValue, statusValue,
                Math.max(1, Math.min(500, limit)));
    }

    @Override
    public Optional<EvidenceOwnerTask> findTask(
            CapabilitySnapshot.Scope scope, String taskId) {
        CapabilitySnapshot.Scope exact = Objects.requireNonNull(scope, "scope");
        return jdbc.query("""
                        SELECT task_json, task_fingerprint, task_id, task_version, package_id,
                               compilation_revision, projection_revision, domain_id, reason,
                               status, owner_id, due_at, updated_at
                        FROM business_mirror_evidence_owner_tasks
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND task_id = ?
                        """, (rs, row) -> readTask(rs, exact),
                exact.tenantId(), exact.organizationId(),
                exact.projectId(), exact.environmentId(), exact.region(),
                required(taskId, "taskId", 1024)).stream().findFirst();
    }

    @Override
    @Transactional
    public EvidenceOwnerTask transitionTask(
            CapabilitySnapshot.Scope scope,
            String taskId,
            long expectedVersion,
            EvidenceOwnerTask.Status target,
            String actor,
            MirrorArtifactRef resolutionEvidenceRef,
            Instant at) {
        CapabilitySnapshot.Scope exact = Objects.requireNonNull(scope, "scope");
        EvidenceOwnerTask current = findTaskForUpdate(exact, taskId);
        if (current == null) {
            throw new TaskNotFoundException();
        }
        if (current.version() != expectedVersion) {
            throw new TaskVersionConflictException();
        }
        EvidenceOwnerTask next = current.transition(target, actor, resolutionEvidenceRef, at, mapper);
        updateTask(current, next);
        insertTaskEvent(next);
        return next;
    }

    private void insertIndex(PackageEvidenceIndex index) {
        jdbc.update("""
                        INSERT INTO business_mirror_package_evidence_indexes (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            package_id, compilation_revision, projection_revision,
                            index_fingerprint, snapshot_fingerprint, domain_id, valid_until,
                            projected_at, index_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, scopeArgs(index.scope(), index.packageId(), index.compilationRevision(),
                index.projectionRevision(), index.indexFingerprint(),
                index.packageSnapshotSource().fingerprint(), index.domainId(),
                Timestamp.from(index.validUntil()), Timestamp.from(index.projectedAt()),
                serialize(index)));
    }

    private void insertHead(PackageEvidenceIndex index) {
        jdbc.update("""
                        INSERT INTO business_mirror_package_evidence_heads (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            package_id, compilation_revision, projection_revision,
                            index_fingerprint, snapshot_fingerprint, domain_id, valid_until,
                            projected_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, scopeArgs(index.scope(), index.packageId(), index.compilationRevision(),
                index.projectionRevision(), index.indexFingerprint(),
                index.packageSnapshotSource().fingerprint(), index.domainId(),
                Timestamp.from(index.validUntil()), Timestamp.from(index.projectedAt())));
    }

    private void updateHead(Head previous, PackageEvidenceIndex index) {
        int updated = jdbc.update("""
                        UPDATE business_mirror_package_evidence_heads
                        SET compilation_revision = ?, projection_revision = ?,
                            index_fingerprint = ?, snapshot_fingerprint = ?, domain_id = ?,
                            valid_until = ?, projected_at = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND projection_revision = ? AND index_fingerprint = ?
                        """, index.compilationRevision(), index.projectionRevision(),
                index.indexFingerprint(), index.packageSnapshotSource().fingerprint(),
                index.domainId(), Timestamp.from(index.validUntil()),
                Timestamp.from(index.projectedAt()), index.scope().tenantId(),
                index.scope().organizationId(), index.scope().projectId(),
                index.scope().environmentId(), index.scope().region(), index.packageId(),
                previous.projectionRevision(), previous.indexFingerprint());
        if (updated != 1) {
            throw new IllegalStateException("Package evidence head lost its database fence");
        }
    }

    private int reconcileTasks(PackageEvidenceIndex index, String deepLink) {
        List<EvidenceOwnerTask> desired = index.driftSignals().stream()
                .map(signal -> EvidenceOwnerTask.open(index, signal, deepLink, mapper)).toList();
        List<EvidenceOwnerTask> current = findActiveTasksForUpdate(
                index.scope(), index.packageId());
        List<String> desiredIds = desired.stream().map(EvidenceOwnerTask::taskId).toList();
        for (EvidenceOwnerTask task : current) {
            if ((task.status() == EvidenceOwnerTask.Status.OPEN
                    || task.status() == EvidenceOwnerTask.Status.ACKNOWLEDGED)
                    && !desiredIds.contains(task.taskId())) {
                EvidenceOwnerTask superseded = task.transition(
                        EvidenceOwnerTask.Status.SUPERSEDED,
                        "resource-gateway-evidence-projector", null,
                        index.projectedAt(), mapper);
                updateTask(task, superseded);
                insertTaskEvent(superseded);
            }
        }
        int created = 0;
        for (EvidenceOwnerTask task : desired) {
            EvidenceOwnerTask existing = findTaskForUpdate(index.scope(), task.taskId());
            if (existing == null) {
                insertTask(task);
                insertTaskEvent(task);
                created++;
            }
        }
        return created;
    }

    private List<EvidenceOwnerTask> findActiveTasksForUpdate(
            CapabilitySnapshot.Scope scope, String packageId) {
        List<EvidenceOwnerTask> tasks = jdbc.query("""
                        SELECT task_json, task_fingerprint, task_id, task_version, package_id,
                               compilation_revision, projection_revision, domain_id, reason,
                               status, owner_id, due_at, updated_at
                        FROM business_mirror_evidence_owner_tasks
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND status IN ('OPEN', 'ACKNOWLEDGED')
                        ORDER BY due_at ASC, task_id ASC
                        LIMIT ? FOR UPDATE
                        """, (rs, row) -> readTask(rs, scope), scope.tenantId(),
                scope.organizationId(), scope.projectId(), scope.environmentId(), scope.region(),
                required(packageId, "packageId", 512),
                PackageEvidenceIndex.MAXIMUM_DRIFT_SIGNALS + 1);
        if (tasks.size() > PackageEvidenceIndex.MAXIMUM_DRIFT_SIGNALS) {
            throw new IllegalStateException(
                    "Package has more active evidence tasks than the protocol permits");
        }
        return tasks;
    }

    private void insertTask(EvidenceOwnerTask task) {
        CapabilitySnapshot.Scope scope = task.scope();
        jdbc.update("""
                        INSERT INTO business_mirror_evidence_owner_tasks (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            task_id, task_version, task_fingerprint, package_id,
                            compilation_revision, projection_revision, domain_id, reason, status,
                            owner_id, due_at, updated_at, task_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), task.taskId(), task.version(),
                task.taskFingerprint(), task.packageId(), task.compilationRevision(),
                task.projectionRevision(), task.domainId(), task.reason().name(),
                task.status().name(), task.owner(), Timestamp.from(task.dueAt()),
                Timestamp.from(task.updatedAt()), serialize(task));
    }

    private void updateTask(EvidenceOwnerTask previous, EvidenceOwnerTask next) {
        CapabilitySnapshot.Scope scope = next.scope();
        int updated = jdbc.update("""
                        UPDATE business_mirror_evidence_owner_tasks
                        SET task_version = ?, task_fingerprint = ?, compilation_revision = ?,
                            projection_revision = ?, domain_id = ?, reason = ?, status = ?,
                            owner_id = ?, due_at = ?, updated_at = ?, task_json = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND task_id = ?
                          AND task_version = ? AND task_fingerprint = ?
                        """, next.version(), next.taskFingerprint(), next.compilationRevision(),
                next.projectionRevision(), next.domainId(), next.reason().name(),
                next.status().name(), next.owner(), Timestamp.from(next.dueAt()),
                Timestamp.from(next.updatedAt()), serialize(next), scope.tenantId(),
                scope.organizationId(), scope.projectId(), scope.environmentId(), scope.region(),
                next.taskId(), previous.version(), previous.taskFingerprint());
        if (updated != 1) {
            throw new TaskVersionConflictException();
        }
    }

    private void insertTaskEvent(EvidenceOwnerTask task) {
        CapabilitySnapshot.Scope scope = task.scope();
        jdbc.update("""
                        INSERT INTO business_mirror_evidence_owner_task_events (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            task_id, task_version, task_fingerprint, status, occurred_at, task_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), task.taskId(), task.version(),
                task.taskFingerprint(), task.status().name(), Timestamp.from(task.updatedAt()),
                serialize(task));
    }

    private EvidenceOwnerTask findTaskForUpdate(
            CapabilitySnapshot.Scope scope, String taskId) {
        return jdbc.query("""
                        SELECT task_json, task_fingerprint, task_id, task_version, package_id,
                               compilation_revision, projection_revision, domain_id, reason,
                               status, owner_id, due_at, updated_at
                        FROM business_mirror_evidence_owner_tasks
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND task_id = ?
                        FOR UPDATE
                        """, (rs, row) -> readTask(rs, scope),
                scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(),
                required(taskId, "taskId", 1024)).stream().findFirst().orElse(null);
    }

    private PackageEvidenceIndex readIndex(
            ResultSet rs, CapabilitySnapshot.Scope expectedScope) throws SQLException {
        try {
            PackageEvidenceIndex value = mapper.readValue(
                    rs.getString("index_json"), PackageEvidenceIndex.class);
            value.verify(mapper);
            if (!value.scope().equals(expectedScope)
                    || !value.indexFingerprint().equals(rs.getString("index_fingerprint"))
                    || !value.packageId().equals(rs.getString("stored_package_id"))
                    || value.compilationRevision()
                    != rs.getLong("stored_compilation_revision")
                    || value.projectionRevision()
                    != rs.getLong("stored_projection_revision")
                    || !value.packageSnapshotSource().fingerprint().equals(
                    rs.getString("stored_snapshot_fingerprint"))
                    || !value.domainId().equals(rs.getString("stored_domain_id"))
                    || !value.validUntil().equals(
                    rs.getTimestamp("stored_valid_until").toInstant())
                    || !value.projectedAt().equals(
                    rs.getTimestamp("stored_projected_at").toInstant())) {
                throw new IllegalStateException("Stored Package evidence index is corrupt");
            }
            return value;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException("Stored Package evidence index is corrupt", failure);
        }
    }

    private EvidenceOwnerTask readTask(
            ResultSet rs, CapabilitySnapshot.Scope expectedScope) throws SQLException {
        try {
            EvidenceOwnerTask value = mapper.readValue(
                    rs.getString("task_json"), EvidenceOwnerTask.class);
            value.verify(mapper);
            if (!value.scope().equals(expectedScope)
                    || !value.taskFingerprint().equals(rs.getString("task_fingerprint"))
                    || !value.taskId().equals(rs.getString("task_id"))
                    || value.version() != rs.getLong("task_version")
                    || !value.packageId().equals(rs.getString("package_id"))
                    || value.compilationRevision() != rs.getLong("compilation_revision")
                    || value.projectionRevision() != rs.getLong("projection_revision")
                    || !value.domainId().equals(rs.getString("domain_id"))
                    || !value.reason().name().equals(rs.getString("reason"))
                    || !value.status().name().equals(rs.getString("status"))
                    || !value.owner().equals(rs.getString("owner_id"))
                    || !value.dueAt().equals(rs.getTimestamp("due_at").toInstant())
                    || !value.updatedAt().equals(rs.getTimestamp("updated_at").toInstant())) {
                throw new IllegalStateException("Stored evidence owner task is corrupt");
            }
            return value;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException("Stored evidence owner task is corrupt", failure);
        }
    }

    private Head findHead(
            CapabilitySnapshot.Scope scope, String packageId, boolean forUpdate) {
        return jdbc.query("""
                        SELECT compilation_revision, projection_revision, index_fingerprint,
                               snapshot_fingerprint, domain_id, valid_until, projected_at
                        FROM business_mirror_package_evidence_heads
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""),
                (rs, row) -> new Head(rs.getLong("compilation_revision"),
                        rs.getLong("projection_revision"), rs.getString("index_fingerprint"),
                        rs.getString("snapshot_fingerprint"), rs.getString("domain_id"),
                        rs.getTimestamp("valid_until").toInstant(),
                        rs.getTimestamp("projected_at").toInstant()),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), packageId)
                .stream().findFirst().orElse(null);
    }

    private JobState findJob(
            CapabilitySnapshot.Scope scope,
            String packageId,
            long compilationRevision,
            boolean forUpdate) {
        return jdbc.query("""
                        SELECT tenant_id, organization_id, project_id, environment_id, region_id,
                               package_id, compilation_revision, snapshot_fingerprint, status,
                               lease_owner, lease_epoch, lease_expires_at, attempt_count,
                               last_failure_code, available_at, created_at, updated_at
                        FROM business_mirror_package_evidence_outbox
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                          AND compilation_revision = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""),
                (rs, row) -> jobState(rs), scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(), packageId,
                compilationRevision).stream().findFirst().orElse(null);
    }

    private void lock(CapabilitySnapshot.Scope scope, String packageId) {
        jdbc.update(dialect.lockAdmissionSql, scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(), packageId);
        jdbc.queryForObject("""
                        SELECT package_id FROM business_mirror_package_evidence_locks
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND package_id = ?
                        FOR UPDATE
                        """, String.class, scope.tenantId(), scope.organizationId(),
                scope.projectId(), scope.environmentId(), scope.region(), packageId);
    }

    private static JobState jobState(ResultSet rs) throws SQLException {
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

    private static void requireSameJob(JobState existing, PackageCompilationReceipt receipt) {
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

    private ProjectionResult result(
            PackageEvidenceIndex index, int taskCount, boolean replayed) {
        return new ProjectionResult(index.packageId(), index.compilationRevision(),
                index.projectionRevision(), index.indexFingerprint(), taskCount,
                index.projectedAt(), replayed);
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
            throw new IllegalStateException("Failed to encode Package evidence material", failure);
        }
    }

    private static Object[] scopeArgs(CapabilitySnapshot.Scope scope, Object... trailing) {
        List<Object> result = new ArrayList<>();
        result.add(scope.tenantId());
        result.add(scope.organizationId());
        result.add(scope.projectId());
        result.add(scope.environmentId());
        result.add(scope.region());
        result.addAll(List.of(trailing));
        return result.toArray();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String required(String value, String field, int maximum) {
        String exact = normalized(value);
        if (exact.isBlank() || exact.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return exact;
    }

    private static Duration positive(Duration value) {
        Duration exact = Objects.requireNonNull(value, "leaseDuration");
        if (exact.isZero() || exact.isNegative() || exact.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "leaseDuration must be positive and no greater than one hour");
        }
        return exact;
    }

    private static Dialect detectDialect(JdbcTemplate jdbc) {
        if (jdbc.getDataSource() == null) {
            throw new IllegalStateException("Package evidence repository requires a DataSource");
        }
        try (var connection = jdbc.getDataSource().getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product != null && product.toLowerCase(Locale.ROOT).contains("postgresql")) {
                return Dialect.POSTGRESQL;
            }
            if (product != null && product.toLowerCase(Locale.ROOT).contains("h2")) {
                return Dialect.H2;
            }
            throw new IllegalStateException("Unsupported Package evidence database: " + product);
        } catch (SQLException failure) {
            throw new IllegalStateException("Failed to inspect Package evidence database", failure);
        }
    }

    private enum Dialect {
        POSTGRESQL("""
                INSERT INTO business_mirror_package_evidence_locks (
                    tenant_id, organization_id, project_id, environment_id, region_id, package_id
                ) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, """
                INSERT INTO business_mirror_package_evidence_outbox (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    package_id, compilation_revision, snapshot_fingerprint, status,
                    lease_owner, lease_epoch, lease_expires_at, attempt_count,
                    last_failure_code, available_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """),
        H2("""
                MERGE INTO business_mirror_package_evidence_locks (
                    tenant_id, organization_id, project_id, environment_id, region_id, package_id
                ) KEY (tenant_id, organization_id, project_id, environment_id, region_id, package_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, """
                MERGE INTO business_mirror_package_evidence_outbox (
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
            long projectionRevision,
            String indexFingerprint,
            String snapshotFingerprint,
            String domainId,
            Instant validUntil,
            Instant projectedAt) {
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
}
