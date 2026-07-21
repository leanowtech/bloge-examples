package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RotatingControlPlaneHttpTransportTest {

    private static final char[] PASSWORD = "test-transport-password".toCharArray();

    @TempDir
    Path temporaryDirectory;

    @Test
    void stableClientAtomicallyActivatesAPrevalidatedSuccessorIdentity() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-current-client");
        var successor = current.rotateClient(temporaryDirectory, "rotation-next-client");
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var transport = new RotatingControlPlaneHttpTransport(
                41, settings(current), secretResolver(new AtomicInteger()), clock,
                Duration.ofMinutes(5), Duration.ofDays(1));
        var client = transport.client(Duration.ofSeconds(2));
        AtomicReference<String> peer = new AtomicReference<>();

        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(current, peer)) {
            assertThat(send(client, server.uri())).isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-rotation-current-client");

            transport.stage(42, now.plus(Duration.ofMinutes(10)), settings(successor));
            assertThat(transport.activeGeneration()).isEqualTo(41);
            assertThat(transport.pendingGeneration()).hasValue(42);
            assertThat(send(client, server.uri())).isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-rotation-current-client");

            clock.advance(Duration.ofMinutes(10));
            assertThat(send(client, server.uri())).isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-rotation-next-client");
            assertThat(transport.activeGeneration()).isEqualTo(42);
            assertThat(transport.pendingGeneration()).isEmpty();
            assertThat(transport.certificateIdentityBound()).isTrue();
        }
    }

    @Test
    void rollbackFarFutureAndSecondPendingGenerationFailBeforeSecretResolution()
            throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-order-current");
        var successor = current.rotateClient(temporaryDirectory, "rotation-order-next");
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        AtomicInteger resolutions = new AtomicInteger();
        var transport = new RotatingControlPlaneHttpTransport(
                9, settings(current), secretResolver(resolutions), clock,
                Duration.ofMinutes(1), Duration.ofHours(1));
        int initialResolutions = resolutions.get();

        assertThatThrownBy(() -> transport.stage(9, now.plusSeconds(10),
                settings(successor))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.stage(10, now.plus(Duration.ofHours(2)),
                settings(successor))).isInstanceOf(IllegalArgumentException.class);
        assertThat(resolutions).hasValue(initialResolutions);

        transport.stage(10, now.plusSeconds(10), settings(successor));
        int stagedResolutions = resolutions.get();
        assertThatThrownBy(() -> transport.stage(11, now.plusSeconds(20),
                settings(successor))).isInstanceOf(IllegalArgumentException.class);
        assertThat(resolutions).hasValue(stagedResolutions);
        assertThat(transport.activeGeneration()).isEqualTo(9);
        assertThat(transport.pendingGeneration()).hasValue(10);
    }

    @Test
    void invalidSuccessorMaterialCannotDisturbTheActiveGeneration() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-invalid-current");
        var successor = current.rotateClient(temporaryDirectory, "rotation-invalid-next");
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var transport = new RotatingControlPlaneHttpTransport(
                3, settings(current), secretResolver(new AtomicInteger()), clock,
                Duration.ofMinutes(1), Duration.ofHours(1));
        var invalidPolicy = new ControlPlaneCertificateIdentityPolicy(
                "CN=wrong-client", successor.clientUriSan(), Set.of(issuerPin(successor)),
                successor.serverUriSan(), Set.of(issuerPin(successor)));
        var invalidSettings = settings(successor, invalidPolicy);
        AtomicReference<String> peer = new AtomicReference<>();

        assertThatThrownBy(() -> transport.stage(4, now.plusSeconds(10), invalidSettings))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(transport.activeGeneration()).isEqualTo(3);
        assertThat(transport.pendingGeneration()).isEmpty();
        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(current, peer)) {
            assertThat(send(transport.client(Duration.ofSeconds(2)), server.uri()))
                    .isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-rotation-invalid-current");
        }
    }

    @Test
    void slowSuccessorCredentialLoadingDoesNotBlockRequestsOnTheActiveGeneration()
            throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-nonblocking-current");
        var successor = current.rotateClient(
                temporaryDirectory, "rotation-nonblocking-next");
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger resolutions = new AtomicInteger();
        ControlPlaneHttpTransport.SecretResolver resolver = reference -> {
            if (resolutions.incrementAndGet() > 2) {
                loading.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test credential release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test credential load interrupted", interrupted);
                }
            }
            return PASSWORD.clone();
        };
        var transport = new RotatingControlPlaneHttpTransport(
                12, settings(current), resolver, clock,
                Duration.ofMinutes(1), Duration.ofHours(1));
        AtomicReference<String> peer = new AtomicReference<>();

        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(current, peer)) {
            CompletableFuture<Void> staging = CompletableFuture.runAsync(() -> transport.stage(
                    13, now.plus(Duration.ofMinutes(10)), settings(successor)));
            assertThat(loading.await(2, TimeUnit.SECONDS)).isTrue();

            assertThat(send(transport.client(Duration.ofSeconds(2)), server.uri()))
                    .isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-rotation-nonblocking-current");
            assertThat(transport.activeGeneration()).isEqualTo(12);

            release.countDown();
            staging.get(5, TimeUnit.SECONDS);
            assertThat(transport.pendingGeneration()).hasValue(13);
        } finally {
            release.countDown();
        }
    }

    @Test
    void expiredActiveIdentityFailsClosedBeforeARequestCanReachTheHandler()
            throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-expired");
        MutableClock clock = new MutableClock(Instant.now());
        var transport = new RotatingControlPlaneHttpTransport(
                1, settings(current), secretResolver(new AtomicInteger()), clock,
                Duration.ZERO, Duration.ofDays(1));
        var client = transport.client(Duration.ofSeconds(2));

        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(
                current, new AtomicReference<>())) {
            clock.advance(Duration.ofDays(3));

            assertThatThrownBy(() -> send(client, server.uri()))
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessage("Control-plane client identity is unavailable");
            assertThat(server.requests()).isZero();
        }
    }

    @Test
    void rotationRejectsUnboundIdentityPolicyAndInvalidTimingBounds() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-policy");

        assertThatThrownBy(() -> new RotatingControlPlaneHttpTransport(
                1, unboundSettings(current), secretResolver(new AtomicInteger()), Clock.systemUTC(),
                Duration.ZERO, Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RotatingControlPlaneHttpTransport(
                1, settings(current), secretResolver(new AtomicInteger()), Clock.systemUTC(),
                Duration.ofDays(31), Duration.ofDays(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RotatingControlPlaneHttpTransport(
                1, settings(current), secretResolver(new AtomicInteger()), Clock.systemUTC(),
                Duration.ZERO, Duration.ofDays(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void durableRestoreHandlesNearFutureAndAlreadyActiveSuccessors() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-restore-current");
        var successor = current.rotateClient(temporaryDirectory, "rotation-restore-next");
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var pending = new RotatingControlPlaneHttpTransport(
                21, settings(current), secretResolver(new AtomicInteger()), clock,
                Duration.ofMinutes(1), Duration.ofHours(1));

        pending.restorePending(22, now.plusMillis(100), settings(successor));
        assertThat(pending.pendingGeneration()).hasValue(22);
        clock.advance(Duration.ofMillis(100));
        assertThat(pending.activeGeneration()).isEqualTo(22);

        var late = new RotatingControlPlaneHttpTransport(
                21, settings(current), secretResolver(new AtomicInteger()), clock,
                Duration.ofMinutes(1), Duration.ofHours(1));
        late.reconcileActive(22, now.minusSeconds(10), settings(successor));
        assertThat(late.activeGeneration()).isEqualTo(22);
        assertThat(late.pendingGeneration()).isEmpty();
        assertThatThrownBy(() -> late.reconcileActive(
                24, now.minusSeconds(5), settings(successor)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dueSuccessorRemainsPendingUntilTheCachedFleetGateAdmitsIt() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-gated-current");
        var successor = current.rotateClient(temporaryDirectory, "rotation-gated-next");
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        AtomicBoolean admitted = new AtomicBoolean();
        var transport = new RotatingControlPlaneHttpTransport(
                31, settings(current), secretResolver(new AtomicInteger()), clock,
                Duration.ofMinutes(1), Duration.ofHours(1),
                (generation, activateAt) -> admitted.get());

        transport.stage(32, now.plusSeconds(10), settings(successor));
        clock.advance(Duration.ofSeconds(10));

        assertThat(transport.reconcileGeneration()).isEqualTo(31);
        assertThat(transport.pendingGeneration()).hasValue(32);
        admitted.set(true);
        assertThat(transport.reconcileGeneration()).isEqualTo(32);
        assertThat(transport.pendingGeneration()).isEmpty();
    }

    @Test
    void dueDurableReconciliationPreloadsButDoesNotBypassFleetAdmission()
            throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-reconcile-gated-current");
        var successor = current.rotateClient(
                temporaryDirectory, "rotation-reconcile-gated-next");
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        AtomicBoolean admitted = new AtomicBoolean();
        var transport = new RotatingControlPlaneHttpTransport(
                61, settings(current), secretResolver(new AtomicInteger()), clock,
                Duration.ofMinutes(1), Duration.ofHours(1),
                (generation, activateAt) -> admitted.get());

        transport.reconcileActive(62, now.minusSeconds(1), settings(successor));

        assertThat(transport.localActiveGeneration()).isEqualTo(61);
        assertThat(transport.pendingGeneration()).hasValue(62);
        admitted.set(true);
        assertThat(transport.reconcileGeneration()).isEqualTo(62);
    }

    @Test
    void restoredSignedGenerationCannotServeUntilFreshFleetProofIsCached()
            throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-serving-gate");
        AtomicBoolean serving = new AtomicBoolean();
        var gate = new RotatingControlPlaneHttpTransport.ActivationGate() {
            @Override
            public boolean activationPermitted(long generation, Instant activateAt) {
                return false;
            }

            @Override
            public boolean servingPermitted(long generation) {
                return serving.get();
            }
        };
        var transport = new RotatingControlPlaneHttpTransport(
                72, settings(current), secretResolver(new AtomicInteger()), Clock.systemUTC(),
                Duration.ofMinutes(1), Duration.ofHours(1), gate);

        assertThatThrownBy(() -> transport.client(Duration.ofSeconds(1))
                .sendAsync(HttpRequest.newBuilder(java.net.URI.create("https://localhost"))
                                .GET().build(), HttpResponse.BodyHandlers.discarding())
                .join()).hasRootCauseMessage(
                "Control-plane client identity is not fleet-admitted");
        serving.set(true);
        assertThat(transport.activeGeneration()).isEqualTo(72);
    }

    private static String send(java.net.http.HttpClient client, java.net.URI uri)
            throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings(
            RecoveryFleetPublicationTlsFixture.Material material) {
        return settings(material, policy(material));
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings(
            RecoveryFleetPublicationTlsFixture.Material material,
            ControlPlaneCertificateIdentityPolicy policy) {
        return new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                material.trustStore(), "test:trust", material.clientKeyStore(), "test:client",
                Set.of(PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.serverCertificate())), policy);
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport.Settings unboundSettings(
            RecoveryFleetPublicationTlsFixture.Material material) {
        return new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                material.trustStore(), "test:trust", material.clientKeyStore(), "test:client",
                Set.of(PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.serverCertificate())));
    }

    private static ControlPlaneCertificateIdentityPolicy policy(
            RecoveryFleetPublicationTlsFixture.Material material) {
        String issuer = issuerPin(material);
        return new ControlPlaneCertificateIdentityPolicy(
                material.clientCertificate().getSubjectX500Principal().getName(),
                material.clientUriSan(), Set.of(issuer), material.serverUriSan(), Set.of(issuer));
    }

    private static String issuerPin(RecoveryFleetPublicationTlsFixture.Material material) {
        return PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                material.certificateAuthority());
    }

    private static ControlPlaneHttpTransport.SecretResolver secretResolver(
            AtomicInteger resolutions) {
        return reference -> {
            resolutions.incrementAndGet();
            return switch (reference) {
                case "test:trust", "test:client" -> PASSWORD.clone();
                default -> throw new IllegalStateException("unexpected secret reference");
            };
        };
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        private void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
