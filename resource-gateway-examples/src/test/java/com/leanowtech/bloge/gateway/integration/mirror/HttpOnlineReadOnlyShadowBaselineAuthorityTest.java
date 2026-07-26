package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneHttpTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOnlineReadOnlyShadowBaselineAuthorityTest {
    private final ObjectMapper mapper =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .mapper();
    private final OnlineReadOnlyShadowBaselineObservationIntegrity
            integrity =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .integrity(mapper);

    @Test
    void probesExecutesIdempotentlyAndReadsExactPayloadFreeEvidence()
            throws Exception {
        AtomicReference<byte[]> commandBody =
                new AtomicReference<>();
        AtomicReference<OnlineReadOnlyShadowBaselineObservation>
                stored = new AtomicReference<>();
        AtomicInteger posts = new AtomicInteger();
        List<URI> requests = new ArrayList<>();
        AtomicReference<String> executionHeader =
                new AtomicReference<>();
        try (TestServer server = new TestServer(exchange -> {
            requests.add(exchange.getRequestURI());
            if (exchange.getRequestURI().getPath()
                    .endsWith("/capabilities")) {
                respond(
                        exchange,
                        200,
                        mapper.writeValueAsBytes(
                                readyCapability()),
                        protocolHeaders());
                return;
            }
            if ("POST".equals(
                    exchange.getRequestMethod())) {
                posts.incrementAndGet();
                commandBody.set(
                        exchange.getRequestBody()
                                .readAllBytes());
                executionHeader.set(
                        exchange.getRequestHeaders()
                                .getFirst(
                                        OnlineReadOnlyShadowBaselineProtocol
                                                .EXECUTION_ID_HEADER));
                OnlineReadOnlyShadowBaselineCommand
                        command = mapper.readValue(
                        commandBody.get(),
                        OnlineReadOnlyShadowBaselineCommand
                                .class);
                OnlineReadOnlyShadowBaselineObservation
                        observation = integrity.sign(
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .unsigned(
                                        mapper, command));
                stored.compareAndSet(
                        null, observation);
                respond(
                        exchange,
                        200,
                        mapper.writeValueAsBytes(
                                stored.get()),
                        protocolHeaders());
                return;
            }
            respond(
                    exchange,
                    200,
                    mapper.writeValueAsBytes(
                            stored.get()),
                    protocolHeaders());
        })) {
            HttpOnlineReadOnlyShadowBaselineAuthority
                    authority = authority(
                    server.uri(), 512 * 1024);
            OnlineReadOnlyShadowBaselineCommand command =
                    OnlineReadOnlyShadowBaselineTestFixtures
                            .command(mapper);

            assertThat(authority.ready()).isTrue();
            OnlineReadOnlyShadowBaselineObservation first =
                    authority.observe(command);
            OnlineReadOnlyShadowBaselineObservation retry =
                    authority.observe(command);
            OnlineReadOnlyShadowBaselineObservation resolved =
                    authority.resolve(
                            command.scope(),
                            first.artifactRef());

            assertThat(posts).hasValue(2);
            assertThat(first).isEqualTo(retry)
                    .isEqualTo(resolved);
            assertThat(executionHeader.get())
                    .isEqualTo(command.executionId());
            ObjectNode sent = (ObjectNode) mapper.readTree(
                    commandBody.get());
            assertThat(sent.fieldNames())
                    .toIterable()
                    .doesNotContain(
                            "payload",
                            "requestPayload",
                            "responsePayload",
                            "endpoint",
                            "credential",
                            "secret");
            assertThat(requests.getLast()
                    .getRawQuery())
                    .contains(
                            "tenantId=support",
                            "organizationId=customer-operations",
                            "environmentId=staging",
                            "fingerprint=sha256%3A");
            assertThat(server.failure()).isNull();
        }
    }

    @Test
    void failsReadinessForStaleOrIncompleteCapability()
            throws Exception {
        var stale =
                new OnlineReadOnlyShadowBaselineProtocol
                        .Capability(
                        OnlineReadOnlyShadowBaselineProtocol
                                .Capability.SCHEMA_VERSION,
                        OnlineReadOnlyShadowBaselineProtocol
                                .VERSION,
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .NOW.minusSeconds(60),
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .NOW.minusSeconds(1),
                        true,
                        true,
                        true,
                        true,
                        true,
                        true,
                        true);
        try (TestServer server = fixed(
                mapper.writeValueAsBytes(stale),
                protocolHeaders())) {
            assertThat(authority(
                    server.uri(), 1024).ready())
                    .isFalse();
        }
        var incomplete =
                new OnlineReadOnlyShadowBaselineProtocol
                        .Capability(
                        OnlineReadOnlyShadowBaselineProtocol
                                .Capability.SCHEMA_VERSION,
                        OnlineReadOnlyShadowBaselineProtocol
                                .VERSION,
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .NOW,
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .NOW.plusSeconds(30),
                        true,
                        true,
                        true,
                        true,
                        true,
                        false,
                        true);
        try (TestServer server = fixed(
                mapper.writeValueAsBytes(incomplete),
                protocolHeaders())) {
            assertThat(authority(
                    server.uri(), 1024).ready())
                    .isFalse();
        }
    }

    @Test
    void rejectsProtocolDowngradeUnknownPayloadFieldAndOversize()
            throws Exception {
        OnlineReadOnlyShadowBaselineCommand command =
                OnlineReadOnlyShadowBaselineTestFixtures
                        .command(mapper);
        byte[] signed = mapper.writeValueAsBytes(
                integrity.sign(
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .unsigned(mapper, command)));
        try (TestServer server = fixed(
                signed,
                Map.of(
                        "Content-Type",
                        "application/json",
                        OnlineReadOnlyShadowBaselineProtocol
                                .VERSION_HEADER,
                        OnlineReadOnlyShadowBaselineProtocol
                                .VERSION))) {
            assertReason(
                    authority(server.uri(), 1024),
                    command,
                    "ONLINE_BASELINE_PROTOCOL_DOWNGRADE");
        }

        ObjectNode unknown = (ObjectNode) mapper
                .readTree(signed);
        unknown.put("payload", "must-not-cross");
        try (TestServer server = fixed(
                mapper.writeValueAsBytes(unknown),
                protocolHeaders())) {
            assertReason(
                    authority(server.uri(), 512 * 1024),
                    command,
                    "ONLINE_BASELINE_RESPONSE_INVALID");
        }

        byte[] oversized = ("{"
                + "\"padding\":\""
                + "x".repeat(1100)
                + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        try (TestServer server = fixed(
                oversized,
                protocolHeaders())) {
            assertReason(
                    authority(server.uri(), 1024),
                    command,
                    "ONLINE_BASELINE_BODY_TOO_LARGE");
        }
    }

    @Test
    void doesNotFollowRedirectAndRejectsAnUnpinnedTransport()
            throws Exception {
        AtomicInteger redirected = new AtomicInteger();
        try (TestServer server = new TestServer(exchange -> {
            if (exchange.getRequestURI().getPath()
                    .equals("/redirected")) {
                redirected.incrementAndGet();
                respond(
                        exchange,
                        200,
                        mapper.writeValueAsBytes(
                                readyCapability()),
                        protocolHeaders());
                return;
            }
            respond(
                    exchange,
                    302,
                    new byte[0],
                    Map.of("Location", "/redirected"));
        })) {
            assertThat(authority(
                    server.uri(), 1024).ready())
                    .isFalse();
            assertThat(redirected).hasValue(0);
        }

        OnlineReadOnlyShadowBaselineTransport insecure =
                OnlineReadOnlyShadowBaselineTransport
                        .from(
                                new ControlPlaneHttpTransport() {
                                    @Override
                                    public HttpClient client(
                                            Duration timeout) {
                                        return HttpClient
                                                .newHttpClient();
                                    }

                                    @Override
                                    public Descriptor descriptor() {
                                        return new Descriptor(
                                                Descriptor
                                                        .SCHEMA_VERSION,
                                                true,
                                                false,
                                                false,
                                                false,
                                                false);
                                    }
                                });
        assertThatThrownBy(() ->
                new HttpOnlineReadOnlyShadowBaselineAuthority(
                        mapper,
                        OnlineReadOnlyShadowBaselineTestFixtures
                                .CLOCK,
                        insecure,
                        settings(
                                URI.create(
                                        "https://baseline.example.test"),
                                1024,
                                false),
                        (operation, uri) -> Map.of()))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "private pinned mTLS");
    }

    private HttpOnlineReadOnlyShadowBaselineAuthority authority(
            URI uri,
            int maximumBytes) {
        return new HttpOnlineReadOnlyShadowBaselineAuthority(
                mapper,
                OnlineReadOnlyShadowBaselineTestFixtures
                        .CLOCK,
                secureTransport(),
                settings(uri, maximumBytes, true),
                (operation, target) -> Map.of(
                        "Authorization",
                        "BLOGE workload-signature"));
    }

    private static HttpOnlineReadOnlyShadowBaselineAuthority
            .Settings settings(
            URI uri,
            int maximumBytes,
            boolean loopback) {
        return new HttpOnlineReadOnlyShadowBaselineAuthority
                .Settings(
                uri,
                Duration.ofSeconds(2),
                maximumBytes,
                loopback);
    }

    private static OnlineReadOnlyShadowBaselineTransport
    secureTransport() {
        return new OnlineReadOnlyShadowBaselineTransport() {
            @Override
            public HttpClient client(
                    Duration connectTimeout) {
                return HttpClient.newBuilder()
                        .connectTimeout(connectTimeout)
                        .followRedirects(
                                HttpClient.Redirect.NEVER)
                        .build();
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor(
                        Descriptor.SCHEMA_VERSION,
                        false,
                        true,
                        true,
                        true,
                        true);
            }
        };
    }

    private OnlineReadOnlyShadowBaselineProtocol.Capability
    readyCapability() {
        return new OnlineReadOnlyShadowBaselineProtocol
                .Capability(
                OnlineReadOnlyShadowBaselineProtocol
                        .Capability.SCHEMA_VERSION,
                OnlineReadOnlyShadowBaselineProtocol
                        .VERSION,
                OnlineReadOnlyShadowBaselineTestFixtures
                        .NOW.plusSeconds(3),
                OnlineReadOnlyShadowBaselineTestFixtures
                        .NOW.plusSeconds(60),
                true,
                true,
                true,
                true,
                true,
                true,
                true);
    }

    private static void assertReason(
            HttpOnlineReadOnlyShadowBaselineAuthority
                    authority,
            OnlineReadOnlyShadowBaselineCommand command,
            String reason) {
        assertThatThrownBy(() ->
                authority.observe(command))
                .isInstanceOf(
                        OnlineReadOnlyShadowBaselineAuthority
                                .AuthorityException.class)
                .extracting("reasonCode")
                .isEqualTo(reason);
    }

    private static TestServer fixed(
            byte[] body,
            Map<String, String> headers)
            throws IOException {
        return new TestServer(exchange ->
                respond(
                        exchange,
                        200,
                        body,
                        headers));
    }

    private static Map<String, String> protocolHeaders() {
        return Map.of(
                "Content-Type",
                OnlineReadOnlyShadowBaselineProtocol
                        .MEDIA_TYPE,
                OnlineReadOnlyShadowBaselineProtocol
                        .VERSION_HEADER,
                OnlineReadOnlyShadowBaselineProtocol
                        .VERSION);
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            byte[] body,
            Map<String, String> headers)
            throws IOException {
        headers.forEach(
                (name, value) ->
                        exchange.getResponseHeaders()
                                .set(name, value));
        exchange.sendResponseHeaders(
                status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange)
                throws Exception;
    }

    private static final class TestServer
            implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<Throwable> failure =
                new AtomicReference<>();

        private TestServer(
                ExchangeHandler handler)
                throws IOException {
            server = HttpServer.create(
                    new InetSocketAddress(
                            "127.0.0.1", 0),
                    0);
            server.createContext("/", exchange -> {
                try {
                    handler.handle(exchange);
                } catch (Throwable failed) {
                    failure.compareAndSet(
                            null, failed);
                    try {
                        respond(
                                exchange,
                                500,
                                new byte[0],
                                Map.of());
                    } catch (IOException ignored) {
                        exchange.close();
                    }
                }
            });
            server.start();
        }

        private URI uri() {
            return URI.create(
                    "http://127.0.0.1:"
                            + server.getAddress()
                            .getPort());
        }

        private Throwable failure() {
            return failure.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
