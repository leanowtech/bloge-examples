package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

class ExternalSequenceAnchorManagedTrustTest {

    private static final Instant NOW = Instant.parse("2026-07-20T00:00:00Z");
    private static final String POLICY = "sha256:" + "a".repeat(64);
    private static final String SCOPE = "stability-fleet";
    private static final String ROOT_SET = "external-notary-roots";
    private static final String ANCHOR_SET = "notary-set-a";
    private static final String NOTARY_DOMAIN = "external-notary.example";
    private static final String BOOTSTRAP_DOMAIN = "external-notary-root.example";

    private ObjectMapper objectMapper;
    private MutableClock clock;
    private KeyPair rootA;
    private KeyPair rootB;
    private Map<String, KeyPair> generationA;
    private Map<String, KeyPair> generationB;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        clock = new MutableClock(NOW);
        rootA = keyPair();
        rootB = keyPair();
        generationA = notaryKeys();
        generationB = notaryKeys();
    }

    @Test
    void verifiesBootstrapQuorumReceiptLifetimeAndDurableFloor() throws Exception {
        InMemoryFloor floor = new InMemoryFloor();
        ExternalSequenceAnchorTrustPublication publication = publication(
                1, "", generationA, NOW.plusSeconds(3600));
        ConfiguredExternalSequenceAnchorReceiptTrustStore store = configured(
                publication, floor);

        store.verify(receipt("notary-1", "key-a-notary-1",
                generationA.get("notary-1"), NOW, NOW.plusSeconds(10)), NOW);

        assertThat(store.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.managedPublication()).isTrue();
            assertThat(descriptor.restartFreeRotation()).isFalse();
            assertThat(descriptor.durableFloor()).isTrue();
            assertThat(descriptor.authorityCount()).isEqualTo(4);
            assertThat(descriptor.activeAuthorityCount()).isEqualTo(4);
        });
        assertThat(store.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.status()).isEqualTo("VERIFIED");
            assertThat(snapshot.publicationSequence()).isOne();
            assertThat(snapshot.toString()).doesNotContain(
                    "notary-1", "key-a", "publicKey", POLICY);
        });
        assertThat(floor.current.sequence()).isOne();

        assertThatThrownBy(() -> store.verify(receipt("notary-1", "key-a-notary-1",
                generationA.get("notary-1"), NOW.plusSeconds(3570),
                NOW.plusSeconds(3630)), NOW.plusSeconds(3570)))
                .isInstanceOf(ExternalSequenceAnchorReceiptTrustStore.TrustException.class)
                .extracting(error -> ((ExternalSequenceAnchorReceiptTrustStore.TrustException)
                        error).reason())
                .isEqualTo(ExternalSequenceAnchorReceiptTrustStore.TrustException
                        .Reason.KEY_INACTIVE);
    }

    @Test
    void rejectsWrongRootBindingOverlapAndUnavailableActiveQuorum() throws Exception {
        ExternalSequenceAnchorTrustPublication valid = publication(
                1, "", generationA, NOW.plusSeconds(3600));
        List<TestSuiteStabilityServingInventory.AuthoritySignature> wrong = List.of(
                sign("root-a", "root-key-a", generationA.get("notary-1"),
                        valid.materialFingerprint()),
                sign("root-b", "root-key-b", rootB, valid.materialFingerprint()));
        ExternalSequenceAnchorTrustPublication invalidSignature =
                new ExternalSequenceAnchorTrustPublication(valid.schemaVersion(), valid.material(),
                        valid.materialFingerprint(), wrong);
        assertThatThrownBy(() -> configured(invalidSignature, new InMemoryFloor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature verification failed");

        var wrongBinding = new ConfiguredExternalSequenceAnchorReceiptTrustStore.ExpectedBinding(
                "other-fleet", ROOT_SET, ANCHOR_SET, NOTARY_DOMAIN, BOOTSTRAP_DOMAIN,
                3, 1, Duration.ofHours(24), Duration.ofSeconds(5), Duration.ofSeconds(30));
        assertThatThrownBy(() -> new ConfiguredExternalSequenceAnchorReceiptTrustStore(
                objectMapper, clock, wrongBinding, Set.of(POLICY), 2,
                roots(), new InMemoryFloor(), valid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");

        List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> overlappingRoots =
                List.of(rootKey("notary-1", "root-key-a", generationA.get("notary-1")),
                        rootKey("root-b", "root-key-b", rootB));
        ExternalSequenceAnchorTrustPublication overlappingSigned =
                new ExternalSequenceAnchorTrustPublication(
                        valid.schemaVersion(), valid.material(), valid.materialFingerprint(),
                        List.of(sign("notary-1", "root-key-a",
                                        generationA.get("notary-1"),
                                        valid.materialFingerprint()),
                                sign("root-b", "root-key-b", rootB,
                                        valid.materialFingerprint())));
        assertThatThrownBy(() -> new ConfiguredExternalSequenceAnchorReceiptTrustStore(
                objectMapper, clock, binding(), Set.of(POLICY), 2,
                overlappingRoots, new InMemoryFloor(), overlappingSigned))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be independent");

        Map<String, KeyPair> onlyTwoActive = new HashMap<>(generationA);
        ExternalSequenceAnchorTrustPublication insufficient = publication(
                1, "", onlyTwoActive, NOW.plusSeconds(3600), Set.of("notary-3", "notary-4"));
        assertThatThrownBy(() -> configured(insufficient, new InMemoryFloor()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active threshold");
    }

    @Test
    void durableFloorRejectsRollbackForkAndGapAcrossReconstruction() throws Exception {
        InMemoryFloor floor = new InMemoryFloor();
        ExternalSequenceAnchorTrustPublication first = publication(
                1, "", generationA, NOW.plusSeconds(3600));
        configured(first, floor);
        ExternalSequenceAnchorTrustPublication second = publication(
                2, first.materialFingerprint(), generationB, NOW.plusSeconds(3600));
        configured(second, floor);

        assertThatThrownBy(() -> configured(first, floor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rollback");
        ExternalSequenceAnchorTrustPublication fork = publication(
                2, first.materialFingerprint(), generationA, NOW.plusSeconds(3600));
        assertThatThrownBy(() -> configured(fork, floor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fork");
        ExternalSequenceAnchorTrustPublication gap = publication(
                4, second.materialFingerprint(), generationA, NOW.plusSeconds(3600));
        assertThatThrownBy(() -> configured(gap, floor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gap");
    }

    @Test
    void unknownKeyRefreshRotatesWithoutRestartAndConcurrentBurstFetchesOnce()
            throws Exception {
        InMemoryFloor floor = new InMemoryFloor();
        ExternalSequenceAnchorTrustPublication first = publication(
                1, "", generationA, NOW.plusSeconds(3600));
        ExternalSequenceAnchorTrustPublication second = publication(
                2, first.materialFingerprint(), generationB, NOW.plusSeconds(3600));
        QueueFetcher fetcher = new QueueFetcher(
                document(first, "etag-a"), document(second, "etag-b"));
        DynamicExternalSequenceAnchorReceiptTrustStore store = dynamic(floor, fetcher);
        var rotatedReceipt = receipt("notary-1", "key-b-notary-1",
                generationB.get("notary-1"), NOW, NOW.plusSeconds(10));

        int callers = 12;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(callers)) {
            List<java.util.concurrent.Future<?>> results = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await(2, TimeUnit.SECONDS);
                    store.verify(rotatedReceipt, NOW);
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
        assertThat(store.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.status()).isEqualTo("HEALTHY");
            assertThat(snapshot.publicationSequence()).isEqualTo(2);
            assertThat(snapshot.refreshSuccessCount()).isEqualTo(2);
            assertThat(snapshot.refreshFailureCount()).isZero();
        });
        assertThat(store.descriptor().restartFreeRotation()).isTrue();
        store.close();
    }

    @Test
    void invalidSuccessorImmediatelyClosesOldSnapshotAndCanRecoverExactly() throws Exception {
        InMemoryFloor floor = new InMemoryFloor();
        ExternalSequenceAnchorTrustPublication first = publication(
                1, "", generationA, NOW.plusSeconds(3600));
        ExternalSequenceAnchorTrustPublication gap = publication(
                3, first.materialFingerprint(), generationB, NOW.plusSeconds(3600));
        ExternalSequenceAnchorTrustPublication second = publication(
                2, first.materialFingerprint(), generationB, NOW.plusSeconds(3600));
        QueueFetcher fetcher = new QueueFetcher(
                document(first, "etag-a"), document(gap, "etag-gap"),
                document(second, "etag-b"));
        DynamicExternalSequenceAnchorReceiptTrustStore store = dynamic(floor, fetcher);

        assertThat(store.refreshNow()).isFalse();
        assertThat(store.snapshot()).extracting(
                ExternalSequenceAnchorReceiptTrustStore.Snapshot::available,
                ExternalSequenceAnchorReceiptTrustStore.Snapshot::status,
                ExternalSequenceAnchorReceiptTrustStore.Snapshot::refreshFailureCount)
                .containsExactly(false, "REFRESH_FAILED", 1L);
        assertThatThrownBy(() -> store.verify(receipt("notary-1", "key-a-notary-1",
                generationA.get("notary-1"), NOW, NOW.plusSeconds(10)), NOW))
                .isInstanceOf(ExternalSequenceAnchorReceiptTrustStore.TrustException.class)
                .extracting(error -> ((ExternalSequenceAnchorReceiptTrustStore.TrustException)
                        error).reason())
                .isEqualTo(ExternalSequenceAnchorReceiptTrustStore.TrustException
                        .Reason.UNAVAILABLE);

        assertThat(store.refreshNow()).isTrue();
        assertThat(store.snapshot()).extracting(
                ExternalSequenceAnchorReceiptTrustStore.Snapshot::available,
                ExternalSequenceAnchorReceiptTrustStore.Snapshot::publicationSequence)
                .containsExactly(true, 2L);
        store.close();
    }

    @Test
    void notModifiedCannotExtendExpiredPublication() throws Exception {
        InMemoryFloor floor = new InMemoryFloor();
        ExternalSequenceAnchorTrustPublication first = publication(
                1, "", generationA, NOW.plusSeconds(120));
        QueueFetcher fetcher = new QueueFetcher(
                document(first, "etag-a"),
                new DynamicExternalSequenceAnchorReceiptTrustStore.FetchedDocument(
                        true, new byte[0], "etag-a"));
        DynamicExternalSequenceAnchorReceiptTrustStore store = dynamic(floor, fetcher);

        clock.advance(Duration.ofSeconds(121));
        assertThat(store.refreshNow()).isFalse();
        assertThat(store.snapshot()).extracting(
                ExternalSequenceAnchorReceiptTrustStore.Snapshot::available,
                ExternalSequenceAnchorReceiptTrustStore.Snapshot::status)
                .containsExactly(false, "REFRESH_FAILED");
        store.close();
    }

    @Test
    void strictJsonRejectsUnknownDuplicateAndTrailingContent() throws Exception {
        ExternalSequenceAnchorTrustPublication valid = publication(
                1, "", generationA, NOW.plusSeconds(3600));
        String json = objectMapper.writeValueAsString(valid);
        assertThatThrownBy(() -> ConfiguredExternalSequenceAnchorReceiptTrustStore.fromJson(
                objectMapper, clock, binding(), Set.of(POLICY), 2, roots(),
                new InMemoryFloor(), json.replaceFirst("\\{", "{\"unknown\":true,")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConfiguredExternalSequenceAnchorReceiptTrustStore.fromJson(
                objectMapper, clock, binding(), Set.of(POLICY), 2, roots(),
                new InMemoryFloor(), json + "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        String duplicate = json.replaceFirst("\\{", "{\"schemaVersion\":\""
                + ExternalSequenceAnchorTrustPublication.SCHEMA_VERSION + "\",");
        assertThatThrownBy(() -> ConfiguredExternalSequenceAnchorReceiptTrustStore.fromJson(
                objectMapper, clock, binding(), Set.of(POLICY), 2, roots(),
                new InMemoryFloor(), duplicate))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void realHttpEnforcesProtocolEtagAndNoRedirect() throws Exception {
        ExternalSequenceAnchorTrustPublication first = publication(
                1, "", generationA, NOW.plusSeconds(3600));
        byte[] body = objectMapper.writeValueAsBytes(first);
        AtomicReference<String> accept = new AtomicReference<>("");
        AtomicReference<String> protocol = new AtomicReference<>("");
        AtomicReference<String> conditional = new AtomicReference<>("");
        AtomicInteger redirectedTargetCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/trust", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            protocol.set(exchange.getRequestHeaders().getFirst(
                    DynamicExternalSequenceAnchorReceiptTrustStore.PROTOCOL_HEADER));
            String etag = exchange.getRequestHeaders().getFirst("If-None-Match");
            conditional.set(etag == null ? "" : etag);
            exchange.getResponseHeaders().set("Content-Type",
                    DynamicExternalSequenceAnchorReceiptTrustStore.MEDIA_TYPE);
            exchange.getResponseHeaders().set(
                    DynamicExternalSequenceAnchorReceiptTrustStore.PROTOCOL_HEADER,
                    ExternalSequenceAnchorTrustPublication.SCHEMA_VERSION);
            exchange.getResponseHeaders().set("ETag", "trust-generation-1");
            if ("trust-generation-1".equals(etag)) {
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            redirectedTargetCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/generic", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set(
                    DynamicExternalSequenceAnchorReceiptTrustStore.PROTOCOL_HEADER,
                    ExternalSequenceAnchorTrustPublication.SCHEMA_VERSION);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            try (DynamicExternalSequenceAnchorReceiptTrustStore store =
                         httpDynamic(base.resolve("/trust"), new InMemoryFloor())) {
                assertThat(accept).hasValue(
                        DynamicExternalSequenceAnchorReceiptTrustStore.MEDIA_TYPE);
                assertThat(protocol).hasValue(
                        ExternalSequenceAnchorTrustPublication.SCHEMA_VERSION);
                assertThat(store.refreshNow()).isTrue();
                assertThat(conditional).hasValue("trust-generation-1");
            }
            assertThatThrownBy(() -> httpDynamic(
                    base.resolve("/redirect"), new InMemoryFloor()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bootstrap");
            assertThat(redirectedTargetCalls).hasValue(0);
            assertThatThrownBy(() -> httpDynamic(
                    base.resolve("/generic"), new InMemoryFloor()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bootstrap");
        } finally {
            server.stop(0);
        }
    }

    private ConfiguredExternalSequenceAnchorReceiptTrustStore configured(
            ExternalSequenceAnchorTrustPublication publication,
            ExternalSequenceAnchorTrustPublicationFloor floor) {
        return new ConfiguredExternalSequenceAnchorReceiptTrustStore(
                objectMapper, clock, binding(), Set.of(POLICY), 2, roots(), floor, publication);
    }

    private DynamicExternalSequenceAnchorReceiptTrustStore dynamic(
            ExternalSequenceAnchorTrustPublicationFloor floor,
            DynamicExternalSequenceAnchorReceiptTrustStore.DocumentFetcher fetcher) {
        return new DynamicExternalSequenceAnchorReceiptTrustStore(
                objectMapper, clock, binding(), Set.of(POLICY), 2, roots(), floor,
                new DynamicExternalSequenceAnchorReceiptTrustStore.Settings(
                        java.net.URI.create("http://127.0.0.1:8080/notary-trust"),
                        Duration.ofSeconds(2), Duration.ofSeconds(10),
                        Duration.ofMinutes(2), Duration.ofSeconds(5), true),
                fetcher, false);
    }

    private DynamicExternalSequenceAnchorReceiptTrustStore httpDynamic(
            URI publicationUri,
            ExternalSequenceAnchorTrustPublicationFloor floor) {
        return new DynamicExternalSequenceAnchorReceiptTrustStore(
                objectMapper, clock, binding(), Set.of(POLICY), 2, roots(), floor,
                new DynamicExternalSequenceAnchorReceiptTrustStore.Settings(
                        publicationUri, Duration.ofSeconds(2), Duration.ofSeconds(10),
                        Duration.ofMinutes(2), Duration.ofSeconds(5), true),
                null, false);
    }

    private ConfiguredExternalSequenceAnchorReceiptTrustStore.ExpectedBinding binding() {
        return new ConfiguredExternalSequenceAnchorReceiptTrustStore.ExpectedBinding(
                SCOPE, ROOT_SET, ANCHOR_SET, NOTARY_DOMAIN, BOOTSTRAP_DOMAIN,
                3, 1, Duration.ofHours(24), Duration.ofSeconds(5), Duration.ofSeconds(30));
    }

    private ExternalSequenceAnchorTrustPublication publication(
            long sequence,
            String previous,
            Map<String, KeyPair> notaries,
            Instant expiresAt) throws Exception {
        return publication(sequence, previous, notaries, expiresAt, Set.of());
    }

    private ExternalSequenceAnchorTrustPublication publication(
            long sequence,
            String previous,
            Map<String, KeyPair> notaries,
            Instant expiresAt,
            Set<String> revoked) throws Exception {
        List<ExternalSequenceAnchorTrustPublication.AuthorityKeyMaterial> materials =
                notaries.entrySet().stream()
                        .map(entry -> new ExternalSequenceAnchorTrustPublication
                                .AuthorityKeyMaterial(entry.getKey(),
                                (notaries == generationA ? "key-a-" : "key-b-")
                                        + entry.getKey(),
                                Base64.getEncoder().encodeToString(
                                        entry.getValue().getPublic().getEncoded()),
                                NOW.minusSeconds(60), expiresAt,
                                true, revoked.contains(entry.getKey())))
                        .sorted(Comparator.comparing(
                                ExternalSequenceAnchorTrustPublication.AuthorityKeyMaterial
                                        ::authorityId)
                                .thenComparing(
                                        ExternalSequenceAnchorTrustPublication.AuthorityKeyMaterial
                                                ::keyId))
                        .toList();
        var material = new ExternalSequenceAnchorTrustPublication.Material(
                ExternalSequenceAnchorTrustPublication.Material.SCHEMA_VERSION,
                ROOT_SET, sequence, previous, SCOPE, ANCHOR_SET,
                NOTARY_DOMAIN, BOOTSTRAP_DOMAIN, 3, 1, materials, POLICY,
                NOW.minusSeconds(60), NOW.minusSeconds(60), expiresAt);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new ExternalSequenceAnchorTrustPublication(
                ExternalSequenceAnchorTrustPublication.SCHEMA_VERSION,
                material, fingerprint,
                List.of(sign("root-a", "root-key-a", rootA, fingerprint),
                        sign("root-b", "root-key-b", rootB, fingerprint)));
    }

    private TestSuiteStabilityExternalSequenceCheckpointReceipt receipt(
            String authority,
            String keyId,
            KeyPair keyPair,
            Instant issuedAt,
            Instant expiresAt) throws Exception {
        String requestFingerprint = "sha256:" + "b".repeat(64);
        String headFingerprint = "sha256:" + "c".repeat(64);
        var material = new TestSuiteStabilityExternalSequenceCheckpointReceipt.Material(
                TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION,
                requestFingerprint, NOTARY_DOMAIN, ANCHOR_SET, authority,
                "domain-" + authority, keyId,
                TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.ACCEPTED,
                1, headFingerprint, 1, headFingerprint,
                issuedAt, expiresAt, "Ed25519");
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return new TestSuiteStabilityExternalSequenceCheckpointReceipt(
                TestSuiteStabilityExternalSequenceCheckpointReceipt.SCHEMA_VERSION,
                fingerprint, requestFingerprint, NOTARY_DOMAIN, ANCHOR_SET,
                authority, "domain-" + authority, keyId,
                TestSuiteStabilityExternalSequenceCheckpointReceipt.Decision.ACCEPTED,
                1, headFingerprint, 1, headFingerprint,
                issuedAt, expiresAt, "Ed25519",
                Base64.getEncoder().encodeToString(signer.sign()));
    }

    private List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey> roots() {
        return List.of(rootKey("root-a", "root-key-a", rootA),
                rootKey("root-b", "root-key-b", rootB));
    }

    private ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey rootKey(
            String authority,
            String keyId,
            KeyPair pair) {
        return new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                authority, keyId, pair.getPublic(), NOW.minusSeconds(3600),
                NOW.plusSeconds(86_400), true, false);
    }

    private TestSuiteStabilityServingInventory.AuthoritySignature sign(
            String authority,
            String keyId,
            KeyPair pair,
            String fingerprint) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
        return new TestSuiteStabilityServingInventory.AuthoritySignature(
                authority, keyId, "Ed25519", NOW,
                Base64.getEncoder().encodeToString(signer.sign()));
    }

    private DynamicExternalSequenceAnchorReceiptTrustStore.FetchedDocument document(
            ExternalSequenceAnchorTrustPublication publication,
            String etag) throws Exception {
        return new DynamicExternalSequenceAnchorReceiptTrustStore.FetchedDocument(
                false, objectMapper.writeValueAsBytes(publication), etag);
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static Map<String, KeyPair> notaryKeys() throws Exception {
        Map<String, KeyPair> result = new HashMap<>();
        for (int index = 1; index <= 4; index++) {
            result.put("notary-" + index, keyPair());
        }
        return result;
    }

    private static final class QueueFetcher
            implements DynamicExternalSequenceAnchorReceiptTrustStore.DocumentFetcher {

        private final ArrayDeque<DynamicExternalSequenceAnchorReceiptTrustStore.FetchedDocument>
                documents;
        private final AtomicInteger fetches = new AtomicInteger();

        private QueueFetcher(
                DynamicExternalSequenceAnchorReceiptTrustStore.FetchedDocument... documents) {
            this.documents = new ArrayDeque<>(List.of(documents));
        }

        @Override
        public synchronized DynamicExternalSequenceAnchorReceiptTrustStore.FetchedDocument fetch(
                java.net.URI uri,
                String etag,
                Duration timeout) {
            fetches.incrementAndGet();
            DynamicExternalSequenceAnchorReceiptTrustStore.FetchedDocument result =
                    documents.pollFirst();
            if (result == null) {
                throw new IllegalStateException("No queued trust publication");
            }
            return result;
        }

        private int fetchCount() {
            return fetches.get();
        }
    }

    private static final class InMemoryFloor
            implements ExternalSequenceAnchorTrustPublicationFloor {

        private Generation current;

        @Override
        public synchronized void accept(Generation generation) {
            if (current == null) {
                if (generation.sequence() != 1) {
                    throw new IllegalArgumentException("floor must begin at sequence one");
                }
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
            if (generation.sequence() != current.sequence() + 1) {
                throw new IllegalArgumentException("floor rejected gap");
            }
            if (!generation.previousMaterialFingerprint()
                    .equals(current.materialFingerprint())) {
                throw new IllegalArgumentException("floor rejected predecessor");
            }
            current = generation;
        }

        @Override
        public boolean durable() {
            return true;
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
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
