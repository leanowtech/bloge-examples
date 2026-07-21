package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityPhysicalAttemptObservationCallSupervisorTest {

    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");

    @Test
    void supervisesDescriptorAndObservationWithoutTrustingJavaProvenance() {
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command();
        TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor = descriptor();
        TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation =
                attestation(command);
        AtomicInteger descriptorCalls = new AtomicInteger();
        AtomicInteger observationCalls = new AtomicInteger();
        TestSuiteStabilityPhysicalAttemptObservationAuthority authority = authority(
                () -> {
                    descriptorCalls.incrementAndGet();
                    return descriptor;
                }, ignored -> {
                    observationCalls.incrementAndGet();
                    return attestation;
                });

        try (var supervisor = supervisor(Duration.ofSeconds(1), 2)) {
            assertThat(supervisor.descriptor(authority)).isSameAs(descriptor);
            assertThat(supervisor.observe(authority, command)).isSameAs(attestation);
            assertThat(supervisor.snapshot())
                    .extracting(
                            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot
                                    ::acceptedCalls,
                            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot
                                    ::completedCalls,
                            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot
                                    ::activeCalls)
                    .containsExactly(2L, 2L, 0L);
        }
        assertThat(descriptorCalls).hasValue(1);
        assertThat(observationCalls).hasValue(1);
    }

    @Test
    void zeroQueueSaturationRejectsBeforeAnotherProviderCallStarts() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        TestSuiteStabilityPhysicalAttemptObservationAuthority authority = authority(
                () -> {
                    calls.incrementAndGet();
                    entered.countDown();
                    await(release);
                    return descriptor();
                }, ignored -> attestation(command()));
        ExecutorService caller = Executors.newSingleThreadExecutor();

        try (var supervisor = supervisor(Duration.ofSeconds(2), 1)) {
            Future<?> first = caller.submit(() -> supervisor.descriptor(authority));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

            assertInvocation(() -> supervisor.descriptor(authority),
                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.CallType
                            .DESCRIPTOR,
                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Disposition
                            .SATURATED);
            assertThat(calls).hasValue(1);
            assertThat(supervisor.snapshot().saturatedCalls()).isEqualTo(1);

            release.countDown();
            first.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            caller.shutdownNow();
            assertThat(caller.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void timeoutLeavesInterruptIgnoringProviderVisibleAndOccupyingItsSlot()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestSuiteStabilityPhysicalAttemptObservationCommand command = command();
        TestSuiteStabilityPhysicalAttemptObservationAuthority authority = authority(
                TestSuiteStabilityPhysicalAttemptObservationCallSupervisorTest::descriptor,
                ignored -> {
                    entered.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignoredInterrupt) {
                            // Deliberately simulate a non-cooperative remote adapter.
                        }
                    }
                    return attestation(command);
                });

        try (var supervisor = supervisor(Duration.ofMillis(100), 1)) {
            assertInvocation(() -> supervisor.observe(authority, command),
                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.CallType
                            .OBSERVATION,
                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Disposition
                            .TIMED_OUT);
            assertThat(entered.getCount()).isZero();
            awaitSnapshot(supervisor, 1, 1);
            assertInvocation(() -> supervisor.descriptor(authority),
                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.CallType
                            .DESCRIPTOR,
                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Disposition
                            .SATURATED);

            release.countDown();
            awaitSnapshot(supervisor, 0, 0);
            assertThat(supervisor.snapshot().timedOutCalls()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void providerFailureAndNullResultCollapseWithoutLeakingDiagnostics() {
        TestSuiteStabilityPhysicalAttemptObservationAuthority failing = authority(
                () -> {
                    throw new IllegalStateException("secret-provider-endpoint");
                }, ignored -> null);

        try (var supervisor = supervisor(Duration.ofSeconds(1), 1)) {
            assertInvocation(() -> supervisor.descriptor(failing),
                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.CallType
                            .DESCRIPTOR,
                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Disposition
                            .UNAVAILABLE);
            assertThatThrownBy(() -> supervisor.observe(failing, command()))
                    .isInstanceOfSatisfying(
                            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
                                    .InvocationException.class,
                            failure -> {
                                assertThat(failure.disposition()).isEqualTo(
                                        TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
                                                .Disposition.UNAVAILABLE);
                                assertThat(failure.getMessage())
                                        .doesNotContain("secret-provider-endpoint");
                                assertThat(failure.getCause()).isNull();
                            });
            assertThat(supervisor.snapshot().failedCalls()).isEqualTo(2);
        }
    }

    @Test
    void callerInterruptIsRestoredWithoutInventingLifecycleState() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicBoolean correctDisposition = new AtomicBoolean();
        TestSuiteStabilityPhysicalAttemptObservationAuthority authority = authority(
                () -> {
                    entered.countDown();
                    await(release);
                    return descriptor();
                }, ignored -> attestation(command()));
        try (var supervisor = supervisor(Duration.ofSeconds(2), 1)) {
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    supervisor.descriptor(authority);
                } catch (TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
                        .InvocationException failure) {
                    correctDisposition.set(failure.disposition()
                            == TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
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
    void closeRejectsNewCallsAndMarksActiveAdapterForLingeringObservation()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TestSuiteStabilityPhysicalAttemptObservationAuthority authority = authority(
                () -> {
                    entered.countDown();
                    while (release.getCount() > 0) {
                        try {
                            release.await(20, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignoredInterrupt) {
                            // Simulate an adapter that survives supervisor shutdown.
                        }
                    }
                    return descriptor();
                }, ignored -> attestation(command()));
        ExecutorService caller = Executors.newSingleThreadExecutor();
        var supervisor = supervisor(Duration.ofSeconds(2), 1);
        try {
            Future<?> active = caller.submit(() -> {
                try {
                    supervisor.descriptor(authority);
                } catch (RuntimeException ignored) {
                    // Close may cancel the waiter while the adapter remains active.
                }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            supervisor.close();
            awaitSnapshot(supervisor, 1, 1);
            assertInvocation(() -> supervisor.observe(authority, command()),
                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.CallType
                            .OBSERVATION,
                    TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Disposition
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
    void policyAndSnapshotRejectImpossibleCapacityOrCounterShapes() {
        assertThatThrownBy(() ->
                new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy(
                        Duration.ofMillis(99), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy(
                        Duration.ofSeconds(1), Duration.ofMinutes(6), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 33))
                .isInstanceOf(IllegalArgumentException.class);
        var policy = new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
        assertThatThrownBy(() ->
                new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot(
                        TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Snapshot
                                .SCHEMA_VERSION,
                        policy, 1, 0, 0, 0, 0, 0, 0, 1, 2, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TestSuiteStabilityPhysicalAttemptObservationCallSupervisor supervisor(
            Duration observationTimeout, int capacity) {
        return new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor(
                new TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Policy(
                        Duration.ofSeconds(1), observationTimeout, capacity));
    }

    private static TestSuiteStabilityPhysicalAttemptObservationAuthority authority(
            java.util.function.Supplier<
                    TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor> descriptor,
            Function<TestSuiteStabilityPhysicalAttemptObservationCommand,
                    TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation> observe) {
        return new TestSuiteStabilityPhysicalAttemptObservationAuthority() {
            @Override
            public Descriptor descriptor() {
                return descriptor.get();
            }

            @Override
            public TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation observe(
                    TestSuiteStabilityPhysicalAttemptObservationCommand command) {
                return observe.apply(command);
            }
        };
    }

    private static TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor descriptor() {
        return new TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptObservationAuthority.Descriptor.SCHEMA_VERSION,
                "isolated-runtime-a", "isolated-runtime-a.generation-7",
                "isolated-runtime-a.key-3", true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofSeconds(30), Duration.ofHours(1));
    }

    private static TestSuiteStabilityPhysicalAttemptObservationCommand command() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TestSuiteStabilityJobLease lease = new TestSuiteStabilityJobLease(
                "stability-job-" + "1".repeat(64), "tenant-a", "test",
                fingerprint('2'), "worker-a", 7, NOW.plusSeconds(60));
        TestSuiteStabilityPhysicalAttemptIdentity identity =
                TestSuiteStabilityPhysicalAttemptIdentity.create(
                        mapper, lease, fingerprint('3'), "isolated-runtime-a",
                        "isolated-runtime-a.generation-7",
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS);
        TestSuiteStabilityPhysicalAttemptStartCommand start =
                TestSuiteStabilityPhysicalAttemptStartCommand.create(
                        mapper, identity, "stability-envelope-" + "4".repeat(64),
                        fingerprint('4'), NOW, NOW.plusSeconds(30), challenge('a'));
        return TestSuiteStabilityPhysicalAttemptObservationCommand.create(
                mapper, start, "", 0, NOW.plusSeconds(5), NOW.plusSeconds(15),
                challenge('b'));
    }

    private static TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation attestation(
            TestSuiteStabilityPhysicalAttemptObservationCommand command) {
        TestSuiteStabilityPhysicalAttemptObservationReceipt receipt =
                new TestSuiteStabilityPhysicalAttemptObservationReceipt(
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(),
                        command.identity().providerId(), command.identity().deploymentId(),
                        command.identity().attemptId(),
                        command.identity().identityFingerprint(),
                        command.startCommand().commandId(),
                        command.startCommand().commandFingerprint(),
                        command.identity().leaseEpoch(), 11, 1,
                        command.identity().isolationMode(),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.State.RUNNING,
                        fingerprint('5'), fingerprint('6'),
                        TestSuiteStabilityPhysicalAttemptObservationReceipt.TerminalDisposition
                                .NONE,
                        "", NOW.plusSeconds(6), NOW.plusSeconds(7));
        return new TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation(
                TestSuiteStabilityPhysicalAttemptObservationReceipt.Attestation.SCHEMA_VERSION,
                receipt, "isolated-runtime-a.key-3",
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]));
    }

    private static void assertInvocation(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.CallType callType,
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor.Disposition disposition) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptObservationCallSupervisor
                                .InvocationException.class,
                        failure -> {
                            assertThat(failure.callType()).isEqualTo(callType);
                            assertThat(failure.disposition()).isEqualTo(disposition);
                        });
    }

    private static void awaitSnapshot(
            TestSuiteStabilityPhysicalAttemptObservationCallSupervisor supervisor,
            long active,
            long lingering) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var snapshot = supervisor.snapshot();
            if (snapshot.activeCalls() == active
                    && snapshot.lingeringCalls() == lingering) {
                return;
            }
            Thread.sleep(10L);
        }
        assertThat(supervisor.snapshot().activeCalls()).isEqualTo(active);
        assertThat(supervisor.snapshot().lingeringCalls()).isEqualTo(lingering);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Provider test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider test latch interrupted");
        }
    }

    private static String challenge(char value) {
        byte[] challenge = new byte[32];
        java.util.Arrays.fill(challenge, (byte) value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(challenge);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
