package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScenarioRehearsalBatchServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T08:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a", "org-a", "support", "test", "sg");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void serviceCompilesBeforeAdmissionAndCapturesNoCredential() {
        ScenarioRehearsalBatchCompiler compiler =
                mock(ScenarioRehearsalBatchCompiler.class);
        ScenarioRehearsalBatchRepository repository =
                mock(ScenarioRehearsalBatchRepository.class);
        ScenarioRehearsalBatchRequest request = request();
        ScenarioRehearsalBatchManifest manifest = manifest();
        ScenarioRehearsalBatchJob job = job();
        when(compiler.compile(request, identity()))
                .thenReturn(manifest);
        when(repository.submit(
                any(ScenarioRehearsalBatchRepository
                        .Submission.class),
                any(ScenarioRehearsalBatchPolicy.class),
                any(MirrorOperationObservability.Observation.class)))
                .thenAnswer(invocation -> {
                    MirrorOperationObservability.Observation
                            operation = invocation.getArgument(2);
                    operation.succeeded(job.jobId());
                    return new ScenarioRehearsalBatchRepository
                            .SubmissionResult(job, false);
                });
        List<MirrorOperationAuditEvent> events =
                new ArrayList<>();
        ScenarioRehearsalBatchService service =
                new ScenarioRehearsalBatchService(
                        compiler,
                        repository,
                        policy(),
                        mapper,
                        mock(
                                ScenarioRehearsalBatchEvidenceRepository
                                        .class),
                        observations(events));

        assertThat(service.submit(request, identity()).job())
                .isEqualTo(job);
        ArgumentCaptor<ScenarioRehearsalBatchRepository.Submission>
                captured = ArgumentCaptor.forClass(
                ScenarioRehearsalBatchRepository.Submission.class);
        verify(repository).submit(
                captured.capture(),
                any(ScenarioRehearsalBatchPolicy.class),
                any(MirrorOperationObservability.Observation.class));
        assertThat(captured.getValue().manifest())
                .isEqualTo(manifest);
        assertThat(captured.getValue().requestFingerprint())
                .matches("sha256:[a-f0-9]{64}");
        assertThat(captured.getValue().principal())
                .satisfies(principal -> {
                    assertThat(principal.scope()).isEqualTo(SCOPE);
                    assertThat(principal.actorId())
                            .isEqualTo("owner-a");
                    assertThat(principal.toExecutionContext("worker"))
                            .extracting(
                                    IntegrationRequestContext::purpose)
                            .isEqualTo("MIRROR_REHEARSAL");
                });
        assertThat(events)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.operation()).isEqualTo(
                            MirrorOperationAuditEvent.Operation
                                    .SCENARIO_REHEARSAL_BATCH_CREATE);
                    assertThat(event.outcome()).isEqualTo(
                            MirrorOperationAuditEvent.Outcome
                                    .SUCCEEDED);
                });
    }

    @Test
    void serviceRejectsUnauthorizedPurposeBeforeCompilation() {
        ScenarioRehearsalBatchCompiler compiler =
                mock(ScenarioRehearsalBatchCompiler.class);
        ScenarioRehearsalBatchService service =
                new ScenarioRehearsalBatchService(
                        compiler,
                        mock(ScenarioRehearsalBatchRepository.class),
                        policy(),
                        mapper,
                        mock(
                                ScenarioRehearsalBatchEvidenceRepository
                                        .class),
                        MirrorOperationObservability.noop());

        assertThatThrownBy(() -> service.submit(
                request(), identity("MIRROR_READ")))
                .isInstanceOf(IntegrationProblemException.class);
        verify(compiler, org.mockito.Mockito.never())
                .compile(any(), any());
    }

    @Test
    void serviceReadsEvidenceOnlyInsideTheAuthenticatedScope() {
        ScenarioRehearsalBatchEvidenceRepository evidence =
                mock(
                        ScenarioRehearsalBatchEvidenceRepository
                                .class);
        ScenarioRehearsalBatchEvidenceBundle bundle =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        when(evidence.find(SCOPE, job().jobId()))
                .thenReturn(java.util.Optional.of(bundle));
        List<MirrorOperationAuditEvent> events =
                new ArrayList<>();
        ScenarioRehearsalBatchService service =
                new ScenarioRehearsalBatchService(
                        mock(ScenarioRehearsalBatchCompiler.class),
                        mock(ScenarioRehearsalBatchRepository.class),
                        policy(),
                        mapper,
                        evidence,
                        observations(events));

        assertThat(service.evidence(
                job().jobId(),
                identity("GOVERNANCE_EVIDENCE_INGESTION")))
                .contains(bundle);
        verify(evidence).find(SCOPE, job().jobId());
        assertThat(events)
                .singleElement()
                .extracting(MirrorOperationAuditEvent::operation)
                .isEqualTo(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_BATCH_EVIDENCE_READ);
    }

    @Test
    void cancellationUsesItsOwnProtectedOperationAudit() {
        ScenarioRehearsalBatchRepository repository =
                mock(ScenarioRehearsalBatchRepository.class);
        ScenarioRehearsalBatchJob cancelled =
                job(ScenarioRehearsalBatchJob.Status.CANCELLED);
        when(repository.cancel(
                any(ScenarioRehearsalBatchRepository
                        .Cancellation.class),
                any(ScenarioRehearsalBatchPolicy.class),
                any(MirrorOperationObservability.Observation.class)))
                .thenAnswer(invocation -> {
                    MirrorOperationObservability.Observation
                            operation = invocation.getArgument(2);
                    operation.succeeded(cancelled.jobId());
                    return new ScenarioRehearsalBatchRepository
                            .SubmissionResult(cancelled, false);
                });
        List<MirrorOperationAuditEvent> events =
                new ArrayList<>();
        ScenarioRehearsalBatchService service =
                new ScenarioRehearsalBatchService(
                        mock(ScenarioRehearsalBatchCompiler.class),
                        repository,
                        policy(),
                        mapper,
                        mock(
                                ScenarioRehearsalBatchEvidenceRepository
                                        .class),
                        observations(events));

        assertThat(service.cancel(
                cancelled.jobId(),
                "cancel-running-001",
                "OWNER_REQUEST",
                identity()).job()).isEqualTo(cancelled);

        assertThat(events)
                .singleElement()
                .extracting(MirrorOperationAuditEvent::operation)
                .isEqualTo(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_BATCH_CANCEL);
    }

    @Test
    void jobReadsAuditSuccessAndHiddenAbsenceWithClosedReasons() {
        List<MirrorOperationAuditEvent> events =
                new ArrayList<>();
        MirrorOperationAuditRepository audit =
                new MirrorOperationAuditRepository() {
                    @Override
                    public MirrorOperationAuditEvent append(
                            MirrorOperationAuditEvent event) {
                        MirrorOperationAuditEvent persisted =
                                event.persisted(
                                        events.size() + 1L,
                                        NOW);
                        events.add(persisted);
                        return persisted;
                    }

                    @Override
                    public List<MirrorOperationAuditEvent> recent(
                            CapabilitySnapshot.Scope scope,
                            int limit) {
                        return List.copyOf(events);
                    }
                };
        ScenarioRehearsalBatchRepository repository =
                mock(ScenarioRehearsalBatchRepository.class);
        when(repository.find(
                SCOPE, job().jobId(), policy()))
                .thenReturn(
                        Optional.of(job()),
                        Optional.empty());
        ScenarioRehearsalBatchService service =
                new ScenarioRehearsalBatchService(
                        mock(ScenarioRehearsalBatchCompiler.class),
                        repository,
                        policy(),
                        mapper,
                        mock(
                                ScenarioRehearsalBatchEvidenceRepository
                                        .class),
                        new MirrorOperationObservability(
                                audit,
                                MirrorOperationTelemetry.noop(),
                                () -> 0L));

        assertThat(service.find(
                job().jobId(),
                identity("GOVERNANCE_EVIDENCE_INGESTION")))
                .contains(job());
        assertThat(service.find(
                job().jobId(),
                identity("GOVERNANCE_EVIDENCE_INGESTION")))
                .isEmpty();

        assertThat(events)
                .extracting(
                        MirrorOperationAuditEvent::operation,
                        MirrorOperationAuditEvent::outcome,
                        MirrorOperationAuditEvent::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_BATCH_READ,
                                MirrorOperationAuditEvent.Outcome
                                        .SUCCEEDED,
                                MirrorOperationAuditEvent.Reason.NONE),
                        org.assertj.core.groups.Tuple.tuple(
                                MirrorOperationAuditEvent.Operation
                                        .SCENARIO_REHEARSAL_BATCH_READ,
                                MirrorOperationAuditEvent.Outcome
                                        .REJECTED,
                                MirrorOperationAuditEvent.Reason
                                        .NOT_FOUND));
    }

    @Test
    void finalizationReadIsScopeBoundPayloadFreeAndAudited() {
        ScenarioRehearsalBatchRepository repository =
                mock(ScenarioRehearsalBatchRepository.class);
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                snapshot =
                new ScenarioRehearsalBatchRepository
                        .FinalizationSnapshot(
                        ScenarioRehearsalBatchRepository
                                .FinalizationState.RETRY_WAIT,
                        job().jobId(),
                        "sha256:" + "b".repeat(64),
                        2,
                        NOW.plusSeconds(30),
                        "",
                        2,
                        Instant.EPOCH,
                        NOW.minusSeconds(10),
                        "RG.MIRROR.KMS.UNAVAILABLE",
                        "",
                        NOW.minusSeconds(20),
                        NOW,
                        null);
        when(repository.findFinalization(
                SCOPE, job().jobId()))
                .thenReturn(Optional.of(snapshot));
        List<MirrorOperationAuditEvent> events =
                new ArrayList<>();
        ScenarioRehearsalBatchService service =
                new ScenarioRehearsalBatchService(
                        mock(ScenarioRehearsalBatchCompiler.class),
                        repository,
                        policy(),
                        mapper,
                        mock(
                                ScenarioRehearsalBatchEvidenceRepository
                                        .class),
                        observations(events));

        assertThat(service.finalization(
                job().jobId(),
                identity("GOVERNANCE_EVIDENCE_INGESTION")))
                .contains(
                        ScenarioRehearsalBatchFinalizationStatus.from(
                                snapshot));

        verify(repository).findFinalization(
                SCOPE, job().jobId());
        assertThat(events)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.operation()).isEqualTo(
                            MirrorOperationAuditEvent.Operation
                                    .SCENARIO_REHEARSAL_BATCH_READ);
                    assertThat(event.outcome()).isEqualTo(
                            MirrorOperationAuditEvent.Outcome
                                    .SUCCEEDED);
                    assertThat(event.runId())
                            .isEqualTo(job().jobId());
                });
    }

    @Test
    void workerCompletesOnlyVerifiedEvidenceAndWorkbookClosure() {
        ScenarioRehearsalBatchRepository repository =
                mock(ScenarioRehearsalBatchRepository.class);
        ScenarioRehearsalRuntimeService runtime =
                mock(ScenarioRehearsalRuntimeService.class);
        ScenarioRehearsalEvidenceIntegrityService integrity =
                mock(ScenarioRehearsalEvidenceIntegrityService.class);
        ScenarioRehearsalBatchRepository.Claim claim = claim();
        ScenarioRehearsalEvidenceBundle bundle =
                mock(ScenarioRehearsalEvidenceBundle.class);
        ScenarioRehearsalResult result =
                mock(ScenarioRehearsalResult.class);
        ScenarioRehearsalEvidenceAttestation attestation =
                mock(ScenarioRehearsalEvidenceAttestation.class);
        ScenarioRehearsalEvidenceIntegrityService.VerifiedBundle
                verified = mock(
                ScenarioRehearsalEvidenceIntegrityService
                        .VerifiedBundle.class);
        ScenarioRehearsalWorkbookSeed workbook =
                mock(ScenarioRehearsalWorkbookSeed.class);
        String runId = ScenarioRehearsalRunIdentity.derive(
                mapper,
                SCOPE,
                claim.item().childRequestId());
        when(repository.claimNext(
                "sg", "test", "worker-a", policy()))
                .thenReturn(claim);
        when(runtime.execute(any(), any(), any()))
                .thenReturn(bundle);
        when(integrity.requireVerified(bundle))
                .thenReturn(verified);
        when(verified.bundle()).thenReturn(bundle);
        when(bundle.result()).thenReturn(result);
        when(bundle.attestation()).thenReturn(attestation);
        when(bundle.bundleFingerprint())
                .thenReturn("sha256:" + "e".repeat(64));
        when(result.scope()).thenReturn(SCOPE);
        when(result.requestId())
                .thenReturn(claim.item().childRequestId());
        when(result.compiledPlanRef())
                .thenReturn(claim.item().compiledPlanRef());
        when(result.outcome()).thenReturn(
                ScenarioCaseRehearsalResult.Outcome.PASS);
        when(attestation.runId()).thenReturn(runId);
        when(runtime.workbookSeed(
                org.mockito.ArgumentMatchers.eq(runId),
                any(IntegrationRequestContext.class)))
                .thenReturn(workbook);
        when(workbook.scope()).thenReturn(SCOPE);
        when(workbook.runId()).thenReturn(runId);
        when(workbook.requestId())
                .thenReturn(claim.item().childRequestId());
        when(workbook.compiledPlanRef())
                .thenReturn(claim.item().compiledPlanRef());
        when(workbook.evidenceBundleFingerprint())
                .thenReturn("sha256:" + "e".repeat(64));
        when(workbook.outcome()).thenReturn(
                ScenarioCaseRehearsalResult.Outcome.PASS);
        when(workbook.seedFingerprint())
                .thenReturn("sha256:" + "d".repeat(64));
        when(repository.completeItem(
                org.mockito.ArgumentMatchers.eq(claim.lease()),
                any(ScenarioRehearsalBatchRepository
                        .ItemCompletion.class),
                org.mockito.ArgumentMatchers.eq(policy())))
                .thenReturn(job(
                        ScenarioRehearsalBatchJob.Status
                                .SUCCEEDED));
        ScenarioRehearsalBatchWorker worker =
                new ScenarioRehearsalBatchWorker(
                        repository,
                        runtime,
                        integrity,
                        policy(),
                        mapper);

        ScenarioRehearsalBatchWorker.Turn turn =
                worker.runOnce("sg", "test", "worker-a");

        assertThat(turn.disposition()).isEqualTo(
                ScenarioRehearsalBatchWorker.Disposition
                        .ITEM_COMPLETED);
        verify(workbook).verify(mapper);
        ArgumentCaptor<ScenarioRehearsalBatchRepository.ItemCompletion>
                completion = ArgumentCaptor.forClass(
                ScenarioRehearsalBatchRepository
                        .ItemCompletion.class);
        verify(repository).completeItem(
                org.mockito.ArgumentMatchers.eq(claim.lease()),
                completion.capture(),
                org.mockito.ArgumentMatchers.eq(policy()));
        assertThat(completion.getValue())
                .satisfies(value -> {
                    assertThat(value.runId()).isEqualTo(runId);
                    assertThat(value.outcome()).isEqualTo(
                            ScenarioCaseRehearsalResult
                                    .Outcome.PASS);
                    assertThat(value.workbookSeedFingerprint())
                            .isEqualTo(
                                    "sha256:" + "d".repeat(64));
                });
    }

    @Test
    void workerSeparatesRetryableOutageFromTerminalGovernanceFailure() {
        ScenarioRehearsalBatchRepository repository =
                mock(ScenarioRehearsalBatchRepository.class);
        ScenarioRehearsalRuntimeService runtime =
                mock(ScenarioRehearsalRuntimeService.class);
        ScenarioRehearsalBatchRepository.Claim first = claim();
        ScenarioRehearsalBatchRepository.Claim second = claim();
        when(repository.claimNext(
                "sg", "test", "worker-a", policy()))
                .thenReturn(first);
        when(repository.claimNext(
                "sg", "test", "worker-b", policy()))
                .thenReturn(second);
        when(repository.retryItem(
                first.lease(),
                "RG.MIRROR.REHEARSAL.RUNTIME_UNAVAILABLE",
                policy()))
                .thenReturn(job());
        when(repository.failItem(
                second.lease(),
                "RG.MIRROR.REHEARSAL.PLAN_REVOKED",
                policy()))
                .thenReturn(job(
                        ScenarioRehearsalBatchJob.Status.PARTIAL));
        when(runtime.execute(any(), any(), any()))
                .thenThrow(new IntegrationProblemException(
                        IntegrationProblem.serviceUnavailable(
                                "RG.MIRROR.REHEARSAL.RUNTIME_UNAVAILABLE",
                                "runtime unavailable",
                                "corr",
                                Map.of())))
                .thenThrow(new IntegrationProblemException(
                        IntegrationProblem.conflict(
                                "RG.MIRROR.REHEARSAL.PLAN_REVOKED",
                                "plan revoked",
                                "corr",
                                Map.of())));
        ScenarioRehearsalBatchWorker worker =
                new ScenarioRehearsalBatchWorker(
                        repository,
                        runtime,
                        mock(ScenarioRehearsalEvidenceIntegrityService.class),
                        policy(),
                        mapper);

        assertThat(worker.runOnce("sg", "test", "worker-a")
                .disposition()).isEqualTo(
                ScenarioRehearsalBatchWorker.Disposition
                        .ITEM_RETRY_SCHEDULED);
        assertThat(worker.runOnce("sg", "test", "worker-b")
                .disposition()).isEqualTo(
                ScenarioRehearsalBatchWorker.Disposition
                        .ITEM_FAILED);
    }

    @Test
    void workerMapsADurableCancellationCheckpointWithoutPublishingEvidence() {
        ScenarioRehearsalBatchRepository repository =
                mock(ScenarioRehearsalBatchRepository.class);
        ScenarioRehearsalRuntimeService runtime =
                mock(ScenarioRehearsalRuntimeService.class);
        ScenarioRehearsalBatchRepository.Claim claim = claim();
        ScenarioRehearsalBatchJob cancelled =
                job(ScenarioRehearsalBatchJob.Status.CANCELLED);
        when(repository.claimNext(
                "sg", "test", "worker-a", policy()))
                .thenReturn(claim);
        when(repository.checkpointExecution(
                claim.lease(), 0, policy()))
                .thenReturn(
                        new ScenarioRehearsalBatchRepository
                                .ExecutionControlCheckpoint(
                                ScenarioRehearsalBatchRepository
                                        .ExecutionControlOutcome
                                        .CONTINUE,
                                NOW,
                                1,
                                0,
                                claim.job()));
        when(repository.checkpointExecution(
                claim.lease(), 1, policy()))
                .thenReturn(
                        new ScenarioRehearsalBatchRepository
                                .ExecutionControlCheckpoint(
                                ScenarioRehearsalBatchRepository
                                        .ExecutionControlOutcome
                                        .CANCELLED,
                                NOW.plusMillis(1),
                                2,
                                1,
                                cancelled));
        when(runtime.execute(any(), any(), any()))
                .thenAnswer(invocation -> {
                    ScenarioRehearsalExecutionControl control =
                            invocation.getArgument(2);
                    control.checkpoint(
                            new ScenarioRehearsalExecutionControl
                                    .Checkpoint(
                                    ScenarioRehearsalExecutionControl
                                            .Phase.BEFORE_CASE,
                                    0,
                                    1));
                    control.checkpoint(
                            new ScenarioRehearsalExecutionControl
                                    .Checkpoint(
                                    ScenarioRehearsalExecutionControl
                                            .Phase.AFTER_CASE,
                                    1,
                                    1));
                    throw new AssertionError(
                            "Cancellation checkpoint must stop execution");
                });
        ScenarioRehearsalBatchWorker worker =
                new ScenarioRehearsalBatchWorker(
                        repository,
                        runtime,
                        mock(ScenarioRehearsalEvidenceIntegrityService.class),
                        policy(),
                        mapper);

        ScenarioRehearsalBatchWorker.Turn turn =
                worker.runOnce("sg", "test", "worker-a");

        assertThat(turn.disposition()).isEqualTo(
                ScenarioRehearsalBatchWorker.Disposition
                        .ITEM_CANCELLED);
        assertThat(turn.job()).isEqualTo(cancelled);
        assertThat(turn.failureCode()).isEqualTo(
                "RG.MIRROR.REHEARSAL_BATCH.CANCELLED");
        verify(repository, org.mockito.Mockito.never())
                .completeItem(any(), any(), any());
        verify(repository, org.mockito.Mockito.never())
                .retryItem(any(), any(), any());
        verify(repository, org.mockito.Mockito.never())
                .failItem(any(), any(), any());
    }

    private ScenarioRehearsalBatchRepository.Claim claim() {
        ScenarioRehearsalBatchJob job = job(
                ScenarioRehearsalBatchJob.Status.RUNNING);
        ScenarioRehearsalBatchItemPage.Item item =
                new ScenarioRehearsalBatchItemPage.Item(
                        0,
                        planRef(),
                        "batch-001:plan:000",
                        ScenarioRehearsalBatchItemPage.Status.RUNNING,
                        1,
                        "", "", "", "",
                        NOW,
                        null);
        ScenarioRehearsalBatchPrincipal principal =
                new ScenarioRehearsalBatchPrincipal(
                        SCOPE,
                        "USER",
                        "owner-a",
                        "",
                        Set.of("support-owner"),
                        "RESTRICTED",
                        "");
        ScenarioRehearsalBatchRepository.Lease lease =
                new ScenarioRehearsalBatchRepository.Lease(
                        SCOPE,
                        job.jobId(),
                        "worker-a",
                        1,
                        0,
                        NOW.plus(Duration.ofMinutes(10)));
        return new ScenarioRehearsalBatchRepository.Claim(
                ScenarioRehearsalBatchRepository
                        .ClaimOutcome.ACQUIRED,
                NOW,
                job,
                item,
                principal,
                lease);
    }

    private ScenarioRehearsalBatchJob job() {
        return job(ScenarioRehearsalBatchJob.Status.QUEUED);
    }

    private ScenarioRehearsalBatchJob job(
            ScenarioRehearsalBatchJob.Status status) {
        boolean terminal = status.terminal();
        ScenarioRehearsalBatchJob.Summary summary =
                terminal
                        ? new ScenarioRehearsalBatchJob.Summary(
                        1, 1, status
                        == ScenarioRehearsalBatchJob.Status.SUCCEEDED
                        ? 1 : 0,
                        status
                                == ScenarioRehearsalBatchJob.Status
                                .PARTIAL ? 1 : 0,
                        0,
                        status
                                == ScenarioRehearsalBatchJob.Status
                                .CANCELLED ? 1 : 0)
                        : new ScenarioRehearsalBatchJob.Summary(
                        1, 0, 0, 0, 0, 0);
        return ScenarioRehearsalBatchIntegrity.seal(
                mapper,
                new ScenarioRehearsalBatchJob(
                        "",
                        manifest().batchId(),
                        "batch-001",
                        "sha256:" + "a".repeat(64),
                        manifest().manifestFingerprint(),
                        SCOPE,
                        status,
                        policy().failureMode(),
                        policy().priority(),
                        policy().maximumItemAttempts(),
                        summary,
                        NOW.plus(Duration.ofHours(1)),
                        status
                                == ScenarioRehearsalBatchJob.Status
                                .PARTIAL
                                ? "RG.MIRROR.REHEARSAL.PLAN_REVOKED"
                                : status
                                == ScenarioRehearsalBatchJob.Status
                                .CANCELLED
                                ? "RG.MIRROR.REHEARSAL_BATCH.CANCELLED"
                                : "",
                        status
                                == ScenarioRehearsalBatchJob.Status
                                .CANCELLED
                                ? "cancel-running-001" : "",
                        status
                                == ScenarioRehearsalBatchJob.Status
                                .CANCELLED
                                ? "OWNER_REQUEST" : "",
                        NOW,
                        NOW,
                        terminal ? NOW : null,
                        ""));
    }

    private ScenarioRehearsalBatchRequest request() {
        return new ScenarioRehearsalBatchRequest(
                "",
                "batch-001",
                List.of(new ScenarioRehearsalBatchRequest.Entry(
                        "entry-0",
                        planRef())));
    }

    private ScenarioRehearsalBatchManifest manifest() {
        String child = "batch-001:plan:000";
        return ScenarioRehearsalBatchManifestIntegrity.seal(
                mapper,
                new ScenarioRehearsalBatchManifest(
                        "",
                        ScenarioRehearsalBatchIdentity.derive(
                                mapper, SCOPE, "batch-001"),
                        "",
                        SCOPE,
                        "batch-001",
                        List.of(
                                new ScenarioRehearsalBatchManifest.Entry(
                                        0,
                                        "entry-0",
                                        planRef(),
                                        child,
                                        ScenarioRehearsalRunIdentity
                                                .derive(
                                                        mapper,
                                                        SCOPE,
                                                        child),
                                        1,
                                        Duration.ofMinutes(5))),
                        1));
    }

    private MirrorArtifactRef planRef() {
        return new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                "refund-plan",
                1,
                "sha256:" + "f".repeat(64));
    }

    private ScenarioRehearsalBatchPolicy policy() {
        return ScenarioRehearsalBatchPolicy.defaults();
    }

    private IntegrationRequestContext identity() {
        return identity("MIRROR_REHEARSAL");
    }

    private IntegrationRequestContext identity(String purpose) {
        return new IntegrationRequestContext(
                SCOPE.tenantId(),
                SCOPE.organizationId(),
                SCOPE.projectId(),
                SCOPE.environmentId(),
                SCOPE.region(),
                "USER",
                "owner-a",
                "",
                purpose,
                "corr-batch",
                Set.of("support-owner"),
                "RESTRICTED",
                "");
    }

    private MirrorOperationObservability observations(
            List<MirrorOperationAuditEvent> events) {
        MirrorOperationAuditRepository audit =
                new MirrorOperationAuditRepository() {
                    @Override
                    public MirrorOperationAuditEvent append(
                            MirrorOperationAuditEvent event) {
                        MirrorOperationAuditEvent persisted =
                                event.persisted(
                                        events.size() + 1L,
                                        NOW);
                        events.add(persisted);
                        return persisted;
                    }

                    @Override
                    public List<MirrorOperationAuditEvent> recent(
                            CapabilitySnapshot.Scope scope,
                            int limit) {
                        return List.copyOf(events);
                    }
                };
        return new MirrorOperationObservability(
                audit,
                MirrorOperationTelemetry.noop(),
                () -> 0L);
    }
}
