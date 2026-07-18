package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSuiteStabilityJobExecutionCoordinatorTest {

    private ObjectMapper mapper;
    private TestSuiteStabilityJobRepository repository;
    private TestSuiteStabilityQueuePolicy policy;
    private TestSuiteStabilityJobRecord job;
    private TestSuiteStabilityJobLease lease;
    private TestSuiteStabilityJobExecutionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        repository = mock(TestSuiteStabilityJobRepository.class);
        policy = policy();
        job = job();
        lease = lease(1, Instant.now().plusSeconds(30));
        coordinator = TestSuiteStabilityJobExecutionCoordinator.passive(
                repository, mapper, Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        coordinator.close();
    }

    @Test
    void exactDescriptorBindingAndCheckpointRenewTheLatestFence() {
        TestSuiteStabilityJobLease renewed = lease(1, lease.expiresAt().plusSeconds(10));
        when(repository.checkAndRenew(lease, policy))
                .thenReturn(TestSuiteStabilityJobLeaseCheck.continuing(renewed));

        try (var guard = coordinator.monitor(job, lease, policy)) {
            guard.executionStarted(TestSuiteStabilityExecutionIdentity.descriptor(mapper, job));
            guard.checkpoint(
                    TestSuiteStabilityExecutionControl.Phase.BEFORE_PROGRESS_RESTORE, 0);
        }

        verify(repository).checkAndRenew(lease, policy);
    }

    @Test
    void descriptorMismatchFailsBeforeAnyQueueMutation() {
        TestSuiteStabilityExecutionDescriptor expected =
                TestSuiteStabilityExecutionIdentity.descriptor(mapper, job);
        TestSuiteStabilityExecutionDescriptor wrong = new TestSuiteStabilityExecutionDescriptor(
                expected.stabilityRunId(), expected.tenantId(), expected.environmentId(),
                "another-request", expected.requestFingerprint(), expected.classification());

        try (var guard = coordinator.monitor(job, lease, policy)) {
            assertThatThrownBy(() -> guard.executionStarted(wrong))
                    .isInstanceOfSatisfying(
                            TestSuiteStabilityJobExecutionCoordinator.ControlException.class,
                            failure -> {
                                assertThat(failure.reason()).isEqualTo(
                                        TestSuiteStabilityJobExecutionCoordinator.ControlException
                                                .Reason.DESCRIPTOR_MISMATCH);
                                assertThat(failure.failureCode()).isEqualTo(
                                        "RG.TEST.STABILITY_JOB_DESCRIPTOR_MISMATCH");
                            });
        }

        verifyNoInteractions(repository);
    }

    @Test
    void typedCancellationStopsAtACooperativeCheckpoint() {
        when(repository.checkAndRenew(lease, policy)).thenReturn(
                TestSuiteStabilityJobLeaseCheck.stopped(
                        TestSuiteStabilityJobLeaseCheck.Decision.CANCELLED,
                        "RG.TEST.STABILITY_JOB_CANCELLED"));

        try (var guard = boundGuard()) {
            assertThatThrownBy(() -> guard.checkpoint(
                    TestSuiteStabilityExecutionControl.Phase.BEFORE_ATTEMPT, 1))
                    .isInstanceOfSatisfying(
                            TestSuiteStabilityJobExecutionCoordinator.ControlException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(
                                    TestSuiteStabilityJobExecutionCoordinator.ControlException
                                            .Reason.CANCELLED));
        }
    }

    @Test
    void terminalPreparationAndFinalRenewalRetainTheCommittingFence() {
        TestSuiteStabilityJobLease committing = lease(1, lease.expiresAt().plusSeconds(10));
        TestSuiteStabilityJobLease finalLease = lease(1, committing.expiresAt().plusSeconds(10));
        when(repository.prepareCompletion(lease, policy)).thenReturn(
                TestSuiteStabilityJobCompletionPreparation.prepared(committing));
        when(repository.checkAndRenew(committing, policy)).thenReturn(
                TestSuiteStabilityJobLeaseCheck.continuing(finalLease));

        try (var guard = boundGuard()) {
            guard.prepareTerminal();

            assertThat(guard.publicationPrepared()).isTrue();
            assertThat(guard.leaseForCompletion()).isEqualTo(finalLease);
            guard.completed();
        }
    }

    @Test
    void terminalPreparationSurfacesTheExactDeadlineWinner() {
        when(repository.prepareCompletion(lease, policy)).thenReturn(
                TestSuiteStabilityJobCompletionPreparation.stopped(
                        TestSuiteStabilityJobCompletionPreparation.Decision.DEADLINE_EXCEEDED,
                        "RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED"));

        try (var guard = boundGuard()) {
            assertThatThrownBy(guard::prepareTerminal)
                    .isInstanceOfSatisfying(
                            TestSuiteStabilityJobExecutionCoordinator.ControlException.class,
                            failure -> {
                                assertThat(failure.reason()).isEqualTo(
                                        TestSuiteStabilityJobExecutionCoordinator.ControlException
                                                .Reason.DEADLINE_EXCEEDED);
                                assertThat(failure.failureCode()).isEqualTo(
                                        "RG.TEST.STABILITY_JOB_DEADLINE_EXCEEDED");
                            });
        }
    }

    @Test
    void repositoryAmbiguityPermanentlyFailClosesTheGuard() {
        when(repository.checkAndRenew(lease, policy))
                .thenThrow(new IllegalStateException("database unavailable"));

        try (var guard = boundGuard()) {
            assertThatThrownBy(() -> guard.checkpoint(
                    TestSuiteStabilityExecutionControl.Phase.BEFORE_ATTEMPT, 1))
                    .isInstanceOfSatisfying(
                            TestSuiteStabilityJobExecutionCoordinator.ControlException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(
                                    TestSuiteStabilityJobExecutionCoordinator.ControlException
                                            .Reason.STORE_UNAVAILABLE));
            assertThatThrownBy(() -> guard.checkpoint(
                    TestSuiteStabilityExecutionControl.Phase.BEFORE_ATTEMPT, 1))
                    .isInstanceOfSatisfying(
                            TestSuiteStabilityJobExecutionCoordinator.ControlException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(
                                    TestSuiteStabilityJobExecutionCoordinator.ControlException
                                            .Reason.STORE_UNAVAILABLE));
        }
    }

    @Test
    void activeCoordinatorHeartbeatsDuringLongChildWork() throws Exception {
        coordinator.close();
        coordinator = new TestSuiteStabilityJobExecutionCoordinator(
                repository, mapper, Duration.ofSeconds(1));
        CountDownLatch heartbeat = new CountDownLatch(1);
        when(repository.checkAndRenew(lease, policy)).thenAnswer(invocation -> {
            heartbeat.countDown();
            return TestSuiteStabilityJobLeaseCheck.continuing(lease);
        });

        try (var guard = boundGuard()) {
            assertThat(heartbeat.await(3, TimeUnit.SECONDS)).isTrue();
        }

        verify(repository, atLeastOnce()).checkAndRenew(lease, policy);
    }

    private TestSuiteStabilityJobExecutionCoordinator.ExecutionGuard boundGuard() {
        var guard = coordinator.monitor(job, lease, policy);
        guard.executionStarted(TestSuiteStabilityExecutionIdentity.descriptor(mapper, job));
        return guard;
    }

    private TestSuiteStabilityJobLease lease(long epoch, Instant expiresAt) {
        return new TestSuiteStabilityJobLease(
                job.jobId(), job.tenantId(), job.environmentId(), job.requestFingerprint(),
                "worker-a", epoch, expiresAt);
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
                "", "", "", "", "",
                TestSuiteStabilityProtocolFixtures.fingerprint('8'));
    }

    private static TestSuiteStabilityQueuePolicy policy() {
        return new TestSuiteStabilityQueuePolicy(
                1, 10, 10, 2, 1, Duration.ofSeconds(30), Duration.ofMinutes(5),
                Duration.ofSeconds(1), Duration.ofMinutes(1), 3,
                Duration.ofDays(7), Duration.ofDays(30));
    }
}
