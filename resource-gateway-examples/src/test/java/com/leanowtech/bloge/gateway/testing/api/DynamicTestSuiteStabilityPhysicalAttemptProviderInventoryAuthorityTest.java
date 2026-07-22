package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.ExpectedBinding;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt.IsolationMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.Binding;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.Material;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.ProviderDeployment;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final String TRUST_DOMAIN = "provider.inventory.example";
    private static final String WITNESS_DOMAIN = "provider.inventory.witness.example";
    private static final String SCOPE = "physical-attempt-providers";
    private static final String COHORT = "release-2026-07-22";
    private static final String PROTOCOL = "bloge.physical-attempt-provider.v1";
    private static final String POLICY = fingerprint('b');

    private ObjectMapper objectMapper;
    private KeyPair deploymentA;
    private KeyPair deploymentB;
    private KeyPair witnessA;
    private KeyPair witnessB;
    private TestSuiteStabilityPhysicalAttemptObservationAuthority providerA;
    private TestSuiteStabilityPhysicalAttemptObservationAuthority providerB;
    private MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        deploymentA = generator.generateKeyPair();
        deploymentB = generator.generateKeyPair();
        witnessA = generator.generateKeyPair();
        witnessB = generator.generateKeyPair();
        providerA = mock(TestSuiteStabilityPhysicalAttemptObservationAuthority.class);
        providerB = mock(TestSuiteStabilityPhysicalAttemptObservationAuthority.class);
        when(providerA.descriptor()).thenReturn(bindingA().descriptor());
        when(providerB.descriptor()).thenReturn(bindingB().descriptor());
        clock = new MutableClock(NOW);
    }

    @Test
    void bootstrapsVerifiedPublicationAndExposesSignedCohortWithoutProviderIo()
            throws Exception {
        QueueFetcher fetcher = fetcher(document(publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                "", "", inventory(17)), "generation-1"));

        try (var authority = authority(fetcher)) {
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isTrue();
                assertThat(observed.status()).isEqualTo("VERIFIED");
                assertThat(observed.sourceType()).isEqualTo(
                        DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                                .SOURCE_TYPE);
                assertThat(observed.bindings()).containsExactly(bindingA(), bindingB());
            });
            assertThat(authority.cohortBinding()).satisfies(binding -> {
                assertThat(binding.expectedReplicaIds())
                        .containsExactly("replica-a", "replica-b");
                assertThat(binding.inventoryAvailable()).isTrue();
                assertThat(binding.sourceSequence()).isOne();
            });
            assertThat(authority.descriptor()).satisfies(descriptor -> {
                assertThat(descriptor.available()).isTrue();
                assertThat(descriptor.properties())
                        .containsEntry("dynamicInventory", true)
                        .containsEntry("automaticRefresh", false)
                        .containsEntry("signedRevocation", true)
                        .containsEntry("witnessedPublications", true)
                        .containsEntry("durablePublicationFloor", true)
                        .containsEntry("externalNonEquivocation", false)
                        .containsEntry("byzantineQuorumNonEquivocation", false)
                        .containsEntry("privateMaterialPresent", false)
                        .doesNotContainKeys("expectedReplicaIds", "publicationId", "etag",
                                "materialFingerprint", "uri", "keyId");
            });
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isTrue();
                assertThat(snapshot.refreshState()).isEqualTo("HEALTHY");
                assertThat(snapshot.publicationState()).isEqualTo("ACTIVE");
                assertThat(snapshot.refreshSuccessCount()).isOne();
            });
            verifyNoInteractions(providerA, providerB);
        }
    }

    @Test
    void conditionalRefreshUsesEtagWithoutChangingGeneration() throws Exception {
        var first = publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        QueueFetcher fetcher = fetcher(document(first, "generation-1"),
                DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                        .FetchedDocument.notModified("generation-1"));

        try (var authority = authority(fetcher)) {
            String generation = authority.observation().sourceGenerationFingerprint();
            clock.advance(Duration.ofSeconds(20));
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.observation().sourceGenerationFingerprint())
                    .isEqualTo(generation);
            assertThat(authority.snapshot().refreshSuccessCount()).isEqualTo(2);
            assertThat(fetcher.seenEtags()).containsExactly("", "generation-1");
        }
    }

    @Test
    void signedRevocationImmediatelyInvalidatesAlreadyResolvedWrapper() throws Exception {
        TestSuiteStabilityPhysicalAttemptProviderInventory inventory = inventory(17);
        var active = publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                "", "", inventory);
        var revoked = publication(2,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.REVOKED,
                active.materialFingerprint(), active.witness().materialFingerprint(), inventory);
        QueueFetcher fetcher = fetcher(document(active, "generation-1"),
                document(revoked, "generation-2"));

        try (var authority = authority(fetcher)) {
            var resolved = authority.resolve("provider-a", "deployment-1");
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.observation().status()).isEqualTo("REVOKED");
            assertThat(authority.cohortBinding().inventoryAvailable()).isFalse();
            assertThatThrownBy(resolved::descriptor)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("generation is unavailable");
            verifyNoInteractions(providerA, providerB);
        }
    }

    @Test
    void refreshFailureFailsClosedAndValidSuccessorRecoversAtomically()
            throws Exception {
        TestSuiteStabilityPhysicalAttemptProviderInventory inventory = inventory(17);
        var first = publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                "", "", inventory);
        var second = publication(2,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                first.materialFingerprint(), first.witness().materialFingerprint(), inventory);
        QueueFetcher fetcher = fetcher(document(first, "generation-1"),
                new IllegalStateException("network unavailable"),
                document(second, "generation-2"));

        try (var authority = authority(fetcher)) {
            var resolved = authority.resolve("provider-a", "deployment-1");
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().status()).isEqualTo("REFRESH_UNAVAILABLE");
            assertThatThrownBy(resolved::descriptor).isInstanceOf(IllegalStateException.class);
            assertThat(authority.snapshot().lastFailureCode())
                    .isEqualTo("REMOTE_REFRESH_FAILED");

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
    void staleSourceForkGapBrokenPredecessorAndInventoryRollbackFailClosed()
            throws Exception {
        assertInvalidSuccessor(publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.REVOKED,
                "", "", inventory(17)));

        TestSuiteStabilityPhysicalAttemptProviderInventory inventory = inventory(17);
        var first = publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                "", "", inventory);
        assertInvalidSuccessor(first, publication(3,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                first.materialFingerprint(), first.witness().materialFingerprint(), inventory));
        assertInvalidSuccessor(first, publication(2,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                fingerprint('f'), first.witness().materialFingerprint(), inventory));
        assertInvalidSuccessor(first, publication(2,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                first.materialFingerprint(), first.witness().materialFingerprint(),
                inventory(16)));
    }

    @Test
    void maximumSnapshotAgeAndCloseFenceLocalResolution() throws Exception {
        var first = publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        var authority = authority(fetcher(document(first, "generation-1")));
        var resolved = authority.resolve("provider-a", "deployment-1");

        clock.advance(Duration.ofSeconds(31));
        assertThat(authority.observation().status()).isEqualTo("SOURCE_EXPIRED");
        assertThatThrownBy(resolved::descriptor).isInstanceOf(IllegalStateException.class);
        authority.close();
        assertThat(authority.observation().status()).isEqualTo("CLOSED");
        assertThat(authority.refreshNow()).isFalse();
    }

    @Test
    void strictParserRejectsUnknownDuplicateAndTrailingContent() throws Exception {
        var first = publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        String json = objectMapper.writeValueAsString(first);
        for (String invalid : List.of(
                json.replaceFirst("\\{", "{\"credential\":\"forbidden\","),
                json.replaceFirst("\\{", "{\"schemaVersion\":\"duplicate\","),
                json + "{}")) {
            assertThatThrownBy(() -> authority(fetcher(
                    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                            .FetchedDocument.modified(
                                    invalid.getBytes(StandardCharsets.UTF_8), "invalid"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bootstrap");
        }
    }

    @Test
    void witnessMustBeCryptographicallyIndependent() throws Exception {
        var valid = publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        assertThatThrownBy(() -> new
                DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
                objectMapper, clock, expected(), 2, deploymentKeys(), catalog(), durableFloor(),
                WITNESS_DOMAIN, 1,
                List.of(key("other-witness-id", "witness-key", deploymentA)), settings(),
                fetcher(document(valid, "valid")), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("independent");
    }

    @Test
    void realHttpTransportNegotiatesProtocolAndRejectsMediaDowngrade() throws Exception {
        var first = publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
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
                    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                            .PROTOCOL_HEADER));
            conditional.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().set(
                    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                            .PROTOCOL_HEADER,
                    TestSuiteStabilityPhysicalAttemptProviderInventoryPublication
                            .SCHEMA_VERSION);
            exchange.getResponseHeaders().set("ETag", "\"generation-1\"");
            if (request == 2) {
                exchange.getResponseHeaders().set("Content-Type",
                        DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                                .MEDIA_TYPE);
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", request == 1
                        ? DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                                .MEDIA_TYPE
                        : "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            var remote = new
                    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Settings(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + "/inventory"), Duration.ofSeconds(10), Duration.ofSeconds(1),
                    Duration.ofSeconds(30), true);
            try (var authority = new
                    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
                    objectMapper, clock, expected(), 2, deploymentKeys(), catalog(),
                    durableFloor(), WITNESS_DOMAIN, 2, witnessKeys(), remote, null, false)) {
                assertThat(accept.get()).isEqualTo(
                        DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                                .MEDIA_TYPE);
                assertThat(protocol.get()).isEqualTo(
                        TestSuiteStabilityPhysicalAttemptProviderInventoryPublication
                                .SCHEMA_VERSION);
                assertThat(authority.refreshNow()).isTrue();
                assertThat(conditional.get()).isEqualTo("\"generation-1\"");
                assertThat(authority.refreshNow()).isFalse();
                assertThat(authority.observation().status())
                        .isEqualTo("REFRESH_UNAVAILABLE");
            }
        } finally {
            server.stop(0);
        }
    }

    private void assertInvalidSuccessor(
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication successor)
            throws Exception {
        var first = publication(1,
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                "", "", inventory(17));
        assertInvalidSuccessor(first, successor);
    }

    private void assertInvalidSuccessor(
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication first,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication successor)
            throws Exception {
        try (var authority = authority(fetcher(document(first, "generation-1"),
                document(successor, "invalid")))) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().status()).isEqualTo("REFRESH_UNAVAILABLE");
        }
    }

    private DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority authority(
            QueueFetcher fetcher) {
        return new DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
                objectMapper, clock, expected(), 2, deploymentKeys(), catalog(), durableFloor(),
                WITNESS_DOMAIN, 2, witnessKeys(), settings(), fetcher, false);
    }

    private ExpectedBinding expected() {
        return new ExpectedBinding(TRUST_DOMAIN, SCOPE, COHORT, PROTOCOL, Set.of(POLICY));
    }

    private DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Settings
            settings() {
        return new DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.Settings(
                URI.create("https://provider.inventory.example/v1/current"),
                Duration.ofSeconds(10), Duration.ofSeconds(1), Duration.ofSeconds(30), false);
    }

    private Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
            catalog() {
        return Map.of(bindingA().identity(), providerA, bindingB().identity(), providerB);
    }

    private List<AuthorityKey> deploymentKeys() {
        return List.of(key("deployment-a", "deployment-key-a", deploymentA),
                key("deployment-b", "deployment-key-b", deploymentB));
    }

    private List<AuthorityKey> witnessKeys() {
        return List.of(key("witness-a", "witness-key-a", witnessA),
                key("witness-b", "witness-key-b", witnessB));
    }

    private static AuthorityKey key(String authorityId, String keyId, KeyPair pair) {
        return new AuthorityKey(authorityId, keyId, pair.getPublic(),
                Instant.MIN, Instant.MAX, true, false);
    }

    private TestSuiteStabilityPhysicalAttemptProviderInventory inventory(long revision) {
        var material = new Material(Material.SCHEMA_VERSION, TRUST_DOMAIN,
                "provider-inventory-" + revision, revision, SCOPE, COHORT, PROTOCOL, POLICY,
                List.of(bindingA(), bindingB()), NOW.minusSeconds(120), NOW.minusSeconds(120),
                NOW.plusSeconds(3_600));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestSuiteStabilityPhysicalAttemptProviderInventory(
                TestSuiteStabilityPhysicalAttemptProviderInventory.SCHEMA_VERSION,
                material, fingerprint, signatures(fingerprint,
                signer("deployment-a", "deployment-key-a", deploymentA),
                signer("deployment-b", "deployment-key-b", deploymentB)));
    }

    private TestSuiteStabilityPhysicalAttemptProviderInventoryPublication publication(
            long sequence,
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State state,
            String previousPublication,
            String previousWitness,
            TestSuiteStabilityPhysicalAttemptProviderInventory inventory) {
        var material = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material
                        .SCHEMA_VERSION,
                TRUST_DOMAIN, "publication-" + sequence, sequence, SCOPE, COHORT,
                inventory.materialFingerprint(), List.of("replica-a", "replica-b"), state,
                POLICY, previousPublication, NOW.minusSeconds(60), NOW.minusSeconds(60),
                NOW.plusSeconds(600), state ==
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE
                        ? "" : "DEPLOYMENT_WITHDRAWN");
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var witnessMaterial = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessMaterial(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessMaterial
                        .SCHEMA_VERSION,
                WITNESS_DOMAIN, "checkpoint-" + sequence, sequence, fingerprint,
                previousWitness, NOW.minusSeconds(30), NOW.minusSeconds(30),
                NOW.plusSeconds(600));
        String witnessFingerprint = ProtocolFingerprint.of(objectMapper, witnessMaterial);
        var witness = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessCheckpoint(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessCheckpoint
                        .SCHEMA_VERSION,
                witnessMaterial, witnessFingerprint, signatures(witnessFingerprint,
                signer("witness-a", "witness-key-a", witnessA),
                signer("witness-b", "witness-key-b", witnessB)));
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryPublication(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.SCHEMA_VERSION,
                inventory, material, fingerprint, signatures(fingerprint,
                signer("deployment-a", "deployment-key-a", deploymentA),
                signer("deployment-b", "deployment-key-b", deploymentB)), witness);
    }

    private DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.FetchedDocument
            document(
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublication publication,
            String etag) throws Exception {
        return DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                .FetchedDocument.modified(objectMapper.writeValueAsBytes(publication), etag);
    }

    private static Binding bindingA() {
        return new Binding(Binding.SCHEMA_VERSION, "provider-a", "deployment-1",
                fingerprint('a'), "observation-key-a", List.of(IsolationMode.PROCESS),
                5_000, 86_400_000);
    }

    private static Binding bindingB() {
        return new Binding(Binding.SCHEMA_VERSION, "provider-b", "deployment-2",
                fingerprint('c'), "observation-key-b",
                List.of(IsolationMode.CONTAINER, IsolationMode.VM),
                10_000, 172_800_000);
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor
            durableFloor() {
        return new InMemoryPublicationFloor();
    }

    private static List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures(
            String fingerprint,
            Signer... signers) {
        return java.util.Arrays.stream(signers).map(signer -> signer.sign(fingerprint))
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

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Signer(String authorityId, String keyId, KeyPair pair) {
        private TestSuiteStabilityServingInventory.AuthoritySignature sign(String fingerprint) {
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
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.DocumentFetcher {
        private final ArrayDeque<Object> results;
        private final List<String> seenEtags = new ArrayList<>();

        private QueueFetcher(List<Object> results) {
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.FetchedDocument
                fetch(URI uri, String etag, Duration timeout) {
            seenEtags.add(etag);
            Object next = results.removeFirst();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                    .FetchedDocument) next;
        }

        private List<String> seenEtags() {
            return List.copyOf(seenEtags);
        }
    }

    private static final class InMemoryPublicationFloor implements
            TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor {
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
