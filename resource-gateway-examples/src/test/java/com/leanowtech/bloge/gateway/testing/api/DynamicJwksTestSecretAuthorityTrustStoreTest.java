package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.AUTHORITY_GENERATION;
import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.AUTHORITY_ID;
import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.KEY_ID;
import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.NOW;
import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.VALUE;
import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.keyPair;
import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.request;
import static com.leanowtech.bloge.gateway.testing.api.TestSecretAuthorityProtocolTestFixtures.response;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicJwksTestSecretAuthorityTrustStoreTest {

    private static final ObjectMapper JSON =
            new ObjectMapper().findAndRegisterModules();
    private static final URI JWKS_URI =
            URI.create("https://iam.example/test-secret-jwks");

    private final List<DynamicJwksTestSecretAuthorityTrustStore> stores =
            new ArrayList<>();

    @AfterEach
    void closeStores() {
        stores.forEach(DynamicJwksTestSecretAuthorityTrustStore::close);
    }

    @Test
    void refreshesOneUnknownKeyAcrossConcurrentVerifiersAndPublishesAtomically()
            throws Exception {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        KeyPair keyA = keyPair();
        KeyPair keyB = keyPair();
        fetcher.publish(jwks(Map.of("key-a", keyA)), "generation-a");
        DynamicJwksTestSecretAuthorityTrustStore store =
                store(clock, fetcher, false);
        String firstGeneration = store.cohortObservation().snapshotFingerprint();

        fetcher.publish(jwks(Map.of("key-b", keyB)), "generation-b");
        TestSecretAuthorityRequest request = request(JSON);
        TestSecretAuthorityResponse response = response(JSON, keyB, request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, AUTHORITY_GENERATION, "key-b",
                NOW, NOW.plusSeconds(30), VALUE);
        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(callers);
        List<java.util.concurrent.Future<TestSecretAuthorityTrustStore.Verification>>
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
        assertThat(store.cohortObservation()).satisfies(observation -> {
            assertThat(observation.available()).isTrue();
            assertThat(observation.snapshotFingerprint())
                    .matches("sha256:[a-f0-9]{64}")
                    .isNotEqualTo(firstGeneration)
                    .doesNotContain("key-a", "key-b");
        });
        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.providerType()).isEqualTo("DYNAMIC_JWKS_ED25519");
            assertThat(descriptor.properties())
                    .containsEntry("refreshState", "HEALTHY")
                    .containsEntry("automaticRefresh", false)
                    .containsEntry("conditionalRequests", true)
                    .containsEntry("failClosedOnRefreshFailure", true);
        });
    }

    @Test
    void refreshFailureInvalidatesOldTrustAndACompleteGenerationRecoversIt() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        KeyPair keyA = keyPair();
        KeyPair keyB = keyPair();
        fetcher.publish(jwks(Map.of("key-a", keyA)), "generation-a");
        DynamicJwksTestSecretAuthorityTrustStore store =
                store(clock, fetcher, false);
        TestSecretAuthorityTrustHealth health =
                new TestSecretAuthorityTrustHealth(store);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);

        fetcher.publish(("{\"keys\":[{\"kid\":\"key-b\",\"kty\":\"OKP\","
                + "\"crv\":\"Ed25519\",\"alg\":\"EdDSA\",\"x\":\"invalid\","
                + "\"d\":\"private-material\"}]}").getBytes(StandardCharsets.UTF_8),
                "generation-invalid");
        assertThat(store.refreshNow()).isFalse();
        assertThat(store.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.refreshState()).isEqualTo("UNAVAILABLE");
            assertThat(snapshot.trustedKeyCount()).isOne();
            assertThat(snapshot.refreshFailureCount()).isOne();
            assertThat(snapshot.lastFailureCode()).isEqualTo("REMOTE_DOCUMENT_INVALID");
        });
        assertThat(health.health()).satisfies(result -> {
            assertThat(result.getStatus()).isEqualTo(Status.DOWN);
            assertThat(result.getDetails())
                    .containsEntry("refreshState", "UNAVAILABLE")
                    .doesNotContainKeys("jwksUri", "etag", "keyId", "publicKey",
                            "secretReference", "secretValue");
        });
        TestSecretAuthorityRequest request = request(JSON);
        TestSecretAuthorityResponse oldResponse = response(JSON, keyA, request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, AUTHORITY_GENERATION, "key-a",
                NOW, NOW.plusSeconds(30), VALUE);
        assertThat(store.verify(oldResponse, request, clock.instant()).status())
                .isEqualTo(TestSecretAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);

        fetcher.publish(jwks(Map.of("key-b", keyB)), "generation-b");
        assertThat(store.refreshNow()).isTrue();
        TestSecretAuthorityResponse newResponse = response(JSON, keyB, request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, AUTHORITY_GENERATION, "key-b",
                NOW, NOW.plusSeconds(30), VALUE);
        assertThat(store.verify(newResponse, request, clock.instant()).verified()).isTrue();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void propagatesRevocationAndRotationWithoutRestart() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        KeyPair keyA = keyPair();
        KeyPair keyB = keyPair();
        fetcher.publish(jwks(Map.of("key-a", keyA)), "generation-a");
        DynamicJwksTestSecretAuthorityTrustStore store =
                store(clock, fetcher, false);
        TestSecretAuthorityRequest request = request(JSON);
        TestSecretAuthorityResponse signedByA = response(JSON, keyA, request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, AUTHORITY_GENERATION, "key-a",
                NOW, NOW.plusSeconds(30), VALUE);
        assertThat(store.verify(signedByA, request, NOW).verified()).isTrue();

        fetcher.publish(jwks(List.of(jwk("key-a", keyA, ",\"revoked\":true"))),
                "generation-a-revoked");
        assertThat(store.refreshNow()).isTrue();
        assertThat(store.verify(signedByA, request, NOW).status())
                .isEqualTo(TestSecretAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);
        assertThat(store.descriptor().available()).isFalse();

        fetcher.publish(jwks(Map.of("key-a", keyA, "key-b", keyB)),
                "generation-b-active");
        assertThat(store.refreshNow()).isTrue();
        TestSecretAuthorityResponse signedByB = response(JSON, keyB, request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, AUTHORITY_GENERATION, "key-b",
                NOW, NOW.plusSeconds(30), VALUE);
        assertThat(store.verify(signedByB, request, NOW).verified()).isTrue();
        assertThat(store.descriptor().keyCount()).isEqualTo(2);
    }

    @Test
    void descriptorNeverFetchesAndSilentRefreshExpiryFailsClosed() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        KeyPair keyA = keyPair();
        fetcher.publish(jwks(Map.of("key-a", keyA)), "generation-a");
        DynamicJwksTestSecretAuthorityTrustStore store = store(clock, fetcher, false,
                Duration.ofSeconds(3));

        assertThat(store.descriptor().available()).isTrue();
        assertThat(store.snapshot().available()).isTrue();
        assertThat(fetcher.calls()).isOne();
        clock.advance(Duration.ofSeconds(3));

        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isFalse();
            assertThat(descriptor.properties()).containsEntry("refreshState", "EXPIRED");
        });
        assertThat(store.snapshot().refreshState()).isEqualTo("EXPIRED");
        assertThat(fetcher.calls()).isOne();
        TestSecretAuthorityRequest request = request(JSON);
        TestSecretAuthorityResponse response = response(JSON, keyA, request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, AUTHORITY_GENERATION, "key-a",
                NOW, NOW.plusSeconds(30), VALUE);
        assertThat(store.verify(response, request, clock.instant()).status())
                .isEqualTo(TestSecretAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);

        store.close();
        assertThat(store.snapshot().refreshState()).isEqualTo("CLOSED");
        assertThat(store.refreshNow()).isFalse();
    }

    @Test
    void unknownKeyRefreshUsesCooldownWhenAuthorityGenerationDoesNotAdvance() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        KeyPair known = keyPair();
        KeyPair unknown = keyPair();
        fetcher.publish(jwks(Map.of("key-a", known)), "generation-a");
        DynamicJwksTestSecretAuthorityTrustStore store =
                store(clock, fetcher, false);
        TestSecretAuthorityRequest request = request(JSON);
        TestSecretAuthorityResponse response = response(JSON, unknown, request,
                TestSecretAuthorityResponse.Decision.AUTHORIZED, "",
                request.challenge(), AUTHORITY_ID, AUTHORITY_GENERATION, "key-unknown",
                NOW, NOW.plusSeconds(30), VALUE);

        assertThat(store.verify(response, request, NOW).status())
                .isEqualTo(TestSecretAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);
        assertThat(store.verify(response, request, NOW).status())
                .isEqualTo(TestSecretAuthorityTrustStore.VerificationStatus.KEY_UNAVAILABLE);
        assertThat(fetcher.calls()).isEqualTo(2);
        assertThat(fetcher.lastConditionalEtag()).isEqualTo("generation-a");

        clock.advance(Duration.ofSeconds(5));
        store.verify(response, request, clock.instant());
        assertThat(fetcher.calls()).isEqualTo(3);
    }

    @Test
    void rejectsPrivateAmbiguousMalformedAndInactiveBootstrapDocuments() {
        KeyPair key = keyPair();
        String validJwk = jwk(KEY_ID, key, "");
        List<String> invalidDocuments = List.of(
                "{\"keys\":[]}",
                "{\"keys\":[" + validJwk + "],\"keys\":[]}",
                "{\"keys\":[" + validJwk.replace("\"alg\":\"EdDSA\"",
                        "\"alg\":\"RS256\"") + "]}",
                "{\"keys\":[" + validJwk.replace("}",
                        ",\"d\":\"private\"}") + "]}",
                "{\"keys\":[" + validJwk.replace("}",
                        ",\"key_ops\":[\"sign\"]}") + "]}",
                "{\"keys\":[" + validJwk + "]} trailing",
                jwks(List.of(jwk(KEY_ID, key, ",\"revoked\":true"))));

        for (String document : invalidDocuments) {
            MutableFetcher fetcher = new MutableFetcher();
            fetcher.publish(document, "invalid-generation");
            assertThatThrownBy(() -> new DynamicJwksTestSecretAuthorityTrustStore(
                    JSON, AUTHORITY_ID, Duration.ofSeconds(60), Duration.ofSeconds(5),
                    Duration.ofMillis(100), settings(JWKS_URI, Duration.ofSeconds(60)),
                    Clock.fixed(NOW, ZoneOffset.UTC), fetcher, false))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bootstrap");
        }
    }

    @Test
    void realHttpFetcherUsesConditionalGetAndRejectsInvalidContentType()
            throws Exception {
        KeyPair key = keyPair();
        AtomicReference<String> contentType = new AtomicReference<>(
                "application/jwk-set+json");
        AtomicReference<String> observedConditional = new AtomicReference<>("");
        AtomicInteger calls = new AtomicInteger();
        byte[] body = jwks(Map.of(KEY_ID, key)).getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            calls.incrementAndGet();
            String conditional = exchange.getRequestHeaders()
                    .getFirst("If-None-Match");
            observedConditional.set(conditional == null ? "" : conditional);
            if ("generation-a".equals(conditional)) {
                exchange.getResponseHeaders().set("ETag", "generation-a");
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", contentType.get());
                exchange.getResponseHeaders().set("ETag", "generation-a");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/jwks");
            DynamicJwksTestSecretAuthorityTrustStore store =
                    new DynamicJwksTestSecretAuthorityTrustStore(
                            JSON, AUTHORITY_ID, Duration.ofSeconds(60),
                            Duration.ofSeconds(5), Duration.ofMillis(100),
                            settings(uri, Duration.ofSeconds(60)),
                            Clock.fixed(NOW, ZoneOffset.UTC), null, false);
            stores.add(store);
            assertThat(store.refreshNow()).isTrue();
            assertThat(calls).hasValue(2);
            assertThat(observedConditional).hasValue("generation-a");

            contentType.set("text/plain");
            observedConditional.set("");
            // A changed response must be fetched so the content type is evaluated.
            server.removeContext("/jwks");
            server.createContext("/jwks", exchange -> {
                calls.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            assertThat(store.refreshNow()).isFalse();
            assertThat(store.snapshot().lastFailureCode())
                    .isEqualTo("REMOTE_DOCUMENT_INVALID");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void validatesHttpsLoopbackAndSnapshotAgePolicy() {
        assertThat(settings(JWKS_URI, Duration.ofSeconds(60)).validated())
                .isNotNull();
        assertThatThrownBy(() -> settings(
                URI.create("http://iam.example/jwks"), Duration.ofSeconds(60))
                .validated()).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new DynamicJwksTestSecretAuthorityTrustStore.Settings(
                JWKS_URI, Duration.ofSeconds(30), Duration.ofSeconds(5),
                Duration.ofSeconds(3), Duration.ofSeconds(30), false).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cover refresh plus request timeout");
        assertThatThrownBy(() -> settings(
                URI.create("https://user@iam.example/jwks"), Duration.ofSeconds(60))
                .validated()).isInstanceOf(IllegalArgumentException.class);
    }

    private DynamicJwksTestSecretAuthorityTrustStore store(
            MutableClock clock, MutableFetcher fetcher, boolean scheduler) {
        return store(clock, fetcher, scheduler, Duration.ofSeconds(60));
    }

    private DynamicJwksTestSecretAuthorityTrustStore store(
            MutableClock clock,
            MutableFetcher fetcher,
            boolean scheduler,
            Duration maximumSnapshotAge) {
        DynamicJwksTestSecretAuthorityTrustStore store =
                new DynamicJwksTestSecretAuthorityTrustStore(
                        JSON, AUTHORITY_ID, Duration.ofSeconds(60),
                        Duration.ofSeconds(5), Duration.ofMillis(100),
                        settings(JWKS_URI, maximumSnapshotAge), clock, fetcher, scheduler);
        stores.add(store);
        return store;
    }

    private static DynamicJwksTestSecretAuthorityTrustStore.Settings settings(
            URI uri, Duration maximumSnapshotAge) {
        return new DynamicJwksTestSecretAuthorityTrustStore.Settings(
                uri, Duration.ofSeconds(1), Duration.ofSeconds(5),
                Duration.ofMillis(100), maximumSnapshotAge,
                "http".equalsIgnoreCase(uri.getScheme()));
    }

    private static String jwks(Map<String, KeyPair> keys) {
        List<String> items = new ArrayList<>();
        new java.util.TreeMap<>(keys).forEach(
                (keyId, key) -> items.add(jwk(keyId, key, "")));
        return jwks(items);
    }

    private static String jwks(List<String> keys) {
        return "{\"keys\":[" + String.join(",", keys) + "]}";
    }

    private static String jwk(String keyId, KeyPair key, String suffix) {
        byte[] encoded = key.getPublic().getEncoded();
        byte[] coordinate = java.util.Arrays.copyOfRange(
                encoded, encoded.length - 32, encoded.length);
        return "{\"kid\":\"" + keyId + "\",\"kty\":\"OKP\","
                + "\"crv\":\"Ed25519\",\"alg\":\"EdDSA\",\"use\":\"sig\","
                + "\"key_ops\":[\"verify\"],\"x\":\""
                + Base64.getUrlEncoder().withoutPadding().encodeToString(coordinate)
                + "\"" + suffix + "}";
    }

    private static final class MutableFetcher implements
            DynamicJwksTestSecretAuthorityTrustStore.DocumentFetcher {
        private final AtomicReference<byte[]> body = new AtomicReference<>();
        private final AtomicReference<String> etag = new AtomicReference<>("");
        private final AtomicReference<String> lastConditionalEtag =
                new AtomicReference<>("");
        private final AtomicInteger calls = new AtomicInteger();

        void publish(String document, String generation) {
            publish(document.getBytes(StandardCharsets.UTF_8), generation);
        }

        void publish(byte[] document, String generation) {
            body.set(document.clone());
            etag.set(generation);
        }

        int calls() {
            return calls.get();
        }

        String lastConditionalEtag() {
            return lastConditionalEtag.get();
        }

        @Override
        public DynamicJwksTestSecretAuthorityTrustStore.FetchedDocument fetch(
                URI uri, String conditionalEtag, Duration timeout) {
            calls.incrementAndGet();
            lastConditionalEtag.set(conditionalEtag == null ? "" : conditionalEtag);
            String generation = etag.get();
            if (!generation.isBlank() && generation.equals(conditionalEtag)) {
                return DynamicJwksTestSecretAuthorityTrustStore.FetchedDocument
                        .notModified(generation);
            }
            return DynamicJwksTestSecretAuthorityTrustStore.FetchedDocument
                    .modified(body.get(), generation);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
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
            return instant;
        }
    }
}
