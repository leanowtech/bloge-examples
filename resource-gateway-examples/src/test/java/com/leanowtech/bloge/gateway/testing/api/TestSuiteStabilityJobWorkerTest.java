package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSuiteStabilityJobWorkerTest {

    private ObjectMapper mapper;
    private TestSuiteStabilityJobRepository repository;
    private TestSuiteStabilityExecutionService executions;
    private TestSuiteStabilityJobAuthorizer authorizer;
    private TestSuiteStabilityJobExecutionCoordinator coordinator;
    private TestSuiteStabilityQueuePolicy policy;
    private TestSuiteStabilityJobRecord job;
    private TestSuiteStabilityJobLease lease;
    private TestSuiteStabilityJobWorker worker;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        repository = mock(TestSuiteStabilityJobRepository.class);
        executions = mock(TestSuiteStabilityExecutionService.class);
        authorizer = mock(TestSuiteStabilityJobAuthorizer.class);
        coordinator = TestSuiteStabilityJobExecutionCoordinator.passive(
                repository, mapper, Duration.ofSeconds(1));
        policy = policy();
        job = job();
        lease = lease(1, Instant.now().plusSeconds(30));
        worker = new TestSuiteStabilityJobWorker(
                repository, executions, coordinator, authorizer, policy, "worker-a", 1);
        when(repository.claimNext("test", "worker-a", policy)).thenReturn(claim());
        when(authorizer.reauthorize(job))
                .thenReturn(TestSuiteStabilityJobAuthorizer.Authorization.authorized());
    }

    @AfterEach
    void tearDown() {
        coordinator.close();
    }

    @Test
    void successfulWorkUsesGuardedCommittingPublication() {
        SuccessPath path = stubSuccess();

        TestSuiteStabilityJobWorkResult result = worker.processNext("test");

        assertThat(result).isEqualTo(TestSuiteStabilityJobWorkResult.succeeded(job.jobId()));
        verify(repository).prepareCompletion(path.runningLease(), policy);
        verify(repository).complete(path.finalLease(), path.stabilityRunId(),
                path.evidenceFingerprint(), policy);
    }

    @Test
    void localSlotIsAcquiredBeforeAnySecondDurableClaim() throws Exception {
        SuccessPath path = stubSuccess();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(executions.executeControlled(
                eq(job.request().suiteRef().suiteId()), eq(job.request()), any(), any()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(3, TimeUnit.SECONDS);
                    TestSuiteStabilityExecutionControl control = invocation.getArgument(3);
                    control.executionStarted(
                            TestSuiteStabilityExecutionIdentity.descriptor(mapper, job));
                    control.checkpoint(
                            TestSuiteStabilityExecutionControl.Phase.BEFORE_PROGRESS_RESTORE, 0);
                    control.prepareTerminal();
                    return path.response();
                });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var first = pool.submit(() -> worker.processNext("test"));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(worker.processNext("test").outcome())
                    .isEqualTo(TestSuiteStabilityJobWorkResult.Outcome.LOCAL_CAPACITY);
            verify(repository, times(1)).claimNext("test", "worker-a", policy);

            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS).outcome())
                    .isEqualTo(TestSuiteStabilityJobWorkResult.Outcome.SUCCEEDED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void revokedAuthorityFailsBeforeEngineExecution() {
        when(authorizer.reauthorize(job)).thenReturn(
                TestSuiteStabilityJobAuthorizer.Authorization.revoked(
                        "RG.TEST.STABILITY_JOB_AUTHORIZATION_REVOKED"));
        TestSuiteStabilityJobLease renewed = lease(1, lease.expiresAt().plusSeconds(10));
        when(repository.checkAndRenew(lease, policy))
                .thenReturn(TestSuiteStabilityJobLeaseCheck.continuing(renewed));
        when(repository.fail(renewed,
                "RG.TEST.STABILITY_JOB_AUTHORIZATION_REVOKED", policy))
                .thenReturn(record(TestSuiteStabilityJobRecord.Status.FAILED,
                        "RG.TEST.STABILITY_JOB_AUTHORIZATION_REVOKED"));

        TestSuiteStabilityJobWorkResult result = worker.processNext("test");

        assertThat(result.outcome()).isEqualTo(
                TestSuiteStabilityJobWorkResult.Outcome.AUTHORIZATION_REVOKED);
        verifyNoInteractions(executions);
    }

    @Test
    void authorizationAmbiguityRetriesWithoutStartingEngine() {
        when(authorizer.reauthorize(job)).thenReturn(
                TestSuiteStabilityJobAuthorizer.Authorization.unavailable(
                        "RG.TEST.STABILITY_JOB_AUTHORIZATION_UNAVAILABLE"));
        TestSuiteStabilityJobLease renewed = lease(1, lease.expiresAt().plusSeconds(10));
        when(repository.checkAndRenew(lease, policy))
                .thenReturn(TestSuiteStabilityJobLeaseCheck.continuing(renewed));
        when(repository.retry(renewed,
                "RG.TEST.STABILITY_JOB_AUTHORIZATION_UNAVAILABLE", policy))
                .thenReturn(record(TestSuiteStabilityJobRecord.Status.QUEUED,
                        "RG.TEST.STABILITY_JOB_AUTHORIZATION_UNAVAILABLE"));

        TestSuiteStabilityJobWorkResult result = worker.processNext("test");

        assertThat(result.outcome())
                .isEqualTo(TestSuiteStabilityJobWorkResult.Outcome.RETRIED);
        verifyNoInteractions(executions);
    }

    @Test
    void cooperativeCancellationNeverFallsThroughToRetryOrCompletion() {
        when(executions.executeControlled(
                eq(job.request().suiteRef().suiteId()), eq(job.request()), any(), any()))
                .thenAnswer(invocation -> {
                    TestSuiteStabilityExecutionControl control = invocation.getArgument(3);
                    control.executionStarted(
                            TestSuiteStabilityExecutionIdentity.descriptor(mapper, job));
                    control.checkpoint(TestSuiteStabilityExecutionControl.Phase.BEFORE_ATTEMPT, 1);
                    return null;
                });
        when(repository.checkAndRenew(lease, policy)).thenReturn(
                TestSuiteStabilityJobLeaseCheck.stopped(
                        TestSuiteStabilityJobLeaseCheck.Decision.CANCELLED,
                        "RG.TEST.STABILITY_JOB_CANCELLED"));

        TestSuiteStabilityJobWorkResult result = worker.processNext("test");

        assertThat(result.outcome())
                .isEqualTo(TestSuiteStabilityJobWorkResult.Outcome.CANCELLED);
        verify(repository, never()).retry(any(), any(), any());
        verify(repository, never()).fail(any(), any(), any());
        verify(repository, never()).complete(any(), any(), any(), any());
    }

    @Test
    void retryableExecutionProblemReturnsTheExactJobToBackoff() {
        when(executions.executeControlled(
                eq(job.request().suiteRef().suiteId()), eq(job.request()), any(), any()))
                .thenThrow(new IntegrationProblemException(
                        IntegrationProblem.serviceUnavailable(
                                "RG.TEST.STABILITY_SOURCE_UNAVAILABLE", "Unavailable", "", null)));
        TestSuiteStabilityJobLease renewed = lease(1, lease.expiresAt().plusSeconds(10));
        when(repository.checkAndRenew(lease, policy))
                .thenReturn(TestSuiteStabilityJobLeaseCheck.continuing(renewed));
        when(repository.retry(renewed, "RG.TEST.STABILITY_SOURCE_UNAVAILABLE", policy))
                .thenReturn(record(TestSuiteStabilityJobRecord.Status.QUEUED,
                        "RG.TEST.STABILITY_SOURCE_UNAVAILABLE"));

        TestSuiteStabilityJobWorkResult result = worker.processNext("test");

        assertThat(result.outcome())
                .isEqualTo(TestSuiteStabilityJobWorkResult.Outcome.RETRIED);
        verify(repository).retry(renewed, "RG.TEST.STABILITY_SOURCE_UNAVAILABLE", policy);
    }

    @Test
    void deterministicExecutionProblemFailsParentFirst() {
        when(executions.executeControlled(
                eq(job.request().suiteRef().suiteId()), eq(job.request()), any(), any()))
                .thenAnswer(invocation -> {
                    TestSuiteStabilityExecutionControl control = invocation.getArgument(3);
                    control.executionStarted(
                            TestSuiteStabilityExecutionIdentity.descriptor(mapper, job));
                    throw new IntegrationProblemException(IntegrationProblem.conflict(
                            "RG.TEST.STABILITY_SUITE_CONFLICT", "Conflict", "", null));
                });
        TestSuiteStabilityJobLease renewed = lease(1, lease.expiresAt().plusSeconds(10));
        when(repository.checkAndRenew(lease, policy))
                .thenReturn(TestSuiteStabilityJobLeaseCheck.continuing(renewed));
        when(repository.fail(renewed, "RG.TEST.STABILITY_SUITE_CONFLICT", policy))
                .thenReturn(record(TestSuiteStabilityJobRecord.Status.FAILED,
                        "RG.TEST.STABILITY_SUITE_CONFLICT"));

        TestSuiteStabilityJobWorkResult result = worker.processNext("test");

        assertThat(result.outcome()).isEqualTo(TestSuiteStabilityJobWorkResult.Outcome.FAILED);
        verify(repository).fail(renewed, "RG.TEST.STABILITY_SUITE_CONFLICT", policy);
    }

    @Test
    void failureAfterTerminalPreparationCanOnlyRetryCommitting() {
        TestSuiteStabilityJobLease committing = lease(1, lease.expiresAt().plusSeconds(10));
        TestSuiteStabilityJobLease renewed = lease(1, committing.expiresAt().plusSeconds(10));
        when(repository.prepareCompletion(lease, policy)).thenReturn(
                TestSuiteStabilityJobCompletionPreparation.prepared(committing));
        when(repository.checkAndRenew(committing, policy))
                .thenReturn(TestSuiteStabilityJobLeaseCheck.continuing(renewed));
        when(repository.retry(renewed,
                "RG.TEST.STABILITY_JOB_PUBLICATION_UNAVAILABLE", policy))
                .thenReturn(record(TestSuiteStabilityJobRecord.Status.COMMITTING,
                        "RG.TEST.STABILITY_JOB_PUBLICATION_UNAVAILABLE"));
        when(executions.executeControlled(
                eq(job.request().suiteRef().suiteId()), eq(job.request()), any(), any()))
                .thenAnswer(invocation -> {
                    TestSuiteStabilityExecutionControl control = invocation.getArgument(3);
                    control.executionStarted(
                            TestSuiteStabilityExecutionIdentity.descriptor(mapper, job));
                    control.prepareTerminal();
                    throw new IntegrationProblemException(IntegrationProblem.conflict(
                            "RG.TEST.STABILITY_TERMINAL_CONFLICT", "Conflict", "", null));
                });

        TestSuiteStabilityJobWorkResult result = worker.processNext("test");

        assertThat(result.outcome())
                .isEqualTo(TestSuiteStabilityJobWorkResult.Outcome.RETRIED);
        assertThat(result.failureCode()).isEqualTo(
                "RG.TEST.STABILITY_JOB_PUBLICATION_UNAVAILABLE");
        verify(repository).retry(renewed,
                "RG.TEST.STABILITY_JOB_PUBLICATION_UNAVAILABLE", policy);
        verify(repository, never()).fail(any(), any(), any());
    }

    @Test
    void queueClaimAmbiguityReturnsNoInventedJobIdentity() {
        when(repository.claimNext("test", "worker-a", policy))
                .thenThrow(new IllegalStateException("database unavailable"));

        TestSuiteStabilityJobWorkResult result = worker.processNext("test");

        assertThat(result.outcome())
                .isEqualTo(TestSuiteStabilityJobWorkResult.Outcome.QUEUE_UNAVAILABLE);
        assertThat(result.jobId()).isBlank();
        verifyNoInteractions(executions);
    }

    private SuccessPath stubSuccess() {
        String runId = "stability-" + "5".repeat(64);
        String evidenceFingerprint = TestSuiteStabilityProtocolFixtures.fingerprint('6');
        TestSuiteStabilityExecutionResponse response =
                mock(TestSuiteStabilityExecutionResponse.class);
        when(response.stabilityRunId()).thenReturn(runId);
        when(response.evidenceFingerprint()).thenReturn(evidenceFingerprint);
        TestSuiteStabilityJobLease runningLease = lease(1, lease.expiresAt().plusSeconds(10));
        TestSuiteStabilityJobLease committing = lease(1, runningLease.expiresAt().plusSeconds(10));
        TestSuiteStabilityJobLease finalLease = lease(1, committing.expiresAt().plusSeconds(10));
        when(repository.checkAndRenew(lease, policy))
                .thenReturn(TestSuiteStabilityJobLeaseCheck.continuing(runningLease));
        when(repository.prepareCompletion(runningLease, policy)).thenReturn(
                TestSuiteStabilityJobCompletionPreparation.prepared(committing));
        when(repository.checkAndRenew(committing, policy))
                .thenReturn(TestSuiteStabilityJobLeaseCheck.continuing(finalLease));
        when(repository.complete(finalLease, runId, evidenceFingerprint, policy))
                .thenReturn(record(TestSuiteStabilityJobRecord.Status.SUCCEEDED, ""));
        when(executions.executeControlled(
                eq(job.request().suiteRef().suiteId()), eq(job.request()), any(), any()))
                .thenAnswer(invocation -> {
                    TestSuiteStabilityExecutionControl control = invocation.getArgument(3);
                    control.executionStarted(
                            TestSuiteStabilityExecutionIdentity.descriptor(mapper, job));
                    control.checkpoint(
                            TestSuiteStabilityExecutionControl.Phase.BEFORE_PROGRESS_RESTORE, 0);
                    control.prepareTerminal();
                    return response;
                });
        return new SuccessPath(
                response, runningLease, finalLease, runId, evidenceFingerprint);
    }

    private TestSuiteStabilityJobClaim claim() {
        return TestSuiteStabilityJobClaim.acquired(
                Instant.now(), job, lease, 1);
    }

    private TestSuiteStabilityJobLease lease(long epoch, Instant expiresAt) {
        return new TestSuiteStabilityJobLease(
                job.jobId(), job.tenantId(), job.environmentId(), job.requestFingerprint(),
                "worker-a", epoch, expiresAt);
    }

    private TestSuiteStabilityJobRecord record(
            TestSuiteStabilityJobRecord.Status status,
            String failureCode) {
        Instant now = job.createdAt();
        boolean succeeded = status == TestSuiteStabilityJobRecord.Status.SUCCEEDED;
        return new TestSuiteStabilityJobRecord(
                job.jobId(), job.request(), job.requestFingerprint(), job.classification(),
                job.principal(), job.priority(), status,
                status == TestSuiteStabilityJobRecord.Status.QUEUED ? 1 : job.retryCount(),
                now, job.deadlineAt(), now, Instant.now(), now.plus(Duration.ofDays(31)),
                succeeded ? "stability-" + "5".repeat(64) : "",
                succeeded ? TestSuiteStabilityProtocolFixtures.fingerprint('6') : "",
                failureCode, "", "", TestSuiteStabilityProtocolFixtures.fingerprint('7'));
    }

    private static TestSuiteStabilityJobRecord job() {
        Instant now = Instant.now();
        TestSuiteStabilityExecutionRequest request = new TestSuiteStabilityExecutionRequest(
                "", TestSuiteStabilityProtocolFixtures.SUITE_REF,
                "worker-request", 3, Map.of("pipeline", "nightly"));
        TestSuiteStabilityJobPrincipal principal = new TestSuiteStabilityJobPrincipal(
                "tenant-a", "org-a", "project-a", "test", "sg-1", "SERVICE",
                "ci-runner", "", "TEST_EXECUTION", "correlation-1",
                Set.of("test-runners"), "INTERNAL", "");
        return new TestSuiteStabilityJobRecord(
                "stability-job-" + "1".repeat(64), request,
                TestSuiteStabilityProtocolFixtures.fingerprint('9'), "INTERNAL", principal,
                TestSuiteStabilityJobSubmission.Priority.NORMAL,
                TestSuiteStabilityJobRecord.Status.RUNNING, 0, now,
                now.plus(Duration.ofHours(1)), now, now, now.plus(Duration.ofDays(31)),
                "", "", "", "", "", TestSuiteStabilityProtocolFixtures.fingerprint('8'));
    }

    private static TestSuiteStabilityQueuePolicy policy() {
        return new TestSuiteStabilityQueuePolicy(
                1, 10, 10, 2, 1, Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofMinutes(1), 3,
                Duration.ofDays(7), Duration.ofDays(30));
    }

    private record SuccessPath(
            TestSuiteStabilityExecutionResponse response,
            TestSuiteStabilityJobLease runningLease,
            TestSuiteStabilityJobLease finalLease,
            String stabilityRunId,
            String evidenceFingerprint) {
    }
}
