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

class HttpControlPlaneCertificateRotationEventSourceTest {

    private static final String SCOPE = "resource-gateway-prod";
    private static final String HEAD = fingerprint('0');
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rotation/events", exchange -> handler.get().handle(exchange));
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                + "/rotation/events");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void fetchesOneStrictContiguousPageWithExactCursorHeadersAndQuery() throws Exception {
        var page = page(1, HEAD, NOW, NOW.plusSeconds(60));
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        handler.set(exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            protocol.set(exchange.getRequestHeaders().getFirst(
                    HttpControlPlaneCertificateRotationEventSource.PROTOCOL_HEADER));
            respond(exchange, 200, objectMapper.writeValueAsBytes(page), true, true);
        });

        var result = source().fetch(position());

        assertThat(result.status()).isEqualTo(
                ControlPlaneCertificateRotationEventSource.FetchStatus.PAGE);
        assertThat(result.page()).isEqualTo(page);
        assertThat(query.get()).contains("deploymentScopeId=" + SCOPE,
                "afterSequence=0", "afterPageFingerprint=sha256%3A");
        assertThat(accept.get()).isEqualTo(
                HttpControlPlaneCertificateRotationEventSource.MEDIA_TYPE);
        assertThat(protocol.get()).isEqualTo(
                HttpControlPlaneCertificateRotationEventSource.PROTOCOL_VERSION);
        assertThat(source().descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.authenticatedProtocol()).isTrue();
            assertThat(descriptor.privateTrustStore()).isTrue();
            assertThat(descriptor.serverSpkiPinned()).isTrue();
            assertThat(descriptor.mutualTls()).isTrue();
            assertThat(descriptor.certificateIdentityBound()).isTrue();
        });
    }

    @Test
    void acceptsOnlyHeaderBoundEmptyResponses() {
        handler.set(exchange -> respond(exchange, 204, new byte[0], false, true));
        assertThat(source().fetch(position()).status()).isEqualTo(
                ControlPlaneCertificateRotationEventSource.FetchStatus.NO_CHANGE);

        handler.set(exchange -> respond(exchange, 204, new byte[0], false, false));
        assertThat(source().fetch(position()).status()).isEqualTo(
                ControlPlaneCertificateRotationEventSource.FetchStatus.PROTOCOL_REJECTED);
    }

    @Test
    void rejectsMediaTypeProtocolAndPageFingerprintDowngrade() throws Exception {
        var page = page(1, HEAD, NOW, NOW.plusSeconds(60));
        handler.set(exchange -> respond(exchange, 200,
                objectMapper.writeValueAsBytes(page), false, true));
        assertThat(source().fetch(position()).reasonCode())
                .isEqualTo("EVENT_SOURCE_PROTOCOL_DOWNGRADE");

        handler.set(exchange -> respond(exchange, 200,
                objectMapper.writeValueAsBytes(page), true, false));
        assertThat(source().fetch(position()).reasonCode())
                .isEqualTo("EVENT_SOURCE_PROTOCOL_DOWNGRADE");

        var tampered = new ControlPlaneCertificateRotationEventPage(
                ControlPlaneCertificateRotationEventPage.SCHEMA_VERSION,
                page.material(), fingerprint('9'));
        handler.set(exchange -> respond(exchange, 200,
                objectMapper.writeValueAsBytes(tampered), true, true));
        assertThat(source().fetch(position()).reasonCode())
                .isEqualTo("EVENT_SOURCE_PAGE_INVALID");
    }

    @Test
    void rejectsGapForkExpiryFutureIssueAndExcessiveLifetime() throws Exception {
        assertRejected(page(2, HEAD, NOW, NOW.plusSeconds(60)));
        assertRejected(page(1, fingerprint('8'), NOW, NOW.plusSeconds(60)));
        assertRejected(page(1, HEAD, NOW.minusSeconds(120), NOW.minusSeconds(60)));
        assertRejected(page(1, HEAD, NOW.plusSeconds(301), NOW.plusSeconds(360)));
        assertRejected(page(1, HEAD, NOW, NOW.plusSeconds(3_601)));
    }

    @Test
    void boundsBodyBeforeParsingAndMapsMalformedJsonToProtocolRejection() {
        byte[] oversized = new byte[2_049];
        handler.set(exchange -> respond(exchange, 200, oversized, true, true));
        assertThat(source(2_048).fetch(position()).reasonCode())
                .isEqualTo("EVENT_SOURCE_PAGE_TOO_LARGE");

        handler.set(exchange -> respond(exchange, 200,
                "{not-json".getBytes(StandardCharsets.UTF_8), true, true));
        assertThat(source().fetch(position()).reasonCode())
                .isEqualTo("EVENT_SOURCE_PAGE_INVALID");
    }

    @Test
    void separatesTransientHttpFailureFromPermanentProtocolRejection() {
        handler.set(exchange -> respond(exchange, 503, new byte[0], false, false));
        assertThat(source().fetch(position()).status()).isEqualTo(
                ControlPlaneCertificateRotationEventSource.FetchStatus.SOURCE_UNAVAILABLE);

        handler.set(exchange -> respond(exchange, 409, new byte[0], false, false));
        assertThat(source().fetch(position()).status()).isEqualTo(
                ControlPlaneCertificateRotationEventSource.FetchStatus.PROTOCOL_REJECTED);
    }

    @Test
    void rejectsNonHttpsRemoteUrisQueryFragmentsAndWeakTransports() {
        assertThatThrownBy(() -> settings("http://example.com/events", false, 64 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(endpoint + "?cursor=caller-owned", true,
                64 * 1024)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(endpoint + "#fragment", true, 64 * 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpControlPlaneCertificateRotationEventSource(
                objectMapper, clock(), weakTransport(), settings()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned mTLS identity");
    }

    private void assertRejected(ControlPlaneCertificateRotationEventPage page) throws Exception {
        handler.set(exchange -> respond(exchange, 200,
                objectMapper.writeValueAsBytes(page), true, true));
        assertThat(source().fetch(position()).status()).isEqualTo(
                ControlPlaneCertificateRotationEventSource.FetchStatus.PROTOCOL_REJECTED);
    }

    private HttpControlPlaneCertificateRotationEventSource source() {
        return source(64 * 1024);
    }

    private HttpControlPlaneCertificateRotationEventSource source(int maximumBytes) {
        return new HttpControlPlaneCertificateRotationEventSource(
                objectMapper, clock(), strictTransport(),
                settings(endpoint.toString(), true, maximumBytes));
    }

    private HttpControlPlaneCertificateRotationEventSource.Settings settings() {
        return settings(endpoint.toString(), true, 64 * 1024);
    }

    private HttpControlPlaneCertificateRotationEventSource.Settings settings(
            String uri,
            boolean allowLoopback,
            int maximumBytes) {
        return new HttpControlPlaneCertificateRotationEventSource.Settings(
                uri, 2_000, maximumBytes, 300, 3_600, allowLoopback);
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

    private ControlPlaneHttpTransport transport(ControlPlaneHttpTransport.Descriptor descriptor) {
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

    private ControlPlaneCertificateRotationEventSource.Position position() {
        return new ControlPlaneCertificateRotationEventSource.Position(SCOPE, 0, HEAD);
    }

    private ControlPlaneCertificateRotationEventPage page(
            long sequence,
            String predecessor,
            Instant issuedAt,
            Instant expiresAt) {
        var material = new ControlPlaneCertificateRotationEventPage.Material(
                ControlPlaneCertificateRotationEventPage.Material.SCHEMA_VERSION,
                SCOPE, sequence, predecessor, issuedAt, expiresAt,
                List.of(event(issuedAt)));
        return new ControlPlaneCertificateRotationEventPage(
                ControlPlaneCertificateRotationEventPage.SCHEMA_VERSION,
                material, ProtocolFingerprint.of(objectMapper, material));
    }

    private ControlPlaneCertificateRotationEvent event(Instant issuedAt) {
        Instant eventIssued = issuedAt.isAfter(NOW) ? NOW : issuedAt;
        var material = new ControlPlaneCertificateRotationEvent.Material(
                ControlPlaneCertificateRotationEvent.Material.SCHEMA_VERSION,
                "certificate-authority", "rotation-002", SCOPE, "target-a", 2,
                fingerprint('a'), "candidate-b", fingerprint('b'), fingerprint('f'),
                eventIssued, eventIssued, eventIssued.plusSeconds(10),
                eventIssued.plusSeconds(7_200));
        return new ControlPlaneCertificateRotationEvent(
                ControlPlaneCertificateRotationEvent.SCHEMA_VERSION, material,
                ProtocolFingerprint.of(objectMapper, material),
                List.of(new ControlPlaneCertificateRotationEvent.AuthoritySignature(
                        "authority-a", "key-a", "Ed25519", eventIssued,
                        Base64.getEncoder().encodeToString(new byte[64]))));
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
                    HttpControlPlaneCertificateRotationEventSource.MEDIA_TYPE);
        }
        if (protocol) {
            exchange.getResponseHeaders().set(
                    HttpControlPlaneCertificateRotationEventSource.PROTOCOL_HEADER,
                    HttpControlPlaneCertificateRotationEventSource.PROTOCOL_VERSION);
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
