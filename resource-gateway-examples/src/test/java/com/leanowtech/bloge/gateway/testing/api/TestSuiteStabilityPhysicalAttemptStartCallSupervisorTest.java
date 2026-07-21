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

class TestSuiteStabilityPhysicalAttemptStartCallSupervisorTest {

    private static final Instant NOW = Instant.parse("2026-07-22T04:00:00Z");

    @Test
    void supervisesDescriptorAndStartWithoutTrustingTheirJavaProvenance() {
        TestSuiteStabilityPhysicalAttemptStartCommand command = command();
        TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor = descriptor();
        TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation =
                attestation(command);
        AtomicInteger descriptorCalls = new AtomicInteger();
        AtomicInteger startCalls = new AtomicInteger();
        TestSuiteStabilityPhysicalAttemptStartAuthority authority = authority(
                () -> {
                    descriptorCalls.incrementAndGet();
                    return descriptor;
                }, ignored -> {
                    startCalls.incrementAndGet();
                    return attestation;
                });

        try (var supervisor = supervisor(Duration.ofSeconds(1), 2)) {
            assertThat(supervisor.descriptor(authority)).isSameAs(descriptor);
            assertThat(supervisor.start(authority, command)).isSameAs(attestation);
            assertThat(supervisor.snapshot())
                    .extracting(
                            TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Snapshot
                                    ::acceptedCalls,
                            TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Snapshot
                                    ::completedCalls,
                            TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Snapshot
                                    ::activeCalls)
                    .containsExactly(2L, 2L, 0L);
        }
        assertThat(descriptorCalls).hasValue(1);
        assertThat(startCalls).hasValue(1);
    }

