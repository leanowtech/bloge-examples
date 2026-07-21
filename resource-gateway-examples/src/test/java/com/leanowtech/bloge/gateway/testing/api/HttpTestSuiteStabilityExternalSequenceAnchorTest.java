package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTestSuiteStabilityExternalSequenceAnchorTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final String TRUST_DOMAIN = "inventory-transparency";
    private static final String ANCHOR_SET = "notary-set-a";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final Map<String, KeyPair> keyPairs = new HashMap<>();
    private final Map<String, Mode> modes = new HashMap<>();
    private final Map<String, TestSuiteStabilityExternalSequenceCheckpointReceipt> cached =
            new HashMap<>();
    private HttpServer server;
    private List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> keys;
    private List<HttpTestSuiteStabilityExternalSequenceAnchor.Endpoint> endpoints;

    @TempDir
    private Path temporaryDirectory;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keys = new ArrayList<>();
        endpoints = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            int authorityIndex = index;
            String authority = "notary-" + index;
            String domain = "region-" + index;
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            keyPairs.put(authority, pair);
            modes.put(authority, Mode.ACCEPT);
            keys.add(new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                    authority, "key-" + index, pair.getPublic(), NOW.minusSeconds(60),
                    NOW.plusSeconds(3600), true, false));
            String path = "/notaries/" + authority;
            server.createContext(path, exchange -> handle(exchange, authority, domain,
                    "key-" + authorityIndex));
            endpoints.add(new HttpTestSuiteStabilityExternalSequenceAnchor.Endpoint(
                    authority, domain, URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + path)));
        }
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void threeOfFourAcceptsWhenOneIndependentNotaryIsUnavailable() {
        modes.put("notary-4", Mode.UNAVAILABLE);
        HttpTestSuiteStabilityExternalSequenceAnchor anchor = anchor(3, 1);

        anchor.accept(head());

        assertThat(anchor.snapshot())
                .extracting(TestSuiteStabilityExternalSequenceAnchor.Snapshot::available,
                        TestSuiteStabilityExternalSequenceAnchor.Snapshot::status,
                        TestSuiteStabilityExternalSequenceAnchor.Snapshot::successCount)
                .containsExactly(true, "HEALTHY", 1L);
        assertThat(anchor.descriptor().byzantineQuorum()).isTrue();
        assertThat(new TestSuiteStabilityExternalSequenceAnchorHealth(anchor)
                .health().getDetails())
                .doesNotContainKeys("uri", "authorityId", "keyId", "fingerprint", "challenge");
    }

    @Test
    void authenticatedConflictIsFatalEvenWhenThreeOthersAccept() {
        modes.put("notary-4", Mode.CONFLICT);
        HttpTestSuiteStabilityExternalSequenceAnchor anchor = anchor(3, 1);

        assertThatThrownBy(() -> anchor.accept(head()))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class)
                .extracting(error -> ((TestSuiteStabilityExternalSequenceAnchor
                        .ExternalAnchorException) error).reason())
                .isEqualTo(TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException
                        .Reason.AUTHENTICATED_CONFLICT);
        assertThat(anchor.snapshot().conflictCount()).isEqualTo(1);
        assertThat(anchor.snapshot().available()).isFalse();
    }

    @Test
    void oldChallengeReceiptsCannotAuthorizeDatabaseRollbackReplay() {
        HttpTestSuiteStabilityExternalSequenceAnchor anchor = anchor(3, 1);
        anchor.accept(head());
        modes.replaceAll((authority, ignored) -> Mode.REPLAY);

        assertThatThrownBy(() -> anchor.accept(head()))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class)
                .extracting(error -> ((TestSuiteStabilityExternalSequenceAnchor
                        .ExternalAnchorException) error).reason())
                .isEqualTo(TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException
                        .Reason.QUORUM_NOT_MET);
        assertThat(anchor.snapshot().failureCount()).isEqualTo(1);
    }

    @Test
    void invalidSignatureCannotContributeToQuorum() {
        modes.put("notary-3", Mode.INVALID_SIGNATURE);
        modes.put("notary-4", Mode.UNAVAILABLE);
        HttpTestSuiteStabilityExternalSequenceAnchor anchor = anchor(3, 1);

        assertThatThrownBy(() -> anchor.accept(head()))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class)
                .extracting(error -> ((TestSuiteStabilityExternalSequenceAnchor
                        .ExternalAnchorException) error).reason())
                .isEqualTo(TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException
                        .Reason.QUORUM_NOT_MET);
    }

    @Test
    void receiptThatOutlivesItsFreshRequestCannotContributeToQuorum() {
        modes.replaceAll((authority, ignored) -> Mode.OUTLIVES_REQUEST);
        HttpTestSuiteStabilityExternalSequenceAnchor anchor = anchor(3, 1);

        assertThatThrownBy(() -> anchor.accept(head()))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class)
                .extracting(error -> ((TestSuiteStabilityExternalSequenceAnchor
                        .ExternalAnchorException) error).reason())
                .isEqualTo(TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException
                        .Reason.QUORUM_NOT_MET);
    }

    @Test
    void configurationEnforcesIntersectingQuorumAndIndependentDomains() {
        assertThatThrownBy(() -> new HttpTestSuiteStabilityExternalSequenceAnchor(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(),
                TRUST_DOMAIN, ANCHOR_SET, 2, 1, keys, endpoints, settings(), client()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quorum policy");

        List<HttpTestSuiteStabilityExternalSequenceAnchor.Endpoint> duplicateDomain =
                new ArrayList<>(endpoints);
        var second = duplicateDomain.get(1);
        duplicateDomain.set(1, new HttpTestSuiteStabilityExternalSequenceAnchor.Endpoint(
                second.authorityId(), endpoints.getFirst().failureDomain(), second.uri()));
        assertThatThrownBy(() -> new HttpTestSuiteStabilityExternalSequenceAnchor(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(),
                TRUST_DOMAIN, ANCHOR_SET, 3, 1, keys, duplicateDomain,
                settings(), client()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be unique");
    }

    @Test
    void refreshedTrustAuthoritySetMustStillExactlyMatchConfiguredEndpoints() {
        MutableAuthorityTrustStore trustStore = new MutableAuthorityTrustStore(
                Set.of("notary-1", "notary-2", "notary-3", "notary-4"));
        HttpTestSuiteStabilityExternalSequenceAnchor anchor =
                new HttpTestSuiteStabilityExternalSequenceAnchor(
                        objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(),
                        TRUST_DOMAIN, ANCHOR_SET, 3, 1, trustStore, endpoints,
                        settings(), client());

        trustStore.authorities = Set.of(
                "notary-1", "notary-2", "notary-3", "replacement-notary");

        assertThat(anchor.descriptor().available()).isFalse();
        assertThatThrownBy(() -> anchor.accept(head()))
                .isInstanceOf(
                        TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException.class)
                .extracting(error -> ((TestSuiteStabilityExternalSequenceAnchor
                        .ExternalAnchorException) error).reason())
                .isEqualTo(TestSuiteStabilityExternalSequenceAnchor.ExternalAnchorException
                        .Reason.UNAVAILABLE);
    }

    @Test
    void notaryRequestsUseTheConfiguredPinnedMutualTlsIdentity() throws Exception {
        RecoveryFleetPublicationTlsFixture.Material material =
                RecoveryFleetPublicationTlsFixture.Material.create(
                        temporaryDirectory, "external-notary");
        AtomicReference<String> peer = new AtomicReference<>("");
        Map<String, String> headers = Map.of(
                "Content-Type", HttpTestSuiteStabilityExternalSequenceAnchor.MEDIA_TYPE,
                HttpTestSuiteStabilityExternalSequenceAnchor.PROTOCOL_HEADER,
                TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION);
        try (RecoveryFleetPublicationTlsFixture.Server tlsServer =
                     RecoveryFleetPublicationTlsFixture.start(
                             material, "/notary", "{}".getBytes(StandardCharsets.UTF_8),
                             headers, peer)) {
            KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Instant now = Instant.now();
            String authorityKeysJson = objectMapper.writeValueAsString(List.of(Map.of(
                    "authorityId", "notary-1",
                    "keyId", "key-1",
                    "publicKeyBase64", Base64.getEncoder().encodeToString(
                            key.getPublic().getEncoded()),
                    "notBefore", now.minusSeconds(60).toString(),
                    "expiresAt", now.plusSeconds(3600).toString(),
                    "enabled", true,
                    "revoked", false)));
            String endpointsJson = objectMapper.writeValueAsString(List.of(Map.of(
                    "authorityId", "notary-1",
                    "failureDomain", "region-1",
                    "uri", tlsServer.uri().toString())));
            var strictSettings = new HttpTestSuiteStabilityExternalSequenceAnchor.Settings(
                    Duration.ofSeconds(2), Duration.ofSeconds(1),
                    Duration.ofSeconds(10), false);
            ControlPlaneHttpTransport transport = pinnedTransport(
                    material, PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                            material.serverCertificate()));
            HttpTestSuiteStabilityExternalSequenceAnchor anchor =
                    HttpTestSuiteStabilityExternalSequenceAnchor.fromJson(
                            objectMapper, TRUST_DOMAIN, ANCHOR_SET, 1, 0,
                            authorityKeysJson, endpointsJson, strictSettings, transport);

            assertThatThrownBy(() -> anchor.accept(head()))
                    .isInstanceOf(TestSuiteStabilityExternalSequenceAnchor
                            .ExternalAnchorException.class);
            assertThat(anchor.transportSecurity().notary()).satisfies(descriptor -> {
                assertThat(descriptor.privateTrustStore()).isTrue();
                assertThat(descriptor.serverSpkiPinned()).isTrue();
                assertThat(descriptor.mutualTls()).isTrue();
            });
            assertThat(anchor.descriptor().properties())
                    .containsEntry("notaryTransportSystemTrustStore", false)
                    .containsEntry("notaryTransportPinnedMutualTls", true)
                    .containsEntry("managedTrustTransportConfigured", false)
                    .containsEntry("bootstrapRootTransportConfigured", false);
            assertThat(peer.get()).contains("recovery-client-external-notary");
            assertThat(tlsServer.requests()).isEqualTo(1);

            HttpTestSuiteStabilityExternalSequenceAnchor wrongPin =
                    HttpTestSuiteStabilityExternalSequenceAnchor.fromJson(
                            objectMapper, TRUST_DOMAIN, ANCHOR_SET, 1, 0,
                            authorityKeysJson, endpointsJson, strictSettings,
                            pinnedTransport(material, "sha256:" + "0".repeat(64)));
            assertThatThrownBy(() -> wrongPin.accept(head()))
                    .isInstanceOf(TestSuiteStabilityExternalSequenceAnchor
                            .ExternalAnchorException.class);
            assertThat(tlsServer.requests()).isEqualTo(1);

            HttpTestSuiteStabilityExternalSequenceAnchor anonymous =
                    HttpTestSuiteStabilityExternalSequenceAnchor.fromJson(
                            objectMapper, TRUST_DOMAIN, ANCHOR_SET, 1, 0,
                            authorityKeysJson, endpointsJson, strictSettings);
            assertThatThrownBy(() -> anonymous.accept(head()))
                    .isInstanceOf(TestSuiteStabilityExternalSequenceAnchor
                            .ExternalAnchorException.class);
            assertThat(tlsServer.requests()).isEqualTo(1);
        }
    }

    private HttpTestSuiteStabilityExternalSequenceAnchor anchor(
            int threshold, int maximumFaults) {
        return new HttpTestSuiteStabilityExternalSequenceAnchor(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(),
                TRUST_DOMAIN, ANCHOR_SET, threshold, maximumFaults,
                keys, endpoints, settings(), client());
    }

    private HttpTestSuiteStabilityExternalSequenceAnchor.Settings settings() {
        return new HttpTestSuiteStabilityExternalSequenceAnchor.Settings(
                Duration.ofSeconds(2), Duration.ofSeconds(1),
                Duration.ofSeconds(10), true);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static ControlPlaneHttpTransport pinnedTransport(
            RecoveryFleetPublicationTlsFixture.Material material,
            String pin) {
        return new PinnedMutualTlsRecoveryFleetPublicationTransport(
                new PinnedMutualTlsRecoveryFleetPublicationTransport.Settings(
                        material.trustStore(), "test:notary-trust",
                        material.clientKeyStore(), "test:notary-client", Set.of(pin)),
                reference -> RecoveryFleetPublicationTlsFixture.password());
    }

    private void handle(
            HttpExchange exchange,
            String authority,
            String failureDomain,
            String keyId) throws IOException {
        try (exchange) {
            Mode mode = modes.get(authority);
            if (mode == Mode.UNAVAILABLE) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            var request = objectMapper.readValue(exchange.getRequestBody(),
                    TestSuiteStabilityExternalSequenceCheckpointRequest.class);
            TestSuiteStabilityExternalSequenceCheckpointReceipt receipt;
            if (mode == Mode.REPLAY) {
                receipt = cached.get(authority);
            } else {
                receipt = receipt(request, authority, failureDomain, keyId,
                        mode == Mode.CONFLICT, mode == Mode.INVALID_SIGNATURE,
                        mode == Mode.OUTLIVES_REQUEST);
                cached.put(authority, receipt);
            }
            byte[] body = objectMapper.writeValueAsBytes(receipt);
            exchange.getResponseHeaders().set("Content-Type",
                    HttpTestSuiteStabilityExternalSequenceAnchor.MEDIA_TYPE);
            exchange.getResponseHeaders().set(
                    HttpTestSuiteStabilityExternalSequenceAnchor.PROTOCOL_HEADER,
                    TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION);
            exchange.sendResponseHeaders(
                    receipt.decision()
                            == TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.ACCEPTED
                            ? 200 : 409,
                    body.length);
            exchange.getResponseBody().write(body);
        } catch (Exception invalid) {
            byte[] body = "invalid".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private TestSuiteStabilityExternalSequenceCheckpointReceipt receipt(
            TestSuiteStabilityExternalSequenceCheckpointRequest request,
            String authority,
            String failureDomain,
            String keyId,
            boolean conflict,
            boolean invalidSignature,
            boolean outlivesRequest) throws Exception {
        var decision = conflict
                ? TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.CONFLICT
                : TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.ACCEPTED;
        long observedSequence = request.head().sequence();
        String observedFingerprint = conflict ? fingerprint('f')
                : request.head().headFingerprint();
        Instant issuedAt = outlivesRequest ? NOW.plusSeconds(1) : NOW;
        Instant expiresAt = outlivesRequest ? NOW.plusSeconds(11) : NOW.plusSeconds(10);
        var material = new TestSuiteStabilityExternalSequenceCheckpointReceipt.Material(
                TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION,
                request.requestFingerprint(), TRUST_DOMAIN, ANCHOR_SET, authority,
                failureDomain, keyId, decision, request.head().sequence(),
                request.head().headFingerprint(), observedSequence, observedFingerprint,
                issuedAt, expiresAt, "Ed25519");
        String receiptFingerprint = ProtocolFingerprint.of(objectMapper, material);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPairs.get(authority).getPrivate());
        signer.update(receiptFingerprint.getBytes(StandardCharsets.UTF_8));
        byte[] signed = signer.sign();
        if (invalidSignature) {
            signed[0] ^= 1;
        }
        return new TestSuiteStabilityExternalSequenceCheckpointReceipt(
                TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION,
                receiptFingerprint, request.requestFingerprint(), TRUST_DOMAIN, ANCHOR_SET,
                authority, failureDomain, keyId, decision, request.head().sequence(),
                request.head().headFingerprint(), observedSequence, observedFingerprint,
                issuedAt, expiresAt, "Ed25519",
                Base64.getEncoder().encodeToString(signed));
    }

    private static TestSuiteStabilityExternalSequenceAnchor.Head head() {
        return new TestSuiteStabilityExternalSequenceAnchor.Head(
                TestSuiteStabilityExternalSequenceAnchor.Head.SCHEMA_VERSION,
                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                        .SERVING_INVENTORY_TRUST_ROOT,
                "stability-fleet", "inventory-roots", 1, fingerprint('a'), "");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private enum Mode {
        ACCEPT,
        CONFLICT,
        UNAVAILABLE,
        INVALID_SIGNATURE,
        REPLAY,
        OUTLIVES_REQUEST
    }

    private static final class MutableAuthorityTrustStore
            implements ExternalSequenceAnchorReceiptTrustStore {

        private Set<String> authorities;

        private MutableAuthorityTrustStore(Set<String> authorities) {
            this.authorities = Set.copyOf(authorities);
        }

        @Override
        public void verify(
                TestSuiteStabilityExternalSequenceCheckpointReceipt receipt,
                Instant observedAt) {
        }

        @Override
        public boolean coversAuthorities(Set<String> expected) {
            return authorities.equals(Set.copyOf(expected));
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(Descriptor.SCHEMA_VERSION, true,
                    true, true, true, authorities.size(), authorities.size());
        }

        @Override
        public Snapshot snapshot() {
            return new Snapshot(Snapshot.SCHEMA_VERSION, true, "HEALTHY",
                    2, authorities.size(), authorities.size(), NOW, 2, 0);
        }
    }
}
