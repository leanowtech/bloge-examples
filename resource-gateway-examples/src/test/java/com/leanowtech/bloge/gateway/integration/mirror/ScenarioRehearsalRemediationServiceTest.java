package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalRemediationServiceTest {
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
    private static final String SHA_A =
            "sha256:" + "a".repeat(64);
    private static final String SHA_B =
            "sha256:" + "b".repeat(64);
    private static final String SHA_C =
            "sha256:" + "c".repeat(64);
    private static final String SHA_D =
            "sha256:" + "d".repeat(64);

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private ScenarioRehearsalRemediationRepository repository;
    private ScenarioRehearsalBatchWorkbookService workbooks;
    private ScenarioRehearsalBatchEvidenceRepository evidence;
    private ScenarioRehearsalBatchEvidenceIntegrityService
            integrity;
    private ScenarioRehearsalBatchCompiler compiler;
    private ScenarioRehearsalRemediationService service;
    private ScenarioRehearsalBatchRequest original;
    private ScenarioRehearsalBatchWorkbookSeed workbook;
    private ScenarioRehearsalBatchEvidenceBundle bundle;

    @BeforeEach
    void setUp() {
        repository = mock(
                ScenarioRehearsalRemediationRepository.class);
        workbooks = mock(
                ScenarioRehearsalBatchWorkbookService.class);
        evidence = mock(
                ScenarioRehearsalBatchEvidenceRepository.class);
        integrity = mock(
                ScenarioRehearsalBatchEvidenceIntegrityService.class);
        compiler = mock(
                ScenarioRehearsalBatchCompiler.class);
        service = new ScenarioRehearsalRemediationService(
                repository,
                ScenarioRehearsalRemediationPolicy.defaults(),
                ScenarioRehearsalBatchPolicy.defaults(),
                workbooks,
                evidence,
                integrity,
                compiler,
                mapper,
                MirrorOperationObservability.noop(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        original = new ScenarioRehearsalBatchRequest(
                "",
                "batch-original",
                List.of(
                        new ScenarioRehearsalBatchRequest.Entry(
                                "entry-a",
                                plan("plan-old", SHA_A))));
        arrangeSignedPredecessor();
    }

    @Test
    void freezesExactSignedPredecessorAndServerDerivedRerun() {
        when(repository.create(
                any(), any(), any()))
                .thenAnswer(invocation -> {
                    ScenarioRehearsalRemediationRepository.Preview
                            preview = invocation.getArgument(0);
                    MirrorOperationObservability.Observation
                            observation = invocation.getArgument(2);
                    observation.succeeded(
                            preview.plan().remediationId());
                    return new ScenarioRehearsalRemediationRepository
                            .PreviewResult(
                            preview.plan(), false);
                });
        ScenarioRehearsalRemediationPreviewRequest request =
                new ScenarioRehearsalRemediationPreviewRequest(
                        "",
                        "preview-a",
                        SHA_B,
                        ScenarioRehearsalRemediationPreviewRequest
                                .Strategy.RERUN_EXACT,
                        List.of(),
                        ticket(),
                        ScenarioRehearsalRemediationPreviewRequest
                                .ReasonCode
                                .TRANSIENT_EXECUTION_RECHECK);

        ScenarioRehearsalRemediationPlan plan =
                service.preview(
                        PREDECESSOR,
                        request,
                        owner()).plan();

        assertThat(plan.predecessorJobId())
                .isEqualTo(PREDECESSOR);
        assertThat(plan.predecessorEvidenceBundleFingerprint())
                .isEqualTo(SHA_C);
        assertThat(plan.predecessorBlockers())
                .containsExactly(
                        "BLOCKER_ASSERTION_FAILED",
                        "REHEARSAL_FAILED");
        assertThat(plan.reasonCode()).isEqualTo(
                request.reasonCode());
        assertThat(plan.successorRequest().entries())
                .isEqualTo(original.entries());
        assertThat(plan.remediationId()).isEqualTo(
                ScenarioRehearsalRemediationIdentity.derive(
                        mapper,
                        SCOPE,
                        PREDECESSOR,
                        request.previewRequestId()));
        assertThat(plan.generatedAt()).isEqualTo(NOW);
        assertThat(plan.expiresAt()).isEqualTo(
                NOW.plus(ScenarioRehearsalRemediationPolicy
                        .defaults().planLifetime()));
        plan.verify(mapper);

        ArgumentCaptor<IntegrationRequestContext> identity =
                ArgumentCaptor.forClass(
                        IntegrationRequestContext.class);
        verify(compiler).compile(
                eq(plan.successorRequest()),
                identity.capture());
        assertThat(identity.getValue().purpose())
                .isEqualTo("MIRROR_REHEARSAL");
        assertThat(identity.getValue().actorId())
                .isEqualTo(owner().actorId());
    }

    @Test
    void replacementUsesSignedEntryFenceAndPreservesOrder() {
        MirrorArtifactRef replacement =
                plan("plan-new", SHA_D);
        when(repository.create(
                any(), any(), any()))
                .thenAnswer(invocation -> {
                    ScenarioRehearsalRemediationRepository.Preview
                            preview = invocation.getArgument(0);
                    invocation
                            .<MirrorOperationObservability.Observation>
                                    getArgument(2)
                            .succeeded(
                                    preview.plan().remediationId());
                    return new ScenarioRehearsalRemediationRepository
                            .PreviewResult(
                            preview.plan(), false);
                });
        ScenarioRehearsalRemediationPreviewRequest request =
                replacementRequest(
                        original.entries().getFirst()
                                .compiledPlanRef(),
                        replacement);

        ScenarioRehearsalRemediationPlan plan =
                service.preview(
                        PREDECESSOR,
                        request,
                        owner()).plan();

        assertThat(plan.successorRequest().entries())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.entryId())
                            .isEqualTo("entry-a");
                    assertThat(entry.compiledPlanRef())
                            .isEqualTo(replacement);
                });

        ScenarioRehearsalRemediationPreviewRequest stale =
                replacementRequest(
                        plan("different-old", SHA_A),
                        replacement);
        assertThatThrownBy(() -> service.preview(
                PREDECESSOR,
                stale,
                owner()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status())
                                    .isEqualTo(409);
                            assertThat(failure.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.REMEDIATION.REPLACEMENT_FENCE_MISMATCH");
                        });
    }

    @Test
    void refusesWorkbookDriftAndNonOwnerBeforeCreatingPlan() {
        ScenarioRehearsalRemediationPreviewRequest drift =
                new ScenarioRehearsalRemediationPreviewRequest(
                        "",
                        "preview-a",
                        SHA_D,
                        ScenarioRehearsalRemediationPreviewRequest
                                .Strategy.RERUN_EXACT,
                        List.of(),
                        ticket(),
                        ScenarioRehearsalRemediationPreviewRequest
                                .ReasonCode
                                .TRANSIENT_EXECUTION_RECHECK);

        assertThatThrownBy(() -> service.preview(
                PREDECESSOR,
                drift,
                owner()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(
                                failure.problem().code())
                                .isEqualTo(
                                        "RG.MIRROR.REMEDIATION.WORKBOOK_FINGERPRINT_MISMATCH"));
        assertThatThrownBy(() -> service.preview(
                PREDECESSOR,
                drift,
                identity(
                        "service-a",
                        "SERVICE",
                        Set.of(ScenarioRehearsalRemediationPolicy
                                .DEFAULT_OWNER_GROUP))))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(
                                failure.problem().status())
                                .isEqualTo(403));
        verify(repository, never()).create(
                any(), any(), any());
    }

    @Test
    void authorizesApprovalRoleFromTrustedGroupsOnly() {
        ScenarioRehearsalRemediationApprovalCommand command =
                new ScenarioRehearsalRemediationApprovalCommand(
                        "",
                        "approve-a",
                        SHA_A,
                        0,
                        ScenarioRehearsalRemediationApprovalCommand
                                .Role.OWNER,
                        ScenarioRehearsalRemediationApprovalCommand
                                .Decision.APPROVE,
                        ticket(),
                        ScenarioRehearsalRemediationApprovalCommand
                                .ReasonCode.APPROVED_AS_REVIEWED);

        assertThatThrownBy(() -> service.approve(
                "scenario-remediation-" + "e".repeat(64),
                command,
                reviewer()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status())
                                    .isEqualTo(403);
                            assertThat(failure.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.REMEDIATION.ROLE_REQUIRED");
                        });
        verify(repository, never()).approve(
                any(), any(), any());
    }

    @Test
    void submissionRecompilesFrozenSuccessorUnderInternalPurpose() {
        ScenarioRehearsalRemediationPlan plan =
                remediationPlan();
        ScenarioRehearsalRemediationRepository.Snapshot snapshot =
                mock(ScenarioRehearsalRemediationRepository
                        .Snapshot.class);
        when(snapshot.plan()).thenReturn(plan);
        when(repository.find(
                SCOPE, plan.remediationId()))
                .thenReturn(Optional.of(snapshot));
        ScenarioRehearsalBatchManifest manifest =
                manifest(plan.successorRequest());
        when(compiler.compile(
                eq(plan.successorRequest()), any()))
                .thenReturn(manifest);
        ScenarioRehearsalRemediationReceipt receipt =
                mock(ScenarioRehearsalRemediationReceipt.class);
        when(repository.submit(
                any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    invocation
                            .<MirrorOperationObservability.Observation>
                                    getArgument(3)
                            .succeeded(manifest.batchId());
                    return new ScenarioRehearsalRemediationRepository
                            .SubmissionResult(
                            receipt, false);
                });
        ScenarioRehearsalRemediationSubmitCommand command =
                new ScenarioRehearsalRemediationSubmitCommand(
                        "",
                        "submit-a",
                        plan.planFingerprint(),
                        2,
                        SHA_D,
                        null);

        assertThat(service.submit(
                plan.remediationId(),
                command,
                owner()).receipt()).isSameAs(receipt);

        ArgumentCaptor<ScenarioRehearsalRemediationRepository
                .SubmissionMutation> mutation =
                ArgumentCaptor.forClass(
                        ScenarioRehearsalRemediationRepository
                                .SubmissionMutation.class);
        verify(repository).submit(
                mutation.capture(),
                eq(ScenarioRehearsalRemediationPolicy
                        .defaults()),
                eq(ScenarioRehearsalBatchPolicy.defaults()),
                any());
        assertThat(mutation.getValue()
                .successorSubmission().request())
                .isEqualTo(plan.successorRequest());
        assertThat(mutation.getValue()
                .successorSubmission().manifest())
                .isEqualTo(manifest);
        assertThat(mutation.getValue()
                .successorSubmission().principal()
                .actorId()).isEqualTo(owner().actorId());

        ArgumentCaptor<IntegrationRequestContext> internal =
                ArgumentCaptor.forClass(
                        IntegrationRequestContext.class);
        verify(compiler).compile(
                eq(plan.successorRequest()),
                internal.capture());
        assertThat(internal.getValue().purpose())
                .isEqualTo("MIRROR_REHEARSAL");
    }

    @Test
    void readReturnsContentAddressedPublicLineage() {
        ScenarioRehearsalRemediationPlan plan =
                remediationPlan();
        when(repository.find(
                SCOPE, plan.remediationId()))
                .thenReturn(Optional.of(
                        new ScenarioRehearsalRemediationRepository
                                .Snapshot(
                                plan,
                                ScenarioRehearsalRemediationRepository
                                        .State.PENDING_APPROVAL,
                                List.of(),
                                null)));

        ScenarioRehearsalRemediationLineage lineage =
                service.find(
                        plan.remediationId(),
                        owner()).orElseThrow();

        lineage.verify(mapper);
        assertThat(lineage.plan()).isEqualTo(plan);
        assertThat(lineage.state()).isEqualTo(
                ScenarioRehearsalRemediationRepository
                        .State.PENDING_APPROVAL);
        assertThat(lineage.approvalGeneration()).isZero();
        assertThat(lineage.approvalHeadFingerprint())
                .isBlank();
    }

    @Test
    void comparisonUsesOnlyTheSubmittedLineageAndBothSignedWorkbooks() {
        ScenarioRehearsalRemediationComparisonTestFixtures
                .Fixture fixture =
                ScenarioRehearsalRemediationComparisonTestFixtures
                        .resolved(mapper);
        ScenarioRehearsalRemediationLineage lineage =
                fixture.lineage();
        when(repository.find(
                SCOPE, lineage.plan().remediationId()))
                .thenReturn(Optional.of(
                        new ScenarioRehearsalRemediationRepository
                                .Snapshot(
                                lineage.plan(),
                                lineage.state(),
                                lineage.approvals(),
                                lineage.receipt())));
        when(workbooks.workbookSeed(
                eq(ScenarioRehearsalRemediationComparisonTestFixtures
                        .PREDECESSOR_ID),
                any())).thenReturn(fixture.predecessor());
        when(workbooks.workbookSeed(
                eq(ScenarioRehearsalRemediationComparisonTestFixtures
                        .SUCCESSOR_ID),
                any())).thenReturn(fixture.successor());

        ScenarioRehearsalRemediationComparison comparison =
                service.compare(
                        lineage.plan().remediationId(),
                        owner());

        assertThat(comparison.gateTransition())
                .isEqualTo(
                        ScenarioRehearsalRemediationComparison
                                .GateTransition.RESOLVED);
        comparison.verify(mapper);
        ArgumentCaptor<IntegrationRequestContext> internal =
                ArgumentCaptor.forClass(
                        IntegrationRequestContext.class);
        verify(workbooks, times(2)).workbookSeed(
                anyString(), internal.capture());
        assertThat(internal.getAllValues())
                .allSatisfy(value ->
                        assertThat(value.purpose())
                                .isEqualTo(
                                        "MIRROR_REHEARSAL"));
    }

    @Test
    void comparisonFailsClosedBeforeAReviewedSuccessorExists() {
        ScenarioRehearsalRemediationPlan plan =
                remediationPlan();
        when(repository.find(
                SCOPE, plan.remediationId()))
                .thenReturn(Optional.of(
                        new ScenarioRehearsalRemediationRepository
                                .Snapshot(
                                plan,
                                ScenarioRehearsalRemediationRepository
                                        .State.PENDING_APPROVAL,
                                List.of(),
                                null)));

        assertThatThrownBy(() -> service.compare(
                plan.remediationId(),
                owner()))
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> {
                            assertThat(failure.problem().status())
                                    .isEqualTo(409);
                            assertThat(failure.problem().code())
                                    .isEqualTo(
                                            "RG.MIRROR.REMEDIATION.COMPARISON_NOT_READY");
                        });
        verify(workbooks, never()).workbookSeed(
                anyString(), any());
    }

    private void arrangeSignedPredecessor() {
        workbook = mock(
                ScenarioRehearsalBatchWorkbookSeed.class);
        when(workbook.seedFingerprint()).thenReturn(SHA_B);
        when(workbook.gateReady()).thenReturn(false);
        when(workbook.blockers()).thenReturn(List.of(
                "REHEARSAL_FAILED",
                "BLOCKER_ASSERTION_FAILED"));
        when(workbook.jobId()).thenReturn(PREDECESSOR);
        when(workbook.scope()).thenReturn(SCOPE);
        when(workbook.evidenceBundleFingerprint())
                .thenReturn(SHA_C);
        when(workbook.status()).thenReturn(
                ScenarioRehearsalBatchJob.Status.FAILED);
        when(workbook.requestFingerprint()).thenReturn(
                ProtocolFingerprint.of(mapper, original));
        when(workbook.manifestFingerprint()).thenReturn(SHA_D);
        when(workbooks.workbookSeed(
                eq(PREDECESSOR), any()))
                .thenReturn(workbook);

        ScenarioRehearsalBatchJob job =
                mock(ScenarioRehearsalBatchJob.class);
        when(job.jobId()).thenReturn(PREDECESSOR);
        when(job.scope()).thenReturn(SCOPE);
        when(job.status()).thenReturn(
                ScenarioRehearsalBatchJob.Status.FAILED);
        when(job.requestFingerprint()).thenReturn(
                ProtocolFingerprint.of(mapper, original));
        ScenarioRehearsalBatchManifest sourceManifest =
                mock(ScenarioRehearsalBatchManifest.class);
        when(sourceManifest.manifestFingerprint())
                .thenReturn(SHA_D);
        ScenarioRehearsalBatchEvidenceIndex index =
                mock(ScenarioRehearsalBatchEvidenceIndex.class);
        when(index.job()).thenReturn(job);
        when(index.request()).thenReturn(original);
        when(index.manifest()).thenReturn(sourceManifest);
        bundle = mock(
                ScenarioRehearsalBatchEvidenceBundle.class);
        when(bundle.bundleFingerprint()).thenReturn(SHA_C);
        when(bundle.index()).thenReturn(index);
        when(evidence.find(SCOPE, PREDECESSOR))
                .thenReturn(Optional.of(bundle));
        ScenarioRehearsalBatchEvidenceIntegrityService
                .VerifiedBundle verified =
                mock(ScenarioRehearsalBatchEvidenceIntegrityService
                        .VerifiedBundle.class);
        when(verified.bundle()).thenReturn(bundle);
        when(integrity.requireVerified(bundle))
                .thenReturn(verified);
    }

    private ScenarioRehearsalRemediationPreviewRequest
    replacementRequest(
            MirrorArtifactRef expected,
            MirrorArtifactRef replacement) {
        return new ScenarioRehearsalRemediationPreviewRequest(
                "",
                "preview-replacement",
                SHA_B,
                ScenarioRehearsalRemediationPreviewRequest
                        .Strategy.REPLACE_COMPILED_PLANS,
                List.of(
                        new ScenarioRehearsalRemediationPreviewRequest
                                .PlanReplacement(
                                0,
                                "entry-a",
                                expected,
                                replacement)),
                ticket(),
                ScenarioRehearsalRemediationPreviewRequest
                        .ReasonCode.SCENARIO_REVISION);
    }

    private ScenarioRehearsalRemediationPlan remediationPlan() {
        ScenarioRehearsalBatchRequest successor =
                new ScenarioRehearsalBatchRequest(
                        "",
                        "scenario-remediation-" + "e".repeat(64),
                        original.entries());
        return ScenarioRehearsalRemediationPlan.seal(
                mapper,
                new ScenarioRehearsalRemediationPlan(
                        "",
                        "",
                        SCOPE,
                        successor.requestId(),
                        "preview-a",
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
                                        ScenarioRehearsalRemediationPolicy
                                                .defaults()
                                                .generation(),
                                        ScenarioRehearsalRemediationPolicy
                                                .defaults()
                                                .fingerprint(mapper)),
                        NOW,
                        NOW.plusSeconds(3600)));
    }

    private ScenarioRehearsalBatchManifest manifest(
            ScenarioRehearsalBatchRequest request) {
        String childRequest =
                request.requestId() + ":plan:000";
        return ScenarioRehearsalBatchManifestIntegrity.seal(
                mapper,
                new ScenarioRehearsalBatchManifest(
                        "",
                        ScenarioRehearsalBatchIdentity.derive(
                                mapper, SCOPE, request.requestId()),
                        "",
                        SCOPE,
                        request.requestId(),
                        List.of(
                                new ScenarioRehearsalBatchManifest.Entry(
                                        0,
                                        "entry-a",
                                        request.entries().getFirst()
                                                .compiledPlanRef(),
                                        childRequest,
                                        ScenarioRehearsalRunIdentity
                                                .derive(
                                                        mapper,
                                                        SCOPE,
                                                        childRequest),
                                        1,
                                        java.time.Duration
                                                .ofSeconds(5))),
                        1));
    }

    private static IntegrationRequestContext owner() {
        return identity(
                "owner-a",
                "USER",
                Set.of(ScenarioRehearsalRemediationPolicy
                        .DEFAULT_OWNER_GROUP));
    }

    private static IntegrationRequestContext reviewer() {
        return identity(
                "reviewer-a",
                "USER",
                Set.of(ScenarioRehearsalRemediationPolicy
                        .DEFAULT_REVIEWER_GROUP));
    }

    private static IntegrationRequestContext identity(
            String actor,
            String actorType,
            Set<String> groups) {
        return new IntegrationRequestContext(
                SCOPE.tenantId(),
                SCOPE.organizationId(),
                SCOPE.projectId(),
                SCOPE.environmentId(),
                SCOPE.region(),
                actorType,
                actor,
                "",
                ScenarioRehearsalRemediationPolicy.PURPOSE,
                "corr-remediation",
                groups,
                "RESTRICTED",
                "");
    }

    private static MirrorArtifactRef plan(
            String id,
            String fingerprint) {
        return new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                id,
                1,
                fingerprint);
    }

    private static MirrorArtifactRef ticket() {
        return new MirrorArtifactRef(
                "GOVERNANCE_REVIEW_TICKET",
                "ticket-a",
                1,
                SHA_C);
    }
}
