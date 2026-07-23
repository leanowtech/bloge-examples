package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationEnvelope;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.mirror.HttpMirrorDeploymentIsolationTrustSource;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAgentSnapshotIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationBundle;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationRepositoryTestFixtures;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityKeySetPublication;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationTrustDistributionProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpMirrorDeploymentIsolationTrustSourceTest {
    @TempDir
    private Path directory;

    @Test
    void pullsBothStrictEnvelopesAcrossIdentityBoundPinnedMutualTls() throws Exception {
        var fixtures = new MirrorDeploymentIsolationAttestationRepositoryTestFixtures()
                .distributionFixtures();
        var tls = RecoveryFleetPublicationTlsFixture.Material.create(directory, "mirror-agent");
        AtomicReference<String> peer = new AtomicReference<>();
        String attestationPath = "/api/mirror/trust/deployment-isolation/attestations/"
                + fixtures.attestationId() + "/latest";
        String authorityPath =
                "/api/mirror/trust/deployment-isolation/authority-key-sets/"
                        + fixtures.keySetId() + "/generations/"
                        + fixtures.authority().material().generation();
        Map<String, String> protocolHeaders = Map.of(
                "Content-Type", MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE);
        Map<String, RecoveryFleetPublicationTlsFixture.Response> routes = Map.of(
                attestationPath, response(fixtures.mapper().writeValueAsBytes(
                        IntegrationEnvelope.of(
                                MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                                MirrorDeploymentIsolationAttestationBundle.SCHEMA_VERSION,
                                fixtures.bundle())), protocolHeaders),
                authorityPath, response(fixtures.mapper().writeValueAsBytes(
                        IntegrationEnvelope.of(
                                MirrorDeploymentIsolationAuthorityKeySetPublication.ARTIFACT_KIND,
                                MirrorDeploymentIsolationAuthorityKeySetPublication.SCHEMA_VERSION,
                                fixtures.authority())), protocolHeaders));
        List<IntegrationOperation> operations = new ArrayList<>();

        try (var server = RecoveryFleetPublicationTlsFixture.startRoutes(tls, routes, peer)) {
            var source = new HttpMirrorDeploymentIsolationTrustSource(
                    fixtures.mapper(), Clock.systemUTC(), transport(tls),
                    settings(server.uri(), fixtures),
                    (operation, uri) -> {
                        operations.add(operation);
                        return Map.of("Authorization", "BLOGE test-signature");
                    });

            assertThat(source.latestAttestation()).isEqualTo(fixtures.bundle());
            assertThat(source.currentAuthority(fixtures.bundle().authorityKeySetRef()))
                    .isEqualTo(fixtures.authority());
            assertThat(operations).containsExactly(
                    IntegrationOperation.MIRROR_ISOLATION_ATTESTATION_READ,
                    IntegrationOperation.MIRROR_ISOLATION_AUTHORITY_READ);
            assertThat(server.requests()).isEqualTo(2);
            assertThat(peer.get()).contains("CN=recovery-client-mirror-agent");
            assertThat(source.descriptor().certificateIdentityBound()).isTrue();
            assertThat(source.descriptor().requestTimeoutMillis()).isEqualTo(2_000);
        }
    }

    @Test
    void rejectsProtocolDowngradeTamperedEnvelopeAndForbiddenAuthorizationHeaders()
            throws Exception {
        var fixtures = new MirrorDeploymentIsolationAttestationRepositoryTestFixtures()
                .distributionFixtures();
        var tls = RecoveryFleetPublicationTlsFixture.Material.create(directory, "mirror-negative");
        String path = "/api/mirror/trust/deployment-isolation/attestations/"
                + fixtures.attestationId() + "/latest";
        byte[] envelope = fixtures.mapper().writeValueAsBytes(IntegrationEnvelope.of(
                MirrorDeploymentIsolationAttestationBundle.ARTIFACT_KIND,
                MirrorDeploymentIsolationAttestationBundle.SCHEMA_VERSION, fixtures.bundle()));

        try (var server = RecoveryFleetPublicationTlsFixture.startRoutes(tls,
                Map.of(path, response(envelope, Map.of("Content-Type", "application/json"))),
                new AtomicReference<>())) {
            var source = source(server.uri(), fixtures, tls,
                    (operation, uri) -> Map.of("Authorization", "BLOGE signed"));
            assertThatThrownBy(source::latestAttestation)
                    .isInstanceOf(HttpMirrorDeploymentIsolationTrustSource.SourceException.class)
                    .extracting("reasonCode")
                    .isEqualTo("MIRROR_TRUST_SOURCE_PROTOCOL_DOWNGRADE");
        }

        String original = new String(envelope, java.nio.charset.StandardCharsets.UTF_8);
        byte[] tampered = original.replaceFirst("\"payloadFingerprint\":\"sha256:[a-f0-9]{64}\"",
                "\"payloadFingerprint\":\"sha256:" + "0".repeat(64) + "\"")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var server = RecoveryFleetPublicationTlsFixture.startRoutes(tls,
                Map.of(path, response(tampered, Map.of("Content-Type",
                        MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE))),
                new AtomicReference<>())) {
            var source = source(server.uri(), fixtures, tls,
                    (operation, uri) -> Map.of("Authorization", "BLOGE signed"));
            assertThatThrownBy(source::latestAttestation)
                    .isInstanceOf(HttpMirrorDeploymentIsolationTrustSource.SourceException.class)
                    .extracting("reasonCode")
                    .isEqualTo("MIRROR_TRUST_ENVELOPE_FINGERPRINT_INVALID");
        }

        try (var server = RecoveryFleetPublicationTlsFixture.startRoutes(tls,
                Map.of(path, response(envelope, Map.of("Content-Type",
                        MirrorDeploymentIsolationTrustDistributionProtocol.MEDIA_TYPE))),
                new AtomicReference<>())) {
            var source = source(server.uri(), fixtures, tls,
                    (operation, uri) -> Map.of("Accept", "application/json"));
            assertThatThrownBy(source::latestAttestation)
                    .isInstanceOf(HttpMirrorDeploymentIsolationTrustSource.SourceException.class)
                    .extracting("reasonCode")
                    .isEqualTo("MIRROR_TRUST_AUTHORIZATION_HEADERS_INVALID");
            assertThat(server.requests()).isZero();
        }
    }

    @Test
    void refusesSystemTrustUnpinnedOrNonIdentityBoundTransportsAndUnsafeUris() throws Exception {
        var fixtures = new MirrorDeploymentIsolationAttestationRepositoryTestFixtures()
                .distributionFixtures();
        ControlPlaneHttpTransport insecure = new ControlPlaneHttpTransport() {
            @Override
            public HttpClient client(Duration connectTimeout) {
                return HttpClient.newHttpClient();
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor(Descriptor.SCHEMA_VERSION,
                        true, false, false, false, false);
            }
        };

        assertThatThrownBy(() -> new HttpMirrorDeploymentIsolationTrustSource(
                fixtures.mapper(), Clock.systemUTC(), insecure,
                settings(URI.create("https://localhost"), fixtures),
                (operation, uri) -> Map.of("Authorization", "BLOGE signed")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private pinned mTLS");

        assertThatThrownBy(() -> settings(
                URI.create("http://mirror.example.test"), fixtures))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpMirrorDeploymentIsolationTrustSource.Settings(
                URI.create("https://user@mirror.example.test"), fixtures.deploymentScopeId(),
                fixtures.keySetId(), fixtures.attestationId(), Duration.ofSeconds(2),
                MirrorDeploymentIsolationAgentSnapshotIntegrity.MAXIMUM_SNAPSHOT_BYTES, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private HttpMirrorDeploymentIsolationTrustSource source(
            URI baseUri,
            MirrorDeploymentIsolationAttestationRepositoryTestFixtures.DistributionFixtures fixtures,
            RecoveryFleetPublicationTlsFixture.Material tls,
            HttpMirrorDeploymentIsolationTrustSource.RequestHeadersProvider headers) {
        return new HttpMirrorDeploymentIsolationTrustSource(fixtures.mapper(), Clock.systemUTC(),
                transport(tls), settings(baseUri, fixtures), headers);
    }

    private static HttpMirrorDeploymentIsolationTrustSource.Settings settings(
            URI baseUri,
            MirrorDeploymentIsolationAttestationRepositoryTestFixtures.DistributionFixtures
                    fixtures) {
        return new HttpMirrorDeploymentIsolationTrustSource.Settings(
                baseUri, fixtures.deploymentScopeId(), fixtures.keySetId(),
                fixtures.attestationId(), Duration.ofSeconds(2),
                MirrorDeploymentIsolationAgentSnapshotIntegrity.MAXIMUM_SNAPSHOT_BYTES, false);
    }

    private static RecoveryFleetPublicationTlsFixture.Response response(
            byte[] body, Map<String, String> headers) {
        return new RecoveryFleetPublicationTlsFixture.Response(200, body, headers);
    }

    private static PinnedMutualTlsRecoveryFleetPublicationTransport transport(
            RecoveryFleetPublicationTlsFixture.Material material) {
        String issuerPin = PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                material.certificateAuthority());
        var identityPolicy = new ControlPlaneCertificateIdentityPolicy(
                material.clientCertificate().getSubjectX500Principal().getName(),
                material.clientUriSan(), Set.of(issuerPin), material.serverUriSan(),
                Set.of(issuerPin));
        var settings = new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                material.trustStore(), "test:trust", material.clientKeyStore(),
                "test:client", Set.of(
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.serverCertificate())), identityPolicy);
        return new PinnedMutualTlsRecoveryFleetPublicationTransport(
                settings, reference -> RecoveryFleetPublicationTlsFixture.password());
    }
}
