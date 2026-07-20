package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootCeremonyTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final String SCOPE = "stability-fleet";
    private static final String ROOT_SET = "external-notary-bootstrap-roots";
    private static final String ROOT_DOMAIN = "external-notary-root.example";
    private static final String NOTARY_DOMAIN = "external-notary.example";
    private static final String ANCHOR_SET = "notary-set-a";
    private static final String POLICY = "sha256:" + "a".repeat(64);
    private static final String GENESIS_POLICY = "sha256:" + "b".repeat(64);

    private ObjectMapper objectMapper;
    private Clock clock;
    private Map<String, KeyPair> genesisKeys;
    private Map<String, KeyPair> generationOneKeys;
    private Map<String, KeyPair> generationTwoKeys;
    private Map<String, KeyPair> notaryKeys;
    private ExternalSequenceAnchorBootstrapRootGenesis genesis;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        genesisKeys = keys("genesis");
        generationOneKeys = keys("one");
        generationTwoKeys = keys("two");
        notaryKeys = keys("notary");
        genesis = genesis(genesisKeys);
    }

    @Test
    void replaysTwoCrossSignedGenerationsAndVerifiesNotaryPublication() throws Exception {
        InMemoryFloor floor = new InMemoryFloor();
        ExternalSequenceAnchorBootstrapRootTransition first = transition(
                1, genesis.materialFingerprint(objectMapper),
                genesisKeys, generationOneKeys,
                NOW.minusSeconds(120), NOW.plusSeconds(7200));
        ExternalSequenceAnchorBootstrapRootTransition second = transition(
                2, first.materialFingerprint(), generationOneKeys, generationTwoKeys,
                NOW.minusSeconds(30), NOW.plusSeconds(10_800));
        var store = configured(bundle(first, second), floor);

        store.verify(notaryPublication(generationTwoKeys), NOW);

        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.managedChain()).isTrue();
            assertThat(descriptor.completeGenesisReplay()).isTrue();
            assertThat(descriptor.restartFreeRotation()).isFalse();
            assertThat(descriptor.durableFloor()).isTrue();
            assertThat(descriptor.authorityCount()).isEqualTo(4);
            assertThat(descriptor.activeAuthorityCount()).isEqualTo(4);
            assertThat(descriptor.signatureThreshold()).isEqualTo(3);
        });
        assertThat(store.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo("HEALTHY");
            assertThat(snapshot.headSequence()).isEqualTo(2);
            assertThat(snapshot.transitionCount()).isEqualTo(2);
            assertThat(snapshot.toString()).doesNotContain(
                    "root-1", "two-key", ROOT_DOMAIN, POLICY);
        });
        assertThat(floor.current.sequence()).isEqualTo(2);
    }

    @Test
    void requiresOldRootAuthorizationAndIncomingProofOfPossession() throws Exception {
        String genesisFingerprint = genesis.materialFingerprint(objectMapper);
        ExternalSequenceAnchorBootstrapRootTransition valid = transition(
                1, genesisFingerprint, genesisKeys, generationOneKeys,
                NOW.minusSeconds(30), NOW.plusSeconds(3600));
        ExternalSequenceAnchorBootstrapRootTransition wrongAuthorization =
                new ExternalSequenceAnchorBootstrapRootTransition(
                        valid.schemaVersion(), valid.material(), valid.materialFingerprint(),
                        signatures(generationTwoKeys, valid.materialFingerprint(),
                                valid.material().issuedAt()),
                        valid.incomingRootSignatures());
        assertThatThrownBy(() -> configured(
                bundle(wrongAuthorization), new InMemoryFloor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authorizing");

        ExternalSequenceAnchorBootstrapRootTransition wrongPossession =
                new ExternalSequenceAnchorBootstrapRootTransition(
                        valid.schemaVersion(), valid.material(), valid.materialFingerprint(),
                        valid.authorizingRootSignatures(),
                        signatures(generationTwoKeys, valid.materialFingerprint(),
                                valid.material().issuedAt()));
        assertThatThrownBy(() -> configured(bundle(wrongPossession), new InMemoryFloor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incoming proof");
    }

    @Test
    void rejectsBrokenChainAndTransitionAuthorizedAfterPreviousExpiry() throws Exception {
        ExternalSequenceAnchorBootstrapRootTransition first = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                generationOneKeys, NOW.minusSeconds(120), NOW.minusSeconds(20));
        ExternalSequenceAnchorBootstrapRootTransition broken = transition(
                2, "sha256:" + "c".repeat(64), generationOneKeys, generationTwoKeys,
                NOW.minusSeconds(10), NOW.plusSeconds(3600));
        assertThatThrownBy(() -> configured(bundle(first, broken), new InMemoryFloor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity or lifecycle");

        ExternalSequenceAnchorBootstrapRootTransition late = transition(
                2, first.materialFingerprint(), generationOneKeys, generationTwoKeys,
                NOW.minusSeconds(10), NOW.plusSeconds(3600));
        assertThatThrownBy(() -> configured(bundle(first, late), new InMemoryFloor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identity or lifecycle");
    }

    @Test
    void rejectsExpiredHeadAndUnavailableActiveRootQuorum() throws Exception {
        ExternalSequenceAnchorBootstrapRootTransition expired = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                generationOneKeys, NOW.minusSeconds(3600), NOW.minusSeconds(1));
        assertThatThrownBy(() -> configured(bundle(expired), new InMemoryFloor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not currently usable");

        Set<String> expiredAtHead = Set.of("root-2", "root-3");
        ExternalSequenceAnchorBootstrapRootTransition insufficient = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                generationOneKeys, NOW.minusSeconds(30), NOW.plusSeconds(3600),
                expiredAtHead);
        assertThatThrownBy(() -> configured(bundle(insufficient), new InMemoryFloor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not currently usable");
    }

    @Test
    void durableFloorRejectsRollbackForkAndGapAcrossReconstruction() throws Exception {
        InMemoryFloor floor = new InMemoryFloor();
        ExternalSequenceAnchorBootstrapRootTransition first = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                generationOneKeys, NOW.minusSeconds(60), NOW.plusSeconds(7200));
        configured(bundle(first), floor);
        ExternalSequenceAnchorBootstrapRootTransition second = transition(
                2, first.materialFingerprint(), generationOneKeys, generationTwoKeys,
                NOW.minusSeconds(10), NOW.plusSeconds(7200));
        configured(bundle(first, second), floor);

        assertThatThrownBy(() -> configured(bundle(first), floor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rollback");

        Map<String, KeyPair> forkKeys = keys("fork");
        ExternalSequenceAnchorBootstrapRootTransition fork = transition(
                2, first.materialFingerprint(), generationOneKeys, forkKeys,
                NOW.minusSeconds(10), NOW.plusSeconds(7200));
        assertThatThrownBy(() -> configured(bundle(first, fork), floor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fork");
    }

    @Test
    void distinguishesUnknownRootKeyFromInvalidSignatureAndRejectsTrustOverlap()
            throws Exception {
        ExternalSequenceAnchorBootstrapRootTransition first = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                generationOneKeys, NOW.minusSeconds(30), NOW.plusSeconds(3600));
        var store = configured(bundle(first), new InMemoryFloor());

        assertThatThrownBy(() -> store.verify(
                notaryPublication(generationTwoKeys), NOW))
                .isInstanceOf(ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException.class)
                .extracting(error -> ((ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException) error).reason())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException.Reason.UNKNOWN_KEY);

        ExternalSequenceAnchorTrustPublication valid =
                notaryPublication(generationOneKeys);
        var corrupted = new ExternalSequenceAnchorTrustPublication(
                valid.schemaVersion(), valid.material(), valid.materialFingerprint(),
                List.of(sign("root-1", "one-key-1", generationTwoKeys.get("root-1"),
                                valid.materialFingerprint(), NOW),
                        valid.bootstrapSignatures().get(1),
                        valid.bootstrapSignatures().get(2)));
        assertThatThrownBy(() -> store.verify(corrupted, NOW))
                .isInstanceOf(ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException.class)
                .extracting(error -> ((ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException) error).reason())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException.Reason.INVALID_SIGNATURE);

        assertThatThrownBy(() -> store.requireIndependentFrom(List.of(
                authorityKey("root-1", "notary-key", generationOneKeys.get("root-1")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be independent");
    }

    @Test
    void strictParserRejectsUnknownFieldsAndWrongPinnedGenesis() throws Exception {
        ExternalSequenceAnchorBootstrapRootTransition first = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                generationOneKeys, NOW.minusSeconds(30), NOW.plusSeconds(3600));
        String json = objectMapper.writeValueAsString(bundle(first));
        String withUnknown = json.replaceFirst("\\{", "{\"unexpected\":true,");
        assertThatThrownBy(() -> ConfiguredExternalSequenceAnchorBootstrapRootTrustStore
                .fromJson(objectMapper, clock, binding(), Set.of(POLICY), genesis,
                        new InMemoryFloor(), withUnknown))
                .isInstanceOf(IllegalArgumentException.class);

        ExternalSequenceAnchorBootstrapRootGenesis otherGenesis = genesis(keys("other"));
        assertThatThrownBy(() -> new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore(
                objectMapper, clock, binding(), Set.of(POLICY), otherGenesis,
                new InMemoryFloor(), bundle(first)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned genesis");
    }

    @Test
    void unknownRootKeyRotatesWithoutRestartAndConcurrentBurstFetchesOnce()
            throws Exception {
        InMemoryFloor floor = new InMemoryFloor();
        ExternalSequenceAnchorBootstrapRootTransition first = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                generationOneKeys, NOW.minusSeconds(60), NOW.plusSeconds(7200));
        ExternalSequenceAnchorBootstrapRootTransition second = transition(
                2, first.materialFingerprint(), generationOneKeys, generationTwoKeys,
                NOW.minusSeconds(10), NOW.plusSeconds(10_800));
        QueueFetcher fetcher = new QueueFetcher(
                document(bundle(first), "etag-a"),
                document(bundle(first, second), "etag-b"));
        var store = dynamic(floor, fetcher);
        ExternalSequenceAnchorTrustPublication rotated =
                notaryPublication(generationTwoKeys);

        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(callers)) {
            List<java.util.concurrent.Future<?>> results = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await(2, TimeUnit.SECONDS);
                    store.verify(rotated, NOW);
                    return null;
                }));
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (var result : results) {
                result.get(3, TimeUnit.SECONDS);
            }
        }

        assertThat(fetcher.fetchCount()).isEqualTo(2);
        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.restartFreeRotation()).isTrue();
            assertThat(descriptor.completeGenesisReplay()).isTrue();
        });
        assertThat(store.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo("HEALTHY");
            assertThat(snapshot.headSequence()).isEqualTo(2);
            assertThat(snapshot.refreshSuccessCount()).isEqualTo(2);
            assertThat(snapshot.refreshFailureCount()).isZero();
        });
        store.close();
    }

    @Test
    void invalidSignatureDoesNotRefreshAndForkFailsClosedUntilExactRecovery()
            throws Exception {
        InMemoryFloor floor = new InMemoryFloor();
        ExternalSequenceAnchorBootstrapRootTransition first = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                generationOneKeys, NOW.minusSeconds(60), NOW.plusSeconds(7200));
        Map<String, KeyPair> forkKeys = keys("fork");
        ExternalSequenceAnchorBootstrapRootTransition fork = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                forkKeys, NOW.minusSeconds(60), NOW.plusSeconds(7200));
        ExternalSequenceAnchorBootstrapRootTransition second = transition(
                2, first.materialFingerprint(), generationOneKeys, generationTwoKeys,
                NOW.minusSeconds(10), NOW.plusSeconds(10_800));
        QueueFetcher fetcher = new QueueFetcher(
                document(bundle(first), "etag-a"),
                document(bundle(fork), "etag-fork"),
                document(bundle(first, second), "etag-b"));
        var store = dynamic(floor, fetcher);

        ExternalSequenceAnchorTrustPublication valid =
                notaryPublication(generationOneKeys);
        var invalid = new ExternalSequenceAnchorTrustPublication(
                valid.schemaVersion(), valid.material(), valid.materialFingerprint(),
                List.of(sign("root-1", "one-key-1", generationTwoKeys.get("root-1"),
                                valid.materialFingerprint(), NOW),
                        valid.bootstrapSignatures().get(1),
                        valid.bootstrapSignatures().get(2)));
        assertThatThrownBy(() -> store.verify(invalid, NOW))
                .isInstanceOf(ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException.class)
                .extracting(error -> ((ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException) error).reason())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException.Reason.INVALID_SIGNATURE);
        assertThat(fetcher.fetchCount()).isOne();

        assertThat(store.refreshNow()).isFalse();
        assertThat(store.snapshot().status()).isEqualTo("REFRESH_FAILED");
        assertThatThrownBy(() -> store.verify(valid, NOW))
                .isInstanceOf(ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException.class)
                .extracting(error -> ((ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException) error).reason())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootTrustStore
                        .TrustException.Reason.UNAVAILABLE);

        assertThat(store.refreshNow()).isTrue();
        store.verify(notaryPublication(generationTwoKeys), NOW);
        assertThat(store.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo("HEALTHY");
            assertThat(snapshot.headSequence()).isEqualTo(2);
            assertThat(snapshot.refreshFailureCount()).isOne();
        });
        store.close();
    }

    @Test
    void notModifiedCannotExtendExpiredSignedRootHead() throws Exception {
        MutableClock mutableClock = new MutableClock(NOW);
        clock = mutableClock;
        ExternalSequenceAnchorBootstrapRootTransition first = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                generationOneKeys, NOW.minusSeconds(30), NOW.plusSeconds(60));
        QueueFetcher fetcher = new QueueFetcher(
                document(bundle(first), "etag-a"),
                new DynamicExternalSequenceAnchorBootstrapRootTrustStore.FetchedDocument(
                        true, new byte[0], "etag-a"));
        var store = dynamic(new InMemoryFloor(), fetcher);

        mutableClock.advance(Duration.ofSeconds(61));

        assertThat(store.refreshNow()).isFalse();
        assertThat(store.snapshot().status()).isEqualTo("REFRESH_FAILED");
        assertThat(store.descriptor().available()).isFalse();
        store.close();
    }

    @Test
    void realHttpRequiresExactMediaVersionEtagAndNoRedirect() throws Exception {
        ExternalSequenceAnchorBootstrapRootTransition first = transition(
                1, genesis.materialFingerprint(objectMapper), genesisKeys,
                generationOneKeys, NOW.minusSeconds(30), NOW.plusSeconds(3600));
        byte[] body = objectMapper.writeValueAsBytes(bundle(first));
        AtomicInteger validCalls = new AtomicInteger();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/valid", exchange -> {
            validCalls.incrementAndGet();
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            protocol.set(exchange.getRequestHeaders().getFirst(
                    DynamicExternalSequenceAnchorBootstrapRootTrustStore.PROTOCOL_HEADER));
            if ("etag-a".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type",
                        DynamicExternalSequenceAnchorBootstrapRootTrustStore.MEDIA_TYPE);
                exchange.getResponseHeaders().set(
                        DynamicExternalSequenceAnchorBootstrapRootTrustStore.PROTOCOL_HEADER,
                        ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION);
                exchange.getResponseHeaders().set("ETag", "etag-a");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/valid");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/generic", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set(
                    DynamicExternalSequenceAnchorBootstrapRootTrustStore.PROTOCOL_HEADER,
                    ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        try {
            var store = httpDynamic(base.resolve("/valid"), new InMemoryFloor());
            assertThat(store.refreshNow()).isTrue();
            assertThat(validCalls).hasValue(2);
            assertThat(accept).hasValue(
                    DynamicExternalSequenceAnchorBootstrapRootTrustStore.MEDIA_TYPE);
            assertThat(protocol).hasValue(
                    ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION);
            store.close();

            assertThatThrownBy(() -> httpDynamic(
                    base.resolve("/redirect"), new InMemoryFloor()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bootstrap is unavailable");
            assertThat(validCalls).hasValue(2);
            assertThatThrownBy(() -> httpDynamic(
                    base.resolve("/generic"), new InMemoryFloor()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bootstrap is unavailable");
        } finally {
            server.stop(0);
        }
    }

    private ConfiguredExternalSequenceAnchorBootstrapRootTrustStore configured(
            ExternalSequenceAnchorBootstrapRootBundle bundle,
            ExternalSequenceAnchorBootstrapRootPublicationFloor floor) {
        return new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore(
                objectMapper, clock, binding(), Set.of(POLICY), genesis, floor, bundle);
    }

    private DynamicExternalSequenceAnchorBootstrapRootTrustStore dynamic(
            ExternalSequenceAnchorBootstrapRootPublicationFloor floor,
            DynamicExternalSequenceAnchorBootstrapRootTrustStore.DocumentFetcher fetcher) {
        return new DynamicExternalSequenceAnchorBootstrapRootTrustStore(
                objectMapper, clock, binding(), Set.of(POLICY), genesis, floor,
                new DynamicExternalSequenceAnchorBootstrapRootTrustStore.Settings(
                        java.net.URI.create("http://127.0.0.1:8080/bootstrap-roots"),
                        Duration.ofSeconds(2), Duration.ofSeconds(10),
                        Duration.ofMinutes(2), Duration.ofSeconds(5), true),
                fetcher, false);
    }

    private DynamicExternalSequenceAnchorBootstrapRootTrustStore httpDynamic(
            URI bundleUri,
            ExternalSequenceAnchorBootstrapRootPublicationFloor floor) {
        return new DynamicExternalSequenceAnchorBootstrapRootTrustStore(
                objectMapper, clock, binding(), Set.of(POLICY), genesis, floor,
                new DynamicExternalSequenceAnchorBootstrapRootTrustStore.Settings(
                        bundleUri, Duration.ofSeconds(2), Duration.ofSeconds(10),
                        Duration.ofMinutes(2), Duration.ofSeconds(5), true),
                null, false);
    }

    private DynamicExternalSequenceAnchorBootstrapRootTrustStore.FetchedDocument document(
            ExternalSequenceAnchorBootstrapRootBundle bundle, String etag) throws Exception {
        return new DynamicExternalSequenceAnchorBootstrapRootTrustStore.FetchedDocument(
                false, objectMapper.writeValueAsBytes(bundle), etag);
    }

    private ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding binding() {
        return new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding(
                SCOPE, ROOT_SET, ROOT_DOMAIN, 3, 1,
                Duration.ofDays(30), Duration.ofSeconds(5),
                Duration.ofSeconds(30), 32);
    }

    private ExternalSequenceAnchorBootstrapRootGenesis genesis(Map<String, KeyPair> keys) {
        return new ExternalSequenceAnchorBootstrapRootGenesis(
                ExternalSequenceAnchorBootstrapRootGenesis.SCHEMA_VERSION,
                SCOPE, ROOT_SET, ROOT_DOMAIN, 3, 1,
                rootMaterials(keys, NOW.minusSeconds(3600), NOW.plusSeconds(86_400), Set.of()),
                GENESIS_POLICY);
    }

    private ExternalSequenceAnchorBootstrapRootTransition transition(
            long sequence,
            String previous,
            Map<String, KeyPair> authorizing,
            Map<String, KeyPair> incoming,
            Instant issuedAt,
            Instant expiresAt) throws Exception {
        return transition(sequence, previous, authorizing, incoming,
                issuedAt, expiresAt, Set.of());
    }

    private ExternalSequenceAnchorBootstrapRootTransition transition(
            long sequence,
            String previous,
            Map<String, KeyPair> authorizing,
            Map<String, KeyPair> incoming,
            Instant issuedAt,
            Instant expiresAt,
            Set<String> expiredAtHead) throws Exception {
        String prefix = incoming == generationOneKeys ? "one"
                : incoming == generationTwoKeys ? "two" : "fork";
        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                ROOT_SET, sequence, previous, SCOPE, ROOT_DOMAIN, 3, 1,
                rootMaterials(incoming, issuedAt.minusSeconds(60), expiresAt,
                        Set.of(), expiredAtHead, prefix),
                POLICY, issuedAt, issuedAt, expiresAt);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new ExternalSequenceAnchorBootstrapRootTransition(
                ExternalSequenceAnchorBootstrapRootTransition.SCHEMA_VERSION,
                material, fingerprint,
                signatures(authorizing, fingerprint, issuedAt),
                signatures(incoming, fingerprint, issuedAt, prefix));
    }

    private ExternalSequenceAnchorBootstrapRootBundle bundle(
            ExternalSequenceAnchorBootstrapRootTransition... transitions) {
        return new ExternalSequenceAnchorBootstrapRootBundle(
                ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION,
                genesis.materialFingerprint(objectMapper), List.of(transitions),
                transitions[transitions.length - 1].materialFingerprint());
    }

    private ExternalSequenceAnchorTrustPublication notaryPublication(
            Map<String, KeyPair> signingRoots) throws Exception {
        List<ExternalSequenceAnchorTrustPublication.AuthorityKeyMaterial> materials =
                notaryKeys.entrySet().stream()
                        .map(entry -> new ExternalSequenceAnchorTrustPublication
                                .AuthorityKeyMaterial(entry.getKey(),
                                "notary-key-" + entry.getKey(),
                                Base64.getEncoder().encodeToString(
                                        entry.getValue().getPublic().getEncoded()),
                                NOW.minusSeconds(60), NOW.plusSeconds(3600), true, false))
                        .sorted(Comparator.comparing(
                                ExternalSequenceAnchorTrustPublication.AuthorityKeyMaterial
                                        ::authorityId))
                        .toList();
        var material = new ExternalSequenceAnchorTrustPublication.Material(
                ExternalSequenceAnchorTrustPublication.Material.SCHEMA_VERSION,
                ROOT_SET, 1, "", SCOPE, ANCHOR_SET, NOTARY_DOMAIN, ROOT_DOMAIN,
                3, 1, materials, POLICY, NOW.minusSeconds(10), NOW.minusSeconds(10),
                NOW.plusSeconds(3600));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        String prefix = signingRoots == generationOneKeys ? "one" : "two";
        return new ExternalSequenceAnchorTrustPublication(
                ExternalSequenceAnchorTrustPublication.SCHEMA_VERSION,
                material, fingerprint, signatures(signingRoots, fingerprint, NOW, prefix));
    }

    private List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> rootMaterials(
            Map<String, KeyPair> keys,
            Instant notBefore,
            Instant expiresAt,
            Set<String> revoked) {
        return rootMaterials(keys, notBefore, expiresAt, revoked, "genesis");
    }

    private List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> rootMaterials(
            Map<String, KeyPair> keys,
            Instant notBefore,
            Instant expiresAt,
            Set<String> revoked,
            String prefix) {
        return rootMaterials(keys, notBefore, expiresAt, revoked, Set.of(), prefix);
    }

    private List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> rootMaterials(
            Map<String, KeyPair> keys,
            Instant notBefore,
            Instant expiresAt,
            Set<String> revoked,
            Set<String> expiredAtHead,
            String prefix) {
        return keys.entrySet().stream()
                .map(entry -> new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                        entry.getKey(), prefix + "-key-" + entry.getKey().substring(5),
                        Base64.getEncoder().encodeToString(
                                entry.getValue().getPublic().getEncoded()),
                        notBefore,
                        expiredAtHead.contains(entry.getKey())
                                ? NOW.minusSeconds(1) : expiresAt,
                        true, revoked.contains(entry.getKey())))
                .sorted(Comparator.comparing(
                        ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial::authorityId))
                .toList();
    }

    private List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures(
            Map<String, KeyPair> keys, String fingerprint, Instant signedAt) throws Exception {
        String prefix = keys == genesisKeys ? "genesis"
                : keys == generationOneKeys ? "one"
                : keys == generationTwoKeys ? "two" : "other";
        return signatures(keys, fingerprint, signedAt, prefix);
    }

    private List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures(
            Map<String, KeyPair> keys,
            String fingerprint,
            Instant signedAt,
            String prefix) throws Exception {
        return keys.entrySet().stream().limit(3).map(entry -> {
            try {
                return sign(entry.getKey(),
                        prefix + "-key-" + entry.getKey().substring(5),
                        entry.getValue(), fingerprint, signedAt);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).sorted(Comparator.comparing(
                TestSuiteStabilityServingInventory.AuthoritySignature::authorityId)).toList();
    }

    private TestSuiteStabilityServingInventory.AuthoritySignature sign(
            String authorityId,
            String keyId,
            KeyPair keyPair,
            String fingerprint,
            Instant signedAt) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return new TestSuiteStabilityServingInventory.AuthoritySignature(
                authorityId, keyId, "Ed25519", signedAt,
                Base64.getEncoder().encodeToString(signer.sign()));
    }

    private ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey authorityKey(
            String authorityId, String keyId, KeyPair keyPair) {
        return new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                authorityId, keyId, keyPair.getPublic(), NOW.minusSeconds(60),
                NOW.plusSeconds(3600), true, false);
    }

    private static Map<String, KeyPair> keys(String label) throws Exception {
        Map<String, KeyPair> result = new HashMap<>();
        for (int index = 1; index <= 4; index++) {
            result.put("root-" + index,
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        }
        return result;
    }

    private static final class InMemoryFloor
            implements ExternalSequenceAnchorBootstrapRootPublicationFloor {

        private Generation current;

        @Override
        public synchronized void accept(VerifiedChain chain) {
            Generation generation = chain.head();
            if (current == null) {
                current = generation;
                return;
            }
            if (generation.sequence() < current.sequence()) {
                throw new IllegalArgumentException("floor rejected rollback");
            }
            if (generation.sequence() == current.sequence()) {
                if (!generation.materialFingerprint().equals(current.materialFingerprint())) {
                    throw new IllegalArgumentException("floor rejected fork");
                }
                return;
            }
            Generation ancestor = chain.generations().get((int) current.sequence() - 1);
            if (!ancestor.materialFingerprint().equals(current.materialFingerprint())) {
                throw new IllegalArgumentException("floor rejected forked ancestry");
            }
            current = generation;
        }

        @Override
        public boolean durable() {
            return true;
        }
    }

    private static final class QueueFetcher
            implements DynamicExternalSequenceAnchorBootstrapRootTrustStore.DocumentFetcher {

        private final ArrayDeque<
                DynamicExternalSequenceAnchorBootstrapRootTrustStore.FetchedDocument> documents;
        private final AtomicInteger fetches = new AtomicInteger();

        private QueueFetcher(
                DynamicExternalSequenceAnchorBootstrapRootTrustStore.FetchedDocument...
                        documents) {
            this.documents = new ArrayDeque<>(List.of(documents));
        }

        @Override
        public synchronized DynamicExternalSequenceAnchorBootstrapRootTrustStore.FetchedDocument
                fetch(java.net.URI uri, String etag, Duration timeout) {
            fetches.incrementAndGet();
            var result = documents.pollFirst();
            if (result == null) {
                throw new IllegalStateException("No queued bootstrap-root bundle");
            }
            return result;
        }

        private int fetchCount() {
            return fetches.get();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
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
            return current;
        }
    }
}
