package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityAttemptCancellationCallSupervisorTest {

    private TestSuiteStabilityAttemptCancellationCallSupervisor supervisor;
    private TestSuiteStabilityAttemptCancellationCommand command;

    @BeforeEach
    void setUp() {
        supervisor = new TestSuiteStabilityAttemptCancellationCallSupervisor(
                new TestSuiteStabilityAttemptCancellationCallSupervisor.Policy(
                        Duration.ofMillis(100), Duration.ofMillis(100), 1));
        byte[] challenge = new byte[32];
        java.util.Arrays.fill(challenge, (byte) 7);
        command = TestSuiteStabilityAttemptCancellationCommand.create(
                new ObjectMapper(), "tenant-a", "test",
                "stability-job-" + "1".repeat(64),
                "stability-attempt-" + "2".repeat(64), "worker-a", 1,
                "sha256:" + "3".repeat(64),
                TestSuiteStabilityAttemptCancellationCommand.Reason.CANCELLED,
                Instant.parse("2026-07-22T02:00:00Z"),
                Instant.parse("2026-07-22T02:00:30Z"),
                Base64.getUrlEncoder().withoutPadding().encodeToString(challenge));
    }

    @AfterEach
    void tearDown() {
        supervisor.close();
    }

    @Test
    void returnsDescriptorAndAttestationWithoutInventingTrust() {
        var descriptor = descriptor();
        var attestation = mockAttestation();
        TestSuiteStabilityAttemptCancellationAuthority authority = authority(
                () -> descriptor, ignored -> attestation);

        assertThat(supervisor.descriptor(authority)).isEqualTo(descriptor);
        assertThat(supervisor.cancel(authority, command)).isSameAs(attestation);
        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.acceptedCalls()).isEqualTo(2);
            assertThat(snapshot.completedCalls()).isEqualTo(2);
            assertThat(snapshot.activeCalls()).isZero();
            assertThat(snapshot.lingeringCalls()).isZero();
        });
    }

    @Test
    void timeoutIgnoringInterruptRemainsLingeringAndSaturatesFixedPool() throws Exception {
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        TestSuiteStabilityAttemptCancellationAuthority authority = authority(
                this::descriptor, ignored -> {
                    entered.countDown();
                    while (!release.get()) {
                        Thread.interrupted();
                        Thread.onSpinWait();
                    }
                    return mockAttestation();
                });

        assertThatThrownBy(() -> supervisor.cancel(authority, command))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationCallSupervisor
                                .InvocationException.class,
                        failure -> assertThat(failure.disposition()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationCallSupervisor
                                        .Disposition.TIMED_OUT));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        await(() -> supervisor.snapshot().lingeringCalls() == 1);

        assertThatThrownBy(() -> supervisor.cancel(authority, command))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationCallSupervisor
                                .InvocationException.class,
                        failure -> assertThat(failure.disposition()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationCallSupervisor
                                        .Disposition.SATURATED));

        release.set(true);
        await(() -> supervisor.snapshot().activeCalls() == 0);
        assertThat(supervisor.snapshot().lingeringCalls()).isZero();
    }

    @Test
    void providerFailureIsClosedAndDoesNotLeakDiagnostics() {
        TestSuiteStabilityAttemptCancellationAuthority authority = authority(
                this::descriptor, ignored -> {
                    throw new IllegalStateException("secret-container-id-and-command");
                });

        assertThatThrownBy(() -> supervisor.cancel(authority, command))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationCallSupervisor
                                .InvocationException.class,
                        failure -> {
                            assertThat(failure.disposition()).isEqualTo(
                                    TestSuiteStabilityAttemptCancellationCallSupervisor
                                            .Disposition.UNAVAILABLE);
                            assertThat(failure).hasMessageNotContaining("secret-container");
                            assertThat(failure.getCause()).isNull();
                        });
    }

    @Test
    void callerInterruptionIsPreserved() throws Exception {
        AtomicBoolean release = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean preserved = new AtomicBoolean();
        TestSuiteStabilityAttemptCancellationAuthority authority = authority(
                this::descriptor, ignored -> {
                    entered.countDown();
                    while (!release.get()) {
                        Thread.interrupted();
                        Thread.onSpinWait();
                    }
                    return mockAttestation();
                });
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                supervisor.cancel(authority, command);
            } catch (TestSuiteStabilityAttemptCancellationCallSupervisor.InvocationException ex) {
                preserved.set(Thread.currentThread().isInterrupted()
                        && ex.disposition()
                        == TestSuiteStabilityAttemptCancellationCallSupervisor
                        .Disposition.CALLER_INTERRUPTED);
            }
        });
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();

        caller.interrupt();
        caller.join(1_000L);
        release.set(true);

        assertThat(caller.isAlive()).isFalse();
        assertThat(preserved).isTrue();
    }

    @Test
    void closedSupervisorRejectsWithoutCallingProvider() {
        AtomicBoolean called = new AtomicBoolean();
        TestSuiteStabilityAttemptCancellationAuthority authority = authority(
                this::descriptor, ignored -> {
                    called.set(true);
                    return mockAttestation();
                });
        supervisor.close();

        assertThatThrownBy(() -> supervisor.cancel(authority, command))
                .isInstanceOfSatisfying(
                        TestSuiteStabilityAttemptCancellationCallSupervisor
                                .InvocationException.class,
                        failure -> assertThat(failure.disposition()).isEqualTo(
                                TestSuiteStabilityAttemptCancellationCallSupervisor
                                        .Disposition.CLOSED));
        assertThat(called).isFalse();
    }

    @Test
    void rejectsUnboundedPolicy() {
        assertThatThrownBy(() ->
                new TestSuiteStabilityAttemptCancellationCallSupervisor.Policy(
                        Duration.ofMillis(99), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new TestSuiteStabilityAttemptCancellationCallSupervisor.Policy(
                        Duration.ofSeconds(1), Duration.ofMinutes(6), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new TestSuiteStabilityAttemptCancellationCallSupervisor.Policy(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 33))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private TestSuiteStabilityAttemptCancellationAuthority.Descriptor descriptor() {
        return new TestSuiteStabilityAttemptCancellationAuthority.Descriptor(
                TestSuiteStabilityAttemptCancellationAuthority.Descriptor.SCHEMA_VERSION,
                "attempt-runtime-a", "attempt-runtime-a.generation-7", "key-3", true,
                Set.of(TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS),
                Duration.ofSeconds(30));
    }

    private TestSuiteStabilityAttemptCancellationReceipt.Attestation mockAttestation() {
        TestSuiteStabilityAttemptCancellationReceipt receipt =
                new TestSuiteStabilityAttemptCancellationReceipt(
                        TestSuiteStabilityAttemptCancellationReceipt.SCHEMA_VERSION,
                        command.commandId(), command.commandFingerprint(), "attempt-runtime-a",
                        "attempt-runtime-a.generation-7", command.attemptId(),
                        command.leaseEpoch(), 1,
                        TestSuiteStabilityAttemptCancellationReceipt.IsolationMode.PROCESS,
                        TestSuiteStabilityAttemptCancellationReceipt.Outcome.NOT_FOUND,
                        TestSuiteStabilityAttemptCancellationReceipt.TerminationMode.NONE,
                        "sha256:" + "4".repeat(64), "sha256:" + "5".repeat(64),
                        command.requestedAt().plusSeconds(1));
        return new TestSuiteStabilityAttemptCancellationReceipt.Attestation(
                TestSuiteStabilityAttemptCancellationReceipt.Attestation.SCHEMA_VERSION,
                receipt, "key-3", Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[64]));
    }

    private static TestSuiteStabilityAttemptCancellationAuthority authority(
            ThrowingSupplier<TestSuiteStabilityAttemptCancellationAuthority.Descriptor> descriptor,
            ThrowingFunction<TestSuiteStabilityAttemptCancellationCommand,
                    TestSuiteStabilityAttemptCancellationReceipt.Attestation> cancellation) {
        return new TestSuiteStabilityAttemptCancellationAuthority() {
            @Override
            public Descriptor descriptor() {
                return descriptor.get();
            }

            @Override
            public TestSuiteStabilityAttemptCancellationReceipt.Attestation cancel(
                    TestSuiteStabilityAttemptCancellationCommand command) {
                return cancellation.apply(command);
            }
        };
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }

    @FunctionalInterface
    private interface ThrowingFunction<T, R> {
        R apply(T value);
    }
}
