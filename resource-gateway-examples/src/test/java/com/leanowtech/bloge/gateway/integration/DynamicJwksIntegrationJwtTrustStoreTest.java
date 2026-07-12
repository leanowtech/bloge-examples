package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicJwksIntegrationJwtTrustStoreTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final URI JWKS_URI = URI.create("https://iam.example/.well-known/jwks.json");
    private static final URI REVOCATIONS_URI = URI.create("https://iam.example/resource-gateway-revocations.json");
    private static final Instant NOW = Instant.parse("2026-07-13T00:00:00Z");
    private static KeyPair keyA;
    private static KeyPair keyB;
    private static KeyPair ed25519;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyA = generator.generateKeyPair();
        keyB = generator.generateKeyPair();
        ed25519 = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    @Test
    void refreshesUnknownKidOnceAcrossConcurrentRequestsAndPublishesRotationAtomically() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(JWKS_URI, jwks(Map.of("key-a", keyA)), "jwks-1");
        fetcher.publish(REVOCATIONS_URI, revocations(clock, Set.of(), Set.of()), "rev-1");
        DynamicJwksIntegrationJwtTrustStore store = store(clock, fetcher,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.FAIL_CLOSED, Duration.ZERO);
        assertThat(fetcher.requests()).isEqualTo(2);

        fetcher.publish(JWKS_URI, jwks(Map.of("key-a", keyA, "key-b", keyB)), "jwks-2");
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                requests.add(() -> store.find("key-b").isPresent());
            }
            assertThat(executor.invokeAll(requests)).allSatisfy(result -> assertThat(result.get()).isTrue());
        }

        assertThat(fetcher.requests()).isEqualTo(4);
        assertThat(store.snapshot()).extracting(IntegrationJwtTrustStore.Snapshot::refreshState,
                        IntegrationJwtTrustStore.Snapshot::trustedKeyCount,
                        IntegrationJwtTrustStore.Snapshot::refreshSuccessCount)
                .containsExactly("HEALTHY", 2, 2L);
    }

    @Test
    void propagatesKeyAndTokenRevocationWithinConfiguredRefreshSlo() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(JWKS_URI, jwks(Map.of("key-a", keyA)), "jwks-1");
        fetcher.publish(REVOCATIONS_URI, revocations(clock, Set.of(), Set.of()), "rev-1");
        DynamicJwksIntegrationJwtTrustStore store = store(clock, fetcher,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.FAIL_CLOSED, Duration.ZERO);

        fetcher.publish(REVOCATIONS_URI,
                revocations(clock, Set.of("key-a"), Set.of("token-revoked")), "rev-2");
        clock.advance(Duration.ofSeconds(11));

        assertThat(store.isTokenRevoked("token-revoked")).isTrue();
        assertThat(store.find("key-a").orElseThrow().revoked()).isTrue();
        assertThat(store.snapshot()).extracting(IntegrationJwtTrustStore.Snapshot::revokedKeyCount,
                        IntegrationJwtTrustStore.Snapshot::revokedTokenCount,
                        IntegrationJwtTrustStore.Snapshot::propagationSloSeconds)
                .containsExactly(1, 1, 11L);
    }

    @Test
    void failsClosedWhenAuthorityRefreshFailsAndRecoversOnTheNextSuccessfulRefresh() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(JWKS_URI, jwks(Map.of("key-a", keyA)), "jwks-1");
        fetcher.publish(REVOCATIONS_URI, revocations(clock, Set.of(), Set.of()), "rev-1");
        DynamicJwksIntegrationJwtTrustStore store = store(clock, fetcher,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.FAIL_CLOSED, Duration.ZERO);

        fetcher.fail(true);
        clock.advance(Duration.ofSeconds(11));
        assertThatThrownBy(() -> store.find("key-a"))
                .isInstanceOf(IntegrationIdentityProviderUnavailableException.class);
        assertThat(store.snapshot()).extracting(IntegrationJwtTrustStore.Snapshot::refreshState,
                        IntegrationJwtTrustStore.Snapshot::refreshFailureCount,
                        IntegrationJwtTrustStore.Snapshot::lastFailureCode,
                        IntegrationJwtTrustStore.Snapshot::available)
                .containsExactly("EXPIRED", 1L, "REMOTE_AUTHORITY_UNAVAILABLE", false);

        fetcher.fail(false);
        fetcher.publish(JWKS_URI, jwks(Map.of("key-b", keyB)), "jwks-2");
        clock.advance(Duration.ofSeconds(5));
        assertThat(store.find("key-b")).isPresent();
        assertThat(store.find("key-a")).isEmpty();
        assertThat(store.snapshot()).extracting(IntegrationJwtTrustStore.Snapshot::refreshState,
                        IntegrationJwtTrustStore.Snapshot::refreshSuccessCount,
                        IntegrationJwtTrustStore.Snapshot::refreshFailureCount)
                .containsExactly("HEALTHY", 3L, 1L);
    }

    @Test
    void acceptsAnExplicitBoundedStaleSnapshotOnlyUntilItsDeadline() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(JWKS_URI, jwks(Map.of("key-a", keyA)), "jwks-1");
        fetcher.publish(REVOCATIONS_URI, revocations(clock, Set.of(), Set.of()), "rev-1");
        DynamicJwksIntegrationJwtTrustStore store = store(clock, fetcher,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.BOUNDED_STALE, Duration.ofSeconds(30));

        fetcher.fail(true);
        clock.advance(Duration.ofSeconds(11));
        assertThat(store.find("key-a")).isPresent();
        assertThat(store.snapshot()).extracting(IntegrationJwtTrustStore.Snapshot::refreshState,
                        IntegrationJwtTrustStore.Snapshot::staleSnapshotAccepted,
                        IntegrationJwtTrustStore.Snapshot::failClosed)
                .containsExactly("STALE", true, false);

        clock.advance(Duration.ofSeconds(21));
        assertThatThrownBy(() -> store.find("key-a"))
                .isInstanceOf(IntegrationIdentityProviderUnavailableException.class);
        assertThat(store.snapshot().refreshState()).isEqualTo("EXPIRED");
    }

    @Test
    void failsClosedAndNeverPublishesNewKeysWhenTheMatchingRevocationDocumentIsInvalid() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(JWKS_URI, jwks(Map.of("key-a", keyA)), "jwks-1");
        fetcher.publish(REVOCATIONS_URI, revocations(clock, Set.of(), Set.of()), "rev-1");
        DynamicJwksIntegrationJwtTrustStore store = store(clock, fetcher,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.BOUNDED_STALE, Duration.ofMinutes(1));

        fetcher.publish(JWKS_URI, jwks(Map.of("key-b", keyB)), "jwks-2");
        fetcher.publish(REVOCATIONS_URI, "{\"schemaVersion\":\"wrong\"}".getBytes(), "rev-2");
        clock.advance(Duration.ofSeconds(11));

        assertThatThrownBy(() -> store.find("key-a"))
                .isInstanceOf(IntegrationIdentityProviderUnavailableException.class);
        assertThat(store.snapshot()).extracting(IntegrationJwtTrustStore.Snapshot::refreshState,
                        IntegrationJwtTrustStore.Snapshot::trustedKeyCount,
                        IntegrationJwtTrustStore.Snapshot::lastFailureCode)
                .containsExactly("EXPIRED", 1, "REMOTE_DOCUMENT_INVALID");
    }

    @Test
    void rejectsInsecureAuthoritiesAndPrivateOrWeakJwkMaterial() throws Exception {
        assertThatThrownBy(() -> settings(URI.create("http://iam.example/jwks"), null,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.FAIL_CLOSED, Duration.ZERO, false).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> settings(JWKS_URI, REVOCATIONS_URI,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.FAIL_CLOSED,
                Duration.ofSeconds(30), false).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maximum stale");

        KeyPairGenerator weakGenerator = KeyPairGenerator.getInstance("RSA");
        weakGenerator.initialize(1024);
        KeyPair weak = weakGenerator.generateKeyPair();
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(JWKS_URI, jwks(Map.of("weak", weak)), "weak");
        fetcher.publish(REVOCATIONS_URI, revocations(new MutableClock(NOW), Set.of(), Set.of()), "rev");
        assertThatThrownBy(() -> store(new MutableClock(NOW), fetcher,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.FAIL_CLOSED, Duration.ZERO))
                .isInstanceOf(IntegrationIdentityProviderUnavailableException.class);

        byte[] privateJwk = jwks(Map.of("key-a", keyA));
        String withPrivate = new String(privateJwk, java.nio.charset.StandardCharsets.UTF_8)
                .replace("\"alg\":\"RS256\"",
                "\"alg\":\"RS256\",\"d\":\"forbidden\"");
        fetcher.publish(JWKS_URI, withPrivate.getBytes(java.nio.charset.StandardCharsets.UTF_8), "private");
        assertThatThrownBy(() -> store(new MutableClock(NOW), fetcher,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.FAIL_CLOSED, Duration.ZERO))
                .isInstanceOf(IntegrationIdentityProviderUnavailableException.class);

        String invalidExponent = new String(jwks(Map.of("key-a", keyA)),
                java.nio.charset.StandardCharsets.UTF_8).replace("\"e\":\"AQAB\"", "\"e\":\"Ag\"");
        fetcher.publish(JWKS_URI, invalidExponent.getBytes(java.nio.charset.StandardCharsets.UTF_8), "exponent");
        assertThatThrownBy(() -> store(new MutableClock(NOW), fetcher,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.FAIL_CLOSED, Duration.ZERO))
                .isInstanceOf(IntegrationIdentityProviderUnavailableException.class);
    }

    @Test
    void reportsTokenRevocationUnsupportedWhenNoRevocationAuthorityIsConfigured() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(JWKS_URI, jwks(Map.of("key-a", keyA)), "jwks-1");
        DynamicJwksIntegrationJwtTrustStore store = new DynamicJwksIntegrationJwtTrustStore(JSON,
                settings(JWKS_URI, null, DynamicJwksIntegrationJwtTrustStore.OutagePolicy.FAIL_CLOSED,
                        Duration.ZERO, false), clock, fetcher);

        assertThat(store.snapshot()).extracting(IntegrationJwtTrustStore.Snapshot::keyRevocationSupported,
                        IntegrationJwtTrustStore.Snapshot::tokenRevocationSupported)
                .containsExactly(true, false);
    }

    @Test
    void loadsEd25519JwksWithoutPrivateMaterial() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(JWKS_URI, ed25519Jwks("ed-2026", ed25519), "jwks-ed");
        fetcher.publish(REVOCATIONS_URI, revocations(clock, Set.of(), Set.of()), "rev-ed");

        DynamicJwksIntegrationJwtTrustStore store = store(clock, fetcher,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.FAIL_CLOSED, Duration.ZERO);

        IntegrationJwtTrustStore.VerificationKey key = store.find("ed-2026").orElseThrow();
        assertThat(key.algorithm()).isEqualTo("EdDSA");
        assertThat(key.publicKey().getEncoded()).containsExactly(ed25519.getPublic().getEncoded());
    }

    @Test
    void treatsAuthorityDeclaredRevocationExpiryAsHardFailClosed() {
        MutableClock clock = new MutableClock(NOW);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(JWKS_URI, jwks(Map.of("key-a", keyA)), "jwks-1");
        fetcher.publish(REVOCATIONS_URI,
                revocations(clock.instant(), clock.instant().plusSeconds(15), Set.of(), Set.of()), "rev-1");
        DynamicJwksIntegrationJwtTrustStore store = store(clock, fetcher,
                DynamicJwksIntegrationJwtTrustStore.OutagePolicy.BOUNDED_STALE, Duration.ofMinutes(1));

        clock.advance(Duration.ofSeconds(16));

        assertThatThrownBy(() -> store.find("key-a"))
                .isInstanceOf(IntegrationIdentityProviderUnavailableException.class);
        assertThat(store.snapshot()).extracting(IntegrationJwtTrustStore.Snapshot::refreshState,
                        IntegrationJwtTrustStore.Snapshot::lastFailureCode)
                .containsExactly("EXPIRED", "REMOTE_DOCUMENT_INVALID");
    }

    private static DynamicJwksIntegrationJwtTrustStore store(
            MutableClock clock,
            MutableFetcher fetcher,
            DynamicJwksIntegrationJwtTrustStore.OutagePolicy outagePolicy,
            Duration maximumStale) {
        return new DynamicJwksIntegrationJwtTrustStore(JSON,
                settings(JWKS_URI, REVOCATIONS_URI, outagePolicy, maximumStale, false), clock, fetcher);
    }

    private static DynamicJwksIntegrationJwtTrustStore.Settings settings(
            URI jwksUri,
            URI revocationsUri,
            DynamicJwksIntegrationJwtTrustStore.OutagePolicy outagePolicy,
            Duration maximumStale,
            boolean allowInsecureLoopback) {
        return new DynamicJwksIntegrationJwtTrustStore.Settings(jwksUri, revocationsUri,
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofSeconds(1), outagePolicy,
                maximumStale, allowInsecureLoopback);
    }

    private static byte[] jwks(Map<String, KeyPair> keys) {
        try {
            List<Map<String, Object>> values = new ArrayList<>();
            keys.forEach((keyId, pair) -> {
                RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();
                values.add(Map.of("kty", "RSA", "kid", keyId, "alg", "RS256", "use", "sig",
                        "key_ops", List.of("verify"), "n", unsigned(publicKey.getModulus()),
                        "e", unsigned(publicKey.getPublicExponent())));
            });
            return JSON.writeValueAsBytes(Map.of("keys", values));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] revocations(MutableClock clock, Set<String> keyIds, Set<String> tokenIds) {
        return revocations(clock.instant(), clock.instant().plus(Duration.ofHours(1)), keyIds, tokenIds);
    }

    private static byte[] revocations(Instant generatedAt,
                                      Instant expiresAt,
                                      Set<String> keyIds,
                                      Set<String> tokenIds) {
        try {
            return JSON.writeValueAsBytes(Map.of(
                    "schemaVersion", DynamicJwksIntegrationJwtTrustStore.REVOCATION_SCHEMA_VERSION,
                    "generatedAt", generatedAt.toString(),
                    "expiresAt", expiresAt.toString(),
                    "revokedKeyIds", keyIds,
                    "revokedTokenIds", tokenIds));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] ed25519Jwks(String keyId, KeyPair pair) {
        try {
            byte[] encoded = pair.getPublic().getEncoded();
            byte[] coordinate = java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
            return JSON.writeValueAsBytes(Map.of("keys", List.of(Map.of(
                    "kty", "OKP", "kid", keyId, "alg", "EdDSA", "crv", "Ed25519",
                    "use", "sig", "key_ops", List.of("verify"),
                    "x", Base64.getUrlEncoder().withoutPadding().encodeToString(coordinate)))));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static final class MutableFetcher implements DynamicJwksIntegrationJwtTrustStore.DocumentFetcher {
        private final Map<URI, Reply> replies = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger requests = new AtomicInteger();
        private volatile boolean failing;

        void publish(URI uri, byte[] body, String etag) {
            replies.put(uri, new Reply(body.clone(), etag));
        }

        void fail(boolean value) {
            failing = value;
        }

        int requests() {
            return requests.get();
        }

        @Override
        public DynamicJwksIntegrationJwtTrustStore.FetchedDocument fetch(
                URI uri, String etag, Duration timeout) {
            requests.incrementAndGet();
            if (failing) {
                throw new IntegrationIdentityProviderUnavailableException("test authority unavailable");
            }
            Reply reply = replies.get(uri);
            if (reply == null) {
                throw new IntegrationIdentityProviderUnavailableException("test document missing");
            }
            if (!etag.isBlank() && etag.equals(reply.etag())) {
                return DynamicJwksIntegrationJwtTrustStore.FetchedDocument.notModified(etag);
            }
            return DynamicJwksIntegrationJwtTrustStore.FetchedDocument.modified(reply.body(), reply.etag());
        }

        private record Reply(byte[] body, String etag) {
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
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
