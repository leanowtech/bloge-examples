package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatabaseScenarioRehearsalBatchRepositoryTest {
    private static final Instant NOW =
            Instant.parse("2026-07-24T08:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE =
            scope("tenant-a", "org-a");
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> databaseTime =
            new AtomicReference<>(NOW);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DatabaseScenarioRehearsalBatchRepository repository;
    private ScenarioRehearsalBatchEvidencePublisher
            evidencePublisher;
    private ScenarioRehearsalBatchLifecycleAuditRepository
            lifecycleAudit;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        evidencePublisher = mock(
                ScenarioRehearsalBatchEvidencePublisher.class);
        ScenarioRehearsalBatchEvidenceBundle evidence =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        when(evidence.bundleFingerprint())
                .thenReturn("sha256:" + "b".repeat(64));
        when(evidencePublisher.publish(
                any(), any(), any(), any(), any()))
                .thenReturn(evidence);
        lifecycleAudit = mock(
                ScenarioRehearsalBatchLifecycleAuditRepository.class);
        repository = new DatabaseScenarioRehearsalBatchRepository(
                jdbc,
                mapper,
                new DataSourceTransactionManager(database),
                evidencePublisher,
                lifecycleAudit,
                ScenarioRehearsalBatchFinalizationPolicy.defaults(),
                databaseTime::get);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void persistsExactManifestAndIdempotentlyRecoversAfterRestart() {
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(SCOPE, "batch-001", "refund");

        ScenarioRehearsalBatchRepository.SubmissionResult created =
                repository.submit(submission, policy());
        ScenarioRehearsalBatchRepository.SubmissionResult replay =
                repository.submit(submission, policy());
        DatabaseScenarioRehearsalBatchRepository restarted =
                new DatabaseScenarioRehearsalBatchRepository(
                        jdbc,
                        mapper,
                        new DataSourceTransactionManager(database),
                        evidencePublisher,
                        lifecycleAudit,
                        ScenarioRehearsalBatchFinalizationPolicy.defaults(),
                        databaseTime::get);
        restarted.init();

        assertThat(created.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.job()).isEqualTo(created.job());
        assertThat(restarted.find(
                SCOPE, created.job().jobId(), policy()))
                .contains(created.job());
        assertThat(restarted.page(
                SCOPE, created.job().jobId(),
                0, 10, policy()).items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(
                            ScenarioRehearsalBatchItemPage
                                    .Status.PENDING);
                    assertThat(item.childRequestId())
                            .isEqualTo(
                                    "batch-001:plan:000");
                });
        verify(lifecycleAudit).append(argThat(
                event -> event.transition()
                        == ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.ADMITTED
                        && event.jobId().equals(
                        created.job().jobId())));
        assertThat(columns("SCENARIO_REHEARSAL_BATCH_JOBS"))
                .noneMatch(
                        DatabaseScenarioRehearsalBatchRepositoryTest
                                ::businessPayloadColumn);
        assertThat(columns("SCENARIO_REHEARSAL_BATCH_ITEMS"))
                .noneMatch(
                        DatabaseScenarioRehearsalBatchRepositoryTest
                                ::businessPayloadColumn);
        assertThat(columns(
                "SCENARIO_REHEARSAL_BATCH_FINALIZATIONS"))
                .noneMatch(
                        DatabaseScenarioRehearsalBatchRepositoryTest
                                ::businessPayloadColumn);
    }

    @Test
    void listsExactScopeWithStableNewestFirstKeysetPagination() {
        ScenarioRehearsalBatchJob first =
                repository.submit(
                        submission(SCOPE, "batch-list-1", "refund"),
                        policy()).job();
        databaseTime.set(NOW.plusSeconds(1));
        ScenarioRehearsalBatchJob second =
                repository.submit(
                        submission(SCOPE, "batch-list-2", "escalation"),
                        policy()).job();
        databaseTime.set(NOW.plusSeconds(2));
        ScenarioRehearsalBatchJob third =
                repository.submit(
                        submission(SCOPE, "batch-list-3", "retention"),
                        policy()).job();
        repository.submit(
                submission(
                        scope("tenant-b", "org-a"),
                        "batch-list-hidden",
                        "hidden"),
                policy());

        ScenarioRehearsalBatchJobPage newest =
                repository.list(SCOPE, null, 2, policy());
        ScenarioRehearsalBatchJobPage older =
                repository.list(
                        SCOPE,
                        newest.nextCursor(),
                        2,
                        policy());

        assertThat(newest.jobs()).containsExactly(third, second);
        assertThat(newest.nextCursor()).isEqualTo(
                ScenarioRehearsalBatchJobPage.Cursor.after(second));
        assertThat(older.jobs()).containsExactly(first);
        assertThat(older.nextCursor()).isNull();
        assertThat(newest.jobs())
                .allSatisfy(job ->
                        assertThat(job.scope()).isEqualTo(SCOPE));
    }

    @Test
    void rejectsRequestDriftButIsolatesSameRequestAcrossFullScope() {
        ScenarioRehearsalBatchRepository.Submission first =
                submission(SCOPE, "batch-001", "refund");
        repository.submit(first, policy());

        assertThatThrownBy(() -> repository.submit(
                submission(SCOPE, "batch-001", "escalation"),
                policy()))
                .isInstanceOfSatisfying(
                        ScenarioRehearsalBatchConflictException.class,
                        conflict -> assertThat(conflict.reason())
                                .isEqualTo(
                                        ScenarioRehearsalBatchConflictException
                                                .Reason
                                                .IDEMPOTENCY_CONFLICT));

        CapabilitySnapshot.Scope other =
                scope("tenant-a", "org-b");
        ScenarioRehearsalBatchJob isolated =
                repository.submit(
                        submission(
                                other, "batch-001", "refund"),
                        policy()).job();
        assertThat(isolated.jobId()).isNotEqualTo(
                first.manifest().batchId());
        assertThat(repository.find(
                SCOPE, isolated.jobId(), policy())).isEmpty();
        assertThat(repository.find(
                other, isolated.jobId(), policy()))
                .contains(isolated);
    }

    @Test
    void rotatesTenantsAndEnforcesLiveConcurrencyCapacity() {
        ScenarioRehearsalBatchPolicy policy =
                policy(2, 1);
        repository.submit(
                        submission(
                                scope("tenant-a", "org-a"),
                                "batch-a",
                                "refund"),
                        policy);
        ScenarioRehearsalBatchJob tenantB =
                repository.submit(
                        submission(
                                scope("tenant-b", "org-b"),
                                "batch-b",
                                "escalation"),
                        policy).job();
        repository.submit(
                submission(
                        scope("tenant-a", "org-a"),
                        "batch-a-2",
                        "retention"),
                policy);

        ScenarioRehearsalBatchRepository.Claim first =
                repository.claimNext(
                        "sg", "test", "worker-a", policy);
        ScenarioRehearsalBatchRepository.Claim second =
                repository.claimNext(
                        "sg", "test", "worker-b", policy);
        ScenarioRehearsalBatchRepository.Claim saturated =
                repository.claimNext(
                        "sg", "test", "worker-c", policy);

        assertThat(first.job().scope().tenantId())
                .isEqualTo("tenant-a");
        assertThat(second.job().jobId()).isEqualTo(
                tenantB.jobId());
        assertThat(saturated.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository.ClaimOutcome
                                .NO_WORK);
    }

    @Test
    void isolatesCapacityPolicyAndClaimsAcrossRegionsWithTheSameEnvironment() {
        ScenarioRehearsalBatchPolicy sgPolicy =
                policy(1, 1);
        ScenarioRehearsalBatchPolicy usPolicy =
                new ScenarioRehearsalBatchPolicy(
                        sgPolicy.generation(),
                        sgPolicy.failureMode(),
                        ScenarioRehearsalBatchPolicy.Priority.HIGH,
                        sgPolicy.maximumItemAttempts(),
                        sgPolicy.maximumQueued(),
                        sgPolicy.maximumQueuedPerTenant(),
                        sgPolicy.maximumRunning(),
                        sgPolicy.maximumRunningPerTenant(),
                        sgPolicy.maximumPlanTimeout(),
                        sgPolicy.maximumDeadlineHorizon(),
                        sgPolicy.leaseReserve(),
                        sgPolicy.retryBackoff(),
                        sgPolicy.priorityAgingInterval(),
                        sgPolicy.terminalRetention());
        CapabilitySnapshot.Scope usScope =
                scope("tenant-a", "org-a", "us");
        repository.submit(
                submission(SCOPE, "batch-sg", "refund"),
                sgPolicy);
        repository.submit(
                submission(usScope, "batch-us", "refund"),
                usPolicy);

        ScenarioRehearsalBatchRepository.Claim sg =
                repository.claimNext(
                        "sg", "test", "worker-sg", sgPolicy);
        ScenarioRehearsalBatchRepository.Claim us =
                repository.claimNext(
                        "us", "test", "worker-us", usPolicy);

        assertThat(sg.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository.ClaimOutcome.ACQUIRED);
        assertThat(sg.job().scope().region()).isEqualTo("sg");
        assertThat(us.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository.ClaimOutcome.ACQUIRED);
        assertThat(us.job().scope().region()).isEqualTo("us");
        assertThat(repository.claimNext(
                "eu", "test", "worker-eu", sgPolicy).outcome())
                .isEqualTo(
                        ScenarioRehearsalBatchRepository.ClaimOutcome.NO_WORK);
    }

    @Test
    void bindsCompletionToManifestAndFencesStaleLease() {
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(SCOPE, "batch-001", "refund");
        repository.submit(submission, policy());
        ScenarioRehearsalBatchRepository.Claim claim =
                repository.claimNext(
                        "sg", "test", "worker-a", policy());

        assertThatThrownBy(() -> repository.completeItem(
                claim.lease(),
                completion(
                        "scenario-run-" + "f".repeat(64)),
                policy()))
                .isInstanceOfSatisfying(
                        ScenarioRehearsalBatchConflictException.class,
                        conflict -> assertThat(conflict.reason())
                                .isEqualTo(
                                        ScenarioRehearsalBatchConflictException
                                                .Reason
                                                .EVIDENCE_MISMATCH));

        String runId = submission.manifest()
                .entries().getFirst().aggregateRunId();
        ScenarioRehearsalBatchJob completed =
                repository.completeItem(
                        claim.lease(),
                        completion(runId),
                        policy());
        assertThat(completed.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status
                        .FINALIZING_EVIDENCE);
        assertThat(completed.summary().passedItems())
                .isEqualTo(1);
        ScenarioRehearsalBatchJob terminal =
                finalizeEvidence(completed);
        assertThat(terminal.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status.SUCCEEDED);
        verify(lifecycleAudit, atLeastOnce()).append(argThat(
                event -> event.transition()
                        == ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.CLAIMED
                        && event.jobId().equals(completed.jobId())));
        verify(lifecycleAudit, atLeastOnce()).append(argThat(
                event -> event.transition()
                        == ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.ITEM_TERMINALIZED
                        && event.evidenceBundleFingerprint().equals(
                        "sha256:" + "e".repeat(64))));
        verify(lifecycleAudit, atLeastOnce()).append(argThat(
                event -> event.transition()
                        == ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.TERMINALIZED
                        && event.evidenceBundleFingerprint().equals(
                        "sha256:" + "b".repeat(64))));
        assertThatThrownBy(() -> repository.retryItem(
                claim.lease(),
                "RG.MIRROR.REHEARSAL_BATCH.LATE",
                policy()))
                .isInstanceOfSatisfying(
                        ScenarioRehearsalBatchConflictException.class,
                        conflict -> assertThat(conflict.reason())
                                .isEqualTo(
                                        ScenarioRehearsalBatchConflictException
                                .Reason.LEASE_LOST));
    }

    @Test
    void retainsEarlierFailureWhenTheLastCollectedItemPasses() {
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(
                        SCOPE,
                        "batch-partial",
                        List.of("refund", "escalation"),
                        Duration.ofSeconds(5));
        repository.submit(submission, policy());
        ScenarioRehearsalBatchRepository.Claim first =
                repository.claimNext(
                        "sg", "test", "worker-a", policy());
        ScenarioRehearsalBatchJob queued =
                repository.completeItem(
                        first.lease(),
                        completion(
                                submission.manifest().entries()
                                        .getFirst().aggregateRunId(),
                                ScenarioCaseRehearsalResult.Outcome
                                        .FAIL),
                        policy());
        assertThat(queued.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status.QUEUED);

        ScenarioRehearsalBatchRepository.Claim second =
                repository.claimNext(
                        "sg", "test", "worker-b", policy());
        ScenarioRehearsalBatchJob terminal =
                repository.completeItem(
                        second.lease(),
                        completion(
                                submission.manifest().entries()
                                        .get(1).aggregateRunId()),
                        policy());

        assertThat(terminal.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status
                        .FINALIZING_EVIDENCE);
        ScenarioRehearsalBatchJob finalized =
                finalizeEvidence(terminal);
        assertThat(finalized.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status.PARTIAL);
        assertThat(finalized.failureCode()).isEqualTo(
                "RG.MIRROR.REHEARSAL_BATCH.ITEM_FAILED");
    }

    @Test
    void rollsBackTerminalProjectionWhenEvidencePersistenceFails() {
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(
                        SCOPE,
                        "batch-evidence-outage",
                        "refund");
        ScenarioRehearsalBatchJob queued =
                repository.submit(submission, policy()).job();
        ScenarioRehearsalBatchRepository.Claim claim =
                repository.claimNext(
                        "sg", "test", "worker-a", policy());
        ScenarioRehearsalBatchJob finalizing =
                repository.completeItem(
                claim.lease(),
                completion(
                        submission.manifest().entries()
                                .getFirst().aggregateRunId()),
                policy());
        ScenarioRehearsalBatchRepository.FinalizationClaim
                finalization = claimFinalization();
        ScenarioRehearsalBatchEvidencePublisher.PreparedFinalization
                prepared = prepared(finalization);
        doThrow(new IllegalStateException(
                "evidence persistence unavailable"))
                .when(evidencePublisher)
                .persist(prepared);

        assertThatThrownBy(() ->
                repository.completeFinalization(
                        finalization, prepared))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.find(
                SCOPE, queued.jobId(), policy()))
                .get()
                .extracting(ScenarioRehearsalBatchJob::status)
                .isEqualTo(
                        ScenarioRehearsalBatchJob.Status
                                .FINALIZING_EVIDENCE);
        assertThat(repository.page(
                SCOPE, queued.jobId(), 0, 10, policy()).items())
                .singleElement()
                .extracting(
                        ScenarioRehearsalBatchItemPage.Item::status)
                .isEqualTo(
                        ScenarioRehearsalBatchItemPage.Status.PASSED);
        assertThat(finalizing.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status
                        .FINALIZING_EVIDENCE);
    }

    @Test
    void rollsBackAdmissionWhenMandatoryOperationAuditFails() {
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(
                        SCOPE,
                        "batch-operation-audit-outage",
                        "refund");
        MirrorOperationObservability.Observation operation =
                mock(MirrorOperationObservability.Observation.class);
        doThrow(new IllegalStateException("audit unavailable"))
                .when(operation)
                .succeeded(anyString());

        assertThatThrownBy(() -> repository.submit(
                submission,
                policy(),
                operation))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_rehearsal_batch_jobs",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_rehearsal_batch_items",
                Long.class)).isZero();
    }

    @Test
    void rollsBackFinalizationIntentWhenQueueAuditFails() {
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(
                        SCOPE,
                        "batch-lifecycle-audit-outage",
                        "refund");
        ScenarioRehearsalBatchJob queued =
                repository.submit(submission, policy()).job();
        ScenarioRehearsalBatchRepository.Claim claim =
                repository.claimNext(
                        "sg", "test", "worker-a", policy());
        doThrow(new IllegalStateException(
                "lifecycle audit unavailable"))
                .when(lifecycleAudit)
                .append(argThat(event ->
                        event.transition()
                                == ScenarioRehearsalBatchLifecycleAuditEvent
                                .Transition.FINALIZATION_QUEUED));

        assertThatThrownBy(() -> repository.completeItem(
                claim.lease(),
                completion(
                        submission.manifest().entries()
                                .getFirst().aggregateRunId()),
                policy()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.find(
                SCOPE, queued.jobId(), policy()))
                .get()
                .extracting(ScenarioRehearsalBatchJob::status)
                .isEqualTo(
                        ScenarioRehearsalBatchJob.Status.RUNNING);
        assertThat(repository.page(
                SCOPE, queued.jobId(), 0, 10, policy()).items())
                .singleElement()
                .extracting(
                        ScenarioRehearsalBatchItemPage.Item::status)
                .isEqualTo(
                        ScenarioRehearsalBatchItemPage.Status.RUNNING);
    }

    @Test
    void takesOverExpiredFinalizationWithStableSigningMaterialAndExactReplay() {
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(
                        SCOPE,
                        "batch-finalization-takeover",
                        "refund");
        repository.submit(submission, policy());
        ScenarioRehearsalBatchRepository.Claim item =
                repository.claimNext(
                        "sg", "test", "worker-a", policy());
        ScenarioRehearsalBatchJob finalizing =
                repository.completeItem(
                        item.lease(),
                        completion(
                                submission.manifest().entries()
                                        .getFirst()
                                        .aggregateRunId()),
                        policy());

        ScenarioRehearsalBatchRepository.FinalizationClaim first =
                claimFinalization();
        databaseTime.set(
                NOW.plus(
                        ScenarioRehearsalBatchFinalizationPolicy
                                .defaults().leaseDuration())
                        .plusMillis(1));
        ScenarioRehearsalBatchRepository.FinalizationAcquisition
                takeover = repository.claimFinalization(
                "sg",
                "test",
                "finalizer-b",
                ScenarioRehearsalBatchFinalizationPolicy.defaults());

        assertThat(takeover.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .FinalizationClaimOutcome.ACQUIRED);
        assertThat(takeover.claim().leaseEpoch())
                .isEqualTo(first.leaseEpoch() + 1);
        assertThat(takeover.claim().attemptCount()).isEqualTo(2);
        assertThat(takeover.claim().signingStartedAt())
                .isEqualTo(first.signingStartedAt());
        assertThat(takeover.claim().intent().signingRequestId())
                .isEqualTo(first.intent().signingRequestId());

        ScenarioRehearsalBatchEvidencePublisher.PreparedFinalization
                prepared = prepared(takeover.claim());
        assertThatThrownBy(() ->
                repository.completeFinalization(
                        first, prepared))
                .isInstanceOf(IllegalStateException.class);
        ScenarioRehearsalBatchJob terminal =
                repository.completeFinalization(
                        takeover.claim(), prepared);
        ScenarioRehearsalBatchJob replay =
                repository.completeFinalization(
                        takeover.claim(), prepared);

        assertThat(finalizing.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status
                        .FINALIZING_EVIDENCE);
        assertThat(terminal.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status.SUCCEEDED);
        assertThat(replay).isEqualTo(terminal);
        verify(evidencePublisher, times(1))
                .persist(prepared);
    }

    @Test
    void retriesTransientFinalizationAfterBackoffWithoutChangingSignatureTime() {
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(
                        SCOPE,
                        "batch-finalization-retry",
                        "refund");
        repository.submit(submission, policy());
        ScenarioRehearsalBatchRepository.Claim item =
                repository.claimNext(
                        "sg", "test", "worker-a", policy());
        repository.completeItem(
                item.lease(),
                completion(
                        submission.manifest().entries()
                                .getFirst().aggregateRunId()),
                policy());
        ScenarioRehearsalBatchRepository.FinalizationClaim first =
                claimFinalization();

        ScenarioRehearsalBatchRepository.FinalizationSnapshot retry =
                repository.releaseFinalization(
                        first,
                        ScenarioRehearsalBatchFinalizationException
                                .Reason.SIGNER_UNAVAILABLE,
                        ScenarioRehearsalBatchFinalizationPolicy
                                .defaults());
        ScenarioRehearsalBatchRepository.FinalizationAcquisition
                delayed = repository.claimFinalization(
                "sg",
                "test",
                "finalizer-b",
                ScenarioRehearsalBatchFinalizationPolicy.defaults());
        databaseTime.set(retry.nextEligibleAt());
        ScenarioRehearsalBatchRepository.FinalizationAcquisition
                second = repository.claimFinalization(
                "sg",
                "test",
                "finalizer-b",
                ScenarioRehearsalBatchFinalizationPolicy.defaults());

        assertThat(retry.state()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .FinalizationState.RETRY_WAIT);
        assertThat(delayed.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .FinalizationClaimOutcome.RETRY_DELAYED);
        assertThat(second.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .FinalizationClaimOutcome.ACQUIRED);
        assertThat(second.claim().attemptCount()).isEqualTo(2);
        assertThat(second.claim().signingStartedAt())
                .isEqualTo(first.signingStartedAt());
    }

    @Test
    void quarantinedFinalizationDoesNotPoisonTheRegionalQueue() {
        ScenarioRehearsalBatchJob first =
                makeFinalizing(
                        "batch-finalization-invalid",
                        "refund",
                        "worker-a");
        ScenarioRehearsalBatchRepository.FinalizationClaim invalid =
                claimFinalization();
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                quarantined = repository.releaseFinalization(
                invalid,
                ScenarioRehearsalBatchFinalizationException
                        .Reason.MATERIAL_INVALID,
                ScenarioRehearsalBatchFinalizationPolicy.defaults());
        ScenarioRehearsalBatchJob second =
                makeFinalizing(
                        "batch-finalization-next",
                        "escalation",
                        "worker-b");

        ScenarioRehearsalBatchRepository.FinalizationAcquisition
                acquired = repository.claimFinalization(
                "sg",
                "test",
                "finalizer-b",
                ScenarioRehearsalBatchFinalizationPolicy.defaults());

        assertThat(quarantined.state()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .FinalizationState.QUARANTINED);
        assertThat(acquired.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .FinalizationClaimOutcome.ACQUIRED);
        assertThat(acquired.claim().intent().terminalJob().jobId())
                .isEqualTo(second.jobId())
                .isNotEqualTo(first.jobId());
    }

    @Test
    void remediatesExactQuarantineWithNewIntentAndRenewedRetentionFloor() {
        ScenarioRehearsalBatchJob finalizing =
                makeFinalizing(
                        "batch-finalization-remediate",
                        "refund",
                        "worker-a");
        ScenarioRehearsalBatchRepository.FinalizationClaim
                failed = claimFinalization();
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                quarantined = repository.releaseFinalization(
                failed,
                ScenarioRehearsalBatchFinalizationException
                        .Reason.MATERIAL_INVALID,
                ScenarioRehearsalBatchFinalizationPolicy
                        .defaults());
        databaseTime.set(NOW.plus(Duration.ofDays(2)));
        ScenarioRehearsalBatchFinalizationRemediationRequest
                request = remediationRequest(
                "remediation-a",
                quarantined);
        MirrorOperationObservability.Observation observation =
                mock(MirrorOperationObservability
                        .Observation.class);

        ScenarioRehearsalBatchRepository
                .FinalizationRemediationResult result =
                repository.remediateFinalization(
                        remediation(
                                finalizing.jobId(),
                                request),
                        policy(),
                        observation);
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                pending = repository.findFinalization(
                SCOPE, finalizing.jobId()).orElseThrow();
        ScenarioRehearsalBatchRepository
                .FinalizationAcquisition acquired =
                repository.claimFinalization(
                        "sg",
                        "test",
                        "finalizer-remediated",
                        ScenarioRehearsalBatchFinalizationPolicy
                                .defaults());

        assertThat(result.idempotentReplay()).isFalse();
        assertThat(result.receipt()
                .previousIntentFingerprint())
                .isEqualTo(failed.intent()
                        .intentFingerprint());
        assertThat(result.receipt()
                .currentIntentFingerprint())
                .isNotEqualTo(failed.intent()
                        .intentFingerprint())
                .isEqualTo(pending.intentFingerprint());
        assertThat(result.receipt()
                .effectiveRetainUntil())
                .isEqualTo(databaseTime.get()
                        .plus(policy()
                                .terminalRetention()));
        assertThat(pending.state()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .FinalizationState.PENDING);
        assertThat(pending.attemptCount()).isZero();
        assertThat(pending.signingStartedAt())
                .isEqualTo(Instant.EPOCH);
        assertThat(pending.leaseEpoch())
                .isEqualTo(quarantined.leaseEpoch() + 1);
        assertThat(acquired.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .FinalizationClaimOutcome.ACQUIRED);
        assertThat(acquired.claim()
                .signingStartedAt())
                .isEqualTo(databaseTime.get());
        assertThat(acquired.claim()
                .intent().signingRequestId())
                .isNotEqualTo(failed.intent()
                        .signingRequestId());
        assertThat(repository.find(
                SCOPE, finalizing.jobId(), policy()))
                .get()
                .extracting(
                        ScenarioRehearsalBatchJob::updatedAt)
                .isEqualTo(databaseTime.get());
        verify(lifecycleAudit).append(argThat(event ->
                event.transition()
                        == ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.FINALIZATION_REMEDIATED
                        && event.reasonCode().equals(
                        "KMS_POLICY_REPAIRED")));
        verify(observation).succeeded(
                finalizing.jobId());
    }

    @Test
    void exactlyReplaysRemediationReceiptAfterLaterClaimWithoutMutation() {
        ScenarioRehearsalBatchJob finalizing =
                makeFinalizing(
                        "batch-finalization-remediation-replay",
                        "refund",
                        "worker-a");
        ScenarioRehearsalBatchRepository.FinalizationClaim
                failed = claimFinalization();
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                quarantined = repository.releaseFinalization(
                failed,
                ScenarioRehearsalBatchFinalizationException
                        .Reason.MATERIAL_INVALID,
                ScenarioRehearsalBatchFinalizationPolicy
                        .defaults());
        ScenarioRehearsalBatchFinalizationRemediationRequest
                request = remediationRequest(
                "remediation-replay",
                quarantined);
        MirrorOperationObservability.Observation firstObservation =
                mock(MirrorOperationObservability
                        .Observation.class);
        MirrorOperationObservability.Observation replayObservation =
                mock(MirrorOperationObservability
                        .Observation.class);
        ScenarioRehearsalBatchRepository
                .FinalizationRemediationResult first =
                repository.remediateFinalization(
                        remediation(
                                finalizing.jobId(),
                                request),
                        policy(),
                        firstObservation);
        ScenarioRehearsalBatchRepository
                .FinalizationAcquisition claim =
                repository.claimFinalization(
                        "sg",
                        "test",
                        "finalizer-after-remediation",
                        ScenarioRehearsalBatchFinalizationPolicy
                                .defaults());

        ScenarioRehearsalBatchRepository
                .FinalizationRemediationResult replay =
                repository.remediateFinalization(
                        remediation(
                                finalizing.jobId(),
                                request),
                        policy(),
                        replayObservation);

        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.receipt()).isEqualTo(
                first.receipt());
        assertThat(repository.findFinalization(
                SCOPE, finalizing.jobId()))
                .get()
                .extracting(
                        ScenarioRehearsalBatchRepository
                                .FinalizationSnapshot::state)
                .isEqualTo(
                        ScenarioRehearsalBatchRepository
                                .FinalizationState.SIGNING);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM scenario_rehearsal_batch_finalization_remediations
                WHERE job_id = ?
                """,
                Long.class,
                finalizing.jobId())).isOne();
        verify(lifecycleAudit, times(1))
                .append(argThat(event ->
                        event.transition()
                                == ScenarioRehearsalBatchLifecycleAuditEvent
                                .Transition
                                .FINALIZATION_REMEDIATED));
        verify(replayObservation).succeeded(
                finalizing.jobId());
        assertThat(claim.claim()).isNotNull();
    }

    @Test
    void rejectsStaleOrReusedFinalizationRemediationFence() {
        ScenarioRehearsalBatchJob finalizing =
                makeFinalizing(
                        "batch-finalization-remediation-conflict",
                        "refund",
                        "worker-a");
        ScenarioRehearsalBatchRepository.FinalizationClaim
                failed = claimFinalization();
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                quarantined = repository.releaseFinalization(
                failed,
                ScenarioRehearsalBatchFinalizationException
                        .Reason.MATERIAL_INVALID,
                ScenarioRehearsalBatchFinalizationPolicy
                        .defaults());
        ScenarioRehearsalBatchFinalizationRemediationRequest
                stale = new ScenarioRehearsalBatchFinalizationRemediationRequest(
                "",
                "remediation-conflict",
                quarantined.attemptCount(),
                quarantined.updatedAt().minusMillis(1),
                "KMS_POLICY_REPAIRED");

        assertThatThrownBy(() ->
                repository.remediateFinalization(
                        remediation(
                                finalizing.jobId(),
                                stale),
                        policy(),
                        mock(MirrorOperationObservability
                                .Observation.class)))
                .isInstanceOfSatisfying(
                        ScenarioRehearsalBatchConflictException
                                .class,
                        failure -> assertThat(
                                failure.reason())
                                .isEqualTo(
                                        ScenarioRehearsalBatchConflictException
                                                .Reason
                                                .FINALIZATION_FENCE_MISMATCH));

        ScenarioRehearsalBatchFinalizationRemediationRequest
                accepted = remediationRequest(
                "remediation-conflict",
                quarantined);
        repository.remediateFinalization(
                remediation(
                        finalizing.jobId(),
                        accepted),
                policy(),
                mock(MirrorOperationObservability
                        .Observation.class));
        ScenarioRehearsalBatchFinalizationRemediationRequest
                reused =
                new ScenarioRehearsalBatchFinalizationRemediationRequest(
                        "",
                        accepted.commandId(),
                        accepted.expectedAttemptCount(),
                        accepted.expectedUpdatedAt(),
                        "OWNER_OVERRIDE");

        assertThatThrownBy(() ->
                repository.remediateFinalization(
                        remediation(
                                finalizing.jobId(),
                                reused),
                        policy(),
                        mock(MirrorOperationObservability
                                .Observation.class)))
                .isInstanceOfSatisfying(
                        ScenarioRehearsalBatchConflictException
                                .class,
                        failure -> assertThat(
                                failure.reason())
                                .isEqualTo(
                                        ScenarioRehearsalBatchConflictException
                                                .Reason
                                                .FINALIZATION_REMEDIATION_CONFLICT));
    }

    @Test
    void rollsBackRemediationWhenMandatoryOperationAuditFails() {
        ScenarioRehearsalBatchJob finalizing =
                makeFinalizing(
                        "batch-finalization-remediation-audit",
                        "refund",
                        "worker-a");
        ScenarioRehearsalBatchRepository.FinalizationClaim
                failed = claimFinalization();
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                quarantined = repository.releaseFinalization(
                failed,
                ScenarioRehearsalBatchFinalizationException
                        .Reason.MATERIAL_INVALID,
                ScenarioRehearsalBatchFinalizationPolicy
                        .defaults());
        MirrorOperationObservability.Observation observation =
                mock(MirrorOperationObservability
                        .Observation.class);
        doThrow(new IllegalStateException(
                "operation audit unavailable"))
                .when(observation)
                .succeeded(anyString());

        assertThatThrownBy(() ->
                repository.remediateFinalization(
                        remediation(
                                finalizing.jobId(),
                                remediationRequest(
                                        "remediation-audit",
                                        quarantined)),
                        policy(),
                        observation))
                .isInstanceOf(IllegalStateException.class);

        assertThat(repository.findFinalization(
                SCOPE, finalizing.jobId()))
                .contains(quarantined);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM scenario_rehearsal_batch_finalization_remediations
                WHERE job_id = ?
                """,
                Long.class,
                finalizing.jobId())).isZero();
    }

    @Test
    void restartRecoversPendingFinalizationIntent() {
        ScenarioRehearsalBatchJob finalizing =
                makeFinalizing(
                        "batch-finalization-restart",
                        "refund",
                        "worker-a");
        DatabaseScenarioRehearsalBatchRepository restarted =
                new DatabaseScenarioRehearsalBatchRepository(
                        jdbc,
                        mapper,
                        new DataSourceTransactionManager(database),
                        evidencePublisher,
                        lifecycleAudit,
                        ScenarioRehearsalBatchFinalizationPolicy
                                .defaults(),
                        databaseTime::get);
        restarted.init();

        ScenarioRehearsalBatchRepository.FinalizationAcquisition
                acquired = restarted.claimFinalization(
                "sg",
                "test",
                "finalizer-restarted",
                ScenarioRehearsalBatchFinalizationPolicy.defaults());

        assertThat(acquired.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .FinalizationClaimOutcome.ACQUIRED);
        assertThat(acquired.claim().intent().finalizingJob())
                .isEqualTo(finalizing);
    }

    @Test
    void aggregatesFinalizationHealthByExactScopeAndDeploymentPartition() {
        ScenarioRehearsalBatchJob first =
                makeFinalizing(
                        "batch-finalization-health",
                        "refund",
                        "worker-a");
        ScenarioRehearsalBatchRepository.FinalizationHealthSnapshot
                pending = repository.finalizationHealth(SCOPE);
        ScenarioRehearsalBatchRepository.FinalizationClaim claim =
                claimFinalization();
        databaseTime.set(NOW.plusSeconds(30));
        ScenarioRehearsalBatchRepository.FinalizationHealthSnapshot
                signing = repository.finalizationHealth(SCOPE);
        ScenarioRehearsalBatchRepository.FinalizationSnapshot
                retry = repository.releaseFinalization(
                claim,
                ScenarioRehearsalBatchFinalizationException
                        .Reason.SIGNER_UNAVAILABLE,
                ScenarioRehearsalBatchFinalizationPolicy
                        .defaults());
        databaseTime.set(retry.nextEligibleAt());
        ScenarioRehearsalBatchRepository.FinalizationClaim
                retryClaim = repository.claimFinalization(
                "sg",
                "test",
                "finalizer-retry",
                ScenarioRehearsalBatchFinalizationPolicy
                        .defaults()).claim();
        repository.releaseFinalization(
                retryClaim,
                ScenarioRehearsalBatchFinalizationException
                        .Reason.MATERIAL_INVALID,
                ScenarioRehearsalBatchFinalizationPolicy
                        .defaults());
        CapabilitySnapshot.Scope other =
                scope("tenant-b", "org-b");
        ScenarioRehearsalBatchRepository.Submission otherSubmission =
                submission(
                        other,
                        "batch-finalization-health-other",
                        "escalation");
        repository.submit(otherSubmission, policy());
        ScenarioRehearsalBatchRepository.Claim otherItem =
                repository.claimNext(
                        "sg", "test", "worker-b", policy());
        repository.completeItem(
                otherItem.lease(),
                completion(
                        otherSubmission.manifest().entries()
                                .getFirst().aggregateRunId()),
                policy());

        ScenarioRehearsalBatchRepository.FinalizationHealthSnapshot
                exact = repository.finalizationHealth(SCOPE);
        ScenarioRehearsalBatchRepository.FinalizationHealthSnapshot
                partition = repository.finalizationPartitionHealth(
                "sg", "test");

        assertThat(pending.totalCount()).isOne();
        assertThat(pending.pendingCount()).isOne();
        assertThat(pending.eligibleCount()).isOne();
        assertThat(pending.oldestEligibleAt())
                .isEqualTo(NOW);
        assertThat(signing.signingCount()).isOne();
        assertThat(signing.eligibleCount()).isZero();
        assertThat(signing.oldestActiveSigningStartedAt())
                .isEqualTo(NOW);
        assertThat(exact.totalCount()).isOne();
        assertThat(exact.quarantinedCount()).isOne();
        assertThat(exact.materialInvalidCount()).isOne();
        assertThat(exact.signerUnavailableCount()).isZero();
        assertThat(exact.maximumAttemptCount()).isEqualTo(2);
        assertThat(exact.oldestQuarantinedAt())
                .isEqualTo(databaseTime.get());
        assertThat(partition.totalCount()).isEqualTo(2);
        assertThat(partition.pendingCount()).isOne();
        assertThat(partition.quarantinedCount()).isOne();
        assertThat(first.jobId()).isEqualTo(
                retryClaim.intent().terminalJob().jobId());
    }

    @Test
    void reportsUnknownControlStateAndPolicyDriftInsteadOfLookingHealthy() {
        ScenarioRehearsalBatchJob finalizing =
                makeFinalizing(
                        "batch-finalization-health-corrupt",
                        "refund",
                        "worker-a");
        jdbc.update("""
                UPDATE scenario_rehearsal_batch_finalizations
                SET state = 'UNKNOWN_STATE',
                    policy_generation = 999
                WHERE job_id = ?
                """,
                finalizing.jobId());

        ScenarioRehearsalBatchRepository.FinalizationHealthSnapshot
                snapshot = repository.finalizationHealth(SCOPE);
        ScenarioRehearsalBatchFinalizationHealth.Assessment
                assessment =
                ScenarioRehearsalBatchFinalizationHealth.assess(
                        snapshot,
                        ScenarioRehearsalBatchFinalizationHealthPolicy
                                .defaults());

        assertThat(snapshot.totalCount()).isOne();
        assertThat(snapshot.unknownStateCount()).isOne();
        assertThat(snapshot.inconsistentRecordCount()).isOne();
        assertThat(snapshot.policyMismatchCount()).isOne();
        assertThat(assessment.state()).isEqualTo(
                ScenarioRehearsalBatchFinalizationHealth
                        .State.CRITICAL);
        assertThat(assessment.violations()).containsExactly(
                ScenarioRehearsalBatchFinalizationHealth
                        .Violation.CONTROL_RECORD_INCONSISTENT,
                ScenarioRehearsalBatchFinalizationHealth
                        .Violation.POLICY_GENERATION_MISMATCH);
    }

    @Test
    void rollsBackTerminalJobWhenTerminalLifecycleAuditFails() {
        ScenarioRehearsalBatchJob finalizing =
                makeFinalizing(
                        "batch-terminal-audit-outage",
                        "refund",
                        "worker-a");
        ScenarioRehearsalBatchRepository.FinalizationClaim claim =
                claimFinalization();
        ScenarioRehearsalBatchEvidencePublisher.PreparedFinalization
                prepared = prepared(claim);
        doThrow(new IllegalStateException(
                "terminal lifecycle audit unavailable"))
                .when(lifecycleAudit)
                .append(argThat(event ->
                        event.transition()
                                == ScenarioRehearsalBatchLifecycleAuditEvent
                                .Transition.TERMINALIZED));

        assertThatThrownBy(() ->
                repository.completeFinalization(
                        claim, prepared))
                .isInstanceOf(IllegalStateException.class);
        assertThat(repository.find(
                SCOPE, finalizing.jobId(), policy()))
                .contains(finalizing);
        assertThat(repository.findFinalization(
                SCOPE, finalizing.jobId()))
                .get()
                .extracting(
                        ScenarioRehearsalBatchRepository
                                .FinalizationSnapshot::state)
                .isEqualTo(
                        ScenarioRehearsalBatchRepository
                                .FinalizationState.SIGNING);
    }

    @Test
    void recoversExpiredLeaseAcrossAnExactSecondBoundary() {
        ScenarioRehearsalBatchPolicy policy =
                shortPolicy();
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(
                        SCOPE,
                        "batch-001",
                        "refund",
                        Duration.ofSeconds(1));
        repository.submit(submission, policy);
        ScenarioRehearsalBatchRepository.Claim first =
                repository.claimNext(
                        "sg", "test", "worker-a", policy);
        assertThat(first.lease().expiresAt())
                .isEqualTo(NOW.plusSeconds(2));

        databaseTime.set(NOW.plusMillis(2_001));
        assertThat(repository.claimNext(
                "sg", "test", "worker-b", policy).outcome())
                .isEqualTo(
                        ScenarioRehearsalBatchRepository.ClaimOutcome
                                .NO_WORK);
        databaseTime.set(NOW.plusMillis(2_101));
        ScenarioRehearsalBatchRepository.Claim takeover =
                repository.claimNext(
                        "sg", "test", "worker-b", policy);

        assertThat(takeover.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository.ClaimOutcome
                        .ACQUIRED);
        assertThat(takeover.lease().epoch()).isEqualTo(2);
        assertThat(takeover.item().attemptCount()).isEqualTo(2);
        assertThatThrownBy(() -> repository.completeItem(
                first.lease(),
                completion(
                        submission.manifest().entries()
                                .getFirst().aggregateRunId()),
                policy))
                .isInstanceOfSatisfying(
                        ScenarioRehearsalBatchConflictException.class,
                        conflict -> assertThat(conflict.reason())
                                .isEqualTo(
                                        ScenarioRehearsalBatchConflictException
                                                .Reason.LEASE_LOST));
    }

    @Test
    void exhaustedStaleLeaseUsesTheSameItemLifecycleAsAWorkerFailure() {
        ScenarioRehearsalBatchPolicy base =
                shortPolicy();
        ScenarioRehearsalBatchPolicy singleAttempt =
                new ScenarioRehearsalBatchPolicy(
                        base.generation(),
                        base.failureMode(),
                        base.priority(),
                        1,
                        base.maximumQueued(),
                        base.maximumQueuedPerTenant(),
                        base.maximumRunning(),
                        base.maximumRunningPerTenant(),
                        base.maximumPlanTimeout(),
                        base.maximumDeadlineHorizon(),
                        base.leaseReserve(),
                        base.retryBackoff(),
                        base.priorityAgingInterval(),
                        base.terminalRetention());
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(
                        SCOPE,
                        "batch-stale-exhausted",
                        "refund",
                        Duration.ofSeconds(1));
        ScenarioRehearsalBatchJob queued =
                repository.submit(
                        submission, singleAttempt).job();
        repository.claimNext(
                "sg", "test", "worker-a", singleAttempt);

        databaseTime.set(NOW.plusMillis(2_001));
        assertThat(repository.claimNext(
                "sg", "test", "worker-b", singleAttempt).outcome())
                .isEqualTo(
                        ScenarioRehearsalBatchRepository.ClaimOutcome
                                .NO_WORK);
        assertThat(repository.find(
                SCOPE, queued.jobId(), singleAttempt))
                .get()
                .extracting(ScenarioRehearsalBatchJob::status)
                .isEqualTo(
                        ScenarioRehearsalBatchJob.Status
                                .FINALIZING_EVIDENCE);
        finalizeEvidence(
                repository.find(
                        SCOPE, queued.jobId(), singleAttempt)
                        .orElseThrow());
        assertThat(repository.find(
                SCOPE, queued.jobId(), singleAttempt))
                .get()
                .extracting(ScenarioRehearsalBatchJob::status)
                .isEqualTo(
                        ScenarioRehearsalBatchJob.Status.PARTIAL);
        verify(lifecycleAudit, atLeastOnce()).append(argThat(
                event -> event.transition()
                        == ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.ITEM_TERMINALIZED
                        && event.jobId().equals(queued.jobId())
                        && event.reasonCode().equals(
                        "RG.MIRROR.REHEARSAL_BATCH.LEASE_EXPIRED")));
    }

    @Test
    void cancellationIsReplayableAndCannotRewriteTerminalHistory() {
        ScenarioRehearsalBatchRepository.Submission queued =
                submission(SCOPE, "batch-cancel", "refund");
        ScenarioRehearsalBatchJob job =
                repository.submit(queued, policy()).job();
        ScenarioRehearsalBatchRepository.Cancellation cancellation =
                new ScenarioRehearsalBatchRepository.Cancellation(
                        SCOPE,
                        job.jobId(),
                        "cancel-001",
                        "OWNER_REQUEST");

        ScenarioRehearsalBatchRepository.SubmissionResult first =
                repository.cancel(cancellation, policy());
        ScenarioRehearsalBatchRepository.SubmissionResult replay =
                repository.cancel(cancellation, policy());
        assertThat(first.job().status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status
                        .FINALIZING_EVIDENCE);
        assertThat(replay.idempotentReplay()).isTrue();
        ScenarioRehearsalBatchJob cancelled =
                finalizeEvidence(first.job());
        assertThat(cancelled.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status.CANCELLED);
        verify(lifecycleAudit, atLeastOnce()).append(argThat(
                event -> event.transition()
                        == ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.CANCELLATION_REQUESTED
                        && event.jobId().equals(job.jobId())
                        && event.reasonCode().equals(
                        "OWNER_REQUEST")));
        verify(lifecycleAudit, atLeastOnce()).append(argThat(
                event -> event.transition()
                        == ScenarioRehearsalBatchLifecycleAuditEvent
                        .Transition.TERMINALIZED
                        && event.jobId().equals(job.jobId())));
        assertThat(repository.page(
                SCOPE, job.jobId(), 0, 10, policy()).items())
                .extracting(
                        ScenarioRehearsalBatchItemPage.Item::status)
                .containsExactly(
                        ScenarioRehearsalBatchItemPage.Status
                                .CANCELLED);

        ScenarioRehearsalBatchRepository.Submission completed =
                submission(SCOPE, "batch-complete", "escalation");
        repository.submit(completed, policy());
        ScenarioRehearsalBatchRepository.Claim claim =
                repository.claimNext(
                        "sg", "test", "worker-a", policy());
        ScenarioRehearsalBatchJob terminal =
                repository.completeItem(
                        claim.lease(),
                        completion(
                                completed.manifest().entries()
                                        .getFirst().aggregateRunId()),
                        policy());
        assertThatThrownBy(() -> repository.cancel(
                new ScenarioRehearsalBatchRepository.Cancellation(
                        SCOPE,
                        terminal.jobId(),
                        "cancel-late",
                        "OWNER_REQUEST"),
                policy()))
                .isInstanceOfSatisfying(
                        ScenarioRehearsalBatchConflictException.class,
                        conflict -> assertThat(conflict.reason())
                                .isEqualTo(
                                        ScenarioRehearsalBatchConflictException
                                                .Reason
                                                .CANCELLATION_CONFLICT));
        assertThat(repository.find(
                SCOPE, terminal.jobId(), policy()))
                .contains(terminal);
    }

    @Test
    void checkpointsProgressAndStopsAtARequestedCancellation() {
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(SCOPE, "batch-running-cancel", "refund");
        ScenarioRehearsalBatchJob job =
                repository.submit(submission, policy()).job();
        ScenarioRehearsalBatchRepository.Claim claim =
                repository.claimNext(
                        "sg", "test", "worker-a", policy());

        ScenarioRehearsalBatchRepository.ExecutionControlCheckpoint
                first = repository.checkpointExecution(
                claim.lease(), 0, policy());
        assertThat(first.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .ExecutionControlOutcome.CONTINUE);
        assertThat(first.heartbeatCount()).isEqualTo(1);

        ScenarioRehearsalBatchJob cancellationRequested =
                repository.cancel(
                        new ScenarioRehearsalBatchRepository
                                .Cancellation(
                                SCOPE,
                                job.jobId(),
                                "cancel-running-001",
                                "OWNER_REQUEST"),
                        policy()).job();
        assertThat(cancellationRequested.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status.CANCEL_REQUESTED);

        databaseTime.set(NOW.plusMillis(100));
        ScenarioRehearsalBatchRepository.ExecutionControlCheckpoint
                stopped = repository.checkpointExecution(
                claim.lease(), 1, policy());

        assertThat(stopped.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .ExecutionControlOutcome.CANCELLED);
        assertThat(stopped.heartbeatCount()).isEqualTo(2);
        assertThat(stopped.job().status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status
                        .FINALIZING_EVIDENCE);
        ScenarioRehearsalBatchJob cancelled =
                finalizeEvidence(stopped.job());
        assertThat(cancelled.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status.CANCELLED);
        assertThat(repository.page(
                SCOPE, job.jobId(), 0, 10, policy()).items())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.status()).isEqualTo(
                            ScenarioRehearsalBatchItemPage.Status
                                    .INDETERMINATE);
                    assertThat(item.failureCode()).isEqualTo(
                            "RG.MIRROR.REHEARSAL_BATCH.CANCELLED");
                });
        assertThat(jdbc.queryForObject("""
                SELECT heartbeat_count
                FROM scenario_rehearsal_batch_jobs
                WHERE job_id = ?
                """, Long.class, job.jobId())).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT heartbeat_case_index
                FROM scenario_rehearsal_batch_jobs
                WHERE job_id = ?
                """, Integer.class, job.jobId())).isEqualTo(1);

        ScenarioRehearsalBatchRepository.ExecutionControlCheckpoint
                stale = repository.checkpointExecution(
                claim.lease(), 1, policy());
        assertThat(stale.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .ExecutionControlOutcome.LEASE_LOST);
        assertThat(stale.job()).isEqualTo(cancelled);
    }

    @Test
    void terminalizesTheExactLeaseAtTheDatabaseDeadline() {
        ScenarioRehearsalBatchPolicy policy = shortPolicy();
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(
                        SCOPE,
                        "batch-deadline",
                        "refund",
                        Duration.ofSeconds(4));
        repository.submit(submission, policy);
        ScenarioRehearsalBatchRepository.Claim claim =
                repository.claimNext(
                        "sg", "test", "worker-a", policy);
        assertThat(claim.lease().expiresAt())
                .isEqualTo(NOW.plusSeconds(5));

        databaseTime.set(NOW.plusSeconds(5));
        ScenarioRehearsalBatchRepository.ExecutionControlCheckpoint
                stopped = repository.checkpointExecution(
                claim.lease(), 0, policy);

        assertThat(stopped.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .ExecutionControlOutcome.DEADLINE_EXCEEDED);
        assertThat(stopped.job().status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status
                        .FINALIZING_EVIDENCE);
        assertThat(stopped.job().failureCode()).isEqualTo(
                "RG.MIRROR.REHEARSAL_BATCH.DEADLINE_EXCEEDED");
        ScenarioRehearsalBatchJob expired =
                finalizeEvidence(stopped.job());
        assertThat(expired.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status.EXPIRED);
        assertThat(repository.page(
                SCOPE,
                stopped.job().jobId(),
                0,
                10,
                policy).items())
                .extracting(
                        ScenarioRehearsalBatchItemPage.Item::status)
                .containsExactly(
                        ScenarioRehearsalBatchItemPage.Status
                                .INDETERMINATE);
    }

    @Test
    void failsClosedOnSameGenerationPolicyDrift() {
        ScenarioRehearsalBatchPolicy first =
                policy();
        repository.submit(
                submission(SCOPE, "batch-001", "refund"),
                first);
        ScenarioRehearsalBatchPolicy drift =
                new ScenarioRehearsalBatchPolicy(
                        first.generation(),
                        first.failureMode(),
                        ScenarioRehearsalBatchPolicy.Priority.HIGH,
                        first.maximumItemAttempts(),
                        first.maximumQueued(),
                        first.maximumQueuedPerTenant(),
                        first.maximumRunning(),
                        first.maximumRunningPerTenant(),
                        first.maximumPlanTimeout(),
                        first.maximumDeadlineHorizon(),
                        first.leaseReserve(),
                        first.retryBackoff(),
                        first.priorityAgingInterval(),
                        first.terminalRetention());

        assertThatThrownBy(() -> repository.find(
                SCOPE,
                ScenarioRehearsalBatchIdentity.derive(
                        mapper, SCOPE, "batch-001"),
                drift))
                .isInstanceOfSatisfying(
                        ScenarioRehearsalBatchConflictException.class,
                        conflict -> assertThat(conflict.reason())
                                .isEqualTo(
                                        ScenarioRehearsalBatchConflictException
                                                .Reason.POLICY_MISMATCH));
    }

    private ScenarioRehearsalBatchRepository.Submission submission(
            CapabilitySnapshot.Scope scope,
            String requestId,
            String planId) {
        return submission(
                scope, requestId, planId,
                Duration.ofSeconds(5));
    }

    private ScenarioRehearsalBatchRepository.Submission submission(
            CapabilitySnapshot.Scope scope,
            String requestId,
            String planId,
            Duration timeout) {
        return submission(
                scope,
                requestId,
                List.of(planId),
                timeout);
    }

    private ScenarioRehearsalBatchRepository.Submission submission(
            CapabilitySnapshot.Scope scope,
            String requestId,
            List<String> planIds,
            Duration timeout) {
        List<MirrorArtifactRef> refs = planIds.stream()
                .map(planId -> new MirrorArtifactRef(
                        "COMPILED_REHEARSAL_PLAN",
                        planId,
                        1,
                        "sha256:" + Integer.toHexString(
                                Math.abs(planId.hashCode()) % 16)
                                .repeat(64)))
                .toList();
        ScenarioRehearsalBatchRequest request =
                new ScenarioRehearsalBatchRequest(
                        "",
                        requestId,
                        java.util.stream.IntStream.range(
                                        0, refs.size())
                                .mapToObj(index ->
                                        new ScenarioRehearsalBatchRequest
                                                .Entry(
                                                "entry-" + index,
                                                refs.get(index)))
                                .toList());
        ScenarioRehearsalBatchManifest manifest =
                ScenarioRehearsalBatchManifestIntegrity.seal(
                        mapper,
                        new ScenarioRehearsalBatchManifest(
                                "",
                                ScenarioRehearsalBatchIdentity
                                        .derive(
                                                mapper,
                                                scope,
                                                requestId),
                                "",
                                scope,
                                requestId,
                                java.util.stream.IntStream.range(
                                                0, refs.size())
                                        .mapToObj(index -> {
                                            String aggregateRequest =
                                                    requestId
                                                            + ":plan:"
                                                            + "%03d".formatted(
                                                            index);
                                            return new ScenarioRehearsalBatchManifest
                                                    .Entry(
                                                    index,
                                                    "entry-" + index,
                                                    refs.get(index),
                                                    aggregateRequest,
                                                    ScenarioRehearsalRunIdentity
                                                            .derive(
                                                                    mapper,
                                                                    scope,
                                                                    aggregateRequest),
                                                    1,
                                                    timeout);
                                        })
                                        .toList(),
                                refs.size()));
        return new ScenarioRehearsalBatchRepository.Submission(
                request,
                ProtocolFingerprint.of(mapper, request),
                manifest,
                new ScenarioRehearsalBatchPrincipal(
                        scope,
                        "USER",
                        "owner-a",
                        "",
                        Set.of("support-owner"),
                        "RESTRICTED",
                        ""));
    }

    private ScenarioRehearsalBatchRepository.ItemCompletion
    completion(String runId) {
        return completion(
                runId,
                ScenarioCaseRehearsalResult.Outcome.PASS);
    }

    private ScenarioRehearsalBatchRepository.ItemCompletion
    completion(
            String runId,
            ScenarioCaseRehearsalResult.Outcome outcome) {
        return new ScenarioRehearsalBatchRepository.ItemCompletion(
                outcome,
                runId,
                "sha256:" + "e".repeat(64),
                "sha256:" + "d".repeat(64));
    }

    private ScenarioRehearsalBatchJob finalizeEvidence(
            ScenarioRehearsalBatchJob finalizing) {
        assertThat(finalizing.status()).isEqualTo(
                ScenarioRehearsalBatchJob.Status
                        .FINALIZING_EVIDENCE);
        ScenarioRehearsalBatchRepository.FinalizationClaim claim =
                claimFinalization();
        ScenarioRehearsalBatchEvidencePublisher.PreparedFinalization
                prepared = prepared(claim);
        return repository.completeFinalization(
                claim, prepared);
    }

    private ScenarioRehearsalBatchJob makeFinalizing(
            String requestId,
            String planId,
            String workerId) {
        ScenarioRehearsalBatchRepository.Submission submission =
                submission(SCOPE, requestId, planId);
        repository.submit(submission, policy());
        ScenarioRehearsalBatchRepository.Claim claim =
                repository.claimNext(
                        "sg", "test", workerId, policy());
        return repository.completeItem(
                claim.lease(),
                completion(
                        submission.manifest().entries()
                                .getFirst().aggregateRunId()),
                policy());
    }

    private ScenarioRehearsalBatchRepository.FinalizationClaim
    claimFinalization() {
        ScenarioRehearsalBatchRepository.FinalizationAcquisition
                acquired = repository.claimFinalization(
                "sg",
                "test",
                "finalizer-a",
                ScenarioRehearsalBatchFinalizationPolicy.defaults());
        assertThat(acquired.outcome()).isEqualTo(
                ScenarioRehearsalBatchRepository
                        .FinalizationClaimOutcome.ACQUIRED);
        return acquired.claim();
    }

    private ScenarioRehearsalBatchFinalizationRemediationRequest
    remediationRequest(
            String commandId,
            ScenarioRehearsalBatchRepository
                    .FinalizationSnapshot quarantined) {
        return new ScenarioRehearsalBatchFinalizationRemediationRequest(
                "",
                commandId,
                quarantined.attemptCount(),
                quarantined.updatedAt(),
                "KMS_POLICY_REPAIRED");
    }

    private ScenarioRehearsalBatchRepository
            .FinalizationRemediation
    remediation(
            String jobId,
            ScenarioRehearsalBatchFinalizationRemediationRequest
                    request) {
        return new ScenarioRehearsalBatchRepository
                .FinalizationRemediation(
                SCOPE,
                jobId,
                request,
                ProtocolFingerprint.of(
                        mapper, request));
    }

    private ScenarioRehearsalBatchEvidencePublisher.PreparedFinalization
    prepared(
            ScenarioRehearsalBatchRepository.FinalizationClaim claim) {
        ScenarioRehearsalBatchRepository.FinalizationIntent intent =
                claim.intent();
        ScenarioRehearsalBatchEvidenceBundle bundle =
                mock(ScenarioRehearsalBatchEvidenceBundle.class);
        ScenarioRehearsalBatchEvidenceIndex index =
                mock(ScenarioRehearsalBatchEvidenceIndex.class);
        ScenarioRehearsalBatchEvidenceAttestation attestation =
                mock(
                        ScenarioRehearsalBatchEvidenceAttestation
                                .class);
        ScenarioRehearsalBatchRetentionRepository
                .PreparedRegistration registration =
                mock(
                        ScenarioRehearsalBatchRetentionRepository
                                .PreparedRegistration.class);
        ScenarioRehearsalBatchRetentionEvent event =
                mock(
                        ScenarioRehearsalBatchRetentionEvent.class);
        when(bundle.bundleFingerprint())
                .thenReturn("sha256:" + "b".repeat(64));
        when(bundle.index()).thenReturn(index);
        when(bundle.attestation()).thenReturn(attestation);
        when(index.job()).thenReturn(intent.terminalJob());
        when(index.request()).thenReturn(intent.request());
        when(index.manifest()).thenReturn(intent.manifest());
        when(index.items()).thenReturn(intent.items());
        when(attestation.signedAt())
                .thenReturn(claim.signingStartedAt());
        when(registration.bundleFingerprint())
                .thenReturn("sha256:" + "b".repeat(64));
        when(registration.retainUntil())
                .thenReturn(intent.retainUntil());
        when(registration.event()).thenReturn(event);
        when(event.jobId()).thenReturn(
                intent.terminalJob().jobId());
        when(event.occurredAt())
                .thenReturn(claim.signingStartedAt());
        ScenarioRehearsalBatchEvidencePublisher.PreparedFinalization
                prepared =
                new ScenarioRehearsalBatchEvidencePublisher
                        .PreparedFinalization(
                        bundle, registration);
        when(evidencePublisher.persist(prepared))
                .thenReturn(bundle);
        return prepared;
    }

    private ScenarioRehearsalBatchPolicy policy() {
        return policy(8, 4);
    }

    private ScenarioRehearsalBatchPolicy policy(
            int maximumRunning,
            int maximumRunningPerTenant) {
        return new ScenarioRehearsalBatchPolicy(
                1,
                ScenarioRehearsalBatchPolicy.FailureMode
                        .COLLECT_ALL,
                ScenarioRehearsalBatchPolicy.Priority.NORMAL,
                3,
                100,
                20,
                maximumRunning,
                maximumRunningPerTenant,
                Duration.ofMinutes(10),
                Duration.ofHours(1),
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                Duration.ofDays(1));
    }

    private ScenarioRehearsalBatchPolicy shortPolicy() {
        return new ScenarioRehearsalBatchPolicy(
                1,
                ScenarioRehearsalBatchPolicy.FailureMode
                        .COLLECT_ALL,
                ScenarioRehearsalBatchPolicy.Priority.NORMAL,
                3,
                100,
                20,
                8,
                4,
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1),
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                Duration.ofDays(1));
    }

    private List<String> columns(String table) {
        return jdbc.queryForList(
                """
                        SELECT COLUMN_NAME
                        FROM INFORMATION_SCHEMA.COLUMNS
                        WHERE TABLE_NAME = ?
                        ORDER BY ORDINAL_POSITION
                        """,
                String.class,
                table);
    }

    private static CapabilitySnapshot.Scope scope(
            String tenant,
            String organization) {
        return scope(tenant, organization, "sg");
    }

    private static CapabilitySnapshot.Scope scope(
            String tenant,
            String organization,
            String region) {
        return new CapabilitySnapshot.Scope(
                tenant,
                organization,
                "support",
                "test",
                region);
    }

    private static boolean businessPayloadColumn(String column) {
        String normalized = column.toLowerCase(
                java.util.Locale.ROOT);
        return normalized.contains("payload")
                || normalized.contains("fixture_value")
                || normalized.contains("input_json")
                || normalized.contains("output_json")
                || normalized.contains("credential")
                || normalized.contains("secret");
    }
}
