package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisorTest {

    private static final TestSuiteStabilityQueuePolicy QUEUE_POLICY = queuePolicy();

    @Test
    void returnsAuthoritativeAttemptWithinCallerDerivedDeadline() {
        var coordinator = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class);
        var expected = proofPending();
        when(coordinator.project("tenant-a", "test", attemptId(), QUEUE_POLICY))
                .thenReturn(expected);

        try (var supervisor = supervisor(Duration.ofSeconds(1), 1)) {
            assertThat(supervisor.project(coordinator, "tenant-a", "test", attemptId(),
                    QUEUE_POLICY, Duration.ofMillis(250))).isSameAs(expected);
            assertThat(supervisor.snapshot())
                    .extracting(
                            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
                                    .Snapshot::acceptedCalls,
                            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
                                    .Snapshot::completedCalls,
                            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
                                    .Snapshot::activeCalls)
                    .containsExactly(1L, 1L, 0L);
        }
    }

    @Test
    void timeoutLeavesInterruptIgnoringCoordinatorVisibleAndOccupyingItsSlot()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var coordinator = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class);
        when(coordinator.project(anyString(), anyString(), anyString(), any()))
                .thenAnswer(ignored -> {
                    entered.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignoredInterrupt) {
                            // Deliberately model a non-cooperative coordinator dependency.
                        }
                    }
                    return proofPending();
                });

        try (var supervisor = supervisor(Duration.ofSeconds(1), 1)) {
            assertInvocation(() -> project(supervisor, coordinator, Duration.ofMillis(100)),
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Disposition
                            .TIMED_OUT);
            assertThat(entered.getCount()).isZero();
            awaitSnapshot(supervisor, 1, 1);
            assertInvocation(() -> project(supervisor, coordinator, Duration.ofMillis(100)),
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Disposition
                            .SATURATED);

            release.countDown();
            awaitSnapshot(supervisor, 0, 0);
            assertThat(supervisor.snapshot().timedOutCalls()).isEqualTo(1);
            assertThat(supervisor.snapshot().saturatedCalls()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void coordinatorFailureAndNullCollapseWithoutLeakingDiagnostics() {
        var coordinator = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class);
        when(coordinator.project(anyString(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("secret-database-endpoint"))
                .thenReturn(null);

        try (var supervisor = supervisor(Duration.ofSeconds(1), 1)) {
            assertThatThrownBy(() -> project(
                    supervisor, coordinator, Duration.ofMillis(500)))
                    .isInstanceOfSatisfying(
                            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
                                    .InvocationException.class,
                            failure -> {
                                assertThat(failure.disposition()).isEqualTo(
                                        TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
                                                .Disposition.UNAVAILABLE);
                                assertThat(failure.getMessage())
                                        .doesNotContain("secret-database-endpoint");
                                assertThat(failure.getCause()).isNull();
                            });
            assertInvocation(() -> project(
                            supervisor, coordinator, Duration.ofMillis(500)),
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Disposition
                            .UNAVAILABLE);
            assertThat(supervisor.snapshot().failedCalls()).isEqualTo(2);
        }
    }

    @Test
    void callerInterruptIsRestoredAndDoesNotInventProjectionState() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicBoolean correctDisposition = new AtomicBoolean();
        var coordinator = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class);
        when(coordinator.project(anyString(), anyString(), anyString(), any()))
                .thenAnswer(ignored -> {
                    entered.countDown();
                    release.await(2, TimeUnit.SECONDS);
                    return proofPending();
                });

        try (var supervisor = supervisor(Duration.ofSeconds(2), 1)) {
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    project(supervisor, coordinator, Duration.ofSeconds(2));
                } catch (TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
                        .InvocationException failure) {
                    correctDisposition.set(failure.disposition()
                            == TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
                            .Disposition.CALLER_INTERRUPTED);
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2_000L);

            assertThat(caller.isAlive()).isFalse();
            assertThat(correctDisposition).isTrue();
            assertThat(interruptRestored).isTrue();
            assertThat(supervisor.snapshot().interruptedCalls()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void closeRejectsNewCallsAndMarksActiveCoordinatorAsLingering() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var coordinator = mock(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class);
        when(coordinator.project(anyString(), anyString(), anyString(), any()))
                .thenAnswer(ignored -> {
                    entered.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignoredInterrupt) {
                            // Deliberately survive supervisor shutdown.
                        }
                    }
                    return proofPending();
                });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        var supervisor = supervisor(Duration.ofSeconds(2), 1);
        try {
            Future<?> active = caller.submit(() -> {
                try {
                    project(supervisor, coordinator, Duration.ofSeconds(2));
                } catch (RuntimeException ignored) {
                    // Shutdown may cancel the waiter while the call remains active.
                }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            supervisor.close();
            awaitSnapshot(supervisor, 1, 1);
            assertInvocation(() -> project(
                            supervisor, coordinator, Duration.ofMillis(100)),
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Disposition
                            .CLOSED);
            release.countDown();
            active.get(2, TimeUnit.SECONDS);
            awaitSnapshot(supervisor, 0, 0);
            assertThat(supervisor.snapshot().closed()).isTrue();
        } finally {
            release.countDown();
            supervisor.close();
            caller.shutdownNow();
            assertThat(caller.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsTimeoutOutsideConfiguredDynamicBudgetAndImpossibleSnapshots() {
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Policy(
                Duration.ofMillis(99), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Policy(
                Duration.ofSeconds(1), 33)).isInstanceOf(IllegalArgumentException.class);
        var policy = new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Policy(
                Duration.ofSeconds(1), 1);
        try (var supervisor = new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor(policy)) {
            var coordinator = mock(
                    TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.class);
            assertThatThrownBy(() -> project(
                    supervisor, coordinator, Duration.ofMillis(99)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> project(
                    supervisor, coordinator, Duration.ofMillis(1_001)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Snapshot
                        .SCHEMA_VERSION,
                policy, 1, 0, 0, 0, 0, 0, 0, 1, 2, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator.Attempt project(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCoordinator coordinator,
            Duration timeout) {
        return supervisor.project(coordinator, "tenant-a", "test", attemptId(),
                QUEUE_POLICY, timeout);
    }

    private static TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor(
            Duration timeout, int capacity) {
        return new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor(
                new TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Policy(
                        timeout, capacity));
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

    private static void assertInvocation(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor.Disposition
                    disposition) {
        assertThatThrownBy(operation).isInstanceOfSatisfying(
                TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor
                        .InvocationException.class,
                failure -> assertThat(failure.disposition()).isEqualTo(disposition));
    }

    private static void awaitSnapshot(
            TestSuiteStabilityPhysicalAttemptTerminalProjectionCallSupervisor supervisor,
            long active,
            long lingering) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var snapshot = supervisor.snapshot();
            if (snapshot.activeCalls() == active && snapshot.lingeringCalls() == lingering) {
                return;
            }
            Thread.sleep(10L);
        }
        assertThat(supervisor.snapshot().activeCalls()).isEqualTo(active);
        assertThat(supervisor.snapshot().lingeringCalls()).isEqualTo(lingering);
    }

    private static TestSuiteStabilityQueuePolicy queuePolicy() {
        return new TestSuiteStabilityQueuePolicy(
                1, 100, 20, 10, 5, Duration.ofSeconds(30), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(30), 2,
                Duration.ofHours(1), Duration.ofDays(30));
    }

    private static String attemptId() {
        return "stability-attempt-" + "1".repeat(64);
    }
}
