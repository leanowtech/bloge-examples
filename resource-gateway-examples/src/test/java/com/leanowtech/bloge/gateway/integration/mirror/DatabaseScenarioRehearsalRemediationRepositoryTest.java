package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DatabaseScenarioRehearsalRemediationRepositoryTest {
    private static final Instant NOW =
            Instant.parse("2026-07-25T12:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a",
                    "org-a",
                    "project-a",
                    "test",
                    "sg");
    private static final String PREDECESSOR =
            "scenario-batch-" + "a".repeat(64);
    private static final String REMEDIATION_ID =
            "scenario-remediation-" + "b".repeat(64);
    private static final String SHA_A =
            "sha256:" + "a".repeat(64);
    private static final String SHA_B =
            "sha256:" + "b".repeat(64);
    private static final String SHA_C =
            "sha256:" + "c".repeat(64);

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> databaseTime =
            new AtomicReference<>(NOW);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private DatabaseScenarioRehearsalBatchRepository batches;
    private DatabaseScenarioRehearsalRemediationRepository
            remediations;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactionManager =
                new DataSourceTransactionManager(database);
        ScenarioRehearsalBatchEvidencePublisher publisher =
                mock(ScenarioRehearsalBatchEvidencePublisher.class);
        ScenarioRehearsalBatchEvidenceBundle bundle =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        when(bundle.bundleFingerprint()).thenReturn(SHA_B);
        when(publisher.publish(
                any(), any(), any(), any(), any()))
                .thenReturn(bundle);
        batches = new DatabaseScenarioRehearsalBatchRepository(
                jdbc,
                mapper,
                transactionManager,
                publisher,
                mock(ScenarioRehearsalBatchLifecycleAuditRepository.class),
                ScenarioRehearsalBatchFinalizationPolicy.defaults(),
                databaseTime::get);
        batches.init();
        remediations =
                new DatabaseScenarioRehearsalRemediationRepository(
                        jdbc,
                        mapper,
                        transactionManager,
                        batches,
                        databaseTime::get);
        remediations.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void createsIdempotentlyAndRecoversIntegrityVerifiedLineageAfterRestart() {
        ScenarioRehearsalRemediationRepository.Preview preview =
                preview("preview-a");

        ScenarioRehearsalRemediationRepository.PreviewResult
                created = remediations.create(
                preview,
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW));
        databaseTime.set(NOW.plusSeconds(30));
        ScenarioRehearsalRemediationRepository.PreviewResult
                replay = remediations.create(
                preview,
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW));
        DatabaseScenarioRehearsalRemediationRepository restarted =
                new DatabaseScenarioRehearsalRemediationRepository(
                        jdbc,
                        mapper,
                        transactionManager,
                        batches,
                        databaseTime::get);
        restarted.init();

        assertThat(created.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.plan()).isEqualTo(created.plan());
        assertThat(restarted.find(SCOPE, REMEDIATION_ID))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.state()).isEqualTo(
                            ScenarioRehearsalRemediationRepository
                                    .State.PENDING_APPROVAL);
                    assertThat(snapshot.approvals()).isEmpty();
                    assertThat(snapshot.receipt()).isNull();
                });

        ScenarioRehearsalRemediationRepository.Preview drift =
                preview("preview-a", SHA_C);
        assertThatThrownBy(() -> remediations.create(
                drift,
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW)))
                .isInstanceOfSatisfying(
                        ScenarioRehearsalRemediationConflictException.class,
                        conflict -> assertThat(conflict.reason())
                                .isEqualTo(
                                        ScenarioRehearsalRemediationConflictException
                                                .Reason
                                                .IDEMPOTENCY_CONFLICT));
    }

    @Test
    void readIsExactScopeAndFailsClosedWhenMutableProjectionIsTampered() {
        remediations.create(
                preview("preview-a"),
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW));
        CapabilitySnapshot.Scope anotherScope =
                new CapabilitySnapshot.Scope(
                        "tenant-b",
                        SCOPE.organizationId(),
                        SCOPE.projectId(),
                        SCOPE.environmentId(),
                        SCOPE.region());

        assertThat(remediations.find(
                anotherScope, REMEDIATION_ID)).isEmpty();

        jdbc.update("""
                UPDATE scenario_rehearsal_remediation_plans
                SET state = 'APPROVED'
                WHERE remediation_id = ?
                """, REMEDIATION_ID);

        assertThatThrownBy(() -> remediations.find(
                SCOPE, REMEDIATION_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "state differs from immutable facts");
    }

    @Test
    void enforcesApprovalOrderGenerationAndDistinctAuthenticatedActors() {
        remediations.create(
                preview("preview-a"),
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW));

        assertConflict(
                () -> remediations.approve(
                        approval(
                                "review-first",
                                0,
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.INDEPENDENT_REVIEWER,
                                "reviewer-a"),
                        remediationPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVE)),
                ScenarioRehearsalRemediationConflictException
                        .Reason.APPROVAL_ORDER_INVALID);

        ScenarioRehearsalRemediationRepository.ApprovalResult
                owner = remediations.approve(
                approval(
                        "owner-approve",
                        0,
                        ScenarioRehearsalRemediationApprovalCommand
                                .Role.OWNER,
                        "owner-a"),
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_APPROVE));
        ScenarioRehearsalRemediationRepository.ApprovalResult
                ownerReplay = remediations.approve(
                approval(
                        "owner-approve",
                        0,
                        ScenarioRehearsalRemediationApprovalCommand
                                .Role.OWNER,
                        "owner-a"),
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_APPROVE));

        assertThat(owner.idempotentReplay()).isFalse();
        assertThat(ownerReplay.idempotentReplay()).isTrue();
        assertThat(ownerReplay.approval()).isEqualTo(
                owner.approval());
        assertConflict(
                () -> remediations.approve(
                        approval(
                                "owner-approve",
                                0,
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.OWNER,
                                "owner-b"),
                        remediationPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVE)),
                ScenarioRehearsalRemediationConflictException
                        .Reason.IDEMPOTENCY_CONFLICT);
        assertConflict(
                () -> remediations.approve(
                        approval(
                                "review-stale",
                                0,
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.INDEPENDENT_REVIEWER,
                                "reviewer-a"),
                        remediationPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVE)),
                ScenarioRehearsalRemediationConflictException
                        .Reason.APPROVAL_GENERATION_MISMATCH);
        assertConflict(
                () -> remediations.approve(
                        approval(
                                "review-same-actor",
                                1,
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.INDEPENDENT_REVIEWER,
                                "owner-a"),
                        remediationPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVE)),
                ScenarioRehearsalRemediationConflictException
                        .Reason.DISTINCT_ACTOR_REQUIRED);
    }

    @Test
    void failsClosedOnServerPolicyDriftAndSharedDelegatingPrincipal() {
        remediations.create(
                preview("preview-a"),
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW));
        ScenarioRehearsalRemediationPolicy drift =
                new ScenarioRehearsalRemediationPolicy(
                        2,
                        remediationPolicy().planLifetime(),
                        remediationPolicy().maximumClockSkew(),
                        remediationPolicy().ownerGroups(),
                        remediationPolicy()
                                .independentReviewerGroups());

        assertConflict(
                () -> remediations.approve(
                        approval(
                                "owner-policy-drift",
                                0,
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.OWNER,
                                "owner-a"),
                        drift,
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVE)),
                ScenarioRehearsalRemediationConflictException
                        .Reason.POLICY_MISMATCH);

        remediations.approve(
                approval(
                        "owner-delegated",
                        0,
                        ScenarioRehearsalRemediationApprovalCommand
                                .Role.OWNER,
                        "owner-proxy",
                        "principal-a"),
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_APPROVE));
        assertConflict(
                () -> remediations.approve(
                        approval(
                                "reviewer-delegated",
                                1,
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.INDEPENDENT_REVIEWER,
                                "reviewer-proxy",
                                "principal-a"),
                        remediationPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVE)),
                ScenarioRehearsalRemediationConflictException
                        .Reason.DISTINCT_ACTOR_REQUIRED);
    }

    @Test
    void rejectionIsAnImmutableTerminalDecision() {
        remediations.create(
                preview("preview-a"),
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW));
        remediations.approve(
                rejection("owner-reject", "owner-a"),
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_APPROVE));

        assertThat(remediations.find(SCOPE, REMEDIATION_ID))
                .get()
                .extracting(
                        ScenarioRehearsalRemediationRepository
                                .Snapshot::state)
                .isEqualTo(
                        ScenarioRehearsalRemediationRepository
                                .State.REJECTED);
        assertConflict(
                () -> remediations.approve(
                        approval(
                                "review-after-reject",
                                1,
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.INDEPENDENT_REVIEWER,
                                "reviewer-a"),
                        remediationPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVE)),
                ScenarioRehearsalRemediationConflictException
                        .Reason.PLAN_REJECTED);
    }

    @Test
    void databaseClockExpiryFailsClosed() {
        remediations.create(
                preview("preview-a"),
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW));
        databaseTime.set(
                NOW.plus(remediationPolicy().planLifetime()));

        assertConflict(
                () -> remediations.approve(
                        approval(
                                "owner-late",
                                0,
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.OWNER,
                                "owner-a"),
                        remediationPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVE)),
                ScenarioRehearsalRemediationConflictException
                        .Reason.PLAN_EXPIRED);
    }

    @Test
    void admitsExactSuccessorAndReceiptAsOneReplayableLineage() {
        Approved approved = approve();
        ScenarioRehearsalRemediationSubmitCommand command =
                submitCommand(approved);
        ScenarioRehearsalRemediationRepository.SubmissionMutation
                mutation = submissionMutation(
                command, "owner-a");

        ScenarioRehearsalRemediationRepository.SubmissionResult
                created = remediations.submit(
                mutation,
                remediationPolicy(),
                batchPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT));
        ScenarioRehearsalRemediationRepository.SubmissionResult
                replay = remediations.submit(
                mutation,
                remediationPolicy(),
                batchPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT));

        assertThat(created.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.receipt()).isEqualTo(
                created.receipt());
        assertThat(created.receipt().predecessorJobId())
                .isEqualTo(PREDECESSOR);
        assertThat(created.receipt().successorJobId())
                .isEqualTo(
                        ScenarioRehearsalBatchIdentity.derive(
                                mapper,
                                SCOPE,
                                REMEDIATION_ID));
        assertThat(remediations.find(SCOPE, REMEDIATION_ID))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.state()).isEqualTo(
                            ScenarioRehearsalRemediationRepository
                                    .State.SUBMITTED);
                    assertThat(snapshot.approvals()).hasSize(2);
                    assertThat(snapshot.receipt()).isEqualTo(
                            created.receipt());
                });
        assertThat(batches.find(
                SCOPE,
                created.receipt().successorJobId(),
                batchPolicy())).isPresent();
        assertConflict(
                () -> remediations.submit(
                        submissionMutation(
                                command, "owner-b"),
                        remediationPolicy(),
                        batchPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT)),
                ScenarioRehearsalRemediationConflictException
                        .Reason.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void preoccupiedSuccessorIdentityCannotBeAdoptedIntoTheLineage() {
        Approved approved = approve();
        ScenarioRehearsalRemediationSubmitCommand command =
                submitCommand(approved);
        batches.submit(
                successorSubmission(),
                batchPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_BATCH_CREATE));

        assertConflict(
                () -> remediations.submit(
                        submissionMutation(command, "owner-a"),
                        remediationPolicy(),
                        batchPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT)),
                ScenarioRehearsalRemediationConflictException
                        .Reason.SUCCESSOR_IDENTITY_ALREADY_USED);

        assertThat(remediations.find(SCOPE, REMEDIATION_ID))
                .get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.state()).isEqualTo(
                            ScenarioRehearsalRemediationRepository
                                    .State.APPROVED);
                    assertThat(snapshot.receipt()).isNull();
                });
    }

    @Test
    void mandatorySuccessAuditFailureRollsBackPreviewCreation() {
        MirrorOperationAuditRepository unavailable =
                mock(MirrorOperationAuditRepository.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(unavailable).append(any());
        MirrorOperationObservability observations =
                new MirrorOperationObservability(
                        unavailable,
                        MirrorOperationTelemetry.noop(),
                        () -> 1L);

        assertThatThrownBy(() -> remediations.create(
                preview("preview-a"),
                remediationPolicy(),
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW,
                        identity("owner-a"),
                        "preview-a",
                        "",
                        REMEDIATION_ID)))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure ->
                        assertThat(((IntegrationProblemException)
                                failure).problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE"));

        assertThat(remediations.find(
                SCOPE, REMEDIATION_ID)).isEmpty();
    }

    @Test
    void mandatorySuccessAuditFailureRollsBackSuccessorAndReceipt() {
        Approved approved = approve();
        ScenarioRehearsalRemediationSubmitCommand command =
                submitCommand(approved);
        MirrorOperationAuditRepository unavailable =
                mock(MirrorOperationAuditRepository.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(unavailable).append(any());
        MirrorOperationObservability observations =
                new MirrorOperationObservability(
                        unavailable,
                        MirrorOperationTelemetry.noop(),
                        () -> 1L);

        assertThatThrownBy(() -> remediations.submit(
                submissionMutation(command, "owner-a"),
                remediationPolicy(),
                batchPolicy(),
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_SUBMIT,
                        identity("owner-a"),
                        command.commandId(),
                        command.remediationPlanFingerprint(),
                        REMEDIATION_ID)))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure ->
                        assertThat(((IntegrationProblemException)
                                failure).problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE"));

        ScenarioRehearsalRemediationRepository.Snapshot
                retained = remediations.find(
                SCOPE, REMEDIATION_ID).orElseThrow();
        assertThat(retained.state()).isEqualTo(
                ScenarioRehearsalRemediationRepository.State.APPROVED);
        assertThat(retained.receipt()).isNull();
        assertThat(batches.find(
                SCOPE,
                ScenarioRehearsalBatchIdentity.derive(
                        mapper, SCOPE, REMEDIATION_ID),
                batchPolicy())).isEmpty();
    }

    @Test
    void transactionalBatchAdmissionRejectsCallsWithoutAnOuterTransaction() {
        assertThatThrownBy(() ->
                batches.submitInCurrentTransaction(
                        successorSubmission(), batchPolicy()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("active transaction");
    }

    private Approved approve() {
        remediations.create(
                preview("preview-a"),
                remediationPolicy(),
                observation(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_REMEDIATION_PREVIEW));
        ScenarioRehearsalRemediationApproval owner =
                remediations.approve(
                        approval(
                                "owner-approve",
                                0,
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.OWNER,
                                "owner-a"),
                        remediationPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVE))
                        .approval();
        ScenarioRehearsalRemediationApproval reviewer =
                remediations.approve(
                        approval(
                                "reviewer-approve",
                                1,
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.INDEPENDENT_REVIEWER,
                                "reviewer-b"),
                        remediationPolicy(),
                        observation(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_REMEDIATION_APPROVE))
                        .approval();
        return new Approved(owner, reviewer);
    }

    private ScenarioRehearsalRemediationRepository.Preview
    preview(String previewRequestId) {
        return preview(previewRequestId, SHA_A);
    }

    private ScenarioRehearsalRemediationRepository.Preview
    preview(
            String previewRequestId,
            String expectedPlanFingerprint) {
        ScenarioRehearsalRemediationPlan plan = plan(
                previewRequestId, expectedPlanFingerprint);
        String fingerprint = ProtocolFingerprint.ofBounded(
                mapper,
                new DatabaseScenarioRehearsalRemediationRepository
                        .PreviewFingerprintMaterial(
                        plan.predecessorJobId(),
                        plan.previewRequestId(),
                        plan.strategy(),
                        plan.reasonCode(),
                        plan.replacements(),
                        plan.predecessorWorkbookSeedFingerprint(),
                        plan.governanceTicketRef()),
                512 * 1024);
        return new ScenarioRehearsalRemediationRepository.Preview(
                plan, fingerprint);
    }

    private ScenarioRehearsalRemediationPlan plan(
            String previewRequestId,
            String oldPlanFingerprint) {
        MirrorArtifactRef oldPlan = new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                "plan-old",
                1,
                oldPlanFingerprint);
        ScenarioRehearsalBatchRequest successor =
                new ScenarioRehearsalBatchRequest(
                        "",
                        REMEDIATION_ID,
                        List.of(
                                new ScenarioRehearsalBatchRequest.Entry(
                                        "entry-a",
                                        oldPlan)));
        return ScenarioRehearsalRemediationPlan.seal(
                mapper,
                new ScenarioRehearsalRemediationPlan(
                        "",
                        "",
                        SCOPE,
                        REMEDIATION_ID,
                        previewRequestId,
                        PREDECESSOR,
                        SHA_B,
                        SHA_C,
                        ScenarioRehearsalBatchJob.Status.FAILED,
                        List.of("REHEARSAL_FAILED"),
                        ScenarioRehearsalRemediationPreviewRequest
                                .Strategy.RERUN_EXACT,
                        ScenarioRehearsalRemediationPreviewRequest
                                .ReasonCode
                                .TRANSIENT_EXECUTION_RECHECK,
                        List.of(),
                        successor,
                        ProtocolFingerprint.of(mapper, successor),
                        ticket(),
                        ScenarioRehearsalRemediationPlan
                                .ApprovalPolicy.twoPerson(
                                        remediationPolicy()
                                                .generation(),
                                        remediationPolicy()
                                                .fingerprint(mapper)),
                        NOW,
                        NOW.plus(
                                remediationPolicy()
                                        .planLifetime())));
    }

    private ScenarioRehearsalRemediationRepository.ApprovalMutation
    approval(
            String commandId,
            long expectedGeneration,
            ScenarioRehearsalRemediationApprovalCommand.Role role,
            String actor) {
        return approval(
                commandId,
                expectedGeneration,
                role,
                actor,
                "");
    }

    private ScenarioRehearsalRemediationRepository.ApprovalMutation
    approval(
            String commandId,
            long expectedGeneration,
            ScenarioRehearsalRemediationApprovalCommand.Role role,
            String actor,
            String delegatedBy) {
        ScenarioRehearsalRemediationApprovalCommand command =
                new ScenarioRehearsalRemediationApprovalCommand(
                        "",
                        commandId,
                        plan("preview-a", SHA_A)
                                .planFingerprint(),
                        expectedGeneration,
                        role,
                        ScenarioRehearsalRemediationApprovalCommand
                                .Decision.APPROVE,
                        ticket(),
                        ScenarioRehearsalRemediationApprovalCommand
                                .ReasonCode.APPROVED_AS_REVIEWED);
        return new ScenarioRehearsalRemediationRepository
                .ApprovalMutation(
                SCOPE,
                REMEDIATION_ID,
                command,
                ProtocolFingerprint.of(mapper, command),
                actor,
                delegatedBy);
    }

    private ScenarioRehearsalRemediationRepository.ApprovalMutation
    rejection(
            String commandId,
            String actor) {
        ScenarioRehearsalRemediationApprovalCommand command =
                new ScenarioRehearsalRemediationApprovalCommand(
                        "",
                        commandId,
                        plan("preview-a", SHA_A)
                                .planFingerprint(),
                        0,
                        ScenarioRehearsalRemediationApprovalCommand
                                .Role.OWNER,
                        ScenarioRehearsalRemediationApprovalCommand
                                .Decision.REJECT,
                        ticket(),
                        ScenarioRehearsalRemediationApprovalCommand
                                .ReasonCode
                                .REJECTED_REQUIRES_CHANGES);
        return new ScenarioRehearsalRemediationRepository
                .ApprovalMutation(
                SCOPE,
                REMEDIATION_ID,
                command,
                ProtocolFingerprint.of(mapper, command),
                actor,
                "");
    }

    private ScenarioRehearsalRemediationSubmitCommand
    submitCommand(Approved approved) {
        return new ScenarioRehearsalRemediationSubmitCommand(
                "",
                "submit-a",
                plan("preview-a", SHA_A)
                        .planFingerprint(),
                2,
                approved.reviewer()
                        .approvalFingerprint(),
                null);
    }

    private ScenarioRehearsalRemediationRepository
            .SubmissionMutation
    submissionMutation(
            ScenarioRehearsalRemediationSubmitCommand command,
            String actor) {
        return new ScenarioRehearsalRemediationRepository
                .SubmissionMutation(
                SCOPE,
                REMEDIATION_ID,
                command,
                ProtocolFingerprint.of(mapper, command),
                successorSubmission(),
                actor,
                "");
    }

    private ScenarioRehearsalBatchRepository.Submission
    successorSubmission() {
        ScenarioRehearsalBatchRequest request =
                plan("preview-a", SHA_A)
                        .successorRequest();
        MirrorArtifactRef planRef =
                request.entries().getFirst()
                        .compiledPlanRef();
        String childRequest =
                REMEDIATION_ID + ":plan:000";
        ScenarioRehearsalBatchManifest manifest =
                ScenarioRehearsalBatchManifestIntegrity.seal(
                        mapper,
                        new ScenarioRehearsalBatchManifest(
                                "",
                                ScenarioRehearsalBatchIdentity
                                        .derive(
                                                mapper,
                                                SCOPE,
                                                REMEDIATION_ID),
                                "",
                                SCOPE,
                                REMEDIATION_ID,
                                List.of(
                                        new ScenarioRehearsalBatchManifest
                                                .Entry(
                                                0,
                                                "entry-a",
                                                planRef,
                                                childRequest,
                                                ScenarioRehearsalRunIdentity
                                                        .derive(
                                                                mapper,
                                                                SCOPE,
                                                                childRequest),
                                                1,
                                                Duration.ofSeconds(5))),
                                1));
        return new ScenarioRehearsalBatchRepository.Submission(
                request,
                ProtocolFingerprint.of(mapper, request),
                manifest,
                new ScenarioRehearsalBatchPrincipal(
                        SCOPE,
                        "USER",
                        "owner-a",
                        "",
                        Set.of(
                                ScenarioRehearsalRemediationPolicy
                                        .DEFAULT_OWNER_GROUP),
                        "RESTRICTED",
                        ""));
    }

    private MirrorOperationObservability.Observation observation(
            MirrorOperationAuditEvent.Operation operation) {
        return MirrorOperationObservability.noop().start(
                operation,
                identity("owner-a"),
                "",
                "",
                "");
    }

    private static IntegrationRequestContext identity(
            String actor) {
        return new IntegrationRequestContext(
                SCOPE.tenantId(),
                SCOPE.organizationId(),
                SCOPE.projectId(),
                SCOPE.environmentId(),
                SCOPE.region(),
                "USER",
                actor,
                "",
                ScenarioRehearsalRemediationPolicy.PURPOSE,
                "corr-remediation",
                Set.of(
                        ScenarioRehearsalRemediationPolicy
                                .DEFAULT_OWNER_GROUP),
                "RESTRICTED",
                "");
    }

    private static MirrorArtifactRef ticket() {
        return new MirrorArtifactRef(
                "GOVERNANCE_REVIEW_TICKET",
                "ticket-a",
                1,
                SHA_C);
    }

    private static ScenarioRehearsalRemediationPolicy
    remediationPolicy() {
        return ScenarioRehearsalRemediationPolicy.defaults();
    }

    private static ScenarioRehearsalBatchPolicy batchPolicy() {
        return ScenarioRehearsalBatchPolicy.defaults();
    }

    private static void assertConflict(
            Runnable action,
            ScenarioRehearsalRemediationConflictException.Reason
                    reason) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ScenarioRehearsalRemediationConflictException.class,
                        conflict -> assertThat(conflict.reason())
                                .isEqualTo(reason));
    }

    private record Approved(
            ScenarioRehearsalRemediationApproval owner,
            ScenarioRehearsalRemediationApproval reviewer
    ) {
    }
}
