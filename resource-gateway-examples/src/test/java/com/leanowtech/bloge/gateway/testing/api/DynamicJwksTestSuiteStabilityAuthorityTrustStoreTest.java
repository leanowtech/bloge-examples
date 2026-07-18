package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.URI;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityTestFixtures.AUTHORITY_ID;
import static com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityTestFixtures.NOW;
import static com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityTestFixtures.keyPair;
import static com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityTestFixtures.request;
import static com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityTestFixtures.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicJwksTestSuiteStabilityAuthorityTrustStoreTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final URI JWKS_URI = URI.create("https://iam.example/stability-jwks");

    private final List<DynamicJwksTestSuiteStabilityAuthorityTrustStore> stores =
            new ArrayList<>();

    @AfterEach
    void closeStores() {
        stores.forEach(DynamicJwksTestSuiteStabilityAuthorityTrustStore::close);
    }

    @Test
    void refreshesOneUnknownKeyAcrossConcurrentVerifiersAndPublishesAtomically()
            throws Exception {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        KeyPair keyA = keyPair();
        KeyPair keyB = keyPair();
        fetcher.publish(jwks(Map.of("key-a", keyA)), "generation-a");
        DynamicJwksTestSuiteStabilityAuthorityTrustStore store =
                store(clock, fetcher, false);
        fetcher.publish(jwks(Map.of("key-b", keyB)), "generation-b");
        TestSuiteStabilityAuthorityRequest request = request(JSON);
        TestSuiteStabilityAuthorityResponse response = response(
                JSON, keyB, request,
                TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, "key-b", NOW, NOW.plusSeconds(30));
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(callers);
        List<java.util.concurrent.Future<TestSuiteStabilityAuthorityTrustStore.Verification>>
                results = new ArrayList<>();
        for (int index = 0; index < callers; index++) {
            results.add(executor.submit(() -> {
                ready.countDown();
                start.await();
                return store.verify(response, request, clock.instant());
            }));
        }
        assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        for (var result : results) {
            assertThat(result.get(2, TimeUnit.SECONDS).verified()).isTrue();
        }
        executor.shutdownNow();
        assertThat(fetcher.calls()).isEqualTo(2);
        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.providerType()).isEqualTo("DYNAMIC_JWKS_ED25519");
            assertThat(descriptor.keyCount()).isEqualTo(1);
            assertThat(descriptor.properties())
                    .containsEntry("refreshState", "HEALTHY")
                    .containsEntry("automaticRefresh", false)
                    .containsEntry("failClosedOnRefreshFailure", true);
        });
    }

    @Test
    void refreshFailureInvalidatesButDoesNotPartiallyReplaceTheLastSnapshot() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        KeyPair keyA = keyPair();
        KeyPair keyB = keyPair();
        fetcher.publish(jwks(Map.of("key-a", keyA)), "generation-a");
        DynamicJwksTestSuiteStabilityAuthorityTrustStore store =
                store(clock, fetcher, false);
        TestSuiteStabilityAuthorityTrustHealth health =
                new TestSuiteStabilityAuthorityTrustHealth(store);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        fetcher.publish(("{\"keys\":[{\"kid\":\"key-b\",\"kty\":\"OKP\","
                + "\"crv\":\"Ed25519\",\"alg\":\"EdDSA\",\"x\":\"invalid\","
                + "\"d\":\"private-material\"}]}").getBytes(), "generation-b-invalid");

        assertThat(store.refreshNow()).isFalse();
        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isFalse();
            assertThat(descriptor.keyCount()).isEqualTo(1);
            assertThat(descriptor.properties()).containsEntry("refreshState", "UNAVAILABLE");
        });
        assertThat(health.health()).satisfies(result -> {
            assertThat(result.getStatus()).isEqualTo(Status.DOWN);
            assertThat(result.getDetails())
                    .containsEntry("refreshState", "UNAVAILABLE")
                    .doesNotContainKeys("jwksUri", "etag", "keyId", "publicKey");
        });
        TestSuiteStabilityAuthorityRequest request = request(JSON);
        TestSuiteStabilityAuthorityResponse oldResponse = response(
                JSON, keyA, request,
                TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, "key-a", NOW, NOW.plusSeconds(30));
        assertThat(store.verify(oldResponse, request, clock.instant()).status())
                .isEqualTo(TestSuiteStabilityAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);

        fetcher.publish(jwks(Map.of("key-b", keyB)), "generation-b");
        assertThat(store.refreshNow()).isTrue();
        TestSuiteStabilityAuthorityResponse newResponse = response(
                JSON, keyB, request,
                TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, "key-b", NOW, NOW.plusSeconds(30));
        assertThat(store.verify(newResponse, request, clock.instant()).verified()).isTrue();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void descriptorNeverFetchesAndExpiresASilentRefreshLaneLocally() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(jwks(Map.of("key-a", keyPair())), "generation-a");
        DynamicJwksTestSuiteStabilityAuthorityTrustStore store =
                store(clock, fetcher, false);

        assertThat(store.descriptor().available()).isTrue();
        assertThat(store.descriptor().available()).isTrue();
        assertThat(fetcher.calls()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(61));
        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isFalse();
            assertThat(descriptor.properties()).containsEntry("refreshState", "EXPIRED");
        });
        assertThat(fetcher.calls()).isEqualTo(1);
    }

    @Test
    void conditionalNotModifiedRefreshExtendsTheHardSnapshotFence() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(jwks(Map.of("key-a", keyPair())), "generation-a");
        DynamicJwksTestSuiteStabilityAuthorityTrustStore store =
                store(clock, fetcher, false);
        clock.advance(Duration.ofSeconds(50));

        assertThat(store.refreshNow()).isTrue();

        assertThat(fetcher.lastConditionalEtag()).isEqualTo("generation-a");
        clock.advance(Duration.ofSeconds(50));
        assertThat(store.descriptor().available()).isTrue();
    }

    @Test
    void successfulRefreshPublishesExplicitKeyRevocationImmediately() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        KeyPair keyA = keyPair();
        fetcher.publish(jwks(Map.of("key-a", keyA)), "generation-a");
        DynamicJwksTestSuiteStabilityAuthorityTrustStore store =
                store(clock, fetcher, false);
        TestSuiteStabilityAuthorityRequest request = request(JSON);
        TestSuiteStabilityAuthorityResponse signed = response(
                JSON, keyA, request,
                TestSuiteStabilityAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, "key-a", NOW, NOW.plusSeconds(30));
        assertThat(store.verify(signed, request, clock.instant()).verified()).isTrue();

        fetcher.publish(jwks("key-a", keyA, true), "generation-a-revoked");
        assertThat(store.refreshNow()).isTrue();

        assertThat(store.verify(signed, request, clock.instant()).status())
                .isEqualTo(TestSuiteStabilityAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);
        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isFalse();
            assertThat(descriptor.properties())
                    .containsEntry("refreshState", "HEALTHY")
                    .containsEntry("activeKeyCount", 0L);
        });
    }

    @Test
    void realHttpFetcherUsesEtagAndNeverFollowsRedirectsOrNonJson() throws Exception {
        KeyPair key = keyPair();
        byte[] document = jwks(Map.of("key-http", key));
        AtomicReference<String> conditional = new AtomicReference<>("");
        AtomicInteger targetCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            String etag = exchange.getRequestHeaders().getFirst("If-None-Match");
            conditional.set(etag == null ? "" : etag);
            exchange.getResponseHeaders().add("ETag", "generation-http");
            if ("generation-http".equals(etag)) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
            exchange.sendResponseHeaders(200, document.length);
            try (var body = exchange.getResponseBody()) {
                body.write(document);
            }
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetCalls.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/jwk-set+json");
            exchange.sendResponseHeaders(200, document.length);
            try (var body = exchange.getResponseBody()) {
                body.write(document);
            }
        });
        server.createContext("/text", exchange -> {
            byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            DynamicJwksTestSuiteStabilityAuthorityTrustStore store =
                    new DynamicJwksTestSuiteStabilityAuthorityTrustStore(
                            JSON, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                            Duration.ofMillis(100), settings(base.resolve("/jwks"),
                            Duration.ofHours(1), Duration.ofSeconds(3_605), true));
            stores.add(store);
            assertThat(store.refreshNow()).isTrue();
            assertThat(conditional).hasValue("generation-http");

            assertThatThrownBy(() -> new DynamicJwksTestSuiteStabilityAuthorityTrustStore(
                    JSON, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                    Duration.ofMillis(100), settings(base.resolve("/redirect"),
                    Duration.ofSeconds(30), Duration.ofSeconds(60), true)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bootstrap");
            assertThat(targetCalls).hasValue(0);
            assertThatThrownBy(() -> new DynamicJwksTestSuiteStabilityAuthorityTrustStore(
                    JSON, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                    Duration.ofMillis(100), settings(base.resolve("/text"),
                    Duration.ofSeconds(30), Duration.ofSeconds(60), true)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bootstrap");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void backgroundLaneRefreshesAndCloseRevokesLocalReadiness() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(jwks(Map.of("key-a", keyPair())), "generation-a");
        DynamicJwksTestSuiteStabilityAuthorityTrustStore store =
                store(clock, fetcher, true, Duration.ofSeconds(1), Duration.ofSeconds(3));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (fetcher.calls() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }

        assertThat(fetcher.calls()).isGreaterThanOrEqualTo(2);
        assertThat(store.descriptor().properties()).containsEntry("automaticRefresh", true);
        store.close();
        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isFalse();
            assertThat(descriptor.properties()).containsEntry("refreshState", "CLOSED");
        });
    }

    @Test
    void rejectsUnsafeSettingsAndUnavailableOrPrivateBootstrapMaterial() {
        assertThatThrownBy(() -> settings(URI.create("http://iam.example/jwks"),
                Duration.ofSeconds(30), Duration.ofSeconds(60), false).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> settings(JWKS_URI,
                Duration.ofSeconds(30), Duration.ofSeconds(30), false).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("refresh plus request timeout");

        MutableFetcher privateMaterial = new MutableFetcher();
        privateMaterial.publish(("{\"keys\":[{\"kid\":\"key-a\",\"kty\":\"OKP\","
                + "\"crv\":\"Ed25519\",\"alg\":\"EdDSA\",\"x\":\"invalid\","
                + "\"d\":\"forbidden\"}]}").getBytes(), "private");
        assertThatThrownBy(() -> store(new MutableClock(NOW), privateMaterial, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap");

        MutableFetcher unavailable = new MutableFetcher();
        unavailable.fail();
        assertThatThrownBy(() -> store(new MutableClock(NOW), unavailable, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap");
    }

    private DynamicJwksTestSuiteStabilityAuthorityTrustStore store(
            MutableClock clock, MutableFetcher fetcher, boolean scheduler) {
        return store(clock, fetcher, scheduler,
                Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    private DynamicJwksTestSuiteStabilityAuthorityTrustStore store(
            MutableClock clock,
            MutableFetcher fetcher,
            boolean scheduler,
            Duration refresh,
            Duration maximumAge) {
        DynamicJwksTestSuiteStabilityAuthorityTrustStore store =
                new DynamicJwksTestSuiteStabilityAuthorityTrustStore(
                        JSON, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                        Duration.ofMillis(100),
                        settings(JWKS_URI, refresh, maximumAge, false),
                        clock, fetcher, scheduler);
        stores.add(store);
        return store;
    }

    private static DynamicJwksTestSuiteStabilityAuthorityTrustStore.Settings settings(
            URI uri, Duration refresh, Duration maximumAge, boolean insecureLoopback) {
        return new DynamicJwksTestSuiteStabilityAuthorityTrustStore.Settings(
                uri, refresh, Duration.ofSeconds(1), Duration.ofSeconds(1),
                maximumAge, insecureLoopback);
    }

    private static byte[] jwks(Map<String, KeyPair> keys) {
        try {
            List<Map<String, Object>> values = keys.entrySet().stream()
                    .map(entry -> Map.<String, Object>of(
                            "kid", entry.getKey(),
                            "kty", "OKP",
                            "crv", "Ed25519",
                            "alg", "EdDSA",
                            "use", "sig",
                            "key_ops", List.of("verify"),
                            "x", coordinate(entry.getValue())))
                    .toList();
            return JSON.writeValueAsBytes(Map.of("keys", values));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] jwks(String keyId, KeyPair keyPair, boolean revoked) {
        try {
            return JSON.writeValueAsBytes(Map.of("keys", List.of(Map.of(
                    "kid", keyId,
                    "kty", "OKP",
                    "crv", "Ed25519",
                    "alg", "EdDSA",
                    "use", "sig",
                    "key_ops", List.of("verify"),
                    "x", coordinate(keyPair),
                    "revoked", revoked))));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String coordinate(KeyPair keyPair) {
        byte[] encoded = keyPair.getPublic().getEncoded();
        byte[] coordinate = java.util.Arrays.copyOfRange(
                encoded, encoded.length - 32, encoded.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(coordinate);
    }

    private static final class MutableFetcher implements
            DynamicJwksTestSuiteStabilityAuthorityTrustStore.DocumentFetcher {
        private final AtomicReference<byte[]> body = new AtomicReference<>();
        private final AtomicReference<String> etag = new AtomicReference<>("");
        private final AtomicReference<String> lastConditionalEtag = new AtomicReference<>("");
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean failed;

        private void publish(byte[] nextBody, String nextEtag) {
            body.set(nextBody.clone());
            etag.set(nextEtag);
            failed = false;
        }

        private void fail() {
            failed = true;
        }

        @Override
        public DynamicJwksTestSuiteStabilityAuthorityTrustStore.FetchedDocument fetch(
                URI ignored, String conditionalEtag, Duration timeout) {
            calls.incrementAndGet();
            lastConditionalEtag.set(conditionalEtag);
            if (failed || body.get() == null) {
                throw new IllegalStateException("authority unavailable");
            }
            if (etag.get().equals(conditionalEtag)) {
                return DynamicJwksTestSuiteStabilityAuthorityTrustStore.FetchedDocument
                        .notModified(etag.get());
            }
            return DynamicJwksTestSuiteStabilityAuthorityTrustStore.FetchedDocument
                    .modified(body.get(), etag.get());
        }

        private int calls() {
            return calls.get();
        }

        private String lastConditionalEtag() {
            return lastConditionalEtag.get();
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        private MutableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        private void advance(Duration duration) {
            now.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
