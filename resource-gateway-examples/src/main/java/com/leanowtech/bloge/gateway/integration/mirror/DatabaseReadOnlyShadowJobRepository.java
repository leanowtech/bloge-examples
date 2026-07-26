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
 * JDBC durable queue for one-sample read-only Shadow jobs.
 *
 * <p>A region/environment lock serializes admission, sample-ordinal reservation, crash recovery,
 * and claim across replicas. Lock-row initialization uses a nested savepoint transaction so a
 * concurrent unique-key conflict cannot poison the caller transaction on PostgreSQL or consume
 * an extra pooled connection. Full enterprise scope is duplicated into every job key; request id
 * and {@code samplingGrantFingerprint + sampleOrdinal} are independently unique inside that scope.
 * All time decisions use the application database clock. Request JSON, job JSON, and signed
 * comparison JSON are revalidated against duplicated indexes on every read.</p>
 *
 * <p>The schema deliberately contains no request/response payload, normalized fact value,
 * credential, connector secret, worker exception, or stack trace.</p>
 */
public final class DatabaseReadOnlyShadowJobRepository
        implements ReadOnlyShadowJobRepository {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");
    private static final int RECONCILIATION_LIMIT = 1_000;
    private static final Runnable NO_INITIALIZATION_PROBE =
            () -> {
            };

    private static final String CREATE_LOCKS = """
            CREATE TABLE IF NOT EXISTS mirror_shadow_job_locks (
                region VARCHAR(96) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                PRIMARY KEY (region, environment_id)
            )
            """;
    private static final String CREATE_JOBS = """
            CREATE TABLE IF NOT EXISTS mirror_shadow_jobs (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                job_id VARCHAR(512) NOT NULL,
                request_id VARCHAR(512) NOT NULL,
                request_fingerprint VARCHAR(71) NOT NULL,
                sampling_grant_fingerprint VARCHAR(71) NOT NULL,
                sample_ordinal BIGINT NOT NULL,
                request_json TEXT NOT NULL,
                status VARCHAR(32) NOT NULL,
                attempt_count INTEGER NOT NULL,
                maximum_attempts INTEGER NOT NULL,
                next_eligible_at TIMESTAMP WITH TIME ZONE NOT NULL,
                deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
                lease_owner VARCHAR(512) NOT NULL,
                lease_epoch BIGINT NOT NULL,
                lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                comparison_fingerprint VARCHAR(71) NOT NULL,
                comparison_json TEXT,
                failure_code VARCHAR(255) NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                completed_at TIMESTAMP WITH TIME ZONE,
                record_fingerprint VARCHAR(71) NOT NULL,
                job_json TEXT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, job_id
                ),
                CONSTRAINT uq_mirror_shadow_request UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region, request_id
                ),
                CONSTRAINT uq_mirror_shadow_sample UNIQUE (
                    tenant_id, organization_id, project_id,
                    environment_id, region,
                    sampling_grant_fingerprint, sample_ordinal
                )
            )
            """;
    private static final String CREATE_SCHEDULE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_mirror_shadow_schedule
            ON mirror_shadow_jobs (
                region, environment_id, status,
                next_eligible_at, lease_expires_at, created_at, job_id
            )
            """;
    private static final String CREATE_LIFECYCLE = """
            CREATE TABLE IF NOT EXISTS mirror_shadow_job_lifecycle (
                sequence BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                job_id VARCHAR(512) NOT NULL,
                request_fingerprint VARCHAR(71) NOT NULL,
                transition VARCHAR(32) NOT NULL,
                status VARCHAR(32) NOT NULL,
                attempt_count INTEGER NOT NULL,
                lease_epoch BIGINT NOT NULL,
                owner_fingerprint VARCHAR(71) NOT NULL,
                next_eligible_at TIMESTAMP WITH TIME ZONE NOT NULL,
                lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                comparison_fingerprint VARCHAR(71) NOT NULL,
                failure_code VARCHAR(255) NOT NULL,
                record_fingerprint VARCHAR(71) NOT NULL
            )
            """;
    private static final String CREATE_LIFECYCLE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_mirror_shadow_lifecycle_scope_job
            ON mirror_shadow_job_lifecycle (
                tenant_id, organization_id, project_id,
                environment_id, region, job_id, sequence
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ReadOnlyShadowComparisonIntegrity comparisonIntegrity;
    private final Supplier<Instant> coordinationClock;
    private final TransactionTemplate mutations;
    private final TransactionTemplate lockRowInitialization;
    private final Runnable beforeLockRowInsert;

    /**
     * Creates a production repository using the application database clock.
     *
     * @param jdbc transaction-aware JDBC boundary
     * @param mapper canonical protocol mapper
     * @param comparisonIntegrity signed comparison verifier
     * @param transactionManager JDBC manager for the same datasource with nested savepoints enabled
     * @throws IllegalArgumentException when the manager cannot provide the required savepoint scope
     */
    public DatabaseReadOnlyShadowJobRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ReadOnlyShadowComparisonIntegrity comparisonIntegrity,
            PlatformTransactionManager transactionManager) {
        this(
                jdbc,
                mapper,
                comparisonIntegrity,
                transactionManager,
                null,
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic database-clock seam for lease, retry, and deadline tests. */
    DatabaseReadOnlyShadowJobRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ReadOnlyShadowComparisonIntegrity comparisonIntegrity,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock) {
        this(
                jdbc,
                mapper,
                comparisonIntegrity,
                transactionManager,
                coordinationClock,
                NO_INITIALIZATION_PROBE);
    }

    /** Deterministic clock and pre-insert race seam for target-database certification. */
    DatabaseReadOnlyShadowJobRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ReadOnlyShadowComparisonIntegrity comparisonIntegrity,
            PlatformTransactionManager transactionManager,
            Supplier<Instant> coordinationClock,
            Runnable beforeLockRowInsert) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.comparisonIntegrity = Objects.requireNonNull(
                comparisonIntegrity, "comparisonIntegrity");
        this.coordinationClock = coordinationClock == null
                ? () -> databaseNow(this.jdbc)
                : Objects.requireNonNull(
                coordinationClock, "coordinationClock");
        this.beforeLockRowInsert = Objects.requireNonNull(
                beforeLockRowInsert, "beforeLockRowInsert");
        DataSourceTransactionManager exactTransactions =
                requireSavepointTransactions(
                        this.jdbc,
                        transactionManager);
        mutations = new TransactionTemplate(
                exactTransactions);
        mutations.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRED);
        mutations.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
        lockRowInitialization = new TransactionTemplate(
                exactTransactions);
        lockRowInitialization.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_NESTED);
        lockRowInitialization.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates the scope-explicit queue and region/environment serialization lock. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_LOCKS);
        jdbc.execute(CREATE_JOBS);
        jdbc.execute(CREATE_SCHEDULE_INDEX);
        jdbc.execute(CREATE_LIFECYCLE);
        jdbc.execute(CREATE_LIFECYCLE_INDEX);
    }

    @Override
    public Submission submit(
            ReadOnlyShadowJobRequest request,
            ReadOnlyShadowJobPolicy policy) {
        ReadOnlyShadowJobRequest exact =
                Objects.requireNonNull(request, "request");
        ReadOnlyShadowJobPolicy controls =
                Objects.requireNonNull(policy, "policy");
        Submission result = mutations.execute(ignored -> {
            lockPartition(
                    exact.scope().region(),
                    exact.scope().environmentId());
            Instant observedAt = coordinationNow();
            if (!exact.deadlineAt().isAfter(
                    safePlus(observedAt,
                            controls.leaseDuration()))
                    || exact.deadlineAt().isAfter(
                    safePlus(
                            observedAt,
                            controls.maximumDeadlineHorizon()))) {
                throw new Violation(
                        Reason.DEADLINE_INVALID);
            }
            String requestFingerprint =
                    ReadOnlyShadowJobIntegrity
                            .requestFingerprint(
                                    mapper, exact);
            Optional<StoredJob> replay =
                    findByRequestId(
                            exact.scope(),
                            exact.requestId(),
                            true);
            if (replay.isPresent()) {
                StoredJob stored = replay.orElseThrow();
                if (!stored.job().requestFingerprint()
                        .equals(requestFingerprint)
                        || !stored.request().equals(exact)) {
                    throw new Violation(
                            Reason.REQUEST_CONFLICT);
                }
                return new Submission(
                        stored.job(), true);
            }
            if (findBySample(exact).isPresent()) {
                throw new Violation(
                        Reason.SAMPLE_ORDINAL_CONFLICT);
            }
            String jobId =
                    ReadOnlyShadowJobIntegrity.jobId(
                            requestFingerprint);
            ReadOnlyShadowJob job =
                    ReadOnlyShadowJobIntegrity.seal(
                            mapper,
                            new ReadOnlyShadowJob(
                                    ReadOnlyShadowJob.SCHEMA_VERSION,
                                    jobId,
                                    exact.requestId(),
                                    requestFingerprint,
                                    exact.scope(),
                                    ReadOnlyShadowJob.Status.QUEUED,
                                    0,
                                    controls.maximumAttempts(),
                                    observedAt,
                                    exact.deadlineAt(),
                                    0,
                                    observedAt,
                                    null,
                                    "",
                                    observedAt,
                                    observedAt,
                                    null,
                                    ""));
            try {
                insert(job, exact);
                appendLifecycle(
                        job,
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.ADMITTED,
                        "");
                return new Submission(job, false);
            } catch (DuplicateKeyException conflict) {
                // A partition lock made both unique dimensions observable before INSERT.
                // Do not query after a PostgreSQL constraint violation: the transaction is aborted.
                throw new Violation(
                        Reason.REQUEST_CONFLICT);
            }
        });
        return Objects.requireNonNull(
                result,
                "read-only Shadow submission returned no result");
    }

    @Override
    public Optional<ReadOnlyShadowJob> find(
            CapabilitySnapshot.Scope scope,
            String jobId) {
        return findStored(
                Objects.requireNonNull(scope, "scope"),
                identifier(jobId),
                false).map(StoredJob::job);
    }

    @Override
    public Optional<ReadOnlyShadowJobRequest> findRequest(
            CapabilitySnapshot.Scope scope,
            String jobId) {
        return findStored(
                Objects.requireNonNull(scope, "scope"),
                identifier(jobId),
                false).map(StoredJob::request);
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
            ReadOnlyShadowJobPolicy policy) {
        String exactRegion = identifier(region);
        String exactEnvironment =
                identifier(environmentId);
        String owner = identifier(ownerId);
        if (!OWNER_ID.matcher(owner).matches()) {
            throw new IllegalArgumentException(
                    "ownerId is invalid");
        }
        ReadOnlyShadowJobPolicy controls =
                Objects.requireNonNull(policy, "policy");
        Claim result = mutations.execute(ignored -> {
            lockPartition(
                    exactRegion, exactEnvironment);
            Instant observedAt = coordinationNow();
            for (int reconciled = 0;
                 reconciled < RECONCILIATION_LIMIT;
                 reconciled++) {
                Optional<StoredJob> selected =
                        selectNext(
                                exactRegion,
                                exactEnvironment,
                                observedAt);
                if (selected.isEmpty()) {
                    return Claim.noWork(observedAt);
                }
                StoredJob stored = selected.orElseThrow();
                ReadOnlyShadowJob before = stored.job();
                if (!observedAt.isBefore(
                        before.deadlineAt())
                        || safePlus(
                        observedAt,
                        controls.leaseDuration())
                        .isAfter(before.deadlineAt())) {
                    update(
                            stored,
                            terminal(
                                    before,
                                    ReadOnlyShadowJob.Status.EXPIRED,
                                    "RG.MIRROR.SHADOW.DEADLINE_INSUFFICIENT",
                                    observedAt),
                            "",
                            before.leaseEpoch(),
                            before.leaseExpiresAt(),
                            stored.comparison());
                    continue;
                }
                if (before.attemptCount()
                        >= before.maximumAttempts()) {
                    update(
                            stored,
                            terminal(
                                    before,
                                    ReadOnlyShadowJob.Status.FAILED,
                                    "RG.MIRROR.SHADOW.ATTEMPTS_EXHAUSTED",
                                    observedAt),
                            "",
                            before.leaseEpoch(),
                            before.leaseExpiresAt(),
                            stored.comparison());
                    continue;
                }
                int attempt = Math.addExact(
                        before.attemptCount(), 1);
                long epoch = Math.addExact(
                        before.leaseEpoch(), 1);
                Instant expiresAt = safePlus(
                        observedAt,
                        controls.leaseDuration());
                ReadOnlyShadowJob running =
                        ReadOnlyShadowJobIntegrity.seal(
                                mapper,
                                new ReadOnlyShadowJob(
                                        before.schemaVersion(),
                                        before.jobId(),
                                        before.requestId(),
                                        before.requestFingerprint(),
                                        before.scope(),
                                        ReadOnlyShadowJob.Status.RUNNING,
                                        attempt,
                                        before.maximumAttempts(),
                                        before.nextEligibleAt(),
                                        before.deadlineAt(),
                                        epoch,
                                        expiresAt,
                                        null,
                                        "",
                                        before.createdAt(),
                                        observedAt,
                                        null,
                                        ""));
                StoredJob claimed = update(
                        stored,
                        running,
                        owner,
                        epoch,
                        expiresAt,
                        null);
                Lease lease = new Lease(
                        running.scope(),
                        running.jobId(),
                        owner,
                        epoch,
                        expiresAt);
                return new Claim(
                        ClaimOutcome.ACQUIRED,
                        observedAt,
                        claimed.job(),
                        claimed.request(),
                        lease);
            }
            throw new IllegalStateException(
                    "read-only Shadow claim reconciliation exceeded its bound");
        });
        return Objects.requireNonNull(
                result,
                "read-only Shadow claim returned no result");
    }

    @Override
    public Heartbeat heartbeat(
            Lease lease,
            ReadOnlyShadowJobPolicy policy) {
        Lease exact = Objects.requireNonNull(
                lease, "lease");
        ReadOnlyShadowJobPolicy controls =
                Objects.requireNonNull(policy, "policy");
        Heartbeat result =
                mutations.execute(ignored -> {
                    StoredJob stored = locked(exact);
                    Instant observedAt = coordinationNow();
                    requireCurrentLease(
                            stored, exact, observedAt);
                    Instant expiresAt = safePlus(
                            observedAt,
                            controls.leaseDuration());
                    if (!expiresAt.isBefore(
                            stored.job().deadlineAt())) {
                        throw new Violation(
                                Reason.LEASE_LOST);
                    }
                    ReadOnlyShadowJob before =
                            stored.job();
                    ReadOnlyShadowJob renewed =
                            ReadOnlyShadowJobIntegrity.seal(
                                    mapper,
                                    new ReadOnlyShadowJob(
                                            before.schemaVersion(),
                                            before.jobId(),
                                            before.requestId(),
                                            before.requestFingerprint(),
                                            before.scope(),
                                            before.status(),
                                            before.attemptCount(),
                                            before.maximumAttempts(),
                                            before.nextEligibleAt(),
                                            before.deadlineAt(),
                                            before.leaseEpoch(),
                                            expiresAt,
                                            null,
                                            "",
                                            before.createdAt(),
                                            observedAt,
                                            null,
                                            ""));
                    ReadOnlyShadowJob persisted = update(
                            stored,
                            renewed,
                            exact.ownerId(),
                            exact.epoch(),
                            expiresAt,
                            null).job();
                    return new Heartbeat(
                            persisted,
                            new Lease(
                                    persisted.scope(),
                                    persisted.jobId(),
                                    exact.ownerId(),
                                    persisted.leaseEpoch(),
                                    persisted.leaseExpiresAt()));
                });
        return Objects.requireNonNull(
                result,
                "read-only Shadow heartbeat returned no result");
    }

    @Override
    public ReadOnlyShadowJob complete(
            Lease lease,
            ReadOnlyShadowComparison comparison) {
        Lease exactLease = Objects.requireNonNull(
                lease, "lease");
        ReadOnlyShadowComparison exactComparison =
                comparisonIntegrity.verify(
                        Objects.requireNonNull(
                                comparison, "comparison"));
        ReadOnlyShadowJob result =
                mutations.execute(ignored -> {
                    StoredJob stored = findStored(
                            exactLease.scope(),
                            exactLease.jobId(),
                            true).orElseThrow(() ->
                            new Violation(
                                    Reason.JOB_NOT_FOUND));
                    if (stored.job().status()
                            == ReadOnlyShadowJob.Status.SUCCEEDED) {
                        if (stored.comparison() != null
                                && stored.comparison()
                                .comparisonFingerprint()
                                .equals(exactComparison
                                        .comparisonFingerprint())) {
                            return stored.job();
                        }
                        throw new Violation(
                                Reason.COMPARISON_MISMATCH);
                    }
                    Instant observedAt = coordinationNow();
                    requireCurrentLease(
                            stored,
                            exactLease,
                            observedAt);
                    requireComparisonClosure(
                            stored, exactComparison);
                    ReadOnlyShadowJob before =
                            stored.job();
                    ReadOnlyShadowJob succeeded =
                            ReadOnlyShadowJobIntegrity.seal(
                                    mapper,
                                    new ReadOnlyShadowJob(
                                            before.schemaVersion(),
                                            before.jobId(),
                                            before.requestId(),
                                            before.requestFingerprint(),
                                            before.scope(),
                                            ReadOnlyShadowJob.Status.SUCCEEDED,
                                            before.attemptCount(),
                                            before.maximumAttempts(),
                                            before.nextEligibleAt(),
                                            before.deadlineAt(),
                                            before.leaseEpoch(),
                                            before.leaseExpiresAt(),
                                            exactComparison.artifactRef(),
                                            "",
                                            before.createdAt(),
                                            observedAt,
                                            observedAt,
                                            ""));
                    return update(
                            stored,
                            succeeded,
                            "",
                            exactLease.epoch(),
                            before.leaseExpiresAt(),
                            exactComparison).job();
                });
        return Objects.requireNonNull(
                result,
                "read-only Shadow completion returned no result");
    }

    @Override
    public ReadOnlyShadowJob fail(
            Lease lease,
            String failureCode,
            boolean retryable,
            ReadOnlyShadowJobPolicy policy) {
        Lease exact = Objects.requireNonNull(
                lease, "lease");
        String code = failureCode == null
                ? "" : failureCode.trim().toUpperCase(
                Locale.ROOT);
        if (!FAILURE_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException(
                    "failureCode is invalid");
        }
        ReadOnlyShadowJobPolicy controls =
                Objects.requireNonNull(policy, "policy");
        ReadOnlyShadowJob result =
                mutations.execute(ignored -> {
                    StoredJob stored = locked(exact);
                    Instant observedAt = coordinationNow();
                    requireCurrentLease(
                            stored, exact, observedAt);
                    ReadOnlyShadowJob before =
                            stored.job();
                    Instant retryAt = safePlus(
                            observedAt,
                            controls.retryDelay());
                    boolean mayRetry = retryable
                            && before.attemptCount()
                            < before.maximumAttempts()
                            && safePlus(
                            retryAt,
                            controls.leaseDuration())
                            .isBefore(before.deadlineAt());
                    ReadOnlyShadowJob next;
                    if (mayRetry) {
                        next = ReadOnlyShadowJobIntegrity.seal(
                                mapper,
                                new ReadOnlyShadowJob(
                                        before.schemaVersion(),
                                        before.jobId(),
                                        before.requestId(),
                                        before.requestFingerprint(),
                                        before.scope(),
                                        ReadOnlyShadowJob.Status.QUEUED,
                                        before.attemptCount(),
                                        before.maximumAttempts(),
                                        retryAt,
                                        before.deadlineAt(),
                                        before.leaseEpoch(),
                                        observedAt,
                                        null,
                                        code,
                                        before.createdAt(),
                                        observedAt,
                                        null,
                                        ""));
                    } else {
                        ReadOnlyShadowJob.Status status =
                                !observedAt.isBefore(
                                        before.deadlineAt())
                                        ? ReadOnlyShadowJob.Status.EXPIRED
                                        : ReadOnlyShadowJob.Status.FAILED;
                        next = terminal(
                                before,
                                status,
                                code,
                                observedAt);
                    }
                    return update(
                            stored,
                            next,
                            "",
                            before.leaseEpoch(),
                            next.leaseExpiresAt(),
                            null).job();
                });
        return Objects.requireNonNull(
                result,
                "read-only Shadow failure returned no result");
    }

    @Override
    public Optional<ReadOnlyShadowComparison> findComparison(
            CapabilitySnapshot.Scope scope,
            String jobId) {
        return findStored(
                Objects.requireNonNull(scope, "scope"),
                identifier(jobId),
                false).map(StoredJob::comparison);
    }

    @Override
    public List<ReadOnlyShadowJobLifecycleEvent> lifecycle(
            CapabilitySnapshot.Scope scope,
            String jobId,
            long afterSequence,
            int limit) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactJobId = identifier(jobId);
        if (afterSequence < 0
                || limit < 1
                || limit > 10_000) {
            throw new IllegalArgumentException(
                    "read-only Shadow lifecycle cursor or limit is invalid");
        }
        return jdbc.query("""
                        SELECT *
                        FROM mirror_shadow_job_lifecycle
                        WHERE tenant_id = ? AND organization_id = ?
                          AND project_id = ? AND environment_id = ?
                          AND region = ? AND job_id = ?
                          AND sequence > ?
                        ORDER BY sequence
                        LIMIT ?
                        """,
                (row, ignored) -> lifecycle(row),
                exactScope.tenantId(),
                exactScope.organizationId(),
                exactScope.projectId(),
                exactScope.environmentId(),
                exactScope.region(),
                exactJobId,
                afterSequence,
                limit);
    }

    private void requireComparisonClosure(
            StoredJob stored,
            ReadOnlyShadowComparison comparison) {
        ReadOnlyShadowJobRequest request =
                stored.request();
        if (!ReadOnlyShadowComparison.SCHEMA_VERSION.equals(
                comparison.schemaVersion())
                || !stored.job().jobId().equals(
                comparison.comparisonId())
                || comparison.revision() != 1
                || !request.scope().equals(
                comparison.scope())
                || !request.inventoryRef().equals(
                comparison.inventoryRef())
                || !request.unitId().equals(
                comparison.unitId())
                || !request.scenarioCaseRef().equals(
                comparison.scenarioCaseRef())
                || !request.targetCapabilityRef().equals(
                comparison.targetCapabilityRef())
                || !request.comparisonPolicyRef().equals(
                comparison.comparisonPolicyRef())
                || comparison.sourceResolutionAttestationRef()
                == null
                || !request.accessGrant()
                .zeroWriteProof()
                .equals(comparison.accessProof())) {
            throw new Violation(
                    Reason.COMPARISON_MISMATCH);
        }
    }

    private StoredJob locked(Lease lease) {
        StoredJob stored = findStored(
                lease.scope(),
                lease.jobId(),
                true).orElseThrow(() ->
                new Violation(Reason.JOB_NOT_FOUND));
        return stored;
    }

    private void requireCurrentLease(
            StoredJob stored,
            Lease lease,
            Instant observedAt) {
        if (stored.job().status()
                != ReadOnlyShadowJob.Status.RUNNING
                || !stored.leaseOwner().equals(
                lease.ownerId())
                || stored.job().leaseEpoch()
                != lease.epoch()
                || !stored.job().leaseExpiresAt()
                .equals(lease.expiresAt())
                || !observedAt.isBefore(
                stored.job().leaseExpiresAt())
                || !observedAt.isBefore(
                stored.job().deadlineAt())) {
            throw new Violation(
                    Reason.LEASE_LOST);
        }
    }

    private ReadOnlyShadowJob terminal(
            ReadOnlyShadowJob before,
            ReadOnlyShadowJob.Status status,
            String failureCode,
            Instant observedAt) {
        return ReadOnlyShadowJobIntegrity.seal(
                mapper,
                new ReadOnlyShadowJob(
                        before.schemaVersion(),
                        before.jobId(),
                        before.requestId(),
                        before.requestFingerprint(),
                        before.scope(),
                        status,
                        before.attemptCount(),
                        before.maximumAttempts(),
                        before.nextEligibleAt(),
                        before.deadlineAt(),
                        before.leaseEpoch(),
                        before.leaseExpiresAt(),
                        null,
                        failureCode,
                        before.createdAt(),
                        observedAt,
                        observedAt,
                        ""));
    }

    private void insert(
            ReadOnlyShadowJob job,
            ReadOnlyShadowJobRequest request) {
        CapabilitySnapshot.Scope scope =
                job.scope();
        try {
            jdbc.update("""
                    INSERT INTO mirror_shadow_jobs (
                        tenant_id, organization_id, project_id,
                        environment_id, region, job_id, request_id,
                        request_fingerprint, sampling_grant_fingerprint,
                        sample_ordinal, request_json, status,
                        attempt_count, maximum_attempts, next_eligible_at,
                        deadline_at, lease_owner, lease_epoch,
                        lease_expires_at, comparison_fingerprint,
                        comparison_json, failure_code, created_at,
                        updated_at, completed_at, record_fingerprint,
                        job_json
                    ) VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    job.jobId(),
                    job.requestId(),
                    job.requestFingerprint(),
                    request.accessGrant()
                            .samplingGrantRef()
                            .fingerprint(),
                    request.accessGrant().sampleOrdinal(),
                    mapper.writeValueAsString(request),
                    job.status().name(),
                    job.attemptCount(),
                    job.maximumAttempts(),
                    timestamp(job.nextEligibleAt()),
                    timestamp(job.deadlineAt()),
                    "",
                    job.leaseEpoch(),
                    timestamp(job.leaseExpiresAt()),
                    "",
                    null,
                    job.failureCode(),
                    timestamp(job.createdAt()),
                    timestamp(job.updatedAt()),
                    null,
                    job.recordFingerprint(),
                    mapper.writeValueAsString(job));
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private StoredJob update(
            StoredJob before,
            ReadOnlyShadowJob job,
            String leaseOwner,
            long leaseEpoch,
            Instant leaseExpiresAt,
            ReadOnlyShadowComparison comparison) {
        try {
            int changed = jdbc.update("""
                    UPDATE mirror_shadow_jobs
                    SET status = ?,
                        attempt_count = ?,
                        next_eligible_at = ?,
                        lease_owner = ?,
                        lease_epoch = ?,
                        lease_expires_at = ?,
                        comparison_fingerprint = ?,
                        comparison_json = ?,
                        failure_code = ?,
                        updated_at = ?,
                        completed_at = ?,
                        record_fingerprint = ?,
                        job_json = ?
                    WHERE tenant_id = ? AND organization_id = ?
                      AND project_id = ? AND environment_id = ?
                      AND region = ? AND job_id = ?
                      AND record_fingerprint = ?
                    """,
                    job.status().name(),
                    job.attemptCount(),
                    timestamp(job.nextEligibleAt()),
                    leaseOwner,
                    leaseEpoch,
                    timestamp(leaseExpiresAt),
                    comparison == null
                            ? "" : comparison.comparisonFingerprint(),
                    comparison == null
                            ? null : mapper.writeValueAsString(comparison),
                    job.failureCode(),
                    timestamp(job.updatedAt()),
                    job.completedAt() == null
                            ? null : timestamp(job.completedAt()),
                    job.recordFingerprint(),
                    mapper.writeValueAsString(job),
                    before.job().scope().tenantId(),
                    before.job().scope().organizationId(),
                    before.job().scope().projectId(),
                    before.job().scope().environmentId(),
                    before.job().scope().region(),
                    before.job().jobId(),
                    before.job().recordFingerprint());
            if (changed != 1) {
                throw new Violation(
                        Reason.LEASE_LOST);
            }
            ReadOnlyShadowJobLifecycleEvent.Transition
                    transition = transition(
                    before.job(), job);
            appendLifecycle(
                    job,
                    transition,
                    lifecycleOwner(
                            before,
                            leaseOwner,
                            transition));
            return new StoredJob(
                    job,
                    before.request(),
                    leaseOwner,
                    comparison);
        } catch (JsonProcessingException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private void appendLifecycle(
            ReadOnlyShadowJob job,
            ReadOnlyShadowJobLifecycleEvent.Transition
                    transition,
            String ownerId) {
        CapabilitySnapshot.Scope scope = job.scope();
        String comparisonFingerprint =
                job.comparisonRef() == null
                        ? ""
                        : job.comparisonRef()
                        .fingerprint();
        jdbc.update("""
                        INSERT INTO mirror_shadow_job_lifecycle (
                            occurred_at, tenant_id, organization_id,
                            project_id, environment_id, region, job_id,
                            request_fingerprint, transition, status,
                            attempt_count, lease_epoch, owner_fingerprint,
                            next_eligible_at, lease_expires_at,
                            comparison_fingerprint, failure_code,
                            record_fingerprint
                        ) VALUES (
                            ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            ?, ?, ?, ?, ?
                        )
                        """,
                timestamp(job.updatedAt()),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                job.jobId(),
                job.requestFingerprint(),
                transition.name(),
                job.status().name(),
                job.attemptCount(),
                job.leaseEpoch(),
                ReadOnlyShadowJobIntegrity
                        .ownerFingerprint(
                                mapper, ownerId),
                timestamp(job.nextEligibleAt()),
                timestamp(job.leaseExpiresAt()),
                comparisonFingerprint,
                job.failureCode(),
                job.recordFingerprint());
    }

    private static ReadOnlyShadowJobLifecycleEvent.Transition
    transition(
            ReadOnlyShadowJob before,
            ReadOnlyShadowJob after) {
        if (after.status()
                == ReadOnlyShadowJob.Status.RUNNING) {
            if (before.status()
                    == ReadOnlyShadowJob.Status.RUNNING) {
                return before.leaseEpoch()
                        == after.leaseEpoch()
                        ? ReadOnlyShadowJobLifecycleEvent
                        .Transition.LEASE_RENEWED
                        : ReadOnlyShadowJobLifecycleEvent
                        .Transition.TAKEN_OVER;
            }
            return ReadOnlyShadowJobLifecycleEvent
                    .Transition.CLAIMED;
        }
        if (after.status()
                == ReadOnlyShadowJob.Status.QUEUED) {
            return ReadOnlyShadowJobLifecycleEvent
                    .Transition.RETRY_SCHEDULED;
        }
        return switch (after.status()) {
            case SUCCEEDED ->
                    ReadOnlyShadowJobLifecycleEvent
                            .Transition.SUCCEEDED;
            case FAILED ->
                    ReadOnlyShadowJobLifecycleEvent
                            .Transition.FAILED;
            case EXPIRED ->
                    ReadOnlyShadowJobLifecycleEvent
                            .Transition.EXPIRED;
            case QUEUED, RUNNING ->
                    throw new IllegalStateException(
                            "unsupported read-only Shadow lifecycle transition");
        };
    }

    private static String lifecycleOwner(
            StoredJob before,
            String resultingOwner,
            ReadOnlyShadowJobLifecycleEvent.Transition
                    transition) {
        if (transition
                == ReadOnlyShadowJobLifecycleEvent
                .Transition.EXPIRED) {
            return "";
        }
        String next = resultingOwner == null
                ? "" : resultingOwner.trim();
        return next.isBlank()
                ? before.leaseOwner()
                : next;
    }

    private static ReadOnlyShadowJobLifecycleEvent lifecycle(
            ResultSet row) throws SQLException {
        return new ReadOnlyShadowJobLifecycleEvent(
                ReadOnlyShadowJobLifecycleEvent
                        .SCHEMA_VERSION,
                row.getLong("sequence"),
                instant(row, "occurred_at"),
                new CapabilitySnapshot.Scope(
                        row.getString("tenant_id"),
                        row.getString("organization_id"),
                        row.getString("project_id"),
                        row.getString("environment_id"),
                        row.getString("region")),
                row.getString("job_id"),
                row.getString(
                        "request_fingerprint"),
                ReadOnlyShadowJobLifecycleEvent
                        .Transition.valueOf(
                                row.getString(
                                        "transition")),
                ReadOnlyShadowJob.Status.valueOf(
                        row.getString("status")),
                row.getInt("attempt_count"),
                row.getLong("lease_epoch"),
                row.getString("owner_fingerprint"),
                instant(row, "next_eligible_at"),
                instant(row, "lease_expires_at"),
                row.getString(
                        "comparison_fingerprint"),
                row.getString("failure_code"),
                row.getString(
                        "record_fingerprint"));
    }

    private Optional<StoredJob> selectNext(
            String region,
            String environmentId,
            Instant observedAt) {
        List<StoredJob> values = jdbc.query("""
                SELECT *
                FROM mirror_shadow_jobs
                WHERE region = ?
                  AND environment_id = ?
                  AND (
                    (status = 'QUEUED'
                      AND next_eligible_at <= ?)
                    OR
                    (status = 'RUNNING'
                      AND lease_expires_at <= ?)
                  )
                ORDER BY created_at, job_id
                FOR UPDATE
                """,
                this::map,
                region,
                environmentId,
                timestamp(observedAt),
                timestamp(observedAt));
        return values.stream().findFirst();
    }

    private Optional<StoredJob> findStored(
            CapabilitySnapshot.Scope scope,
            String jobId,
            boolean forUpdate) {
        return one(jdbc.query("""
                SELECT *
                FROM mirror_shadow_jobs
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND job_id = ?
                """ + (forUpdate ? " FOR UPDATE" : ""),
                this::map,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                jobId));
    }

    private Optional<StoredJob> findByRequestId(
            CapabilitySnapshot.Scope scope,
            String requestId,
            boolean forUpdate) {
        return one(jdbc.query("""
                SELECT *
                FROM mirror_shadow_jobs
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ? AND request_id = ?
                """ + (forUpdate ? " FOR UPDATE" : ""),
                this::map,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                requestId));
    }

    private Optional<StoredJob> findBySample(
            ReadOnlyShadowJobRequest request) {
        CapabilitySnapshot.Scope scope =
                request.scope();
        return one(jdbc.query("""
                SELECT *
                FROM mirror_shadow_jobs
                WHERE tenant_id = ? AND organization_id = ?
                  AND project_id = ? AND environment_id = ?
                  AND region = ?
                  AND sampling_grant_fingerprint = ?
                  AND sample_ordinal = ?
                """,
                this::map,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                request.accessGrant()
                        .samplingGrantRef()
                        .fingerprint(),
                request.accessGrant().sampleOrdinal()));
    }

    private StoredJob map(
            ResultSet row,
            int ignored) throws SQLException {
        try {
            ReadOnlyShadowJob job =
                    mapper.readValue(
                            row.getString("job_json"),
                            ReadOnlyShadowJob.class);
            ReadOnlyShadowJobRequest request =
                    mapper.readValue(
                            row.getString("request_json"),
                            ReadOnlyShadowJobRequest.class);
            ReadOnlyShadowJobIntegrity.verify(
                    mapper, job);
            String comparisonJson =
                    row.getString("comparison_json");
            ReadOnlyShadowComparison comparison =
                    comparisonJson == null
                            ? null
                            : comparisonIntegrity.verify(
                            mapper.readValue(
                                    comparisonJson,
                                    ReadOnlyShadowComparison.class));
            MirrorArtifactRef comparisonRef =
                    job.comparisonRef();
            boolean comparisonMatches =
                    comparison == null
                            ? comparisonRef == null
                            && row.getString(
                            "comparison_fingerprint").isBlank()
                            : comparisonRef != null
                            && comparison.artifactRef()
                            .equals(comparisonRef)
                            && comparison.comparisonFingerprint()
                            .equals(row.getString(
                                    "comparison_fingerprint"));
            CapabilitySnapshot.Scope scope =
                    job.scope();
            if (!request.scope().equals(scope)
                    || !request.requestId().equals(
                    job.requestId())
                    || !ReadOnlyShadowJobIntegrity
                    .requestFingerprint(mapper, request)
                    .equals(job.requestFingerprint())
                    || !scope.tenantId().equals(
                    row.getString("tenant_id"))
                    || !scope.organizationId().equals(
                    row.getString("organization_id"))
                    || !scope.projectId().equals(
                    row.getString("project_id"))
                    || !scope.environmentId().equals(
                    row.getString("environment_id"))
                    || !scope.region().equals(
                    row.getString("region"))
                    || !job.jobId().equals(
                    row.getString("job_id"))
                    || !job.requestId().equals(
                    row.getString("request_id"))
                    || !job.requestFingerprint().equals(
                    row.getString("request_fingerprint"))
                    || !request.accessGrant()
                    .samplingGrantRef().fingerprint()
                    .equals(row.getString(
                            "sampling_grant_fingerprint"))
                    || request.accessGrant().sampleOrdinal()
                    != row.getLong("sample_ordinal")
                    || !job.status().name().equals(
                    row.getString("status"))
                    || job.attemptCount()
                    != row.getInt("attempt_count")
                    || job.maximumAttempts()
                    != row.getInt("maximum_attempts")
                    || !job.nextEligibleAt().equals(
                    instant(row, "next_eligible_at"))
                    || !job.deadlineAt().equals(
                    instant(row, "deadline_at"))
                    || job.leaseEpoch()
                    != row.getLong("lease_epoch")
                    || !job.leaseExpiresAt().equals(
                    instant(row, "lease_expires_at"))
                    || !job.failureCode().equals(
                    row.getString("failure_code"))
                    || !job.createdAt().equals(
                    instant(row, "created_at"))
                    || !job.updatedAt().equals(
                    instant(row, "updated_at"))
                    || !Objects.equals(
                    job.completedAt(),
                    nullableInstant(row, "completed_at"))
                    || !job.recordFingerprint().equals(
                    row.getString("record_fingerprint"))
                    || !comparisonMatches) {
                throw new Violation(
                        Reason.STORED_STATE_CORRUPT);
            }
            return new StoredJob(
                    job,
                    request,
                    row.getString("lease_owner"),
                    comparison);
        } catch (Violation invalid) {
            throw invalid;
        } catch (JsonProcessingException
                 | RuntimeException invalid) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
    }

    private void lockPartition(
            String region,
            String environmentId) {
        Long existing = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM mirror_shadow_job_locks
                WHERE region = ? AND environment_id = ?
                """,
                Long.class,
                region,
                environmentId);
        if (existing == null || existing == 0) {
            beforeLockRowInsert.run();
            try {
                lockRowInitialization.executeWithoutResult(ignored ->
                        jdbc.update("""
                                INSERT INTO mirror_shadow_job_locks (
                                    region, environment_id
                                ) VALUES (?, ?)
                                """,
                                region,
                                environmentId));
            } catch (DuplicateKeyException exists) {
                // A concurrent initializer won; rollback to the savepoint keeps the caller usable.
            }
        }
        jdbc.queryForObject("""
                SELECT region
                FROM mirror_shadow_job_locks
                WHERE region = ? AND environment_id = ?
                FOR UPDATE
                """,
                String.class,
                region,
                environmentId);
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
                    "Shadow queue requires one nested-savepoint "
                            + "DataSourceTransactionManager for its JdbcTemplate");
        }
        return exact;
    }

    private static Timestamp timestamp(
            Instant value) {
        return Timestamp.from(
                Objects.requireNonNull(value, "value"));
    }

    private static Instant instant(
            ResultSet row,
            String column) throws SQLException {
        return Objects.requireNonNull(
                row.getTimestamp(column),
                column).toInstant();
    }

    private static Instant nullableInstant(
            ResultSet row,
            String column) throws SQLException {
        Timestamp value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant safePlus(
            Instant value,
            Duration duration) {
        try {
            return value.plus(duration);
        } catch (RuntimeException overflow) {
            throw new IllegalArgumentException(
                    "read-only Shadow time bound overflow",
                    overflow);
        }
    }

    private static String identifier(
            String value) {
        String normalized = value == null
                ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    "identifier is invalid");
        }
        return normalized;
    }

    private static <T> Optional<T> one(
            List<T> values) {
        if (values.size() > 1) {
            throw new Violation(
                    Reason.STORED_STATE_CORRUPT);
        }
        return values.stream().findFirst();
    }

    private record StoredJob(
            ReadOnlyShadowJob job,
            ReadOnlyShadowJobRequest request,
            String leaseOwner,
            ReadOnlyShadowComparison comparison
    ) {
        private StoredJob {
            job = Objects.requireNonNull(job, "job");
            request = Objects.requireNonNull(
                    request, "request");
            leaseOwner = leaseOwner == null
                    ? "" : leaseOwner;
        }
    }
}
