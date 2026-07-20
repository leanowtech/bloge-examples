package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootSignerCallSupervisorTest {

    private static final Instant NOW = Instant.parse("2026-07-21T01:00:00Z");
    private static final String REQUEST_ID = "sha256:" + "c".repeat(64);
    private static final String MATERIAL_FINGERPRINT = "sha256:" + "d".repeat(64);
    private static final ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest
            REQUEST = new ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest(
            ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest.SCHEMA_VERSION,
            REQUEST_ID, "ceremony-supervisor", ExternalSequenceAnchorBootstrapRootSigningAuthority
            .Role.AUTHORIZING_ROOT, "bootstrap-roots", 1L, "root-1", "root-key-1",
            MATERIAL_FINGERPRINT, NOW);

    private final List<ExternalSequenceAnchorBootstrapRootSignerCallSupervisor> supervisors =
            new ArrayList<>();
    private final List<AtomicBoolean> releases = new ArrayList<>();

    @AfterEach
    void tearDown() {
        releases.forEach(release -> release.set(true));
        supervisors.forEach(ExternalSequenceAnchorBootstrapRootSignerCallSupervisor::close);
    }

    @Test
    void successfulDescriptorAndSignatureCallsHaveAConsistentSnapshot() {
        var supervisor = supervisor(Duration.ofSeconds(1), Duration.ofSeconds(1), 2);
        var authority = authority(this::response);

        assertThat(supervisor.descriptor(authority).authorityId()).isEqualTo("root-1");
        assertThat(supervisor.sign(authority, REQUEST)).isEqualTo(response(REQUEST));

        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.schemaVersion()).isEqualTo(
                    ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Snapshot
                            .SCHEMA_VERSION);
            assertThat(snapshot.acceptedCalls()).isEqualTo(2L);
            assertThat(snapshot.completedCalls()).isEqualTo(2L);
            assertThat(snapshot.failedCalls()).isZero();
            assertThat(snapshot.timedOutCalls()).isZero();
            assertThat(snapshot.activeCalls()).isZero();
            assertThat(snapshot.lingeringCalls()).isZero();
        });
    }

    @Test
    void adapterFailureIsClassifiedWithoutLeakingProviderDiagnostics() {
        var supervisor = supervisor(Duration.ofSeconds(1), Duration.ofSeconds(1), 1);
        var authority = authority(request -> {
            throw new IllegalStateException("secret-provider-endpoint-and-token");
        });

        assertThatThrownBy(() -> supervisor.sign(authority, REQUEST))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                                .InvocationException.class,
                        failure -> {
                            assertThat(failure.callType()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                                            .CallType.SIGNATURE);
                            assertThat(failure.disposition()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                                            .InvocationDisposition.UNAVAILABLE);
                        })
                .hasMessageNotContaining("secret-provider");
        assertThat(supervisor.snapshot().failedCalls()).isEqualTo(1L);
    }

    @Test
    void wallClockTimeoutInterruptsACooperativeAdapterAndReturnsWithinTheBound()
            throws Exception {
        var supervisor = supervisor(Duration.ofSeconds(1), Duration.ofMillis(100), 1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        var authority = authority(request -> {
            entered.countDown();
            try {
                Thread.sleep(10_000L);
                return response(request);
            } catch (InterruptedException failure) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", failure);
            }
        });

        long startedAt = System.nanoTime();
        var failure = invocationFailure(() -> supervisor.sign(authority, REQUEST));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(entered.getCount()).isZero();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(failure.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                        .InvocationDisposition.TIMED_OUT);
        await(() -> supervisor.snapshot().activeCalls() == 0L);
        assertThat(interrupted).isTrue();
        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.timedOutCalls()).isEqualTo(1L);
            assertThat(snapshot.lingeringCalls()).isZero();
        });
    }

    @Test
    void interruptIgnoringAdapterConsumesOneBoundedSlotAndNewWorkIsNeverQueued()
            throws Exception {
        var supervisor = supervisor(Duration.ofSeconds(1), Duration.ofMillis(250), 1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = release();
        var blocked = authority(request -> nonCooperativeResponse(request, entered, release));

        var timeout = invocationFailure(() -> supervisor.sign(blocked, REQUEST));

        assertThat(timeout.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                        .InvocationDisposition.TIMED_OUT);
        assertThat(entered.getCount()).isZero();
        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.activeCalls()).isEqualTo(1L);
            assertThat(snapshot.lingeringCalls()).isEqualTo(1L);
        });
        assertThat(invocationFailure(() -> supervisor.descriptor(authority(this::response)))
                .disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                        .InvocationDisposition.SATURATED);
        assertThat(supervisor.snapshot().saturatedCalls()).isEqualTo(1L);

        release.set(true);
        await(() -> supervisor.snapshot().activeCalls() == 0L);
        assertThat(supervisor.descriptor(authority(this::response)).authorityId())
                .isEqualTo("root-1");
    }

    @Test
    void callerInterruptionIsPreservedWhileTheAdapterRemainsBounded() throws Exception {
        var supervisor = supervisor(Duration.ofSeconds(5), Duration.ofSeconds(5), 1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = release();
        var blocked = authority(request -> nonCooperativeResponse(request, entered, release));
        AtomicReference<ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                .InvocationException> observed = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                supervisor.sign(blocked, REQUEST);
            } catch (ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.InvocationException
                     failure) {
                observed.set(failure);
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            }
        });

        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        caller.interrupt();
        caller.join(2_000L);

        assertThat(caller.isAlive()).isFalse();
        assertThat(observed.get()).isNotNull().satisfies(failure ->
                assertThat(failure.disposition()).isEqualTo(
                        ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                                .InvocationDisposition.CALLER_INTERRUPTED));
        assertThat(interruptPreserved).isTrue();
        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.callerInterruptedCalls()).isEqualTo(1L);
            assertThat(snapshot.lingeringCalls()).isEqualTo(1L);
        });

        release.set(true);
        await(() -> supervisor.snapshot().activeCalls() == 0L);
    }

    @Test
    void closeDoesNotWaitForAnUncooperativeAdapterAndRejectsNewCalls() throws Exception {
        var supervisor = supervisor(Duration.ofSeconds(5), Duration.ofSeconds(5), 1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = release();
        var blocked = authority(request -> nonCooperativeResponse(request, entered, release));
        Thread caller = Thread.ofPlatform().start(() -> supervisor.sign(blocked, REQUEST));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        long startedAt = System.nanoTime();
        supervisor.close();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
        assertThat(invocationFailure(() -> supervisor.descriptor(authority(this::response)))
                .disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                        .InvocationDisposition.CLOSED);
        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.closed()).isTrue();
            assertThat(snapshot.activeCalls()).isEqualTo(1L);
            assertThat(snapshot.lingeringCalls()).isEqualTo(1L);
            assertThat(snapshot.closedCalls()).isEqualTo(1L);
        });

        release.set(true);
        caller.join(2_000L);
        assertThat(caller.isAlive()).isFalse();
    }

    @Test
    void concurrentFastCallsNeverExposeAnImpossibleSnapshot() throws Exception {
        var supervisor = supervisor(Duration.ofSeconds(1), Duration.ofSeconds(1), 8);
        var authority = authority(this::response);
        try (var callers = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(ignored -> callers.submit(() -> {
                        for (int index = 0; index < 250; index++) {
                            supervisor.sign(authority, REQUEST);
                            supervisor.snapshot();
                        }
                    }))
                    .toList();
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.acceptedCalls()).isEqualTo(1_000L);
            assertThat(snapshot.completedCalls()).isEqualTo(1_000L);
            assertThat(snapshot.activeCalls()).isZero();
        });

        CountDownLatch blockedCallsEntered = new CountDownLatch(4);
        AtomicBoolean release = release();
        var blocked = authority(request ->
                nonCooperativeResponse(request, blockedCallsEntered, release));
        try (var callers = Executors.newFixedThreadPool(4)) {
            var futures = java.util.stream.IntStream.range(0, 4)
                    .mapToObj(ignored -> callers.submit(() ->
                            invocationFailure(() -> supervisor.sign(blocked, REQUEST))))
                    .toList();
            assertThat(blockedCallsEntered.await(2, TimeUnit.SECONDS)).isTrue();
            while (futures.stream().anyMatch(future -> !future.isDone())) {
                supervisor.snapshot();
                Thread.sleep(1L);
            }
            for (var future : futures) {
                assertThat(future.get(2, TimeUnit.SECONDS).disposition()).isEqualTo(
                        ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                                .InvocationDisposition.TIMED_OUT);
            }
        }
        release.set(true);
        await(() -> supervisor.snapshot().activeCalls() == 0L);
        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.acceptedCalls()).isEqualTo(1_004L);
            assertThat(snapshot.timedOutCalls()).isEqualTo(4L);
            assertThat(snapshot.lingeringCalls()).isZero();
        });
    }

    @Test
    void policyRejectsUnboundedOrSubMillisecondLimits() {
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                .Policy(Duration.ofMillis(99), Duration.ofSeconds(1),
                Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                .Policy(Duration.ofMillis(99), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                .Policy(Duration.ofSeconds(1), Duration.ofSeconds(301), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                .Policy(Duration.ofNanos(100_000_001L), Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                .Policy(Duration.ofSeconds(1), Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor
                .Policy(Duration.ofSeconds(1), Duration.ofSeconds(1), 33))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ExternalSequenceAnchorBootstrapRootSignerCallSupervisor supervisor(
            Duration descriptorTimeout, Duration signatureTimeout, int capacity) {
        var result = new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor(
                new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy(
                        descriptorTimeout, signatureTimeout, capacity));
        supervisors.add(result);
        return result;
    }

    private AtomicBoolean release() {
        AtomicBoolean result = new AtomicBoolean();
        releases.add(result);
        return result;
    }

    private ExternalSequenceAnchorBootstrapRootSigningAuthority authority(
            Function<ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest,
                    ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureResponse>
                    signer) {
        try {
            var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            var descriptor = new ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor(
                    ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor.SCHEMA_VERSION,
                    "root-1", "root-key-1", "Ed25519",
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
            return new ExternalSequenceAnchorBootstrapRootSigningAuthority() {
                @Override
                public Descriptor descriptor() {
                    return descriptor;
                }

                @Override
                public SignatureResponse sign(SignatureRequest request) {
                    return signer.apply(request);
                }
            };
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureResponse response(
            ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest request) {
        return new ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureResponse(
                ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureResponse
                        .SCHEMA_VERSION,
                request.requestId(), request.authorityId(), request.keyId(), "Ed25519",
                request.materialFingerprint(), request.issuedAt(),
                Base64.getEncoder().encodeToString(new byte[64]));
    }

    private ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureResponse
            nonCooperativeResponse(
            ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest request,
            CountDownLatch entered,
            AtomicBoolean release) {
        entered.countDown();
        while (!release.get()) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ignored) {
                // The test adapter deliberately models a provider call that ignores interrupt.
            }
        }
        return response(request);
    }

    private static ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.InvocationException
            invocationFailure(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected signer invocation to fail");
        } catch (ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.InvocationException
                 failure) {
            return failure;
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
