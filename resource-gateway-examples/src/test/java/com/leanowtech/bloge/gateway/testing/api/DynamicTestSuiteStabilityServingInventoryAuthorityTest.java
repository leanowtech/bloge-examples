package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.URI;
import java.net.InetSocketAddress;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicTestSuiteStabilityServingInventoryAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final String TRUST_DOMAIN = "inventory.example";
    private static final String WITNESS_DOMAIN = "inventory-witness.example";
    private static final String POLICY = "sha256:" + "b".repeat(64);
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);

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
    void bootstrapsVerifiedPublicationAndExposesOnlyAggregateRefreshTruth()
            throws Exception {
        var first = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        QueueFetcher fetcher = fetcher(document(first, "generation-1"));

        try (var authority = authority(fetcher)) {
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isTrue();
                assertThat(observed.status()).isEqualTo("VERIFIED");
                assertThat(observed.sourceType()).isEqualTo(
                        DynamicTestSuiteStabilityServingInventoryAuthority.SOURCE_TYPE);
                assertThat(observed.expectedInstanceIds())
                        .containsExactly("replica-a", "replica-b");
            });
            assertThat(authority.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.available()).isTrue();
                assertThat(descriptor.properties())
                        .containsEntry("automaticRefresh", false)
                        .containsEntry("signedRevocation", true)
                        .containsEntry("witnessedPublications", true)
                        .containsEntry("privateMaterialPresent", false)
                        .doesNotContainKeys("publicationId", "checkpointId", "etag",
                                "materialFingerprint", "instanceIds", "publicKey",
                                "privateKey", "uri");
            });
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isTrue();
                assertThat(snapshot.refreshState()).isEqualTo("HEALTHY");
                assertThat(snapshot.publicationState()).isEqualTo("ACTIVE");
                assertThat(snapshot.sequence()).isOne();
                assertThat(snapshot.refreshSuccessCount()).isOne();
            });
            assertThat(new TestSuiteStabilityServingInventoryHealth(authority).health())
                    .satisfies(health -> {
                        assertThat(health.getStatus()).isEqualTo(Status.UP);
                        assertThat(health.getDetails())
                                .containsEntry("publicationState", "ACTIVE")
                                .doesNotContainKeys("publicationId", "checkpointId", "etag",
                                        "materialFingerprint", "instanceIds", "uri",
                                        "publicKey", "privateKey");
                    });
        }
    }

    @Test
    void conditionalNotModifiedRefreshRenewsSourceFreshnessWithoutChangingIdentity()
            throws Exception {
        var first = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        QueueFetcher fetcher = fetcher(document(first, "generation-1"),
                DynamicTestSuiteStabilityServingInventoryAuthority.FetchedDocument
                        .notModified("generation-1"));

        try (var authority = authority(fetcher)) {
            clock.advance(Duration.ofSeconds(20));
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.observation().available()).isTrue();
            assertThat(authority.snapshot().refreshSuccessCount()).isEqualTo(2);
            assertThat(fetcher.seenEtags()).containsExactly("", "generation-1");
        }
    }

    @Test
    void signedRevocationIsAHealthyRefreshThatImmediatelyClosesAdmission()
            throws Exception {
        TestSuiteStabilityServingInventory inventory = inventory(17);
        var active = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "", "", inventory);
        var revoked = publication(2,
                TestSuiteStabilityServingInventoryPublication.State.REVOKED,
                active.materialFingerprint(), active.witness().materialFingerprint(),
                inventory);
        QueueFetcher fetcher = fetcher(document(active, "generation-1"),
                document(revoked, "generation-2"));

        try (var authority = authority(fetcher)) {
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status()).isEqualTo("REVOKED");
                assertThat(observed.expectedInstanceIds())
                        .containsExactly("replica-a", "replica-b");
            });
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.refreshState()).isEqualTo("HEALTHY");
                assertThat(snapshot.publicationState()).isEqualTo("REVOKED");
                assertThat(snapshot.sequence()).isEqualTo(2);
            });
            assertThat(new TestSuiteStabilityServingInventoryHealth(authority)
                    .health().getStatus()).isEqualTo(Status.DOWN);
        }
    }

    @Test
    void refreshFailureClosesImmediatelyAndValidSuccessorRecoversAtomically()
            throws Exception {
        TestSuiteStabilityServingInventory inventory = inventory(17);
        var first = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "", "", inventory);
        var second = publication(2,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                first.materialFingerprint(), first.witness().materialFingerprint(),
                inventory);
        QueueFetcher fetcher = fetcher(document(first, "generation-1"),
                new IllegalStateException("network unavailable"),
                document(second, "generation-2"));

        try (var authority = authority(fetcher)) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().status())
                    .isEqualTo("REFRESH_UNAVAILABLE");
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.refreshFailureCount()).isOne();
                assertThat(snapshot.lastFailureCode())
                        .isEqualTo("REMOTE_REFRESH_FAILED");
            });

            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.observation().available()).isTrue();
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.sequence()).isEqualTo(2);
                assertThat(snapshot.refreshSuccessCount()).isEqualTo(2);
                assertThat(snapshot.refreshFailureCount()).isOne();
                assertThat(snapshot.lastFailureCode()).isEmpty();
            });
        }
    }

    @Test
    void maximumSnapshotAgeClosesEvenWhenBackgroundLaneIsSilent() throws Exception {
        var first = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        try (var authority = authority(fetcher(document(first, "generation-1")))) {
            clock.advance(Duration.ofSeconds(31));
            assertThat(authority.observation().status()).isEqualTo("SOURCE_EXPIRED");
            assertThat(authority.snapshot().refreshState()).isEqualTo("EXPIRED");
        }
    }

    @Test
    void sameSequenceForkGapAndBrokenPredecessorFailClosed() throws Exception {
        TestSuiteStabilityServingInventory inventory = inventory(17);
        var first = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "", "", inventory);
        var fork = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.REVOKED,
                "", "", inventory);
        QueueFetcher forkFetcher = fetcher(document(first, "generation-1"),
                document(fork, "fork"));
        try (var authority = authority(forkFetcher)) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().status())
                    .isEqualTo("REFRESH_UNAVAILABLE");
        }

        var gap = publication(3,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                first.materialFingerprint(), first.witness().materialFingerprint(),
                inventory);
        QueueFetcher gapFetcher = fetcher(document(first, "generation-1"),
                document(gap, "gap"));
        try (var authority = authority(gapFetcher)) {
            assertThat(authority.refreshNow()).isFalse();
        }

        var second = publication(2,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "sha256:" + "f".repeat(64), first.witness().materialFingerprint(),
                inventory);
        QueueFetcher brokenFetcher = fetcher(document(first, "generation-1"),
                document(second, "broken"));
        try (var authority = authority(brokenFetcher)) {
            assertThat(authority.refreshNow()).isFalse();
        }

        var inventoryRollback = publication(2,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                first.materialFingerprint(), first.witness().materialFingerprint(),
                inventory(16));
        QueueFetcher rollbackFetcher = fetcher(document(first, "generation-1"),
                document(inventoryRollback, "rollback"));
        try (var authority = authority(rollbackFetcher)) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.snapshot().lastFailureCode())
                    .isEqualTo("REMOTE_DOCUMENT_INVALID");
        }
    }

    @Test
    void wrongWitnessAndNonIndependentWitnessTrustAreRejected() throws Exception {
        TestSuiteStabilityServingInventory inventory = inventory(17);
        var valid = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "", "", inventory);
        var badWitness = new TestSuiteStabilityServingInventoryPublication.WitnessCheckpoint(
                valid.witness().schemaVersion(), valid.witness().material(),
                valid.witness().materialFingerprint(),
                signatures(valid.witness().materialFingerprint(),
                        signer("witness-a", "witness-key-a", deploymentA)));
        var invalid = new TestSuiteStabilityServingInventoryPublication(
                valid.schemaVersion(), valid.inventory(), valid.material(),
                valid.materialFingerprint(), valid.signatures(), badWitness);
        assertThatThrownBy(() -> authority(fetcher(document(invalid, "invalid"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap");

        assertThatThrownBy(() -> new DynamicTestSuiteStabilityServingInventoryAuthority(
                objectMapper, clock, TRUST_DOMAIN, Set.of(POLICY), 2,
                deploymentKeys(), binding(), WITNESS_DOMAIN, 1,
                List.of(key("other-witness-id", "witness-key", deploymentA)),
                settings(), fetcher(document(valid, "valid")), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("independent");
    }

    @Test
    void strictParserRejectsUnknownDuplicateAndTrailingDocumentContent() throws Exception {
        var first = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        String json = objectMapper.writeValueAsString(first);
        for (String invalid : List.of(
                json.replaceFirst("\\{", "{\"credential\":\"forbidden\","),
                json.replaceFirst("\\{", "{\"schemaVersion\":\"duplicate\","),
                json + "{}")) {
            assertThatThrownBy(() -> authority(fetcher(
                    DynamicTestSuiteStabilityServingInventoryAuthority.FetchedDocument.modified(
                            invalid.getBytes(StandardCharsets.UTF_8), "invalid"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bootstrap");
        }
    }

    @Test
    void closeMakesPreviouslyVerifiedLocalSnapshotUnavailable() throws Exception {
        var first = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        var authority = authority(fetcher(document(first, "generation-1")));

        authority.close();

        assertThat(authority.observation().status()).isEqualTo("CLOSED");
        assertThat(authority.refreshNow()).isFalse();
        assertThat(authority.descriptor().properties())
                .containsEntry("automaticRefresh", false);
    }

    @Test
    void realHttpTransportNegotiatesVersionUsesEtagAndRejectsMediaDowngrades()
            throws Exception {
        var first = publication(1,
                TestSuiteStabilityServingInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        byte[] body = objectMapper.writeValueAsBytes(first);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> accept = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        AtomicReference<String> conditional = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/inventory", exchange -> {
            int request = requests.incrementAndGet();
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            protocol.set(exchange.getRequestHeaders().getFirst(
                    DynamicTestSuiteStabilityServingInventoryAuthority.PROTOCOL_HEADER));
            conditional.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().set(
                    DynamicTestSuiteStabilityServingInventoryAuthority.PROTOCOL_HEADER,
                    TestSuiteStabilityServingInventoryPublication.SCHEMA_VERSION);
            exchange.getResponseHeaders().set("ETag", "\"generation-1\"");
            if (request == 2) {
                exchange.getResponseHeaders().set("Content-Type",
                        DynamicTestSuiteStabilityServingInventoryAuthority.MEDIA_TYPE);
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", switch (request) {
                    case 1 -> DynamicTestSuiteStabilityServingInventoryAuthority.MEDIA_TYPE;
                    case 3 -> DynamicTestSuiteStabilityServingInventoryAuthority.MEDIA_TYPE
                            + "evil";
                    default -> "application/json";
                });
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            var remoteSettings =
                    new DynamicTestSuiteStabilityServingInventoryAuthority.Settings(
                            URI.create("http://127.0.0.1:"
                                    + server.getAddress().getPort() + "/inventory"),
                            Duration.ofSeconds(10), Duration.ofSeconds(1),
                            Duration.ofSeconds(30), true);
            try (var authority = new DynamicTestSuiteStabilityServingInventoryAuthority(
                    objectMapper, clock, TRUST_DOMAIN, Set.of(POLICY), 2,
                    deploymentKeys(), binding(), WITNESS_DOMAIN, 2, witnessKeys(),
                    remoteSettings, null, false)) {
                assertThat(accept.get()).isEqualTo(
                        DynamicTestSuiteStabilityServingInventoryAuthority.MEDIA_TYPE);
                assertThat(protocol.get()).isEqualTo(
                        TestSuiteStabilityServingInventoryPublication.SCHEMA_VERSION);
                assertThat(authority.refreshNow()).isTrue();
                assertThat(conditional.get()).isEqualTo("\"generation-1\"");
                assertThat(authority.refreshNow()).isFalse();
                assertThat(authority.observation().status())
                        .isEqualTo("REFRESH_UNAVAILABLE");
                assertThat(authority.refreshNow()).isFalse();
            }
        } finally {
            server.stop(0);
        }
    }

    private DynamicTestSuiteStabilityServingInventoryAuthority authority(
            QueueFetcher fetcher) {
        return new DynamicTestSuiteStabilityServingInventoryAuthority(
                objectMapper, clock, TRUST_DOMAIN, Set.of(POLICY), 2,
                deploymentKeys(), binding(), WITNESS_DOMAIN, 2, witnessKeys(),
                settings(), fetcher, false);
    }

    private DynamicTestSuiteStabilityServingInventoryAuthority.Settings settings() {
        return new DynamicTestSuiteStabilityServingInventoryAuthority.Settings(
                URI.create("https://inventory.example/v1/current"),
                Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofSeconds(30), false);
    }

    private ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding binding() {
        return new ConfiguredTestSuiteStabilityServingInventoryAuthority.ExpectedBinding(
                "stability-fleet", "release-2026-07-19", ARTIFACT,
                ToolStudioResourceGatewayProtocol.VERSION, "replica-a");
    }

    private List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            deploymentKeys() {
        return List.of(key("deployment-a", "deployment-key-a", deploymentA),
                key("deployment-b", "deployment-key-b", deploymentB));
    }

    private List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            witnessKeys() {
        return List.of(key("witness-a", "witness-key-a", witnessA),
                key("witness-b", "witness-key-b", witnessB));
    }

    private static ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey key(
            String authorityId, String keyId, KeyPair pair) {
        return new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                authorityId, keyId, pair.getPublic(), Instant.MIN, Instant.MAX,
                true, false);
    }

    private TestSuiteStabilityServingInventory inventory(long revision) {
        var material = new TestSuiteStabilityServingInventory.Material(
                TestSuiteStabilityServingInventory.Material.SCHEMA_VERSION,
                TRUST_DOMAIN, "inventory-17", revision,
                "stability-fleet", "release-2026-07-19", ARTIFACT,
                ToolStudioResourceGatewayProtocol.VERSION,
                List.of("replica-a", "replica-b"), POLICY,
                NOW.minusSeconds(120), NOW.minusSeconds(120), NOW.plusSeconds(3600));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestSuiteStabilityServingInventory(
                TestSuiteStabilityServingInventory.SCHEMA_VERSION,
                material, fingerprint, signatures(fingerprint,
                signer("deployment-a", "deployment-key-a", deploymentA),
                signer("deployment-b", "deployment-key-b", deploymentB)));
    }

    private TestSuiteStabilityServingInventoryPublication publication(
            long sequence,
            TestSuiteStabilityServingInventoryPublication.State state,
            String previousPublication,
            String previousWitness,
            TestSuiteStabilityServingInventory inventory) {
        var material = new TestSuiteStabilityServingInventoryPublication.Material(
                TestSuiteStabilityServingInventoryPublication.Material.SCHEMA_VERSION,
                TRUST_DOMAIN, "publication-" + sequence, sequence,
                inventory.materialFingerprint(), state, POLICY, previousPublication,
                NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(600),
                state == TestSuiteStabilityServingInventoryPublication.State.ACTIVE
                        ? "" : "DEPLOYMENT_WITHDRAWN");
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var witnessMaterial =
                new TestSuiteStabilityServingInventoryPublication.WitnessMaterial(
                        TestSuiteStabilityServingInventoryPublication.WitnessMaterial
                                .SCHEMA_VERSION,
                        WITNESS_DOMAIN, "checkpoint-" + sequence, sequence,
                        fingerprint, previousWitness,
                        NOW.minusSeconds(30), NOW.minusSeconds(30),
                        NOW.plusSeconds(600));
        String witnessFingerprint = ProtocolFingerprint.of(objectMapper, witnessMaterial);
        var witness = new TestSuiteStabilityServingInventoryPublication.WitnessCheckpoint(
                TestSuiteStabilityServingInventoryPublication.WitnessCheckpoint.SCHEMA_VERSION,
                witnessMaterial, witnessFingerprint,
                signatures(witnessFingerprint,
                        signer("witness-a", "witness-key-a", witnessA),
                        signer("witness-b", "witness-key-b", witnessB)));
        return new TestSuiteStabilityServingInventoryPublication(
                TestSuiteStabilityServingInventoryPublication.SCHEMA_VERSION,
                inventory, material, fingerprint,
                signatures(fingerprint,
                        signer("deployment-a", "deployment-key-a", deploymentA),
                        signer("deployment-b", "deployment-key-b", deploymentB)),
                witness);
    }

    private DynamicTestSuiteStabilityServingInventoryAuthority.FetchedDocument document(
            TestSuiteStabilityServingInventoryPublication publication,
            String etag) throws Exception {
        return DynamicTestSuiteStabilityServingInventoryAuthority.FetchedDocument.modified(
                objectMapper.writeValueAsBytes(publication), etag);
    }

    private static List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures(
            String fingerprint,
            Signer... signers) {
        return java.util.Arrays.stream(signers)
                .map(signer -> signer.sign(fingerprint))
                .sorted(Comparator.comparing(
                        TestSuiteStabilityServingInventory.AuthoritySignature::authorityId))
                .toList();
    }

    private static Signer signer(String authorityId, String keyId, KeyPair pair) {
        return new Signer(authorityId, keyId, pair);
    }

    private static QueueFetcher fetcher(Object... results) {
        return new QueueFetcher(List.of(results));
    }

    private record Signer(String authorityId, String keyId, KeyPair pair) {
        private TestSuiteStabilityServingInventory.AuthoritySignature sign(
                String fingerprint) {
            try {
                Signature signature = Signature.getInstance("Ed25519");
                signature.initSign(pair.getPrivate());
                signature.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new TestSuiteStabilityServingInventory.AuthoritySignature(
                        authorityId, keyId, "Ed25519", NOW.minusSeconds(20),
                        Base64.getEncoder().encodeToString(signature.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class QueueFetcher implements
            DynamicTestSuiteStabilityServingInventoryAuthority.DocumentFetcher {
        private final ArrayDeque<Object> results;
        private final List<String> seenEtags = new ArrayList<>();

        private QueueFetcher(List<Object> results) {
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public DynamicTestSuiteStabilityServingInventoryAuthority.FetchedDocument fetch(
                URI uri, String etag, Duration timeout) {
            seenEtags.add(etag);
            Object next = results.removeFirst();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (DynamicTestSuiteStabilityServingInventoryAuthority.FetchedDocument) next;
        }

        private List<String> seenEtags() {
            return List.copyOf(seenEtags);
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
