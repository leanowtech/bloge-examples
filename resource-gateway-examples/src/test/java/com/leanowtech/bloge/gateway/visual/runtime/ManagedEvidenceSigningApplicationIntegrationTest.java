package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "gateway.integration.evidence-signing.managed.enabled=true",
                "gateway.integration.evidence-signing.managed.provider-name=test-enterprise-hsm",
                "gateway.integration.evidence-signing.managed.request-timeout-seconds=1",
                "gateway.integration.evidence-signing.managed.refresh-interval-seconds=1",
                "gateway.integration.evidence-signing.managed.maximum-snapshot-lifetime-seconds=60",
                "gateway.integration.evidence-signing.managed.allow-insecure-loopback=true",
                "spring.datasource.url=jdbc:h2:mem:managed-evidence-signing-application;DB_CLOSE_DELAY=-1"
        })
class ManagedEvidenceSigningApplicationIntegrationTest {
    private static final String FINGERPRINT = "sha256:" + "b".repeat(64);
    private static final TestSigningAuthority AUTHORITY = TestSigningAuthority.start();

    @DynamicPropertySource
    static void signingAuthorityProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.integration.evidence-signing.managed.base-uri",
                () -> AUTHORITY.baseUri().toString());
    }

    @Autowired
    private VisualEvidenceSigner signer;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate rest;

    @AfterAll
    static void stopAuthority() {
        AUTHORITY.close();
    }

    @Test
    void usesRemoteNonExportableKeysForRotationFailureAndPublicIntegrationContract() throws Exception {
        assertThat(signer).isInstanceOf(ManagedVisualEvidenceSigner.class);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
                WHERE TABLE_NAME = 'VISUAL_EVIDENCE_SIGNING_KEYS'
                """, Long.class)).isZero();

        VisualRunEvidenceSeal oldSeal = signer.seal(FINGERPRINT);
        assertThat(oldSeal.keyId()).isEqualTo("hsm-key-1");
        assertThat(signer.verify(oldSeal, FINGERPRINT).valid()).isTrue();
        assertThat(AUTHORITY.lastSignRequest()).contains(FINGERPRINT, "hsm-key-1")
                .doesNotContain("privateKey", "encodedPrivateKey");

        JsonNode capabilities = rest.getForObject("/api/integration/capabilities", JsonNode.class);
        JsonNode payload = capabilities.path("payload");
        assertThat(payload.path("features").path("managedEvidenceSigning").asBoolean()).isTrue();
        assertThat(payload.path("features").path("nonExportableEvidenceSigningKey").asBoolean()).isTrue();
        assertThat(payload.path("evidenceSigner").path("providerName").asText())
                .isEqualTo("test-enterprise-hsm");
        assertThat(payload.path("evidenceSigner").path("privateKeyExportable").asBoolean()).isFalse();

        JsonNode publicKey = rest.getForObject("/api/integration/evidence-keys/hsm-key-1", JsonNode.class);
        assertThat(publicKey.path("payload").path("state").asText()).isEqualTo("ACTIVE");
        assertThat(publicKey.toString()).doesNotContain("privateKey", "encodedPrivateKey");

        AUTHORITY.rotate("hsm-key-2");
        Thread.sleep(1_100);
        VisualRunEvidenceSeal newSeal = signer.seal(FINGERPRINT);
        assertThat(newSeal.keyId()).isEqualTo("hsm-key-2");
        assertThat(signer.verify(oldSeal, FINGERPRINT).status()).isEqualTo("VERIFIED");
        assertThat(signer.verify(newSeal, FINGERPRINT).status()).isEqualTo("VERIFIED");

        AUTHORITY.state("hsm-key-1", "REVOKED");
        Thread.sleep(1_100);
        assertThat(signer.verify(oldSeal, FINGERPRINT).status()).isEqualTo("KEY_REVOKED");

        AUTHORITY.failSign(true);
        assertThatThrownBy(() -> signer.seal(FINGERPRINT))
                .isInstanceOf(EvidenceSigningProviderException.class)
                .extracting(failure -> ((EvidenceSigningProviderException) failure).code())
                .isEqualTo("PROVIDER_UNAVAILABLE");
        AUTHORITY.failSign(false);
        assertThat(signer.seal(FINGERPRINT).keyId()).isEqualTo("hsm-key-2");
        assertThat(AUTHORITY.signCalls()).isGreaterThanOrEqualTo(4);
    }

    private static final class TestSigningAuthority implements AutoCloseable {
        private static final ObjectMapper JSON = new ObjectMapper();

        private final HttpServer server;
        private final ExecutorService executor;
        private final Map<String, KeyPair> keys = new LinkedHashMap<>();
        private final Map<String, String> states = new LinkedHashMap<>();
        private final AtomicBoolean failSign = new AtomicBoolean();
        private final AtomicInteger signCalls = new AtomicInteger();
        private volatile String activeKeyId;
        private volatile String lastSignRequest = "";

        private TestSigningAuthority(HttpServer server, ExecutorService executor) throws Exception {
            this.server = server;
            this.executor = executor;
            addActive("hsm-key-1");
            server.createContext("/v1/evidence-signing/keys", this::keys);
            server.createContext("/v1/evidence-signing/sign", this::sign);
            server.setExecutor(executor);
            server.start();
        }

        static TestSigningAuthority start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                return new TestSigningAuthority(server, Executors.newVirtualThreadPerTaskExecutor());
            } catch (Exception failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        URI baseUri() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        synchronized void rotate(String keyId) throws Exception {
            states.put(activeKeyId, "VERIFY_ONLY");
            addActive(keyId);
        }

        synchronized void state(String keyId, String value) {
            states.put(keyId, value);
        }

        void failSign(boolean value) {
            failSign.set(value);
        }

        int signCalls() {
            return signCalls.get();
        }

        String lastSignRequest() {
            return lastSignRequest;
        }

        private synchronized void addActive(String keyId) throws Exception {
            keys.put(keyId, KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
            states.put(keyId, "ACTIVE");
            activeKeyId = keyId;
        }

        private synchronized void keys(HttpExchange exchange) throws IOException {
            List<Map<String, Object>> descriptors = keys.entrySet().stream().map(entry -> Map.<String, Object>of(
                    "keyId", entry.getKey(),
                    "algorithm", "Ed25519",
                    "encodedPublicKey", Base64.getEncoder().encodeToString(entry.getValue().getPublic().getEncoded()),
                    "createdAt", "2026-07-12T00:00:00Z",
                    "state", states.get(entry.getKey()),
                    "providerKeyVersion", "hsm/version/" + entry.getKey()
            )).toList();
            send(exchange, 200, Map.of(
                    "schemaVersion", ManagedEvidenceSigningProvider.KeySet.SCHEMA_VERSION,
                    "generatedAt", Instant.now().toString(),
                    "expiresAt", Instant.now().plusSeconds(30).toString(),
                    "activeKeyId", activeKeyId,
                    "keys", descriptors));
        }

        private synchronized void sign(HttpExchange exchange) throws IOException {
            signCalls.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastSignRequest = new String(body, StandardCharsets.UTF_8);
            if (failSign.get()) {
                send(exchange, 503, Map.of("code", "HSM_UNAVAILABLE"));
                return;
            }
            JsonNode request = JSON.readTree(body);
            String keyId = request.path("keyId").asText();
            if (!keyId.equals(activeKeyId)) {
                send(exchange, 409, Map.of("code", "KEY_VERSION_MISMATCH"));
                return;
            }
            try {
                String fingerprint = request.path("materialFingerprint").asText();
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(keys.get(keyId).getPrivate());
                signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                send(exchange, 200, Map.of(
                        "schemaVersion", ManagedEvidenceSigningProvider.SignatureResult.SCHEMA_VERSION,
                        "requestId", request.path("requestId").asText(),
                        "keyId", keyId,
                        "algorithm", "Ed25519",
                        "materialFingerprint", fingerprint,
                        "signedAt", Instant.now().toString(),
                        "signature", Base64.getEncoder().encodeToString(signer.sign())
                ));
            } catch (Exception failure) {
                throw new IOException(failure);
            }
        }

        private static void send(HttpExchange exchange, int status, Object body) throws IOException {
            byte[] bytes = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
