package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpControlPlaneCertificateStatusSourceTest {

    private static final String SCOPE = "resource-gateway-prod";
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certificate/status", exchange -> handler.get().handle(exchange));
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/certificate/status");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesOneStrictContiguousPublicationWithExactCursorAndHeaders() throws Exception {
        ControlPlaneCertificateStatusPublication publication = publication(
                1, "", NOW, NOW.plusSeconds(60));
        ControlPlaneCertificateStatusSourceHead sourceHead = sourceHead(
                3, fingerprint('3'), NOW, NOW.plusSeconds(300));
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        handler.set(exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            protocol.set(exchange.getRequestHeaders().getFirst(
                    HttpControlPlaneCertificateStatusSource.PROTOCOL_HEADER));
            respond(exchange, 200, objectMapper.writeValueAsBytes(
                    response(publication, sourceHead)), true, true);
        });

        ControlPlaneCertificateStatusSource.FetchResult result = source().fetch(cursor());

        assertThat(result.status()).isEqualTo(
                ControlPlaneCertificateStatusSource.FetchStatus.PUBLICATION);
        assertThat(result.publication()).isEqualTo(publication);
        assertThat(result.sourceHead()).isEqualTo(sourceHead);
        assertThat(result.exactSourceHead()).isTrue();
        assertThat(query.get()).contains("deploymentScopeId=" + SCOPE,
                "afterSequence=0", "afterPublicationFingerprint=sha256%3A");
        assertThat(accept.get()).isEqualTo(HttpControlPlaneCertificateStatusSource.MEDIA_TYPE);
        assertThat(protocol.get()).isEqualTo(
                HttpControlPlaneCertificateStatusSource.PROTOCOL_VERSION);
        assertThat(source().descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.privateTrustStore()).isTrue();
            assertThat(descriptor.serverSpkiPinned()).isTrue();
            assertThat(descriptor.mutualTls()).isTrue();
            assertThat(descriptor.certificateIdentityBound()).isTrue();
            assertThat(descriptor.strictProtocol()).isTrue();
        });
    }

    @Test
    void unchangedRequiresAnExactSignedHeadAndForbidsEmptySuccess() throws Exception {
        ControlPlaneCertificateStatusSourceHead baseline = sourceHead(
                0, fingerprint('0'), NOW, NOW.plusSeconds(300));
        handler.set(exchange -> respond(exchange, 200,
                objectMapper.writeValueAsBytes(response(null, baseline)), true, true));
        var unchanged = source().fetch(cursor());
        assertThat(unchanged.status()).isEqualTo(
                ControlPlaneCertificateStatusSource.FetchStatus.UNCHANGED);
        assertThat(unchanged.sourceHead()).isEqualTo(baseline);

        handler.set(exchange -> respond(exchange, 200, objectMapper.writeValueAsBytes(
                response(null, sourceHead(1, fingerprint('1'), NOW,
                        NOW.plusSeconds(300)))), true, true));
        assertThat(source().fetch(cursor()).reasonCode())
                .isEqualTo("CERTIFICATE_STATUS_SOURCE_RESPONSE_INVALID");

        handler.set(exchange -> respond(exchange, 204, new byte[0], false, true));
        assertThat(source().fetch(cursor()).reasonCode())
                .isEqualTo("CERTIFICATE_STATUS_HTTP_REJECTED");
    }

    @Test
    void rejectsMediaTypeProtocolAndFingerprintDowngrade() throws Exception {
        ControlPlaneCertificateStatusPublication publication = publication(
                1, "", NOW, NOW.plusSeconds(60));
        ControlPlaneCertificateStatusSourceHead sourceHead = sourceHead(
                1, publication.materialFingerprint(), NOW, NOW.plusSeconds(300));
        handler.set(exchange -> respond(exchange, 200,
                objectMapper.writeValueAsBytes(response(publication, sourceHead)), false, true));
        assertThat(source().fetch(cursor()).reasonCode())
                .isEqualTo("CERTIFICATE_STATUS_PROTOCOL_DOWNGRADE");

        handler.set(exchange -> respond(exchange, 200,
                objectMapper.writeValueAsBytes(response(publication, sourceHead)), true, false));
        assertThat(source().fetch(cursor()).reasonCode())
                .isEqualTo("CERTIFICATE_STATUS_PROTOCOL_DOWNGRADE");

        var tampered = new ControlPlaneCertificateStatusPublication(
                ControlPlaneCertificateStatusPublication.SCHEMA_VERSION,
                publication.material(), fingerprint('9'), publication.signatures());
        handler.set(exchange -> respond(exchange, 200,
                objectMapper.writeValueAsBytes(response(tampered, sourceHead(
                        1, tampered.materialFingerprint(), NOW,
                        NOW.plusSeconds(300)))), true, true));
        assertThat(source().fetch(cursor()).reasonCode())
                .isEqualTo("CERTIFICATE_STATUS_SOURCE_RESPONSE_INVALID");
    }

    @Test
    void rejectsGapForkExpiryFutureIssueAndExcessiveLifetime() throws Exception {
        assertRejected(publication(2, fingerprint('0'), NOW, NOW.plusSeconds(60)));
        assertRejected(publication(1, "", NOW.minusSeconds(120), NOW.minusSeconds(60)));
        assertRejected(publication(1, "", NOW.plusSeconds(301), NOW.plusSeconds(360)));
        assertRejected(publication(1, "", NOW, NOW.plusSeconds(3_601)));

        ControlPlaneCertificateStatusSource.Cursor successorCursor =
                new ControlPlaneCertificateStatusSource.Cursor(1, fingerprint('0'));
        handler.set(exchange -> respond(exchange, 200,
                objectMapper.writeValueAsBytes(response(publication(
                        2, fingerprint('8'), NOW, NOW.plusSeconds(60)), sourceHead(
                        2, publication(2, fingerprint('8'), NOW,
                                NOW.plusSeconds(60)).materialFingerprint(), NOW,
                        NOW.plusSeconds(300)))), true, true));
        assertThat(source().fetch(successorCursor).status()).isEqualTo(
                ControlPlaneCertificateStatusSource.FetchStatus.PROTOCOL_REJECTED);
    }

    @Test
    void boundsBodyBeforeParsingAndRejectsMalformedOrUnknownJson() {
        byte[] oversized = new byte[2_049];
        handler.set(exchange -> respond(exchange, 200, oversized, true, true));
        assertThat(source(2_048).fetch(cursor()).reasonCode())
                .isEqualTo("CERTIFICATE_STATUS_BODY_TOO_LARGE");

        handler.set(exchange -> respond(exchange, 200,
                "{not-json".getBytes(StandardCharsets.UTF_8), true, true));
        assertThat(source().fetch(cursor()).reasonCode())
                .isEqualTo("CERTIFICATE_STATUS_SOURCE_RESPONSE_INVALID");

        handler.set(exchange -> respond(exchange, 200,
                "{\"unknown\":true}".getBytes(StandardCharsets.UTF_8), true, true));
        assertThat(source().fetch(cursor()).reasonCode())
                .isEqualTo("CERTIFICATE_STATUS_SOURCE_RESPONSE_INVALID");
    }

    @Test
    void separatesTransientHttpFailureFromPermanentProtocolRejection() {
        handler.set(exchange -> respond(exchange, 503, new byte[0], false, false));
        assertThat(source().fetch(cursor()).status()).isEqualTo(
                ControlPlaneCertificateStatusSource.FetchStatus.SOURCE_UNAVAILABLE);

        handler.set(exchange -> respond(exchange, 409, new byte[0], false, false));
        assertThat(source().fetch(cursor()).status()).isEqualTo(
                ControlPlaneCertificateStatusSource.FetchStatus.PROTOCOL_REJECTED);
    }

    @Test
    void rejectsNonHttpsRemoteUrisCallerQueriesAndWeakTransports() {
        assertThatThrownBy(() -> settings("http://example.com/status", false, 64 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(endpoint + "?cursor=caller-owned", true,
                64 * 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(endpoint + "#fragment", true, 64 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpControlPlaneCertificateStatusSource(
                objectMapper, clock(), weakTransport(), settings()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned mTLS identity");
    }

    private void assertRejected(ControlPlaneCertificateStatusPublication publication)
            throws Exception {
        handler.set(exchange -> respond(exchange, 200,
                objectMapper.writeValueAsBytes(response(publication, sourceHead(
                        publication.material().sequence(), publication.materialFingerprint(),
                        NOW, NOW.plusSeconds(300)))), true, true));
        assertThat(source().fetch(cursor()).status()).isEqualTo(
                ControlPlaneCertificateStatusSource.FetchStatus.PROTOCOL_REJECTED);
    }

    private HttpControlPlaneCertificateStatusSource source() {
        return source(64 * 1024);
    }

    private HttpControlPlaneCertificateStatusSource source(int maximumBytes) {
        return new HttpControlPlaneCertificateStatusSource(objectMapper, clock(),
                strictTransport(), settings(endpoint.toString(), true, maximumBytes));
    }

    private HttpControlPlaneCertificateStatusSource.Settings settings() {
        return settings(endpoint.toString(), true, 64 * 1024);
    }

    private HttpControlPlaneCertificateStatusSource.Settings settings(
            String uri, boolean allowLoopback, int maximumBytes) {
        return new HttpControlPlaneCertificateStatusSource.Settings(
                SCOPE, uri, 2_000, maximumBytes, 300, 3_600, allowLoopback);
    }

    private ControlPlaneHttpTransport strictTransport() {
        return transport(new ControlPlaneHttpTransport.Descriptor(
                ControlPlaneHttpTransport.Descriptor.SCHEMA_VERSION,
                false, true, true, true, true));
    }

    private ControlPlaneHttpTransport weakTransport() {
        return transport(new ControlPlaneHttpTransport.Descriptor(
                ControlPlaneHttpTransport.Descriptor.SCHEMA_VERSION,
                true, false, false, false, false));
    }

    private static ControlPlaneHttpTransport transport(
            ControlPlaneHttpTransport.Descriptor descriptor) {
        return new ControlPlaneHttpTransport() {
            @Override
            public HttpClient client(Duration connectTimeout) {
                return HttpClient.newBuilder().connectTimeout(connectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER).build();
            }

            @Override
            public Descriptor descriptor() {
                return descriptor;
            }

            @Override
            public boolean certificateIdentityBound() {
                return descriptor.certificateIdentityBound();
            }
        };
    }

    private static ControlPlaneCertificateStatusSource.Cursor cursor() {
        return new ControlPlaneCertificateStatusSource.Cursor(0, fingerprint('0'));
    }

    private ControlPlaneCertificateStatusPublication publication(
            long sequence, String predecessor, Instant issuedAt, Instant expiresAt) {
        Instant evidenceTime = issuedAt.isAfter(NOW) ? NOW : issuedAt;
        var client = evidence(ControlPlaneCertificateStatusPublication.CertificateRole.CLIENT,
                evidenceTime, expiresAt.plusSeconds(60), 'c');
        var serverEvidence = evidence(
                ControlPlaneCertificateStatusPublication.CertificateRole.SERVER,
                evidenceTime, expiresAt.plusSeconds(60), 'd');
        var target = new ControlPlaneCertificateStatusPublication.TargetStatus(
                "recovery-fleet.publisher", 1, fingerprint('a'),
                List.of(client, serverEvidence));
        var material = new ControlPlaneCertificateStatusPublication.Material(
                ControlPlaneCertificateStatusPublication.Material.SCHEMA_VERSION,
                "enterprise-ca-status", "status-%03d".formatted(sequence), SCOPE,
                sequence, predecessor, fingerprint('f'), issuedAt, expiresAt,
                List.of(target));
        return new ControlPlaneCertificateStatusPublication(
                ControlPlaneCertificateStatusPublication.SCHEMA_VERSION, material,
                ProtocolFingerprint.of(objectMapper, material), List.of(
                new ControlPlaneCertificateStatusPublication.AuthoritySignature(
                        "authority-a", "key-a", "Ed25519", evidenceTime,
                        Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private ControlPlaneCertificateStatusSourceHead sourceHead(
            long sequence, String publicationFingerprint, Instant issuedAt, Instant expiresAt) {
        var material = new ControlPlaneCertificateStatusSourceHead.Material(
                ControlPlaneCertificateStatusSourceHead.Material.SCHEMA_VERSION,
                "enterprise-ca-status", "source-head-%03d-%d".formatted(
                sequence, issuedAt.getEpochSecond()), SCOPE, sequence,
                publicationFingerprint, fingerprint('f'), issuedAt, expiresAt);
        return new ControlPlaneCertificateStatusSourceHead(
                ControlPlaneCertificateStatusSourceHead.SCHEMA_VERSION, material,
                ProtocolFingerprint.of(objectMapper, material), List.of(
                new ControlPlaneCertificateStatusPublication.AuthoritySignature(
                        "authority-a", "key-a", "Ed25519", issuedAt,
                        Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private static ControlPlaneCertificateStatusSourceResponse response(
            ControlPlaneCertificateStatusPublication publication,
            ControlPlaneCertificateStatusSourceHead sourceHead) {
        return new ControlPlaneCertificateStatusSourceResponse(
                ControlPlaneCertificateStatusSourceResponse.SCHEMA_VERSION,
                sourceHead, publication);
    }

    private static ControlPlaneCertificateStatusPublication.CertificateEvidence evidence(
            ControlPlaneCertificateStatusPublication.CertificateRole role,
            Instant thisUpdate,
            Instant nextUpdate,
            char value) {
        return new ControlPlaneCertificateStatusPublication.CertificateEvidence(role,
                ControlPlaneCertificateStatusPublication.CertificateStatus.GOOD,
                ControlPlaneCertificateStatusPublication.EvidenceType.OCSP,
                fingerprint(value), fingerprint('e'), fingerprint('f'),
                "CERTIFICATE_GOOD", thisUpdate, thisUpdate, nextUpdate);
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            byte[] body,
            boolean contentType,
            boolean protocol) throws IOException {
        if (contentType) {
            exchange.getResponseHeaders().set("Content-Type",
                    HttpControlPlaneCertificateStatusSource.MEDIA_TYPE);
        }
        if (protocol) {
            exchange.getResponseHeaders().set(
                    HttpControlPlaneCertificateStatusSource.PROTOCOL_HEADER,
                    HttpControlPlaneCertificateStatusSource.PROTOCOL_VERSION);
        }
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
