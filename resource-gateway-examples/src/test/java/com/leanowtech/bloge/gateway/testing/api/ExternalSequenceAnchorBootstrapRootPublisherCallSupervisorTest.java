package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootPublisherCallSupervisorTest {

    private static final Instant NOW = Instant.parse("2026-07-21T02:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final List<ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor> supervisors =
            new ArrayList<>();
    private final List<AtomicBoolean> releases = new ArrayList<>();

    @AfterEach
    void tearDown() {
        releases.forEach(release -> release.set(true));
        supervisors.forEach(
                ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor::close);
    }

    @Test
    void successfulPublicationHasConsistentCapacityCounters() throws Exception {
        var request = request();
        var supervisor = supervisor(Duration.ofSeconds(1), 1);

        var receipt = supervisor.publish(publisher(this::receipt), request);

        assertThat(receipt.publicationId()).isEqualTo(request.publicationId());
        assertThat(supervisor.minimumLeaseDurationSeconds()).isEqualTo(3L);
        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.acceptedCalls()).isOne();
            assertThat(snapshot.completedCalls()).isOne();
            assertThat(snapshot.unavailableCalls()).isZero();
            assertThat(snapshot.activeCalls()).isZero();
            assertThat(snapshot.lingeringCalls()).isZero();
        });
    }

    @Test
    void boundedPublisherReasonsSurviveWithoutProviderDiagnostics() throws Exception {
        var request = request();
        var supervisor = supervisor(Duration.ofSeconds(1), 1);

        for (var reason : ExternalSequenceAnchorBootstrapRootPublisher.FailureReason.values()) {
            var failure = invocationFailure(() -> supervisor.publish(publisher(ignored -> {
                throw new ExternalSequenceAnchorBootstrapRootPublisher.PublisherException(
                        reason);
            }), request));
            var expected = switch (reason) {
                case UNAVAILABLE -> ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                        .InvocationDisposition.UNAVAILABLE;
                case INVALID_RESPONSE ->
                        ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                                .InvocationDisposition.INVALID_RESPONSE;
                case AUTHENTICATED_CONFLICT ->
                        ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                                .InvocationDisposition.AUTHENTICATED_CONFLICT;
            };
            assertThat(failure.disposition()).isEqualTo(expected);
            assertThat(failure)
                    .hasMessageNotContaining("endpoint")
                    .hasMessageNotContaining(request.publicationId())
                    .hasMessageNotContaining(request.bundleFingerprint());
        }
        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.unavailableCalls()).isOne();
            assertThat(snapshot.invalidResponseCalls()).isOne();
            assertThat(snapshot.conflictCalls()).isOne();
        });
    }

    @Test
    void wallClockTimeoutInterruptsCooperativePublisherWithinBound() throws Exception {
        var request = request();
        var supervisor = supervisor(Duration.ofMillis(100), 1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        var blocked = publisher(value -> {
            entered.countDown();
            try {
                Thread.sleep(10_000L);
                return receipt(value);
            } catch (InterruptedException failure) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("provider-secret", failure);
            }
        });

        long started = System.nanoTime();
        var failure = invocationFailure(() -> supervisor.publish(blocked, request));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(entered.getCount()).isZero();
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(failure.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                        .InvocationDisposition.TIMED_OUT);
        await(() -> supervisor.snapshot().activeCalls() == 0L);
        assertThat(interrupted).isTrue();
        assertThat(supervisor.snapshot().lingeringCalls()).isZero();
    }

    @Test
    void interruptIgnoringPublisherConsumesOneSlotAndNeverBuildsAQueue()
            throws Exception {
        var request = request();
        var supervisor = supervisor(Duration.ofMillis(100), 1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = release();
        var blocked = publisher(value -> nonCooperativeReceipt(
                value, entered, release));

        var timeout = invocationFailure(() -> supervisor.publish(blocked, request));
        var saturated = invocationFailure(() -> supervisor.publish(
                publisher(this::receipt), request));

        assertThat(timeout.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                        .InvocationDisposition.TIMED_OUT);
        assertThat(saturated.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                        .InvocationDisposition.SATURATED);
        assertThat(supervisor.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.activeCalls()).isOne();
            assertThat(snapshot.lingeringCalls()).isOne();
            assertThat(snapshot.saturatedCalls()).isOne();
        });

        release.set(true);
        await(() -> supervisor.snapshot().activeCalls() == 0L);
        assertThat(supervisor.publish(publisher(this::receipt), request))
                .isEqualTo(receipt(request));
    }

    @Test
    void closeReturnsWithoutWaitingForUncooperativePublisher() throws Exception {
        var request = request();
        var supervisor = supervisor(Duration.ofSeconds(5), 1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean release = release();
        var blocked = publisher(value -> nonCooperativeReceipt(
                value, entered, release));
        Thread caller = Thread.ofPlatform().start(() -> supervisor.publish(blocked, request));
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        supervisor.close();

        assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(Duration.ofSeconds(1));
        assertThat(invocationFailure(() -> supervisor.publish(
                publisher(this::receipt), request)).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                        .InvocationDisposition.CLOSED);
        assertThat(supervisor.snapshot().closed()).isTrue();

        release.set(true);
        caller.join(2_000L);
        assertThat(caller.isAlive()).isFalse();
    }

    @Test
    void policyRejectsUnboundedOrSubMillisecondLimits() {
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                .Policy(Duration.ofMillis(99), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                .Policy(Duration.ofSeconds(241), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                .Policy(Duration.ofNanos(100_000_001L), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                .Policy(Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor supervisor(
            Duration timeout,
            int capacity) {
        var result = new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor(
                new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Policy(
                        timeout, capacity));
        supervisors.add(result);
        return result;
    }

    private AtomicBoolean release() {
        AtomicBoolean result = new AtomicBoolean();
        releases.add(result);
        return result;
    }

    private static ExternalSequenceAnchorBootstrapRootPublisher publisher(
            Function<ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest,
                    ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt>
                    operation) {
        return new ExternalSequenceAnchorBootstrapRootPublisher() {
            @Override
            public ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt
                    publish(
                    ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest
                            request) {
                return operation.apply(request);
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor(Descriptor.SCHEMA_VERSION, true, true, true,
                        true, true, true, 4 * 1024 * 1024);
            }

            @Override
            public Snapshot snapshot() {
                return new Snapshot(Snapshot.SCHEMA_VERSION, true, "HEALTHY",
                        0L, 0L, 0L, 0L, null);
            }
        };
    }

    private ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt receipt(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest request) {
        return new ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt(
                ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt
                        .SCHEMA_VERSION,
                ExternalSequenceAnchorBootstrapRootPublicationOutbox
                        .PublicationReceiptStatus.PUBLISHED,
                request.publicationId(), request.sequence(), request.bundleFingerprint(),
                request.headMaterialFingerprint(), NOW);
    }

    private ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt
            nonCooperativeReceipt(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest request,
            CountDownLatch entered,
            AtomicBoolean release) {
        entered.countDown();
        while (!release.get()) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ignored) {
                // Deliberately models a provider that ignores local interruption.
            }
        }
        return receipt(request);
    }

    private ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.InvocationException
            invocationFailure(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Expected publisher invocation failure");
        } catch (ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor
                 .InvocationException failure) {
            return failure;
        }
    }

    private ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest request()
            throws Exception {
        var rootPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var root = new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                "root-a", "root-key-a",
                Base64.getEncoder().encodeToString(rootPair.getPublic().getEncoded()),
                NOW.minusSeconds(60), NOW.plusSeconds(3600), true, false);
        String predecessor = "sha256:" + "a".repeat(64);
        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                "notary-bootstrap-roots", 1L, predecessor, "stability-fleet",
                "bootstrap.example", 1, 0, List.of(root),
                "sha256:" + "b".repeat(64), NOW, NOW, NOW.plusSeconds(3600));
        String materialFingerprint = ProtocolFingerprint.of(objectMapper, material);
        var signature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                "root-a", "root-key-a", "Ed25519", NOW,
                Base64.getEncoder().encodeToString(new byte[64]));
        var transition = new ExternalSequenceAnchorBootstrapRootTransition(
                ExternalSequenceAnchorBootstrapRootTransition.SCHEMA_VERSION,
                material, materialFingerprint, List.of(signature), List.of(signature));
        var bundle = new ExternalSequenceAnchorBootstrapRootBundle(
                ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION,
                predecessor, List.of(transition), materialFingerprint);
        String bundleFingerprint = ProtocolFingerprint.of(objectMapper, bundle);
        return new ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest(
                ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest
                        .SCHEMA_VERSION,
                "root-pub-" + bundleFingerprint.substring("sha256:".length()),
                "stability-fleet", "notary-bootstrap-roots", "ceremony-supervisor", 1L,
                predecessor, bundle, bundleFingerprint, materialFingerprint);
    }

    private static void await(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
