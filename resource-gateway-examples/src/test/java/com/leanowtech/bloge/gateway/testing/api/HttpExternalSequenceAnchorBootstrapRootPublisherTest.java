package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpExternalSequenceAnchorBootstrapRootPublisherTest {

    private static final Instant NOW = Instant.parse("2026-07-21T02:00:00Z");
    private static final String TRUST_DOMAIN = "root-publisher.example";
    private static final String PUBLISHER_ID = "root-publisher-a";
    private static final String KEY_ID = "publisher-key-a";

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.PUBLISH);
    private final AtomicReference<String> idempotencyKey = new AtomicReference<>();
    private final AtomicReference<String> ifMatch = new AtomicReference<>();
    private final AtomicReference<String> requestProtocol = new AtomicReference<>();

    private HttpServer server;
    private KeyPair keyPair;
    private ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest request;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        request = request();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bootstrap-roots", this::handle);
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void publishesWithContentAddressedAndConditionalHeaders() {
        var publisher = publisher();

        var receipt = publisher.publish(request);

        assertThat(receipt.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootPublicationOutbox
                        .PublicationReceiptStatus.PUBLISHED);
        assertThat(receipt.publicationId()).isEqualTo(request.publicationId());
        assertThat(receipt.publishedAt()).isEqualTo(NOW);
        assertThat(idempotencyKey).hasValue(request.publicationId());
        assertThat(ifMatch).hasValue('"'
                + request.expectedPreviousMaterialFingerprint() + '"');
        assertThat(requestProtocol).hasValue(
                HttpExternalSequenceAnchorBootstrapRootPublisher.PROTOCOL_VERSION);
        assertThat(publisher.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.signedResponses()).isTrue();
            assertThat(descriptor.conditionalPredecessor()).isTrue();
            assertThat(descriptor.maximumRequestBytes()).isEqualTo(
                    HttpExternalSequenceAnchorBootstrapRootPublisher
                            .MAXIMUM_REQUEST_BYTES);
        });
        assertThat(publisher.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.status()).isEqualTo("HEALTHY");
            assertThat(snapshot.publishedCount()).isOne();
            assertThat(snapshot.failureCount()).isZero();
        });
    }

    @Test
    void exactReplayReturnsTheStableOriginalPublishedInstant() {
        mode.set(Mode.REPLAY);
        var publisher = publisher();

        var receipt = publisher.publish(request);

        assertThat(receipt.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootPublicationOutbox
                        .PublicationReceiptStatus.IDEMPOTENT_REPLAY);
        assertThat(receipt.publishedAt()).isEqualTo(NOW);
        assertThat(publisher.snapshot().replayCount()).isOne();
    }

    @Test
    void validSignedConflictIsSafetySignificant() {
        mode.set(Mode.CONFLICT);
        var publisher = publisher();

        assertThatThrownBy(() -> publisher.publish(request))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootPublisher.PublisherException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ExternalSequenceAnchorBootstrapRootPublisher.FailureReason
                                        .AUTHENTICATED_CONFLICT));
        assertThat(publisher.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.status()).isEqualTo("AUTHENTICATED_CONFLICT");
            assertThat(snapshot.conflictCount()).isOne();
        });
    }

    @Test
    void unsignedOrWronglySignedConflictCannotTriggerSafetyQuarantine() {
        mode.set(Mode.INVALID_SIGNATURE_CONFLICT);
        var publisher = publisher();

        assertThatThrownBy(() -> publisher.publish(request))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootPublisher.PublisherException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ExternalSequenceAnchorBootstrapRootPublisher.FailureReason
                                        .INVALID_RESPONSE));
        assertThat(publisher.snapshot().conflictCount()).isZero();
        assertThat(publisher.snapshot().failureCount()).isOne();
    }

    @Test
    void mismatchedStaleAndUnknownFieldResponsesFailClosed() {
        var publisher = publisher();
        for (Mode invalid : List.of(Mode.MISMATCHED_REQUEST, Mode.STALE,
                Mode.UNKNOWN_FIELD, Mode.BAD_PROTOCOL)) {
            mode.set(invalid);
            assertThatThrownBy(() -> publisher.publish(request))
                    .isInstanceOfSatisfying(
                            ExternalSequenceAnchorBootstrapRootPublisher
                                    .PublisherException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootPublisher
                                            .FailureReason.INVALID_RESPONSE));
        }
        assertThat(publisher.snapshot().failureCount()).isEqualTo(4L);
    }

    @Test
    void transientHttpStatusIsUnavailableWithoutRemoteDiagnostics() {
        mode.set(Mode.UNAVAILABLE);
        var publisher = publisher();

        assertThatThrownBy(() -> publisher.publish(request))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootPublisher.PublisherException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ExternalSequenceAnchorBootstrapRootPublisher.FailureReason
                                        .UNAVAILABLE))
                .hasMessageNotContaining("bootstrap-roots")
                .hasMessageNotContaining("127.0.0.1")
                .hasMessageNotContaining(request.publicationId());
        assertThat(publisher.snapshot().status()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void productionEndpointAndTimingPolicyAreStrictlyBounded() {
        assertThatThrownBy(() -> new HttpExternalSequenceAnchorBootstrapRootPublisher(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), TRUST_DOMAIN,
                PUBLISHER_ID, KEY_ID, keyPair.getPublic(), NOW.minusSeconds(60),
                NOW.plusSeconds(3600), URI.create("http://example.com/publish"),
                settings(false), HttpClient.newHttpClient()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new HttpExternalSequenceAnchorBootstrapRootPublisher
                .Settings(Duration.ofMillis(99), Duration.ZERO,
                Duration.ofSeconds(30), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpExternalSequenceAnchorBootstrapRootPublisher
                .Settings(Duration.ofSeconds(30), Duration.ZERO,
                Duration.ofSeconds(30), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private HttpExternalSequenceAnchorBootstrapRootPublisher publisher() {
        return new HttpExternalSequenceAnchorBootstrapRootPublisher(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), TRUST_DOMAIN,
                PUBLISHER_ID, KEY_ID, keyPair.getPublic(), NOW.minusSeconds(60),
                NOW.plusSeconds(3600), endpoint(), settings(true),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            idempotencyKey.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
            requestProtocol.set(exchange.getRequestHeaders().getFirst(
                    HttpExternalSequenceAnchorBootstrapRootPublisher.PROTOCOL_HEADER));
            byte[] requestBytes = exchange.getRequestBody().readAllBytes();
            var received = objectMapper.readValue(requestBytes,
                    ExternalSequenceAnchorBootstrapRootPublicationOutbox
                            .PublicationRequest.class);
            Mode selected = mode.get();
            if (selected == Mode.UNAVAILABLE) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            var signed = response(received, selected);
            byte[] body = objectMapper.writeValueAsBytes(signed);
            if (selected == Mode.UNKNOWN_FIELD) {
                String value = new String(body, StandardCharsets.UTF_8);
                body = (value.substring(0, value.length() - 1)
                        + ",\"providerSecret\":\"forbidden\"}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type",
                    HttpExternalSequenceAnchorBootstrapRootPublisher.MEDIA_TYPE);
            exchange.getResponseHeaders().set(
                    HttpExternalSequenceAnchorBootstrapRootPublisher.PROTOCOL_HEADER,
                    selected == Mode.BAD_PROTOCOL ? "unsupported.v0"
                            : HttpExternalSequenceAnchorBootstrapRootPublisher.PROTOCOL_VERSION);
            int status = selected == Mode.CONFLICT
                    || selected == Mode.INVALID_SIGNATURE_CONFLICT ? 409 : 200;
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private ExternalSequenceAnchorBootstrapRootPublisher.SignedResponse response(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest received,
            Mode selected) {
        boolean conflict = selected == Mode.CONFLICT
                || selected == Mode.INVALID_SIGNATURE_CONFLICT;
        Instant signedAt = selected == Mode.STALE ? NOW.minusSeconds(61) : NOW;
        Instant expiresAt = signedAt.plusSeconds(30);
        String requestFingerprint = ProtocolFingerprint.of(objectMapper, received);
        if (selected == Mode.MISMATCHED_REQUEST) {
            requestFingerprint = "sha256:" + "f".repeat(64);
        }
        var material = new ExternalSequenceAnchorBootstrapRootPublisher.ResponseMaterial(
                ExternalSequenceAnchorBootstrapRootPublisher.ResponseMaterial.SCHEMA_VERSION,
                conflict
                        ? ExternalSequenceAnchorBootstrapRootPublisher.ResponseDecision.CONFLICT
                        : selected == Mode.REPLAY
                        ? ExternalSequenceAnchorBootstrapRootPublisher.ResponseDecision
                        .IDEMPOTENT_REPLAY
                        : ExternalSequenceAnchorBootstrapRootPublisher.ResponseDecision.PUBLISHED,
                TRUST_DOMAIN, PUBLISHER_ID, KEY_ID, requestFingerprint,
                received.publicationId(), received.scopeId(), received.rootSetId(),
                received.sequence(), received.expectedPreviousMaterialFingerprint(),
                received.bundleFingerprint(), received.headMaterialFingerprint(),
                received.sequence(), conflict ? "sha256:" + "e".repeat(64)
                : received.headMaterialFingerprint(), conflict ? null
                : selected == Mode.STALE ? signedAt.minusSeconds(1) : NOW,
                signedAt, expiresAt);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        byte[] signature = sign(fingerprint, selected == Mode.INVALID_SIGNATURE_CONFLICT);
        return new ExternalSequenceAnchorBootstrapRootPublisher.SignedResponse(
                ExternalSequenceAnchorBootstrapRootPublisher.SignedResponse.SCHEMA_VERSION,
                material, fingerprint, Base64.getEncoder().encodeToString(signature));
    }

    private byte[] sign(String fingerprint, boolean invalid) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            KeyPair pair = invalid
                    ? KeyPairGenerator.getInstance("Ed25519").generateKeyPair() : keyPair;
            signer.initSign(pair.getPrivate());
            signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            return signer.sign();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest request()
            throws Exception {
        var rootPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var root = new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                "root-a", "root-key-a",
                Base64.getEncoder().encodeToString(rootPair.getPublic().getEncoded()),
                NOW.minusSeconds(180), NOW.plusSeconds(3600), true, false);
        String predecessor = "sha256:" + "a".repeat(64);
        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                "notary-bootstrap-roots", 1L, predecessor, "stability-fleet",
                "bootstrap.example", 1, 0, List.of(root),
                "sha256:" + "b".repeat(64), NOW.minusSeconds(120),
                NOW.minusSeconds(120), NOW.plusSeconds(3600));
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
                "stability-fleet", "notary-bootstrap-roots", "ceremony-publisher", 1L,
                predecessor, bundle, bundleFingerprint, materialFingerprint);
    }

    private URI endpoint() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/bootstrap-roots");
    }

    private static HttpExternalSequenceAnchorBootstrapRootPublisher.Settings settings(
            boolean loopback) {
        return new HttpExternalSequenceAnchorBootstrapRootPublisher.Settings(
                Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(30), loopback);
    }

    private enum Mode {
        PUBLISH,
        REPLAY,
        CONFLICT,
        INVALID_SIGNATURE_CONFLICT,
        MISMATCHED_REQUEST,
        STALE,
        UNKNOWN_FIELD,
        BAD_PROTOCOL,
        UNAVAILABLE
    }
}
