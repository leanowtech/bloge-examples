package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Lane;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.LaneDescriptor;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.Material;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.State;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.WitnessCheckpoint;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.WitnessMaterial;
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

class DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final String POLICY = "sha256:" + "a".repeat(64);
    private static final String DEPLOYMENT_ROOT_DOMAIN = "recovery-deployment-root.example";
    private static final String WITNESS_ROOT_DOMAIN = "recovery-witness-root.example";
    private static final String DEPLOYMENT_DOMAIN = "recovery-deployment.example";
    private static final String WITNESS_DOMAIN = "recovery-witness.example";
    private static final String ARTIFACT = "sha256:" + "b".repeat(64);

    private ObjectMapper objectMapper;
    private MutableClock clock;
    private KeyPair deploymentRootA;
    private KeyPair deploymentRootB;
    private KeyPair witnessRootA;
    private KeyPair witnessRootB;
    private KeyPair deploymentLeafA;
    private KeyPair deploymentLeafB;
    private KeyPair witnessLeafA;
    private KeyPair witnessLeafB;
    private Lane laneA;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        clock = new MutableClock(NOW);
        deploymentRootA = keyPair();
        deploymentRootB = keyPair();
        witnessRootA = keyPair();
        witnessRootB = keyPair();
        deploymentLeafA = keyPair();
        deploymentLeafB = keyPair();
        witnessLeafA = keyPair();
        witnessLeafB = keyPair();
        laneA = lane("tenant", "recovery-roots", 'a');
    }

    @Test
    void managedInventoryRotatesBothRuntimeVerifierDomainsWithoutRestart() throws Exception {
        var firstRoot = publication(1, "", "deployment-leaf-a", "deployment-key-a",
                deploymentLeafA, "witness-leaf-a", "witness-key-a", witnessLeafA, false);
        var secondRoot = publication(2, firstRoot.materialFingerprint(), "deployment-leaf-b",
                "deployment-key-b", deploymentLeafB, "witness-leaf-b", "witness-key-b",
                witnessLeafB, false);
        var revokedRoot = publication(3, secondRoot.materialFingerprint(), "deployment-leaf-b",
                "deployment-key-b", deploymentLeafB, "witness-leaf-b", "witness-key-b",
                witnessLeafB, true);
        MutableFetcher rootFetcher = new MutableFetcher();
        rootFetcher.publish(firstRoot, "\"root-generation-1\"", objectMapper);

        var firstInventory = inventoryPublication(1, null,
                inventory(17, "deployment-leaf-a", "deployment-key-a", deploymentLeafA),
                "deployment-leaf-a", "deployment-key-a", deploymentLeafA,
                "witness-leaf-a", "witness-key-a", witnessLeafA);
        var secondInventory = inventoryPublication(2, firstInventory,
                inventory(18, "deployment-leaf-b", "deployment-key-b", deploymentLeafB),
                "deployment-leaf-b", "deployment-key-b", deploymentLeafB,
                "witness-leaf-b", "witness-key-b", witnessLeafB);
        InventoryFetcher inventoryFetcher = new InventoryFetcher(
                inventoryDocument(firstInventory, "\"inventory-generation-1\""),
                inventoryDocument(secondInventory, "\"inventory-generation-2\""));

        try (var roots = authority(rootFetcher, new InMemoryFloor());
             var inventory = new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                     objectMapper, clock, Set.of(POLICY), inventoryBinding(),
                     key -> key.equals(laneA.key()) ? laneA : null,
                     new DurableInventoryFloor(), roots, inventorySettings(),
                     inventoryFetcher, false)) {
            assertThat(inventory.observation()).satisfies(observed -> {
                assertThat(observed.available()).isTrue();
                assertThat(observed.generation()).isEqualTo(17);
            });
            assertThat(inventory.descriptor().properties())
                    .containsEntry("managedTrustRootRefresh", true)
                    .containsEntry("atomicDualTrustRootPublication", true);

            rootFetcher.publish(secondRoot, "\"root-generation-2\"", objectMapper);
            assertThat(roots.refreshNow()).isTrue();
            assertThat(inventory.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status())
                        .isEqualTo("TRUST_ROOT_GENERATION_UNVERIFIED");
            });
            assertThat(inventory.refreshNow()).isTrue();

            assertThat(rootFetcher.calls()).isEqualTo(2);
            assertThat(roots.snapshot().sequence()).isEqualTo(2);
            assertThat(inventory.observation()).satisfies(observed -> {
                assertThat(observed.available()).isTrue();
                assertThat(observed.generation()).isEqualTo(18);
                assertThat(observed.requiredSignatureCount()).isOne();
            });

            rootFetcher.publish(revokedRoot, "\"root-generation-3\"", objectMapper);
            assertThat(roots.refreshNow()).isTrue();
            assertThat(inventory.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status())
                        .isEqualTo("TRUST_ROOT_WITNESS_THRESHOLD_UNAVAILABLE");
            });
        }
    }

    @Test
    void inventory304IsReverifiedAndRejectedAfterDisjointTrustRootRotation()
            throws Exception {
        var firstRoot = publication(1, "", "deployment-leaf-a", "deployment-key-a",
                deploymentLeafA, "witness-leaf-a", "witness-key-a", witnessLeafA, false);
        var secondRoot = publication(2, firstRoot.materialFingerprint(),
                "deployment-leaf-b", "deployment-key-b", deploymentLeafB,
                "witness-leaf-b", "witness-key-b", witnessLeafB, false);
        MutableFetcher rootFetcher = new MutableFetcher();
        rootFetcher.publish(firstRoot, "\"root-generation-1\"", objectMapper);

        var firstInventory = inventoryPublication(1, null,
                inventory(17, "deployment-leaf-a", "deployment-key-a", deploymentLeafA),
                "deployment-leaf-a", "deployment-key-a", deploymentLeafA,
                "witness-leaf-a", "witness-key-a", witnessLeafA);
        String inventoryEtag = "\"inventory-generation-1\"";
        InventoryFetcher inventoryFetcher = new InventoryFetcher(
                inventoryDocument(firstInventory, inventoryEtag),
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .FetchedDocument.notModified(inventoryEtag));

        try (var roots = authority(rootFetcher, new InMemoryFloor());
             var inventory = new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority(
                     objectMapper, clock, Set.of(POLICY), inventoryBinding(),
                     key -> key.equals(laneA.key()) ? laneA : null,
                     new DurableInventoryFloor(), roots, inventorySettings(),
                     inventoryFetcher, false)) {
            rootFetcher.publish(secondRoot, "\"root-generation-2\"", objectMapper);
            assertThat(roots.refreshNow()).isTrue();
            assertThat(inventory.observation().status())
                    .isEqualTo("TRUST_ROOT_GENERATION_UNVERIFIED");

            assertThat(inventory.refreshNow()).isFalse();
            assertThat(inventory.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status()).isEqualTo("REFRESH_UNAVAILABLE");
            });
            assertThat(inventory.refreshSnapshot().lastFailureCode())
                    .isEqualTo("REMOTE_DOCUMENT_INVALID");
        }
    }

    @Test
    void unknownRuntimeKeyTriggersOneAtomicDualKeySetRotation() throws Exception {
        var first = publication(1, "", "deployment-leaf-a", "deployment-key-a",
                deploymentLeafA, "witness-leaf-a", "witness-key-a", witnessLeafA, false);
        var second = publication(2, first.materialFingerprint(), "deployment-leaf-b",
                "deployment-key-b", deploymentLeafB, "witness-leaf-b", "witness-key-b",
                witnessLeafB, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "\"root-generation-1\"", objectMapper);
        InMemoryFloor floor = new InMemoryFloor();
        try (var authority = authority(fetcher, floor)) {
            fetcher.publish(second, "\"root-generation-2\"", objectMapper);

            var keys = authority.keysFor(
                    List.of(reference("deployment-leaf-b", "deployment-key-b")),
                    List.of(reference("witness-leaf-b", "witness-key-b")));

            assertThat(fetcher.calls()).isEqualTo(2);
            assertThat(keys.deploymentKeys())
                    .containsKey("deployment-leaf-b\u0000deployment-key-b");
            assertThat(keys.witnessKeys()).containsKey("witness-leaf-b\u0000witness-key-b");
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isTrue();
                assertThat(snapshot.status()).isEqualTo("HEALTHY");
                assertThat(snapshot.sequence()).isEqualTo(2);
            });
            assertThat(floor.current.sequence()).isEqualTo(2);
        }
    }

    @Test
    void sourceFailureClosesKeyAccessAndValidSuccessorRecovers() throws Exception {
        var first = publication(1, "", "deployment-leaf-a", "deployment-key-a",
                deploymentLeafA, "witness-leaf-a", "witness-key-a", witnessLeafA, false);
        var second = publication(2, first.materialFingerprint(), "deployment-leaf-b",
                "deployment-key-b", deploymentLeafB, "witness-leaf-b", "witness-key-b",
                witnessLeafB, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "\"root-generation-1\"", objectMapper);
        try (var authority = authority(fetcher, new InMemoryFloor())) {
            fetcher.fail();
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isFalse();
                assertThat(snapshot.status()).isEqualTo("REFRESH_UNAVAILABLE");
                assertThat(snapshot.lastFailureCode())
                        .isEqualTo("TRUST_ROOT_SOURCE_UNAVAILABLE");
            });
            assertThatThrownBy(() -> authority.keysFor(List.of(), List.of()))
                    .isInstanceOf(IllegalStateException.class);

            fetcher.publish(second, "\"root-generation-2\"", objectMapper);
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.snapshot().available()).isTrue();
        }
    }

    @Test
    void conditionalRefreshRenewsSourceAgeButSnapshotReadNeverPerformsIo() throws Exception {
        var first = publication(1, "", "deployment-leaf-a", "deployment-key-a",
                deploymentLeafA, "witness-leaf-a", "witness-key-a", witnessLeafA, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "\"root-generation-1\"", objectMapper);
        try (var authority = authority(fetcher, new InMemoryFloor())) {
            clock.advance(Duration.ofSeconds(50));
            assertThat(authority.refreshNow()).isTrue();
            assertThat(fetcher.lastConditionalEtag()).isEqualTo("\"root-generation-1\"");

            int calls = fetcher.calls();
            clock.set(NOW.plusSeconds(49));
            assertThat(authority.snapshot().status()).isEqualTo("SOURCE_EXPIRED");
            assertThat(fetcher.calls()).isEqualTo(calls);

            clock.set(NOW.plusSeconds(50));
            clock.advance(Duration.ofSeconds(119));
            assertThat(authority.snapshot().available()).isTrue();

            clock.advance(Duration.ofSeconds(1));
            assertThat(authority.snapshot().status()).isEqualTo("SOURCE_EXPIRED");
            assertThat(fetcher.calls()).isEqualTo(calls);
        }
    }

    @Test
    void signedThresholdRevocationAdvancesFloorButClosesRuntimeKeys() throws Exception {
        var first = publication(1, "", "deployment-leaf-a", "deployment-key-a",
                deploymentLeafA, "witness-leaf-a", "witness-key-a", witnessLeafA, false);
        var revoked = publication(2, first.materialFingerprint(), "deployment-leaf-b",
                "deployment-key-b", deploymentLeafB, "witness-leaf-b", "witness-key-b",
                witnessLeafB, true);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "\"root-generation-1\"", objectMapper);
        InMemoryFloor floor = new InMemoryFloor();
        try (var authority = authority(fetcher, floor)) {
            fetcher.publish(revoked, "\"root-generation-2\"", objectMapper);
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isFalse();
                assertThat(snapshot.status()).isEqualTo("WITNESS_THRESHOLD_UNAVAILABLE");
                assertThat(snapshot.refreshFailureCount()).isZero();
                assertThat(snapshot.sequence()).isEqualTo(2);
            });
            assertThat(floor.current.sequence()).isEqualTo(2);
        }
    }

    @Test
    void forkGapBrokenPredecessorAndEtagReuseNeverReplaceCurrentGeneration()
            throws Exception {
        var first = publication(1, "", "deployment-leaf-a", "deployment-key-a",
                deploymentLeafA, "witness-leaf-a", "witness-key-a", witnessLeafA, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "\"root-generation-1\"", objectMapper);
        try (var authority = authority(fetcher, new InMemoryFloor())) {
            var fork = publication(1, "", "deployment-leaf-b", "deployment-key-b",
                    deploymentLeafB, "witness-leaf-b", "witness-key-b", witnessLeafB, false);
            fetcher.publish(fork, "\"fork\"", objectMapper);
            assertThat(authority.refreshNow()).isFalse();

            var gap = publication(3, first.materialFingerprint(), "deployment-leaf-b",
                    "deployment-key-b", deploymentLeafB, "witness-leaf-b", "witness-key-b",
                    witnessLeafB, false);
            fetcher.publish(gap, "\"gap\"", objectMapper);
            assertThat(authority.refreshNow()).isFalse();

            var broken = publication(2, "sha256:" + "f".repeat(64), "deployment-leaf-b",
                    "deployment-key-b", deploymentLeafB, "witness-leaf-b", "witness-key-b",
                    witnessLeafB, false);
            fetcher.publish(broken, "\"broken\"", objectMapper);
            assertThat(authority.refreshNow()).isFalse();

            var successor = publication(2, first.materialFingerprint(), "deployment-leaf-b",
                    "deployment-key-b", deploymentLeafB, "witness-leaf-b", "witness-key-b",
                    witnessLeafB, false);
            fetcher.publishReusingEtag(successor, "\"root-generation-1\"", objectMapper);
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.snapshot().sequence()).isOne();
        }
    }

    @Test
    void healthIsAggregateOnlyAndCloseImmediatelyRevokesReadiness() throws Exception {
        var first = publication(1, "", "deployment-leaf-a", "deployment-key-a",
                deploymentLeafA, "witness-leaf-a", "witness-key-a", witnessLeafA, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "\"root-generation-1\"", objectMapper);
        var authority = authority(fetcher, new InMemoryFloor());

        var health = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootHealth(
                authority).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("status", "HEALTHY")
                .containsEntry("sequence", 1L)
                .containsEntry("durableFloor", true)
                .doesNotContainKeys("uri", "etag", "trustRootSetId", "policyFingerprint",
                        "generationFingerprint", "authorityId", "keyId", "publicKey");

        authority.close();
        authority.close();
        assertThat(new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootHealth(
                authority).health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(authority.refreshNow()).isFalse();
    }

    @Test
    void realHttpRequiresExactMediaProtocolStrongEtagAndRejectsRedirect() throws Exception {
        var first = publication(1, "", "deployment-leaf-a", "deployment-key-a",
                deploymentLeafA, "witness-leaf-a", "witness-key-a", witnessLeafA, false);
        byte[] body = objectMapper.writeValueAsBytes(first);
        AtomicReference<String> accept = new AtomicReference<>("");
        AtomicReference<String> protocol = new AtomicReference<>("");
        AtomicReference<String> conditional = new AtomicReference<>("");
        AtomicInteger targetCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/roots", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            protocol.set(exchange.getRequestHeaders().getFirst(
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                            .PROTOCOL_HEADER));
            String etag = exchange.getRequestHeaders().getFirst("If-None-Match");
            conditional.set(etag == null ? "" : etag);
            exchange.getResponseHeaders().set("Content-Type",
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                            .MEDIA_TYPE);
            exchange.getResponseHeaders().set(
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                            .PROTOCOL_HEADER,
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                            .SCHEMA_VERSION);
            exchange.getResponseHeaders().set("ETag", "\"root-http-1\"");
            if ("\"root-http-1\"".equals(etag)) {
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
            targetCalls.incrementAndGet();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            try (var authority = httpAuthority(base.resolve("/roots"), new InMemoryFloor())) {
                assertThat(accept).hasValue(
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                                .MEDIA_TYPE);
                assertThat(protocol).hasValue(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                                .SCHEMA_VERSION);
                assertThat(authority.refreshNow()).isTrue();
                assertThat(conditional).hasValue("\"root-http-1\"");
            }
            assertThatThrownBy(() -> httpAuthority(base.resolve("/redirect"),
                    new InMemoryFloor())).isInstanceOf(IllegalStateException.class);
            assertThat(targetCalls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unsafeSettingsWeakEtagsAndUnavailableBootstrapAreRejected() throws Exception {
        assertThatThrownBy(() -> new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .Settings(URI.create("http://roots.example/current"), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(120), false)
                .validated()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .FetchedDocument.modified(new byte[]{1}, "W/\"weak\""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .FetchedDocument.notModified(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authority(new MutableFetcher(), new InMemoryFloor()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap is unavailable");
    }

    private DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
            authority(MutableFetcher fetcher,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor floor) {
        return new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority(
                objectMapper, clock, binding(), Set.of(POLICY), 2, deploymentRoots(), 2,
                witnessRoots(), floor, settings(), fetcher, false);
    }

    private DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
            httpAuthority(URI uri,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor floor) {
        var settings = new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .Settings(uri, Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofSeconds(5), Duration.ofSeconds(120), true);
        return new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority(
                objectMapper, clock, binding(), Set.of(POLICY), 2, deploymentRoots(), 2,
                witnessRoots(), floor, settings, null, false);
    }

    private DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
            .Settings settings() {
        return new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .Settings(URI.create("https://roots.example/current"), Duration.ofSeconds(10),
                Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(120), false);
    }

    private ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
            .ExpectedBinding binding() {
        return new ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .ExpectedBinding("tenant-a/staging", "recovery-fleet",
                "recovery-inventory-dual-roots",
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                DEPLOYMENT_ROOT_DOMAIN, WITNESS_ROOT_DOMAIN);
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.VerifiedBinding
            inventoryBinding() {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .VerifiedBinding("tenant-a/staging", "recovery-fleet", ARTIFACT, 4);
    }

    private DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.Settings
            inventorySettings() {
        return new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .Settings(URI.create("https://inventory.example/current"),
                Duration.ofSeconds(10), Duration.ofSeconds(1),
                Duration.ofSeconds(120), false);
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation inventory(
            long generation,
            String authorityId,
            String keyId,
            KeyPair signingKey) {
        List<LaneDescriptor> descriptors = List.of(laneA.descriptor());
        var material = new Material(Material.SCHEMA_VERSION, DEPLOYMENT_DOMAIN,
                "inventory-" + generation, generation, "tenant-a/staging", "recovery-fleet",
                ARTIFACT, 4, descriptors, POLICY, NOW.minusSeconds(120),
                NOW.minusSeconds(120), NOW.plusSeconds(3600));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .SCHEMA_VERSION,
                material, fingerprint,
                List.of(signer(authorityId, keyId, signingKey).sign(fingerprint)));
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
            inventoryPublication(
            long sequence,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication predecessor,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation inventory,
            String deploymentAuthorityId,
            String deploymentKeyId,
            KeyPair deploymentKey,
            String witnessAuthorityId,
            String witnessKeyId,
            KeyPair witnessKey) {
        String previousPublication = predecessor == null
                ? "" : predecessor.materialFingerprint();
        String previousWitness = predecessor == null
                ? "" : predecessor.witness().materialFingerprint();
        var material = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                .Material(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.Material
                        .SCHEMA_VERSION,
                DEPLOYMENT_DOMAIN, "publication-" + sequence, "tenant-a/staging",
                "recovery-fleet", sequence, inventory.materialFingerprint(), State.ACTIVE,
                POLICY, previousPublication, NOW.minusSeconds(60), NOW.minusSeconds(60),
                NOW.plusSeconds(600), "");
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var witnessMaterial = new WitnessMaterial(WitnessMaterial.SCHEMA_VERSION,
                WITNESS_DOMAIN, "checkpoint-" + sequence, "tenant-a/staging",
                "recovery-fleet", sequence, fingerprint, previousWitness,
                NOW.minusSeconds(30), NOW.minusSeconds(30), NOW.plusSeconds(600));
        String witnessFingerprint = ProtocolFingerprint.of(objectMapper, witnessMaterial);
        var witness = new WitnessCheckpoint(WitnessCheckpoint.SCHEMA_VERSION,
                witnessMaterial, witnessFingerprint,
                List.of(signer(witnessAuthorityId, witnessKeyId, witnessKey)
                        .sign(witnessFingerprint)));
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                inventory, material, fingerprint,
                List.of(signer(deploymentAuthorityId, deploymentKeyId, deploymentKey)
                        .sign(fingerprint)),
                witness);
    }

    private DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
            .FetchedDocument inventoryDocument(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication publication,
            String etag) throws Exception {
        return DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .FetchedDocument.modified(objectMapper.writeValueAsBytes(publication), etag);
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
            publication(long sequence, String previous, String deploymentAuthorityId,
            String deploymentKeyId, KeyPair deploymentLeaf, String witnessAuthorityId,
            String witnessKeyId, KeyPair witnessLeaf, boolean revokeWitness) {
        var material = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                .Material(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .Material.SCHEMA_VERSION,
                "recovery-inventory-dual-roots", sequence, previous,
                "tenant-a/staging", "recovery-fleet",
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                DEPLOYMENT_ROOT_DOMAIN, WITNESS_ROOT_DOMAIN,
                DEPLOYMENT_DOMAIN, WITNESS_DOMAIN, 1, 1,
                List.of(keyMaterial(deploymentAuthorityId, deploymentKeyId,
                        deploymentLeaf, false)),
                List.of(keyMaterial(witnessAuthorityId, witnessKeyId,
                        witnessLeaf, revokeWitness)),
                POLICY, NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(3600));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .SCHEMA_VERSION,
                material, fingerprint,
                signatures(fingerprint,
                        signer("deployment-root-a", "deployment-root-key-a", deploymentRootA),
                        signer("deployment-root-b", "deployment-root-key-b", deploymentRootB)),
                signatures(fingerprint,
                        signer("witness-root-a", "witness-root-key-a", witnessRootA),
                        signer("witness-root-b", "witness-root-key-b", witnessRootB)));
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
            .AuthorityKeyMaterial keyMaterial(String authorityId, String keyId, KeyPair pair,
            boolean revoked) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                .AuthorityKeyMaterial(authorityId, keyId,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                NOW.minusSeconds(3600), NOW.plusSeconds(7200), true, revoked);
    }

    private List<AuthorityKey> deploymentRoots() {
        return List.of(rootKey("deployment-root-a", "deployment-root-key-a", deploymentRootA),
                rootKey("deployment-root-b", "deployment-root-key-b", deploymentRootB));
    }

    private List<AuthorityKey> witnessRoots() {
        return List.of(rootKey("witness-root-a", "witness-root-key-a", witnessRootA),
                rootKey("witness-root-b", "witness-root-key-b", witnessRootB));
    }

    private static AuthorityKey rootKey(String authorityId, String keyId, KeyPair pair) {
        return new AuthorityKey(authorityId, keyId, pair.getPublic(),
                NOW.minusSeconds(3600), NOW.plusSeconds(7200), true, false);
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
            .AuthoritySignature reference(String authorityId, String keyId) {
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature(authorityId, keyId, "Ed25519", NOW.minusSeconds(20),
                Base64.getEncoder().encodeToString(new byte[64]));
    }

    private static List<ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
            .AuthoritySignature> signatures(String fingerprint, Signer... signers) {
        return java.util.Arrays.stream(signers).map(signer -> signer.sign(fingerprint))
                .sorted(Comparator.comparing(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                                .AuthoritySignature::authorityId))
                .toList();
    }

    private static Signer signer(String authorityId, String keyId, KeyPair pair) {
        return new Signer(authorityId, keyId, pair);
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private record Signer(String authorityId, String keyId, KeyPair pair) {
        private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                .AuthoritySignature sign(String fingerprint) {
            try {
                Signature signature = Signature.getInstance("Ed25519");
                signature.initSign(pair.getPrivate());
                signature.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .AuthoritySignature(authorityId, keyId, "Ed25519",
                        NOW.minusSeconds(20),
                        Base64.getEncoder().encodeToString(signature.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class MutableFetcher implements
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                    .DocumentFetcher {
        private DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .FetchedDocument current;
        private boolean failed;
        private boolean forceModified;
        private int calls;
        private String lastConditionalEtag = "";

        @Override
        public synchronized DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                .FetchedDocument fetch(URI uri, String etag, Duration timeout) {
            calls++;
            lastConditionalEtag = etag;
            if (failed || current == null) {
                throw new DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                        .RemoteTrustRootUnavailableException("unavailable");
            }
            if (!forceModified && current.etag().equals(etag)) {
                return DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                        .FetchedDocument.notModified(etag);
            }
            forceModified = false;
            return current;
        }

        private synchronized void publish(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        publication,
                String etag,
                ObjectMapper objectMapper) throws Exception {
            current = DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                    .FetchedDocument.modified(objectMapper.writeValueAsBytes(publication), etag);
            failed = false;
            forceModified = false;
        }

        private synchronized void publishReusingEtag(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        publication,
                String etag,
                ObjectMapper objectMapper) throws Exception {
            publish(publication, etag, objectMapper);
            forceModified = true;
        }

        private synchronized void fail() {
            failed = true;
        }

        private synchronized int calls() {
            return calls;
        }

        private synchronized String lastConditionalEtag() {
            return lastConditionalEtag;
        }
    }

    private static final class InMemoryFloor implements
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor {
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
                throw new IllegalArgumentException("floor rollback");
            }
            if (generation.sequence() == current.sequence()) {
                if (!generation.materialFingerprint().equals(current.materialFingerprint())) {
                    throw new IllegalArgumentException("floor fork");
                }
                return;
            }
            if (generation.sequence() != current.sequence() + 1
                    || !generation.previousMaterialFingerprint().equals(
                    current.materialFingerprint())) {
                throw new IllegalArgumentException("floor discontinuity");
            }
            current = generation;
        }

        @Override
        public boolean durable() {
            return true;
        }
    }

    private static final class DurableInventoryFloor implements
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor {

        @Override
        public void accept(Generation generation) {
            // The dynamic authority independently verifies the complete predecessor chain.
        }

        @Override
        public boolean durable() {
            return true;
        }
    }

    private static final class InventoryFetcher implements
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                    .DocumentFetcher {
        private final List<DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .FetchedDocument> documents;
        private int index;

        private InventoryFetcher(
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .FetchedDocument... documents) {
            this.documents = List.of(documents);
        }

        @Override
        public DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                .FetchedDocument fetch(URI uri, String etag, Duration timeout) {
            if (index >= documents.size()) {
                return DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .FetchedDocument.notModified(etag);
            }
            return documents.get(index++);
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

        private void set(Instant value) {
            instant = value;
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
