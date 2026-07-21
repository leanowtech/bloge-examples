package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCertificateRotationRuntimeTest {

    private static final String TARGET =
            ControlPlaneCertificateRotationTargets.BOOTSTRAP_ROOT_PUBLISHER;
    private static final String POLICY = "sha256:" + "c".repeat(64);

    @TempDir
    Path temporaryDirectory;

    @Test
    void productRuntimeStagesAndActivatesCatalogMaterialOnAStableRealTlsClient()
            throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "runtime-current");
        var successor = current.rotateClient(temporaryDirectory, "runtime-next");
        var objectMapper = new ObjectMapper().findAndRegisterModules();
        var fingerprinter = new ControlPlaneCertificateSettingsFingerprint(objectMapper);
        var currentSettings = settings(current);
        var successorSettings = settings(successor);
        String currentFingerprint = fingerprinter.fingerprint(currentSettings);
        String successorFingerprint = fingerprinter.fingerprint(successorSettings);
        Instant now = Instant.now();
        MutableClock clock = new MutableClock(now);
        var runtime = new ControlPlaneCertificateRotationRuntime(
                enabledProperties(), Map.of(TARGET, 41L), verifiedTrust(),
                (targetId, generation, materialId) ->
                        new ControlPlaneCertificateRotationMaterialSource.ResolvedMaterial(
                                successorFingerprint, successorSettings),
                reference -> RecoveryFleetPublicationTlsFixture.password(),
                fingerprinter, clock);

        RecoveryFleetPublicationTransport transport = runtime.transport(
                TARGET, transportProperties(current));
        var client = transport.client(Duration.ofSeconds(2));
        AtomicReference<String> peer = new AtomicReference<>();

        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(current, peer)) {
            assertThat(send(client, server.uri())).isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-runtime-current");

            var result = runtime.apply(event(41, currentFingerprint,
                    successorFingerprint, now.plusSeconds(10)));

            assertThat(result.status()).isEqualTo(
                    ControlPlaneCertificateRotationController.ApplyStatus.APPLIED);
            assertThat(result.pendingGeneration()).isEqualTo(42);
            assertThat(runtime.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.enabled()).isTrue();
                assertThat(descriptor.ready()).isTrue();
                assertThat(descriptor.inventoriedTargetCount()).isEqualTo(1);
                assertThat(descriptor.registeredTargetCount()).isEqualTo(1);
                assertThat(descriptor.synchronizedState()).isTrue();
            });

            clock.advance(Duration.ofSeconds(10));
            assertThat(send(client, server.uri())).isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-runtime-next");
        }
    }

    @Test
    void enabledRuntimeRejectsUnknownUninventoriedDuplicateAndUnboundTargets() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "runtime-invalid");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var fingerprinter = new ControlPlaneCertificateSettingsFingerprint(mapper);
        var runtime = new ControlPlaneCertificateRotationRuntime(
                enabledProperties(), Map.of(TARGET, 1L), verifiedTrust(),
                (targetId, generation, materialId) -> {
                    throw new AssertionError("material resolution must not run");
                }, reference -> RecoveryFleetPublicationTlsFixture.password(),
                fingerprinter, Clock.systemUTC());

        assertThatThrownBy(() -> runtime.transport(
                ControlPlaneCertificateRotationTargets.RECOVERY_FLEET_INVENTORY,
                transportProperties(current))).isInstanceOf(IllegalArgumentException.class);
        runtime.transport(TARGET, transportProperties(current));
        assertThatThrownBy(() -> runtime.transport(TARGET, transportProperties(current)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runtime.apply(eventForTarget("unknown.target", 1,
                "sha256:" + "a".repeat(64), "sha256:" + "b".repeat(64),
                Instant.now().plusSeconds(10))).status()).isEqualTo(
                ControlPlaneCertificateRotationController.ApplyStatus.TARGET_UNKNOWN);
    }

    @Test
    void disabledRuntimePreservesStaticCompatibilityTransport() throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "runtime-disabled");
        var mapper = new ObjectMapper().findAndRegisterModules();
        var runtime = new ControlPlaneCertificateRotationRuntime(
                ControlPlaneCertificateRotationRuntimeProperties.disabled(), Map.of(),
                ControlPlaneCertificateRotationTrustStore.unavailable(),
                (targetId, generation, materialId) -> {
                    throw new AssertionError("disabled runtime must not resolve material");
                }, reference -> RecoveryFleetPublicationTlsFixture.password(),
                new ControlPlaneCertificateSettingsFingerprint(mapper), Clock.systemUTC());

        RecoveryFleetPublicationTransport transport = runtime.transport(
                TARGET, transportProperties(current));

        assertThat(transport).isInstanceOf(PinnedMutualTlsRecoveryFleetPublicationTransport.class);
        assertThat(runtime.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.enabled()).isFalse();
            assertThat(descriptor.ready()).isTrue();
            assertThat(descriptor.registeredTargetCount()).isZero();
        });
        assertThat(runtime.apply(event(1, "sha256:" + "a".repeat(64),
                "sha256:" + "b".repeat(64), Instant.now().plusSeconds(10))).status())
                .isEqualTo(ControlPlaneCertificateRotationController.ApplyStatus
                        .AUTHORIZATION_REJECTED);
    }

    private static String send(java.net.http.HttpClient client, java.net.URI uri)
            throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET()
                        .timeout(Duration.ofSeconds(2)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    private static ControlPlaneCertificateRotationEvent event(
            long activeGeneration,
            String currentFingerprint,
            String successorFingerprint,
            Instant activateAt) {
        return eventForTarget(TARGET, activeGeneration, currentFingerprint,
                successorFingerprint, activateAt);
    }

    private static ControlPlaneCertificateRotationEvent eventForTarget(
            String targetId,
            long activeGeneration,
            String currentFingerprint,
            String successorFingerprint,
            Instant activateAt) {
        Instant issuedAt = activateAt.minusSeconds(20);
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "enterprise-pki", "rotation-%03d".formatted(activeGeneration + 1),
                "rg-staging", targetId, activeGeneration + 1, currentFingerprint,
                "candidate-next", successorFingerprint, POLICY, issuedAt, issuedAt,
                activateAt, activateAt.plusSeconds(300));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, material,
                "sha256:" + "e".repeat(64), List.of(
                new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                        "authority-a", "key-a", "Ed25519", issuedAt,
                        Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private static ControlPlaneCertificateRotationRuntimeProperties enabledProperties() {
        return new ControlPlaneCertificateRotationRuntimeProperties(
                true, true, "rg-staging", "enterprise-pki", POLICY, 1,
                "[{}]", 0L, 3_600L, "{\"" + TARGET + "\":41}", "[{}]");
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

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
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
            return instant;
        }
    }
}
