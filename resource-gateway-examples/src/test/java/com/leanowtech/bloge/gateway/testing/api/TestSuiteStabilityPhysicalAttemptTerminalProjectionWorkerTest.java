package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-22T02:00:00Z");
    private static final TestSuiteStabilityQueuePolicy QUEUE_POLICY = queuePolicy();

    @Test
    void noWorkDoesNotEnterCoordinatorOrCompletionAuthority() {
        var fixture = fixture(Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(5), System::nanoTime);
        when(fixture.works().claimNext("replica-a/terminal-projection-1"))
                .thenReturn(Optional.empty());

        var execution = fixture.worker().processNext();

        assertThat(execution.outcome()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.NO_WORK);
        verifyNoInteractions(fixture.coordinator());
        verify(fixture.works(), never()).complete(any(), any());
        fixture.supervisor().close();
    }

    @Test
    void claimAuthorityFailureIsPayloadFreeAndDoesNotInvokeCoordinator() {
        var fixture = fixture(Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(5), System::nanoTime);
        when(fixture.works().claimNext(anyString()))
                .thenThrow(new IllegalStateException("tenant-secret"));

        var execution = fixture.worker().processNext();

        assertThat(execution.outcome()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome
                        .WORK_UNAVAILABLE);
        assertThat(execution.toString()).doesNotContain("tenant-secret");
        verifyNoInteractions(fixture.coordinator());
        fixture.supervisor().close();
    }

    @ParameterizedTest
    @EnumSource(TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
            .CompletionStatus.class)
    void mapsEveryDurableCompletionStatusWithoutReinterpretingIt(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.CompletionStatus
                    completionStatus) {
        var fixture = fixture(Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(5), System::nanoTime);
        var claim = claim(Duration.ofSeconds(30));
        when(fixture.works().claimNext(anyString())).thenReturn(Optional.of(claim));
        when(fixture.coordinator().project(anyString(), anyString(), anyString(), any()))
                .thenReturn(proofPending());
        var completion = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Completion.class);
        when(completion.status()).thenReturn(completionStatus);
        when(fixture.works().complete(any(), any())).thenReturn(completion);

        var execution = fixture.worker().processNext();

        assertThat(execution.outcome().name()).isEqualTo(completionStatus.name());
        assertThat(execution.localDisposition()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition.NONE);
        fixture.supervisor().close();
    }

    @Test
    void preservesProofPendingDetailInFencedDurableResult() {
        var fixture = fixture(Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(5), System::nanoTime);
        var claim = claim(Duration.ofSeconds(30));
        when(fixture.works().claimNext(anyString())).thenReturn(Optional.of(claim));
        when(fixture.coordinator().project(anyString(), anyString(), anyString(), any()))
                .thenReturn(proofPending());
        var completion = completion(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.CompletionStatus
                        .RESCHEDULED);
        when(fixture.works().complete(any(), any())).thenReturn(completion);
        ArgumentCaptor<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result>
                result = ArgumentCaptor.forClass(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result.class);

        var execution = fixture.worker().processNext();

        verify(fixture.works()).complete(org.mockito.ArgumentMatchers.eq(claim.lease()),
                result.capture());
        assertThat(result.getValue().kind()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ResultKind
                        .PROOF_PENDING);
        assertThat(result.getValue().proofReason()).contains(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                        .PARENT_NOT_CONFIRMED);
        assertThat(execution.outcome()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.RESCHEDULED);
        fixture.supervisor().close();
    }

    @Test
    void claimLatencyExhaustsBudgetBeforeCoordinatorStarts() {
        AtomicLong nanos = new AtomicLong();
        var fixture = fixture(Duration.ofSeconds(30), Duration.ofSeconds(20),
                Duration.ofSeconds(5), () -> nanos.getAndAdd(
                        Duration.ofSeconds(25).toNanos()));
        var claim = claim(Duration.ofSeconds(30));
        when(fixture.works().claimNext(anyString())).thenReturn(Optional.of(claim));
        var completion = completion(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.CompletionStatus
                        .RESCHEDULED);
        when(fixture.works().complete(any(), any())).thenReturn(completion);
        ArgumentCaptor<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result>
                result = ArgumentCaptor.forClass(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result.class);

        var execution = fixture.worker().processNext();

        verifyNoInteractions(fixture.coordinator());
        verify(fixture.works()).complete(any(), result.capture());
        assertThat(result.getValue().kind()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ResultKind
                        .UNAVAILABLE);
        assertThat(result.getValue().failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROJECTION_UNAVAILABLE);
        assertThat(execution.localDisposition()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition
                        .BUDGET_EXHAUSTED);
        fixture.supervisor().close();
    }

    @Test
    void timeoutReschedulesWhileLingeringCoordinatorMayConvergeByReplay() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var fixture = fixture(Duration.ofSeconds(2), Duration.ofMillis(100),
                Duration.ofMillis(100), System::nanoTime);
        var claim = claim(Duration.ofSeconds(2));
        when(fixture.works().claimNext(anyString())).thenReturn(Optional.of(claim));
        when(fixture.coordinator().project(anyString(), anyString(), anyString(), any()))
                .thenAnswer(ignored -> {
                    entered.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignoredInterrupt) {
                            // Simulate a late exact-source projection transaction.
                        }
                    }
                    return proofPending();
                });
        var completion = completion(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.CompletionStatus
                        .RESCHEDULED);
        when(fixture.works().complete(any(), any())).thenReturn(completion);
        ArgumentCaptor<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result>
                result = ArgumentCaptor.forClass(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result.class);
        try {
            var execution = fixture.worker().processNext();

            assertThat(entered.getCount()).isZero();
            verify(fixture.works()).complete(any(), result.capture());
            assertThat(result.getValue().kind()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ResultKind
                            .UNAVAILABLE);
            assertThat(execution.outcome()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome
                            .RESCHEDULED);
            assertThat(execution.localDisposition()).isEqualTo(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition
                            .TIMED_OUT);
            assertThat(fixture.supervisor().snapshot().lingeringCalls()).isEqualTo(1);
        } finally {
            release.countDown();
            awaitNoActiveCall(fixture.supervisor());
            fixture.supervisor().close();
        }
    }

    @Test
    void callerInterruptSkipsCompletionIoAndLeavesLeaseForTakeover() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        var fixture = fixture(Duration.ofSeconds(30), Duration.ofSeconds(2),
                Duration.ofSeconds(5), System::nanoTime);
        when(fixture.works().claimNext(anyString()))
                .thenReturn(Optional.of(claim(Duration.ofSeconds(30))));
        when(fixture.coordinator().project(anyString(), anyString(), anyString(), any()))
                .thenAnswer(ignored -> {
                    entered.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return proofPending();
                });

        Thread caller = Thread.ofPlatform().start(() -> {
            var result = fixture.worker().processNext();
            interrupted.set(Thread.currentThread().isInterrupted()
                    && result.outcome()
                    == TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome
                    .CALLER_INTERRUPTED);
        });
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2_000L);
            assertThat(caller.isAlive()).isFalse();
            assertThat(interrupted).isTrue();
            verify(fixture.works(), never()).complete(any(), any());
        } finally {
            release.countDown();
            fixture.supervisor().close();
        }
    }

    @Test
    void newerLeaseOwnerFencesLateCompletion() {
        var fixture = fixture(Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(5), System::nanoTime);
        when(fixture.works().claimNext(anyString()))
                .thenReturn(Optional.of(claim(Duration.ofSeconds(30))));
        when(fixture.coordinator().project(anyString(), anyString(), anyString(), any()))
                .thenReturn(proofPending());
        when(fixture.works().complete(any(), any())).thenThrow(
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .ConflictException(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                .ConflictReason.LEASE_LOST));

        var execution = fixture.worker().processNext();

        assertThat(execution.outcome()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.LEASE_LOST);
        assertThat(execution.conflictReason()).contains(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ConflictReason
                        .LEASE_LOST);
        fixture.supervisor().close();
    }

    @Test
    void changedResultConflictRemainsDistinctFromLeaseLoss() {
        var fixture = fixture(Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(5), System::nanoTime);
        when(fixture.works().claimNext(anyString()))
                .thenReturn(Optional.of(claim(Duration.ofSeconds(30))));
        when(fixture.coordinator().project(anyString(), anyString(), anyString(), any()))
                .thenReturn(proofPending());
        when(fixture.works().complete(any(), any())).thenThrow(
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                        .ConflictException(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                .ConflictReason.RESULT_CONFLICT));

        var execution = fixture.worker().processNext();

        assertThat(execution.outcome()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.WORK_CONFLICT);
        assertThat(execution.conflictReason()).contains(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.ConflictReason
                        .RESULT_CONFLICT);
        fixture.supervisor().close();
    }

    @Test
    void malformedCoordinatorResultReschedulesAsContractViolation() {
        var fixture = fixture(Duration.ofSeconds(30), Duration.ofSeconds(1),
                Duration.ofSeconds(5), System::nanoTime);
        when(fixture.works().claimNext(anyString()))
                .thenReturn(Optional.of(claim(Duration.ofSeconds(30))));
        var malformed = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt.class);
        when(fixture.coordinator().project(anyString(), anyString(), anyString(), any()))
                .thenReturn(malformed);
        var completion = completion(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.CompletionStatus
                        .RESCHEDULED);
        when(fixture.works().complete(any(), any())).thenReturn(completion);
        ArgumentCaptor<TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result>
                result = ArgumentCaptor.forClass(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Result.class);

        var execution = fixture.worker().processNext();

        verify(fixture.works()).complete(any(), result.capture());
        assertThat(result.getValue().failureReason()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROJECTION_CONTRACT_VIOLATION);
        assertThat(execution.outcome()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.RESCHEDULED);
        assertThat(execution.localDisposition()).isEqualTo(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition
                        .UNAVAILABLE);
        fixture.supervisor().close();
    }

    @Test
    void constructorRejectsLeaseBudgetOverlapAndInvalidPolicyShapes() {
        var works = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.class);
        when(works.policy()).thenReturn(workPolicy(Duration.ofSeconds(5)));
        var coordinator = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class);
        try (var supervisor = new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor(
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Policy(
                        Duration.ofSeconds(4), 1))) {
            assertThatThrownBy(() -> new
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker(
                    works, coordinator, supervisor, QUEUE_POLICY,
                    new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Policy(
                            Duration.ofSeconds(1)), "worker-a"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("shorter than the work lease");
        }
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Policy(
                Duration.ofMillis(99))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void executionTruthTableRejectsImpossibleConflictAndLocalShapes() {
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Execution(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Execution
                        .SCHEMA_VERSION,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.NO_WORK,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition
                        .TIMED_OUT,
                Optional.empty())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Execution(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Execution
                        .SCHEMA_VERSION,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Outcome.LEASE_LOST,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.LocalDisposition.NONE,
                Optional.of(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal
                                .ConflictReason.RESULT_CONFLICT)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Fixture fixture(
            Duration leaseDuration,
            Duration callTimeout,
            Duration completionReserve,
            java.util.function.LongSupplier nanos) {
        var works = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.class);
        when(works.policy()).thenReturn(workPolicy(leaseDuration));
        var coordinator = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class);
        var supervisor = new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor(
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Policy(
                        callTimeout, 1));
        var worker = new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker(
                works, coordinator, supervisor, QUEUE_POLICY,
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker.Policy(
                        completionReserve),
                "replica-a/terminal-projection-1", nanos);
        return new Fixture(works, coordinator, supervisor, worker);
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Claim claim(
            Duration leaseDuration) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var trigger = TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Trigger
                .create(mapper, "tenant-a", "test", attemptId(),
                        "stability-attempt-observe-" + "2".repeat(64), fingerprint('3'));
        var lease = new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Lease(
                trigger.workId(), trigger.attemptId(),
                "replica-a/terminal-projection-1", UUID.randomUUID().toString(), 1,
                NOW, NOW.plus(leaseDuration), fingerprint('4'));
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Claim(
                lease, trigger, 0, 0, 0, NOW.minusSeconds(1));
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt
            proofPending() {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Stage
                        .PROOF_PENDING,
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.FailureReason
                        .PROOF_NOT_READY,
                Optional.of(
                        TestSuiteStabilityPhysicalAttemptTerminalProjectionProofResolver.Reason
                                .PARENT_NOT_CONFIRMED),
                Optional.empty(), Optional.empty());
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Completion
            completion(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.CompletionStatus
                    status) {
        var completion = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Completion.class);
        when(completion.status()).thenReturn(status);
        return completion;
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Policy
            workPolicy(Duration leaseDuration) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal.Policy(
                leaseDuration, Duration.ofMillis(100), Duration.ofMillis(100),
                Duration.ofSeconds(30), 8);
    }

    private static TestSuiteStabilityQueuePolicy queuePolicy() {
        return new TestSuiteStabilityQueuePolicy(
                1, 100, 20, 10, 5, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(30), 2,
                Duration.ofHours(1), Duration.ofDays(30));
    }

    private static void awaitNoActiveCall(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && supervisor.snapshot().activeCalls() != 0) {
            Thread.sleep(10L);
        }
        assertThat(supervisor.snapshot().activeCalls()).isZero();
    }

    private static String attemptId() {
        return "stability-attempt-" + "1".repeat(64);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Fixture(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorkJournal works,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator coordinator,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionWorker worker) {
    }
}
