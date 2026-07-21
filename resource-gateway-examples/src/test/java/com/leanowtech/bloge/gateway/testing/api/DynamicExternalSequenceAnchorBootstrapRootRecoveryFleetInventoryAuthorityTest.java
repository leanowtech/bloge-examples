package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.LaneResolver;
import com.leanowtech.bloge.gateway.testing.api.DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.FetchedDocument;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneDescriptor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.AuthoritySignature;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.Material;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.VerifiedBinding;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.State;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.WitnessCheckpoint;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.WitnessMaterial;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor.Generation;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTest.lane;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final String TRUST_DOMAIN = "fleet-inventory.example";
    private static final String WITNESS_DOMAIN = "fleet-inventory-witness.example";
    private static final String SCOPE = "recovery-prod";
    private static final String FLEET = "bootstrap-recovery";
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String POLICY = "sha256:" + "b".repeat(64);

    private ObjectMapper objectMapper;
    private MutableClock clock;
    private KeyPair deploymentA;
    private KeyPair deploymentB;
    private KeyPair witnessA;
    private KeyPair witnessB;
    private Lane laneA;
    private Lane laneB;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        clock = new MutableClock(NOW);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        deploymentA = generator.generateKeyPair();
        deploymentB = generator.generateKeyPair();
        witnessA = generator.generateKeyPair();
        witnessB = generator.generateKeyPair();
        laneA = lane("tenant", "roots-a", 'a');
        laneB = lane("tenant", "roots-b", 'b');
    }

    @Test
    void bootstrapsActivePublicationAndExposesAggregateDynamicTruth() throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
        RecordingFloor floor = new RecordingFloor();

        try (var authority = authority(fetcher(document(first, 1)), floor,
                catalog(laneA), false)) {
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.generation()).isEqualTo(17L);
                assertThat(snapshot.lanes()).containsExactly(laneA);
            });
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isTrue();
                assertThat(observed.status()).isEqualTo("VERIFIED");
                assertThat(observed.sourceType()).isEqualTo(
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                                .SOURCE_TYPE);
                assertThat(observed.validSignatureCount()).isEqualTo(2);
            });
            assertThat(authority.descriptor().properties())
                    .containsEntry("automaticRefresh", false)
                    .containsEntry("signedRevocation", true)
                    .containsEntry("durableGenerationFloor", true)
                    .containsEntry("witnessedPublications", true)
                    .containsEntry("conditionalRequests", true)
                    .containsEntry("publicationSequence", 1L)
                    .containsEntry("privateMaterialPresent", false)
                    .doesNotContainKeys("publicationId", "checkpointId", "etag", "uri",
                            "materialFingerprint", "policyFingerprint", "publicKey",
                            "privateKey", "laneKeys");
            assertThat(authority.refreshSnapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isTrue();
                assertThat(snapshot.refreshState()).isEqualTo("HEALTHY");
                assertThat(snapshot.publicationState()).isEqualTo("ACTIVE");
                assertThat(snapshot.publicationSequence()).isOne();
                assertThat(snapshot.refreshSuccessCount()).isOne();
                assertThat(snapshot.durablePublicationFloor()).isTrue();
            });
            assertThat(floor.generations()).singleElement().satisfies(generation -> {
                assertThat(generation.inventoryGeneration()).isEqualTo(17L);
                assertThat(generation.state()).isEqualTo(State.ACTIVE);
            });
            assertThat(new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth(
                    authority).health()).satisfies(health -> {
                assertThat(health.getStatus()).isEqualTo(Status.UP);
                assertThat(health.getDetails())
                        .containsEntry("automaticRefresh", false)
                        .containsEntry("signedRevocation", true)
                        .containsEntry("durableGenerationFloor", true)
                        .containsEntry("publicationState", "ACTIVE")
                        .doesNotContainKeys("etag", "uri", "materialFingerprint",
                                "policyFingerprint", "privateKey", "laneKeys");
            });
        }
    }

    @Test
    void conditional304ReverifiesCachedDocumentAndRenewsOnlySourceFreshness()
            throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
        QueueFetcher fetcher = fetcher(document(first, 1),
                FetchedDocument.notModified(etag(1)));

        try (var authority = authority(fetcher, new RecordingFloor(),
                catalog(laneA), false)) {
            clock.advance(Duration.ofSeconds(20));
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.observation().available()).isTrue();
            assertThat(authority.refreshSnapshot().refreshSuccessCount()).isEqualTo(2L);
            assertThat(fetcher.seenEtags()).containsExactly("", etag(1));

            clock.advance(Duration.ofSeconds(31));
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status()).isEqualTo("SOURCE_EXPIRED");
            });
        }
    }

    @Test
    void conditionalValidatorsRejectChanged304EtagAndChangedContentUnderSameEtag()
            throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
        var second = publication(2, State.ACTIVE, inventory(18, laneA), first);
        QueueFetcher changed304 = fetcher(document(first, 1),
                FetchedDocument.notModified(etag(2)));
        try (var authority = authority(changed304, new RecordingFloor(),
                catalog(laneA), false)) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.refreshSnapshot().lastFailureCode())
                    .isEqualTo("REMOTE_DOCUMENT_INVALID");
        }

        QueueFetcher reusedEtag = fetcher(document(first, 1),
                FetchedDocument.modified(objectMapper.writeValueAsBytes(second), etag(1)));
        try (var authority = authority(reusedEtag, new RecordingFloor(),
                catalog(laneA), false)) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().status()).isEqualTo("REFRESH_UNAVAILABLE");
        }
    }

    @Test
    void signedRevocationAdvancesFloorWithoutResolvingRemovedRuntimeLanes()
            throws Exception {
        var inventory = inventory(17, laneA);
        var active = publication(1, State.ACTIVE, inventory, null);
        var revoked = publication(2, State.REVOKED, inventory, active);
        QueueFetcher fetcher = fetcher(document(active, 1), document(revoked, 2));
        AtomicInteger resolutions = new AtomicInteger();
        LaneResolver resolver = key -> {
            resolutions.incrementAndGet();
            return laneA.key().equals(key) ? laneA : null;
        };
        RecordingFloor floor = new RecordingFloor();

        try (var authority = authority(fetcher, floor, resolver, false)) {
            assertThat(resolutions).hasValue(1);
            assertThat(authority.refreshNow()).isTrue();
            assertThat(resolutions).hasValue(1);
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status()).isEqualTo("REVOKED");
            });
            assertThatThrownBy(authority::snapshot)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("REVOKED");
            assertThat(floor.generations()).extracting(Generation::state)
                    .containsExactly(State.ACTIVE, State.REVOKED);
            assertThat(authority.refreshSnapshot().lastFailureCode()).isEmpty();
        }
    }

    @Test
    void activeSuccessorAtomicallyPublishesNewInventoryAndExactRuntimeBinding()
            throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
        var second = publication(2, State.ACTIVE, inventory(18, laneA, laneB), first);
        QueueFetcher fetcher = fetcher(document(first, 1), document(second, 2));
        RecordingFloor floor = new RecordingFloor();

        try (var authority = authority(fetcher, floor, catalog(laneA, laneB), false)) {
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.generation()).isEqualTo(18L);
                assertThat(snapshot.lanes()).containsExactly(laneA, laneB);
            });
            assertThat(authority.descriptor().properties())
                    .containsEntry("publicationSequence", 2L)
                    .containsEntry("publicationState", "ACTIVE");
            assertThat(floor.generations()).hasSize(2);
        }
    }

    @Test
    void refreshFailureImmediatelyClosesAdmissionButRetainsBoundedTelemetry()
            throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
        var second = publication(2, State.ACTIVE, inventory(18, laneA), first);
        QueueFetcher fetcher = fetcher(document(first, 1),
                new IllegalStateException("tenant secret endpoint detail"),
                document(second, 2));

        try (var authority = authority(fetcher, new RecordingFloor(),
                catalog(laneA), false)) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().status()).isEqualTo("REFRESH_UNAVAILABLE");
            assertThatThrownBy(authority::snapshot).isInstanceOf(IllegalStateException.class);
            assertThat(authority.refreshSnapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isFalse();
                assertThat(snapshot.refreshFailureCount()).isOne();
                assertThat(snapshot.lastFailureCode()).isEqualTo("REMOTE_REFRESH_FAILED");
                assertThat(snapshot.toString()).doesNotContain(
                        "tenant secret endpoint detail", "inventory.example");
            });

            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.observation()).satisfies(observed -> {
                assertThat(observed.available()).isTrue();
                assertThat(observed.generation()).isEqualTo(18L);
            });
            assertThat(authority.refreshSnapshot()).satisfies(snapshot -> {
                assertThat(snapshot.refreshSuccessCount()).isEqualTo(2L);
                assertThat(snapshot.refreshFailureCount()).isOne();
                assertThat(snapshot.lastFailureCode()).isEmpty();
            });
        }
    }

    @Test
    void chainRollbackGapInventoryForkAndFloorFailureAllFailClosed() throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
        var gap = publication(3, State.ACTIVE, inventory(18, laneA), first,
                first.materialFingerprint(), first.witness().materialFingerprint());
        QueueFetcher fetcher = fetcher(document(first, 1), document(gap, 3));
        RecordingFloor floor = new RecordingFloor();

        try (var authority = authority(fetcher, floor, catalog(laneA), false)) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().status()).isEqualTo("REFRESH_UNAVAILABLE");
            assertThat(floor.generations()).hasSize(1);
        }

        var sameGenerationDrift = publication(2, State.ACTIVE,
                inventory(17, laneA, laneB), first);
        QueueFetcher driftFetcher = fetcher(document(first, 1),
                document(sameGenerationDrift, 2));
        try (var authority = authority(driftFetcher, new RecordingFloor(),
                catalog(laneA, laneB), false)) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().status()).isEqualTo("REFRESH_UNAVAILABLE");
        }

        RecordingFloor failingFloor = new RecordingFloor();
        QueueFetcher floorFetcher = fetcher(document(first, 1),
                document(publication(2, State.ACTIVE, inventory(18, laneA), first), 2));
        try (var authority = authority(floorFetcher, failingFloor, catalog(laneA), false)) {
            failingFloor.failNext();
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().status()).isEqualTo("REFRESH_UNAVAILABLE");
        }
    }

    @Test
    void activeRuntimeDriftFailsBeforeDurableFloorAdvance() throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
        var second = publication(2, State.ACTIVE, inventory(18, laneA, laneB), first);
        QueueFetcher fetcher = fetcher(document(first, 1), document(second, 2));
        RecordingFloor floor = new RecordingFloor();

        try (var authority = authority(fetcher, floor, catalog(laneA), false)) {
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.observation().status()).isEqualTo("REFRESH_UNAVAILABLE");
            assertThat(floor.generations()).hasSize(1);
        }
    }

    @Test
    void badDeploymentOrWitnessSignatureAndNonIndependentTrustFailBootstrap()
            throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
        var badPublication = new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
                first.schemaVersion(), first.inventory(), first.material(),
                first.materialFingerprint(),
                signatures(first.materialFingerprint(),
                        signer("deployment-a", "deployment-key-a", witnessA),
                        signer("deployment-b", "deployment-key-b", deploymentB)),
                first.witness());

        assertThatThrownBy(() -> authority(fetcher(document(badPublication, 1)),
                new RecordingFloor(), catalog(laneA), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap is unavailable");

        var earlyWitnessMaterial = new WitnessMaterial(WitnessMaterial.SCHEMA_VERSION,
                WITNESS_DOMAIN, "checkpoint-early", SCOPE, FLEET, 1,
                first.materialFingerprint(), "", NOW.minusSeconds(600),
                NOW.minusSeconds(600), NOW.plusSeconds(600));
        String earlyWitnessFingerprint = ProtocolFingerprint.of(
                objectMapper, earlyWitnessMaterial);
        var earlyWitness = new WitnessCheckpoint(WitnessCheckpoint.SCHEMA_VERSION,
                earlyWitnessMaterial, earlyWitnessFingerprint,
                signatures(earlyWitnessFingerprint,
                        signer("witness-a", "witness-key-a", witnessA),
                        signer("witness-b", "witness-key-b", witnessB)));
        var earlyWitnessPublication = new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
                first.schemaVersion(), first.inventory(), first.material(),
                first.materialFingerprint(), first.signatures(), earlyWitness);
        assertThatThrownBy(() -> authority(
                fetcher(document(earlyWitnessPublication, 1)), new RecordingFloor(),
                catalog(laneA), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap is unavailable");

        List<AuthorityKey> overlappingWitnessKeys = List.of(
                key("deployment-a", "witness-key-a", witnessA),
                key("witness-b", "witness-key-b", witnessB));
        assertThatThrownBy(() -> new
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                objectMapper, clock, TRUST_DOMAIN, Set.of(POLICY), 2, deploymentKeys(),
                binding(), catalog(laneA), new RecordingFloor(), WITNESS_DOMAIN, 2,
                overlappingWitnessKeys, settings(), fetcher(document(first, 1)), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("independent");

        List<AuthorityKey> reusedPublicKey = List.of(
                key("witness-a", "witness-key-a", deploymentA),
                key("witness-b", "witness-key-b", witnessB));
        assertThatThrownBy(() -> new
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                objectMapper, clock, TRUST_DOMAIN, Set.of(POLICY), 2, deploymentKeys(),
                binding(), catalog(laneA), new RecordingFloor(), WITNESS_DOMAIN, 2,
                reusedPublicKey, settings(), fetcher(document(first, 1)), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("independent");
    }

    @Test
    void strictParserRejectsUnknownDuplicateAndTrailingDocuments() throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
        String json = objectMapper.writeValueAsString(first);
        String unknown = json.replaceFirst("\\{", "{\"privateKey\":\"secret\",");
        String duplicate = json.replaceFirst("\\{", "{\"schemaVersion\":\"duplicate\",");

        assertInvalidDocument(unknown);
        assertInvalidDocument(duplicate);
        assertInvalidDocument(json + " {}");
    }

    @Test
    void settingsAndFetchedDocumentsRejectUnsafeUrisWeakEtagsAndOversizeBodies() {
        assertThatThrownBy(() -> new
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Settings(
                URI.create("http://inventory.example/current"), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(30), false).validated())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> new
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Settings(
                URI.create("https://user:pass@inventory.example/current"),
                Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofSeconds(30), false).validated())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FetchedDocument.modified(new byte[]{1}, "W/\"weak\""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FetchedDocument.modified(new byte[512 * 1024 + 1], etag(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FetchedDocument.notModified(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FetchedDocument(new byte[]{1}, etag(1), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closeIsIdempotentAndImmediatelyClosesObservationAndRefresh() throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
        var authority = authority(fetcher(document(first, 1)), new RecordingFloor(),
                catalog(laneA), true);

        authority.close();
        authority.close();

        assertThat(authority.observation().status()).isEqualTo("CLOSED");
        assertThat(authority.refreshNow()).isFalse();
        assertThat(authority.descriptor().properties())
                .containsEntry("automaticRefresh", false)
                .containsEntry("refreshState", "CLOSED");
    }

    @Test
    void realHttpTransportNegotiatesProtocolUsesStrongEtagAndRejectsDowngrade()
            throws Exception {
        var first = publication(1, State.ACTIVE, inventory(17, laneA), null);
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
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                            .PROTOCOL_HEADER));
            conditional.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            exchange.getResponseHeaders().set(
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                            .PROTOCOL_HEADER,
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                            .SCHEMA_VERSION);
            exchange.getResponseHeaders().set("ETag", etag(1));
            if (request == 2) {
                exchange.getResponseHeaders().set("Content-Type",
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                                .MEDIA_TYPE);
                exchange.sendResponseHeaders(304, -1);
            } else {
                exchange.getResponseHeaders().set("Content-Type", request == 1
                        ? DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .MEDIA_TYPE : "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            var settings = new
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                    .Settings(URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/inventory"),
                    Duration.ofSeconds(10), Duration.ofSeconds(1),
                    Duration.ofSeconds(30), true);
            try (var authority = new
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                    objectMapper, clock, TRUST_DOMAIN, Set.of(POLICY), 2,
                    deploymentKeys(), binding(), catalog(laneA), new RecordingFloor(),
                    WITNESS_DOMAIN, 2, witnessKeys(), settings, null, false)) {
                assertThat(accept.get()).isEqualTo(
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                                .MEDIA_TYPE);
                assertThat(protocol.get()).isEqualTo(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                                .SCHEMA_VERSION);
                assertThat(authority.refreshNow()).isTrue();
                assertThat(conditional.get()).isEqualTo(etag(1));
                assertThat(authority.refreshNow()).isFalse();
                assertThat(authority.observation().status())
                        .isEqualTo("REFRESH_UNAVAILABLE");
            }
        } finally {
            server.stop(0);
        }
    }

    private DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority authority(
            QueueFetcher fetcher,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor floor,
            LaneResolver resolver,
            boolean startScheduler) {
        return new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                objectMapper, clock, TRUST_DOMAIN, Set.of(POLICY), 2, deploymentKeys(),
                binding(), resolver, floor, WITNESS_DOMAIN, 2, witnessKeys(), settings(),
                fetcher, startScheduler);
    }

    private void assertInvalidDocument(String json) {
        assertThatThrownBy(() -> authority(fetcher(FetchedDocument.modified(
                        json.getBytes(StandardCharsets.UTF_8), etag(1))),
                new RecordingFloor(), catalog(laneA), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap is unavailable");
    }

    private DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Settings
            settings() {
        return new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .Settings(URI.create("https://inventory.example/v1/current"),
                Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofSeconds(30), false);
    }

    private static VerifiedBinding binding() {
        return new VerifiedBinding(SCOPE, FLEET, ARTIFACT, 4);
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

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation inventory(
            long generation,
            Lane... lanes) {
        List<LaneDescriptor> descriptors = java.util.Arrays.stream(lanes)
                .map(Lane::descriptor).sorted(Comparator.comparing(LaneDescriptor::key)).toList();
        var material = new Material(Material.SCHEMA_VERSION, TRUST_DOMAIN,
                "inventory-" + generation, generation, SCOPE, FLEET, ARTIFACT, 4,
                descriptors, POLICY, NOW.minusSeconds(120), NOW.minusSeconds(120),
                NOW.plusSeconds(7_200));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .SCHEMA_VERSION,
                material, fingerprint, signatures(fingerprint,
                signer("deployment-a", "deployment-key-a", deploymentA),
                signer("deployment-b", "deployment-key-b", deploymentB)));
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication publication(
            long sequence,
            State state,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation inventory,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication predecessor) {
        return publication(sequence, state, inventory, predecessor,
                predecessor == null ? "" : predecessor.materialFingerprint(),
                predecessor == null ? "" : predecessor.witness().materialFingerprint());
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication publication(
            long sequence,
            State state,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation inventory,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication predecessor,
            String previousPublication,
            String previousWitness) {
        var material = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                .Material(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.Material
                        .SCHEMA_VERSION,
                TRUST_DOMAIN, "publication-" + sequence, SCOPE, FLEET, sequence,
                inventory.materialFingerprint(), state, POLICY, previousPublication,
                NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(600),
                state == State.ACTIVE ? "" : "DEPLOYMENT_WITHDRAWN");
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var witnessMaterial = new WitnessMaterial(WitnessMaterial.SCHEMA_VERSION,
                WITNESS_DOMAIN, "checkpoint-" + sequence, SCOPE, FLEET, sequence,
                fingerprint, previousWitness, NOW.minusSeconds(30), NOW.minusSeconds(30),
                NOW.plusSeconds(600));
        String witnessFingerprint = ProtocolFingerprint.of(objectMapper, witnessMaterial);
        var witness = new WitnessCheckpoint(WitnessCheckpoint.SCHEMA_VERSION,
                witnessMaterial, witnessFingerprint, signatures(witnessFingerprint,
                signer("witness-a", "witness-key-a", witnessA),
                signer("witness-b", "witness-key-b", witnessB)));
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                inventory, material, fingerprint, signatures(fingerprint,
                signer("deployment-a", "deployment-key-a", deploymentA),
                signer("deployment-b", "deployment-key-b", deploymentB)), witness);
    }

    private FetchedDocument document(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication publication,
            int generation) throws Exception {
        return FetchedDocument.modified(objectMapper.writeValueAsBytes(publication),
                etag(generation));
    }

    private static List<AuthoritySignature> signatures(
            String fingerprint,
            Signer... signers) {
        return java.util.Arrays.stream(signers).map(signer -> signer.sign(fingerprint))
                .sorted(Comparator.comparing(AuthoritySignature::authorityId)
                        .thenComparing(AuthoritySignature::keyId)).toList();
    }

    private static Signer signer(String authorityId, String keyId, KeyPair pair) {
        return new Signer(authorityId, keyId, pair);
    }

    private static LaneResolver catalog(Lane... lanes) {
        Map<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneKey, Lane> indexed =
                java.util.Arrays.stream(lanes).collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Lane::key, value -> value));
        return indexed::get;
    }

    private static QueueFetcher fetcher(Object... results) {
        return new QueueFetcher(List.of(results));
    }

    private static String etag(int generation) {
        return "\"generation-" + generation + "\"";
    }

    private record Signer(String authorityId, String keyId, KeyPair pair) {

        private AuthoritySignature sign(String fingerprint) {
            try {
                Signature signature = Signature.getInstance("Ed25519");
                signature.initSign(pair.getPrivate());
                signature.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new AuthoritySignature(authorityId, keyId, "Ed25519",
                        NOW.minusSeconds(20),
                        Base64.getEncoder().encodeToString(signature.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class QueueFetcher implements
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                    .DocumentFetcher {
        private final ArrayDeque<Object> results;
        private final List<String> seenEtags = new ArrayList<>();

        private QueueFetcher(List<Object> results) {
            this.results = new ArrayDeque<>(results);
        }

        @Override
        public FetchedDocument fetch(URI uri, String etag, Duration timeout) {
            seenEtags.add(etag);
            Object next = results.removeFirst();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (FetchedDocument) next;
        }

        private List<String> seenEtags() {
            return List.copyOf(seenEtags);
        }
    }

    private static final class RecordingFloor implements
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor {
        private final List<Generation> generations = new ArrayList<>();
        private boolean failNext;

        @Override
        public synchronized void accept(Generation generation) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("durable floor unavailable");
            }
            if (!generations.isEmpty()) {
                Generation current = generations.getLast();
                if (generation.sequence() == current.sequence()) {
                    if (!generation.equals(current)) {
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
                if (generation.inventoryGeneration() < current.inventoryGeneration()
                        || generation.inventoryGeneration() == current.inventoryGeneration()
                        && !generation.inventoryMaterialFingerprint().equals(
                        current.inventoryMaterialFingerprint())) {
                    throw new IllegalArgumentException("floor inventory rollback");
                }
                if (current.state() == State.REVOKED && generation.state() == State.ACTIVE
                        && generation.inventoryGeneration() <= current.inventoryGeneration()) {
                    throw new IllegalArgumentException("floor reactivation");
                }
            } else if (generation.sequence() != 1) {
                throw new IllegalArgumentException("floor must begin at one");
            }
            generations.add(generation);
        }

        @Override
        public boolean durable() {
            return true;
        }

        private synchronized List<Generation> generations() {
            return List.copyOf(generations);
        }

        private synchronized void failNext() {
            failNext = true;
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