    @Test
    void zeroQueueSaturationRejectsBeforeAnotherProviderCallStarts() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        TestSuiteStabilityPhysicalAttemptStartAuthority authority = authority(
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
                    TestSuiteStabilityPhysicalAttemptStartCallSupervisor.CallType.DESCRIPTOR,
                    TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Disposition.SATURATED);
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
        TestSuiteStabilityPhysicalAttemptStartCommand command = command();
        TestSuiteStabilityPhysicalAttemptStartAuthority authority = authority(
                TestSuiteStabilityPhysicalAttemptStartCallSupervisorTest::descriptor,
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
            assertInvocation(() -> supervisor.start(authority, command),
                    TestSuiteStabilityPhysicalAttemptStartCallSupervisor.CallType.START,
                    TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Disposition.TIMED_OUT);
            assertThat(entered.getCount()).isZero();
            awaitSnapshot(supervisor, 1, 1);
            assertInvocation(() -> supervisor.descriptor(authority),
                    TestSuiteStabilityPhysicalAttemptStartCallSupervisor.CallType.DESCRIPTOR,
                    TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Disposition.SATURATED);

            release.countDown();
            awaitSnapshot(supervisor, 0, 0);
            assertThat(supervisor.snapshot().timedOutCalls()).isEqualTo(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void providerFailureAndNullResultCollapseWithoutLeakingDiagnostics() {
        TestSuiteStabilityPhysicalAttemptStartAuthority failing = authority(
                () -> {
                    throw new IllegalStateException("secret-provider-endpoint");
                }, ignored -> null);

        try (var supervisor = supervisor(Duration.ofSeconds(1), 1)) {
            assertInvocation(() -> supervisor.descriptor(failing),
                    TestSuiteStabilityPhysicalAttemptStartCallSupervisor.CallType.DESCRIPTOR,
                    TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Disposition.UNAVAILABLE);
            assertThatThrownBy(() -> supervisor.start(failing, command()))
                    .isInstanceOfSatisfying(
                            TestSuiteStabilityPhysicalAttemptStartCallSupervisor
                                    .InvocationException.class,
                            failure -> {
                                assertThat(failure.disposition()).isEqualTo(
                                        TestSuiteStabilityPhysicalAttemptStartCallSupervisor
                                                .Disposition.UNAVAILABLE);
                                assertThat(failure.getMessage())
                                        .doesNotContain("secret-provider-endpoint");
                                assertThat(failure.getCause()).isNull();
                            });
            assertThat(supervisor.snapshot().failedCalls()).isEqualTo(2);
        }
    }

    @Test
    void callerInterruptIsRestoredAndDoesNotClaimRemoteNonStart() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        AtomicBoolean correctDisposition = new AtomicBoolean();
        TestSuiteStabilityPhysicalAttemptStartAuthority authority = authority(
                () -> {
                    entered.countDown();
                    await(release);
                    return descriptor();
                }, ignored -> attestation(command()));
        try (var supervisor = supervisor(Duration.ofSeconds(2), 1)) {
            Thread caller = Thread.ofPlatform().start(() -> {
                try {
                    supervisor.descriptor(authority);
                } catch (TestSuiteStabilityPhysicalAttemptStartCallSupervisor
                        .InvocationException failure) {
                    correctDisposition.set(failure.disposition()
                            == TestSuiteStabilityPhysicalAttemptStartCallSupervisor
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
        TestSuiteStabilityPhysicalAttemptStartAuthority authority = authority(
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
                    // Close may cancel the waiting future while the adapter remains active.
                }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            supervisor.close();
            awaitSnapshot(supervisor, 1, 1);
            assertInvocation(() -> supervisor.start(authority, command()),
                    TestSuiteStabilityPhysicalAttemptStartCallSupervisor.CallType.START,
                    TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Disposition.CLOSED);
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
        assertThatThrownBy(() -> new TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Policy(
                Duration.ofMillis(99), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Policy(
                Duration.ofSeconds(1), Duration.ofMinutes(6), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Policy(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 33))
                .isInstanceOf(IllegalArgumentException.class);
        var policy = new TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Policy(
                Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
        assertThatThrownBy(() -> new TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Snapshot(
                TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Snapshot.SCHEMA_VERSION,
                policy, 1, 0, 0, 0, 0, 0, 0, 1, 2, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TestSuiteStabilityPhysicalAttemptStartCallSupervisor supervisor(
            Duration startTimeout, int capacity) {
        return new TestSuiteStabilityPhysicalAttemptStartCallSupervisor(
                new TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Policy(
                        Duration.ofSeconds(1), startTimeout, capacity));
    }

    private static TestSuiteStabilityPhysicalAttemptStartAuthority authority(
            java.util.function.Supplier<
                    TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor> descriptor,
            Function<TestSuiteStabilityPhysicalAttemptStartCommand,
                    TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation> start) {
        return new TestSuiteStabilityPhysicalAttemptStartAuthority() {
            @Override
            public Descriptor descriptor() {
                return descriptor.get();
            }

            @Override
            public TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation start(
                    TestSuiteStabilityPhysicalAttemptStartCommand command) {
                return start.apply(command);
            }
        };
    }

    private static TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor descriptor() {
        return new TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor(
                TestSuiteStabilityPhysicalAttemptStartAuthority.Descriptor.SCHEMA_VERSION,
                "isolated-runtime-a", "isolated-runtime-a.generation-7",
                "isolated-runtime-a.key-3", true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofSeconds(30));
    }

    private static TestSuiteStabilityPhysicalAttemptStartCommand command() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TestSuiteStabilityJobLease lease = new TestSuiteStabilityJobLease(
                "stability-job-" + "1".repeat(64), "tenant-a", "test",
                fingerprint('2'), "worker-a", 7, NOW.plusSeconds(60));
        TestSuiteStabilityPhysicalAttemptIdentity identity =
                TestSuiteStabilityPhysicalAttemptIdentity.create(
                        mapper, lease, fingerprint('3'), "isolated-runtime-a",
                        "isolated-runtime-a.generation-7",
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS);
        return TestSuiteStabilityPhysicalAttemptStartCommand.create(
                mapper, identity, "stability-envelope-" + "4".repeat(64),
                fingerprint('4'), NOW, NOW.plusSeconds(30), challenge());
    }

    private static TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation attestation(
            TestSuiteStabilityPhysicalAttemptStartCommand command) {
        TestSuiteStabilityPhysicalAttemptStartReceipt receipt =
                new TestSuiteStabilityPhysicalAttemptStartReceipt(
                        TestSuiteStabilityPhysicalAttemptStartReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(),
                        command.identity().providerId(), command.identity().deploymentId(),
                        command.identity().attemptId(),
                        command.identity().identityFingerprint(),
                        command.identity().leaseEpoch(), 11,
                        command.identity().isolationMode(),
                        TestSuiteStabilityPhysicalAttemptStartReceipt.Outcome.STARTED,
                        fingerprint('5'), fingerprint('6'), NOW.plusSeconds(2));
        return new TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation(
                TestSuiteStabilityPhysicalAttemptStartReceipt.Attestation.SCHEMA_VERSION,
                receipt, "isolated-runtime-a.key-3",
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[64]));
    }

    private static void assertInvocation(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            TestSuiteStabilityPhysicalAttemptStartCallSupervisor.CallType callType,
            TestSuiteStabilityPhysicalAttemptStartCallSupervisor.Disposition disposition) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(
                        TestSuiteStabilityPhysicalAttemptStartCallSupervisor
                                .InvocationException.class,
                        failure -> {
                            assertThat(failure.callType()).isEqualTo(callType);
                            assertThat(failure.disposition()).isEqualTo(disposition);
                        });
    }

    private static void awaitSnapshot(
            TestSuiteStabilityPhysicalAttemptStartCallSupervisor supervisor,
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

    private static String challenge() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
