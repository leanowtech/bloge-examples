package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityExecutionStop;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobClaim;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobCompletionPreparation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobLease;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobLeaseCheck;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobPrincipal;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobParentAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRepository;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRetentionAttempt;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRetentionResult;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobRetentionSnapshot;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobSubmission;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityQueuePolicy;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityQueueSnapshot;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunConflictException;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * JDBC implementation of the cross-replica suite-stability parent queue.
 *
 * <p>One environment lock serializes policy convergence, capacity admission, stale-owner recovery,
 * tenant cursor movement, and claims. This intentionally trades peak claim throughput for a simple
 * proof that independently deployed replicas cannot over-admit or select different next tenants.
 * Execution itself occurs outside the transaction under an exact per-job lease. Every queue
 * terminal stop invokes the parent-first authority before its queue transition; a failed outer
 * transaction can therefore leave only a conservative replayable parent stop, never a terminal
 * queue row with resumable parent progress. The inverse success path requires a verified parent
 * terminal proof before the queue may enter {@code SUCCEEDED}.</p>
 */
public final class DatabaseTestSuiteStabilityJobRepository
        implements TestSuiteStabilityJobRepository {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");
    private static final int MAXIMUM_PURGE_BATCH = 10_000;
    private static final int RECONCILIATION_BATCH = 1_000;
    private static final int TOMBSTONE_VERSION = 1;
    private static final String RETENTION_JOB = "suite-stability-job-retention-v1";
    private static final Duration MINIMUM_TOMBSTONE_RETENTION = Duration.ofDays(1);
    private static final Duration MAXIMUM_TOMBSTONE_RETENTION = Duration.ofDays(3650);
    private static final Duration MINIMUM_RETENTION_LEASE = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_RETENTION_LEASE = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityJobParentAuthority parentAuthority;
    private final TestSuiteStabilityJobRequestKeyProtector requestKeys;
    private final String retentionOwnerId;
    private final Duration retentionLeaseDuration;
    private final TransactionTemplate mutations;

    /**
     * @param jdbc isolated test-runtime JDBC adapter
     * @param objectMapper canonical protocol mapper
     * @param parentAuthority parent-first stop or signed-winner resolver
     * @param requestKeys non-reversible retired request identity authority
     * @param retentionOwnerId stable process identity for cross-replica maintenance
     * @param retentionLeaseDuration database-clock page ownership window
     * @param transactionManager transaction manager for the same datasource
     */
    public DatabaseTestSuiteStabilityJobRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityJobParentAuthority parentAuthority,
            TestSuiteStabilityJobRequestKeyProtector requestKeys,
            String retentionOwnerId,
            Duration retentionLeaseDuration,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.parentAuthority = Objects.requireNonNull(parentAuthority, "parentAuthority");
        this.requestKeys = Objects.requireNonNull(requestKeys, "requestKeys");
        this.retentionOwnerId = requiredRetentionOwner(retentionOwnerId);
        this.retentionLeaseDuration = boundedRetentionLease(retentionLeaseDuration);
        mutations = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates queue, tombstone, environment lock, policy, cursor, and bounded lookup indexes. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_queue_locks (
                    environment_id VARCHAR(255) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_queue_policies (
                    environment_id VARCHAR(255) PRIMARY KEY,
                    policy_generation BIGINT NOT NULL,
                    policy_fingerprint VARCHAR(71) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_queue_cursors (
                    environment_id VARCHAR(255) PRIMARY KEY,
                    last_tenant_id VARCHAR(255) NOT NULL,
                    cycle_epoch BIGINT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_jobs (
                    job_id VARCHAR(255) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    client_request_id VARCHAR(255) NOT NULL,
                    submission_fingerprint VARCHAR(71) NOT NULL,
                    request_fingerprint VARCHAR(71) NOT NULL,
                    request_json CLOB NOT NULL,
                    classification VARCHAR(32) NOT NULL,
                    principal_json CLOB NOT NULL,
                    base_priority INTEGER NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    retry_count INTEGER NOT NULL,
                    next_eligible_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    owner_id VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE,
                    policy_generation BIGINT NOT NULL,
                    terminal_stability_run_id VARCHAR(255) NOT NULL,
                    terminal_evidence_fingerprint VARCHAR(71) NOT NULL,
                    failure_code VARCHAR(128) NOT NULL,
                    cancellation_request_id VARCHAR(255) NOT NULL,
                    cancellation_fingerprint VARCHAR(71) NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    CONSTRAINT uq_rg_test_suite_stability_job_request
                        UNIQUE (tenant_id, environment_id, client_request_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_jobs_schedule
                ON rg_test_suite_stability_jobs (
                    environment_id, status, next_eligible_at, deadline_at, tenant_id, created_at
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_jobs_running
                ON rg_test_suite_stability_jobs (
                    environment_id, tenant_id, status, lease_expires_at
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_jobs_retention
                ON rg_test_suite_stability_jobs (expires_at, job_id)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_job_tombstones (
                    tenant_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    request_key_id VARCHAR(64) NOT NULL,
                    request_key VARCHAR(80) NOT NULL,
                    submission_fingerprint VARCHAR(71) NOT NULL,
                    tombstoned_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_version INTEGER NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (tenant_id, environment_id, request_key_id, request_key)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_test_suite_stability_job_tombstone_expiry
                ON rg_test_suite_stability_job_tombstones (expires_at, request_key_id, request_key)
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_job_retention (
                    job_name VARCHAR(128) PRIMARY KEY,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    revision BIGINT NOT NULL,
                    total_jobs_tombstoned BIGINT NOT NULL,
                    total_tombstones_purged BIGINT NOT NULL,
                    last_success_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        initializeRetentionState();
        retentionState(false);
        requireRetainedTombstoneKeys();
    }

    @Override
    public TestSuiteStabilityJobRecord submit(
            TestSuiteStabilityJobSubmission submission,
            TestSuiteStabilityQueuePolicy policy) {
        return submitDetailed(submission, policy).job();
    }

    @Override
    public SubmissionResult submitDetailed(
            TestSuiteStabilityJobSubmission submission,
            TestSuiteStabilityQueuePolicy policy) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(policy, "policy");
        SubmissionResult result = mutations.execute(status -> {
            String environment = submission.principal().environmentId();
            lockEnvironment(environment);
            Instant observedAt = currentTime();
            ensurePolicy(environment, policy, observedAt);
            reconcile(environment, observedAt, policy);
            String submissionFingerprint = submissionFingerprint(submission);
            Optional<StoredJob> existing = byClientRequestId(
                    submission.principal().tenantId(), environment,
                    submission.request().clientRequestId());
            if (existing.isPresent()) {
                requireSameSubmission(existing.get(), submission, submissionFingerprint);
                return new SubmissionResult(existing.get().record(), true);
            }
            requireNotRetired(submission, submissionFingerprint, observedAt);
            if (!submission.deadlineAt().isAfter(observedAt)
                    || submission.deadlineAt().isAfter(
                    observedAt.plus(policy.maximumDeadlineHorizon()))) {
                throw conflict(TestSuiteStabilityJobConflictException.Reason.DEADLINE_INVALID,
                        "Suite-stability job deadline is outside the accepted horizon");
            }
            long global = activeCount(environment, null, observedAt);
            if (global >= policy.maximumQueued()) {
                throw conflict(TestSuiteStabilityJobConflictException.Reason.GLOBAL_QUEUE_FULL,
                        "Suite-stability environment queue is full");
            }
            long tenant = activeCount(environment,
                    submission.principal().tenantId(), observedAt);
            if (tenant >= policy.maximumQueuedPerTenant()) {
                throw conflict(TestSuiteStabilityJobConflictException.Reason.TENANT_QUEUE_FULL,
                        "Suite-stability tenant queue is full");
            }
            Instant expiresAt = safePlus(submission.deadlineAt(), policy.terminalRetention());
            StoredJob inserted = stored(
                    submission.jobId(), submission.request(), submissionFingerprint,
                    submission.requestFingerprint(), submission.classification(),
                    submission.principal(), submission.priority(),
                    TestSuiteStabilityJobRecord.Status.QUEUED, 0, observedAt,
                    submission.deadlineAt(), observedAt, observedAt, expiresAt,
                    "", -1, null, policy.generation(), "", "", "", "", "");
            try {
                insert(inserted);
            } catch (DuplicateKeyException collision) {
                throw conflict(TestSuiteStabilityJobConflictException.Reason.IDEMPOTENCY_CONFLICT,
                        "Suite-stability job identity already belongs to another intent");
            }
            return new SubmissionResult(inserted.record(), false);
        });
        return required(result, "Suite-stability job submission returned no result");
    }

    @Override
    public TestSuiteStabilityJobClaim claimNext(
            String environmentId,
            String ownerId,
            TestSuiteStabilityQueuePolicy policy) {
        String environment = environment(environmentId);
        String owner = identifier(ownerId, "ownerId");
        Objects.requireNonNull(policy, "policy");
        TestSuiteStabilityJobClaim result = mutations.execute(status -> {
            lockEnvironment(environment);
            Instant observedAt = currentTime();
            ensurePolicy(environment, policy, observedAt);
            reconcile(environment, observedAt, policy);
            if (liveRunningCount(environment, null, observedAt)
                    >= policy.maximumRunning()) {
                return TestSuiteStabilityJobClaim.noWork(observedAt);
            }
            Cursor cursor = cursor(environment, observedAt);
            List<String> eligibleTenants = eligibleTenants(environment, observedAt).stream()
                    .filter(tenant -> liveRunningCount(environment, tenant, observedAt)
                            < policy.maximumRunningPerTenant())
                    .toList();
            if (eligibleTenants.isEmpty()) {
                return TestSuiteStabilityJobClaim.noWork(observedAt);
            }
            String tenant = nextTenant(eligibleTenants, cursor.lastTenantId());
            List<StoredJob> jobs = eligibleJobs(environment, tenant, observedAt);
            StoredJob selected = jobs.stream()
                    .min(Comparator
                            .comparingInt((StoredJob value) ->
                                    effectivePriority(value.record(), observedAt, policy)).reversed()
                            .thenComparing(value -> value.record().createdAt())
                            .thenComparing(value -> value.record().jobId()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Eligible tenant had no integrity-verified queued job"));
            long epoch = Math.addExact(selected.leaseEpoch(), 1);
            Instant leaseExpiresAt = observedAt.plus(policy.leaseDuration());
            TestSuiteStabilityJobRecord.Status claimedStatus =
                    selected.record().status()
                            == TestSuiteStabilityJobRecord.Status.COMMITTING
                            ? TestSuiteStabilityJobRecord.Status.COMMITTING
                            : TestSuiteStabilityJobRecord.Status.RUNNING;
            StoredJob claimed = transition(selected,
                    claimedStatus,
                    selected.record().retryCount(), selected.record().nextEligibleAt(),
                    observedAt, selected.record().expiresAt(), owner, epoch, leaseExpiresAt,
                    "", "", selected.record().failureCode(),
                    selected.record().cancellationRequestId(),
                    selected.record().cancellationFingerprint());
            updateExact(selected, claimed);
            advanceCursor(cursor, tenant, observedAt);
            return TestSuiteStabilityJobClaim.acquired(observedAt, claimed.record(),
                    lease(claimed), effectivePriority(claimed.record(), observedAt, policy));
        });
        return required(result, "Suite-stability queue claim returned no result");
    }

    @Override
    public TestSuiteStabilityJobLeaseCheck checkAndRenew(
            TestSuiteStabilityJobLease lease,
            TestSuiteStabilityQueuePolicy policy) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(policy, "policy");
        TestSuiteStabilityJobLeaseCheck result = mutations.execute(status -> {
            lockEnvironment(lease.environmentId());
            Instant observedAt = currentTime();
            ensurePolicy(lease.environmentId(), policy, observedAt);
            Optional<StoredJob> candidate = byJobId(
                    lease.tenantId(), lease.environmentId(), lease.jobId());
            if (candidate.isEmpty() || !sameFence(candidate.get(), lease)
                    || !candidate.get().leaseExpiresAt().isAfter(observedAt)) {
                return TestSuiteStabilityJobLeaseCheck.stopped(
                        TestSuiteStabilityJobLeaseCheck.Decision.LEASE_LOST,
                        "RG.TEST.STABILITY_JOB_LEASE_LOST");
            }
            StoredJob stored = candidate.get();
            if (stored.record().status()
                    == TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED) {
                StoredJob cancelled = parentTerminal(stored,
                        TestSuiteStabilityJobRecord.Status.CANCELLED,
                        TestSuiteStabilityExecutionStop.Reason.CANCELLED, observedAt,
                        "RG.TEST.STABILITY_JOB_CANCELLED", policy,
                        stored.record().retryCount());
                updateExact(stored, cancelled);
                if (cancelled.record().status()
                        == TestSuiteStabilityJobRecord.Status.SUCCEEDED) {
                    return TestSuiteStabilityJobLeaseCheck.stopped(
                            TestSuiteStabilityJobLeaseCheck.Decision.PARENT_COMPLETED,
                            "RG.TEST.STABILITY_JOB_PARENT_COMPLETED");
                }
                return TestSuiteStabilityJobLeaseCheck.stopped(
                        TestSuiteStabilityJobLeaseCheck.Decision.CANCELLED,
                        "RG.TEST.STABILITY_JOB_CANCELLED");
            }
            if (stored.record().status() != TestSuiteStabilityJobRecord.Status.RUNNING) {
                if (stored.record().status()
                        == TestSuiteStabilityJobRecord.Status.COMMITTING) {
                    StoredJob renewed = transition(stored, stored.record().status(),
                            stored.record().retryCount(), stored.record().nextEligibleAt(),
                            observedAt, stored.record().expiresAt(), stored.ownerId(),
                            stored.leaseEpoch(), observedAt.plus(policy.leaseDuration()),
                            "", "", stored.record().failureCode(), "", "");
                    updateExact(stored, renewed);
                    return TestSuiteStabilityJobLeaseCheck.continuing(lease(renewed));
                }
                return TestSuiteStabilityJobLeaseCheck.stopped(
                        TestSuiteStabilityJobLeaseCheck.Decision.LEASE_LOST,
                        "RG.TEST.STABILITY_JOB_LEASE_LOST");
            }
            if (!stored.record().deadlineAt().isAfter(observedAt)) {
                StoredJob expired = parentTerminal(stored,
                        TestSuiteStabilityJobRecord.Status.EXPIRED,
                        TestSuiteStabilityExecutionStop.Reason.DEADLINE_EXCEEDED, observedAt,
                        "RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED", policy,
                        stored.record().retryCount());
                updateExact(stored, expired);
                if (expired.record().status()
                        == TestSuiteStabilityJobRecord.Status.SUCCEEDED) {
                    return TestSuiteStabilityJobLeaseCheck.stopped(
                            TestSuiteStabilityJobLeaseCheck.Decision.PARENT_COMPLETED,
                            "RG.TEST.STABILITY_JOB_PARENT_COMPLETED");
                }
                return TestSuiteStabilityJobLeaseCheck.stopped(
                        TestSuiteStabilityJobLeaseCheck.Decision.DEADLINE_EXCEEDED,
                        "RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED");
            }
            StoredJob renewed = transition(stored, stored.record().status(),
                    stored.record().retryCount(), stored.record().nextEligibleAt(), observedAt,
                    stored.record().expiresAt(), stored.ownerId(), stored.leaseEpoch(),
                    observedAt.plus(policy.leaseDuration()), "", "",
                    stored.record().failureCode(), stored.record().cancellationRequestId(),
                    stored.record().cancellationFingerprint());
            updateExact(stored, renewed);
            return TestSuiteStabilityJobLeaseCheck.continuing(lease(renewed));
        });
        return required(result, "Suite-stability queue heartbeat returned no result");
    }

    @Override
    public TestSuiteStabilityJobCompletionPreparation prepareCompletion(
            TestSuiteStabilityJobLease lease,
            TestSuiteStabilityQueuePolicy policy) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(policy, "policy");
        TestSuiteStabilityJobCompletionPreparation result = mutations.execute(status -> {
            lockEnvironment(lease.environmentId());
            Instant observedAt = currentTime();
            ensurePolicy(lease.environmentId(), policy, observedAt);
            Optional<StoredJob> candidate = byJobId(
                    lease.tenantId(), lease.environmentId(), lease.jobId());
            if (candidate.isEmpty() || !sameFence(candidate.get(), lease)
                    || !candidate.get().leaseExpiresAt().isAfter(observedAt)) {
                return TestSuiteStabilityJobCompletionPreparation.stopped(
                        TestSuiteStabilityJobCompletionPreparation.Decision.LEASE_LOST,
                        "RG.TEST.STABILITY_JOB_LEASE_LOST");
            }
            StoredJob stored = candidate.get();
            if (stored.record().status()
                    == TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED) {
                StoredJob cancelled = parentTerminal(stored,
                        TestSuiteStabilityJobRecord.Status.CANCELLED,
                        TestSuiteStabilityExecutionStop.Reason.CANCELLED, observedAt,
                        "RG.TEST.STABILITY_JOB_CANCELLED", policy,
                        stored.record().retryCount());
                updateExact(stored, cancelled);
                return TestSuiteStabilityJobCompletionPreparation.stopped(
                        cancelled.record().status()
                                == TestSuiteStabilityJobRecord.Status.SUCCEEDED
                                ? TestSuiteStabilityJobCompletionPreparation.Decision
                                .PARENT_COMPLETED
                                : TestSuiteStabilityJobCompletionPreparation.Decision.CANCELLED,
                        cancelled.record().status()
                                == TestSuiteStabilityJobRecord.Status.SUCCEEDED
                                ? "RG.TEST.STABILITY_JOB_PARENT_COMPLETED"
                                : "RG.TEST.STABILITY_JOB_CANCELLED");
            }
            if (!stored.record().deadlineAt().isAfter(observedAt)) {
                StoredJob expired = parentTerminal(stored,
                        TestSuiteStabilityJobRecord.Status.EXPIRED,
                        TestSuiteStabilityExecutionStop.Reason.DEADLINE_EXCEEDED, observedAt,
                        "RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED", policy,
                        stored.record().retryCount());
                updateExact(stored, expired);
                return TestSuiteStabilityJobCompletionPreparation.stopped(
                        expired.record().status()
                                == TestSuiteStabilityJobRecord.Status.SUCCEEDED
                                ? TestSuiteStabilityJobCompletionPreparation.Decision
                                .PARENT_COMPLETED
                                : TestSuiteStabilityJobCompletionPreparation.Decision
                                .DEADLINE_EXCEEDED,
                        expired.record().status()
                                == TestSuiteStabilityJobRecord.Status.SUCCEEDED
                                ? "RG.TEST.STABILITY_JOB_PARENT_COMPLETED"
                                : "RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED");
            }
            if (!Set.of(TestSuiteStabilityJobRecord.Status.RUNNING,
                    TestSuiteStabilityJobRecord.Status.COMMITTING)
                    .contains(stored.record().status())) {
                return TestSuiteStabilityJobCompletionPreparation.stopped(
                        TestSuiteStabilityJobCompletionPreparation.Decision.LEASE_LOST,
                        "RG.TEST.STABILITY_JOB_LEASE_LOST");
            }
            StoredJob committing = transition(stored,
                    TestSuiteStabilityJobRecord.Status.COMMITTING,
                    stored.record().retryCount(), stored.record().nextEligibleAt(), observedAt,
                    stored.record().expiresAt(), stored.ownerId(), stored.leaseEpoch(),
                    observedAt.plus(policy.leaseDuration()), "", "", "", "", "");
            updateExact(stored, committing);
            return TestSuiteStabilityJobCompletionPreparation.prepared(lease(committing));
        });
        return required(
                result, "Suite-stability completion preparation returned no result");
    }

    @Override
    public TestSuiteStabilityJobRecord retry(
            TestSuiteStabilityJobLease lease,
            String failureCode,
            TestSuiteStabilityQueuePolicy policy) {
        Objects.requireNonNull(lease, "lease");
        String code = failureCode(failureCode);
        Objects.requireNonNull(policy, "policy");
        TestSuiteStabilityJobRecord result = mutations.execute(status -> {
            lockEnvironment(lease.environmentId());
            Instant observedAt = currentTime();
            ensurePolicy(lease.environmentId(), policy, observedAt);
            StoredJob stored = requireLive(lease, observedAt);
            if (stored.record().status()
                    == TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED) {
                StoredJob cancelled = parentTerminal(stored,
                        TestSuiteStabilityJobRecord.Status.CANCELLED,
                        TestSuiteStabilityExecutionStop.Reason.CANCELLED, observedAt,
                        "RG.TEST.STABILITY_JOB_CANCELLED", policy,
                        stored.record().retryCount());
                updateExact(stored, cancelled);
                return cancelled.record();
            }
            if (stored.record().status()
                    == TestSuiteStabilityJobRecord.Status.COMMITTING) {
                int retryCount = Math.addExact(stored.record().retryCount(), 1);
                StoredJob recoverable = transition(stored,
                        TestSuiteStabilityJobRecord.Status.COMMITTING, retryCount,
                        observedAt.plus(policy.retryDelay(retryCount)), observedAt,
                        stored.record().expiresAt(), "", stored.leaseEpoch(), null,
                        "", "", code, "", "");
                updateExact(stored, recoverable);
                return recoverable.record();
            }
            if (!stored.record().deadlineAt().isAfter(observedAt)) {
                StoredJob expired = parentTerminal(stored,
                        TestSuiteStabilityJobRecord.Status.EXPIRED,
                        TestSuiteStabilityExecutionStop.Reason.DEADLINE_EXCEEDED, observedAt,
                        "RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED", policy,
                        stored.record().retryCount());
                updateExact(stored, expired);
                return expired.record();
            }
            int retryCount = Math.addExact(stored.record().retryCount(), 1);
            StoredJob successor;
            if (retryCount > policy.maximumRetries()) {
                successor = parentTerminal(stored,
                        TestSuiteStabilityJobRecord.Status.FAILED,
                        TestSuiteStabilityExecutionStop.Reason.WORKER_FAILED,
                        observedAt, code, policy, retryCount);
            } else {
                successor = transition(stored, TestSuiteStabilityJobRecord.Status.QUEUED,
                        retryCount, observedAt.plus(policy.retryDelay(retryCount)), observedAt,
                        stored.record().expiresAt(), "", stored.leaseEpoch(), null,
                        "", "", code, "", "");
            }
            updateExact(stored, successor);
            return successor.record();
        });
        return required(result, "Suite-stability queue retry returned no result");
    }

    @Override
    public TestSuiteStabilityJobRecord fail(
            TestSuiteStabilityJobLease lease,
            String failureCode,
            TestSuiteStabilityQueuePolicy policy) {
        Objects.requireNonNull(lease, "lease");
        String code = failureCode(failureCode);
        Objects.requireNonNull(policy, "policy");
        TestSuiteStabilityJobRecord result = mutations.execute(status -> {
            lockEnvironment(lease.environmentId());
            Instant observedAt = currentTime();
            ensurePolicy(lease.environmentId(), policy, observedAt);
            StoredJob stored = requireLive(lease, observedAt);
            if (stored.record().status()
                    == TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED) {
                StoredJob cancelled = parentTerminal(stored,
                        TestSuiteStabilityJobRecord.Status.CANCELLED,
                        TestSuiteStabilityExecutionStop.Reason.CANCELLED, observedAt,
                        "RG.TEST.STABILITY_JOB_CANCELLED", policy,
                        stored.record().retryCount());
                updateExact(stored, cancelled);
                return cancelled.record();
            }
            if (stored.record().status()
                    == TestSuiteStabilityJobRecord.Status.COMMITTING) {
                throw conflict(TestSuiteStabilityJobConflictException.Reason.TERMINAL_CONFLICT,
                        "Linearized suite-stability publication cannot be failed");
            }
            StoredJob failed = parentTerminal(stored,
                    TestSuiteStabilityJobRecord.Status.FAILED,
                    TestSuiteStabilityExecutionStop.Reason.WORKER_FAILED,
                    observedAt, code, policy, stored.record().retryCount());
            updateExact(stored, failed);
            return failed.record();
        });
        return required(result, "Suite-stability queue failure returned no result");
    }

    @Override
    public TestSuiteStabilityJobRecord complete(
            TestSuiteStabilityJobLease lease,
            String stabilityRunId,
            String evidenceFingerprint,
            TestSuiteStabilityQueuePolicy policy) {
        Objects.requireNonNull(lease, "lease");
        String runId = identifier(stabilityRunId, "stabilityRunId");
        String evidence = fingerprint(evidenceFingerprint, "evidenceFingerprint");
        Objects.requireNonNull(policy, "policy");
        TestSuiteStabilityJobRecord result = mutations.execute(status -> {
            lockEnvironment(lease.environmentId());
            Instant observedAt = currentTime();
            ensurePolicy(lease.environmentId(), policy, observedAt);
            StoredJob stored = requireLive(lease, observedAt);
            if (stored.record().status() == TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED) {
                throw conflict(TestSuiteStabilityJobConflictException.Reason.TERMINAL_CONFLICT,
                        "Cancelled suite-stability job cannot publish a terminal result");
            }
            if (stored.record().status()
                    != TestSuiteStabilityJobRecord.Status.COMMITTING) {
                throw conflict(TestSuiteStabilityJobConflictException.Reason.TERMINAL_CONFLICT,
                        "Suite-stability job did not linearize terminal publication");
            }
            TestSuiteStabilityJobParentAuthority.Resolution parent =
                    requireCompletedParent(stored.record(), runId, evidence);
            if (parent.outcome()
                    != TestSuiteStabilityJobParentAuthority.Outcome.COMPLETED
                    || !parent.stabilityRunId().equals(runId)
                    || !parent.evidenceFingerprint().equals(evidence)) {
                throw new IllegalStateException(
                        "Parent authority returned a contradictory completion proof");
            }
            StoredJob completed = transition(stored,
                    TestSuiteStabilityJobRecord.Status.SUCCEEDED,
                    stored.record().retryCount(), stored.record().nextEligibleAt(), observedAt,
                    observedAt.plus(policy.terminalRetention()), "", stored.leaseEpoch(), null,
                    parent.stabilityRunId(), parent.evidenceFingerprint(), "", "", "");
            updateExact(stored, completed);
            return completed.record();
        });
        return required(result, "Suite-stability queue completion returned no result");
    }

    @Override
    public TestSuiteStabilityJobRecord cancel(
            String tenantId,
            String environmentId,
            String jobId,
            String clientRequestId,
            String commandFingerprint,
            TestSuiteStabilityQueuePolicy policy) {
        String tenant = identifier(tenantId, "tenantId");
        String environment = environment(environmentId);
        String job = identifier(jobId, "jobId");
        String requestId = identifier(clientRequestId, "clientRequestId");
        String cancellationFingerprint = fingerprint(
                commandFingerprint, "commandFingerprint");
        Objects.requireNonNull(policy, "policy");
        TestSuiteStabilityJobRecord result = mutations.execute(status -> {
            lockEnvironment(environment);
            Instant observedAt = currentTime();
            ensurePolicy(environment, policy, observedAt);
            StoredJob stored = byJobId(tenant, environment, job).orElseThrow(() ->
                    conflict(TestSuiteStabilityJobConflictException.Reason.NOT_FOUND,
                            "Suite-stability job was not found in the authorized scope"));
            if (!stored.record().cancellationRequestId().isBlank()) {
                if (!stored.record().cancellationRequestId().equals(requestId)
                        || !stored.record().cancellationFingerprint()
                        .equals(cancellationFingerprint)) {
                    throw conflict(
                            TestSuiteStabilityJobConflictException.Reason.CANCELLATION_CONFLICT,
                            "Cancellation identity already represents another command");
                }
                return stored.record();
            }
            if (stored.record().status().terminal()) {
                return stored.record();
            }
            if (stored.record().status()
                    == TestSuiteStabilityJobRecord.Status.COMMITTING) {
                return stored.record();
            }
            TestSuiteStabilityJobRecord.Status next = stored.record().status()
                    == TestSuiteStabilityJobRecord.Status.QUEUED
                    ? TestSuiteStabilityJobRecord.Status.CANCELLED
                    : TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED;
            TestSuiteStabilityJobParentAuthority.Resolution parent = stopParent(
                    stored.record(), TestSuiteStabilityExecutionStop.Reason.CANCELLED,
                    "RG.TEST.STABILITY_JOB_CANCELLED", policy.terminalRetention());
            StoredJob cancelled;
            if (parent.outcome()
                    == TestSuiteStabilityJobParentAuthority.Outcome.COMPLETED) {
                cancelled = parentCompleted(stored, parent, observedAt, policy,
                        stored.record().retryCount());
            } else {
                Instant expiresAt = next.terminal()
                        ? observedAt.plus(policy.terminalRetention())
                        : stored.record().expiresAt();
                cancelled = transition(stored, next, stored.record().retryCount(),
                        stored.record().nextEligibleAt(), observedAt, expiresAt,
                        stored.ownerId(), stored.leaseEpoch(), stored.leaseExpiresAt(), "", "",
                        "RG.TEST.STABILITY_JOB_CANCELLED", requestId, cancellationFingerprint);
            }
            updateExact(stored, cancelled);
            return cancelled.record();
        });
        return required(result, "Suite-stability queue cancellation returned no result");
    }

    @Override
    public Optional<TestSuiteStabilityJobRecord> find(
            String tenantId, String environmentId, String jobId) {
        return byJobId(identifier(tenantId, "tenantId"), environment(environmentId),
                identifier(jobId, "jobId")).map(StoredJob::record);
    }

    @Override
    public TestSuiteStabilityQueueSnapshot observe(String environmentId) {
        String environment = environment(environmentId);
        TestSuiteStabilityQueueSnapshot snapshot = jdbc.queryForObject("""
                SELECT CURRENT_TIMESTAMP AS observed_at,
                  COUNT(*) AS all_records,
                  COALESCE(SUM(CASE WHEN status = 'QUEUED' THEN 1 ELSE 0 END), 0) AS queued,
                  COALESCE(SUM(CASE WHEN status = 'RUNNING' THEN 1 ELSE 0 END), 0) AS running,
                  COALESCE(SUM(CASE WHEN status = 'CANCEL_REQUESTED' THEN 1 ELSE 0 END), 0)
                    AS cancel_requested,
                  COALESCE(SUM(CASE WHEN status = 'COMMITTING' THEN 1 ELSE 0 END), 0)
                    AS committing,
                  COALESCE(SUM(CASE WHEN status = 'SUCCEEDED' THEN 1 ELSE 0 END), 0) AS succeeded,
                  COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed,
                  COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelled,
                  COALESCE(SUM(CASE WHEN status = 'EXPIRED' THEN 1 ELSE 0 END), 0) AS expired,
                  COALESCE(SUM(CASE WHEN status = 'QUARANTINED' THEN 1 ELSE 0 END), 0)
                    AS quarantined,
                  MIN(CASE WHEN status = 'QUEUED' THEN created_at ELSE NULL END) AS oldest_queued_at,
                  COALESCE(SUM(CASE
                    WHEN status IN ('RUNNING', 'CANCEL_REQUESTED', 'COMMITTING')
                      AND (lease_expires_at IS NULL
                        OR lease_expires_at <= CURRENT_TIMESTAMP) THEN 1 ELSE 0 END), 0)
                    AS expired_live_leases,
                  COUNT(DISTINCT CASE WHEN status = 'QUEUED' THEN tenant_id ELSE NULL END)
                    AS distinct_queued_tenants
                FROM rg_test_suite_stability_jobs
                WHERE environment_id = ?
                """, (rs, rowNum) -> queueSnapshot(rs), environment);
        return required(snapshot, "Suite-stability queue observation returned no result");
    }

    @Override
    public TestSuiteStabilityJobRetentionAttempt retainExpired(
            Duration tombstoneRetention, int limit) {
        Duration retention = boundedTombstoneRetention(tombstoneRetention);
        int bounded = boundedRetentionPage(limit);
        Optional<RetentionLease> lease = acquireRetentionLease();
        if (lease.isEmpty()) {
            return TestSuiteStabilityJobRetentionAttempt.leaseBusy();
        }
        try {
            return retainClaimedPage(lease.orElseThrow(), retention, bounded);
        } catch (RuntimeException failure) {
            try {
                releaseRetentionLease(lease.orElseThrow());
            } catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    @Override
    public TestSuiteStabilityJobRetentionSnapshot observeRetention() {
        TestSuiteStabilityJobRetentionSnapshot snapshot = jdbc.queryForObject("""
                SELECT s.*, CURRENT_TIMESTAMP AS observed_at,
                  (SELECT COUNT(*) FROM rg_test_suite_stability_jobs)
                    AS detailed_job_records,
                  (SELECT COUNT(*) FROM rg_test_suite_stability_job_tombstones)
                    AS tombstone_records,
                  (SELECT COUNT(*) FROM rg_test_suite_stability_jobs
                   WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'QUARANTINED')
                     AND expires_at <= CURRENT_TIMESTAMP) AS overdue_job_records,
                  (SELECT MIN(expires_at) FROM rg_test_suite_stability_jobs
                   WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'QUARANTINED')
                     AND expires_at <= CURRENT_TIMESTAMP) AS oldest_overdue_job,
                  (SELECT COUNT(*) FROM rg_test_suite_stability_job_tombstones
                   WHERE expires_at <= CURRENT_TIMESTAMP) AS expired_tombstone_records,
                  (SELECT MIN(expires_at) FROM rg_test_suite_stability_job_tombstones
                   WHERE expires_at <= CURRENT_TIMESTAMP) AS oldest_expired_tombstone
                FROM rg_test_suite_stability_job_retention s
                WHERE s.job_name = ?
                """, (rs, rowNum) -> {
            RetentionState state = retentionState(rs);
            requireValidRetentionState(state);
            Timestamp lastSuccess = rs.getTimestamp("last_success_at");
            Timestamp oldestJob = rs.getTimestamp("oldest_overdue_job");
            Timestamp oldestTombstone = rs.getTimestamp("oldest_expired_tombstone");
            return new TestSuiteStabilityJobRetentionSnapshot(
                    state.leaseOwner(), state.leaseEpoch(), state.leaseUntil(),
                    state.revision(), state.totalJobsTombstoned(),
                    state.totalTombstonesPurged(), rs.getLong("detailed_job_records"),
                    rs.getLong("tombstone_records"), rs.getLong("overdue_job_records"),
                    rs.getLong("expired_tombstone_records"),
                    oldestJob == null ? null : oldestJob.toInstant(),
                    oldestTombstone == null ? null : oldestTombstone.toInstant(),
                    lastSuccess == null ? null : lastSuccess.toInstant(),
                    rs.getTimestamp("observed_at").toInstant());
        }, RETENTION_JOB);
        return required(snapshot, "Stability-job retention observation returned no result");
    }

    Optional<RetentionLease> acquireRetentionLease() {
        Optional<RetentionLease> result = mutations.execute(status -> {
            RetentionState current = retentionState(true);
            Instant now = currentTime();
            if (current.leaseUntil().isAfter(now)) {
                return Optional.empty();
            }
            String token = UUID.randomUUID().toString();
            RetentionState next = new RetentionState(
                    retentionOwnerId, token, Math.addExact(current.leaseEpoch(), 1),
                    safePlus(now, retentionLeaseDuration),
                    Math.addExact(current.revision(), 1),
                    current.totalJobsTombstoned(), current.totalTombstonesPurged(),
                    current.lastSuccessAt(), now, "");
            next = next.withFingerprint(retentionStateFingerprint(next));
            int updated = jdbc.update("""
                    UPDATE rg_test_suite_stability_job_retention
                    SET lease_owner = ?, lease_token = ?, lease_epoch = ?, lease_until = ?,
                        revision = ?, updated_at = ?, record_fingerprint = ?
                    WHERE job_name = ? AND revision = ? AND record_fingerprint = ?
                    """, next.leaseOwner(), next.leaseToken(), next.leaseEpoch(),
                    Timestamp.from(next.leaseUntil()), next.revision(),
                    Timestamp.from(next.updatedAt()), next.recordFingerprint(),
                    RETENTION_JOB, current.revision(), current.recordFingerprint());
            if (updated != 1) {
                throw new IllegalStateException(
                        "Stability-job retention lease changed concurrently");
            }
            return Optional.of(new RetentionLease(next.leaseOwner(), next.leaseToken(),
                    next.leaseEpoch(), next.leaseUntil()));
        });
        return required(result, "Stability-job retention lease returned no result");
    }

    TestSuiteStabilityJobRetentionAttempt retainClaimedPage(
            RetentionLease lease, Duration retention, int bounded) {
        RetentionLease requiredLease = Objects.requireNonNull(lease, "lease");
        TestSuiteStabilityJobRetentionAttempt attempt = mutations.execute(status -> {
            RetentionState current = retentionState(true);
            Instant observedAt = currentTime();
            requireLiveRetentionFence(current, requiredLease, observedAt);
            List<StoredJob> expired = jdbc.query("""
                    SELECT * FROM rg_test_suite_stability_jobs
                    WHERE status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'QUARANTINED')
                      AND expires_at <= ?
                    ORDER BY expires_at, job_id LIMIT ? FOR UPDATE
                    """, this::storedJob, Timestamp.from(observedAt), bounded);
            int tombstoned = 0;
            for (StoredJob job : expired) {
                insertTombstone(job, observedAt, retention);
                int removed = jdbc.update("""
                        DELETE FROM rg_test_suite_stability_jobs
                        WHERE job_id = ? AND record_fingerprint = ?
                          AND status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'EXPIRED', 'QUARANTINED')
                        """, job.record().jobId(), job.record().recordFingerprint());
                if (removed != 1) {
                    throw new IllegalStateException(
                            "Stability-job retention source fence was rejected");
                }
                tombstoned = Math.addExact(tombstoned, 1);
            }
            List<StoredTombstone> expiredTombstones = jdbc.query("""
                    SELECT * FROM rg_test_suite_stability_job_tombstones
                    WHERE expires_at <= ?
                    ORDER BY expires_at, request_key_id, request_key LIMIT ? FOR UPDATE
                    """, this::storedTombstone, Timestamp.from(observedAt), bounded);
            int purged = 0;
            for (StoredTombstone tombstone : expiredTombstones) {
                int removed = jdbc.update("""
                        DELETE FROM rg_test_suite_stability_job_tombstones
                        WHERE tenant_id = ? AND environment_id = ?
                          AND request_key_id = ? AND request_key = ?
                          AND record_fingerprint = ?
                        """, tombstone.tenantId(), tombstone.environmentId(),
                        tombstone.requestKeyId(), tombstone.requestKey(),
                        tombstone.recordFingerprint());
                if (removed != 1) {
                    throw new IllegalStateException(
                            "Stability-job tombstone retention fence was rejected");
                }
                purged = Math.addExact(purged, 1);
            }
            Instant completedAt = currentTime();
            requireLiveRetentionFence(current, requiredLease, completedAt);
            RetentionState next = new RetentionState(
                    "", "", current.leaseEpoch(), Instant.EPOCH,
                    Math.addExact(current.revision(), 1),
                    Math.addExact(current.totalJobsTombstoned(), tombstoned),
                    Math.addExact(current.totalTombstonesPurged(), purged),
                    completedAt, completedAt, "");
            next = next.withFingerprint(retentionStateFingerprint(next));
            int stateUpdated = jdbc.update("""
                    UPDATE rg_test_suite_stability_job_retention
                    SET lease_owner = '', lease_token = '', lease_until = ?,
                        revision = ?, total_jobs_tombstoned = ?,
                        total_tombstones_purged = ?, last_success_at = ?,
                        updated_at = ?, record_fingerprint = ?
                    WHERE job_name = ? AND lease_owner = ? AND lease_token = ?
                      AND lease_epoch = ? AND revision = ? AND record_fingerprint = ?
                    """, Timestamp.from(next.leaseUntil()), next.revision(),
                    next.totalJobsTombstoned(), next.totalTombstonesPurged(),
                    Timestamp.from(next.lastSuccessAt()), Timestamp.from(next.updatedAt()),
                    next.recordFingerprint(), RETENTION_JOB, requiredLease.ownerId(),
                    requiredLease.token(), requiredLease.epoch(), current.revision(),
                    current.recordFingerprint());
            if (stateUpdated != 1) {
                throw new IllegalStateException(
                        "Stability-job retention fence changed before commit");
            }
            return TestSuiteStabilityJobRetentionAttempt.completed(
                    new TestSuiteStabilityJobRetentionResult(
                            tombstoned, purged, completedAt));
        });
        return required(attempt, "Stability-job retention page returned no result");
    }

    private void reconcile(
            String environment,
            Instant observedAt,
            TestSuiteStabilityQueuePolicy policy) {
        List<StoredJob> stale = jdbc.query("""
                SELECT * FROM rg_test_suite_stability_jobs
                WHERE environment_id = ? AND (
                    (status = 'QUEUED' AND deadline_at <= ?)
                    OR (status IN ('RUNNING', 'CANCEL_REQUESTED')
                        AND lease_expires_at <= ?)
                )
                ORDER BY updated_at, job_id
                FETCH FIRST ? ROWS ONLY
                """, this::storedJob, environment, Timestamp.from(observedAt),
                Timestamp.from(observedAt), RECONCILIATION_BATCH);
        for (StoredJob job : stale) {
            StoredJob successor;
            if (job.record().status() == TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED) {
                successor = parentTerminal(job,
                        TestSuiteStabilityJobRecord.Status.CANCELLED,
                        TestSuiteStabilityExecutionStop.Reason.CANCELLED, observedAt,
                        "RG.TEST.STABILITY_JOB_CANCELLED", policy,
                        job.record().retryCount());
            } else if (!job.record().deadlineAt().isAfter(observedAt)) {
                successor = parentTerminal(job,
                        TestSuiteStabilityJobRecord.Status.EXPIRED,
                        TestSuiteStabilityExecutionStop.Reason.DEADLINE_EXCEEDED, observedAt,
                        "RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED", policy,
                        job.record().retryCount());
            } else {
                int retryCount = Math.addExact(job.record().retryCount(), 1);
                successor = retryCount > policy.maximumRetries()
                        ? parentTerminal(job, TestSuiteStabilityJobRecord.Status.FAILED,
                        TestSuiteStabilityExecutionStop.Reason.WORKER_FAILED, observedAt,
                        "RG.TEST.STABILITY_JOB_WORKER_LEASE_EXPIRED", policy, retryCount)
                        : transition(job, TestSuiteStabilityJobRecord.Status.QUEUED,
                        retryCount, observedAt.plus(policy.retryDelay(retryCount)), observedAt,
                        job.record().expiresAt(), "", job.leaseEpoch(), null, "", "",
                        "RG.TEST.STABILITY_JOB_WORKER_LEASE_EXPIRED", "", "");
            }
            updateExact(job, successor);
        }
    }

    private void ensurePolicy(
            String environment,
            TestSuiteStabilityQueuePolicy policy,
            Instant observedAt) {
        List<PolicyRow> rows = jdbc.query("""
                SELECT policy_generation, policy_fingerprint
                FROM rg_test_suite_stability_queue_policies
                WHERE environment_id = ?
                """, (rs, rowNum) -> new PolicyRow(
                rs.getLong("policy_generation"), rs.getString("policy_fingerprint")),
                environment);
        if (rows.isEmpty()) {
            jdbc.update("""
                    INSERT INTO rg_test_suite_stability_queue_policies (
                        environment_id, policy_generation, policy_fingerprint, updated_at
                    ) VALUES (?, ?, ?, ?)
                    """, environment, policy.generation(), policy.fingerprint(),
                    Timestamp.from(observedAt));
            return;
        }
        PolicyRow current = rows.getFirst();
        if (current.generation() == policy.generation()
                && current.fingerprint().equals(policy.fingerprint())) {
            return;
        }
        Long active = jdbc.queryForObject("""
                SELECT COUNT(*) FROM rg_test_suite_stability_jobs
                WHERE environment_id = ?
                  AND status IN ('QUEUED', 'RUNNING', 'CANCEL_REQUESTED', 'COMMITTING')
                """, Long.class, environment);
        if (active == null || active > 0 || policy.generation() <= current.generation()) {
            throw conflict(TestSuiteStabilityJobConflictException.Reason.POLICY_DRIFT,
                    "Suite-stability queue replicas do not share one active policy");
        }
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_queue_policies
                SET policy_generation = ?, policy_fingerprint = ?, updated_at = ?
                WHERE environment_id = ? AND policy_generation = ? AND policy_fingerprint = ?
                """, policy.generation(), policy.fingerprint(), Timestamp.from(observedAt),
                environment, current.generation(), current.fingerprint());
        if (updated != 1) {
            throw conflict(TestSuiteStabilityJobConflictException.Reason.POLICY_DRIFT,
                    "Suite-stability queue policy changed concurrently");
        }
    }

    private Cursor cursor(String environment, Instant observedAt) {
        List<Cursor> rows = jdbc.query("""
                SELECT environment_id, last_tenant_id, cycle_epoch
                FROM rg_test_suite_stability_queue_cursors
                WHERE environment_id = ? FOR UPDATE
                """, (rs, rowNum) -> new Cursor(rs.getString("environment_id"),
                rs.getString("last_tenant_id"), rs.getLong("cycle_epoch")), environment);
        if (rows.isEmpty()) {
            try {
                jdbc.update("""
                        INSERT INTO rg_test_suite_stability_queue_cursors (
                            environment_id, last_tenant_id, cycle_epoch, updated_at
                        ) VALUES (?, '', 0, ?)
                        """, environment, Timestamp.from(observedAt));
            } catch (DuplicateKeyException concurrentInsert) {
                // The environment lock normally prevents this; re-read protects older databases.
            }
            rows = jdbc.query("""
                    SELECT environment_id, last_tenant_id, cycle_epoch
                    FROM rg_test_suite_stability_queue_cursors
                    WHERE environment_id = ? FOR UPDATE
                    """, (rs, rowNum) -> new Cursor(rs.getString("environment_id"),
                    rs.getString("last_tenant_id"), rs.getLong("cycle_epoch")), environment);
        }
        if (rows.size() != 1) {
            throw new IllegalStateException("Suite-stability queue cursor is unavailable");
        }
        return rows.getFirst();
    }

    private void advanceCursor(Cursor cursor, String tenant, Instant observedAt) {
        long epoch = tenant.compareTo(cursor.lastTenantId()) <= 0
                ? Math.addExact(cursor.cycleEpoch(), 1) : cursor.cycleEpoch();
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_queue_cursors
                SET last_tenant_id = ?, cycle_epoch = ?, updated_at = ?
                WHERE environment_id = ? AND last_tenant_id = ? AND cycle_epoch = ?
                """, tenant, epoch, Timestamp.from(observedAt), cursor.environmentId(),
                cursor.lastTenantId(), cursor.cycleEpoch());
        if (updated != 1) {
            throw new IllegalStateException("Suite-stability fairness cursor changed while locked");
        }
    }

    private List<String> eligibleTenants(String environment, Instant observedAt) {
        return jdbc.query("""
                SELECT DISTINCT tenant_id
                FROM rg_test_suite_stability_jobs
                WHERE environment_id = ? AND (
                    (status = 'QUEUED' AND next_eligible_at <= ? AND deadline_at > ?)
                    OR (status = 'COMMITTING' AND next_eligible_at <= ?
                        AND (lease_expires_at IS NULL OR lease_expires_at <= ?))
                )
                ORDER BY tenant_id
                """, (rs, rowNum) -> rs.getString("tenant_id"), environment,
                Timestamp.from(observedAt), Timestamp.from(observedAt),
                Timestamp.from(observedAt), Timestamp.from(observedAt));
    }

    private List<StoredJob> eligibleJobs(
            String environment, String tenant, Instant observedAt) {
        return jdbc.query("""
                SELECT * FROM rg_test_suite_stability_jobs
                WHERE environment_id = ? AND tenant_id = ? AND (
                    (status = 'QUEUED' AND next_eligible_at <= ? AND deadline_at > ?)
                    OR (status = 'COMMITTING' AND next_eligible_at <= ?
                        AND (lease_expires_at IS NULL OR lease_expires_at <= ?))
                )
                ORDER BY created_at, job_id
                """, this::storedJob, environment, tenant, Timestamp.from(observedAt),
                Timestamp.from(observedAt), Timestamp.from(observedAt),
                Timestamp.from(observedAt));
    }

    private long activeCount(String environment, String tenant, Instant observedAt) {
        String tenantClause = tenant == null ? "" : " AND tenant_id = ?";
        String sql = """
                SELECT COUNT(*) FROM rg_test_suite_stability_jobs
                WHERE environment_id = ? AND (
                    (status = 'QUEUED' AND deadline_at > ?)
                    OR status = 'COMMITTING'
                    OR (status IN ('RUNNING', 'CANCEL_REQUESTED')
                        AND lease_expires_at > ?)
                )
                """ + tenantClause;
        Long count = tenant == null
                ? jdbc.queryForObject(sql, Long.class, environment,
                Timestamp.from(observedAt), Timestamp.from(observedAt))
                : jdbc.queryForObject(sql, Long.class, environment,
                Timestamp.from(observedAt), Timestamp.from(observedAt), tenant);
        return count == null ? 0 : count;
    }

    private long liveRunningCount(String environment, String tenant, Instant observedAt) {
        String tenantClause = tenant == null ? "" : " AND tenant_id = ?";
        String sql = """
                SELECT COUNT(*) FROM rg_test_suite_stability_jobs
                WHERE environment_id = ?
                  AND status IN ('RUNNING', 'CANCEL_REQUESTED', 'COMMITTING')
                  AND lease_expires_at > ?
                """ + tenantClause;
        Long count = tenant == null
                ? jdbc.queryForObject(sql, Long.class, environment, Timestamp.from(observedAt))
                : jdbc.queryForObject(sql, Long.class, environment,
                Timestamp.from(observedAt), tenant);
        return count == null ? 0 : count;
    }

    private StoredJob requireLive(TestSuiteStabilityJobLease lease, Instant observedAt) {
        StoredJob stored = byJobId(lease.tenantId(), lease.environmentId(), lease.jobId())
                .orElseThrow(() -> conflict(
                        TestSuiteStabilityJobConflictException.Reason.LEASE_LOST,
                        "Suite-stability job lease no longer exists"));
        if (!sameFence(stored, lease) || !stored.leaseExpiresAt().isAfter(observedAt)
                || !Set.of(TestSuiteStabilityJobRecord.Status.RUNNING,
                TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED,
                TestSuiteStabilityJobRecord.Status.COMMITTING)
                .contains(stored.record().status())) {
            throw conflict(TestSuiteStabilityJobConflictException.Reason.LEASE_LOST,
                    "Suite-stability job lease was lost");
        }
        return stored;
    }

    private Optional<StoredJob> byClientRequestId(
            String tenant, String environment, String clientRequestId) {
        List<StoredJob> rows = jdbc.query("""
                SELECT * FROM rg_test_suite_stability_jobs
                WHERE tenant_id = ? AND environment_id = ? AND client_request_id = ?
                """, this::storedJob, tenant, environment, clientRequestId);
        return one(rows, "Duplicate suite-stability job request rows");
    }

    private void requireNotRetired(
            TestSuiteStabilityJobSubmission submission,
            String submissionFingerprint,
            Instant observedAt) {
        String tenant = submission.principal().tenantId();
        String environment = submission.principal().environmentId();
        String requestId = submission.request().clientRequestId();
        List<StoredTombstone> live = new ArrayList<>();
        for (TestSuiteStabilityJobRequestKeyProtector.IndexKey candidate
                : requestKeys.lookupCandidates(tenant, environment, requestId)) {
            List<StoredTombstone> rows = tombstone(candidate, tenant, environment);
            if (rows.size() > 1) {
                throw new IllegalStateException(
                        "Duplicate suite-stability job tombstone rows");
            }
            for (StoredTombstone tombstone : rows) {
                if (!requestKeys.matches(tenant, environment, requestId,
                        tombstone.requestKeyId(), tombstone.requestKey())) {
                    throw new IllegalStateException(
                            "Stored suite-stability job tombstone request index failed");
                }
                if (tombstone.expiresAt().isAfter(observedAt)) {
                    live.add(tombstone);
                } else {
                    deleteTombstoneExact(tombstone);
                }
            }
        }
        if (live.size() > 1) {
            throw new IllegalStateException(
                    "One suite-stability request has multiple live tombstones");
        }
        if (live.isEmpty()) {
            return;
        }
        StoredTombstone tombstone = live.getFirst();
        if (tombstone.submissionFingerprint().equals(submissionFingerprint)) {
            throw conflict(
                    TestSuiteStabilityJobConflictException.Reason.REPLAY_WINDOW_EXPIRED,
                    "Suite-stability job replay detail has expired");
        }
        throw conflict(TestSuiteStabilityJobConflictException.Reason.IDEMPOTENCY_CONFLICT,
                "clientRequestId identifies a retired stability job intent");
    }

    private List<StoredTombstone> tombstone(
            TestSuiteStabilityJobRequestKeyProtector.IndexKey candidate,
            String tenant,
            String environment) {
        return jdbc.query("""
                SELECT * FROM rg_test_suite_stability_job_tombstones
                WHERE tenant_id = ? AND environment_id = ?
                  AND request_key_id = ? AND request_key = ?
                FOR UPDATE
                """, this::storedTombstone, tenant, environment,
                candidate.keyId(), candidate.value());
    }

    private void insertTombstone(
            StoredJob job, Instant observedAt, Duration retention) {
        if (!Set.of(TestSuiteStabilityJobRecord.Status.SUCCEEDED,
                TestSuiteStabilityJobRecord.Status.FAILED,
                TestSuiteStabilityJobRecord.Status.CANCELLED,
                TestSuiteStabilityJobRecord.Status.EXPIRED,
                TestSuiteStabilityJobRecord.Status.QUARANTINED)
                .contains(job.record().status())) {
            throw new IllegalStateException(
                    "Only terminal suite-stability jobs may become tombstones");
        }
        TestSuiteStabilityJobRequestKeyProtector.IndexKey requestKey = requestKeys.protect(
                job.record().tenantId(), job.record().environmentId(),
                job.record().request().clientRequestId());
        List<StoredTombstone> existing = tombstone(requestKey,
                job.record().tenantId(), job.record().environmentId());
        if (existing.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate suite-stability job tombstone rows");
        }
        if (!existing.isEmpty()) {
            StoredTombstone retained = existing.getFirst();
            if (retained.expiresAt().isAfter(observedAt)) {
                throw new IllegalStateException(
                        "Live tombstone overlaps retained suite-stability job detail");
            }
            deleteTombstoneExact(retained);
        }
        Instant expiresAt = safePlus(observedAt, retention);
        String recordFingerprint = tombstoneFingerprint(
                job.record().tenantId(), job.record().environmentId(),
                requestKey.keyId(), requestKey.value(), job.submissionFingerprint(),
                observedAt, expiresAt, TOMBSTONE_VERSION);
        try {
            int inserted = jdbc.update("""
                    INSERT INTO rg_test_suite_stability_job_tombstones (
                        tenant_id, environment_id, request_key_id, request_key,
                        submission_fingerprint, tombstoned_at, expires_at,
                        record_version, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, job.record().tenantId(), job.record().environmentId(),
                    requestKey.keyId(), requestKey.value(), job.submissionFingerprint(),
                    Timestamp.from(observedAt), Timestamp.from(expiresAt),
                    TOMBSTONE_VERSION, recordFingerprint);
            if (inserted != 1) {
                throw new IllegalStateException(
                        "Suite-stability job tombstone was not persisted");
            }
        } catch (DuplicateKeyException collision) {
            throw new IllegalStateException(
                    "Suite-stability job tombstone changed while retention was locked",
                    collision);
        }
    }

    private void deleteTombstoneExact(StoredTombstone tombstone) {
        int removed = jdbc.update("""
                DELETE FROM rg_test_suite_stability_job_tombstones
                WHERE tenant_id = ? AND environment_id = ?
                  AND request_key_id = ? AND request_key = ?
                  AND record_fingerprint = ?
                """, tombstone.tenantId(), tombstone.environmentId(),
                tombstone.requestKeyId(), tombstone.requestKey(),
                tombstone.recordFingerprint());
        if (removed != 1) {
            throw new IllegalStateException(
                    "Suite-stability job tombstone changed while locked");
        }
    }

    private StoredTombstone storedTombstone(ResultSet rs, int rowNum) throws SQLException {
        try {
            String tenant = identifier(rs.getString("tenant_id"), "tenantId");
            String environment = environment(rs.getString("environment_id"));
            TestSuiteStabilityJobRequestKeyProtector.IndexKey requestKey =
                    new TestSuiteStabilityJobRequestKeyProtector.IndexKey(
                            rs.getString("request_key_id"), rs.getString("request_key"));
            String submissionFingerprint = fingerprint(
                    rs.getString("submission_fingerprint"), "submissionFingerprint");
            Instant tombstonedAt = rs.getTimestamp("tombstoned_at").toInstant();
            Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
            int recordVersion = rs.getInt("record_version");
            String recordFingerprint = fingerprint(
                    rs.getString("record_fingerprint"), "recordFingerprint");
            if (recordVersion != TOMBSTONE_VERSION || !expiresAt.isAfter(tombstonedAt)) {
                throw new IllegalStateException(
                        "Stored suite-stability job tombstone lifecycle is invalid");
            }
            String expected = tombstoneFingerprint(tenant, environment,
                    requestKey.keyId(), requestKey.value(), submissionFingerprint,
                    tombstonedAt, expiresAt, recordVersion);
            if (!expected.equals(recordFingerprint)) {
                throw new IllegalStateException(
                        "Stored suite-stability job tombstone integrity failed");
            }
            return new StoredTombstone(tenant, environment, requestKey.keyId(),
                    requestKey.value(), submissionFingerprint, tombstonedAt, expiresAt,
                    recordVersion, recordFingerprint);
        } catch (IllegalArgumentException corrupt) {
            throw new IllegalStateException(
                    "Stored suite-stability job tombstone is corrupt", corrupt);
        }
    }

    private String tombstoneFingerprint(
            String tenantId,
            String environmentId,
            String requestKeyId,
            String requestKey,
            String submissionFingerprint,
            Instant tombstonedAt,
            Instant expiresAt,
            int recordVersion) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", "bloge.testSuiteStabilityJobTombstone.v1");
        material.put("tenantId", tenantId);
        material.put("environmentId", environmentId);
        material.put("requestKeyId", requestKeyId);
        material.put("requestKey", requestKey);
        material.put("submissionFingerprint", submissionFingerprint);
        material.put("tombstonedAt", tombstonedAt);
        material.put("expiresAt", expiresAt);
        material.put("recordVersion", recordVersion);
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private void requireRetainedTombstoneKeys() {
        List<String> retainedKeyIds = jdbc.query("""
                SELECT DISTINCT request_key_id
                FROM rg_test_suite_stability_job_tombstones
                WHERE expires_at > CURRENT_TIMESTAMP
                """, (rs, rowNum) -> rs.getString(1));
        for (String keyId : retainedKeyIds) {
            try {
                if (!requestKeys.containsKey(keyId)) {
                    throw new IllegalStateException(
                            "A live suite-stability tombstone references an unavailable key");
                }
            } catch (IllegalArgumentException malformed) {
                throw new IllegalStateException(
                        "A live suite-stability tombstone has an invalid key id", malformed);
            }
        }
    }

    private void initializeRetentionState() {
        try {
            Instant now = currentTime();
            RetentionState initial = new RetentionState(
                    "", "", 0, Instant.EPOCH, 0, 0, 0, null, now, "");
            initial = initial.withFingerprint(retentionStateFingerprint(initial));
            jdbc.update("""
                    INSERT INTO rg_test_suite_stability_job_retention (
                        job_name, lease_owner, lease_token, lease_epoch, lease_until,
                        revision, total_jobs_tombstoned, total_tombstones_purged,
                        last_success_at, updated_at, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, RETENTION_JOB, initial.leaseOwner(), initial.leaseToken(),
                    initial.leaseEpoch(), Timestamp.from(initial.leaseUntil()),
                    initial.revision(), initial.totalJobsTombstoned(),
                    initial.totalTombstonesPurged(), initial.lastSuccessAt(),
                    Timestamp.from(initial.updatedAt()), initial.recordFingerprint());
        } catch (DuplicateKeyException alreadyInitialized) {
            // Another replica initialized the singleton maintenance row first.
        }
    }

    private RetentionState retentionState(boolean lock) {
        List<RetentionState> rows = jdbc.query("""
                SELECT lease_owner, lease_token, lease_epoch, lease_until, revision,
                       total_jobs_tombstoned, total_tombstones_purged,
                       last_success_at, updated_at, record_fingerprint
                FROM rg_test_suite_stability_job_retention
                WHERE job_name = ?
                """ + (lock ? " FOR UPDATE" : ""),
                (rs, rowNum) -> retentionState(rs), RETENTION_JOB);
        if (rows.size() != 1) {
            throw new IllegalStateException(
                    "Stability-job retention state is not initialized");
        }
        RetentionState state = rows.getFirst();
        requireValidRetentionState(state);
        return state;
    }

    private static RetentionState retentionState(ResultSet rs) throws SQLException {
        Timestamp lastSuccess = rs.getTimestamp("last_success_at");
        return new RetentionState(
                rs.getString("lease_owner"), rs.getString("lease_token"),
                rs.getLong("lease_epoch"), rs.getTimestamp("lease_until").toInstant(),
                rs.getLong("revision"), rs.getLong("total_jobs_tombstoned"),
                rs.getLong("total_tombstones_purged"),
                lastSuccess == null ? null : lastSuccess.toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("record_fingerprint"));
    }

    private void requireValidRetentionState(RetentionState state) {
        boolean idle = state.leaseOwner().isBlank() && state.leaseToken().isBlank();
        boolean fullyIdle = idle && state.leaseUntil().equals(Instant.EPOCH);
        boolean active = !state.leaseOwner().isBlank() && !state.leaseToken().isBlank()
                && state.leaseUntil().isAfter(state.updatedAt());
        if (state.leaseEpoch() < 0 || state.revision() < 0
                || state.totalJobsTombstoned() < 0 || state.totalTombstonesPurged() < 0
                || (!fullyIdle && !active)
                || (state.lastSuccessAt() != null
                && state.lastSuccessAt().isAfter(state.updatedAt()))) {
            throw new IllegalStateException(
                    "Stored stability-job retention state is invalid");
        }
        if (active) {
            identifier(state.leaseOwner(), "retention lease owner");
            try {
                UUID.fromString(state.leaseToken());
            } catch (IllegalArgumentException invalidToken) {
                throw new IllegalStateException(
                        "Stored stability-job retention lease token is invalid",
                        invalidToken);
            }
        }
        if (!fingerprint(state.recordFingerprint(), "recordFingerprint")
                .equals(retentionStateFingerprint(state))) {
            throw new IllegalStateException(
                    "Stored stability-job retention state integrity failed");
        }
    }

    private String retentionStateFingerprint(RetentionState state) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", "bloge.testSuiteStabilityJobRetentionState.v1");
        material.put("jobName", RETENTION_JOB);
        material.put("leaseOwner", state.leaseOwner());
        material.put("leaseToken", state.leaseToken());
        material.put("leaseEpoch", state.leaseEpoch());
        material.put("leaseUntil", state.leaseUntil());
        material.put("revision", state.revision());
        material.put("totalJobsTombstoned", state.totalJobsTombstoned());
        material.put("totalTombstonesPurged", state.totalTombstonesPurged());
        material.put("lastSuccessAt", state.lastSuccessAt());
        material.put("updatedAt", state.updatedAt());
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private static void requireLiveRetentionFence(
            RetentionState state, RetentionLease lease, Instant now) {
        if (!state.leaseOwner().equals(lease.ownerId())
                || !state.leaseToken().equals(lease.token())
                || state.leaseEpoch() != lease.epoch()
                || !state.leaseUntil().equals(lease.leaseUntil())
                || !state.leaseUntil().isAfter(now)) {
            throw new IllegalStateException(
                    "Stability-job retention fence is stale or expired");
        }
    }

    boolean releaseRetentionLease(RetentionLease lease) {
        RetentionLease requiredLease = Objects.requireNonNull(lease, "lease");
        Boolean released = mutations.execute(status -> {
            RetentionState current = retentionState(true);
            if (!current.leaseOwner().equals(requiredLease.ownerId())
                    || !current.leaseToken().equals(requiredLease.token())
                    || current.leaseEpoch() != requiredLease.epoch()) {
                return false;
            }
            Instant now = currentTime();
            RetentionState next = new RetentionState(
                    "", "", current.leaseEpoch(), Instant.EPOCH,
                    Math.addExact(current.revision(), 1),
                    current.totalJobsTombstoned(), current.totalTombstonesPurged(),
                    current.lastSuccessAt(), now, "");
            next = next.withFingerprint(retentionStateFingerprint(next));
            int updated = jdbc.update("""
                    UPDATE rg_test_suite_stability_job_retention
                    SET lease_owner = '', lease_token = '', lease_until = ?,
                        revision = ?, updated_at = ?, record_fingerprint = ?
                    WHERE job_name = ? AND lease_owner = ? AND lease_token = ?
                      AND lease_epoch = ? AND revision = ? AND record_fingerprint = ?
                    """, Timestamp.from(next.leaseUntil()), next.revision(),
                    Timestamp.from(next.updatedAt()), next.recordFingerprint(),
                    RETENTION_JOB, requiredLease.ownerId(), requiredLease.token(),
                    requiredLease.epoch(), current.revision(), current.recordFingerprint());
            return updated == 1;
        });
        return Boolean.TRUE.equals(released);
    }

    private Optional<StoredJob> byJobId(
            String tenant, String environment, String jobId) {
        List<StoredJob> rows = jdbc.query("""
                SELECT * FROM rg_test_suite_stability_jobs
                WHERE tenant_id = ? AND environment_id = ? AND job_id = ?
                """, this::storedJob, tenant, environment, jobId);
        return one(rows, "Duplicate suite-stability job rows");
    }

    private StoredJob storedJob(ResultSet rs, int rowNum) throws SQLException {
        try {
            TestSuiteStabilityExecutionRequest request = objectMapper.readValue(
                    rs.getString("request_json"), TestSuiteStabilityExecutionRequest.class);
            TestSuiteStabilityJobPrincipal principal = objectMapper.readValue(
                    rs.getString("principal_json"), TestSuiteStabilityJobPrincipal.class);
            Timestamp leaseTimestamp = rs.getTimestamp("lease_expires_at");
            StoredJob stored = stored(
                    rs.getString("job_id"), request,
                    rs.getString("submission_fingerprint"),
                    rs.getString("request_fingerprint"), rs.getString("classification"),
                    principal, TestSuiteStabilityJobSubmission.Priority.values()[
                            rs.getInt("base_priority")],
                    TestSuiteStabilityJobRecord.Status.valueOf(rs.getString("status")),
                    rs.getInt("retry_count"), rs.getTimestamp("next_eligible_at").toInstant(),
                    rs.getTimestamp("deadline_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant(),
                    rs.getTimestamp("expires_at").toInstant(), rs.getString("owner_id"),
                    rs.getLong("lease_epoch"),
                    leaseTimestamp == null ? null : leaseTimestamp.toInstant(),
                    rs.getLong("policy_generation"),
                    rs.getString("terminal_stability_run_id"),
                    rs.getString("terminal_evidence_fingerprint"),
                    rs.getString("failure_code"), rs.getString("cancellation_request_id"),
                    rs.getString("cancellation_fingerprint"));
            if (!stored.record().recordFingerprint()
                    .equals(rs.getString("record_fingerprint"))) {
                throw new IllegalStateException("Stored suite-stability job integrity failed");
            }
            return stored;
        } catch (JsonProcessingException | IllegalArgumentException corrupt) {
            throw new IllegalStateException("Stored suite-stability job is corrupt", corrupt);
        }
    }

    private StoredJob stored(
            String jobId,
            TestSuiteStabilityExecutionRequest request,
            String submissionFingerprint,
            String requestFingerprint,
            String classification,
            TestSuiteStabilityJobPrincipal principal,
            TestSuiteStabilityJobSubmission.Priority priority,
            TestSuiteStabilityJobRecord.Status status,
            int retryCount,
            Instant nextEligibleAt,
            Instant deadlineAt,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt,
            long policyGeneration,
            String terminalStabilityRunId,
            String terminalEvidenceFingerprint,
            String failureCode,
            String cancellationRequestId,
            String cancellationFingerprint) {
        String fingerprint = recordFingerprint(jobId, request, submissionFingerprint,
                requestFingerprint, classification, principal, priority, status, retryCount,
                nextEligibleAt, deadlineAt, createdAt, updatedAt, expiresAt, ownerId, leaseEpoch,
                leaseExpiresAt, policyGeneration, terminalStabilityRunId,
                terminalEvidenceFingerprint, failureCode, cancellationRequestId,
                cancellationFingerprint);
        TestSuiteStabilityJobRecord record = new TestSuiteStabilityJobRecord(
                jobId, request, requestFingerprint, classification, principal, priority, status,
                retryCount, nextEligibleAt, deadlineAt, createdAt, updatedAt, expiresAt,
                terminalStabilityRunId, terminalEvidenceFingerprint, failureCode,
                cancellationRequestId, cancellationFingerprint, fingerprint);
        return new StoredJob(record, submissionFingerprint, normalized(ownerId), leaseEpoch,
                leaseExpiresAt, policyGeneration);
    }

    private StoredJob transition(
            StoredJob source,
            TestSuiteStabilityJobRecord.Status status,
            int retryCount,
            Instant nextEligibleAt,
            Instant updatedAt,
            Instant expiresAt,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt,
            String terminalStabilityRunId,
            String terminalEvidenceFingerprint,
            String failureCode,
            String cancellationRequestId,
            String cancellationFingerprint) {
        TestSuiteStabilityJobRecord record = source.record();
        return stored(record.jobId(), record.request(), source.submissionFingerprint(),
                record.requestFingerprint(), record.classification(), record.principal(),
                record.priority(), status, retryCount, nextEligibleAt, record.deadlineAt(),
                record.createdAt(), updatedAt, expiresAt, ownerId, leaseEpoch, leaseExpiresAt,
                source.policyGeneration(), terminalStabilityRunId, terminalEvidenceFingerprint,
                failureCode, cancellationRequestId, cancellationFingerprint);
    }

    private StoredJob terminal(
            StoredJob source,
            TestSuiteStabilityJobRecord.Status status,
            Instant observedAt,
            String failureCode,
            TestSuiteStabilityQueuePolicy policy) {
        return terminal(source, status, observedAt, failureCode, policy,
                source.record().retryCount());
    }

    private StoredJob terminal(
            StoredJob source,
            TestSuiteStabilityJobRecord.Status status,
            Instant observedAt,
            String failureCode,
            TestSuiteStabilityQueuePolicy policy,
            int retryCount) {
        return transition(source, status, retryCount, source.record().nextEligibleAt(),
                observedAt, observedAt.plus(policy.terminalRetention()), "",
                source.leaseEpoch(), null, "", "", failureCode,
                source.record().cancellationRequestId(),
                source.record().cancellationFingerprint());
    }

    private StoredJob parentTerminal(
            StoredJob source,
            TestSuiteStabilityJobRecord.Status status,
            TestSuiteStabilityExecutionStop.Reason reason,
            Instant observedAt,
            String failureCode,
            TestSuiteStabilityQueuePolicy policy,
            int retryCount) {
        if (!Set.of(TestSuiteStabilityJobRecord.Status.CANCELLED,
                TestSuiteStabilityJobRecord.Status.EXPIRED,
                TestSuiteStabilityJobRecord.Status.FAILED).contains(status)) {
            throw new IllegalArgumentException(
                    "Parent-first transition requires a queue stop state");
        }
        TestSuiteStabilityJobParentAuthority.Resolution parent = stopParent(
                source.record(), reason, failureCode, policy.terminalRetention());
        if (parent.outcome() == TestSuiteStabilityJobParentAuthority.Outcome.COMPLETED) {
            return parentCompleted(source, parent, observedAt, policy, retryCount);
        }
        return terminal(source, status, observedAt, failureCode, policy, retryCount);
    }

    private StoredJob parentCompleted(
            StoredJob source,
            TestSuiteStabilityJobParentAuthority.Resolution parent,
            Instant observedAt,
            TestSuiteStabilityQueuePolicy policy,
            int retryCount) {
        return transition(source, TestSuiteStabilityJobRecord.Status.SUCCEEDED,
                retryCount, source.record().nextEligibleAt(), observedAt,
                observedAt.plus(policy.terminalRetention()), "", source.leaseEpoch(), null,
                parent.stabilityRunId(), parent.evidenceFingerprint(), "", "", "");
    }

    private TestSuiteStabilityJobParentAuthority.Resolution stopParent(
            TestSuiteStabilityJobRecord job,
            TestSuiteStabilityExecutionStop.Reason reason,
            String failureCode,
            Duration retention) {
        try {
            return parentAuthority.stop(job, reason, failureCode, retention);
        } catch (TestSuiteStabilityRunConflictException rejected) {
            throw conflict(TestSuiteStabilityJobConflictException.Reason.TERMINAL_CONFLICT,
                    "Parent execution rejected the queue stop transition");
        }
    }

    private TestSuiteStabilityJobParentAuthority.Resolution requireCompletedParent(
            TestSuiteStabilityJobRecord job,
            String stabilityRunId,
            String evidenceFingerprint) {
        try {
            return parentAuthority.requireCompleted(
                    job, stabilityRunId, evidenceFingerprint);
        } catch (TestSuiteStabilityRunConflictException rejected) {
            throw conflict(TestSuiteStabilityJobConflictException.Reason.TERMINAL_CONFLICT,
                    "Parent execution cannot authorize queue success");
        }
    }

    private void insert(StoredJob job) {
        TestSuiteStabilityJobRecord record = job.record();
        jdbc.update("""
                INSERT INTO rg_test_suite_stability_jobs (
                    job_id, tenant_id, environment_id, client_request_id,
                    submission_fingerprint, request_fingerprint, request_json, classification,
                    principal_json, base_priority, status, retry_count, next_eligible_at,
                    deadline_at, created_at, updated_at, expires_at, owner_id, lease_epoch,
                    lease_expires_at, policy_generation, terminal_stability_run_id,
                    terminal_evidence_fingerprint, failure_code, cancellation_request_id,
                    cancellation_fingerprint, record_fingerprint
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, record.jobId(), record.tenantId(), record.environmentId(),
                record.request().clientRequestId(), job.submissionFingerprint(),
                record.requestFingerprint(), json(record.request()), record.classification(),
                json(record.principal()), record.priority().ordinal(), record.status().name(),
                record.retryCount(), Timestamp.from(record.nextEligibleAt()),
                Timestamp.from(record.deadlineAt()), Timestamp.from(record.createdAt()),
                Timestamp.from(record.updatedAt()), Timestamp.from(record.expiresAt()),
                job.ownerId(), job.leaseEpoch(), timestamp(job.leaseExpiresAt()),
                job.policyGeneration(), record.terminalStabilityRunId(),
                record.terminalEvidenceFingerprint(), record.failureCode(),
                record.cancellationRequestId(), record.cancellationFingerprint(),
                record.recordFingerprint());
    }

    private void updateExact(StoredJob predecessor, StoredJob successor) {
        TestSuiteStabilityJobRecord next = successor.record();
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_jobs
                SET status = ?, retry_count = ?, next_eligible_at = ?, updated_at = ?,
                    expires_at = ?, owner_id = ?, lease_epoch = ?, lease_expires_at = ?,
                    terminal_stability_run_id = ?, terminal_evidence_fingerprint = ?,
                    failure_code = ?, cancellation_request_id = ?, cancellation_fingerprint = ?,
                    record_fingerprint = ?
                WHERE job_id = ? AND record_fingerprint = ?
                """, next.status().name(), next.retryCount(),
                Timestamp.from(next.nextEligibleAt()), Timestamp.from(next.updatedAt()),
                Timestamp.from(next.expiresAt()), successor.ownerId(), successor.leaseEpoch(),
                timestamp(successor.leaseExpiresAt()), next.terminalStabilityRunId(),
                next.terminalEvidenceFingerprint(), next.failureCode(),
                next.cancellationRequestId(), next.cancellationFingerprint(),
                next.recordFingerprint(), predecessor.record().jobId(),
                predecessor.record().recordFingerprint());
        if (updated != 1) {
            throw new IllegalStateException("Suite-stability job changed while environment locked");
        }
    }

    private String recordFingerprint(
            String jobId,
            TestSuiteStabilityExecutionRequest request,
            String submissionFingerprint,
            String requestFingerprint,
            String classification,
            TestSuiteStabilityJobPrincipal principal,
            TestSuiteStabilityJobSubmission.Priority priority,
            TestSuiteStabilityJobRecord.Status status,
            int retryCount,
            Instant nextEligibleAt,
            Instant deadlineAt,
            Instant createdAt,
            Instant updatedAt,
            Instant expiresAt,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt,
            long policyGeneration,
            String terminalStabilityRunId,
            String terminalEvidenceFingerprint,
            String failureCode,
            String cancellationRequestId,
            String cancellationFingerprint) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", "bloge.testSuiteStabilityJobRecord.v1");
        material.put("jobId", jobId);
        material.put("request", request);
        material.put("submissionFingerprint", submissionFingerprint);
        material.put("requestFingerprint", requestFingerprint);
        material.put("classification", classification);
        material.put("principal", principal);
        material.put("priority", priority.name());
        material.put("status", status.name());
        material.put("retryCount", retryCount);
        material.put("nextEligibleAt", nextEligibleAt);
        material.put("deadlineAt", deadlineAt);
        material.put("createdAt", createdAt);
        material.put("updatedAt", updatedAt);
        material.put("expiresAt", expiresAt);
        material.put("ownerId", normalized(ownerId));
        material.put("leaseEpoch", leaseEpoch);
        material.put("leaseExpiresAt", leaseExpiresAt);
        material.put("policyGeneration", policyGeneration);
        material.put("terminalStabilityRunId", normalized(terminalStabilityRunId));
        material.put("terminalEvidenceFingerprint", normalized(terminalEvidenceFingerprint));
        material.put("failureCode", normalized(failureCode));
        material.put("cancellationRequestId", normalized(cancellationRequestId));
        material.put("cancellationFingerprint", normalized(cancellationFingerprint));
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private String submissionFingerprint(TestSuiteStabilityJobSubmission submission) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", "bloge.testSuiteStabilityJobSubmission.v1");
        material.put("jobId", submission.jobId());
        material.put("request", submission.request());
        material.put("requestFingerprint", submission.requestFingerprint());
        material.put("classification", submission.classification());
        material.put("principal", stableSubmissionPrincipal(submission.principal()));
        material.put("priority", submission.priority().name());
        material.put("deadlineAt", submission.deadlineAt());
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private static Map<String, Object> stableSubmissionPrincipal(
            TestSuiteStabilityJobPrincipal principal) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("tenantId", principal.tenantId());
        material.put("organizationId", principal.organizationId());
        material.put("projectId", principal.projectId());
        material.put("environmentId", principal.environmentId());
        material.put("region", principal.region());
        material.put("actorType", principal.actorType());
        material.put("actorId", principal.actorId());
        material.put("delegatedBy", principal.delegatedBy());
        material.put("purpose", principal.purpose());
        material.put("groups", principal.groups().stream().sorted().toList());
        material.put("clearance", principal.clearance());
        material.put("delegationGrantId", principal.delegationGrantId());
        return material;
    }

    private void requireSameSubmission(
            StoredJob existing,
            TestSuiteStabilityJobSubmission submission,
            String submissionFingerprint) {
        if (!existing.record().jobId().equals(submission.jobId())
                || !existing.submissionFingerprint().equals(submissionFingerprint)
                || !existing.record().requestFingerprint().equals(
                submission.requestFingerprint())) {
            throw conflict(TestSuiteStabilityJobConflictException.Reason.IDEMPOTENCY_CONFLICT,
                    "clientRequestId already identifies another stability job intent");
        }
    }

    private TestSuiteStabilityJobLease lease(StoredJob job) {
        return new TestSuiteStabilityJobLease(
                job.record().jobId(), job.record().tenantId(), job.record().environmentId(),
                job.record().requestFingerprint(), job.ownerId(), job.leaseEpoch(),
                Objects.requireNonNull(job.leaseExpiresAt(), "leaseExpiresAt"));
    }

    private static boolean sameFence(
            StoredJob stored, TestSuiteStabilityJobLease lease) {
        return stored.record().jobId().equals(lease.jobId())
                && stored.record().tenantId().equals(lease.tenantId())
                && stored.record().environmentId().equals(lease.environmentId())
                && stored.record().requestFingerprint().equals(lease.requestFingerprint())
                && stored.ownerId().equals(lease.ownerId())
                && stored.leaseEpoch() == lease.epoch()
                && Objects.equals(stored.leaseExpiresAt(), lease.expiresAt());
    }

    private void lockEnvironment(String environment) {
        jdbc.update("""
                MERGE INTO rg_test_suite_stability_queue_locks (environment_id)
                KEY (environment_id) VALUES (?)
                """, environment);
        List<String> rows = jdbc.query("""
                SELECT environment_id FROM rg_test_suite_stability_queue_locks
                WHERE environment_id = ? FOR UPDATE
                """, (rs, rowNum) -> rs.getString(1), environment);
        if (rows.size() != 1) {
            throw new IllegalStateException("Suite-stability environment lock is unavailable");
        }
    }

    private Instant currentTime() {
        Timestamp value = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException("Suite-stability queue database time is unavailable");
        }
        return value.toInstant();
    }

    private static TestSuiteStabilityQueueSnapshot queueSnapshot(ResultSet rs)
            throws SQLException {
        EnumMap<TestSuiteStabilityJobRecord.Status, Long> totals =
                new EnumMap<>(TestSuiteStabilityJobRecord.Status.class);
        totals.put(TestSuiteStabilityJobRecord.Status.QUEUED, rs.getLong("queued"));
        totals.put(TestSuiteStabilityJobRecord.Status.RUNNING, rs.getLong("running"));
        totals.put(TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED,
                rs.getLong("cancel_requested"));
        totals.put(TestSuiteStabilityJobRecord.Status.COMMITTING, rs.getLong("committing"));
        totals.put(TestSuiteStabilityJobRecord.Status.SUCCEEDED, rs.getLong("succeeded"));
        totals.put(TestSuiteStabilityJobRecord.Status.FAILED, rs.getLong("failed"));
        totals.put(TestSuiteStabilityJobRecord.Status.CANCELLED, rs.getLong("cancelled"));
        totals.put(TestSuiteStabilityJobRecord.Status.EXPIRED, rs.getLong("expired"));
        totals.put(TestSuiteStabilityJobRecord.Status.QUARANTINED, rs.getLong("quarantined"));
        long knownRecords = totals.values().stream().mapToLong(Long::longValue).sum();
        if (knownRecords != rs.getLong("all_records")) {
            throw new IllegalStateException(
                    "Suite-stability queue contains an unknown lifecycle status");
        }
        Timestamp oldest = rs.getTimestamp("oldest_queued_at");
        return new TestSuiteStabilityQueueSnapshot(
                rs.getTimestamp("observed_at").toInstant(), totals,
                oldest == null ? null : oldest.toInstant(),
                rs.getLong("expired_live_leases"),
                rs.getLong("distinct_queued_tenants"));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("Suite-stability job cannot be serialized", invalid);
        }
    }

    private static int effectivePriority(
            TestSuiteStabilityJobRecord record,
            Instant observedAt,
            TestSuiteStabilityQueuePolicy policy) {
        long waited = Math.max(0, Duration.between(record.createdAt(), observedAt).toSeconds());
        long promotions = waited / policy.agingInterval().toSeconds();
        return (int) Math.min(TestSuiteStabilityJobSubmission.Priority.HIGH.ordinal(),
                record.priority().ordinal() + promotions);
    }

    private static String nextTenant(List<String> tenants, String previous) {
        return tenants.stream().filter(value -> value.compareTo(previous) > 0).findFirst()
                .orElseGet(tenants::getFirst);
    }

    private static Instant safePlus(Instant value, Duration duration) {
        try {
            return value.plus(duration);
        } catch (RuntimeException overflow) {
            throw new IllegalArgumentException("Suite-stability job retention overflows", overflow);
        }
    }

    private static Duration boundedTombstoneRetention(Duration retention) {
        Duration safe = Objects.requireNonNull(retention, "tombstoneRetention");
        if (safe.getNano() != 0
                || safe.compareTo(MINIMUM_TOMBSTONE_RETENTION) < 0
                || safe.compareTo(MAXIMUM_TOMBSTONE_RETENTION) > 0) {
            throw new IllegalArgumentException(
                    "Tombstone retention must be whole seconds between 1 and 3650 days");
        }
        return safe;
    }

    private static int boundedRetentionPage(int limit) {
        if (limit < 1 || limit > MAXIMUM_PURGE_BATCH) {
            throw new IllegalArgumentException(
                    "Stability-job retention page must be between 1 and 10000");
        }
        return limit;
    }

    private static String requiredRetentionOwner(String value) {
        return identifier(value, "retentionOwnerId");
    }

    private static Duration boundedRetentionLease(Duration value) {
        Duration safe = Objects.requireNonNull(value, "retentionLeaseDuration");
        if (safe.getNano() != 0
                || safe.compareTo(MINIMUM_RETENTION_LEASE) < 0
                || safe.compareTo(MAXIMUM_RETENTION_LEASE) > 0) {
            throw new IllegalArgumentException(
                    "retentionLeaseDuration must be whole seconds between 1 second and 1 hour");
        }
        return safe;
    }

    private static String environment(String value) {
        String result = normalized(value).toLowerCase(Locale.ROOT);
        if (!Set.of("test", "staging").contains(result)) {
            throw new IllegalArgumentException("environmentId must be test or staging");
        }
        return result;
    }

    private static String identifier(String value, String name) {
        String result = normalized(value);
        if (!IDENTIFIER.matcher(result).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return result;
    }

    private static String fingerprint(String value, String name) {
        String result = normalized(value);
        if (!FINGERPRINT.matcher(result).matches()) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return result;
    }

    private static String failureCode(String value) {
        String result = normalized(value).toUpperCase(Locale.ROOT);
        if (!FAILURE_CODE.matcher(result).matches()) {
            throw new IllegalArgumentException("failureCode is invalid");
        }
        return result;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static <T> Optional<T> one(List<T> rows, String error) {
        if (rows.size() > 1) {
            throw new IllegalStateException(error);
        }
        return rows.stream().findFirst();
    }

    private static <T> T required(T value, String message) {
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    private static TestSuiteStabilityJobConflictException conflict(
            TestSuiteStabilityJobConflictException.Reason reason, String message) {
        return new TestSuiteStabilityJobConflictException(reason, message);
    }

    private record PolicyRow(long generation, String fingerprint) {
    }

    private record Cursor(String environmentId, String lastTenantId, long cycleEpoch) {
    }

    private record StoredJob(
            TestSuiteStabilityJobRecord record,
            String submissionFingerprint,
            String ownerId,
            long leaseEpoch,
            Instant leaseExpiresAt,
            long policyGeneration) {
    }

    private record StoredTombstone(
            String tenantId,
            String environmentId,
            String requestKeyId,
            String requestKey,
            String submissionFingerprint,
            Instant tombstonedAt,
            Instant expiresAt,
            int recordVersion,
            String recordFingerprint) {
    }

    record RetentionLease(
            String ownerId,
            String token,
            long epoch,
            Instant leaseUntil) {
    }

    private record RetentionState(
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            long totalJobsTombstoned,
            long totalTombstonesPurged,
            Instant lastSuccessAt,
            Instant updatedAt,
            String recordFingerprint) {

        private RetentionState withFingerprint(String fingerprint) {
            return new RetentionState(
                    leaseOwner, leaseToken, leaseEpoch, leaseUntil, revision,
                    totalJobsTombstoned, totalTombstonesPurged,
                    lastSuccessAt, updatedAt, fingerprint);
        }
    }

}
