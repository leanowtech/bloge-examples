package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * JDBC continuous-assessment registry with database time and owner/epoch fencing.
 *
 * <p>Complete enterprise scope participates in every key. Registration, lease-expiry recovery,
 * claim, and completion serialize through the same region/environment lock family used by
 * authoritative outcome admission. Selected populations and assessments are checked against
 * their immutable evidence tables before this rebuildable projection may reference them.</p>
 *
 * <p>Raw owner identities exist only in the private coordination column; protocol JSON exposes a
 * domain-separated fingerprint. Every read recomputes the projection fingerprint and compares all
 * duplicated query indexes, failing closed on partial or out-of-band SQL mutation.</p>
 */
public final class
DatabaseAuthoritativeOutcomeContinuousAssessmentRepository
        implements AuthoritativeOutcomeContinuousAssessmentRepository {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    private static final int RECOVERY_LIMIT = 1_000;
    private static final Runnable NO_INITIALIZATION_PROBE =
            () -> {
            };
    private static final String CREATE_LOCKS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_inbox_locks (
                region VARCHAR(96) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                PRIMARY KEY (region, environment_id)
            )
            """;
    private static final String CREATE_PROJECTIONS = """
            CREATE TABLE IF NOT EXISTS mirror_outcome_continuous_assessments (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                projection_id VARCHAR(512) NOT NULL,
                population_id VARCHAR(512) NOT NULL,
                population_revision BIGINT NOT NULL,
                population_fingerprint VARCHAR(71) NOT NULL,
                assessment_id VARCHAR(512) NOT NULL,
                status VARCHAR(32) NOT NULL,
                last_assessment_revision BIGINT NOT NULL,
                last_assessment_fingerprint VARCHAR(71) NOT NULL,
                observation_set_fingerprint VARCHAR(71) NOT NULL,
                disposition_set_fingerprint VARCHAR(71) NOT NULL,
                current_through TIMESTAMP WITH TIME ZONE NOT NULL,
                fresh_until TIMESTAMP WITH TIME ZONE NOT NULL,
                attempt_count BIGINT NOT NULL,
                consecutive_failures INTEGER NOT NULL,
                next_eligible_at TIMESTAMP WITH TIME ZONE NOT NULL,
                lease_owner VARCHAR(512) NOT NULL,
                lease_owner_fingerprint VARCHAR(71) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                failure_code VARCHAR(255) NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                terminal_at TIMESTAMP WITH TIME ZONE,
                record_fingerprint VARCHAR(71) NOT NULL,
                projection_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, projection_id
                ),
                CONSTRAINT uq_mirror_outcome_continuous_assessment_stream UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, assessment_id
                )
            )
            """;
    private static final String CREATE_SCHEDULE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_mirror_outcome_continuous_schedule
            ON mirror_outcome_continuous_assessments (
                region, environment_id, status,
                next_eligible_at, lease_expires_at,
                created_at, projection_id
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Supplier<Instant> coordinationClock;
    private final TransactionTemplate mutations;
    private final TransactionTemplate lockRowInitialization;
    private final Runnable beforeLockRowInsert;

    /**
     * Creates a production repository using the application database clock.
     *
     * @param jdbc transaction-aware JDBC boundary
     * @param mapper canonical protocol mapper
     * @param transactionManager nested-savepoint manager for the same datasource
     */
    public DatabaseAuthoritativeOutcomeContinuousAssessmentRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager) {
        this(
                jdbc,
                mapper,
                transactionManager,
                null,
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic database-clock seam for lease and freshness tests. */
    DatabaseAuthoritativeOutcomeContinuousAssessmentRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock) {
        this(
                jdbc,
                mapper,
                transactionManager,
                coordinationClock,
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic clock and lock-row initialization seam for database certification. */
    DatabaseAuthoritativeOutcomeContinuousAssessmentRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock,
            Runnable beforeLockRowInsert) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.coordinationClock = coordinationClock == null
                ? () -> databaseNow(this.jdbc)
                : Objects.requireNonNull(
                coordinationClock, "coordinationClock");
        this.beforeLockRowInsert = Objects.requireNonNull(
                beforeLockRowInsert, "beforeLockRowInsert");
        DataSourceTransactionManager transactions =
                requireSavepointTransactions(
                        this.jdbc, transactionManager);
        mutations = new TransactionTemplate(transactions);
        mutations.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED);
        mutations.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        lockRowInitialization =
                new TransactionTemplate(transactions);
        lockRowInitialization.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NESTED);
        lockRowInitialization.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates the projection, schedule index, and shared partition-lock table. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_LOCKS);
        jdbc.execute(CREATE_PROJECTIONS);
        jdbc.execute(CREATE_SCHEDULE_INDEX);
    }

    @Override
    public Admission register(
            CapabilitySnapshot.Scope scope,
            AuthoritativeOutcomeContinuousAssessmentRequest
                    request) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        AuthoritativeOutcomeContinuousAssessmentRequest command =
                Objects.requireNonNull(request, "request");
        return required(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactScope.region(),
                            exactScope.environmentId());
                    Optional<StoredProjection> existing =
                            findStored(
                                    exactScope,
                                    command.projectionId(),
                                    true);
                    if (existing.isPresent()) {
                        AuthoritativeOutcomeContinuousAssessmentProjection
                                value = existing.orElseThrow()
                                .projection();
                        if (!value.populationRef().equals(
                                command.populationRef())
                                || !value.assessmentId().equals(
                                command.assessmentId())) {
                            throw new Violation(
                                    Reason.CONTENT_CONFLICT);
                        }
                        requirePopulation(
                                exactScope,
                                command.populationRef());
                        return new Admission(
                                value,
                                coordinationNow(),
                                true);
                    }
                    requirePopulation(
                            exactScope, command.populationRef());
                    requireUnusedAssessmentStream(
                            exactScope, command.assessmentId());
                    Instant now = coordinationNow();
                    AuthoritativeOutcomeContinuousAssessmentProjection
                            projection =
                            new AuthoritativeOutcomeContinuousAssessmentProjection(
                                    "",
                                    exactScope,
                                    command.projectionId(),
                                    command.populationRef(),
                                    command.assessmentId(),
                                    AuthoritativeOutcomeContinuousAssessmentProjection
                                            .Status.QUEUED,
                                    null,
                                    "",
                                    "",
                                    Instant.EPOCH,
                                    Instant.EPOCH,
                                    0,
                                    0,
                                    now,
                                    "",
                                    0,
                                    Instant.EPOCH,
                                    "",
                                    now,
                                    now,
                                    null,
                                    "").seal(mapper);
                    try {
                        insert(projection, "");
                    } catch (DuplicateKeyException conflict) {
                        StoredProjection concurrent =
                                findStored(
                                        exactScope,
                                        command.projectionId(),
                                        true)
                                        .orElseThrow(() ->
                                                new Violation(
                                                        Reason.CONTENT_CONFLICT));
                        if (!concurrent.projection()
                                .populationRef().equals(
                                        command.populationRef())
                                || !concurrent.projection()
                                .assessmentId().equals(
                                        command.assessmentId())) {
                            throw new Violation(
                                    Reason.CONTENT_CONFLICT);
                        }
                        return new Admission(
                                concurrent.projection(),
                                coordinationNow(),
                                true);
                    }
                    return new Admission(
                            projection,
                            now,
                            false);
                }),
                "continuous assessment registration returned null");
    }

    @Override
    public Optional<ObservedProjection> find(
            CapabilitySnapshot.Scope scope,
            String projectionId) {
        Optional<StoredProjection> stored =
                findStored(
                        Objects.requireNonNull(scope, "scope"),
                        identifier(projectionId),
                        false);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ObservedProjection(
                stored.orElseThrow().projection(),
                coordinationNow()));
    }

    @Override
    public Instant observedAt() {
        return coordinationNow();
    }

    @Override
    public Claim claimNext(
            String region,
            String environmentId,
            String ownerId,
            AuthoritativeOutcomeContinuousAssessmentPolicy
                    policy) {
        String exactRegion = partition(
                region, "region", 96)
                .toLowerCase(Locale.ROOT);
        String exactEnvironment = partition(
                environmentId, "environmentId", 255)
                .toLowerCase(Locale.ROOT);
        String exactOwner = owner(ownerId);
        AuthoritativeOutcomeContinuousAssessmentPolicy controls =
                Objects.requireNonNull(policy, "policy");
        return required(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactRegion, exactEnvironment);
                    Instant now = coordinationNow();
                    recoverExpired(
                            exactRegion,
                            exactEnvironment,
                            now,
                            controls);
                    Optional<StoredProjection> candidate =
                            selectNext(
                                    exactRegion,
                                    exactEnvironment,
                                    now);
                    if (candidate.isEmpty()) {
                        return Claim.noWork(now);
                    }
                    StoredProjection current =
                            candidate.orElseThrow();
                    long epoch = Math.addExact(
                            current.projection()
                                    .leaseEpoch(),
                            1L);
                    Instant expiresAt = safePlus(
                            now, controls.leaseDuration());
                    String ownerFingerprint =
                            ReadOnlyShadowJobIntegrity
                                    .ownerFingerprint(
                                            mapper, exactOwner);
                    AuthoritativeOutcomeContinuousAssessmentProjection
                            running = copy(
                            current.projection(),
                            AuthoritativeOutcomeContinuousAssessmentProjection
                                    .Status.RUNNING,
                            current.projection()
                                    .lastAssessmentRef(),
                            current.projection()
                                    .observationSetFingerprint(),
                            current.projection()
                                    .dispositionSetFingerprint(),
                            current.projection()
                                    .currentThrough(),
                            current.projection().freshUntil(),
                            Math.addExact(
                                    current.projection()
                                            .attemptCount(),
                                    1L),
                            current.projection()
                                    .consecutiveFailures(),
                            current.projection()
                                    .nextEligibleAt(),
                            ownerFingerprint,
                            epoch,
                            expiresAt,
                            "",
                            now,
                            null);
                    StoredProjection stored = update(
                            current, running, exactOwner);
                    Lease lease = new Lease(
                            running.scope(),
                            running.projectionId(),
                            exactOwner,
                            epoch,
                            expiresAt);
                    return new Claim(
                            Claim.Outcome.ACQUIRED,
                            now,
                            stored.projection(),
                            lease);
                }),
                "continuous assessment claim returned null");
    }

    @Override
    public AuthoritativeOutcomeContinuousAssessmentProjection
    publish(
            Lease lease,
            MirrorArtifactRef assessmentRef,
            String observationSetFingerprint,
            String dispositionSetFingerprint,
            AuthoritativeOutcomeContinuousAssessmentPolicy
                    policy) {
        Lease exactLease = Objects.requireNonNull(
                lease, "lease");
        MirrorArtifactRef exactAssessment =
                Objects.requireNonNull(
                        assessmentRef, "assessmentRef");
        String observations = fingerprint(
                observationSetFingerprint);
        String dispositions = fingerprint(
                dispositionSetFingerprint);
        AuthoritativeOutcomeContinuousAssessmentPolicy controls =
                Objects.requireNonNull(policy, "policy");
        return required(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactLease.scope().region(),
                            exactLease.scope().environmentId());
                    Instant now = coordinationNow();
                    StoredProjection current =
                            requireLease(exactLease, now);
                    requireAssessment(
                            current.projection(),
                            exactAssessment,
                            observations,
                            dispositions);
                    MirrorArtifactRef previous =
                            current.projection()
                                    .lastAssessmentRef();
                    if (previous != null
                            && (exactAssessment.revision()
                            < previous.revision()
                            || exactAssessment.revision()
                            == previous.revision()
                            && !exactAssessment.equals(previous))) {
                        throw new Violation(
                                Reason.ASSESSMENT_INVALID);
                    }
                    Instant freshUntil = safePlus(
                            now, controls.pollingInterval());
                    AuthoritativeOutcomeContinuousAssessmentProjection
                            queued = copy(
                            current.projection(),
                            AuthoritativeOutcomeContinuousAssessmentProjection
                                    .Status.QUEUED,
                            exactAssessment,
                            observations,
                            dispositions,
                            now,
                            freshUntil,
                            current.projection()
                                    .attemptCount(),
                            0,
                            freshUntil,
                            "",
                            current.projection()
                                    .leaseEpoch(),
                            Instant.EPOCH,
                            "",
                            now,
                            null);
                    return update(
                            current, queued, "")
                            .projection();
                }),
                "continuous assessment publication returned null");
    }

    @Override
    public AuthoritativeOutcomeContinuousAssessmentProjection
    unchanged(
            Lease lease,
            AuthoritativeOutcomeContinuousAssessmentPolicy
                    policy) {
        Lease exactLease = Objects.requireNonNull(
                lease, "lease");
        AuthoritativeOutcomeContinuousAssessmentPolicy controls =
                Objects.requireNonNull(policy, "policy");
        return required(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactLease.scope().region(),
                            exactLease.scope().environmentId());
                    Instant now = coordinationNow();
                    StoredProjection current =
                            requireLease(exactLease, now);
                    if (current.projection()
                            .lastAssessmentRef() == null) {
                        throw new Violation(
                                Reason.ASSESSMENT_INVALID);
                    }
                    Instant freshUntil = safePlus(
                            now, controls.pollingInterval());
                    AuthoritativeOutcomeContinuousAssessmentProjection
                            queued = copy(
                            current.projection(),
                            AuthoritativeOutcomeContinuousAssessmentProjection
                                    .Status.QUEUED,
                            current.projection()
                                    .lastAssessmentRef(),
                            current.projection()
                                    .observationSetFingerprint(),
                            current.projection()
                                    .dispositionSetFingerprint(),
                            now,
                            freshUntil,
                            current.projection()
                                    .attemptCount(),
                            0,
                            freshUntil,
                            "",
                            current.projection()
                                    .leaseEpoch(),
                            Instant.EPOCH,
                            "",
                            now,
                            null);
                    return update(
                            current, queued, "")
                            .projection();
                }),
                "continuous assessment unchanged transition returned null");
    }

    @Override
    public AuthoritativeOutcomeContinuousAssessmentProjection fail(
            Lease lease,
            String failureCode,
            boolean retryable,
            AuthoritativeOutcomeContinuousAssessmentPolicy
                    policy) {
        Lease exactLease = Objects.requireNonNull(
                lease, "lease");
        String code = failureCode(failureCode);
        AuthoritativeOutcomeContinuousAssessmentPolicy controls =
                Objects.requireNonNull(policy, "policy");
        return required(
                mutations.execute(ignored -> {
                    lockPartition(
                            exactLease.scope().region(),
                            exactLease.scope().environmentId());
                    Instant now = coordinationNow();
                    StoredProjection current =
                            requireLease(exactLease, now);
                    int failures = Math.addExact(
                            current.projection()
                                    .consecutiveFailures(),
                            1);
                    boolean retry = retryable
                            && failures
                            < controls
                            .maximumConsecutiveFailures();
                    AuthoritativeOutcomeContinuousAssessmentProjection
                            failed = copy(
                            current.projection(),
                            retry
                                    ? AuthoritativeOutcomeContinuousAssessmentProjection
                                    .Status.RETRY_WAIT
                                    : AuthoritativeOutcomeContinuousAssessmentProjection
                                    .Status.QUARANTINED,
                            current.projection()
                                    .lastAssessmentRef(),
                            current.projection()
                                    .observationSetFingerprint(),
                            current.projection()
                                    .dispositionSetFingerprint(),
                            current.projection()
                                    .currentThrough(),
                            current.projection().freshUntil(),
                            current.projection()
                                    .attemptCount(),
                            failures,
                            retry
                                    ? safePlus(
                                    now,
                                    controls.retryDelay(
                                            failures))
                                    : now,
                            "",
                            current.projection()
                                    .leaseEpoch(),
                            Instant.EPOCH,
                            code,
                            now,
                            retry ? null : now);
                    return update(
                            current, failed, "")
                            .projection();
                }),
                "continuous assessment failure transition returned null");
    }

    private void recoverExpired(
            String region,
            String environmentId,
            Instant now,
            AuthoritativeOutcomeContinuousAssessmentPolicy
                    policy) {
        List<StoredProjection> expired = jdbc.query("""
                        SELECT *
                        FROM mirror_outcome_continuous_assessments
                        WHERE region = ? AND environment_id = ?
                          AND status = 'RUNNING'
                          AND lease_expires_at <= ?
                        ORDER BY lease_expires_at, created_at, projection_id
                        FETCH FIRST ? ROWS ONLY
                        FOR UPDATE
                        """,
                this::mapProjection,
                region,
                environmentId,
                timestamp(now),
                RECOVERY_LIMIT);
        for (StoredProjection current : expired) {
            int failures = Math.addExact(
                    current.projection()
                            .consecutiveFailures(),
                    1);
            boolean retry = failures
                    < policy.maximumConsecutiveFailures();
            AuthoritativeOutcomeContinuousAssessmentProjection
                    recovered = copy(
                    current.projection(),
                    retry
                            ? AuthoritativeOutcomeContinuousAssessmentProjection
                            .Status.RETRY_WAIT
                            : AuthoritativeOutcomeContinuousAssessmentProjection
                            .Status.QUARANTINED,
                    current.projection()
                            .lastAssessmentRef(),
                    current.projection()
                            .observationSetFingerprint(),
                    current.projection()
                            .dispositionSetFingerprint(),
                    current.projection()
                            .currentThrough(),
                    current.projection().freshUntil(),
                    current.projection().attemptCount(),
                    failures,
                    retry
                            ? safePlus(
                            now,
                            policy.retryDelay(failures))
                            : now,
                    "",
                    current.projection().leaseEpoch(),
                    Instant.EPOCH,
                    "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_LEASE_EXPIRED",
                    now,
                    retry ? null : now);
            update(current, recovered, "");
        }
    }

    private Optional<StoredProjection> selectNext(
            String region,
            String environmentId,
            Instant now) {
        List<StoredProjection> values = jdbc.query("""
                        SELECT *
                        FROM mirror_outcome_continuous_assessments
                        WHERE region = ? AND environment_id = ?
                          AND status IN ('QUEUED', 'RETRY_WAIT')
                          AND next_eligible_at <= ?
                        ORDER BY next_eligible_at, created_at, projection_id
                        FETCH FIRST 1 ROWS ONLY
                        FOR UPDATE
                        """,
                this::mapProjection,
                region,
                environmentId,
                timestamp(now));
        return one(values);
    }

    private StoredProjection requireLease(
            Lease lease,
            Instant now) {
        StoredProjection current =
                findStored(
                        lease.scope(),
                        lease.projectionId(),
                        true)
                        .orElseThrow(() ->
                                new Violation(
                                        Reason.PROJECTION_NOT_FOUND));
        if (current.projection().status()
                != AuthoritativeOutcomeContinuousAssessmentProjection
                .Status.RUNNING
                || current.projection().leaseEpoch()
                != lease.epoch()
                || !current.leaseOwner().equals(
                lease.ownerId())
                || !current.projection()
                .leaseExpiresAt().equals(
                        lease.expiresAt())
                || !lease.expiresAt().isAfter(now)) {
            throw new Violation(Reason.LEASE_LOST);
        }
        return current;
    }

    private void requirePopulation(
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef populationRef) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM mirror_outcome_selected_populations
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND population_id = ?
                          AND revision = ? AND population_fingerprint = ?
                        """,
                Long.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                populationRef.id(),
                populationRef.revision(),
                populationRef.fingerprint());
        if (count == null || count != 1L) {
            throw new Violation(
                    Reason.PROJECTION_NOT_FOUND);
        }
    }

    private void requireAssessment(
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection,
            MirrorArtifactRef assessmentRef,
            String observationSetFingerprint,
            String dispositionSetFingerprint) {
        if (!AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                .ARTIFACT_KIND.equals(
                assessmentRef.kind())
                || !projection.assessmentId().equals(
                assessmentRef.id())) {
            throw new Violation(
                    Reason.ASSESSMENT_INVALID);
        }
        CapabilitySnapshot.Scope scope = projection.scope();
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM mirror_outcome_population_assessments
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND assessment_id = ?
                          AND revision = ? AND assessment_fingerprint = ?
                          AND population_id = ?
                          AND population_revision = ?
                          AND population_fingerprint = ?
                          AND observation_set_fingerprint = ?
                          AND disposition_set_fingerprint = ?
                        """,
                Long.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                assessmentRef.id(),
                assessmentRef.revision(),
                assessmentRef.fingerprint(),
                projection.populationRef().id(),
                projection.populationRef().revision(),
                projection.populationRef().fingerprint(),
                observationSetFingerprint,
                dispositionSetFingerprint);
        if (count == null || count != 1L) {
            throw new Violation(
                    Reason.ASSESSMENT_INVALID);
        }
    }

    private void requireUnusedAssessmentStream(
            CapabilitySnapshot.Scope scope,
            String assessmentId) {
        Long count = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM mirror_outcome_population_assessment_heads
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND assessment_id = ?
                        """,
                Long.class,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                assessmentId);
        if (count == null || count != 0L) {
            throw new Violation(
                    Reason.CONTENT_CONFLICT);
        }
    }

    private Optional<StoredProjection> findStored(
            CapabilitySnapshot.Scope scope,
            String projectionId,
            boolean locked) {
        String suffix = locked ? " FOR UPDATE" : "";
        List<StoredProjection> values = jdbc.query("""
                        SELECT *
                        FROM mirror_outcome_continuous_assessments
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND projection_id = ?
                        """ + suffix,
                this::mapProjection,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                identifier(projectionId));
        return one(values);
    }

    private void insert(
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection,
            String leaseOwner) {
        CapabilitySnapshot.Scope scope = projection.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_outcome_continuous_assessments (
                                tenant_id, organization_id, project_id,
                                environment_id, region, projection_id,
                                population_id, population_revision,
                                population_fingerprint, assessment_id,
                                status, last_assessment_revision,
                                last_assessment_fingerprint,
                                observation_set_fingerprint,
                                disposition_set_fingerprint,
                                current_through, fresh_until,
                                attempt_count, consecutive_failures,
                                next_eligible_at, lease_owner,
                                lease_owner_fingerprint, lease_epoch,
                                lease_expires_at, failure_code,
                                created_at, updated_at, terminal_at,
                                record_fingerprint, projection_json
                            ) VALUES (
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                            )
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    projection.projectionId(),
                    projection.populationRef().id(),
                    projection.populationRef().revision(),
                    projection.populationRef().fingerprint(),
                    projection.assessmentId(),
                    projection.status().name(),
                    assessmentRevision(projection),
                    assessmentFingerprint(projection),
                    projection.observationSetFingerprint(),
                    projection.dispositionSetFingerprint(),
                    timestamp(projection.currentThrough()),
                    timestamp(projection.freshUntil()),
                    projection.attemptCount(),
                    projection.consecutiveFailures(),
                    timestamp(projection.nextEligibleAt()),
                    leaseOwner,
                    projection.leaseOwnerFingerprint(),
                    projection.leaseEpoch(),
                    timestamp(projection.leaseExpiresAt()),
                    projection.failureCode(),
                    timestamp(projection.createdAt()),
                    timestamp(projection.updatedAt()),
                    projection.terminalAt() == null
                            ? null
                            : timestamp(projection.terminalAt()),
                    projection.recordFingerprint(),
                    mapper.writeValueAsString(projection));
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private StoredProjection update(
            StoredProjection before,
            AuthoritativeOutcomeContinuousAssessmentProjection
                    after,
            String leaseOwner) {
        CapabilitySnapshot.Scope scope = after.scope();
        String exactOwner =
                after.status()
                        == AuthoritativeOutcomeContinuousAssessmentProjection
                        .Status.RUNNING
                        ? owner(leaseOwner)
                        : "";
        try {
            int changed = jdbc.update("""
                            UPDATE mirror_outcome_continuous_assessments
                            SET status = ?,
                                last_assessment_revision = ?,
                                last_assessment_fingerprint = ?,
                                observation_set_fingerprint = ?,
                                disposition_set_fingerprint = ?,
                                current_through = ?, fresh_until = ?,
                                attempt_count = ?,
                                consecutive_failures = ?,
                                next_eligible_at = ?, lease_owner = ?,
                                lease_owner_fingerprint = ?,
                                lease_epoch = ?, lease_expires_at = ?,
                                failure_code = ?, updated_at = ?,
                                terminal_at = ?, record_fingerprint = ?,
                                projection_json = ?
                            WHERE tenant_id = ? AND organization_id = ?
                              AND project_id = ? AND environment_id = ?
                              AND region = ? AND projection_id = ?
                              AND record_fingerprint = ?
                            """,
                    after.status().name(),
                    assessmentRevision(after),
                    assessmentFingerprint(after),
                    after.observationSetFingerprint(),
                    after.dispositionSetFingerprint(),
                    timestamp(after.currentThrough()),
                    timestamp(after.freshUntil()),
                    after.attemptCount(),
                    after.consecutiveFailures(),
                    timestamp(after.nextEligibleAt()),
                    exactOwner,
                    after.leaseOwnerFingerprint(),
                    after.leaseEpoch(),
                    timestamp(after.leaseExpiresAt()),
                    after.failureCode(),
                    timestamp(after.updatedAt()),
                    after.terminalAt() == null
                            ? null
                            : timestamp(after.terminalAt()),
                    after.recordFingerprint(),
                    mapper.writeValueAsString(after),
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    after.projectionId(),
                    before.projection()
                            .recordFingerprint());
            if (changed != 1) {
                throw new Violation(
                        Reason.LEASE_LOST);
            }
            return new StoredProjection(after, exactOwner);
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private StoredProjection mapProjection(
            ResultSet row,
            int ignored) throws SQLException {
        try {
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection = mapper.readValue(
                    row.getString("projection_json"),
                    AuthoritativeOutcomeContinuousAssessmentProjection
                            .class);
            projection.verify(mapper);
            CapabilitySnapshot.Scope scope =
                    projection.scope();
            if (!scope.tenantId().equals(
                    row.getString("tenant_id"))
                    || !scope.organizationId().equals(
                    row.getString("organization_id"))
                    || !scope.projectId().equals(
                    row.getString("project_id"))
                    || !scope.environmentId().equals(
                    row.getString("environment_id"))
                    || !scope.region().equals(
                    row.getString("region"))
                    || !projection.projectionId().equals(
                    row.getString("projection_id"))
                    || !projection.populationRef().id()
                    .equals(row.getString(
                            "population_id"))
                    || projection.populationRef().revision()
                    != row.getLong("population_revision")
                    || !projection.populationRef()
                    .fingerprint().equals(
                            row.getString(
                                    "population_fingerprint"))
                    || !projection.assessmentId().equals(
                    row.getString("assessment_id"))
                    || !projection.status().name().equals(
                    row.getString("status"))
                    || assessmentRevision(projection)
                    != row.getLong(
                            "last_assessment_revision")
                    || !assessmentFingerprint(projection)
                    .equals(row.getString(
                            "last_assessment_fingerprint"))
                    || !projection.observationSetFingerprint()
                    .equals(row.getString(
                            "observation_set_fingerprint"))
                    || !projection.dispositionSetFingerprint()
                    .equals(row.getString(
                            "disposition_set_fingerprint"))
                    || !projection.currentThrough().equals(
                    instant(row, "current_through"))
                    || !projection.freshUntil().equals(
                    instant(row, "fresh_until"))
                    || projection.attemptCount()
                    != row.getLong("attempt_count")
                    || projection.consecutiveFailures()
                    != row.getInt("consecutive_failures")
                    || !projection.nextEligibleAt().equals(
                    instant(row, "next_eligible_at"))
                    || !projection.leaseOwnerFingerprint()
                    .equals(row.getString(
                            "lease_owner_fingerprint"))
                    || projection.leaseEpoch()
                    != row.getLong("lease_epoch")
                    || !projection.leaseExpiresAt().equals(
                    instant(row, "lease_expires_at"))
                    || !projection.failureCode().equals(
                    row.getString("failure_code"))
                    || !projection.createdAt().equals(
                    instant(row, "created_at"))
                    || !projection.updatedAt().equals(
                    instant(row, "updated_at"))
                    || !Objects.equals(
                    projection.terminalAt(),
                    nullableInstant(row, "terminal_at"))
                    || !projection.recordFingerprint()
                    .equals(row.getString(
                            "record_fingerprint"))) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            String owner = normalized(
                    row.getString("lease_owner"));
            boolean running = projection.status()
                    == AuthoritativeOutcomeContinuousAssessmentProjection
                    .Status.RUNNING;
            if (running != !owner.isBlank()
                    || !projection.leaseOwnerFingerprint()
                    .equals(
                            ReadOnlyShadowJobIntegrity
                                    .ownerFingerprint(
                                            mapper, owner))) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return new StoredProjection(
                    projection, owner);
        } catch (Violation invalid) {
            throw invalid;
        } catch (JsonProcessingException
                 | RuntimeException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private AuthoritativeOutcomeContinuousAssessmentProjection
    copy(
            AuthoritativeOutcomeContinuousAssessmentProjection before,
            AuthoritativeOutcomeContinuousAssessmentProjection.Status
                    status,
            MirrorArtifactRef assessmentRef,
            String observationSetFingerprint,
            String dispositionSetFingerprint,
            Instant currentThrough,
            Instant freshUntil,
            long attemptCount,
            int consecutiveFailures,
            Instant nextEligibleAt,
            String leaseOwnerFingerprint,
            long leaseEpoch,
            Instant leaseExpiresAt,
            String failureCode,
            Instant updatedAt,
            Instant terminalAt) {
        return new AuthoritativeOutcomeContinuousAssessmentProjection(
                "",
                before.scope(),
                before.projectionId(),
                before.populationRef(),
                before.assessmentId(),
                status,
                assessmentRef,
                observationSetFingerprint,
                dispositionSetFingerprint,
                currentThrough,
                freshUntil,
                attemptCount,
                consecutiveFailures,
                nextEligibleAt,
                leaseOwnerFingerprint,
                leaseEpoch,
                leaseExpiresAt,
                failureCode,
                before.createdAt(),
                updatedAt,
                terminalAt,
                "").seal(mapper);
    }

    private void lockPartition(
            String region,
            String environmentId) {
        String exactRegion = partition(
                region, "region", 96)
                .toLowerCase(Locale.ROOT);
        String exactEnvironment = partition(
                environmentId, "environmentId", 255)
                .toLowerCase(Locale.ROOT);
        Long existing = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM mirror_outcome_inbox_locks
                        WHERE region = ? AND environment_id = ?
                        """,
                Long.class,
                exactRegion,
                exactEnvironment);
        if (existing == null || existing == 0) {
            beforeLockRowInsert.run();
            try {
                lockRowInitialization.executeWithoutResult(
                        ignored -> jdbc.update("""
                                        INSERT INTO mirror_outcome_inbox_locks (
                                            region, environment_id
                                        ) VALUES (?, ?)
                                        """,
                                exactRegion,
                                exactEnvironment));
            } catch (DuplicateKeyException exists) {
                // A concurrent initializer won; savepoint rollback keeps the caller usable.
            }
        }
        jdbc.queryForObject("""
                        SELECT region
                        FROM mirror_outcome_inbox_locks
                        WHERE region = ? AND environment_id = ?
                        FOR UPDATE
                        """,
                String.class,
                exactRegion,
                exactEnvironment);
    }

    private Instant coordinationNow() {
        return Objects.requireNonNull(
                coordinationClock.get(),
                "database clock returned null");
    }

    private static Instant databaseNow(
            JdbcTemplate jdbc) {
        Timestamp value = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP",
                Timestamp.class);
        return Objects.requireNonNull(
                value,
                "database clock returned null").toInstant();
    }

    private static DataSourceTransactionManager
    requireSavepointTransactions(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager) {
        if (!(Objects.requireNonNull(
                transactionManager,
                "transactionManager")
                instanceof DataSourceTransactionManager exact)
                || !exact.isNestedTransactionAllowed()
                || exact.getDataSource()
                != jdbc.getDataSource()) {
            throw new IllegalArgumentException(
                    "Continuous assessment requires one nested-savepoint "
                            + "DataSourceTransactionManager for its JdbcTemplate");
        }
        return exact;
    }

    private static long assessmentRevision(
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection) {
        return projection.lastAssessmentRef() == null
                ? 0L
                : projection.lastAssessmentRef().revision();
    }

    private static String assessmentFingerprint(
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection) {
        return projection.lastAssessmentRef() == null
                ? ""
                : projection.lastAssessmentRef()
                .fingerprint();
    }

    private static String partition(
            String value,
            String field,
            int maximum) {
        String exact = normalized(value);
        if (exact.length() > maximum
                || !IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String identifier(String value) {
        return partition(
                value, "projectionId", 512);
    }

    private static String owner(String value) {
        String exact = normalized(value);
        if (!OWNER_ID.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "ownerId is invalid");
        }
        return exact;
    }

    private static String failureCode(String value) {
        String exact = normalized(value);
        if (!FAILURE_CODE.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "failureCode is invalid");
        }
        return exact;
    }

    private static String fingerprint(String value) {
        String exact = normalized(value);
        if (!AuthoritativeOutcomeSelectedPopulationRepository
                .FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "source-set fingerprint is invalid");
        }
        return exact;
    }

    private static Instant safePlus(
            Instant value, Duration duration) {
        try {
            return value.plus(duration);
        } catch (RuntimeException overflow) {
            return Instant.MAX;
        }
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(
                Objects.requireNonNull(value, "value"));
    }

    private static Instant instant(
            ResultSet row,
            String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        if (value == null) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(
            ResultSet row,
            String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> Optional<T> one(List<T> values) {
        if (values.size() > 1) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return values.stream().findFirst();
    }

    private static <T> T required(
            T value, String message) {
        return Objects.requireNonNull(value, message);
    }

    private record StoredProjection(
            AuthoritativeOutcomeContinuousAssessmentProjection
                    projection,
            String leaseOwner
    ) {
        private StoredProjection {
            projection = Objects.requireNonNull(
                    projection, "projection");
            leaseOwner = normalized(leaseOwner);
        }
    }
}
