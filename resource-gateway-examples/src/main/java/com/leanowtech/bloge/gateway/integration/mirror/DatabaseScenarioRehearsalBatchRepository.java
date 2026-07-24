package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalBatchConflictException.Reason;

/**
 * JDBC implementation of the cross-replica Scenario rehearsal batch queue.
 *
 * <p>One region-and-environment authority row serializes policy convergence, capacity admission,
 * tenant rotation, stale-lease recovery, and claim. Execution occurs outside that transaction
 * under a one-item owner/epoch/expiry fence. Every item and public job projection is
 * content-addressed; corruption fails closed instead of silently changing scheduling or
 * correctness outcomes.</p>
 *
 * <p>Only exact plan references, credential-free principal coordinates, and payload-free evidence
 * references are retained. TestSuite input, FixtureBundle values, Session state, child node
 * input/output, and external responses are deliberately absent from the schema.</p>
 */
public final class DatabaseScenarioRehearsalBatchRepository
        implements ScenarioRehearsalBatchRepository {
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}");
    private static final Pattern CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,254}");
    private static final int RECONCILIATION_LIMIT = 1_000;
    private static final String NO_TENANT = "";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Supplier<Instant> coordinationClock;
    private final TransactionTemplate mutations;
    private final ScenarioRehearsalBatchEvidencePublisher
            evidencePublisher;
    private final ScenarioRehearsalBatchLifecycleAuditRepository
            lifecycleAudit;

    /**
     * Creates the production repository using an independent application-database clock sample.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param transactionManager manager for the same datasource
     * @param evidencePublisher mandatory atomic terminal evidence publisher
     * @param lifecycleAudit mandatory payload-free transition audit
     */
    public DatabaseScenarioRehearsalBatchRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            ScenarioRehearsalBatchEvidencePublisher
                    evidencePublisher,
            ScenarioRehearsalBatchLifecycleAuditRepository
                    lifecycleAudit) {
        this(
                jdbc,
                mapper,
                transactionManager,
                evidencePublisher,
                lifecycleAudit,
                null);
    }

    /** Package-private deterministic database-clock seam for concurrency and deadline tests. */
    DatabaseScenarioRehearsalBatchRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            ScenarioRehearsalBatchEvidencePublisher
                    evidencePublisher,
            ScenarioRehearsalBatchLifecycleAuditRepository
                    lifecycleAudit,
            Supplier<Instant> coordinationClock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.evidencePublisher = Objects.requireNonNull(
                evidencePublisher, "evidencePublisher");
        this.lifecycleAudit = Objects.requireNonNull(
                lifecycleAudit, "lifecycleAudit");
        this.coordinationClock = coordinationClock == null
                ? () -> databaseNow(this.jdbc)
                : Objects.requireNonNull(
                coordinationClock, "coordinationClock");
        mutations = new TransactionTemplate(
                Objects.requireNonNull(
                        transactionManager, "transactionManager"));
        mutations.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates authority, policy, cursor, job, and payload-free item tables and indexes. */
    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scenario_rehearsal_batch_locks (
                    environment_id VARCHAR(255) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scenario_rehearsal_batch_policies (
                    environment_id VARCHAR(255) PRIMARY KEY,
                    policy_generation BIGINT NOT NULL,
                    policy_fingerprint VARCHAR(71) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scenario_rehearsal_batch_cursors (
                    environment_id VARCHAR(255) PRIMARY KEY,
                    last_tenant_id VARCHAR(255) NOT NULL,
                    cycle_epoch BIGINT NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scenario_rehearsal_batch_jobs (
                    job_id VARCHAR(512) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    region VARCHAR(64) NOT NULL,
                    request_id VARCHAR(256) NOT NULL,
                    request_fingerprint VARCHAR(71) NOT NULL,
                    manifest_fingerprint VARCHAR(71) NOT NULL,
                    request_json CLOB NOT NULL,
                    manifest_json CLOB NOT NULL,
                    principal_json CLOB NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    base_priority INTEGER NOT NULL,
                    next_eligible_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_owner VARCHAR(512) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    current_item_index INTEGER NOT NULL,
                    heartbeat_at TIMESTAMP WITH TIME ZONE,
                    heartbeat_count BIGINT DEFAULT 0 NOT NULL,
                    heartbeat_case_index INTEGER DEFAULT 0 NOT NULL,
                    deadline_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    job_json CLOB NOT NULL,
                    CONSTRAINT uq_scenario_rehearsal_batch_request
                        UNIQUE (
                            tenant_id, organization_id, project_id,
                            environment_id, region, request_id
                        )
                )
                """);
        jdbc.execute("""
                ALTER TABLE scenario_rehearsal_batch_jobs
                ADD COLUMN IF NOT EXISTS heartbeat_at TIMESTAMP WITH TIME ZONE
                """);
        jdbc.execute("""
                ALTER TABLE scenario_rehearsal_batch_jobs
                ADD COLUMN IF NOT EXISTS heartbeat_count BIGINT DEFAULT 0 NOT NULL
                """);
        jdbc.execute("""
                ALTER TABLE scenario_rehearsal_batch_jobs
                ADD COLUMN IF NOT EXISTS heartbeat_case_index INTEGER DEFAULT 0 NOT NULL
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scenario_rehearsal_batch_schedule
                ON scenario_rehearsal_batch_jobs (
                    environment_id, status, next_eligible_at,
                    tenant_id, created_at, job_id
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scenario_rehearsal_batch_live
                ON scenario_rehearsal_batch_jobs (
                    environment_id, tenant_id, status, lease_expires_at
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scenario_rehearsal_batch_partition_schedule
                ON scenario_rehearsal_batch_jobs (
                    region, environment_id, status, next_eligible_at,
                    tenant_id, created_at, job_id
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scenario_rehearsal_batch_partition_live
                ON scenario_rehearsal_batch_jobs (
                    region, environment_id, tenant_id, status, lease_expires_at
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scenario_rehearsal_batch_items (
                    job_id VARCHAR(512) NOT NULL,
                    item_index INTEGER NOT NULL,
                    compiled_plan_id VARCHAR(512) NOT NULL,
                    compiled_plan_revision BIGINT NOT NULL,
                    compiled_plan_fingerprint VARCHAR(71) NOT NULL,
                    child_request_id VARCHAR(512) NOT NULL,
                    execution_timeout_millis BIGINT NOT NULL,
                    status VARCHAR(32) NOT NULL,
                    attempt_count INTEGER NOT NULL,
                    run_id VARCHAR(512) NOT NULL,
                    evidence_bundle_fingerprint VARCHAR(71) NOT NULL,
                    workbook_seed_fingerprint VARCHAR(71) NOT NULL,
                    failure_code VARCHAR(255) NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE,
                    completed_at TIMESTAMP WITH TIME ZONE,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (job_id, item_index),
                    FOREIGN KEY (job_id)
                        REFERENCES scenario_rehearsal_batch_jobs (job_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scenario_rehearsal_batch_item_status
                ON scenario_rehearsal_batch_items (
                    job_id, status, item_index
                )
                """);
    }

    @Override
    public SubmissionResult submit(
            Submission submission,
            ScenarioRehearsalBatchPolicy policy) {
        return submitObserved(
                submission, policy, null);
    }

    @Override
    public SubmissionResult submit(
            Submission submission,
            ScenarioRehearsalBatchPolicy policy,
            MirrorOperationObservability.Observation observation) {
        return submitObserved(
                submission,
                policy,
                Objects.requireNonNull(
                        observation, "observation"));
    }

    private SubmissionResult submitObserved(
            Submission submission,
            ScenarioRehearsalBatchPolicy policy,
            MirrorOperationObservability.Observation observation) {
        Objects.requireNonNull(submission, "submission");
        Objects.requireNonNull(policy, "policy");
        SubmissionResult result = mutations.execute(status -> {
            QueuePartition partition = partition(
                    submission.manifest().scope());
            lockPartition(partition);
            Instant observedAt = coordinationNow();
            ensurePolicy(partition, policy, observedAt);
            reconcile(partition, observedAt, policy);
            ScenarioRehearsalBatchManifestIntegrity.verify(
                    mapper, submission.manifest());
            if (!submission.requestFingerprint().equals(
                    ProtocolFingerprint.of(
                            mapper, submission.request()))) {
                throw new IllegalArgumentException(
                        "Scenario batch request fingerprint mismatch");
            }
            Optional<StoredJob> existing = byRequest(
                    submission.manifest().scope(),
                    submission.request().requestId(),
                    true);
            if (existing.isPresent()) {
                requireSameSubmission(
                        existing.orElseThrow(), submission);
                return observed(
                        new SubmissionResult(
                                existing.orElseThrow().job(),
                                true),
                        observation);
            }
            requireDeadline(submission, policy, observedAt);
            if (activeCount(partition, null)
                    >= policy.maximumQueued()) {
                throw conflict(
                        Reason.GLOBAL_QUEUE_FULL,
                        "Scenario rehearsal batch environment queue is full");
            }
            if (activeCount(
                    partition,
                    submission.manifest().scope()
                            .tenantId())
                    >= policy.maximumQueuedPerTenant()) {
                throw conflict(
                        Reason.TENANT_QUEUE_FULL,
                        "Scenario rehearsal batch tenant queue is full");
            }
            Instant deadlineAt = safePlus(
                    observedAt,
                    policy.maximumDeadlineHorizon());
            Instant expiresAt = safePlus(
                    deadlineAt,
                    policy.terminalRetention());
            ScenarioRehearsalBatchJob job =
                    ScenarioRehearsalBatchIntegrity.seal(
                            mapper,
                            new ScenarioRehearsalBatchJob(
                                    "",
                                    submission.manifest().batchId(),
                                    submission.request().requestId(),
                                    submission.requestFingerprint(),
                                    submission.manifest()
                                            .manifestFingerprint(),
                                    submission.manifest().scope(),
                                    ScenarioRehearsalBatchJob.Status.QUEUED,
                                    policy.failureMode(),
                                    policy.priority(),
                                    policy.maximumItemAttempts(),
                                    new ScenarioRehearsalBatchJob.Summary(
                                            submission.manifest()
                                                    .entries().size(),
                                            0, 0, 0, 0, 0),
                                    deadlineAt,
                                    "", "", "",
                                    observedAt, observedAt,
                                    null, ""));
            StoredJob stored = new StoredJob(
                    job,
                    submission.request(),
                    submission.manifest(),
                    submission.principal(),
                    observedAt,
                    "",
                    0,
                    Instant.EPOCH,
                    -1,
                    expiresAt);
            try {
                insertJob(stored);
                for (ScenarioRehearsalBatchManifest.Entry entry
                        : submission.manifest().entries()) {
                    insertItem(
                            job.jobId(),
                            new StoredItem(
                                    pending(entry),
                                    entry.executionTimeout()));
                }
            } catch (DuplicateKeyException collision) {
                Optional<StoredJob> winner = byRequest(
                        submission.manifest().scope(),
                        submission.request().requestId(),
                        true);
                if (winner.isPresent()) {
                    requireSameSubmission(
                            winner.orElseThrow(), submission);
                    return observed(
                            new SubmissionResult(
                                    winner.orElseThrow().job(),
                                    true),
                            observation);
                }
                throw conflict(
                        Reason.IDEMPOTENCY_CONFLICT,
                        "Scenario rehearsal batch identity already belongs to another intent");
            }
            appendLifecycle(
                    stored,
                    ScenarioRehearsalBatchLifecycleAuditEvent
                            .Transition.ADMITTED,
                    job,
                    null,
                    "",
                    "");
            return observed(
                    new SubmissionResult(job, false),
                    observation);
        });
        return required(
                result,
                "Scenario rehearsal batch submission returned no result");
    }

    @Override
    public Claim claimNext(
            String region,
            String environmentId,
            String ownerId,
            ScenarioRehearsalBatchPolicy policy) {
        QueuePartition partition = partition(
                region, environmentId);
        String owner = identifier(ownerId, "ownerId");
        Objects.requireNonNull(policy, "policy");
        Claim result = mutations.execute(status -> {
            lockPartition(partition);
            Instant observedAt = coordinationNow();
            ensurePolicy(partition, policy, observedAt);
            reconcile(partition, observedAt, policy);
            if (liveRunningCount(partition, null, observedAt)
                    >= policy.maximumRunning()) {
                return Claim.noWork(observedAt);
            }
            for (int skipped = 0;
                 skipped < RECONCILIATION_LIMIT;
                 skipped++) {
                Optional<StoredJob> selected =
                        selectNext(partition, observedAt, policy);
                if (selected.isEmpty()) {
                    return Claim.noWork(observedAt);
                }
                StoredJob stored = selected.orElseThrow();
                StoredItem item = firstNonTerminal(
                        stored.job().jobId()).orElseThrow(() ->
                        new IllegalStateException(
                                "Active Scenario batch has no unfinished item"));
                Duration leaseDuration = safePlus(
                        item.executionTimeout(),
                        policy.leaseReserve());
                Instant latestStart = stored.job().deadlineAt()
                        .minus(leaseDuration);
                if (observedAt.isAfter(latestStart)) {
                    terminalizeRemaining(
                            stored,
                            item,
                            ScenarioRehearsalBatchJob.Status.EXPIRED,
                            "RG.MIRROR.REHEARSAL_BATCH.DEADLINE_INSUFFICIENT",
                            observedAt);
                    continue;
                }
                int attempt = Math.addExact(
                        item.item().attemptCount(), 1);
                Instant startedAt =
                        item.item().startedAt() == null
                                ? observedAt
                                : item.item().startedAt();
                ScenarioRehearsalBatchItemPage.Item running =
                        new ScenarioRehearsalBatchItemPage.Item(
                                item.item().itemIndex(),
                                item.item().compiledPlanRef(),
                                item.item().childRequestId(),
                                ScenarioRehearsalBatchItemPage.Status.RUNNING,
                                attempt,
                                "", "", "", "",
                                startedAt, null);
                updateItem(stored.job().jobId(), item, new StoredItem(
                        running, item.executionTimeout()));
                long epoch = Math.addExact(
                        stored.leaseEpoch(), 1);
                Instant leaseExpiresAt =
                        safePlus(observedAt, leaseDuration);
                ScenarioRehearsalBatchJob runningJob =
                        transition(
                                stored.job(),
                                ScenarioRehearsalBatchJob.Status.RUNNING,
                                summary(stored.job().jobId()),
                                stored.job().failureCode(),
                                stored.job().cancellationRequestId(),
                                stored.job().cancellationReasonCode(),
                                observedAt,
                                null);
                StoredJob claimed = new StoredJob(
                        runningJob,
                        stored.request(),
                        stored.manifest(),
                        stored.principal(),
                        stored.nextEligibleAt(),
                        owner,
                        epoch,
                        leaseExpiresAt,
                        running.itemIndex(),
                        stored.expiresAt());
                updateJob(stored, claimed);
                resetExecutionCheckpoint(
                        claimed, observedAt);
                advanceCursor(
                        partition,
                        stored.job().scope().tenantId(),
                        observedAt);
                appendLifecycle(
                        claimed,
                        ScenarioRehearsalBatchLifecycleAuditEvent
                                .Transition.CLAIMED,
                        runningJob,
                        running,
                        "",
                        "");
                Lease lease = new Lease(
                        runningJob.scope(),
                        runningJob.jobId(),
                        owner,
                        epoch,
                        running.itemIndex(),
                        leaseExpiresAt);
                return new Claim(
                        ClaimOutcome.ACQUIRED,
                        observedAt,
                        runningJob,
                        running,
                        stored.principal(),
                        lease);
            }
            throw new IllegalStateException(
                    "Scenario batch claim exceeded bounded reconciliation");
        });
        return required(
                result,
                "Scenario rehearsal batch claim returned no result");
    }

    @Override
    public ExecutionControlCheckpoint checkpointExecution(
            Lease lease,
            int nextCaseIndex,
            ScenarioRehearsalBatchPolicy policy) {
        Objects.requireNonNull(lease, "lease");
        if (nextCaseIndex < 0
                || nextCaseIndex > ScenarioPack.MAXIMUM_CASES) {
            throw new IllegalArgumentException(
                    "Scenario batch execution cursor is invalid");
        }
        Objects.requireNonNull(policy, "policy");
        ExecutionControlCheckpoint result =
                mutations.execute(status -> {
                    QueuePartition partition =
                            partition(lease.scope());
                    lockPartition(partition);
                    Instant observedAt = coordinationNow();
                    ensurePolicy(
                            partition,
                            policy,
                            observedAt);
                    StoredJob stored = byJob(
                            lease.scope(),
                            lease.jobId(),
                            true).orElseThrow(() ->
                            conflict(
                                    Reason.JOB_NOT_FOUND,
                                    "Scenario rehearsal batch lease no longer exists"));
                    StoredHeartbeat heartbeat =
                            heartbeat(stored.job().jobId());
                    if (!sameLease(stored, lease)) {
                        return new ExecutionControlCheckpoint(
                                ExecutionControlOutcome.LEASE_LOST,
                                observedAt,
                                heartbeat.count(),
                                nextCaseIndex,
                                stored.job());
                    }
                    StoredItem current =
                            requireRunningItem(stored, lease);
                    if (nextCaseIndex < heartbeat.nextCaseIndex()) {
                        throw new IllegalStateException(
                                "Scenario batch execution cursor regressed");
                    }
                    if (stored.job().status()
                            != ScenarioRehearsalBatchJob.Status
                            .CANCEL_REQUESTED
                            && stored.job().deadlineAt()
                            .isAfter(observedAt)
                            && !stored.leaseExpiresAt()
                            .isAfter(observedAt)) {
                        return new ExecutionControlCheckpoint(
                                ExecutionControlOutcome.LEASE_LOST,
                                observedAt,
                                heartbeat.count(),
                                nextCaseIndex,
                                stored.job());
                    }
                    long heartbeatCount = Math.addExact(
                            heartbeat.count(), 1);
                    updateExecutionCheckpoint(
                            stored,
                            lease,
                            observedAt,
                            heartbeatCount,
                            nextCaseIndex);
                    if (stored.job().status()
                            == ScenarioRehearsalBatchJob.Status
                            .CANCEL_REQUESTED) {
                        ScenarioRehearsalBatchJob cancelled =
                                terminalizeRemaining(
                                        stored,
                                        current,
                                        ScenarioRehearsalBatchJob.Status
                                                .CANCELLED,
                                        "RG.MIRROR.REHEARSAL_BATCH.CANCELLED",
                                        observedAt);
                        return new ExecutionControlCheckpoint(
                                ExecutionControlOutcome.CANCELLED,
                                observedAt,
                                heartbeatCount,
                                nextCaseIndex,
                                cancelled);
                    }
                    if (!stored.job().deadlineAt()
                            .isAfter(observedAt)) {
                        ScenarioRehearsalBatchJob expired =
                                terminalizeRemaining(
                                        stored,
                                        current,
                                        ScenarioRehearsalBatchJob.Status
                                                .EXPIRED,
                                        "RG.MIRROR.REHEARSAL_BATCH.DEADLINE_EXCEEDED",
                                        observedAt);
                        return new ExecutionControlCheckpoint(
                                ExecutionControlOutcome
                                        .DEADLINE_EXCEEDED,
                                observedAt,
                                heartbeatCount,
                                nextCaseIndex,
                                expired);
                    }
                    return new ExecutionControlCheckpoint(
                            ExecutionControlOutcome.CONTINUE,
                            observedAt,
                            heartbeatCount,
                            nextCaseIndex,
                            stored.job());
                });
        return required(
                result,
                "Scenario batch execution checkpoint returned no result");
    }

    @Override
    public ScenarioRehearsalBatchJob completeItem(
            Lease lease,
            ItemCompletion completion,
            ScenarioRehearsalBatchPolicy policy) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(policy, "policy");
        ScenarioRehearsalBatchJob result =
                mutations.execute(status -> {
                    QueuePartition partition =
                            partition(lease.scope());
                    lockPartition(partition);
                    Instant observedAt = coordinationNow();
                    ensurePolicy(
                            partition,
                            policy,
                            observedAt);
                    StoredJob stored =
                            requireLive(lease, observedAt);
                    StoredItem current =
                            requireRunningItem(stored, lease);
                    ScenarioRehearsalBatchManifest.Entry expected =
                            stored.manifest().entries()
                                    .get(lease.itemIndex());
                    if (!expected.aggregateRunId().equals(
                            completion.runId())) {
                        throw conflict(
                                Reason.EVIDENCE_MISMATCH,
                                "Scenario rehearsal batch completion does not belong to the claimed manifest item");
                    }
                    ScenarioRehearsalBatchItemPage.Status itemStatus =
                            switch (completion.outcome()) {
                                case PASS ->
                                        ScenarioRehearsalBatchItemPage
                                                .Status.PASSED;
                                case FAIL ->
                                        ScenarioRehearsalBatchItemPage
                                                .Status.FAILED;
                                case INDETERMINATE ->
                                        ScenarioRehearsalBatchItemPage
                                                .Status.INDETERMINATE;
                            };
                    String failureCode = switch (completion.outcome()) {
                        case PASS -> "";
                        case FAIL ->
                                "RG.MIRROR.REHEARSAL_BATCH.ITEM_FAILED";
                        case INDETERMINATE ->
                                "RG.MIRROR.REHEARSAL_BATCH.ITEM_INDETERMINATE";
                    };
                    ScenarioRehearsalBatchItemPage.Item terminal =
                            new ScenarioRehearsalBatchItemPage.Item(
                                    current.item().itemIndex(),
                                    current.item().compiledPlanRef(),
                                    current.item().childRequestId(),
                                    itemStatus,
                                    current.item().attemptCount(),
                                    completion.runId(),
                                    completion
                                            .evidenceBundleFingerprint(),
                                    completion
                                            .workbookSeedFingerprint(),
                                    failureCode,
                                    current.item().startedAt(),
                                    observedAt);
                    updateItem(
                            stored.job().jobId(),
                            current,
                            new StoredItem(
                                    terminal,
                                    current.executionTimeout()));
                    appendLifecycle(
                            stored,
                            ScenarioRehearsalBatchLifecycleAuditEvent
                                    .Transition.ITEM_TERMINALIZED,
                            stored.job(),
                            terminal,
                            completion.evidenceBundleFingerprint(),
                            failureCode);
                    return advanceAfterTerminalItem(
                            stored,
                            terminal,
                            failureCode,
                            observedAt,
                            policy);
                });
        return required(
                result,
                "Scenario rehearsal batch completion returned no result");
    }

    @Override
    public ScenarioRehearsalBatchJob retryItem(
            Lease lease,
            String failureCode,
            ScenarioRehearsalBatchPolicy policy) {
        Objects.requireNonNull(lease, "lease");
        String code = code(failureCode, "failureCode");
        Objects.requireNonNull(policy, "policy");
        ScenarioRehearsalBatchJob result =
                mutations.execute(status -> {
                    QueuePartition partition =
                            partition(lease.scope());
                    lockPartition(partition);
                    Instant observedAt = coordinationNow();
                    ensurePolicy(
                            partition,
                            policy,
                            observedAt);
                    StoredJob stored =
                            requireLive(lease, observedAt);
                    StoredItem current =
                            requireRunningItem(stored, lease);
                    if (stored.job().status()
                            == ScenarioRehearsalBatchJob.Status
                            .CANCEL_REQUESTED) {
                        return terminalizeRemaining(
                                stored,
                                current,
                                ScenarioRehearsalBatchJob.Status
                                        .CANCELLED,
                                code,
                                observedAt);
                    }
                    if (!stored.job().deadlineAt()
                            .isAfter(observedAt)) {
                        return terminalizeRemaining(
                                stored,
                                current,
                                ScenarioRehearsalBatchJob.Status
                                        .EXPIRED,
                                "RG.MIRROR.REHEARSAL_BATCH.DEADLINE_EXCEEDED",
                                observedAt);
                    }
                    if (current.item().attemptCount()
                            < stored.job().maximumItemAttempts()) {
                        ScenarioRehearsalBatchItemPage.Item pending =
                                new ScenarioRehearsalBatchItemPage.Item(
                                        current.item().itemIndex(),
                                        current.item()
                                                .compiledPlanRef(),
                                        current.item()
                                                .childRequestId(),
                                        ScenarioRehearsalBatchItemPage
                                                .Status.PENDING,
                                        current.item().attemptCount(),
                                        "", "", "", code,
                                        current.item().startedAt(),
                                        null);
                        updateItem(
                                stored.job().jobId(),
                                current,
                                new StoredItem(
                                        pending,
                                        current.executionTimeout()));
                        ScenarioRehearsalBatchJob queued =
                                transition(
                                        stored.job(),
                                        ScenarioRehearsalBatchJob.Status
                                                .QUEUED,
                                        summary(
                                                stored.job().jobId()),
                                        code,
                                        stored.job()
                                                .cancellationRequestId(),
                                        stored.job()
                                                .cancellationReasonCode(),
                                        observedAt,
                                        null);
                        StoredJob successor = idle(
                                stored,
                                queued,
                                safePlus(
                                        observedAt,
                                        policy.retryBackoff()));
                        updateJob(stored, successor);
                        appendLifecycle(
                                stored,
                                ScenarioRehearsalBatchLifecycleAuditEvent
                                        .Transition.ITEM_RETRY_SCHEDULED,
                                queued,
                                pending,
                                "",
                                code);
                        return queued;
                    }
                    return terminalFailure(
                            stored,
                            current,
                            code,
                            observedAt,
                            policy);
                });
        return required(
                result,
                "Scenario rehearsal batch retry returned no result");
    }

    @Override
    public ScenarioRehearsalBatchJob failItem(
            Lease lease,
            String failureCode,
            ScenarioRehearsalBatchPolicy policy) {
        Objects.requireNonNull(lease, "lease");
        String code = code(failureCode, "failureCode");
        Objects.requireNonNull(policy, "policy");
        ScenarioRehearsalBatchJob result =
                mutations.execute(status -> {
                    QueuePartition partition =
                            partition(lease.scope());
                    lockPartition(partition);
                    Instant observedAt = coordinationNow();
                    ensurePolicy(
                            partition,
                            policy,
                            observedAt);
                    StoredJob stored =
                            requireLive(lease, observedAt);
                    StoredItem current =
                            requireRunningItem(stored, lease);
                    return terminalFailure(
                            stored,
                            current,
                            code,
                            observedAt,
                            policy);
                });
        return required(
                result,
                "Scenario rehearsal batch terminal failure returned no result");
    }

    @Override
    public SubmissionResult cancel(
            Cancellation cancellation,
            ScenarioRehearsalBatchPolicy policy) {
        return cancelObserved(
                cancellation, policy, null);
    }

    @Override
    public SubmissionResult cancel(
            Cancellation cancellation,
            ScenarioRehearsalBatchPolicy policy,
            MirrorOperationObservability.Observation observation) {
        return cancelObserved(
                cancellation,
                policy,
                Objects.requireNonNull(
                        observation, "observation"));
    }

    private SubmissionResult cancelObserved(
            Cancellation cancellation,
            ScenarioRehearsalBatchPolicy policy,
            MirrorOperationObservability.Observation observation) {
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(policy, "policy");
        SubmissionResult result = mutations.execute(status -> {
            QueuePartition partition =
                    partition(cancellation.scope());
            lockPartition(partition);
            Instant observedAt = coordinationNow();
            ensurePolicy(
                    partition,
                    policy,
                    observedAt);
            reconcile(
                    partition,
                    observedAt,
                    policy);
            StoredJob stored = byJob(
                    cancellation.scope(),
                    cancellation.jobId(),
                    true).orElseThrow(() ->
                    conflict(
                            Reason.JOB_NOT_FOUND,
                            "Scenario rehearsal batch was not found"));
            if (!stored.job().cancellationRequestId().isBlank()) {
                if (stored.job().cancellationRequestId().equals(
                        cancellation.commandId())
                        && stored.job().cancellationReasonCode()
                        .equals(cancellation.reasonCode())) {
                    return observed(
                            new SubmissionResult(
                                    stored.job(), true),
                            observation);
                }
                throw conflict(
                        Reason.CANCELLATION_CONFLICT,
                        "Scenario rehearsal batch already retained another cancellation intent");
            }
            if (stored.job().status().terminal()) {
                throw conflict(
                        Reason.CANCELLATION_CONFLICT,
                        "A terminal Scenario rehearsal batch cannot be cancelled");
            }
            ScenarioRehearsalBatchJob.Status nextStatus =
                    switch (stored.job().status()) {
                        case QUEUED ->
                                ScenarioRehearsalBatchJob.Status
                                        .CANCELLED;
                        case RUNNING ->
                                ScenarioRehearsalBatchJob.Status
                                        .CANCEL_REQUESTED;
                        default -> stored.job().status();
                    };
            if (nextStatus
                    == ScenarioRehearsalBatchJob.Status.CANCELLED) {
                cancelPending(
                        stored.job().jobId(),
                        observedAt,
                        "RG.MIRROR.REHEARSAL_BATCH.CANCELLED");
            }
            String terminalFailureCode =
                    nextStatus.terminal()
                            ? "RG.MIRROR.REHEARSAL_BATCH.CANCELLED"
                            : stored.job().failureCode();
            ScenarioRehearsalBatchJob updated =
                    transition(
                            stored.job(),
                            nextStatus,
                            summary(stored.job().jobId()),
                            terminalFailureCode,
                            cancellation.commandId(),
                            cancellation.reasonCode(),
                            observedAt,
                            nextStatus.terminal()
                                    ? observedAt : null);
            appendLifecycle(
                    stored,
                    ScenarioRehearsalBatchLifecycleAuditEvent
                            .Transition.CANCELLATION_REQUESTED,
                    updated,
                    null,
                    "",
                    cancellation.reasonCode());
            if (nextStatus.terminal()) {
                ScenarioRehearsalBatchJob terminal =
                        publishTerminal(
                                stored,
                                nextStatus,
                                terminalFailureCode,
                                cancellation.commandId(),
                                cancellation.reasonCode(),
                                observedAt);
                return observed(
                        new SubmissionResult(
                                terminal, false),
                        observation);
            }
            StoredJob successor =
                    new StoredJob(
                            updated,
                            stored.request(),
                            stored.manifest(),
                            stored.principal(),
                            stored.nextEligibleAt(),
                            stored.leaseOwner(),
                            stored.leaseEpoch(),
                            stored.leaseExpiresAt(),
                            stored.currentItemIndex(),
                            stored.expiresAt());
            updateJob(stored, successor);
            return observed(
                    new SubmissionResult(updated, false),
                    observation);
        });
        return required(
                result,
                "Scenario rehearsal batch cancellation returned no result");
    }

    @Override
    public Optional<ScenarioRehearsalBatchJob> find(
            CapabilitySnapshot.Scope scope,
            String jobId,
            ScenarioRehearsalBatchPolicy policy) {
        Objects.requireNonNull(scope, "scope");
        String id = required(jobId, "jobId");
        Objects.requireNonNull(policy, "policy");
        Optional<ScenarioRehearsalBatchJob> result =
                mutations.execute(status -> {
                    QueuePartition partition =
                            partition(scope);
                    lockPartition(partition);
                    Instant observedAt = coordinationNow();
                    ensurePolicy(
                            partition,
                            policy,
                            observedAt);
                    reconcile(
                            partition,
                            observedAt,
                            policy);
                    return byJob(scope, id, false)
                            .map(StoredJob::job);
                });
        return result == null ? Optional.empty() : result;
    }

    @Override
    public ScenarioRehearsalBatchItemPage page(
            CapabilitySnapshot.Scope scope,
            String jobId,
            int startIndex,
            int limit,
            ScenarioRehearsalBatchPolicy policy) {
        if (startIndex < 0
                || limit < 1
                || limit
                > ScenarioRehearsalBatchItemPage.MAXIMUM_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Scenario batch page bounds are invalid");
        }
        ScenarioRehearsalBatchJob job = find(
                scope, jobId, policy).orElseThrow(() ->
                conflict(
                        Reason.JOB_NOT_FOUND,
                        "Scenario rehearsal batch was not found"));
        List<ScenarioRehearsalBatchItemPage.Item> values =
                items(job.jobId()).stream()
                        .map(StoredItem::item)
                        .filter(item ->
                                item.itemIndex() >= startIndex)
                        .limit(limit)
                        .toList();
        Integer next = values.isEmpty()
                || values.get(values.size() - 1).itemIndex() + 1
                >= job.summary().totalItems()
                ? null
                : values.get(values.size() - 1).itemIndex() + 1;
        return new ScenarioRehearsalBatchItemPage(
                "",
                job.jobId(),
                job.manifestFingerprint(),
                values,
                next);
    }

    private Optional<StoredJob> selectNext(
            QueuePartition partition,
            Instant observedAt,
            ScenarioRehearsalBatchPolicy policy) {
        Cursor cursor = cursor(partition, observedAt);
        List<String> eligibleTenants = jdbc.queryForList("""
                SELECT DISTINCT tenant_id
                FROM scenario_rehearsal_batch_jobs
                WHERE region = ?
                  AND environment_id = ?
                  AND status = 'QUEUED'
                  AND next_eligible_at <= ?
                  AND tenant_id IN (
                    SELECT candidate.tenant_id
                    FROM scenario_rehearsal_batch_jobs candidate
                    WHERE candidate.region = ?
                      AND candidate.environment_id = ?
                    GROUP BY candidate.tenant_id
                    HAVING SUM(CASE
                        WHEN candidate.status IN ('RUNNING', 'CANCEL_REQUESTED')
                          AND candidate.lease_expires_at > ?
                        THEN 1 ELSE 0 END) < ?
                  )
                ORDER BY tenant_id
                """,
                String.class,
                partition.region(),
                partition.environmentId(),
                timestamp(observedAt),
                partition.region(),
                partition.environmentId(),
                timestamp(observedAt),
                policy.maximumRunningPerTenant());
        if (eligibleTenants.isEmpty()) {
            return Optional.empty();
        }
        String tenant = nextTenant(
                eligibleTenants, cursor.lastTenantId());
        List<StoredJob> candidates = jdbc.query("""
                SELECT *
                FROM scenario_rehearsal_batch_jobs
                WHERE region = ?
                  AND environment_id = ?
                  AND tenant_id = ?
                  AND status = 'QUEUED'
                  AND next_eligible_at <= ?
                ORDER BY created_at, job_id
                FOR UPDATE
                """,
                this::mapJob,
                partition.region(),
                partition.environmentId(),
                tenant,
                timestamp(observedAt));
        return candidates.stream()
                .min(Comparator
                        .comparingInt((StoredJob value) ->
                                effectivePriority(
                                        value,
                                        observedAt,
                                        policy))
                        .reversed()
                        .thenComparing(value ->
                                value.job().createdAt())
                        .thenComparing(value ->
                                value.job().jobId()));
    }

    private int effectivePriority(
            StoredJob job,
            Instant observedAt,
            ScenarioRehearsalBatchPolicy policy) {
        long waited = Math.max(
                0,
                Duration.between(
                        job.job().createdAt(),
                        observedAt).toMillis());
        long aging = waited
                / policy.priorityAgingInterval().toMillis();
        return Math.toIntExact(Math.min(
                1_000,
                job.job().priority().weight() + aging));
    }

    private ScenarioRehearsalBatchJob advanceAfterTerminalItem(
            StoredJob stored,
            ScenarioRehearsalBatchItemPage.Item terminal,
            String failureCode,
            Instant observedAt,
            ScenarioRehearsalBatchPolicy policy) {
        if (stored.job().status()
                == ScenarioRehearsalBatchJob.Status
                .CANCEL_REQUESTED) {
            cancelPending(
                    stored.job().jobId(),
                    observedAt,
                    "RG.MIRROR.REHEARSAL_BATCH.CANCELLED");
            return publishTerminal(
                    stored,
                    ScenarioRehearsalBatchJob.Status.CANCELLED,
                    "RG.MIRROR.REHEARSAL_BATCH.CANCELLED",
                    observedAt);
        }
        if (!stored.job().deadlineAt().isAfter(observedAt)) {
            cancelPending(
                    stored.job().jobId(),
                    observedAt,
                    "RG.MIRROR.REHEARSAL_BATCH.DEADLINE_EXCEEDED");
            return publishTerminal(
                    stored,
                    ScenarioRehearsalBatchJob.Status.EXPIRED,
                    "RG.MIRROR.REHEARSAL_BATCH.DEADLINE_EXCEEDED",
                    observedAt);
        }
        boolean nonPassing =
                terminal.status()
                        != ScenarioRehearsalBatchItemPage.Status.PASSED;
        if (nonPassing
                && stored.job().failureMode()
                == ScenarioRehearsalBatchPolicy.FailureMode
                .FAIL_FAST) {
            cancelPending(
                    stored.job().jobId(),
                    observedAt,
                    "RG.MIRROR.REHEARSAL_BATCH.FAIL_FAST");
            return publishTerminal(
                    stored,
                    ScenarioRehearsalBatchJob.Status.FAILED,
                    failureCode,
                    observedAt);
        }
        ScenarioRehearsalBatchJob.Summary summary =
                summary(stored.job().jobId());
        if (summary.completedItems() == summary.totalItems()) {
            ScenarioRehearsalBatchJob.Status terminalStatus =
                    summary.passedItems() == summary.totalItems()
                            ? ScenarioRehearsalBatchJob.Status
                            .SUCCEEDED
                            : ScenarioRehearsalBatchJob.Status
                            .PARTIAL;
            String terminalFailure =
                    terminalStatus
                            == ScenarioRehearsalBatchJob.Status.SUCCEEDED
                            ? ""
                            : !failureCode.isBlank()
                            ? failureCode
                            : !stored.job().failureCode().isBlank()
                            ? stored.job().failureCode()
                            : "RG.MIRROR.REHEARSAL_BATCH.NON_PASSING_ITEM";
            return publishTerminal(
                    stored,
                    terminalStatus,
                    terminalFailure,
                    observedAt);
        }
        ScenarioRehearsalBatchJob queued =
                transition(
                        stored.job(),
                        ScenarioRehearsalBatchJob.Status.QUEUED,
                        summary,
                        failureCode.isBlank()
                                ? stored.job().failureCode()
                                : failureCode,
                        stored.job().cancellationRequestId(),
                        stored.job().cancellationReasonCode(),
                        observedAt,
                        null);
        StoredJob successor = idle(
                stored, queued, observedAt);
        updateJob(stored, successor);
        return queued;
    }

    private ScenarioRehearsalBatchJob terminalFailure(
            StoredJob stored,
            StoredItem current,
            String failureCode,
            Instant observedAt,
            ScenarioRehearsalBatchPolicy policy) {
        ScenarioRehearsalBatchItemPage.Item failed =
                new ScenarioRehearsalBatchItemPage.Item(
                        current.item().itemIndex(),
                        current.item().compiledPlanRef(),
                        current.item().childRequestId(),
                        ScenarioRehearsalBatchItemPage.Status.FAILED,
                        current.item().attemptCount(),
                        "", "", "", failureCode,
                        current.item().startedAt(),
                        observedAt);
        updateItem(
                stored.job().jobId(),
                current,
                new StoredItem(
                        failed,
                        current.executionTimeout()));
        appendLifecycle(
                stored,
                ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.ITEM_TERMINALIZED,
                stored.job(),
                failed,
                "",
                failureCode);
        return advanceAfterTerminalItem(
                stored,
                failed,
                failureCode,
                observedAt,
                policy);
    }

    private ScenarioRehearsalBatchJob terminalizeRemaining(
            StoredJob stored,
            StoredItem current,
            ScenarioRehearsalBatchJob.Status status,
            String failureCode,
            Instant observedAt) {
        ScenarioRehearsalBatchItemPage.Item item =
                current.item();
        if (item.status()
                == ScenarioRehearsalBatchItemPage.Status.RUNNING) {
            ScenarioRehearsalBatchItemPage.Item uncertain =
                    new ScenarioRehearsalBatchItemPage.Item(
                            item.itemIndex(),
                            item.compiledPlanRef(),
                            item.childRequestId(),
                            ScenarioRehearsalBatchItemPage.Status
                                    .INDETERMINATE,
                            item.attemptCount(),
                            "", "", "",
                            failureCode,
                            item.startedAt(),
                            observedAt);
            updateItem(
                    stored.job().jobId(),
                    current,
                    new StoredItem(
                            uncertain,
                            current.executionTimeout()));
            appendLifecycle(
                    stored,
                    ScenarioRehearsalBatchLifecycleAuditEvent
                            .Transition.ITEM_TERMINALIZED,
                    stored.job(),
                    uncertain,
                    "",
                    failureCode);
        }
        cancelPending(
                stored.job().jobId(),
                observedAt,
                failureCode);
        return publishTerminal(
                stored, status, failureCode, observedAt);
    }

    private ScenarioRehearsalBatchJob publishTerminal(
            StoredJob stored,
            ScenarioRehearsalBatchJob.Status status,
            String failureCode,
            Instant observedAt) {
        return publishTerminal(
                stored,
                status,
                failureCode,
                stored.job().cancellationRequestId(),
                stored.job().cancellationReasonCode(),
                observedAt);
    }

    private ScenarioRehearsalBatchJob publishTerminal(
            StoredJob stored,
            ScenarioRehearsalBatchJob.Status status,
            String failureCode,
            String cancellationRequestId,
            String cancellationReasonCode,
            Instant observedAt) {
        ScenarioRehearsalBatchJob terminal =
                transition(
                        stored.job(),
                        status,
                        summary(stored.job().jobId()),
                        failureCode,
                        cancellationRequestId,
                        cancellationReasonCode,
                        observedAt,
                        observedAt);
        ScenarioRehearsalBatchEvidenceBundle evidence =
                evidencePublisher.publish(
                        stored.request(),
                        stored.manifest(),
                        terminal,
                        items(stored.job().jobId()).stream()
                                .map(StoredItem::item)
                                .toList(),
                        stored.expiresAt());
        updateJob(
                stored,
                idle(stored, terminal, Instant.EPOCH));
        appendLifecycle(
                stored,
                ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.TERMINALIZED,
                terminal,
                null,
                evidence.bundleFingerprint(),
                failureCode);
        return terminal;
    }

    private void reconcile(
            QueuePartition partition,
            Instant observedAt,
            ScenarioRehearsalBatchPolicy policy) {
        List<String> stale = jdbc.queryForList("""
                SELECT job_id
                FROM scenario_rehearsal_batch_jobs
                WHERE region = ?
                  AND environment_id = ?
                  AND (
                    status = 'QUEUED' AND deadline_at <= ?
                    OR status IN ('RUNNING', 'CANCEL_REQUESTED')
                       AND lease_expires_at <= ?
                  )
                ORDER BY updated_at, job_id
                LIMIT ?
                """,
                String.class,
                partition.region(),
                partition.environmentId(),
                timestamp(observedAt),
                timestamp(observedAt),
                RECONCILIATION_LIMIT);
        for (String jobId : stale) {
            StoredJob stored = byJobId(jobId, true)
                    .orElseThrow(() ->
                            new IllegalStateException(
                                    "Locked Scenario batch disappeared"));
            if (stored.job().status()
                    == ScenarioRehearsalBatchJob.Status.QUEUED) {
                Optional<StoredItem> current =
                        firstNonTerminal(jobId);
                if (current.isPresent()) {
                    terminalizeRemaining(
                            stored,
                            current.orElseThrow(),
                            ScenarioRehearsalBatchJob.Status
                                    .EXPIRED,
                            "RG.MIRROR.REHEARSAL_BATCH.DEADLINE_EXCEEDED",
                            observedAt);
                }
                continue;
            }
            StoredItem current = item(
                    jobId,
                    stored.currentItemIndex(),
                    true).orElseThrow(() ->
                    new IllegalStateException(
                            "Stale Scenario batch lease lost its current item"));
            if (stored.job().status()
                    == ScenarioRehearsalBatchJob.Status
                    .CANCEL_REQUESTED) {
                terminalizeRemaining(
                        stored,
                        current,
                        ScenarioRehearsalBatchJob.Status
                                .CANCELLED,
                        "RG.MIRROR.REHEARSAL_BATCH.CANCELLED_AFTER_LEASE",
                        observedAt);
                continue;
            }
            if (!stored.job().deadlineAt()
                    .isAfter(observedAt)) {
                terminalizeRemaining(
                        stored,
                        current,
                        ScenarioRehearsalBatchJob.Status
                                .EXPIRED,
                        "RG.MIRROR.REHEARSAL_BATCH.DEADLINE_EXCEEDED",
                        observedAt);
                continue;
            }
            if (current.item().attemptCount()
                    >= stored.job().maximumItemAttempts()) {
                ScenarioRehearsalBatchItemPage.Item failed =
                        new ScenarioRehearsalBatchItemPage.Item(
                                current.item().itemIndex(),
                                current.item().compiledPlanRef(),
                                current.item().childRequestId(),
                                ScenarioRehearsalBatchItemPage.Status
                                        .FAILED,
                                current.item().attemptCount(),
                                "", "", "",
                                "RG.MIRROR.REHEARSAL_BATCH.LEASE_EXPIRED",
                                current.item().startedAt(),
                                observedAt);
                updateItem(
                        stored.job().jobId(),
                        current,
                        new StoredItem(
                                failed,
                                current.executionTimeout()));
                appendLifecycle(
                        stored,
                        ScenarioRehearsalBatchLifecycleAuditEvent
                                .Transition.ITEM_TERMINALIZED,
                        stored.job(),
                        failed,
                        "",
                        "RG.MIRROR.REHEARSAL_BATCH.LEASE_EXPIRED");
                advanceAfterTerminalItem(
                        stored,
                        failed,
                        "RG.MIRROR.REHEARSAL_BATCH.LEASE_EXPIRED",
                        observedAt,
                        policy);
                continue;
            }
            ScenarioRehearsalBatchItemPage.Item pending =
                    new ScenarioRehearsalBatchItemPage.Item(
                            current.item().itemIndex(),
                            current.item().compiledPlanRef(),
                            current.item().childRequestId(),
                            ScenarioRehearsalBatchItemPage.Status
                                    .PENDING,
                            current.item().attemptCount(),
                            "", "", "",
                            "RG.MIRROR.REHEARSAL_BATCH.LEASE_EXPIRED",
                            current.item().startedAt(),
                            null);
            updateItem(
                    stored.job().jobId(),
                    current,
                    new StoredItem(
                            pending,
                            current.executionTimeout()));
            ScenarioRehearsalBatchJob queued =
                    transition(
                            stored.job(),
                            ScenarioRehearsalBatchJob.Status.QUEUED,
                            summary(jobId),
                            "RG.MIRROR.REHEARSAL_BATCH.LEASE_EXPIRED",
                            stored.job().cancellationRequestId(),
                            stored.job().cancellationReasonCode(),
                            observedAt,
                            null);
            updateJob(
                    stored,
                    idle(
                            stored,
                            queued,
                            safePlus(
                                    observedAt,
                                    policy.retryBackoff())));
            appendLifecycle(
                    stored,
                    ScenarioRehearsalBatchLifecycleAuditEvent
                            .Transition.ITEM_RETRY_SCHEDULED,
                    queued,
                    pending,
                    "",
                    "RG.MIRROR.REHEARSAL_BATCH.LEASE_EXPIRED");
        }
    }

    private void requireDeadline(
            Submission submission,
            ScenarioRehearsalBatchPolicy policy,
            Instant observedAt) {
        for (ScenarioRehearsalBatchManifest.Entry entry
                : submission.manifest().entries()) {
            if (entry.executionTimeout()
                    .compareTo(policy.maximumPlanTimeout()) > 0) {
                throw conflict(
                        Reason.PLAN_TIMEOUT_EXCEEDED,
                        "A Scenario rehearsal plan exceeds the batch execution-time policy");
            }
        }
    }

    private StoredJob requireLive(
            Lease lease,
            Instant observedAt) {
        StoredJob stored = byJob(
                lease.scope(), lease.jobId(), true)
                .orElseThrow(() ->
                        conflict(
                                Reason.LEASE_LOST,
                                "Scenario rehearsal batch lease no longer exists"));
        if (!liveLease(stored, lease, observedAt)) {
            throw conflict(
                    Reason.LEASE_LOST,
                    "Scenario rehearsal batch lease was lost");
        }
        return stored;
    }

    private static boolean liveLease(
            StoredJob stored,
            Lease lease,
            Instant observedAt) {
        return sameLease(stored, lease)
                && stored.leaseExpiresAt().isAfter(observedAt);
    }

    private static boolean sameLease(
            StoredJob stored,
            Lease lease) {
        return stored.leaseOwner().equals(lease.ownerId())
                && stored.leaseEpoch() == lease.epoch()
                && stored.currentItemIndex() == lease.itemIndex()
                && stored.leaseExpiresAt().equals(lease.expiresAt())
                && (stored.job().status()
                == ScenarioRehearsalBatchJob.Status.RUNNING
                || stored.job().status()
                == ScenarioRehearsalBatchJob.Status
                .CANCEL_REQUESTED);
    }

    private StoredHeartbeat heartbeat(String jobId) {
        List<StoredHeartbeat> values = jdbc.query("""
                SELECT heartbeat_count, heartbeat_case_index
                FROM scenario_rehearsal_batch_jobs
                WHERE job_id = ?
                """,
                (rs, rowNum) -> new StoredHeartbeat(
                        rs.getLong("heartbeat_count"),
                        rs.getInt("heartbeat_case_index")),
                jobId);
        if (values.size() != 1) {
            throw new IllegalStateException(
                    "Scenario batch heartbeat state is unavailable");
        }
        return values.getFirst();
    }

    private void resetExecutionCheckpoint(
            StoredJob claimed,
            Instant observedAt) {
        int updated = jdbc.update("""
                UPDATE scenario_rehearsal_batch_jobs
                SET heartbeat_at = ?,
                    heartbeat_count = 0,
                    heartbeat_case_index = 0
                WHERE job_id = ?
                  AND lease_owner = ?
                  AND lease_epoch = ?
                  AND current_item_index = ?
                """,
                timestamp(observedAt),
                claimed.job().jobId(),
                claimed.leaseOwner(),
                claimed.leaseEpoch(),
                claimed.currentItemIndex());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Scenario batch heartbeat reset lost its lease");
        }
    }

    private void updateExecutionCheckpoint(
            StoredJob stored,
            Lease lease,
            Instant observedAt,
            long heartbeatCount,
            int nextCaseIndex) {
        int updated = jdbc.update("""
                UPDATE scenario_rehearsal_batch_jobs
                SET heartbeat_at = ?,
                    heartbeat_count = ?,
                    heartbeat_case_index = ?
                WHERE job_id = ?
                  AND lease_owner = ?
                  AND lease_epoch = ?
                  AND lease_expires_at = ?
                  AND current_item_index = ?
                  AND status IN ('RUNNING', 'CANCEL_REQUESTED')
                """,
                timestamp(observedAt),
                heartbeatCount,
                nextCaseIndex,
                stored.job().jobId(),
                lease.ownerId(),
                lease.epoch(),
                timestamp(lease.expiresAt()),
                lease.itemIndex());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Scenario batch heartbeat lost its lease");
        }
    }

    private StoredItem requireRunningItem(
            StoredJob stored,
            Lease lease) {
        StoredItem item = item(
                stored.job().jobId(),
                lease.itemIndex(),
                true).orElseThrow(() ->
                new IllegalStateException(
                        "Scenario batch current item is unavailable"));
        if (item.item().status()
                != ScenarioRehearsalBatchItemPage.Status.RUNNING) {
            throw conflict(
                    Reason.LEASE_LOST,
                    "Scenario rehearsal batch item is no longer running");
        }
        return item;
    }

    private void cancelPending(
            String jobId,
            Instant observedAt,
            String failureCode) {
        for (StoredItem item : items(jobId)) {
            if (item.item().status()
                    != ScenarioRehearsalBatchItemPage.Status.PENDING) {
                continue;
            }
            ScenarioRehearsalBatchItemPage.Item cancelled =
                    new ScenarioRehearsalBatchItemPage.Item(
                            item.item().itemIndex(),
                            item.item().compiledPlanRef(),
                            item.item().childRequestId(),
                            ScenarioRehearsalBatchItemPage.Status
                                    .CANCELLED,
                            item.item().attemptCount(),
                            "", "", "",
                            failureCode,
                            item.item().startedAt(),
                            observedAt);
            updateItem(
                    jobId,
                    item,
                    new StoredItem(
                            cancelled,
                            item.executionTimeout()));
        }
    }

    private ScenarioRehearsalBatchJob.Summary summary(
            String jobId) {
        List<StoredItem> values = items(jobId);
        int passed = count(
                values,
                ScenarioRehearsalBatchItemPage.Status.PASSED);
        int failed = count(
                values,
                ScenarioRehearsalBatchItemPage.Status.FAILED);
        int indeterminate = count(
                values,
                ScenarioRehearsalBatchItemPage.Status
                        .INDETERMINATE);
        int cancelled = count(
                values,
                ScenarioRehearsalBatchItemPage.Status
                        .CANCELLED);
        return new ScenarioRehearsalBatchJob.Summary(
                values.size(),
                passed + failed + indeterminate + cancelled,
                passed,
                failed,
                indeterminate,
                cancelled);
    }

    private static int count(
            List<StoredItem> values,
            ScenarioRehearsalBatchItemPage.Status status) {
        return Math.toIntExact(
                values.stream()
                        .filter(value ->
                                value.item().status() == status)
                        .count());
    }

    private ScenarioRehearsalBatchJob transition(
            ScenarioRehearsalBatchJob source,
            ScenarioRehearsalBatchJob.Status status,
            ScenarioRehearsalBatchJob.Summary summary,
            String failureCode,
            String cancellationRequestId,
            String cancellationReasonCode,
            Instant updatedAt,
            Instant completedAt) {
        return ScenarioRehearsalBatchIntegrity.seal(
                mapper,
                new ScenarioRehearsalBatchJob(
                        source.schemaVersion(),
                        source.jobId(),
                        source.requestId(),
                        source.requestFingerprint(),
                        source.manifestFingerprint(),
                        source.scope(),
                        status,
                        source.failureMode(),
                        source.priority(),
                        source.maximumItemAttempts(),
                        summary,
                        source.deadlineAt(),
                        failureCode,
                        cancellationRequestId,
                        cancellationReasonCode,
                        source.createdAt(),
                        updatedAt,
                        completedAt,
                        ""));
    }

    private StoredJob idle(
            StoredJob source,
            ScenarioRehearsalBatchJob job,
            Instant nextEligibleAt) {
        return new StoredJob(
                job,
                source.request(),
                source.manifest(),
                source.principal(),
                nextEligibleAt,
                "",
                source.leaseEpoch(),
                Instant.EPOCH,
                -1,
                source.expiresAt());
    }

    private ScenarioRehearsalBatchItemPage.Item pending(
            ScenarioRehearsalBatchManifest.Entry entry) {
        return new ScenarioRehearsalBatchItemPage.Item(
                entry.entryIndex(),
                entry.compiledPlanRef(),
                entry.aggregateRequestId(),
                ScenarioRehearsalBatchItemPage.Status.PENDING,
                0,
                "", "", "", "",
                null, null);
    }

    private void requireSameSubmission(
            StoredJob stored,
            Submission submission) {
        if (!stored.job().jobId().equals(
                submission.manifest().batchId())
                || !stored.job().requestFingerprint().equals(
                submission.requestFingerprint())
                || !stored.job().manifestFingerprint().equals(
                submission.manifest().manifestFingerprint())
                || !stored.request().equals(submission.request())
                || !stored.manifest().equals(
                submission.manifest())
                || !stored.principal().equals(
                submission.principal())
                || !stored.job().scope().equals(
                submission.manifest().scope())) {
            throw conflict(
                    Reason.IDEMPOTENCY_CONFLICT,
                    "Scenario rehearsal batch request id already identifies different immutable inputs");
        }
        List<StoredItem> retained =
                items(stored.job().jobId());
        if (retained.size()
                != submission.manifest().entries().size()) {
            throw new IllegalStateException(
                    "Scenario rehearsal batch manifest is incomplete");
        }
        for (int index = 0; index < retained.size(); index++) {
            StoredItem existing = retained.get(index);
            ScenarioRehearsalBatchManifest.Entry expected =
                    submission.manifest().entries().get(index);
            if (existing.item().itemIndex()
                    != expected.entryIndex()
                    || !existing.item().compiledPlanRef()
                    .equals(expected.compiledPlanRef())
                    || !existing.item().childRequestId()
                    .equals(expected.aggregateRequestId())
                    || !existing.executionTimeout()
                    .equals(expected.executionTimeout())) {
                throw new IllegalStateException(
                        "Scenario rehearsal batch manifest failed retained integrity");
            }
        }
    }

    private void lockPartition(QueuePartition partition) {
        String coordinationKey = coordinationKey(partition);
        try {
            jdbc.update("""
                    INSERT INTO scenario_rehearsal_batch_locks (
                        environment_id
                    ) VALUES (?)
                    """, coordinationKey);
        } catch (DuplicateKeyException existing) {
            // The existing row is the intended cross-replica authority.
        }
        String locked = jdbc.queryForObject("""
                SELECT environment_id
                FROM scenario_rehearsal_batch_locks
                WHERE environment_id = ?
                FOR UPDATE
                """, String.class, coordinationKey);
        if (!coordinationKey.equals(locked)) {
            throw new IllegalStateException(
                    "Scenario batch regional partition lock is unavailable");
        }
    }

    private void ensurePolicy(
            QueuePartition partition,
            ScenarioRehearsalBatchPolicy policy,
            Instant observedAt) {
        String coordinationKey = coordinationKey(partition);
        String fingerprint = ProtocolFingerprint.of(
                mapper, policy);
        List<PolicyState> states = jdbc.query("""
                SELECT policy_generation, policy_fingerprint
                FROM scenario_rehearsal_batch_policies
                WHERE environment_id = ?
                FOR UPDATE
                """,
                (rs, row) -> new PolicyState(
                        rs.getLong("policy_generation"),
                        rs.getString("policy_fingerprint")),
                coordinationKey);
        if (states.isEmpty()) {
            jdbc.update("""
                    INSERT INTO scenario_rehearsal_batch_policies (
                        environment_id, policy_generation,
                        policy_fingerprint, updated_at
                    ) VALUES (?, ?, ?, ?)
                    """,
                    coordinationKey,
                    policy.generation(),
                    fingerprint,
                    timestamp(observedAt));
            return;
        }
        PolicyState stored = states.getFirst();
        if (policy.generation() < stored.generation()
                || policy.generation() == stored.generation()
                && !fingerprint.equals(stored.fingerprint())) {
            throw conflict(
                    Reason.POLICY_MISMATCH,
                    "Scenario batch queue replicas disagree on active policy");
        }
        if (policy.generation() > stored.generation()) {
            int updated = jdbc.update("""
                    UPDATE scenario_rehearsal_batch_policies
                    SET policy_generation = ?,
                        policy_fingerprint = ?,
                        updated_at = ?
                    WHERE environment_id = ?
                      AND policy_generation = ?
                      AND policy_fingerprint = ?
                    """,
                    policy.generation(),
                    fingerprint,
                    timestamp(observedAt),
                    coordinationKey,
                    stored.generation(),
                    stored.fingerprint());
            if (updated != 1) {
                throw new IllegalStateException(
                        "Scenario batch policy changed while locked");
            }
        }
    }

    private Cursor cursor(
            QueuePartition partition,
            Instant observedAt) {
        String coordinationKey = coordinationKey(partition);
        List<Cursor> values = jdbc.query("""
                SELECT last_tenant_id, cycle_epoch
                FROM scenario_rehearsal_batch_cursors
                WHERE environment_id = ?
                FOR UPDATE
                """,
                (rs, row) -> new Cursor(
                        rs.getString("last_tenant_id"),
                        rs.getLong("cycle_epoch")),
                coordinationKey);
        if (values.isEmpty()) {
            jdbc.update("""
                    INSERT INTO scenario_rehearsal_batch_cursors (
                        environment_id, last_tenant_id,
                        cycle_epoch, updated_at
                    ) VALUES (?, '', 0, ?)
                    """,
                    coordinationKey,
                    timestamp(observedAt));
            return new Cursor(NO_TENANT, 0);
        }
        return values.getFirst();
    }

    private void advanceCursor(
            QueuePartition partition,
            String tenant,
            Instant observedAt) {
        Cursor cursor = cursor(partition, observedAt);
        String coordinationKey = coordinationKey(partition);
        long epoch = tenant.compareTo(
                cursor.lastTenantId()) <= 0
                ? Math.addExact(cursor.cycleEpoch(), 1)
                : cursor.cycleEpoch();
        int updated = jdbc.update("""
                UPDATE scenario_rehearsal_batch_cursors
                SET last_tenant_id = ?, cycle_epoch = ?,
                    updated_at = ?
                WHERE environment_id = ?
                  AND last_tenant_id = ?
                  AND cycle_epoch = ?
                """,
                tenant,
                epoch,
                timestamp(observedAt),
                coordinationKey,
                cursor.lastTenantId(),
                cursor.cycleEpoch());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Scenario batch fairness cursor changed while locked");
        }
    }

    private static String nextTenant(
            List<String> tenants,
            String lastTenant) {
        return tenants.stream()
                .filter(value ->
                        value.compareTo(lastTenant) > 0)
                .findFirst()
                .orElse(tenants.getFirst());
    }

    private long activeCount(
            QueuePartition partition,
            String tenant) {
        String tenantClause =
                tenant == null ? "" : " AND tenant_id = ?";
        String sql = """
                SELECT COUNT(*)
                FROM scenario_rehearsal_batch_jobs
                WHERE region = ?
                  AND environment_id = ?
                  AND status IN (
                    'QUEUED', 'RUNNING', 'CANCEL_REQUESTED'
                  )
                """ + tenantClause;
        Long count = tenant == null
                ? jdbc.queryForObject(
                sql, Long.class,
                partition.region(),
                partition.environmentId())
                : jdbc.queryForObject(
                sql, Long.class,
                partition.region(),
                partition.environmentId(),
                tenant);
        return count == null ? 0 : count;
    }

    private long liveRunningCount(
            QueuePartition partition,
            String tenant,
            Instant observedAt) {
        String tenantClause =
                tenant == null ? "" : " AND tenant_id = ?";
        String sql = """
                SELECT COUNT(*)
                FROM scenario_rehearsal_batch_jobs
                WHERE region = ?
                  AND environment_id = ?
                  AND status IN ('RUNNING', 'CANCEL_REQUESTED')
                  AND lease_expires_at > ?
                """ + tenantClause;
        Long count = tenant == null
                ? jdbc.queryForObject(
                sql,
                Long.class,
                partition.region(),
                partition.environmentId(),
                timestamp(observedAt))
                : jdbc.queryForObject(
                sql,
                Long.class,
                partition.region(),
                partition.environmentId(),
                timestamp(observedAt),
                tenant);
        return count == null ? 0 : count;
    }

    private Optional<StoredJob> byRequest(
            CapabilitySnapshot.Scope scope,
            String requestId,
            boolean forUpdate) {
        String sql = """
                SELECT *
                FROM scenario_rehearsal_batch_jobs
                WHERE tenant_id = ?
                  AND organization_id = ?
                  AND project_id = ?
                  AND environment_id = ?
                  AND region = ?
                  AND request_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return one(jdbc.query(
                sql,
                this::mapJob,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                requestId));
    }

    private Optional<StoredJob> byJob(
            CapabilitySnapshot.Scope scope,
            String jobId,
            boolean forUpdate) {
        String sql = """
                SELECT *
                FROM scenario_rehearsal_batch_jobs
                WHERE tenant_id = ?
                  AND organization_id = ?
                  AND project_id = ?
                  AND environment_id = ?
                  AND region = ?
                  AND job_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return one(jdbc.query(
                sql,
                this::mapJob,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                jobId));
    }

    private Optional<StoredJob> byJobId(
            String jobId,
            boolean forUpdate) {
        String sql = """
                SELECT *
                FROM scenario_rehearsal_batch_jobs
                WHERE job_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return one(jdbc.query(
                sql, this::mapJob, jobId));
    }

    private Optional<StoredItem> firstNonTerminal(
            String jobId) {
        List<StoredItem> values = jdbc.query("""
                SELECT *
                FROM scenario_rehearsal_batch_items
                WHERE job_id = ?
                  AND status IN ('PENDING', 'RUNNING')
                ORDER BY item_index
                LIMIT 1
                FOR UPDATE
                """,
                this::mapItem,
                jobId);
        return one(values);
    }

    private Optional<StoredItem> item(
            String jobId,
            int itemIndex,
            boolean forUpdate) {
        String sql = """
                SELECT *
                FROM scenario_rehearsal_batch_items
                WHERE job_id = ?
                  AND item_index = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return one(jdbc.query(
                sql,
                this::mapItem,
                jobId,
                itemIndex));
    }

    private List<StoredItem> items(String jobId) {
        return jdbc.query("""
                SELECT *
                FROM scenario_rehearsal_batch_items
                WHERE job_id = ?
                ORDER BY item_index
                """,
                this::mapItem,
                jobId);
    }

    private void insertJob(StoredJob stored) {
        ScenarioRehearsalBatchJob job = stored.job();
        CapabilitySnapshot.Scope scope = job.scope();
        jdbc.update("""
                INSERT INTO scenario_rehearsal_batch_jobs (
                    job_id, tenant_id, organization_id,
                    project_id, environment_id, region,
                    request_id, request_fingerprint,
                    manifest_fingerprint, request_json,
                    manifest_json, principal_json, status, base_priority,
                    next_eligible_at, lease_owner,
                    lease_epoch, lease_expires_at,
                    current_item_index, deadline_at,
                    created_at, updated_at, expires_at,
                    record_fingerprint, job_json
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                job.jobId(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                job.requestId(),
                job.requestFingerprint(),
                job.manifestFingerprint(),
                json(stored.request()),
                json(stored.manifest()),
                json(stored.principal()),
                job.status().name(),
                job.priority().weight(),
                timestamp(stored.nextEligibleAt()),
                stored.leaseOwner(),
                stored.leaseEpoch(),
                timestamp(stored.leaseExpiresAt()),
                stored.currentItemIndex(),
                timestamp(job.deadlineAt()),
                timestamp(job.createdAt()),
                timestamp(job.updatedAt()),
                timestamp(stored.expiresAt()),
                job.recordFingerprint(),
                json(job));
    }

    private void updateJob(
            StoredJob previous,
            StoredJob successor) {
        ScenarioRehearsalBatchJob next =
                successor.job();
        int updated = jdbc.update("""
                UPDATE scenario_rehearsal_batch_jobs
                SET status = ?,
                    next_eligible_at = ?,
                    lease_owner = ?,
                    lease_epoch = ?,
                    lease_expires_at = ?,
                    current_item_index = ?,
                    updated_at = ?,
                    expires_at = ?,
                    record_fingerprint = ?,
                    job_json = ?
                WHERE job_id = ?
                  AND record_fingerprint = ?
                  AND lease_epoch = ?
                """,
                next.status().name(),
                timestamp(successor.nextEligibleAt()),
                successor.leaseOwner(),
                successor.leaseEpoch(),
                timestamp(successor.leaseExpiresAt()),
                successor.currentItemIndex(),
                timestamp(next.updatedAt()),
                timestamp(successor.expiresAt()),
                next.recordFingerprint(),
                json(next),
                previous.job().jobId(),
                previous.job().recordFingerprint(),
                previous.leaseEpoch());
        if (updated != 1) {
            throw new IllegalStateException(
                    "Scenario rehearsal batch changed while locked");
        }
    }

    private void insertItem(
            String jobId,
            StoredItem stored) {
        ScenarioRehearsalBatchItemPage.Item item =
                stored.item();
        String fingerprint = itemFingerprint(stored);
        jdbc.update("""
                INSERT INTO scenario_rehearsal_batch_items (
                    job_id, item_index, compiled_plan_id,
                    compiled_plan_revision,
                    compiled_plan_fingerprint,
                    child_request_id,
                    execution_timeout_millis, status,
                    attempt_count, run_id,
                    evidence_bundle_fingerprint,
                    workbook_seed_fingerprint, failure_code,
                    started_at, completed_at,
                    record_fingerprint
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                jobId,
                item.itemIndex(),
                item.compiledPlanRef().id(),
                item.compiledPlanRef().revision(),
                item.compiledPlanRef().fingerprint(),
                item.childRequestId(),
                stored.executionTimeout().toMillis(),
                item.status().name(),
                item.attemptCount(),
                item.runId(),
                item.evidenceBundleFingerprint(),
                item.workbookSeedFingerprint(),
                item.failureCode(),
                timestamp(item.startedAt()),
                timestamp(item.completedAt()),
                fingerprint);
    }

    private void updateItem(
            String jobId,
            StoredItem previous,
            StoredItem successor) {
        ScenarioRehearsalBatchItemPage.Item item =
                successor.item();
        String previousFingerprint =
                itemFingerprint(previous);
        String nextFingerprint =
                itemFingerprint(successor);
        int updated = jdbc.update("""
                UPDATE scenario_rehearsal_batch_items
                SET status = ?, attempt_count = ?,
                    run_id = ?,
                    evidence_bundle_fingerprint = ?,
                    workbook_seed_fingerprint = ?,
                    failure_code = ?, started_at = ?,
                    completed_at = ?, record_fingerprint = ?
                WHERE child_request_id = ?
                  AND job_id = ?
                  AND item_index = ?
                  AND record_fingerprint = ?
                """,
                item.status().name(),
                item.attemptCount(),
                item.runId(),
                item.evidenceBundleFingerprint(),
                item.workbookSeedFingerprint(),
                item.failureCode(),
                timestamp(item.startedAt()),
                timestamp(item.completedAt()),
                nextFingerprint,
                item.childRequestId(),
                jobId,
                item.itemIndex(),
                previousFingerprint);
        if (updated != 1) {
            throw new IllegalStateException(
                    "Scenario rehearsal batch item changed while locked");
        }
    }

    private StoredJob mapJob(
            ResultSet rs,
            int row) throws SQLException {
        ScenarioRehearsalBatchJob job = read(
                rs.getString("job_json"),
                ScenarioRehearsalBatchJob.class);
        try {
            ScenarioRehearsalBatchIntegrity.verify(
                    mapper, job);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "Stored Scenario batch job failed integrity validation",
                    invalid);
        }
        if (!job.jobId().equals(rs.getString("job_id"))
                || !job.status().name().equals(
                rs.getString("status"))
                || !job.recordFingerprint().equals(
                rs.getString("record_fingerprint"))
                || !job.deadlineAt().equals(
                instant(rs, "deadline_at"))
                || job.priority().weight()
                != rs.getInt("base_priority")) {
            throw new IllegalStateException(
                    "Stored Scenario batch scheduling projection is inconsistent");
        }
        ScenarioRehearsalBatchRequest request = read(
                rs.getString("request_json"),
                ScenarioRehearsalBatchRequest.class);
        ScenarioRehearsalBatchManifest manifest = read(
                rs.getString("manifest_json"),
                ScenarioRehearsalBatchManifest.class);
        try {
            ScenarioRehearsalBatchManifestIntegrity.verify(
                    mapper, manifest);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException(
                    "Stored Scenario batch manifest failed integrity validation",
                    invalid);
        }
        ScenarioRehearsalBatchPrincipal principal = read(
                rs.getString("principal_json"),
                ScenarioRehearsalBatchPrincipal.class);
        if (!job.scope().equals(principal.scope())
                || !job.requestId().equals(request.requestId())
                || !job.jobId().equals(manifest.batchId())
                || !job.manifestFingerprint().equals(
                manifest.manifestFingerprint())
                || !job.requestFingerprint().equals(
                rs.getString("request_fingerprint"))
                || !job.manifestFingerprint().equals(
                rs.getString("manifest_fingerprint"))) {
            throw new IllegalStateException(
                    "Stored Scenario batch immutable closure is inconsistent");
        }
        return new StoredJob(
                job,
                request,
                manifest,
                principal,
                instant(rs, "next_eligible_at"),
                rs.getString("lease_owner"),
                rs.getLong("lease_epoch"),
                instant(rs, "lease_expires_at"),
                rs.getInt("current_item_index"),
                instant(rs, "expires_at"));
    }

    private StoredItem mapItem(
            ResultSet rs,
            int row) throws SQLException {
        ScenarioRehearsalBatchItemPage.Item item =
                new ScenarioRehearsalBatchItemPage.Item(
                        rs.getInt("item_index"),
                        new MirrorArtifactRef(
                                "COMPILED_REHEARSAL_PLAN",
                                rs.getString(
                                        "compiled_plan_id"),
                                rs.getLong(
                                        "compiled_plan_revision"),
                                rs.getString(
                                        "compiled_plan_fingerprint")),
                        rs.getString("child_request_id"),
                        ScenarioRehearsalBatchItemPage.Status
                                .valueOf(
                                        rs.getString("status")),
                        rs.getInt("attempt_count"),
                        rs.getString("run_id"),
                        rs.getString(
                                "evidence_bundle_fingerprint"),
                        rs.getString(
                                "workbook_seed_fingerprint"),
                        rs.getString("failure_code"),
                        instant(rs, "started_at"),
                        instant(rs, "completed_at"));
        StoredItem stored = new StoredItem(
                item,
                Duration.ofMillis(
                        rs.getLong(
                                "execution_timeout_millis")));
        if (!itemFingerprint(stored).equals(
                rs.getString("record_fingerprint"))) {
            throw new IllegalStateException(
                    "Stored Scenario batch item failed integrity validation");
        }
        return stored;
    }

    private String itemFingerprint(StoredItem stored) {
        LinkedHashMap<String, Object> material =
                new LinkedHashMap<>();
        material.put("item", stored.item());
        material.put(
                "executionTimeout",
                stored.executionTimeout());
        return ProtocolFingerprint.ofBounded(
                mapper, material, 128 * 1024);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException(
                    "Scenario batch protocol could not be serialized",
                    invalid);
        }
    }

    private <T> T read(
            String value,
            Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException(
                    "Stored Scenario batch protocol is invalid",
                    invalid);
        }
    }

    private Instant coordinationNow() {
        return Objects.requireNonNull(
                coordinationClock.get(),
                "database clock").truncatedTo(
                java.time.temporal.ChronoUnit.MILLIS);
    }

    private static Instant databaseNow(
            JdbcTemplate jdbc) {
        DataSource dataSource =
                Objects.requireNonNull(
                        jdbc.getDataSource(),
                        "jdbc dataSource");
        while (dataSource
                instanceof DelegatingDataSource delegating
                && delegating.getTargetDataSource() != null) {
            dataSource = delegating.getTargetDataSource();
        }
        try (Connection connection =
                     dataSource.getConnection();
             Statement statement =
                     connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT CURRENT_TIMESTAMP")) {
            if (!result.next()) {
                throw new IllegalStateException(
                        "Database clock returned no value");
            }
            return result.getTimestamp(1)
                    .toInstant()
                    .truncatedTo(
                            java.time.temporal.ChronoUnit
                                    .MILLIS);
        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "Database clock is unavailable",
                    unavailable);
        }
    }

    private static Instant safePlus(
            Instant start,
            Duration duration) {
        try {
            return start.plus(duration);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Scenario batch time bound overflowed",
                    invalid);
        }
    }

    private static Duration safePlus(
            Duration left,
            Duration right) {
        try {
            return left.plus(right);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(
                    "Scenario batch duration overflowed",
                    invalid);
        }
    }

    private void appendLifecycle(
            StoredJob stored,
            ScenarioRehearsalBatchLifecycleAuditEvent.Transition
                    transition,
            ScenarioRehearsalBatchJob job,
            ScenarioRehearsalBatchItemPage.Item item,
            String evidenceFingerprint,
            String reasonCode) {
        lifecycleAudit.append(
                new ScenarioRehearsalBatchLifecycleAuditEvent(
                        0,
                        null,
                        job.scope(),
                        job.jobId(),
                        job.requestId(),
                        job.manifestFingerprint(),
                        transition,
                        job.status(),
                        item == null
                                ? -1
                                : item.itemIndex(),
                        ScenarioRehearsalBatchLifecycleAuditEvent
                                .ItemStatus.from(
                                item == null
                                        ? null
                                        : item.status()),
                        item == null
                                ? 0
                                : item.attemptCount(),
                        stored.leaseOwner(),
                        stored.leaseEpoch(),
                        evidenceFingerprint,
                        reasonCode));
    }

    private static SubmissionResult observed(
            SubmissionResult result,
            MirrorOperationObservability.Observation observation) {
        SubmissionResult required =
                Objects.requireNonNull(result, "result");
        if (observation != null) {
            observation.succeeded(
                    required.job().jobId());
        }
        return required;
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private QueuePartition partition(
            CapabilitySnapshot.Scope scope) {
        CapabilitySnapshot.Scope required =
                Objects.requireNonNull(scope, "scope");
        return partition(
                required.region(),
                required.environmentId());
    }

    private static QueuePartition partition(
            String region,
            String environmentId) {
        return new QueuePartition(
                required(region, "region"),
                required(environmentId, "environmentId"));
    }

    private String coordinationKey(
            QueuePartition partition) {
        return ProtocolFingerprint.of(
                mapper,
                Objects.requireNonNull(partition, "partition"));
    }

    private static Instant instant(
            ResultSet result,
            String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String identifier(
            String value,
            String field) {
        String normalized = required(value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String code(
            String value,
            String field) {
        String normalized = required(value, field)
                .toUpperCase(Locale.ROOT);
        if (!CODE.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static String required(
            String value,
            String field) {
        String normalized =
                value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }

    private static <T> Optional<T> one(
            List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException(
                    "Scenario batch lookup returned duplicate rows");
        }
        return values.stream().findFirst();
    }

    private static <T> T required(
            T value,
            String message) {
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    private static ScenarioRehearsalBatchConflictException
    conflict(
            Reason reason,
            String message) {
        return new ScenarioRehearsalBatchConflictException(
                reason, message);
    }

    private record StoredJob(
            ScenarioRehearsalBatchJob job,
            ScenarioRehearsalBatchRequest request,
            ScenarioRehearsalBatchManifest manifest,
            ScenarioRehearsalBatchPrincipal principal,
            Instant nextEligibleAt,
            String leaseOwner,
            long leaseEpoch,
            Instant leaseExpiresAt,
            int currentItemIndex,
            Instant expiresAt
    ) {
        private StoredJob {
            job = Objects.requireNonNull(job, "job");
            request = Objects.requireNonNull(
                    request, "request");
            manifest = Objects.requireNonNull(
                    manifest, "manifest");
            principal = Objects.requireNonNull(
                    principal, "principal");
            nextEligibleAt = Objects.requireNonNull(
                    nextEligibleAt, "nextEligibleAt");
            leaseOwner = leaseOwner == null
                    ? "" : leaseOwner.trim();
            if (leaseEpoch < 0) {
                throw new IllegalArgumentException(
                        "leaseEpoch must not be negative");
            }
            leaseExpiresAt = Objects.requireNonNull(
                    leaseExpiresAt, "leaseExpiresAt");
            expiresAt = Objects.requireNonNull(
                    expiresAt, "expiresAt");
            boolean activeLease =
                    !leaseOwner.isBlank()
                            && currentItemIndex >= 0
                            && leaseExpiresAt
                            .isAfter(Instant.EPOCH);
            boolean idleLease =
                    leaseOwner.isBlank()
                            && currentItemIndex == -1
                            && leaseExpiresAt
                            .equals(Instant.EPOCH);
            if (!(activeLease || idleLease)) {
                throw new IllegalArgumentException(
                        "Scenario batch stored lease is incomplete");
            }
        }
    }

    private record StoredItem(
            ScenarioRehearsalBatchItemPage.Item item,
            Duration executionTimeout
    ) {
        private StoredItem {
            item = Objects.requireNonNull(item, "item");
            executionTimeout = Objects.requireNonNull(
                    executionTimeout, "executionTimeout");
            if (executionTimeout.isZero()
                    || executionTimeout.isNegative()) {
                throw new IllegalArgumentException(
                        "executionTimeout must be positive");
            }
        }
    }

    private record StoredHeartbeat(
            long count,
            int nextCaseIndex
    ) {
        private StoredHeartbeat {
            if (count < 0 || nextCaseIndex < 0) {
                throw new IllegalStateException(
                        "Scenario batch heartbeat state is invalid");
            }
        }
    }

    private record PolicyState(
            long generation,
            String fingerprint) {
    }

    private record QueuePartition(
            String region,
            String environmentId) {
        private QueuePartition {
            region = required(region, "region");
            environmentId = required(
                    environmentId, "environmentId");
            if (region.length() > 64
                    || environmentId.length() > 255) {
                throw new IllegalArgumentException(
                        "Scenario batch regional partition is too long");
            }
        }
    }

    private record Cursor(
            String lastTenantId,
            long cycleEpoch) {
    }
}
