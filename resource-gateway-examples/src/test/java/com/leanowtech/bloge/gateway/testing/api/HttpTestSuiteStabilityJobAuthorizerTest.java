package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityTestFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpTestSuiteStabilityJobAuthorizerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final KeyPair keyPair = keyPair();

    @Test
    void acceptsOnlyExactSignedAuthorizedDecisionAndSendsMinimalRequest() throws Exception {
        AtomicReference<String> observedBody = new AtomicReference<>();
        AtomicReference<String> observedMethod = new AtomicReference<>();
        try (AuthorityServer server = AuthorityServer.start(objectMapper, request -> {
            observedMethod.set(request.method());
            observedBody.set(request.body());
            return signed(request.request(), keyPair,
                    TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "");
        })) {
            TestSuiteStabilityJobAuthorizer.Authorization result =
                    authorizer(server.baseUri(), Duration.ofSeconds(1)).reauthorize(job());

            assertThat(result).isEqualTo(TestSuiteStabilityJobAuthorizer.Authorization.authorized());
            assertThat(observedMethod).hasValue("POST");
            assertThat(observedBody.get()).contains("EXECUTE_SUITE_STABILITY_JOB")
                    .doesNotContain("correlation-secret", "business-secret", "Bearer ",
                            "metadata", "fixture", "context", "payload");
        }
    }

    @Test
    void acceptsSignedRevocationAsTheOnlyDefinitiveDenyPath() throws Exception {
        try (AuthorityServer server = AuthorityServer.start(objectMapper, request -> signed(
                request.request(), keyPair,
                TestSuiteStabilityAuthorityResponse.Decision.REVOKED,
                "RG.POLICY.DELEGATION_REVOKED"))) {
            assertThat(authorizer(server.baseUri(), Duration.ofSeconds(1)).reauthorize(job()))
                    .isEqualTo(TestSuiteStabilityJobAuthorizer.Authorization.revoked(
                            "RG.POLICY.DELEGATION_REVOKED"));
        }
        try (AuthorityServer server = AuthorityServer.start(objectMapper,
                request -> new Reply(403, "application/json", "{}"))) {
            assertThat(authorizer(server.baseUri(), Duration.ofSeconds(1)).reauthorize(job()))
                    .isEqualTo(TestSuiteStabilityJobAuthorizer.Authorization.unavailable(
                            "RG.TEST.STABILITY_JOB_AUTHORITY_UNAVAILABLE"));
        }
    }

    @Test
    void rejectsTamperedSignatureAndCrossRequestReplayBinding() throws Exception {
        try (AuthorityServer server = AuthorityServer.start(objectMapper, request -> signed(
                request.request(), keyPair(),
                TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, ""))) {
            assertThat(authorizer(server.baseUri(), Duration.ofSeconds(1)).reauthorize(job())
                    .decision()).isEqualTo(TestSuiteStabilityJobAuthorizer.Decision.UNAVAILABLE);
        }
        try (AuthorityServer server = AuthorityServer.start(objectMapper, request -> {
            String otherChallenge = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(new byte[33]);
            TestSuiteStabilityAuthorityResponse response =
                    TestSuiteStabilityAuthorityTestFixtures.response(
                            objectMapper, keyPair, request.request(),
                            TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "",
                            otherChallenge, AUTHORITY_ID, KEY_ID, NOW, NOW.plusSeconds(30));
            return json(response);
        })) {
            assertThat(authorizer(server.baseUri(), Duration.ofSeconds(1)).reauthorize(job()))
                    .isEqualTo(TestSuiteStabilityJobAuthorizer.Authorization.unavailable(
                            "RG.TEST.STABILITY_JOB_AUTHORITY_BINDING_MISMATCH"));
        }
    }

    @Test
    void rejectsDuplicateUnknownNonJsonAndOversizedResponses() throws Exception {
        assertInvalidResponse(request -> {
            Reply valid = signed(request.request(), keyPair,
                    TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "");
            String duplicate = valid.body().replaceFirst(
                    "\\\"requestId\\\":\\\"", "\"requestId\":\"duplicate\",\"requestId\":\"");
            return new Reply(200, "application/json", duplicate);
        });
        assertInvalidResponse(request -> {
            Reply valid = signed(request.request(), keyPair,
                    TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "");
            return new Reply(200, "application/json",
                    valid.body().substring(0, valid.body().length() - 1) + ",\"unknown\":true}");
        });
        assertInvalidResponse(request -> new Reply(200, "text/plain", "not-json"));
        assertInvalidResponse(request -> new Reply(
                200, "application/json", "x".repeat(64 * 1024 + 1)));
    }

    @Test
    void neverFollowsRedirectAndMapsTimeoutToUnavailable() throws Exception {
        try (AuthorityServer server = AuthorityServer.start(objectMapper,
                request -> new Reply(302, "application/json", "{}"))) {
            assertThat(authorizer(server.baseUri(), Duration.ofSeconds(1)).reauthorize(job()))
                    .isEqualTo(TestSuiteStabilityJobAuthorizer.Authorization.unavailable(
                            "RG.TEST.STABILITY_JOB_AUTHORITY_UNAVAILABLE"));
            assertThat(server.requests()).isOne();
        }
        try (AuthorityServer server = AuthorityServer.start(objectMapper, request -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return signed(request.request(), keyPair,
                    TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "");
        })) {
            assertThat(authorizer(server.baseUri(), Duration.ofMillis(100)).reauthorize(job()))
                    .isEqualTo(TestSuiteStabilityJobAuthorizer.Authorization.unavailable(
                            "RG.TEST.STABILITY_JOB_AUTHORITY_UNAVAILABLE"));
        }
    }

    @Test
    void settingsRequireHttpsExceptForExplicitLoopbackTests() {
        assertThatThrownBy(() -> new HttpTestSuiteStabilityJobAuthorizer.Settings(
                URI.create("http://iam.example"), Duration.ofSeconds(1), false).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new HttpTestSuiteStabilityJobAuthorizer.Settings(
                URI.create("http://127.0.0.1"), Duration.ofSeconds(1), false).validated())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new HttpTestSuiteStabilityJobAuthorizer.Settings(
                URI.create("http://127.0.0.1"), Duration.ofSeconds(1), true).validated()
                .baseUri()).hasToString("http://127.0.0.1");
        assertThat(new HttpTestSuiteStabilityJobAuthorizer.Settings(
                URI.create("https://iam.example/pdp"), Duration.ofSeconds(1), false).validated()
                .baseUri()).hasToString("https://iam.example/pdp");
    }

    @Test
    void unavailableCohortPreventsAnyPdpRequestAndClosesDescriptor() throws Exception {
        TestSuiteStabilityAuthorityCohortGate gate = () ->
                TestSuiteStabilityAuthorityCohortGate.Descriptor.unavailable(2, 30, true);
        try (AuthorityServer server = AuthorityServer.start(objectMapper, request -> signed(
                request.request(), keyPair,
                TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, ""))) {
            HttpTestSuiteStabilityJobAuthorizer authorizer = authorizer(
                    server.baseUri(), Duration.ofSeconds(1), gate);

            assertThat(authorizer.reauthorize(job())).isEqualTo(
                    TestSuiteStabilityJobAuthorizer.Authorization.unavailable(
                            "RG.TEST.STABILITY_JOB_AUTHORITY_COHORT_UNAVAILABLE"));
            assertThat(server.requests()).isZero();
            assertThat(authorizer.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.available()).isFalse();
                assertThat(descriptor.properties())
                        .containsEntry("trustLocalAvailable", true)
                        .containsEntry("trustCohortConfigured", true)
                        .containsEntry("trustCohortConverged", false)
                        .containsEntry("trustCohortStatus", "STORE_UNAVAILABLE")
                        .containsEntry(
                                "trustCohortExternalInventoryNonEquivocation", false)
                        .containsEntry(
                                "trustCohortByzantineQuorumInventoryNonEquivocation", false)
                        .doesNotContainKeys("instanceId", "snapshotFingerprint", "cohortId");
            });
        }
    }

    private HttpTestSuiteStabilityJobAuthorizer authorizer(URI baseUri, Duration timeout) {
        return authorizer(baseUri, timeout, TestSuiteStabilityAuthorityCohortGate.localOnly());
    }

    private HttpTestSuiteStabilityJobAuthorizer authorizer(
            URI baseUri,
            Duration timeout,
            TestSuiteStabilityAuthorityCohortGate gate) {
        var trustStore = new ConfiguredTestSuiteStabilityAuthorityTrustStore(
                objectMapper, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                Duration.ofMillis(10), List.of(
                new ConfiguredTestSuiteStabilityAuthorityTrustStore.AuthorityKey(
                        KEY_ID, keyPair.getPublic(), null, null, true, false)));
        return new HttpTestSuiteStabilityJobAuthorizer(
                objectMapper, trustStore, gate,
                new HttpTestSuiteStabilityJobAuthorizer.Settings(baseUri, timeout, true),
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom(),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(timeout).build());
    }

    private Reply signed(
            TestSuiteStabilityAuthorityRequest request,
            KeyPair signer,
            TestSuiteStabilityAuthorityResponse.Decision decision,
            String failureCode) {
        return json(response(objectMapper, signer, request, decision, failureCode));
    }

    private Reply json(Object value) {
        try {
            return new Reply(200, "application/json", objectMapper.writeValueAsString(value));
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private void assertInvalidResponse(Function<ObservedRequest, Reply> responder) throws Exception {
        try (AuthorityServer server = AuthorityServer.start(objectMapper, responder)) {
            assertThat(authorizer(server.baseUri(), Duration.ofSeconds(1)).reauthorize(job()))
                    .isEqualTo(TestSuiteStabilityJobAuthorizer.Authorization.unavailable(
                            "RG.TEST.STABILITY_JOB_AUTHORITY_RESPONSE_INVALID"));
        }
    }

    private record Reply(int status, String contentType, String body) {
    }

    private record ObservedRequest(
            String method,
            String body,
            TestSuiteStabilityAuthorityRequest request) {
    }

    private static final class AuthorityServer implements AutoCloseable {
        private final HttpServer server;
        private int requests;

        private AuthorityServer(HttpServer server) {
            this.server = server;
        }

        static AuthorityServer start(
                ObjectMapper objectMapper,
                Function<ObservedRequest, Reply> responder) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AuthorityServer authority = new AuthorityServer(server);
            server.createContext("/v1/stability-job-authorizations", exchange -> {
                authority.requests++;
                handle(objectMapper, responder, exchange);
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

        @Override
        public void close() {
            server.stop(0);
        }

        private static void handle(
                ObjectMapper objectMapper,
                Function<ObservedRequest, Reply> responder,
                HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            TestSuiteStabilityAuthorityRequest request = objectMapper.readValue(
                    body, TestSuiteStabilityAuthorityRequest.class);
            Reply reply = responder.apply(new ObservedRequest(
                    exchange.getRequestMethod(), body, request));
            byte[] response = reply.body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", reply.contentType());
            if (reply.status() == 302) {
                exchange.getResponseHeaders().set("Location", baseLocation(exchange));
            }
            exchange.sendResponseHeaders(reply.status(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }

        private static String baseLocation(HttpExchange exchange) {
            return "http://127.0.0.1:" + exchange.getLocalAddress().getPort()
                    + "/v1/stability-job-authorizations";
        }
    }
}
