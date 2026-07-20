package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTestSecretAuthorityTest {

    private ObjectMapper objectMapper;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        keyPair = keyPair();
    }

    @Test
    void resolvesAnExactSignedClosureWithFreshCredentialFreeChallenges() throws Exception {
        AtomicReference<String> firstChallenge = new AtomicReference<>();
        try (AuthorityServer server = AuthorityServer.start(objectMapper, observed -> {
            firstChallenge.compareAndSet(null, observed.request().challenge());
            return json(response(objectMapper, keyPair, observed.request(),
                    TestSecretAuthorityResponse.Decision.AUTHORIZED, ""));
        })) {
            HttpTestSecretAuthority authority = authority(server.baseUri(), Duration.ofSeconds(1));
            ResolvedTestSecrets first = authority.resolve(context());
            ResolvedTestSecrets second = authority.resolve(context());

            assertThat(first.resolve(ALIAS)).isEqualTo(VALUE);
            assertThat(second.resolve(ALIAS)).isEqualTo(VALUE);
            assertThat(server.requests()).isEqualTo(2);
            assertThat(server.lastRequest()).satisfies(observed -> {
                assertThat(observed.method()).isEqualTo("POST");
                assertThat(observed.idempotencyKey())
                        .isEqualTo(observed.request().requestId());
                assertThat(observed.body()).contains(REFERENCE, "RESOLVE_TEST_SECRET_CLOSURE")
                        .doesNotContain(VALUE, "Bearer ", "credential", "correlationId",
                                "graphInput", "fixturePayload", "privateKey");
                assertThat(observed.request().challenge()).isNotEqualTo(firstChallenge.get());
            });
            assertThat(ResolvedTestSecrets.verified(
                    objectMapper, second, context(), NOW).resolve(ALIAS)).isEqualTo(VALUE);
            assertThat(authority.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.available()).isTrue();
                assertThat(descriptor.providerType())
                        .isEqualTo("HTTPS_SIGNED_TEST_SECRET_AUTHORITY");
                assertThat(descriptor.properties())
                        .containsEntry("signedResponses", true)
                        .containsEntry("challengeBound", true)
                        .containsEntry("credentialFree", true)
                        .containsEntry("redirectsFollowed", false)
                        .containsEntry("automaticRetries", false)
                        .containsEntry("trustCohortConfigured", false)
                        .containsEntry("trustCohortAvailable", true)
                        .containsEntry("trustCohortStatus", "LOCAL_ONLY")
                        .doesNotContainKeys("baseUri", "publicKey", "privateKey", "secret");
            });
        }
    }

    @Test
    void acceptsOnlySignedDenialAsDefinitivePolicyTruth() throws Exception {
        try (AuthorityServer server = AuthorityServer.start(objectMapper, observed -> json(response(
                objectMapper, keyPair, observed.request(),
                TestSecretAuthorityResponse.Decision.DENIED,
                "RG.POLICY.SECRET_DENIED")))) {
            assertResolutionReason(authority(server.baseUri(), Duration.ofSeconds(1)),
                    TestSecretAuthority.Reason.DENIED);
        }
        try (AuthorityServer server = AuthorityServer.start(objectMapper,
                observed -> new Reply(403, "application/json", "{}"))) {
            assertResolutionReason(authority(server.baseUri(), Duration.ofSeconds(1)),
                    TestSecretAuthority.Reason.UNAVAILABLE);
        }
    }

    @Test
    void rejectsTamperedSignatureAndCrossRequestReplay() throws Exception {
        try (AuthorityServer server = AuthorityServer.start(objectMapper, observed -> json(response(
                objectMapper, keyPair(), observed.request(),
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "")))) {
            assertResolutionReason(authority(server.baseUri(), Duration.ofSeconds(1)),
                    TestSecretAuthority.Reason.INVALID_RESPONSE);
        }
        try (AuthorityServer server = AuthorityServer.start(objectMapper, observed -> {
            String otherChallenge = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(new byte[33]);
            return json(response(objectMapper, keyPair, observed.request(),
                    TestSecretAuthorityResponse.Decision.AUTHORIZED, "", otherChallenge,
                    AUTHORITY_ID, AUTHORITY_GENERATION, KEY_ID, NOW,
                    NOW.plusSeconds(30), VALUE));
        })) {
            assertResolutionReason(authority(server.baseUri(), Duration.ofSeconds(1)),
                    TestSecretAuthority.Reason.INVALID_RESPONSE);
        }
    }

    @Test
    void rejectsDuplicateUnknownNonJsonAndOversizedResponses() throws Exception {
        assertInvalid(observed -> {
            Reply valid = signed(observed.request());
            String duplicate = valid.body().replaceFirst(
                    "\\\"requestId\\\":\\\"", "\"requestId\":\"duplicate\",\"requestId\":\"");
            return new Reply(200, "application/json", duplicate);
        });
        assertInvalid(observed -> {
            Reply valid = signed(observed.request());
            return new Reply(200, "application/json",
                    valid.body().substring(0, valid.body().length() - 1)
                            + ",\"unknown\":true}");
        });
        assertInvalid(observed -> new Reply(200, "text/plain", "not-json"));
        assertInvalid(observed -> new Reply(
                200, "application/json", "x".repeat(2 * 1024 * 1024 + 1)));
    }

    @Test
    void neverFollowsRedirectAndMapsTimeoutToUnavailable() throws Exception {
        try (AuthorityServer server = AuthorityServer.start(objectMapper,
                observed -> new Reply(302, "application/json", "{}"))) {
            assertResolutionReason(authority(server.baseUri(), Duration.ofSeconds(1)),
                    TestSecretAuthority.Reason.UNAVAILABLE);
            assertThat(server.requests()).isOne();
        }
        try (AuthorityServer server = AuthorityServer.start(objectMapper, observed -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return signed(observed.request());
        })) {
            assertResolutionReason(authority(server.baseUri(), Duration.ofMillis(100)),
                    TestSecretAuthority.Reason.UNAVAILABLE);
        }
    }

    @Test
    void cohortConvergenceIsRequiredBeforeNetworkAndAfterSignatureVerification()
            throws Exception {
        try (AuthorityServer server = AuthorityServer.start(objectMapper,
                observed -> signed(observed.request()))) {
            HttpTestSecretAuthority preflightBlocked = authority(
                    server.baseUri(), Duration.ofSeconds(1), () -> divergentCohort());
            assertResolutionReason(preflightBlocked, TestSecretAuthority.Reason.UNAVAILABLE);
            assertThat(server.requests()).isZero();

            AtomicInteger reads = new AtomicInteger();
            HttpTestSecretAuthority changedInFlight = authority(
                    server.baseUri(), Duration.ofSeconds(1), () ->
                            reads.incrementAndGet() == 1 ? convergedCohort() : divergentCohort());
            assertResolutionReason(changedInFlight, TestSecretAuthority.Reason.UNAVAILABLE);
            assertThat(server.requests()).isOne();
            assertThat(changedInFlight.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.available()).isFalse();
                assertThat(descriptor.properties())
                        .containsEntry("trustCohortConfigured", true)
                        .containsEntry("trustCohortAvailable", false)
                        .containsEntry("trustCohortStatus", "SNAPSHOT_DIVERGED")
                        .containsEntry("trustCohortDistinctGenerationCount", 2)
                        .doesNotContainKeys("instanceId", "startupId", "snapshotFingerprint");
            });
        }
    }

    @Test
    void servingInventoryRevocationBlocksNetworkAndDynamicTruthReachesDescriptor()
            throws Exception {
        try (AuthorityServer server = AuthorityServer.start(objectMapper,
                observed -> signed(observed.request()))) {
            var trustStore = new ConfiguredTestSecretAuthorityTrustStore(
                    objectMapper, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                    Duration.ofMillis(10), List.of(
                    new ConfiguredTestSecretAuthorityTrustStore.AuthorityKey(
                            KEY_ID, keyPair.getPublic(), null, null, true, false)));
            HttpTestSecretAuthority blocked = new HttpTestSecretAuthority(
                    objectMapper, trustStore, TestSecretAuthorityTrustCohortGate.localOnly(),
                    inventory(false),
                    new HttpTestSecretAuthority.Settings(
                            server.baseUri(), Duration.ofSeconds(1), true),
                    Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(),
                    HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(Duration.ofSeconds(1)).build());

            assertResolutionReason(blocked, TestSecretAuthority.Reason.UNAVAILABLE);
            assertThat(server.requests()).isZero();
            assertThat(blocked.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.available()).isFalse();
                assertThat(descriptor.properties())
                        .containsEntry("servingInventorySourceType",
                                DynamicTestSecretAuthorityServingInventoryAuthority.SOURCE_TYPE)
                        .containsEntry("servingInventoryAvailable", false)
                        .containsEntry("servingInventoryStatus", "REVOKED")
                        .containsEntry("servingInventoryAutomaticRefresh", true)
                        .containsEntry("servingInventorySignedRevocation", true)
                        .containsEntry("servingInventoryWitnessedPublications", true)
                        .containsEntry("servingInventoryDurablePublicationFloor", true)
                        .doesNotContainKeys("publicationId", "fingerprint", "instanceIds",
                                "authorityKey", "witnessKey", "uri");
            });
        }
    }

    @Test
    void settingsRequireHttpsExceptForExplicitLoopbackTests() {
        assertThatThrownBy(() -> new HttpTestSecretAuthority.Settings(
                URI.create("http://authority.example"), Duration.ofSeconds(1), false).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new HttpTestSecretAuthority.Settings(
                URI.create("http://127.0.0.1"), Duration.ofSeconds(1), false).validated())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpTestSecretAuthority.Settings(
                URI.create("https://user@authority.example"),
                Duration.ofSeconds(1), false).validated())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new HttpTestSecretAuthority.Settings(
                URI.create("http://127.0.0.1"), Duration.ofSeconds(1), true).validated()
                .baseUri()).hasToString("http://127.0.0.1");
        assertThat(new HttpTestSecretAuthority.Settings(
                URI.create("https://authority.example/secrets"),
                Duration.ofSeconds(1), false).validated().baseUri())
                .hasToString("https://authority.example/secrets");
    }

    private HttpTestSecretAuthority authority(URI baseUri, Duration timeout) {
        return authority(baseUri, timeout, TestSecretAuthorityTrustCohortGate.localOnly());
    }

    private HttpTestSecretAuthority authority(
            URI baseUri,
            Duration timeout,
            TestSecretAuthorityTrustCohortGate cohortGate) {
        var trustStore = new ConfiguredTestSecretAuthorityTrustStore(
                objectMapper, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                Duration.ofMillis(10), List.of(
                new ConfiguredTestSecretAuthorityTrustStore.AuthorityKey(
                        KEY_ID, keyPair.getPublic(), null, null, true, false)));
        return new HttpTestSecretAuthority(objectMapper, trustStore, cohortGate,
                new HttpTestSecretAuthority.Settings(baseUri, timeout, true),
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(timeout).build());
    }

    private static TestSecretAuthorityTrustCohortGate.Descriptor convergedCohort() {
        return new TestSecretAuthorityTrustCohortGate.Descriptor(
                TestSecretAuthorityTrustCohortGate.Descriptor.SCHEMA_VERSION,
                true, true, "CONVERGED", 1, 1, 1, 1, 0,
                30, true, true, false);
    }

    private static TestSecretAuthorityTrustCohortGate.Descriptor divergentCohort() {
        return new TestSecretAuthorityTrustCohortGate.Descriptor(
                TestSecretAuthorityTrustCohortGate.Descriptor.SCHEMA_VERSION,
                true, false, "SNAPSHOT_DIVERGED", 2, 2, 2, 2, 0,
                30, true, true, false);
    }

    private static TestSecretAuthorityServingInventoryAuthority inventory(boolean available) {
        return new TestSecretAuthorityServingInventoryAuthority() {
            @Override
            public Observation observation() {
                return new Observation(Observation.SCHEMA_VERSION, true, true, available,
                        available ? "VERIFIED" : "REVOKED",
                        DynamicTestSecretAuthorityServingInventoryAuthority.SOURCE_TYPE,
                        3, "sha256:" + "c".repeat(64), 17,
                        "sha256:" + "d".repeat(64), "sha256:" + "e".repeat(64),
                        List.of("replica-a", "replica-b"),
                        Instant.parse("2099-01-01T00:00:00Z"), 2, 2);
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor(Descriptor.SCHEMA_VERSION, true, true, available,
                        available ? "VERIFIED" : "REVOKED", 2, 17, Map.ofEntries(
                        Map.entry("sourceType",
                                DynamicTestSecretAuthorityServingInventoryAuthority.SOURCE_TYPE),
                        Map.entry("automaticRefresh", true),
                        Map.entry("refreshState", "HEALTHY"),
                        Map.entry("refreshIntervalSeconds", 30L),
                        Map.entry("maximumSnapshotAgeSeconds", 60L),
                        Map.entry("conditionalRequests", true),
                        Map.entry("failClosedOnRefreshFailure", true),
                        Map.entry("signedRevocation", true),
                        Map.entry("witnessedPublications", true),
                        Map.entry("witnessSignatureThreshold", 2),
                        Map.entry("durablePublicationFloor", true)));
            }
        };
    }

    private Reply signed(TestSecretAuthorityRequest request) {
        return json(response(objectMapper, keyPair, request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, ""));
    }

    private Reply json(Object value) {
        try {
            return new Reply(200, "application/json", objectMapper.writeValueAsString(value));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void assertInvalid(Function<ObservedRequest, Reply> responder) throws Exception {
        try (AuthorityServer server = AuthorityServer.start(objectMapper, responder)) {
            assertResolutionReason(authority(server.baseUri(), Duration.ofSeconds(1)),
                    TestSecretAuthority.Reason.INVALID_RESPONSE);
        }
    }

    private static void assertResolutionReason(
            HttpTestSecretAuthority authority,
            TestSecretAuthority.Reason expected) {
        assertThatThrownBy(() -> authority.resolve(context()))
                .isInstanceOfSatisfying(TestSecretAuthority.ResolutionException.class,
                        failure -> assertThat(failure.reason()).isEqualTo(expected))
                .hasMessageNotContaining(REFERENCE)
                .hasMessageNotContaining(ALIAS)
                .hasMessageNotContaining(VALUE);
    }

    private record Reply(int status, String contentType, String body) {
    }

    private record ObservedRequest(
            String method,
            String body,
            String idempotencyKey,
            TestSecretAuthorityRequest request) {
    }

    private static final class AuthorityServer implements AutoCloseable {
        private final HttpServer server;
        private int requests;
        private ObservedRequest lastRequest;

        private AuthorityServer(HttpServer server) {
            this.server = server;
        }

        static AuthorityServer start(
                ObjectMapper objectMapper,
                Function<ObservedRequest, Reply> responder) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AuthorityServer authority = new AuthorityServer(server);
            server.createContext("/v1/test-secret-resolutions", exchange -> {
                authority.requests++;
                authority.handle(objectMapper, responder, exchange);
            });
            server.start();
            return authority;
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        int requests() {
            return requests;
        }

        ObservedRequest lastRequest() {
            return lastRequest;
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private void handle(
                ObjectMapper objectMapper,
                Function<ObservedRequest, Reply> responder,
                HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            TestSecretAuthorityRequest request = objectMapper.readValue(
                    body, TestSecretAuthorityRequest.class);
            lastRequest = new ObservedRequest(exchange.getRequestMethod(), body,
                    exchange.getRequestHeaders().getFirst("Idempotency-Key"), request);
            Reply reply = responder.apply(lastRequest);
            byte[] bytes = reply.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", reply.contentType());
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (var response = exchange.getResponseBody()) {
                response.write(bytes);
            }
        }
    }
}
