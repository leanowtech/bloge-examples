package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseControlPlaneCertificateRotationConvergenceRepository;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseControlPlaneCertificateRotationFloor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.AfterEach;
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
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateRotationProductConvergenceTest {

    private static final String TARGET =
            ControlPlaneCertificateRotationTargets.BOOTSTRAP_ROOT_PUBLISHER;
    private static final String POLICY_FINGERPRINT = "sha256:" + "c".repeat(64);
    private static final String ARTIFACT_FINGERPRINT = "sha256:" + "d".repeat(64);

    @TempDir
    Path temporaryDirectory;

    private TestRuntimeDatabase database;

    @AfterEach
    void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void twoProductRuntimesRequireFinalActiveConvergenceBeforeRealTlsServing()
            throws Exception {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:certificate-product-convergence-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        var fingerprinter = new ControlPlaneCertificateSettingsFingerprint(objectMapper);
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "product-convergence-current");
        var successor = current.rotateClient(
                temporaryDirectory, "product-convergence-successor");
        var currentSettings = settings(current);
        var successorSettings = settings(successor);
        String currentFingerprint = fingerprinter.fingerprint(currentSettings);
        String successorFingerprint = fingerprinter.fingerprint(successorSettings);
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant activateAt = now.plusMillis(1_500);
        MutableClock clockA = new MutableClock(now);
        MutableClock clockB = new MutableClock(now);
        var inventory = new ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation(
                ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation.SCHEMA_VERSION,
                true, "DEPLOYMENT_SIGNED", 11, "sha256:" + "e".repeat(64),
                "sha256:" + "f".repeat(64), now.plusSeconds(3_600));
        var policyA = policy("replica-a", inventory);
        var policyB = policy("replica-b", inventory);
        var monitorA = monitor(policyA, objectMapper, clockA);
        var monitorB = monitor(policyB, objectMapper, clockB);

        try (monitorA; monitorB) {
            ControlPlaneCertificateRotationRuntime runtimeA = runtime(
                    objectMapper, fingerprinter, current, successorSettings,
                    successorFingerprint, monitorA, clockA);
            ControlPlaneCertificateRotationRuntime runtimeB = runtime(
                    objectMapper, fingerprinter, current, successorSettings,
                    successorFingerprint, monitorB, clockB);
            RecoveryFleetPublicationTransport transportA = runtimeA.transport(
                    TARGET, transportProperties(current));
            RecoveryFleetPublicationTransport transportB = runtimeB.transport(
                    TARGET, transportProperties(current));
            ControlPlaneCertificateRotationEvent event = event(
                    currentFingerprint, successorFingerprint, now, activateAt);

            assertThat(runtimeA.apply(event).pendingGeneration()).isEqualTo(2);
            assertThat(runtimeB.apply(event).pendingGeneration()).isEqualTo(2);

            long waitMillis = Math.max(0, Duration.between(
                    Instant.now(), activateAt.plusMillis(150)).toMillis());
            Thread.sleep(waitMillis);
            clockA.advance(Duration.ofSeconds(2));
            clockB.advance(Duration.ofSeconds(2));

            monitorA.refreshNow();
            assertThat(runtimeA.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.replicaConvergenceProven()).isFalse();
                assertThat(descriptor.servingReady()).isFalse();
                assertThat(descriptor.productionReady()).isFalse();
            });
            assertThatThrownBy(() -> transportA.client(Duration.ofSeconds(1))
                    .sendAsync(HttpRequest.newBuilder(java.net.URI.create("https://localhost"))
                                    .GET().build(), HttpResponse.BodyHandlers.discarding())
                    .join()).isInstanceOfAny(IllegalStateException.class,
                            CompletionException.class)
                    .hasRootCauseMessage(
                            "Control-plane client identity is not fleet-admitted");

            monitorB.refreshNow();
            monitorA.refreshNow();

            assertThat(runtimeA.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.ready()).isTrue();
                assertThat(descriptor.replicaConvergenceProven()).isTrue();
                assertThat(descriptor.servingReady()).isTrue();
                assertThat(descriptor.productionReady()).isFalse();
            });
            assertThat(runtimeB.descriptor().replicaConvergenceProven()).isTrue();
            AtomicReference<String> peer = new AtomicReference<>();
            try (var server = RecoveryFleetPublicationTlsFixture.startPublication(
                    current, peer)) {
                assertThat(send(transportA, server.uri())).isEqualTo("publication");
                assertThat(peer.get()).contains(
                        "CN=recovery-client-product-convergence-successor");
                assertThat(send(transportB, server.uri())).isEqualTo("publication");
            }
        }
    }

    private ControlPlaneCertificateRotationConvergenceMonitor monitor(
            ControlPlaneCertificateRotationFleetPolicy policy,
            ObjectMapper objectMapper,
            Clock clock) {
        var repository = new DatabaseControlPlaneCertificateRotationConvergenceRepository(
                database.jdbc(), objectMapper, policy, database.transactionManager());
        repository.init();
        return new ControlPlaneCertificateRotationConvergenceMonitor(
                repository, policy, objectMapper, clock, false);
    }

    private ControlPlaneCertificateRotationRuntime runtime(
            ObjectMapper objectMapper,
            ControlPlaneCertificateSettingsFingerprint fingerprinter,
            RecoveryFleetPublicationTlsFixture.Material current,
            PinnedMutualTlsRecoveryFleetPublicationTransport.Settings successorSettings,
            String successorFingerprint,
            ControlPlaneCertificateRotationConvergenceMonitor monitor,
            Clock clock) {
        ControlPlaneCertificateRotationFloorFactory floors = (scope, initialTargets) -> {
            var floor = new DatabaseControlPlaneCertificateRotationFloor(database.jdbc(),
                    objectMapper, scope, initialTargets, database.transactionManager(), monitor);
            floor.init();
            return floor;
        };
        return new ControlPlaneCertificateRotationRuntime(
                enabledProperties(), Map.of(TARGET,
                new ControlPlaneCertificateRotationRuntimeProperties.InitialTargetSpec(
                        1, "initial")), verifiedTrust(),
                (targetId, generation, materialId) ->
                        new ControlPlaneCertificateRotationMaterialSource.ResolvedMaterial(
                                successorFingerprint, successorSettings),
                reference -> RecoveryFleetPublicationTlsFixture.password(),
                fingerprinter, floors, clock, monitor);
    }

    private static ControlPlaneCertificateRotationFleetPolicy policy(
            String instanceId,
            ControlPlaneCertificateRotationFleetPolicy.InventoryAttestation inventory) {
        return new ControlPlaneCertificateRotationFleetPolicy(
                "rg-staging", "fleet-2026-07", instanceId,
                UUID.randomUUID().toString(), ARTIFACT_FINGERPRINT,
                Set.of("replica-a", "replica-b"), "protocol-v1",
                ControlPlaneCertificateRotationFleetPolicy.ActivationMode.ALL_REPLICAS,
                2, Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofHours(1), inventory);
    }

    private static ControlPlaneCertificateRotationRuntimeProperties enabledProperties() {
        return new ControlPlaneCertificateRotationRuntimeProperties(
                true, true, "rg-staging", "enterprise-pki", POLICY_FINGERPRINT, 1,
                "[{}]", 0L, 3_600L, "{\"" + TARGET + "\":1}", "[{}]");
    }

    private static ControlPlaneCertificateRotationEvent event(
            String currentFingerprint,
            String successorFingerprint,
            Instant now,
            Instant activateAt) {
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "enterprise-pki", "rotation-002", "rg-staging", TARGET, 2,
                currentFingerprint, "candidate-next", successorFingerprint,
                POLICY_FINGERPRINT, now, now, activateAt, now.plusSeconds(300));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, material,
                "sha256:" + "9".repeat(64), List.of(
                new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                        "authority-a", "key-a", "Ed25519", now,
                        Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private static ControlPlaneCertificateRotationTrustStore verifiedTrust() {
        return new ControlPlaneCertificateRotationTrustStore() {
            @Override
            public Verification verify(
                    ControlPlaneCertificateRotationEvent event,
                    ExpectedBinding expected,
                    Instant observedAt) {
                return new Verification(VerificationStatus.VERIFIED, "VERIFIED",
                        event.material().eventId(), event.materialFingerprint(),
                        event.material().settingsFingerprint(), 1, 1);
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor("", true, "enterprise-pki", 1, 1, 1, 1, Map.of());
            }
        };
    }

    private static RecoveryFleetPublicationTransportProperties transportProperties(
            RecoveryFleetPublicationTlsFixture.Material material) {
        String issuer = PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                material.certificateAuthority());
        return new RecoveryFleetPublicationTransportProperties(
                true, true, material.trustStore().toString(), "test:trust",
                material.clientKeyStore().toString(), "test:client",
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.serverCertificate()), true,
                material.clientCertificate().getSubjectX500Principal().getName(),
                material.clientUriSan(), issuer, material.serverUriSan(), issuer);
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings(
            RecoveryFleetPublicationTlsFixture.Material material) {
        return transportProperties(material).pinnedSettings();
    }

    private static String send(
            RecoveryFleetPublicationTransport transport,
            java.net.URI uri) throws Exception {
        HttpResponse<byte[]> response = transport.client(Duration.ofSeconds(2)).send(
                HttpRequest.newBuilder(uri).GET().timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new String(response.body(), StandardCharsets.UTF_8);
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
