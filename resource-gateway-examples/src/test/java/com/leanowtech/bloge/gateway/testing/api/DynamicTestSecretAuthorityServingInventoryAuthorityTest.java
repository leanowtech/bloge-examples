package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

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
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicTestSecretAuthorityServingInventoryAuthorityTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final String TRUST_DOMAIN = "test-secret-inventory.example";
    private static final String WITNESS_DOMAIN = "test-secret-witness.example";
    private static final String POLICY = "sha256:" + "b".repeat(64);
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String SECRET_AUTHORITY = "secret-authority.example";

    private ObjectMapper objectMapper;
    private KeyPair deploymentA;
    private KeyPair deploymentB;
    private KeyPair witnessA;
    private KeyPair witnessB;
    private MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        deploymentA = generator.generateKeyPair();
        deploymentB = generator.generateKeyPair();
        witnessA = generator.generateKeyPair();
        witnessB = generator.generateKeyPair();
        clock = new MutableClock(NOW);
    }

    @Test
    void bootstrapsWitnessedPublicationAndExposesOnlyAggregateTruth() throws Exception {
        var first = publication(1,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                "", "", inventory());

        try (var authority = authority(fetcher(document(first, "generation-1")))) {
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isTrue();
                assertThat(observed.status()).isEqualTo("VERIFIED");
                assertThat(observed.sourceType()).isEqualTo(
                        DynamicTestSecretAuthorityServingInventoryAuthority.SOURCE_TYPE);
                assertThat(observed.expectedInstanceIds())
                        .containsExactly("replica-a", "replica-b");
            });
            assertThat(authority.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.available()).isTrue();
                assertThat(descriptor.properties())
                        .containsEntry("automaticRefresh", false)
                        .containsEntry("signedRevocation", true)
                        .containsEntry("witnessedPublications", true)
                        .containsEntry("durablePublicationFloor", true)
                        .containsEntry("authorityIdentityBound", true)
                        .doesNotContainKeys("publicationId", "checkpointId", "etag",
                                "materialFingerprint", "instanceIds", "authorityId", "uri",
                                "publicKey", "privateKey");
            });
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isTrue();
                assertThat(snapshot.refreshState()).isEqualTo("HEALTHY");
                assertThat(snapshot.publicationState()).isEqualTo("ACTIVE");
                assertThat(snapshot.sequence()).isOne();
                assertThat(snapshot.refreshSuccessCount()).isOne();
            });
            assertThat(new TestSecretAuthorityServingInventoryHealth(authority).health())
                    .satisfies(health -> {
                        assertThat(health.getStatus()).isEqualTo(Status.UP);
                        assertThat(health.getDetails())
                                .containsEntry("publicationState", "ACTIVE")
                                .doesNotContainKeys("publicationId", "checkpointId", "etag",
                                        "materialFingerprint", "instanceIds", "authorityId",
                                        "uri", "publicKey", "privateKey");
                    });
        }
    }

    @Test
    void etagNotModifiedRenewsFreshnessButHardMaximumAgeStillCloses() throws Exception {
        var first = publication(1,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                "", "", inventory());
        QueueFetcher fetcher = fetcher(document(first, "generation-1"),
                DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument
                        .notModified("generation-1"));

        try (var authority = authority(fetcher)) {
            clock.advance(Duration.ofSeconds(20));
            assertThat(authority.refreshNow()).isTrue();
            assertThat(fetcher.seenEtags()).containsExactly("", "generation-1");
            assertThat(authority.observation().available()).isTrue();

            clock.advance(Duration.ofSeconds(31));
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status()).isEqualTo("SOURCE_EXPIRED");
            });
            assertThat(new TestSecretAuthorityServingInventoryHealth(authority)
                    .health().getStatus()).isEqualTo(Status.DOWN);
        }
    }

    @Test
    void signedRevocationClosesImmediatelyAndValidSuccessorCanRecover() throws Exception {
        TestSecretAuthorityServingInventory nested = inventory();
        var active = publication(1,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                "", "", nested);
        var revoked = publication(2,
                TestSecretAuthorityServingInventoryPublication.State.REVOKED,
                active.materialFingerprint(), active.witness().materialFingerprint(), nested);
        var recovered = publication(3,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                revoked.materialFingerprint(), revoked.witness().materialFingerprint(), nested);

        try (var authority = authority(fetcher(document(active, "generation-1"),
                document(revoked, "generation-2"),
                document(recovered, "generation-3")))) {
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status()).isEqualTo("REVOKED");
            });
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.refreshState()).isEqualTo("HEALTHY");
                assertThat(snapshot.publicationState()).isEqualTo("REVOKED");
                assertThat(snapshot.refreshFailureCount()).isZero();
            });

            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.observation().available()).isTrue();
            assertThat(authority.snapshot().sequence()).isEqualTo(3);
        }
    }

    @Test
    void invalidSuccessorFailsAtomicallyAndTheExactValidSuccessorRecovers() throws Exception {
        TestSecretAuthorityServingInventory nested = inventory();
        var first = publication(1,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                "", "", nested);
        var second = publication(2,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                first.materialFingerprint(), first.witness().materialFingerprint(), nested);
        byte[] tampered = objectMapper.writeValueAsBytes(second);
        tampered[tampered.length - 2] = (byte) (
                tampered[tampered.length - 2] == 'a' ? 'b' : 'a');

        try (var authority = authority(fetcher(document(first, "generation-1"),
                DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument.modified(
                        tampered, "tampered"), document(second, "generation-2")))) {
            String firstHead = authority.observation().sourceGenerationFingerprint();
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status()).isEqualTo("REFRESH_UNAVAILABLE");
                assertThat(observed.sourceSequence()).isOne();
                assertThat(observed.sourceGenerationFingerprint()).isEqualTo(firstHead);
            });
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.refreshFailureCount()).isOne();
                assertThat(snapshot.lastFailureCode()).isEqualTo("REMOTE_DOCUMENT_INVALID");
            });

            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.observation().available()).isTrue();
            assertThat(authority.observation().sourceSequence()).isEqualTo(2);
        }
    }

    @Test
    void durableFloorRejectsRollbackAndSurvivesProcessReconstruction() throws Exception {
        TestSecretAuthorityServingInventory nested = inventory();
        var first = publication(1,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                "", "", nested);
        var second = publication(2,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                first.materialFingerprint(), first.witness().materialFingerprint(), nested);
        InMemoryPublicationFloor floor = new InMemoryPublicationFloor();

        try (var ignored = authority(fetcher(document(first, "generation-1")), floor)) {
            assertThat(ignored.observation().available()).isTrue();
        }
        try (var restarted = authority(fetcher(document(second, "generation-2")), floor)) {
            assertThat(restarted.observation().sourceSequence()).isEqualTo(2);
        }
        assertThatThrownBy(() -> authority(fetcher(document(first, "rollback")), floor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap");
    }

    @Test
    void forkGapAndWitnessSubstitutionFailClosedWithoutAdvancingHead() throws Exception {
        TestSecretAuthorityServingInventory nested = inventory();
        var first = publication(1,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                "", "", nested);
        var gap = publication(3,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                first.materialFingerprint(), first.witness().materialFingerprint(), nested);
        try (var authority = authority(fetcher(document(first, "generation-1"),
                document(gap, "gap")))) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().sourceSequence()).isOne();
        }

        assertThatThrownBy(() -> new DynamicTestSecretAuthorityServingInventoryAuthority(
                objectMapper, clock, TRUST_DOMAIN, Set.of(POLICY), 2, deploymentKeys(),
                binding(), new InMemoryPublicationFloor(), WITNESS_DOMAIN, 1,
                List.of(secretKey("deployment-a", "witness-key", deploymentA)),
                settings(), fetcher(document(first, "generation-1")), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("independent");
    }

    @Test
    void realHttpNegotiatesVendorProtocolAndUsesConditionalRequest() throws Exception {
        var first = publication(1,
                TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                "", "", inventory());
        byte[] body = objectMapper.writeValueAsBytes(first);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        AtomicReference<String> conditional = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/inventory", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            protocol.set(exchange.getRequestHeaders().getFirst(
                    DynamicTestSecretAuthorityServingInventoryAuthority.PROTOCOL_HEADER));
            conditional.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().set("Content-Type",
                    DynamicTestSecretAuthorityServingInventoryAuthority.MEDIA_TYPE);
            exchange.getResponseHeaders().set(
                    DynamicTestSecretAuthorityServingInventoryAuthority.PROTOCOL_HEADER,
                    TestSecretAuthorityServingInventoryPublication.SCHEMA_VERSION);
            exchange.getResponseHeaders().set("ETag", "generation-1");
            if (calls.getAndIncrement() == 0) {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(304, -1);
            }
            exchange.close();
        });
        server.start();
        try (var authority = new DynamicTestSecretAuthorityServingInventoryAuthority(
                objectMapper, TRUST_DOMAIN, Set.of(POLICY), 2, deploymentKeys(), binding(),
                new InMemoryPublicationFloor(), WITNESS_DOMAIN, 2, witnessKeys(),
                new DynamicTestSecretAuthorityServingInventoryAuthority.Settings(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                                + "/inventory"),
                        Duration.ofHours(1), Duration.ofSeconds(1), Duration.ofHours(2), true))) {
            assertThat(authority.refreshNow()).isTrue();
            assertThat(accept.get()).isEqualTo(
                    DynamicTestSecretAuthorityServingInventoryAuthority.MEDIA_TYPE);
            assertThat(protocol.get()).isEqualTo(
                    TestSecretAuthorityServingInventoryPublication.SCHEMA_VERSION);
            assertThat(conditional.get()).isEqualTo("generation-1");
        } finally {
            server.stop(0);
        }
    }

    private DynamicTestSecretAuthorityServingInventoryAuthority authority(QueueFetcher fetcher) {
        return authority(fetcher, new InMemoryPublicationFloor());
    }

    private DynamicTestSecretAuthorityServingInventoryAuthority authority(
            QueueFetcher fetcher, TestSecretAuthorityServingInventoryPublicationFloor floor) {
        return new DynamicTestSecretAuthorityServingInventoryAuthority(
                objectMapper, clock, TRUST_DOMAIN, Set.of(POLICY), 2, deploymentKeys(),
                binding(), floor, WITNESS_DOMAIN, 2, witnessKeys(), settings(), fetcher, false);
    }

    private DynamicTestSecretAuthorityServingInventoryAuthority.Settings settings() {
        return new DynamicTestSecretAuthorityServingInventoryAuthority.Settings(
                URI.create("https://test-secret-inventory.example/current"),
                Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofSeconds(30), false);
    }

    private ConfiguredTestSecretAuthorityServingInventoryAuthority.ExpectedBinding binding() {
        return new ConfiguredTestSecretAuthorityServingInventoryAuthority.ExpectedBinding(
                "test-secret-scope", "release-2026-07-20", ARTIFACT,
                TestSecretAuthorityResponse.SCHEMA_VERSION, SECRET_AUTHORITY, "replica-a");
    }

    private List<ConfiguredTestSecretAuthorityServingInventoryAuthority.AuthorityKey>
            deploymentKeys() {
        return List.of(secretKey("deployment-a", "deployment-key-a", deploymentA),
                secretKey("deployment-b", "deployment-key-b", deploymentB));
    }

    private List<ConfiguredTestSecretAuthorityServingInventoryAuthority.AuthorityKey>
            witnessKeys() {
        return List.of(secretKey("witness-a", "witness-key-a", witnessA),
                secretKey("witness-b", "witness-key-b", witnessB));
    }

    private static ConfiguredTestSecretAuthorityServingInventoryAuthority.AuthorityKey secretKey(
            String authorityId, String keyId, KeyPair pair) {
        return new ConfiguredTestSecretAuthorityServingInventoryAuthority.AuthorityKey(
                authorityId, keyId, pair.getPublic(), Instant.MIN, Instant.MAX,
                true, false);
    }

    private TestSecretAuthorityServingInventory inventory() {
        var material = new TestSecretAuthorityServingInventory.Material(
                TestSecretAuthorityServingInventory.Material.SCHEMA_VERSION,
                TRUST_DOMAIN, "inventory-17", 17,
                "test-secret-scope", "release-2026-07-20", ARTIFACT,
                TestSecretAuthorityResponse.SCHEMA_VERSION, SECRET_AUTHORITY,
                List.of("replica-a", "replica-b"), POLICY,
                NOW.minusSeconds(120), NOW.minusSeconds(120), NOW.plusSeconds(3600));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestSecretAuthorityServingInventory(
                TestSecretAuthorityServingInventory.SCHEMA_VERSION,
                material, fingerprint, signatures(fingerprint,
                signer("deployment-a", "deployment-key-a", deploymentA),
                signer("deployment-b", "deployment-key-b", deploymentB)));
    }

    private TestSecretAuthorityServingInventoryPublication publication(
            long sequence,
            TestSecretAuthorityServingInventoryPublication.State state,
            String previousPublication,
            String previousWitness,
            TestSecretAuthorityServingInventory nested) {
        var material = new TestSecretAuthorityServingInventoryPublication.Material(
                TestSecretAuthorityServingInventoryPublication.Material.SCHEMA_VERSION,
                TRUST_DOMAIN, "publication-" + sequence, sequence,
                nested.materialFingerprint(), state, POLICY, previousPublication,
                NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(600),
                state == TestSecretAuthorityServingInventoryPublication.State.ACTIVE
                        ? "" : "DEPLOYMENT_WITHDRAWN");
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var witnessMaterial =
                new TestSecretAuthorityServingInventoryPublication.WitnessMaterial(
                        TestSecretAuthorityServingInventoryPublication.WitnessMaterial
                                .SCHEMA_VERSION,
                        WITNESS_DOMAIN, "checkpoint-" + sequence, sequence,
                        fingerprint, previousWitness, NOW.minusSeconds(30),
                        NOW.minusSeconds(30), NOW.plusSeconds(600));
        String witnessFingerprint = ProtocolFingerprint.of(objectMapper, witnessMaterial);
        var witness = new TestSecretAuthorityServingInventoryPublication.WitnessCheckpoint(
                TestSecretAuthorityServingInventoryPublication.WitnessCheckpoint.SCHEMA_VERSION,
                witnessMaterial, witnessFingerprint,
                signatures(witnessFingerprint,
                        signer("witness-a", "witness-key-a", witnessA),
                        signer("witness-b", "witness-key-b", witnessB)));
        return new TestSecretAuthorityServingInventoryPublication(
                TestSecretAuthorityServingInventoryPublication.SCHEMA_VERSION,
                nested, material, fingerprint,
                signatures(fingerprint,
                        signer("deployment-a", "deployment-key-a", deploymentA),
                        signer("deployment-b", "deployment-key-b", deploymentB)),
                witness);
    }

    private DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument document(
            TestSecretAuthorityServingInventoryPublication publication,
            String etag) throws Exception {
        return DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument.modified(
                objectMapper.writeValueAsBytes(publication), etag);
    }

    private static List<TestSecretAuthorityServingInventory.AuthoritySignature> signatures(
            String fingerprint, Signer... signers) {
        return java.util.Arrays.stream(signers)
                .map(signer -> signer.sign(fingerprint))
                .sorted(Comparator.comparing(
                        TestSecretAuthorityServingInventory.AuthoritySignature::authorityId))
                .toList();
    }

    private static Signer signer(String authorityId, String keyId, KeyPair pair) {
        return new Signer(authorityId, keyId, pair);
    }

    private static QueueFetcher fetcher(Object... results) {
        return new QueueFetcher(List.of(results));
    }

    private record Signer(String authorityId, String keyId, KeyPair pair) {
        private TestSecretAuthorityServingInventory.AuthoritySignature sign(String fingerprint) {
            try {
                Signature signature = Signature.getInstance("Ed25519");
                signature.initSign(pair.getPrivate());
                signature.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new TestSecretAuthorityServingInventory.AuthoritySignature(
                        authorityId, keyId, "Ed25519", NOW.minusSeconds(20),
                        Base64.getEncoder().encodeToString(signature.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class QueueFetcher implements
            DynamicTestSecretAuthorityServingInventoryAuthority.DocumentFetcher {
        private final ArrayDeque<Object> results;
        private final List<String> seenEtags = new ArrayList<>();

        private QueueFetcher(List<Object> results) {
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument fetch(
                URI uri, String etag, Duration timeout) {
            seenEtags.add(etag);
            Object next = results.removeFirst();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument) next;
        }

        private List<String> seenEtags() {
            return List.copyOf(seenEtags);
        }
    }

    private static final class InMemoryPublicationFloor
            implements TestSecretAuthorityServingInventoryPublicationFloor {
        private Generation current;

        @Override
        public synchronized void accept(Generation generation) {
            if (current == null) {
                if (generation.sequence() != 1) {
                    throw new IllegalArgumentException("floor must begin at one");
                }
                current = generation;
                return;
            }
            if (generation.sequence() < current.sequence()) {
                throw new IllegalArgumentException("floor rollback");
            }
            if (generation.sequence() == current.sequence()) {
                if (!generation.publicationMaterialFingerprint().equals(
                        current.publicationMaterialFingerprint())
                        || !generation.witnessMaterialFingerprint().equals(
                        current.witnessMaterialFingerprint())) {
                    throw new IllegalArgumentException("floor fork");
                }
                return;
            }
            if (generation.sequence() != current.sequence() + 1
                    || !generation.previousPublicationFingerprint().equals(
                    current.publicationMaterialFingerprint())
                    || !generation.previousWitnessFingerprint().equals(
                    current.witnessMaterialFingerprint())) {
                throw new IllegalArgumentException("floor discontinuity");
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
