package com.leanowtech.bloge.gateway.testing.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryFleetPublicationTransportTest {

    private static final char[] PASSWORD = "test-transport-password".toCharArray();

    @TempDir
    private Path temporaryDirectory;

    @Test
    void pinnedMutualTlsAuthenticatesBothPeersAndDoesNotProjectSensitiveMaterial()
            throws Exception {
        RecoveryFleetPublicationTlsFixture.Material material =
                RecoveryFleetPublicationTlsFixture.Material.create(
                        temporaryDirectory, "trusted");
        AtomicReference<String> peer = new AtomicReference<>();
        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(
                material, peer)) {
            ControlPlaneHttpTransport transport = transport(
                    material.clientKeyStore(), material.trustStore(),
                    material.serverCertificate());
            var response = transport.client(Duration.ofSeconds(2)).send(
                    HttpRequest.newBuilder(server.uri()).GET()
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("publication");
            assertThat(peer.get()).contains("CN=recovery-client-trusted");
            assertThat(transport.descriptor()).isEqualTo(
                    new ControlPlaneHttpTransport.Descriptor(
                            ControlPlaneHttpTransport.Descriptor.SCHEMA_VERSION,
                            false, true, true, true));
            assertThat(transport.descriptor().toString())
                    .doesNotContain(temporaryDirectory.toString(), "test:trust", "test:client",
                            PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                                    material.serverCertificate()));
        }
    }

    @Test
    void validPkixChainWithWrongSpkiPinFailsClosed() throws Exception {
        var material = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "pin-mismatch");
        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(
                material, new AtomicReference<>())) {
            var settings = new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                    material.trustStore(), "test:trust", material.clientKeyStore(),
                    "test:client", Set.of("sha256:" + "0".repeat(64)));
            var transport = new PinnedMutualTlsRecoveryFleetPublicationTransport(
                    settings, secretResolver());

            assertThatThrownBy(() -> transport.client(Duration.ofSeconds(2)).send(
                    HttpRequest.newBuilder(server.uri()).GET()
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOfAny(java.io.IOException.class, InterruptedException.class)
                    .hasMessageNotContaining(PASSWORD.toString());
        }
    }

    @Test
    void serverCertificateRotationRequiresAnExplicitOverlappingPinWindow()
            throws Exception {
        var current = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-current");
        var next = current.rotateServer(temporaryDirectory, "rotation-next");
        Set<String> overlappingPins = Set.of(
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        current.serverCertificate()),
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        next.serverCertificate()));
        var transport = new PinnedMutualTlsRecoveryFleetPublicationTransport(
                newSettings(current.trustStore(), "test:trust",
                        current.clientKeyStore(), "test:client", overlappingPins),
                secretResolver());
        AtomicReference<String> currentPeer = new AtomicReference<>();
        AtomicReference<String> nextPeer = new AtomicReference<>();

        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(
                current, currentPeer)) {
            assertThat(transport.client(Duration.ofSeconds(2)).send(
                    HttpRequest.newBuilder(server.uri()).GET()
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body())
                    .isEqualTo("publication");
        }
        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(
                next, nextPeer)) {
            assertThat(transport.client(Duration.ofSeconds(2)).send(
                    HttpRequest.newBuilder(server.uri()).GET()
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body())
                    .isEqualTo("publication");
        }

        assertThat(currentPeer.get()).contains("CN=recovery-client-rotation-current");
        assertThat(nextPeer.get()).isEqualTo(currentPeer.get());
        assertThat(overlappingPins).hasSize(2);
    }

    @Test
    void untrustedClientIdentityFailsEvenWhenServerPinAndTrustAreValid() throws Exception {
        var serverMaterial = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "server-domain");
        var rogueMaterial = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rogue-domain");
        try (var server = RecoveryFleetPublicationTlsFixture.startPublication(
                serverMaterial, new AtomicReference<>())) {
            var transport = transport(rogueMaterial.clientKeyStore(),
                    serverMaterial.trustStore(), serverMaterial.serverCertificate());

            assertThatThrownBy(() -> transport.client(Duration.ofSeconds(2)).send(
                    HttpRequest.newBuilder(server.uri()).GET()
                            .timeout(Duration.ofSeconds(2)).build(),
                    HttpResponse.BodyHandlers.discarding()))
                    .isInstanceOfAny(java.io.IOException.class, InterruptedException.class);
        }
    }

    @Test
    void resolvedCredentialCharactersAreErasedAfterContextInitialization() throws Exception {
        var material = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "erase");
        List<char[]> issued = new ArrayList<>();
        var settings = settings(material.clientKeyStore(), material.trustStore(),
                material.serverCertificate());

        new PinnedMutualTlsRecoveryFleetPublicationTransport(settings, reference -> {
            char[] secret = PASSWORD.clone();
            issued.add(secret);
            return secret;
        });

        assertThat(issued).hasSize(2).allSatisfy(
                secret -> assertThat(secret).containsOnly('\0'));
    }

    @Test
    void publicSettingsRejectRawPartialRelativeAndDuplicateTrustConfiguration()
            throws Exception {
        Path file = Files.createFile(temporaryDirectory.resolve("placeholder.p12"));
        String pin = "sha256:" + "a".repeat(64);

        assertThatThrownBy(() -> newSettings(null, "", file, "", Set.of(pin)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSettings(null, "", Path.of("relative.p12"),
                "test:client", Set.of(pin)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSettings(file, "raw-password", file,
                "test:client", Set.of(pin)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSettings(null, "", file,
                "test:client", Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> newSettings(null, "", file,
                "test:client", new java.util.AbstractSet<>() {
                    @Override
                    public java.util.Iterator<String> iterator() {
                        return List.of(pin, pin).iterator();
                    }

                    @Override
                    public int size() {
                        return 2;
                    }
                }))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void systemTrustAdapterRemainsExplicitlyUnpinnedAndRejectsUnboundedTimeouts() {
        var transport = new SystemTrustRecoveryFleetPublicationTransport();

        assertThat(transport.descriptor()).isEqualTo(
                new RecoveryFleetPublicationTransport.Descriptor(
                        RecoveryFleetPublicationTransport.Descriptor.SCHEMA_VERSION,
                        true, false, false, false));
        assertThat(transport.client(Duration.ofSeconds(1)).followRedirects())
                .isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
        assertThatThrownBy(() -> transport.client(Duration.ofMillis(99)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transport.client(Duration.ofSeconds(31)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport transport(
            Path clientKeyStore,
            Path trustStore,
            X509Certificate serverCertificate) {
        return new PinnedMutualTlsRecoveryFleetPublicationTransport(
                settings(clientKeyStore, trustStore, serverCertificate), secretResolver());
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport.Settings settings(
            Path clientKeyStore,
            Path trustStore,
            X509Certificate serverCertificate) {
        return newSettings(trustStore, "test:trust", clientKeyStore, "test:client",
                Set.of(PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        serverCertificate)));
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport.Settings newSettings(
            Path trustStore,
            String trustSecret,
            Path clientKeyStore,
            String clientSecret,
            Set<String> pins) {
        return new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                trustStore, trustSecret, clientKeyStore, clientSecret, pins);
    }

    private static RecoveryFleetPublicationTransport.SecretResolver secretResolver() {
        return reference -> switch (reference) {
            case "test:trust", "test:client" -> PASSWORD.clone();
            default -> throw new IllegalStateException("unexpected test secret reference");
        };
    }

}
