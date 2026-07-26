package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneHttpTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpOnlineReadOnlyShadowCandidateAuthorityTest {
    private static final String FIXTURE =
            "online-read-only-shadow-source-resolution-stage1-v1.fixture.json";
    private final ObjectMapper mapper =
            OnlineReadOnlyShadowBaselineTestFixtures
                    .mapper();
    private OnlineReadOnlyShadowCandidateCommand command;
    private MirrorEvidenceBundle bundle;
    private Clock clock;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode fixture = mapper.readTree(
                Files.readString(fixturePath()));
        command = mapper.treeToValue(
                fixture.path("candidateCommand"),
                OnlineReadOnlyShadowCandidateCommand
                        .class);
        bundle = mapper.treeToValue(
                fixture.path("candidateEvidenceBundle"),
                MirrorEvidenceBundle.class);
        clock = Clock.fixed(
                Instant.parse(
                        fixture.path("verificationTime")
                                .asText()),
                ZoneOffset.UTC);
    }

    @Test
    void probesExecutesIdempotentlyAndReadsExactPayloadFreeEvidence()
            throws Exception {
        AtomicReference<byte[]> commandBody =
                new AtomicReference<>();
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
                                        OnlineReadOnlyShadowCandidateProtocol
                                                .EXECUTION_ID_HEADER));
                assertThat(mapper.readValue(
                        commandBody.get(),
                        OnlineReadOnlyShadowCandidateCommand
                                .class))
                        .isEqualTo(command);
            }
            respond(
                    exchange,
                    200,
                    mapper.writeValueAsBytes(bundle),
                    protocolHeaders());
        })) {
            HttpOnlineReadOnlyShadowCandidateAuthority
                    authority = authority(
                    server.uri(),
                    Duration.ofSeconds(2),
                    2 * 1024 * 1024);

            assertThat(authority.ready()).isTrue();
            MirrorEvidenceBundle first =
                    authority.execute(command);
            MirrorEvidenceBundle retry =
                    authority.execute(command);
            MirrorEvidenceBundle resolved =
                    authority.resolve(
                            command.scope(),
                            reference(bundle));

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
                            "tenantId=tenant-a",
                            "organizationId=support",
                            "environmentId=test",
                            "fingerprint=sha256%3A");
            assertThat(server.failure()).isNull();
        }
    }

    @Test
    void failsReadinessForStaleOrIncompleteCapability()
            throws Exception {
        var stale =
                new OnlineReadOnlyShadowCandidateProtocol
                        .Capability(
                        OnlineReadOnlyShadowCandidateProtocol
                                .Capability.SCHEMA_VERSION,
                        OnlineReadOnlyShadowCandidateProtocol
                                .VERSION,
                        clock.instant().minusSeconds(60),
                        clock.instant().minusSeconds(1),
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
                    server.uri(),
                    Duration.ofSeconds(2),
                    4096).ready()).isFalse();
        }
        var incomplete =
                new OnlineReadOnlyShadowCandidateProtocol
                        .Capability(
                        OnlineReadOnlyShadowCandidateProtocol
                                .Capability.SCHEMA_VERSION,
                        OnlineReadOnlyShadowCandidateProtocol
                                .VERSION,
                        clock.instant(),
                        clock.instant().plusSeconds(30),
                        true,
                        true,
                        false,
                        true,
                        true,
                        true,
                        true);
        try (TestServer server = fixed(
                mapper.writeValueAsBytes(incomplete),
                protocolHeaders())) {
            assertThat(authority(
                    server.uri(),
                    Duration.ofSeconds(2),
                    4096).ready()).isFalse();
        }
    }

    @Test
    void rejectsDowngradeUnknownFieldsDuplicateFieldsAndOversize()
            throws Exception {
        byte[] exact = mapper.writeValueAsBytes(bundle);
        try (TestServer server = fixed(
                exact,
                Map.of(
                        "Content-Type",
                        "application/json",
                        OnlineReadOnlyShadowCandidateProtocol
                                .VERSION_HEADER,
                        OnlineReadOnlyShadowCandidateProtocol
                                .VERSION))) {
            assertExecuteReason(
                    authority(
                            server.uri(),
                            Duration.ofSeconds(2),
                            2 * 1024 * 1024),
                    "ONLINE_CANDIDATE_PROTOCOL_DOWNGRADE");
        }

        ObjectNode unknown = (ObjectNode) mapper
                .readTree(exact);
        unknown.put("payload", "must-not-cross");
        try (TestServer server = fixed(
                mapper.writeValueAsBytes(unknown),
                protocolHeaders())) {
            assertExecuteReason(
                    authority(
                            server.uri(),
                            Duration.ofSeconds(2),
                            2 * 1024 * 1024),
                    "ONLINE_CANDIDATE_RESPONSE_INVALID");
        }

        String duplicate =
                mapper.writeValueAsString(bundle)
                        .replaceFirst(
                                "\\{",
                                "{\"schemaVersion\":\""
                                        + bundle.schemaVersion()
                                        + "\",");
        try (TestServer server = fixed(
                duplicate.getBytes(
                        StandardCharsets.UTF_8),
                protocolHeaders())) {
            assertExecuteReason(
                    authority(
                            server.uri(),
                            Duration.ofSeconds(2),
                            2 * 1024 * 1024),
                    "ONLINE_CANDIDATE_RESPONSE_INVALID");
        }

        byte[] oversized = ("{"
                + "\"padding\":\""
                + "x".repeat(1100)
                + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        try (TestServer server = fixed(
                oversized,
                protocolHeaders())) {
            assertExecuteReason(
                    authority(
                            server.uri(),
                            Duration.ofSeconds(2),
                            1024),
                    "ONLINE_CANDIDATE_BODY_TOO_LARGE");
        }
    }

    @Test
    void classifiesTimeoutAndHttpFailuresWithoutFollowingRedirects()
            throws Exception {
        try (TestServer server = new TestServer(exchange -> {
            Thread.sleep(500);
            respond(
                    exchange,
                    200,
                    mapper.writeValueAsBytes(bundle),
                    protocolHeaders());
        })) {
            assertExecuteReason(
                    authority(
                            server.uri(),
                            Duration.ofMillis(100),
                            2 * 1024 * 1024),
                    "ONLINE_CANDIDATE_UNAVAILABLE");
        }

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
                    server.uri(),
                    Duration.ofSeconds(2),
                    4096).ready()).isFalse();
            assertThat(redirected).hasValue(0);
        }

        try (TestServer server = new TestServer(exchange ->
                respond(
                        exchange,
                        429,
                        "{}".getBytes(
                                StandardCharsets.UTF_8),
                        protocolHeaders()))) {
            assertThatThrownBy(() ->
                    authority(
                            server.uri(),
                            Duration.ofSeconds(2),
                            4096)
                            .execute(command))
                    .isInstanceOf(
                            OnlineReadOnlyShadowCandidateAuthority
                                    .AuthorityException.class)
                    .extracting("failure")
                    .isEqualTo(
                            OnlineReadOnlyShadowCandidateAuthority
                                    .Failure.UNAVAILABLE);
        }
    }

    @Test
    void rejectsExactReadCoordinateDriftAndUnpinnedTransport()
            throws Exception {
        try (TestServer server = fixed(
                mapper.writeValueAsBytes(bundle),
                protocolHeaders())) {
            MirrorArtifactRef wrong =
                    new MirrorArtifactRef(
                            "MIRROR_EVIDENCE_BUNDLE",
                            bundle.evidence().runId(),
                            1,
                            OnlineReadOnlyShadowBaselineTestFixtures
                                    .fingerprint('f'));
            assertThatThrownBy(() ->
                    authority(
                            server.uri(),
                            Duration.ofSeconds(2),
                            2 * 1024 * 1024)
                            .resolve(
                                    command.scope(),
                                    wrong))
                    .isInstanceOf(
                            OnlineReadOnlyShadowCandidateAuthority
                                    .AuthorityException.class)
                    .extracting("reasonCode")
                    .isEqualTo(
                            "ONLINE_CANDIDATE_EXACT_READ_MISMATCH");
        }

        OnlineReadOnlyShadowCandidateTransport insecure =
                OnlineReadOnlyShadowCandidateTransport
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
                new HttpOnlineReadOnlyShadowCandidateAuthority(
                        mapper,
                        clock,
                        insecure,
                        settings(
                                URI.create(
                                        "https://candidate.example.test"),
                                Duration.ofSeconds(2),
                                4096,
                                false),
                        (operation, uri) -> Map.of()))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining(
                        "private pinned mTLS");
    }

    @Test
    void rejectsExecutionCoordinateDriftAndReservedAuthorizationHeaders()
            throws Exception {
        OnlineReadOnlyShadowCandidateCommand altered =
                new OnlineReadOnlyShadowCandidateCommand(
                        command.schemaVersion(),
                        command.executionId() + "-other",
                        command.requestId(),
                        command.scope(),
                        command.inventoryRef(),
                        command.unitId(),
                        command.scenarioCaseRef(),
                        command.targetCapabilityRef(),
                        command.candidatePlanRef(),
                        command.comparisonPolicyRef(),
                        command.baselineObservationRef(),
                        command.payloadVaultReceiptRef(),
                        command.requestContextFingerprint(),
                        command.accessGrant(),
                        command.admissionFingerprint(),
                        command.admittedAt(),
                        command.deadlineAt());
        try (TestServer server = fixed(
                mapper.writeValueAsBytes(bundle),
                protocolHeaders())) {
            assertThatThrownBy(() ->
                    authority(
                            server.uri(),
                            Duration.ofSeconds(2),
                            2 * 1024 * 1024)
                            .execute(altered))
                    .isInstanceOf(
                            OnlineReadOnlyShadowCandidateAuthority
                                    .AuthorityException.class)
                    .extracting("reasonCode")
                    .isEqualTo(
                            "ONLINE_CANDIDATE_EXECUTION_COORDINATES_MISMATCH");

            HttpOnlineReadOnlyShadowCandidateAuthority
                    headerOverride =
                    new HttpOnlineReadOnlyShadowCandidateAuthority(
                            mapper,
                            clock,
                            secureTransport(),
                            settings(
                                    server.uri(),
                                    Duration.ofSeconds(2),
                                    2 * 1024 * 1024,
                                    true),
                            (operation, uri) -> Map.of(
                                    "Content-Type",
                                    "application/json"));
            assertThatThrownBy(() ->
                    headerOverride.execute(command))
                    .isInstanceOf(
                            OnlineReadOnlyShadowCandidateAuthority
                                    .AuthorityException.class)
                    .extracting("reasonCode")
                    .isEqualTo(
                            "ONLINE_CANDIDATE_AUTHORIZATION_HEADERS_INVALID");
        }
    }

    private HttpOnlineReadOnlyShadowCandidateAuthority authority(
            URI uri,
            Duration timeout,
            int maximumBytes) {
        return new HttpOnlineReadOnlyShadowCandidateAuthority(
                mapper,
                clock,
                secureTransport(),
                settings(
                        uri,
                        timeout,
                        maximumBytes,
                        true),
                (operation, target) -> Map.of(
                        "Authorization",
                        "BLOGE workload-signature"));
    }

    private static HttpOnlineReadOnlyShadowCandidateAuthority
            .Settings settings(
            URI uri,
            Duration timeout,
            int maximumBytes,
            boolean loopback) {
        return new HttpOnlineReadOnlyShadowCandidateAuthority
                .Settings(
                uri,
                timeout,
                maximumBytes,
                loopback);
    }

    private static OnlineReadOnlyShadowCandidateTransport
    secureTransport() {
        return new OnlineReadOnlyShadowCandidateTransport() {
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

    private OnlineReadOnlyShadowCandidateProtocol.Capability
    readyCapability() {
        return new OnlineReadOnlyShadowCandidateProtocol
                .Capability(
                OnlineReadOnlyShadowCandidateProtocol
                        .Capability.SCHEMA_VERSION,
                OnlineReadOnlyShadowCandidateProtocol
                        .VERSION,
                clock.instant(),
                clock.instant().plusSeconds(60),
                true,
                true,
                true,
                true,
                true,
                true,
                true);
    }

    private void assertExecuteReason(
            HttpOnlineReadOnlyShadowCandidateAuthority
                    authority,
            String reason) {
        assertThatThrownBy(() ->
                authority.execute(command))
                .isInstanceOf(
                        OnlineReadOnlyShadowCandidateAuthority
                                .AuthorityException.class)
                .extracting("reasonCode")
                .isEqualTo(reason);
    }

    private static MirrorArtifactRef reference(
            MirrorEvidenceBundle value) {
        return new MirrorArtifactRef(
                "MIRROR_EVIDENCE_BUNDLE",
                value.evidence().runId(),
                1,
                value.bundleFingerprint());
    }

    private static Path fixturePath() {
        Path moduleRelative = Path.of(
                "..", "docs", "schemas",
                "resource-gateway-mirror",
                FIXTURE);
        return Files.exists(moduleRelative)
                ? moduleRelative
                : Path.of(
                        "docs", "schemas",
                        "resource-gateway-mirror",
                        FIXTURE);
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
                OnlineReadOnlyShadowCandidateProtocol
                        .MEDIA_TYPE,
                OnlineReadOnlyShadowCandidateProtocol
                        .VERSION_HEADER,
                OnlineReadOnlyShadowCandidateProtocol
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
