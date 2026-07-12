package com.leanowtech.bloge.gateway.integration;

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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "gateway.integration.identity.jwt.enabled=true",
                "gateway.integration.identity.jwt.issuer=https://iam.example/",
                "gateway.integration.identity.jwt.audience=resource-gateway",
                "gateway.integration.identity.jwt.refresh-interval-seconds=1",
                "gateway.integration.identity.jwt.unknown-key-refresh-interval-seconds=1",
                "gateway.integration.identity.jwt.request-timeout-seconds=1",
                "gateway.integration.identity.jwt.outage-policy=FAIL_CLOSED",
                "gateway.integration.identity.jwt.maximum-stale-seconds=0",
                "gateway.integration.identity.jwt.allow-insecure-loopback=true",
                "gateway.integration.identity.demo-enabled=false",
                "spring.datasource.url=jdbc:h2:mem:dynamic-jwks-application;DB_CLOSE_DELAY=-1"
        })
class DynamicJwksApplicationIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TestIdentityAuthority AUTHORITY = TestIdentityAuthority.start();

    @DynamicPropertySource
    static void identityAuthorityProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.integration.identity.jwt.jwks-uri", () -> AUTHORITY.uri("/jwks").toString());
        registry.add("gateway.integration.identity.jwt.revocations-uri",
                () -> AUTHORITY.uri("/revocations").toString());
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private IntegrationAccessAuditRepository audit;

    @AfterAll
    static void stopAuthority() {
        AUTHORITY.close();
    }

    @Test
    void rotatesAndRevokesMultiOrganizationCredentialsAndFailsClosedDuringAuthorityOutage() throws Exception {
        String tenantAToken = AUTHORITY.token("key-a", "token-a", "tenant-a", "org-a",
                List.of("knowledge-owners", "tool-authors"), "CONFIDENTIAL", "alice@example.com");
        ResponseEntity<JsonNode> tenantAResponse = reconciliation(tenantAToken);
        assertThat(tenantAResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audit.recent(1).getFirst()).extracting(IntegrationAccessAuditRecord::organizationId,
                        IntegrationAccessAuditRecord::delegatedBy,
                        IntegrationAccessAuditRecord::delegationGrantId,
                        IntegrationAccessAuditRecord::clearance,
                        IntegrationAccessAuditRecord::groupCount)
                .containsExactly("org-a", "alice@example.com", "grant-token-a", "CONFIDENTIAL", 2);
        assertThat(audit.recent(1).getFirst().groupFingerprint()).hasSize(64);

        AUTHORITY.publishKeys(Set.of("key-a", "key-b"));
        String tenantBToken = AUTHORITY.token("key-b", "token-b", "tenant-b", "org-b",
                List.of("regional-governors"), "RESTRICTED", "bob@example.com");
        assertThat(reconciliation(tenantBToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(audit.recent(1).getFirst()).extracting(IntegrationAccessAuditRecord::organizationId,
                        IntegrationAccessAuditRecord::clearance, IntegrationAccessAuditRecord::groupCount)
                .containsExactly("org-b", "RESTRICTED", 1);

        ResponseEntity<JsonNode> capabilities = rest.getForEntity("/api/integration/capabilities", JsonNode.class);
        assertThat(capabilities.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode capabilityData = capabilities.getBody().path("payload");
        assertThat(capabilityData.path("features").path("dynamicCredentialTrust").booleanValue()).isTrue();
        assertThat(capabilityData.path("features").path("credentialRevocationPropagationSlo").booleanValue())
                .isTrue();
        assertThat(capabilityData.path("identityProvider").path("claimsSource").textValue())
                .isEqualTo("DYNAMIC_JWKS");
        assertThat(capabilityData.path("identityProvider").path("properties")
                .path("revocationPropagationSloSeconds").longValue()).isEqualTo(2L);

        AUTHORITY.revokeToken("token-b");
        Thread.sleep(1_100);
        assertThat(reconciliation(tenantBToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(AUTHORITY.jwksNotModifiedResponses()).isGreaterThanOrEqualTo(1);

        AUTHORITY.restoreHealthyRevocations();
        Thread.sleep(1_100);
        assertThat(reconciliation(tenantAToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        AUTHORITY.fail(true);
        Thread.sleep(1_100);
        ResponseEntity<JsonNode> outage = reconciliation(tenantAToken);
        assertThat(outage.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(outage.getBody().path("code").textValue())
                .isEqualTo("RG.INTEGRATION.IDENTITY_PROVIDER_UNAVAILABLE");
        assertThat(outage.getBody().path("retryable").booleanValue()).isTrue();
        assertThat(audit.recent(1).getFirst().reasonCode())
                .isEqualTo("RG.INTEGRATION.IDENTITY_PROVIDER_UNAVAILABLE");

        AUTHORITY.fail(false);
        AUTHORITY.publishRawJwks(new byte[256 * 1024 + 1]);
        Thread.sleep(1_100);
        assertThat(reconciliation(tenantAToken).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        ResponseEntity<JsonNode> invalidDocumentCapabilities = rest.getForEntity(
                "/api/integration/capabilities", JsonNode.class);
        assertThat(invalidDocumentCapabilities.getBody().path("payload").path("identityProvider")
                .path("properties").path("lastFailureCode").textValue())
                .isEqualTo("REMOTE_DOCUMENT_INVALID");
    }

    private ResponseEntity<JsonNode> reconciliation(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Purpose", "CHANGE_SYNC");
        headers.set("X-Correlation-Id", "dynamic-jwks-integration");
        return rest.exchange("/api/integration/reconciliation", HttpMethod.GET,
                new HttpEntity<>(headers), JsonNode.class);
    }

    private static final class TestIdentityAuthority implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;
        private final Map<String, KeyPair> keys;
        private final AtomicReference<byte[]> jwks = new AtomicReference<>();
        private final AtomicReference<byte[]> revocations = new AtomicReference<>();
        private final AtomicReference<String> jwksEtag = new AtomicReference<>("\"jwks-1\"");
        private final AtomicReference<String> revocationsEtag = new AtomicReference<>("\"rev-1\"");
        private final AtomicInteger revision = new AtomicInteger(1);
        private final AtomicInteger jwksNotModified = new AtomicInteger();
        private volatile boolean failing;

        private TestIdentityAuthority(HttpServer server, ExecutorService executor, Map<String, KeyPair> keys) {
            this.server = server;
            this.executor = executor;
            this.keys = keys;
        }

        static TestIdentityAuthority start() {
            try {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                Map<String, KeyPair> keys = Map.of("key-a", generator.generateKeyPair(),
                        "key-b", generator.generateKeyPair());
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = Executors.newCachedThreadPool();
                server.setExecutor(executor);
                TestIdentityAuthority authority = new TestIdentityAuthority(server, executor, keys);
                authority.publishKeys(Set.of("key-a"));
                authority.restoreHealthyRevocations();
                server.createContext("/jwks", exchange -> authority.respond(exchange, authority.jwks,
                        authority.jwksEtag, authority.jwksNotModified));
                server.createContext("/revocations", exchange -> authority.respond(exchange, authority.revocations,
                        authority.revocationsEtag, null));
                server.start();
                return authority;
            } catch (Exception failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }

        URI uri(String path) {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        }

        void publishKeys(Set<String> keyIds) {
            try {
                List<Map<String, Object>> values = new ArrayList<>();
                keyIds.stream().sorted().forEach(keyId -> {
                    RSAPublicKey key = (RSAPublicKey) keys.get(keyId).getPublic();
                    values.add(Map.of("kty", "RSA", "kid", keyId, "alg", "RS256", "use", "sig",
                            "key_ops", List.of("verify"), "n", unsigned(key.getModulus()),
                            "e", unsigned(key.getPublicExponent())));
                });
                jwks.set(JSON.writeValueAsBytes(Map.of("keys", values)));
                jwksEtag.set("\"jwks-" + revision.incrementAndGet() + "\"");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        void publishRawJwks(byte[] body) {
            jwks.set(body.clone());
            jwksEtag.set("\"jwks-" + revision.incrementAndGet() + "\"");
        }

        void revokeToken(String tokenId) {
            publishRevocations(Set.of(), Set.of(tokenId));
        }

        void restoreHealthyRevocations() {
            publishRevocations(Set.of(), Set.of());
        }

        void fail(boolean value) {
            failing = value;
        }

        int jwksNotModifiedResponses() {
            return jwksNotModified.get();
        }

        String token(String keyId,
                     String tokenId,
                     String tenantId,
                     String organizationId,
                     List<String> groups,
                     String clearance,
                     String delegatedBy) {
            try {
                Instant now = Instant.now();
                Map<String, Object> claims = new LinkedHashMap<>();
                claims.put("iss", "https://iam.example/");
                claims.put("aud", List.of("resource-gateway"));
                claims.put("sub", "aneke-" + tenantId);
                claims.put("jti", tokenId);
                claims.put("iat", now.minusSeconds(5).getEpochSecond());
                claims.put("nbf", now.minusSeconds(5).getEpochSecond());
                claims.put("exp", now.plusSeconds(300).getEpochSecond());
                claims.put("tenant_id", tenantId);
                claims.put("organization_id", organizationId);
                claims.put("project_id", "tool-studio");
                claims.put("environment_id", "prod");
                claims.put("region", "ap-southeast-1");
                claims.put("actor_type", "WORKLOAD");
                claims.put("actor_id", "aneke-sync-" + tenantId);
                claims.put("groups", groups);
                claims.put("clearance", clearance);
                claims.put("purposes", List.of("CHANGE_SYNC"));
                claims.put("delegated_by", delegatedBy);
                claims.put("delegation_grant_id", "grant-" + tokenId);
                claims.put("delegation_exp", now.plusSeconds(240).getEpochSecond());
                claims.put("delegation_purposes", List.of("CHANGE_SYNC"));
                String header = encode(JSON.writeValueAsBytes(
                        Map.of("alg", "RS256", "kid", keyId, "typ", "JWT")));
                String payload = encode(JSON.writeValueAsBytes(claims));
                String signingInput = header + "." + payload;
                Signature signer = Signature.getInstance("SHA256withRSA");
                signer.initSign(keys.get(keyId).getPrivate());
                signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
                return signingInput + "." + encode(signer.sign());
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        private void publishRevocations(Set<String> keyIds, Set<String> tokenIds) {
            try {
                Instant now = Instant.now();
                revocations.set(JSON.writeValueAsBytes(Map.of(
                        "schemaVersion", DynamicJwksIntegrationJwtTrustStore.REVOCATION_SCHEMA_VERSION,
                        "generatedAt", now.toString(),
                        "expiresAt", now.plusSeconds(300).toString(),
                        "revokedKeyIds", keyIds,
                        "revokedTokenIds", tokenIds)));
                revocationsEtag.set("\"rev-" + revision.incrementAndGet() + "\"");
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        private void respond(HttpExchange exchange,
                             AtomicReference<byte[]> body,
                             AtomicReference<String> etag,
                             AtomicInteger notModifiedCounter) throws IOException {
            try (exchange) {
                if (failing) {
                    byte[] unavailable = "{\"error\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(503, unavailable.length);
                    exchange.getResponseBody().write(unavailable);
                    return;
                }
                String currentEtag = etag.get();
                if (currentEtag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    if (notModifiedCounter != null) {
                        notModifiedCounter.incrementAndGet();
                    }
                    exchange.getResponseHeaders().set("ETag", currentEtag);
                    exchange.sendResponseHeaders(304, -1);
                    return;
                }
                byte[] response = body.get();
                exchange.getResponseHeaders().set("Content-Type", "application/jwk-set+json");
                exchange.getResponseHeaders().set("ETag", currentEtag);
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        }

        private static String unsigned(BigInteger value) {
            byte[] bytes = value.toByteArray();
            if (bytes.length > 1 && bytes[0] == 0) {
                bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
            }
            return encode(bytes);
        }

        private static String encode(byte[] value) {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
