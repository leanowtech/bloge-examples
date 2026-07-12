package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpManagedEvidenceSigningProviderTest {

    @Test
    void requiresHttpsExceptForExplicitLoopbackTesting() {
        assertThatThrownBy(() -> settings(URI.create("http://kms.example.com"), true).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> settings(URI.create("http://127.0.0.1:8080"), false).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThat(settings(URI.create("http://127.0.0.1:8080"), true).validated().baseUri())
                .hasToString("http://127.0.0.1:8080");
    }

    @Test
    void rejectsPrivateMaterialEvenWhenPublicMetadataLooksValid() throws Exception {
        String body = """
                {"schemaVersion":"resourceGateway.managedEvidenceSigningKeys.v1",
                 "generatedAt":"2026-07-13T00:00:00Z","expiresAt":"2026-07-13T00:01:00Z",
                 "activeKeyId":"key-1","keys":[{"keyId":"key-1","algorithm":"Ed25519",
                 "encodedPublicKey":"public","createdAt":"2026-07-12T00:00:00Z","state":"ACTIVE",
                 "providerKeyVersion":"v1","privateKey":"must-not-cross-boundary"}]}
                """;
        withServer(200, "application/json", body.getBytes(StandardCharsets.UTF_8), provider ->
                assertThatThrownBy(provider::fetchKeys)
                        .isInstanceOf(EvidenceSigningProviderException.class)
                        .extracting(failure -> ((EvidenceSigningProviderException) failure).code())
                        .isEqualTo("PRIVATE_KEY_MATERIAL_REJECTED"));
    }

    @Test
    void rejectsDuplicateJsonAndOversizedResponsesAsNonRetryableProtocolFailures() throws Exception {
        String duplicate = """
                {"schemaVersion":"resourceGateway.managedEvidenceSigningKeys.v1",
                 "schemaVersion":"duplicate","keys":[]}
                """;
        withServer(200, "application/json", duplicate.getBytes(StandardCharsets.UTF_8), provider ->
                assertThatThrownBy(provider::fetchKeys)
                        .isInstanceOf(EvidenceSigningProviderException.class)
                        .satisfies(failure -> {
                            EvidenceSigningProviderException typed = (EvidenceSigningProviderException) failure;
                            assertThat(typed.code()).isEqualTo("INVALID_PROVIDER_RESPONSE");
                            assertThat(typed.retryable()).isFalse();
                        }));

        byte[] oversized = new byte[128 * 1024 + 1];
        withServer(200, "application/json", oversized, provider ->
                assertThatThrownBy(provider::fetchKeys)
                        .isInstanceOf(EvidenceSigningProviderException.class)
                        .extracting(failure -> ((EvidenceSigningProviderException) failure).code())
                        .isEqualTo("PROVIDER_RESPONSE_TOO_LARGE"));
    }

    @Test
    void neverFollowsRedirectsAndClassifiesRotationConflict() throws Exception {
        withServer(302, "application/json", "{}".getBytes(StandardCharsets.UTF_8), provider ->
                assertThatThrownBy(provider::fetchKeys)
                        .isInstanceOf(EvidenceSigningProviderException.class)
                        .satisfies(failure -> {
                            EvidenceSigningProviderException typed = (EvidenceSigningProviderException) failure;
                            assertThat(typed.code()).isEqualTo("PROVIDER_REJECTED");
                            assertThat(typed.retryable()).isFalse();
                        }));

        withServer(409, "application/json", "{}".getBytes(StandardCharsets.UTF_8), provider ->
                assertThatThrownBy(() -> provider.sign(new ManagedEvidenceSigningProvider.SignatureRequest(
                        "", "request-1", "key-1", "Ed25519", "sha256:" + "c".repeat(64))))
                        .isInstanceOf(EvidenceSigningProviderException.class)
                        .satisfies(failure -> {
                            EvidenceSigningProviderException typed = (EvidenceSigningProviderException) failure;
                            assertThat(typed.code()).isEqualTo("KEY_VERSION_MISMATCH");
                            assertThat(typed.retryable()).isTrue();
                        }));
    }

    private static HttpManagedEvidenceSigningProvider.Settings settings(URI uri, boolean allowInsecure) {
        return new HttpManagedEvidenceSigningProvider.Settings(uri, Duration.ofSeconds(1), "test-kms",
                allowInsecure);
    }

    private static void withServer(int status,
                                   String contentType,
                                   byte[] body,
                                   Consumer<HttpManagedEvidenceSigningProvider> assertion) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.createContext("/", exchange -> respond(exchange, status, contentType, body));
        server.setExecutor(executor);
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            assertion.accept(new HttpManagedEvidenceSigningProvider(new ObjectMapper(), settings(uri, true)));
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
