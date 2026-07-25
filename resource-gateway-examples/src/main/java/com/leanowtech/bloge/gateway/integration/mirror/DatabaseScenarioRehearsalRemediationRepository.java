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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import static com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalRemediationConflictException.Reason;

/**
 * JDBC reviewed-remediation state machine with atomic Scenario batch successor admission.
 *
 * <p>Canonical plan, approval, and receipt JSON are immutable source facts. The mutable plan row
 * contains only a lock target and verified state projection. Every mutation uses database time,
 * locks the lineage, applies compare-and-set fences, appends its immutable fact, and commits the
 * protected-operation success audit in the same local transaction.</p>
 */
public final class DatabaseScenarioRehearsalRemediationRepository
        implements ScenarioRehearsalRemediationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ScenarioRehearsalBatchTransactionalAdmission
            batchAdmission;
    private final Supplier<Instant> coordinationClock;
    private final TransactionTemplate mutations;

    /**
     * Creates the production repository over the shared application datasource.
     *
     * @param jdbc transaction-aware JDBC boundary
     * @param mapper canonical protocol mapper
     * @param transactionManager manager for the same datasource
     * @param batchAdmission transaction-participating batch admission boundary
     */
    public DatabaseScenarioRehearsalRemediationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            ScenarioRehearsalBatchTransactionalAdmission
                    batchAdmission) {
        this(
                jdbc,
                mapper,
                transactionManager,
                batchAdmission,
                null);
    }

    /** Package-private deterministic database-clock seam for state-machine tests. */
    DatabaseScenarioRehearsalRemediationRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            PlatformTransactionManager transactionManager,
            ScenarioRehearsalBatchTransactionalAdmission
                    batchAdmission,
            Supplier<Instant> coordinationClock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.batchAdmission = Objects.requireNonNull(
                batchAdmission, "batchAdmission");
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

    /** Creates immutable fact tables and bounded exact-scope indexes. */
    @PostConstruct
    void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scenario_rehearsal_remediation_plans (
                    remediation_id VARCHAR(96) PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    region VARCHAR(64) NOT NULL,
                    preview_request_id VARCHAR(128) NOT NULL,
                    preview_request_fingerprint VARCHAR(71) NOT NULL,
                    predecessor_job_id VARCHAR(512) NOT NULL,
                    plan_fingerprint VARCHAR(71) NOT NULL,
                    plan_json CLOB NOT NULL,
                    state VARCHAR(32) NOT NULL,
                    approval_generation BIGINT NOT NULL,
                    approval_head_fingerprint VARCHAR(71) NOT NULL,
                    policy_generation BIGINT NOT NULL,
                    generated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT uq_scenario_rehearsal_remediation_preview
                        UNIQUE (
                            tenant_id, organization_id, project_id,
                            environment_id, region, preview_request_id
                        )
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scenario_rehearsal_remediation_predecessor
                ON scenario_rehearsal_remediation_plans (
                    tenant_id, organization_id, project_id,
                    environment_id, region, predecessor_job_id,
                    generated_at DESC
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_scenario_rehearsal_remediation_state_expiry
                ON scenario_rehearsal_remediation_plans (
                    state, expires_at
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scenario_rehearsal_remediation_approvals (
                    remediation_id VARCHAR(96) NOT NULL,
                    approval_generation BIGINT NOT NULL,
                    command_id VARCHAR(128) NOT NULL,
                    command_fingerprint VARCHAR(71) NOT NULL,
                    actor_id VARCHAR(255) NOT NULL,
                    delegated_by VARCHAR(255) NOT NULL,
                    approval_fingerprint VARCHAR(71) NOT NULL,
                    approval_json CLOB NOT NULL,
                    decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (
                        remediation_id, approval_generation
                    ),
                    CONSTRAINT uq_scenario_rehearsal_remediation_approval_command
                        UNIQUE (remediation_id, command_id),
                    FOREIGN KEY (remediation_id)
                        REFERENCES scenario_rehearsal_remediation_plans (
                            remediation_id
                        )
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS scenario_rehearsal_remediation_submissions (
                    remediation_id VARCHAR(96) PRIMARY KEY,
                    command_id VARCHAR(128) NOT NULL,
                    command_fingerprint VARCHAR(71) NOT NULL,
                    accepted_by VARCHAR(255) NOT NULL,
                    delegated_by VARCHAR(255) NOT NULL,
                    successor_job_id VARCHAR(512) NOT NULL,
                    receipt_fingerprint VARCHAR(71) NOT NULL,
                    receipt_json CLOB NOT NULL,
                    accepted_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    CONSTRAINT uq_scenario_rehearsal_remediation_submit_command
                        UNIQUE (remediation_id, command_id),
                    FOREIGN KEY (remediation_id)
                        REFERENCES scenario_rehearsal_remediation_plans (
                            remediation_id
                        )
                )
                """);
    }

    @Override
    public PreviewResult create(
            Preview preview,
            ScenarioRehearsalRemediationPolicy policy,
            MirrorOperationObservability.Observation observation) {
        Preview exact = Objects.requireNonNull(
                preview, "preview");
        ScenarioRehearsalRemediationPolicy exactPolicy =
                Objects.requireNonNull(policy, "policy");
        MirrorOperationObservability.Observation exactObservation =
                Objects.requireNonNull(
                        observation, "observation");
        exact.plan().verify(mapper);
        verifyPreviewFingerprint(exact);
        PreviewResult result = mutations.execute(status -> {
            Instant observedAt = coordinationNow();
            requirePreviewTime(
                    exact.plan(), exactPolicy, observedAt);
            Optional<StoredPlan> existing = byPreview(
                    exact.plan().scope(),
                    exact.plan().previewRequestId(),
                    true);
            if (existing.isPresent()) {
                StoredPlan stored = existing.orElseThrow();
                requireSamePreview(stored, exact);
                exactObservation.succeeded(
                        stored.plan().remediationId());
                return new PreviewResult(
                        stored.plan(), true);
            }
            try {
                insertPlan(
                        exact,
                        exactPolicy.generation(),
                        observedAt);
            } catch (DuplicateKeyException collision) {
                Optional<StoredPlan> winner = byPreview(
                        exact.plan().scope(),
                        exact.plan().previewRequestId(),
                        true);
                if (winner.isPresent()) {
                    StoredPlan stored = winner.orElseThrow();
                    requireSamePreview(stored, exact);
                    exactObservation.succeeded(
                            stored.plan().remediationId());
                    return new PreviewResult(
                            stored.plan(), true);
                }
                throw conflict(
                        Reason.IDEMPOTENCY_CONFLICT,
                        "Scenario remediation identity already belongs to another preview");
            }
            exactObservation.succeeded(
                    exact.plan().remediationId());
            return new PreviewResult(
                    exact.plan(), false);
        });
        return required(
                result,
                "Scenario remediation preview returned no result");
    }

    @Override
    public ApprovalResult approve(
            ApprovalMutation mutation,
            ScenarioRehearsalRemediationPolicy policy,
            MirrorOperationObservability.Observation observation) {
        ApprovalMutation exact = Objects.requireNonNull(
                mutation, "mutation");
        ScenarioRehearsalRemediationPolicy exactPolicy =
                Objects.requireNonNull(policy, "policy");
        MirrorOperationObservability.Observation exactObservation =
                Objects.requireNonNull(
                        observation, "observation");
        verifyCommandFingerprint(
                exact.command(), exact.commandFingerprint());
        ApprovalResult result = mutations.execute(status -> {
            StoredPlan stored = requirePlan(
                    exact.scope(),
                    exact.remediationId(),
                    true);
            requireCurrentPolicy(stored, exactPolicy);
            Optional<StoredApproval> replay =
                    approvalByCommand(
                            exact.remediationId(),
                            exact.command().commandId());
            if (replay.isPresent()) {
                StoredApproval retained = replay.orElseThrow();
                requireSameApprovalCommand(
                        retained, exact);
                exactObservation.succeeded(
                        exact.remediationId());
                return new ApprovalResult(
                        retained.approval(), true);
            }
            requirePlanFingerprint(
                    stored, exact.command()
                            .remediationPlanFingerprint());
            Instant decidedAt = coordinationNow();
            requireUnexpired(stored.plan(), decidedAt);
            if (stored.state() == State.REJECTED) {
                throw conflict(
                        Reason.PLAN_REJECTED,
                        "Scenario remediation was rejected by an immutable approval fact");
            }
            if (stored.state() == State.SUBMITTED) {
                throw conflict(
                        Reason.ALREADY_SUBMITTED,
                        "Scenario remediation successor was already submitted");
            }
            if (stored.approvalGeneration()
                    != exact.command()
                    .expectedApprovalGeneration()) {
                throw conflict(
                        Reason.APPROVAL_GENERATION_MISMATCH,
                        "Scenario remediation approval generation changed");
            }
            if (!stored.plan().governanceTicketRef()
                    .equals(exact.command()
                            .governanceTicketRef())) {
                throw conflict(
                        Reason.GOVERNANCE_TICKET_MISMATCH,
                        "Scenario remediation approval ticket differs from the frozen plan");
            }
            int nextIndex = Math.toIntExact(
                    stored.approvalGeneration());
            if (nextIndex
                    >= stored.plan().approvalPolicy()
                    .requiredRoles().size()
                    || stored.plan().approvalPolicy()
                    .requiredRoles().get(nextIndex)
                    != exact.command().role()) {
                throw conflict(
                        Reason.APPROVAL_ORDER_INVALID,
                        "Scenario remediation approvals must follow the frozen role order");
            }
            List<ScenarioRehearsalRemediationApproval>
                    prior = approvals(
                    exact.remediationId());
            if (prior.stream().anyMatch(approval ->
                    sameControlPrincipal(
                            approval.actorId(),
                            approval.delegatedBy(),
                            exact.actorId(),
                            exact.delegatedBy()))) {
                throw conflict(
                        Reason.DISTINCT_ACTOR_REQUIRED,
                        "Scenario remediation approvals require distinct authenticated actors");
            }
            long generation = Math.addExact(
                    stored.approvalGeneration(), 1);
            String previous = stored
                    .approvalHeadFingerprint();
            ScenarioRehearsalRemediationApproval approval =
                    ScenarioRehearsalRemediationApproval.seal(
                            mapper,
                            new ScenarioRehearsalRemediationApproval(
                                    "",
                                    "",
                                    exact.commandFingerprint(),
                                    exact.scope(),
                                    exact.remediationId(),
                                    stored.plan()
                                            .planFingerprint(),
                                    generation,
                                    previous,
                                    exact.command().role(),
                                    exact.command().decision(),
                                    exact.command()
                                            .governanceTicketRef(),
                                    exact.command().reasonCode(),
                                    exact.actorId(),
                                    exact.delegatedBy(),
                                    decidedAt));
            State next = nextState(
                    approval, generation);
            insertApproval(
                    exact.command().commandId(),
                    exact.commandFingerprint(),
                    approval);
            int updated = jdbc.update("""
                    UPDATE scenario_rehearsal_remediation_plans
                    SET state = ?,
                        approval_generation = ?,
                        approval_head_fingerprint = ?,
                        updated_at = ?
                    WHERE remediation_id = ?
                      AND approval_generation = ?
                      AND approval_head_fingerprint = ?
                      AND state = ?
                    """,
                    next.name(),
                    generation,
                    approval.approvalFingerprint(),
                    timestamp(decidedAt),
                    exact.remediationId(),
                    stored.approvalGeneration(),
                    previous,
                    stored.state().name());
            if (updated != 1) {
                throw conflict(
                        Reason.APPROVAL_GENERATION_MISMATCH,
                        "Scenario remediation approval head changed concurrently");
            }
            exactObservation.succeeded(
                    exact.remediationId());
            return new ApprovalResult(
                    approval, false);
        });
        return required(
                result,
                "Scenario remediation approval returned no result");
    }

    @Override
    public SubmissionResult submit(
            SubmissionMutation mutation,
            ScenarioRehearsalRemediationPolicy policy,
            ScenarioRehearsalBatchPolicy batchPolicy,
            MirrorOperationObservability.Observation observation) {
        SubmissionMutation exact = Objects.requireNonNull(
                mutation, "mutation");
        ScenarioRehearsalRemediationPolicy exactPolicy =
                Objects.requireNonNull(policy, "policy");
        ScenarioRehearsalBatchPolicy exactBatchPolicy =
                Objects.requireNonNull(
                        batchPolicy, "batchPolicy");
        MirrorOperationObservability.Observation exactObservation =
                Objects.requireNonNull(
                        observation, "observation");
        verifyCommandFingerprint(
                exact.command(), exact.commandFingerprint());
        SubmissionResult result = mutations.execute(status -> {
            StoredPlan stored = requirePlan(
                    exact.scope(),
                    exact.remediationId(),
                    true);
            requireCurrentPolicy(stored, exactPolicy);
            Optional<StoredSubmission> replay =
                    submission(exact.remediationId());
            if (replay.isPresent()) {
                StoredSubmission retained =
                        replay.orElseThrow();
                requireSameSubmissionCommand(
                        retained, exact);
                exactObservation.succeeded(
                        retained.receipt()
                                .successorJobId());
                return new SubmissionResult(
                        retained.receipt(), true);
            }
            requirePlanFingerprint(
                    stored,
                    exact.command()
                            .remediationPlanFingerprint());
            Instant acceptedAt = coordinationNow();
            requireUnexpired(stored.plan(), acceptedAt);
            if (stored.state() == State.REJECTED) {
                throw conflict(
                        Reason.PLAN_REJECTED,
                        "Scenario remediation was rejected by an immutable approval fact");
            }
            if (stored.state() == State.SUBMITTED) {
                throw conflict(
                        Reason.ALREADY_SUBMITTED,
                        "Scenario remediation successor was already submitted");
            }
            if (stored.state() != State.APPROVED) {
                throw conflict(
                        Reason.APPROVALS_INCOMPLETE,
                        "Scenario remediation requires both approvals before submission");
            }
            if (stored.approvalGeneration()
                    != exact.command()
                    .expectedApprovalGeneration()
                    || !stored.approvalHeadFingerprint()
                    .equals(exact.command()
                            .expectedApprovalHeadFingerprint())) {
                throw conflict(
                        Reason.APPROVAL_GENERATION_MISMATCH,
                        "Scenario remediation approved head changed");
            }
            requireApprovedChain(stored);
            requireSuccessorSubmission(
                    stored.plan(),
                    exact.successorSubmission());
            ScenarioRehearsalBatchRepository.SubmissionResult
                    admitted =
                    batchAdmission.submitInCurrentTransaction(
                            exact.successorSubmission(),
                            exactBatchPolicy);
            if (admitted.idempotentReplay()) {
                throw conflict(
                        Reason.SUCCESSOR_IDENTITY_ALREADY_USED,
                        "Scenario remediation successor identity was already admitted outside this lineage");
            }
            ScenarioRehearsalRemediationReceipt receipt =
                    ScenarioRehearsalRemediationReceipt.seal(
                            mapper,
                            new ScenarioRehearsalRemediationReceipt(
                                    "",
                                    "",
                                    exact.commandFingerprint(),
                                    exact.scope(),
                                    exact.remediationId(),
                                    stored.plan()
                                            .planFingerprint(),
                                    stored.plan()
                                            .predecessorJobId(),
                                    admitted.job().jobId(),
                                    stored.plan()
                                            .successorRequestFingerprint(),
                                    stored.approvalGeneration(),
                                    stored.approvalHeadFingerprint(),
                                    exact.acceptedBy(),
                                    exact.delegatedBy(),
                                    acceptedAt));
            insertSubmission(
                    exact.command().commandId(),
                    exact.commandFingerprint(),
                    receipt);
            int updated = jdbc.update("""
                    UPDATE scenario_rehearsal_remediation_plans
                    SET state = 'SUBMITTED',
                        updated_at = ?
                    WHERE remediation_id = ?
                      AND state = 'APPROVED'
                      AND approval_generation = ?
                      AND approval_head_fingerprint = ?
                    """,
                    timestamp(acceptedAt),
                    exact.remediationId(),
                    stored.approvalGeneration(),
                    stored.approvalHeadFingerprint());
            if (updated != 1) {
                throw conflict(
                        Reason.APPROVAL_GENERATION_MISMATCH,
                        "Scenario remediation approval head changed during successor admission");
            }
            exactObservation.succeeded(
                    admitted.job().jobId());
            return new SubmissionResult(
                    receipt, false);
        });
        return required(
                result,
                "Scenario remediation submission returned no result");
    }

    @Override
    public Optional<Snapshot> find(
            CapabilitySnapshot.Scope scope,
            String remediationId) {
        CapabilitySnapshot.Scope exactScope =
                Objects.requireNonNull(scope, "scope");
        String exactId = MirrorStateProtocolSupport.required(
                remediationId, "remediationId");
        return byId(exactScope, exactId, false)
                .map(this::snapshot);
    }

    private Snapshot snapshot(StoredPlan stored) {
        List<ScenarioRehearsalRemediationApproval>
                exactApprovals = approvals(
                stored.plan().remediationId());
        ScenarioRehearsalRemediationReceipt receipt =
                submission(stored.plan().remediationId())
                        .map(StoredSubmission::receipt)
                        .orElse(null);
        if (stored.approvalGeneration()
                != exactApprovals.size()
                || !stored.approvalHeadFingerprint()
                .equals(exactApprovals.isEmpty()
                ? ""
                : exactApprovals.getLast()
                .approvalFingerprint())) {
            throw new IllegalStateException(
                    "Stored Scenario remediation approval projection failed integrity validation");
        }
        return new Snapshot(
                stored.plan(),
                stored.state(),
                exactApprovals,
                receipt);
    }

    private void requireApprovedChain(
            StoredPlan stored) {
        Snapshot snapshot = snapshot(stored);
        List<ScenarioRehearsalRemediationApproval>
                approvals = snapshot.approvals();
        if (approvals.size()
                != stored.plan().approvalPolicy()
                .requiredRoles().size()
                || approvals.stream().anyMatch(approval ->
                approval.decision()
                        != ScenarioRehearsalRemediationApprovalCommand
                        .Decision.APPROVE)
                || approvals.stream()
                .map(ScenarioRehearsalRemediationApproval::actorId)
                .distinct().count()
                < stored.plan().approvalPolicy()
                .minimumDistinctActors()) {
            throw conflict(
                    Reason.APPROVALS_INCOMPLETE,
                    "Scenario remediation immutable approval chain is incomplete");
        }
        for (int index = 0;
             index < approvals.size();
             index++) {
            if (approvals.get(index).role()
                    != stored.plan().approvalPolicy()
                    .requiredRoles().get(index)) {
                throw new IllegalStateException(
                        "Stored Scenario remediation approval role order is corrupt");
            }
        }
    }

    private void requireSuccessorSubmission(
            ScenarioRehearsalRemediationPlan plan,
            ScenarioRehearsalBatchRepository.Submission
                    submission) {
        if (!submission.manifest().scope().equals(
                plan.scope())
                || !submission.request().equals(
                plan.successorRequest())
                || !submission.requestFingerprint().equals(
                plan.successorRequestFingerprint())
                || !submission.request().requestId().equals(
                plan.remediationId())) {
            throw new IllegalArgumentException(
                    "Scenario remediation successor admission differs from the frozen plan");
        }
    }

    private StoredPlan requirePlan(
            CapabilitySnapshot.Scope scope,
            String remediationId,
            boolean forUpdate) {
        return byId(scope, remediationId, forUpdate)
                .orElseThrow(() -> conflict(
                        Reason.NOT_FOUND,
                        "Scenario remediation was not found in the authorized scope"));
    }

    private Optional<StoredPlan> byPreview(
            CapabilitySnapshot.Scope scope,
            String previewRequestId,
            boolean forUpdate) {
        String sql = """
                SELECT *
                FROM scenario_rehearsal_remediation_plans
                WHERE tenant_id = ?
                  AND organization_id = ?
                  AND project_id = ?
                  AND environment_id = ?
                  AND region = ?
                  AND preview_request_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return one(jdbc.query(
                sql,
                this::mapPlan,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                previewRequestId));
    }

    private Optional<StoredPlan> byId(
            CapabilitySnapshot.Scope scope,
            String remediationId,
            boolean forUpdate) {
        String sql = """
                SELECT *
                FROM scenario_rehearsal_remediation_plans
                WHERE tenant_id = ?
                  AND organization_id = ?
                  AND project_id = ?
                  AND environment_id = ?
                  AND region = ?
                  AND remediation_id = ?
                """ + (forUpdate ? " FOR UPDATE" : "");
        return one(jdbc.query(
                sql,
                this::mapPlan,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                remediationId));
    }

    private Optional<StoredApproval> approvalByCommand(
            String remediationId,
            String commandId) {
        return one(jdbc.query("""
                SELECT *
                FROM scenario_rehearsal_remediation_approvals
                WHERE remediation_id = ?
                  AND command_id = ?
                """,
                this::mapApproval,
                remediationId,
                commandId));
    }

    private List<ScenarioRehearsalRemediationApproval> approvals(
            String remediationId) {
        return jdbc.query("""
                SELECT *
                FROM scenario_rehearsal_remediation_approvals
                WHERE remediation_id = ?
                ORDER BY approval_generation
                """,
                this::mapApproval,
                remediationId).stream()
                .map(StoredApproval::approval)
                .toList();
    }

    private Optional<StoredSubmission> submission(
            String remediationId) {
        return one(jdbc.query("""
                SELECT *
                FROM scenario_rehearsal_remediation_submissions
                WHERE remediation_id = ?
                """,
                this::mapSubmission,
                remediationId));
    }

    private StoredPlan mapPlan(
            ResultSet rs,
            int rowNumber) throws SQLException {
        ScenarioRehearsalRemediationPlan plan =
                read(
                        rs.getString("plan_json"),
                        ScenarioRehearsalRemediationPlan.class);
        plan.verify(mapper);
        CapabilitySnapshot.Scope indexed =
                new CapabilitySnapshot.Scope(
                        rs.getString("tenant_id"),
                        rs.getString("organization_id"),
                        rs.getString("project_id"),
                        rs.getString("environment_id"),
                        rs.getString("region"));
        State state = State.valueOf(
                rs.getString("state"));
        long generation = rs.getLong(
                "approval_generation");
        String head = rs.getString(
                "approval_head_fingerprint");
        if (!plan.scope().equals(indexed)
                || !plan.remediationId().equals(
                rs.getString("remediation_id"))
                || !plan.previewRequestId().equals(
                rs.getString("preview_request_id"))
                || !plan.predecessorJobId().equals(
                rs.getString("predecessor_job_id"))
                || !plan.planFingerprint().equals(
                rs.getString("plan_fingerprint"))
                || !plan.generatedAt().equals(
                instant(rs, "generated_at"))
                || !plan.expiresAt().equals(
                instant(rs, "expires_at"))
                || generation < 0
                || generation > 2
                || generation == 0 != head.isBlank()
                || rs.getLong("policy_generation")
                != plan.approvalPolicy()
                .serverPolicyGeneration()) {
            throw new IllegalStateException(
                    "Stored Scenario remediation plan failed integrity validation");
        }
        return new StoredPlan(
                plan,
                rs.getString(
                        "preview_request_fingerprint"),
                state,
                generation,
                head,
                rs.getLong("policy_generation"));
    }

    private StoredApproval mapApproval(
            ResultSet rs,
            int rowNumber) throws SQLException {
        ScenarioRehearsalRemediationApproval approval =
                read(
                        rs.getString("approval_json"),
                        ScenarioRehearsalRemediationApproval.class);
        approval.verify(mapper);
        if (!approval.remediationId().equals(
                rs.getString("remediation_id"))
                || approval.generation()
                != rs.getLong("approval_generation")
                || !approval.sourceCommandFingerprint().equals(
                rs.getString("command_fingerprint"))
                || !approval.actorId().equals(
                rs.getString("actor_id"))
                || !approval.delegatedBy().equals(
                rs.getString("delegated_by"))
                || !approval.approvalFingerprint().equals(
                rs.getString("approval_fingerprint"))
                || !approval.decidedAt().equals(
                instant(rs, "decided_at"))) {
            throw new IllegalStateException(
                    "Stored Scenario remediation approval failed integrity validation");
        }
        return new StoredApproval(
                rs.getString("command_id"),
                rs.getString("command_fingerprint"),
                approval);
    }

    private StoredSubmission mapSubmission(
            ResultSet rs,
            int rowNumber) throws SQLException {
        ScenarioRehearsalRemediationReceipt receipt =
                read(
                        rs.getString("receipt_json"),
                        ScenarioRehearsalRemediationReceipt.class);
        receipt.verify(mapper);
        if (!receipt.remediationId().equals(
                rs.getString("remediation_id"))
                || !receipt.sourceCommandFingerprint().equals(
                rs.getString("command_fingerprint"))
                || !receipt.acceptedBy().equals(
                rs.getString("accepted_by"))
                || !receipt.delegatedBy().equals(
                rs.getString("delegated_by"))
                || !receipt.successorJobId().equals(
                rs.getString("successor_job_id"))
                || !receipt.receiptFingerprint().equals(
                rs.getString("receipt_fingerprint"))
                || !receipt.acceptedAt().equals(
                instant(rs, "accepted_at"))) {
            throw new IllegalStateException(
                    "Stored Scenario remediation receipt failed integrity validation");
        }
        return new StoredSubmission(
                rs.getString("command_id"),
                rs.getString("command_fingerprint"),
                receipt);
    }

    private void insertPlan(
            Preview preview,
            long policyGeneration,
            Instant observedAt) {
        ScenarioRehearsalRemediationPlan plan =
                preview.plan();
        CapabilitySnapshot.Scope scope = plan.scope();
        jdbc.update("""
                INSERT INTO scenario_rehearsal_remediation_plans (
                    remediation_id,
                    tenant_id, organization_id, project_id,
                    environment_id, region,
                    preview_request_id,
                    preview_request_fingerprint,
                    predecessor_job_id,
                    plan_fingerprint,
                    plan_json,
                    state,
                    approval_generation,
                    approval_head_fingerprint,
                    policy_generation,
                    generated_at,
                    expires_at,
                    updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    'PENDING_APPROVAL', 0, '', ?, ?, ?, ?
                )
                """,
                plan.remediationId(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                plan.previewRequestId(),
                preview.previewRequestFingerprint(),
                plan.predecessorJobId(),
                plan.planFingerprint(),
                json(plan),
                policyGeneration,
                timestamp(plan.generatedAt()),
                timestamp(plan.expiresAt()),
                timestamp(observedAt));
    }

    private void insertApproval(
            String commandId,
            String commandFingerprint,
            ScenarioRehearsalRemediationApproval approval) {
        jdbc.update("""
                INSERT INTO scenario_rehearsal_remediation_approvals (
                    remediation_id,
                    approval_generation,
                    command_id,
                    command_fingerprint,
                    actor_id,
                    delegated_by,
                    approval_fingerprint,
                    approval_json,
                    decided_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                approval.remediationId(),
                approval.generation(),
                commandId,
                commandFingerprint,
                approval.actorId(),
                approval.delegatedBy(),
                approval.approvalFingerprint(),
                json(approval),
                timestamp(approval.decidedAt()));
    }

    private void insertSubmission(
            String commandId,
            String commandFingerprint,
            ScenarioRehearsalRemediationReceipt receipt) {
        jdbc.update("""
                INSERT INTO scenario_rehearsal_remediation_submissions (
                    remediation_id,
                    command_id,
                    command_fingerprint,
                    accepted_by,
                    delegated_by,
                    successor_job_id,
                    receipt_fingerprint,
                    receipt_json,
                    accepted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                receipt.remediationId(),
                commandId,
                commandFingerprint,
                receipt.acceptedBy(),
                receipt.delegatedBy(),
                receipt.successorJobId(),
                receipt.receiptFingerprint(),
                json(receipt),
                timestamp(receipt.acceptedAt()));
    }

    private static State nextState(
            ScenarioRehearsalRemediationApproval approval,
            long generation) {
        if (approval.decision()
                == ScenarioRehearsalRemediationApprovalCommand
                .Decision.REJECT) {
            return State.REJECTED;
        }
        return generation == 2
                ? State.APPROVED
                : State.PENDING_APPROVAL;
    }

    private void requireSamePreview(
            StoredPlan stored,
            Preview requested) {
        if (!stored.previewRequestFingerprint().equals(
                requested.previewRequestFingerprint())
                || !stored.plan().predecessorJobId().equals(
                requested.plan().predecessorJobId())
                || !stored.plan().remediationId().equals(
                requested.plan().remediationId())
                || !stored.plan()
                .predecessorWorkbookSeedFingerprint().equals(
                        requested.plan()
                                .predecessorWorkbookSeedFingerprint())
                || !stored.plan()
                .predecessorEvidenceBundleFingerprint().equals(
                        requested.plan()
                                .predecessorEvidenceBundleFingerprint())
                || stored.plan().predecessorStatus()
                != requested.plan().predecessorStatus()
                || !stored.plan().predecessorBlockers().equals(
                        requested.plan().predecessorBlockers())
                || stored.plan().strategy()
                != requested.plan().strategy()
                || stored.plan().reasonCode()
                != requested.plan().reasonCode()
                || !stored.plan().replacements().equals(
                        requested.plan().replacements())
                || !stored.plan().successorRequestFingerprint().equals(
                        requested.plan()
                                .successorRequestFingerprint())
                || !stored.plan().governanceTicketRef().equals(
                        requested.plan()
                                .governanceTicketRef())) {
            throw conflict(
                    Reason.IDEMPOTENCY_CONFLICT,
                    "Scenario remediation preview request id was reused for different content");
        }
    }

    private static void requireSameApprovalCommand(
            StoredApproval stored,
            ApprovalMutation requested) {
        if (!stored.commandFingerprint().equals(
                requested.commandFingerprint())
                || !stored.approval().actorId().equals(
                requested.actorId())
                || !stored.approval().delegatedBy().equals(
                requested.delegatedBy())) {
            throw conflict(
                    Reason.IDEMPOTENCY_CONFLICT,
                    "Scenario remediation approval command id was reused by different content or actor");
        }
    }

    private static boolean sameControlPrincipal(
            String priorActor,
            String priorDelegatedBy,
            String candidateActor,
            String candidateDelegatedBy) {
        List<String> prior = List.of(
                        priorActor,
                        priorDelegatedBy)
                .stream()
                .filter(value -> value != null
                        && !value.isBlank())
                .toList();
        return List.of(
                        candidateActor,
                        candidateDelegatedBy)
                .stream()
                .filter(value -> value != null
                        && !value.isBlank())
                .anyMatch(prior::contains);
    }

    private static void requireSameSubmissionCommand(
            StoredSubmission stored,
            SubmissionMutation requested) {
        if (!stored.commandFingerprint().equals(
                requested.commandFingerprint())
                || !stored.receipt().acceptedBy().equals(
                requested.acceptedBy())
                || !stored.receipt().delegatedBy().equals(
                requested.delegatedBy())) {
            throw conflict(
                    Reason.IDEMPOTENCY_CONFLICT,
                    "Scenario remediation submit command id was reused by different content or actor");
        }
    }

    private static void requirePlanFingerprint(
            StoredPlan stored,
            String expected) {
        if (!stored.plan().planFingerprint()
                .equals(expected)) {
            throw conflict(
                    Reason.PLAN_FINGERPRINT_MISMATCH,
                    "Scenario remediation plan fingerprint changed");
        }
    }

    private static void requireUnexpired(
            ScenarioRehearsalRemediationPlan plan,
            Instant observedAt) {
        if (!plan.expiresAt().isAfter(observedAt)) {
            throw conflict(
                    Reason.PLAN_EXPIRED,
                    "Scenario remediation plan expired before this decision");
        }
    }

    private void requirePreviewTime(
            ScenarioRehearsalRemediationPlan plan,
            ScenarioRehearsalRemediationPolicy policy,
            Instant observedAt) {
        Duration lifetime = Duration.between(
                plan.generatedAt(), plan.expiresAt());
        if (!lifetime.equals(policy.planLifetime())
                || plan.approvalPolicy()
                .serverPolicyGeneration()
                != policy.generation()
                || !plan.approvalPolicy()
                .serverPolicyFingerprint()
                .equals(policy.fingerprint(mapper))
                || plan.generatedAt().isAfter(
                observedAt.plus(
                        policy.maximumClockSkew()))
                || plan.generatedAt().isBefore(
                observedAt.minus(
                        policy.maximumClockSkew()))
                || !plan.expiresAt().isAfter(observedAt)) {
            throw new IllegalArgumentException(
                    "Scenario remediation preview time differs from server policy");
        }
    }

    private void requireCurrentPolicy(
            StoredPlan stored,
            ScenarioRehearsalRemediationPolicy policy) {
        if (stored.policyGeneration()
                != policy.generation()
                || !stored.plan().approvalPolicy()
                .serverPolicyFingerprint()
                .equals(policy.fingerprint(mapper))) {
            throw conflict(
                    Reason.POLICY_MISMATCH,
                    "Scenario remediation server policy changed after preview");
        }
    }

    private void verifyPreviewFingerprint(
            Preview preview) {
        if (!preview.previewRequestFingerprint()
                .equals(ProtocolFingerprint.ofBounded(
                        mapper,
                        new PreviewFingerprintMaterial(
                                preview.plan()
                                        .predecessorJobId(),
                                preview.plan()
                                        .previewRequestId(),
                                preview.plan().strategy(),
                                preview.plan().reasonCode(),
                                preview.plan().replacements(),
                                preview.plan()
                                        .predecessorWorkbookSeedFingerprint(),
                                preview.plan()
                                        .governanceTicketRef()),
                        512 * 1024))) {
            throw new IllegalArgumentException(
                    "Scenario remediation preview fingerprint does not bind its plan");
        }
    }

    private void verifyCommandFingerprint(
            Object command,
            String fingerprint) {
        if (!fingerprint.equals(
                ProtocolFingerprint.ofBounded(
                        mapper, command, 256 * 1024))) {
            throw new IllegalArgumentException(
                    "Scenario remediation command fingerprint mismatch");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException(
                    "Scenario remediation protocol could not be serialized",
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
                    "Stored Scenario remediation protocol is invalid",
                    invalid);
        }
    }

    private Instant coordinationNow() {
        return Objects.requireNonNull(
                coordinationClock.get(),
                "database clock").truncatedTo(
                ChronoUnit.MILLIS);
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
                    .truncatedTo(ChronoUnit.MILLIS);
        } catch (SQLException unavailable) {
            throw new IllegalStateException(
                    "Database clock is unavailable",
                    unavailable);
        }
    }

    private static Instant instant(
            ResultSet result,
            String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null
                ? null
                : timestamp.toInstant()
                .truncatedTo(ChronoUnit.MILLIS);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static <T> Optional<T> one(
            List<T> values) {
        if (values.size() > 1) {
            throw new IllegalStateException(
                    "Scenario remediation query returned duplicate rows");
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

    private static ScenarioRehearsalRemediationConflictException
    conflict(
            Reason reason,
            String message) {
        return new ScenarioRehearsalRemediationConflictException(
                reason, message);
    }

    private record StoredPlan(
            ScenarioRehearsalRemediationPlan plan,
            String previewRequestFingerprint,
            State state,
            long approvalGeneration,
            String approvalHeadFingerprint,
            long policyGeneration
    ) {
    }

    private record StoredApproval(
            String commandId,
            String commandFingerprint,
            ScenarioRehearsalRemediationApproval approval
    ) {
    }

    private record StoredSubmission(
            String commandId,
            String commandFingerprint,
            ScenarioRehearsalRemediationReceipt receipt
    ) {
    }

    /**
     * Canonical material persisted as the preview idempotency content address.
     *
     * <p>It excludes trusted timestamps and the derived successor request id so an exact retry
     * after an ambiguous response can recover the original plan.</p>
     */
    record PreviewFingerprintMaterial(
            String predecessorJobId,
            String previewRequestId,
            ScenarioRehearsalRemediationPreviewRequest.Strategy
                    strategy,
            ScenarioRehearsalRemediationPreviewRequest.ReasonCode
                    reasonCode,
            List<ScenarioRehearsalRemediationPreviewRequest.PlanReplacement>
                    replacements,
            String predecessorWorkbookSeedFingerprint,
            MirrorArtifactRef governanceTicketRef
    ) {
    }
}
