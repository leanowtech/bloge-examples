package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.ToolStudioResourceGatewayProtocol;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicTestSecretAuthorityServingInventoryTrustRootAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-19T00:00:00Z");
    private static final String POLICY = "sha256:" + "a".repeat(64);
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
    }

    @Test
    void unknownRuntimeKeyTriggersOneAtomicDualKeySetRotation() throws Exception {
        var first = publication(1, "", "deployment-a", deploymentLeafA,
                "witness-a", witnessLeafA, false);
        var second = publication(2, first.materialFingerprint(),
                "deployment-b", deploymentLeafB, "witness-b", witnessLeafB, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "root-generation-1", objectMapper);
        InMemoryFloor floor = new InMemoryFloor();
        try (var authority = authority(fetcher, floor, false)) {
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isTrue();
                assertThat(snapshot.sequence()).isOne();
                assertThat(snapshot.status()).isEqualTo("HEALTHY");
            });
            fetcher.publish(second, "root-generation-2", objectMapper);

            var keys = authority.keysFor(
                    List.of(reference("deployment-b", "deployment-key-b")),
                    List.of(reference("witness-b", "witness-key-b")));

            assertThat(fetcher.calls()).isEqualTo(2);
            assertThat(keys.deploymentKeys()).containsKey("deployment-b\u0000deployment-key-b");
            assertThat(keys.witnessKeys()).containsKey("witness-b\u0000witness-key-b");
            assertThat(authority.snapshot().sequence()).isEqualTo(2);
            assertThat(floor.current.sequence()).isEqualTo(2);
            assertThat(authority.generationFingerprint())
                    .isEqualTo(second.materialFingerprint());
        }
    }

    @Test
    void healthPublishesOnlyAggregateManagedRootReadiness() throws Exception {
        var first = publication(1, "", "deployment-a", deploymentLeafA,
                "witness-a", witnessLeafA, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "root-generation-1", objectMapper);
        var authority = authority(fetcher, new InMemoryFloor(), false);

        var health = new TestSecretAuthorityServingInventoryTrustRootHealth(authority).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("status", "HEALTHY")
                .containsEntry("sequence", 1L)
                .containsEntry("deploymentSignatureThreshold", 1)
                .containsEntry("witnessSignatureThreshold", 1)
                .containsEntry("durableFloor", true)
                .doesNotContainKeys("uri", "etag", "trustRootSetId", "policyFingerprint",
                        "generationFingerprint", "authorityId", "keyId", "publicKey");

        authority.close();
        assertThat(new TestSecretAuthorityServingInventoryTrustRootHealth(authority)
                .health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void managedInventoryRotatesRuntimeKeysWithoutRestartAndFailsClosedOnRootRevocation()
            throws Exception {
        var firstRoot = publication(1, "", "deployment-a", deploymentLeafA,
                "witness-a", witnessLeafA, false);
        var secondRoot = publication(2, firstRoot.materialFingerprint(),
                "deployment-b", deploymentLeafB, "witness-b", witnessLeafB, false);
        var revokedRoot = publication(3, secondRoot.materialFingerprint(),
                "deployment-b", deploymentLeafB, "witness-b", witnessLeafB, true);
        MutableFetcher rootFetcher = new MutableFetcher();
        rootFetcher.publish(firstRoot, "root-generation-1", objectMapper);

        var firstInventory = inventoryPublication(1, "", "", 17,
                "deployment-a", deploymentLeafA, "witness-a", witnessLeafA);
        var secondInventory = inventoryPublication(2,
                firstInventory.materialFingerprint(),
                firstInventory.witness().materialFingerprint(), 18,
                "deployment-b", deploymentLeafB, "witness-b", witnessLeafB);
        InventoryFetcher inventoryFetcher = new InventoryFetcher(
                inventoryDocument(firstInventory, "inventory-generation-1"),
                inventoryDocument(secondInventory, "inventory-generation-2"));

        try (var roots = authority(rootFetcher, new InMemoryFloor(), false);
             var inventory = new DynamicTestSecretAuthorityServingInventoryAuthority(
                     objectMapper, clock, Set.of(POLICY), inventoryBinding(),
                     new InMemoryInventoryFloor(), roots, inventorySettings(),
                     inventoryFetcher, false)) {
            String firstGeneration = inventory.observation().sourceGenerationFingerprint();
            assertThat(inventory.descriptor().properties())
                    .containsEntry("managedTrustRootRefresh", true)
                    .containsEntry("atomicDualTrustRootPublication", true);

            rootFetcher.publish(secondRoot, "root-generation-2", objectMapper);
            assertThat(inventory.refreshNow()).isTrue();

            assertThat(rootFetcher.calls()).isEqualTo(2);
            assertThat(roots.snapshot().sequence()).isEqualTo(2);
            assertThat(inventory.observation()).satisfies(observed -> {
                assertThat(observed.available()).isTrue();
                assertThat(observed.sourceSequence()).isEqualTo(2);
                assertThat(observed.revision()).isEqualTo(18);
                assertThat(observed.sourceGenerationFingerprint())
                        .isNotEqualTo(firstGeneration);
            });

            rootFetcher.publish(revokedRoot, "root-generation-3", objectMapper);
            assertThat(roots.refreshNow()).isTrue();
            assertThat(roots.snapshot().status())
                    .isEqualTo("WITNESS_THRESHOLD_UNAVAILABLE");
            assertThat(inventory.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status())
                        .isEqualTo("TRUST_ROOT_WITNESS_THRESHOLD_UNAVAILABLE");
            });
        }
    }

    @Test
    void notModifiedInventoryIsReverifiedAfterManagedRootGenerationChanges()
            throws Exception {
        var firstRoot = publication(1, "", "deployment-a", deploymentLeafA,
                "witness-a", witnessLeafA, false);
        var secondRoot = publication(2, firstRoot.materialFingerprint(),
                "deployment-a", deploymentLeafA, "witness-a", witnessLeafA, false);
        MutableFetcher rootFetcher = new MutableFetcher();
        rootFetcher.publish(firstRoot, "root-generation-1", objectMapper);

        var inventoryPublication = inventoryPublication(1, "", "", 17,
                "deployment-a", deploymentLeafA, "witness-a", witnessLeafA);
        InventoryFetcher inventoryFetcher = new InventoryFetcher(
                inventoryDocument(inventoryPublication, "inventory-generation-1"),
                DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument
                        .notModified("inventory-generation-1"));

        try (var roots = authority(rootFetcher, new InMemoryFloor(), false);
             var inventory = new DynamicTestSecretAuthorityServingInventoryAuthority(
                     objectMapper, clock, Set.of(POLICY), inventoryBinding(),
                     new InMemoryInventoryFloor(), roots, inventorySettings(),
                     inventoryFetcher, false)) {
            String firstGeneration = inventory.observation().sourceGenerationFingerprint();

            rootFetcher.publish(secondRoot, "root-generation-2", objectMapper);
            assertThat(roots.refreshNow()).isTrue();
            assertThat(inventory.observation()).satisfies(observed -> {
                assertThat(observed.available()).isFalse();
                assertThat(observed.status())
                        .isEqualTo("TRUST_ROOT_GENERATION_UNVERIFIED");
            });

            assertThat(inventory.refreshNow()).isTrue();

            assertThat(inventory.observation()).satisfies(observed -> {
                assertThat(observed.available()).isTrue();
                assertThat(observed.status()).isEqualTo("VERIFIED");
                assertThat(observed.sourceSequence()).isOne();
                assertThat(observed.sourceGenerationFingerprint())
                        .isNotEqualTo(firstGeneration);
            });
        }
    }

    @Test
    void refreshFailureClosesKeyAccessAndValidSuccessorRecovers() throws Exception {
        var first = publication(1, "", "deployment-a", deploymentLeafA,
                "witness-a", witnessLeafA, false);
        var second = publication(2, first.materialFingerprint(),
                "deployment-b", deploymentLeafB, "witness-b", witnessLeafB, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "root-generation-1", objectMapper);
        try (var authority = authority(fetcher, new InMemoryFloor(), false)) {
            fetcher.fail();
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isFalse();
                assertThat(snapshot.status()).isEqualTo("REFRESH_UNAVAILABLE");
                assertThat(snapshot.lastFailureCode())
                        .isEqualTo("TRUST_ROOT_SOURCE_UNAVAILABLE");
            });
            assertThatThrownBy(() -> authority.keysFor(
                    List.of(reference("deployment-a", "deployment-key-a")),
                    List.of(reference("witness-a", "witness-key-a"))))
                    .isInstanceOf(IllegalStateException.class);

            fetcher.publish(second, "root-generation-2", objectMapper);
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.snapshot().available()).isTrue();
        }
    }

    @Test
    void notModifiedRenewsOnlySourceAgeAndHardAgeFailsWithoutNetworkRead() throws Exception {
        var first = publication(1, "", "deployment-a", deploymentLeafA,
                "witness-a", witnessLeafA, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "root-generation-1", objectMapper);
        try (var authority = authority(fetcher, new InMemoryFloor(), false)) {
            clock.advance(Duration.ofSeconds(50));
            assertThat(authority.refreshNow()).isTrue();
            assertThat(fetcher.lastConditionalEtag()).isEqualTo("root-generation-1");
            clock.advance(Duration.ofSeconds(50));
            assertThat(authority.snapshot().available()).isTrue();

            clock.advance(Duration.ofSeconds(61));
            int calls = fetcher.calls();
            assertThat(authority.snapshot().status()).isEqualTo("SOURCE_EXPIRED");
            assertThat(fetcher.calls()).isEqualTo(calls);
        }
    }

    @Test
    void signedRuntimeThresholdRevocationIsHealthyRefreshButUnavailable() throws Exception {
        var first = publication(1, "", "deployment-a", deploymentLeafA,
                "witness-a", witnessLeafA, false);
        var revoked = publication(2, first.materialFingerprint(),
                "deployment-b", deploymentLeafB, "witness-b", witnessLeafB, true);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "root-generation-1", objectMapper);
        try (var authority = authority(fetcher, new InMemoryFloor(), false)) {
            fetcher.publish(revoked, "root-generation-2", objectMapper);
            assertThat(authority.refreshNow()).isTrue();
            assertThat(authority.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.available()).isFalse();
                assertThat(snapshot.status()).isEqualTo("WITNESS_THRESHOLD_UNAVAILABLE");
                assertThat(snapshot.refreshFailureCount()).isZero();
                assertThat(snapshot.sequence()).isEqualTo(2);
            });
        }
    }

    @Test
    void forkGapAndBrokenPredecessorNeverReplaceCurrentSnapshot() throws Exception {
        var first = publication(1, "", "deployment-a", deploymentLeafA,
                "witness-a", witnessLeafA, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "root-generation-1", objectMapper);
        try (var authority = authority(fetcher, new InMemoryFloor(), false)) {
            var fork = publication(1, "", "deployment-b", deploymentLeafB,
                    "witness-b", witnessLeafB, false);
            fetcher.publish(fork, "fork", objectMapper);
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.snapshot().sequence()).isOne();

            var gap = publication(3, first.materialFingerprint(),
                    "deployment-b", deploymentLeafB, "witness-b", witnessLeafB, false);
            fetcher.publish(gap, "gap", objectMapper);
            assertThat(authority.refreshNow()).isFalse();

            var broken = publication(2, "sha256:" + "f".repeat(64),
                    "deployment-b", deploymentLeafB, "witness-b", witnessLeafB, false);
            fetcher.publish(broken, "broken", objectMapper);
            assertThat(authority.refreshNow()).isFalse();
            assertThat(authority.snapshot().sequence()).isOne();
        }
    }

    @Test
    void backgroundLaneRefreshesAndCloseRevokesReadiness() throws Exception {
        var first = publication(1, "", "deployment-a", deploymentLeafA,
                "witness-a", witnessLeafA, false);
        MutableFetcher fetcher = new MutableFetcher();
        fetcher.publish(first, "root-generation-1", objectMapper);
        var authority = authority(fetcher, new InMemoryFloor(), true,
                Duration.ofSeconds(1), Duration.ofSeconds(3));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (fetcher.calls() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertThat(fetcher.calls()).isGreaterThanOrEqualTo(2);
        assertThat(authority.snapshot().automaticRefresh()).isTrue();
        authority.close();
        assertThat(authority.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.available()).isFalse();
            assertThat(snapshot.status()).isEqualTo("CLOSED");
        });
    }

    @Test
    void realHttpRequiresExactMediaProtocolEtagAndNoRedirect() throws Exception {
        var first = publication(1, "", "deployment-a", deploymentLeafA,
                "witness-a", witnessLeafA, false);
        byte[] body = objectMapper.writeValueAsBytes(first);
        AtomicReference<String> accept = new AtomicReference<>("");
        AtomicReference<String> protocol = new AtomicReference<>("");
        AtomicReference<String> conditional = new AtomicReference<>("");
        AtomicInteger targetCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/roots", exchange -> {
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            protocol.set(exchange.getRequestHeaders().getFirst(
                    DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.PROTOCOL_HEADER));
            String etag = exchange.getRequestHeaders().getFirst("If-None-Match");
            conditional.set(etag == null ? "" : etag);
            exchange.getResponseHeaders().set("Content-Type",
                    DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.MEDIA_TYPE);
            exchange.getResponseHeaders().set(
                    DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.PROTOCOL_HEADER,
                    TestSecretAuthorityServingInventoryTrustRootPublication.SCHEMA_VERSION);
            exchange.getResponseHeaders().set("ETag", "root-http-1");
            if ("root-http-1".equals(etag)) {
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
        server.createContext("/generic", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.getResponseHeaders().set(
                    DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.PROTOCOL_HEADER,
                    TestSecretAuthorityServingInventoryTrustRootPublication.SCHEMA_VERSION);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            try (var authority = httpAuthority(base.resolve("/roots"), new InMemoryFloor())) {
                assertThat(accept).hasValue(
                        DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.MEDIA_TYPE);
                assertThat(protocol).hasValue(
                        TestSecretAuthorityServingInventoryTrustRootPublication.SCHEMA_VERSION);
                assertThat(authority.refreshNow()).isTrue();
                assertThat(conditional).hasValue("root-http-1");
            }
            assertThatThrownBy(() -> httpAuthority(
                    base.resolve("/redirect"), new InMemoryFloor()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("bootstrap");
            assertThat(targetCalls).hasValue(0);
            assertThatThrownBy(() -> httpAuthority(
                    base.resolve("/generic"), new InMemoryFloor()))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unsafeSettingsAndUnavailableBootstrapAreRejected() {
        assertThatThrownBy(() -> settings(URI.create("http://roots.example/current"),
                Duration.ofSeconds(30), Duration.ofSeconds(60), false).validated())
                .hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> settings(URI.create("https://roots.example/current"),
                Duration.ofSeconds(30), Duration.ofSeconds(30), false).validated())
                .hasMessageContaining("cover refresh plus timeout");
        MutableFetcher unavailable = new MutableFetcher();
        unavailable.fail();
        assertThatThrownBy(() -> authority(unavailable, new InMemoryFloor(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bootstrap");
        assertThatThrownBy(() ->
                DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.fromJson(
                        objectMapper, binding(), POLICY, 1,
                        "[{\"authorityId\":\"duplicate\",\"authorityId\":\"duplicate\"}]",
                        1, "[]", new InMemoryFloor(),
                        settings(URI.create("https://roots.example/current"),
                                Duration.ofSeconds(30), Duration.ofSeconds(60), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("configuration");
    }

    private DynamicTestSecretAuthorityServingInventoryTrustRootAuthority authority(
            MutableFetcher fetcher, InMemoryFloor floor, boolean scheduler) {
        return authority(fetcher, floor, scheduler,
                Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    private DynamicTestSecretAuthorityServingInventoryTrustRootAuthority authority(
            MutableFetcher fetcher,
            InMemoryFloor floor,
            boolean scheduler,
            Duration refresh,
            Duration maximumAge) {
        return new DynamicTestSecretAuthorityServingInventoryTrustRootAuthority(
                objectMapper, clock, binding(), Set.of(POLICY), 2, deploymentRoots(),
                2, witnessRoots(), floor,
                settings(URI.create("https://roots.example/current"),
                        refresh, maximumAge, false), fetcher, scheduler);
    }

    private DynamicTestSecretAuthorityServingInventoryTrustRootAuthority httpAuthority(
            URI uri, InMemoryFloor floor) {
        return new DynamicTestSecretAuthorityServingInventoryTrustRootAuthority(
                objectMapper, clock, binding(), Set.of(POLICY), 2, deploymentRoots(),
                2, witnessRoots(), floor,
                settings(uri, Duration.ofSeconds(30), Duration.ofSeconds(60), true),
                null, false);
    }

    private static DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.Settings settings(
            URI uri, Duration refresh, Duration maximumAge, boolean insecureLoopback) {
        return new DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.Settings(
                uri, refresh, Duration.ofSeconds(1), Duration.ofSeconds(1),
                maximumAge, insecureLoopback);
    }

    private ConfiguredTestSecretAuthorityServingInventoryTrustRootAuthority.ExpectedBinding
            binding() {
        return new ConfiguredTestSecretAuthorityServingInventoryTrustRootAuthority.ExpectedBinding(
                "test-secret-fleet", "inventory-dual-roots",
                ToolStudioResourceGatewayProtocol.VERSION,
                "deployment-root.example", "witness-root.example");
    }

    private TestSecretAuthorityServingInventoryTrustRootPublication publication(
            long sequence,
            String previous,
            String deploymentAuthority,
            KeyPair deploymentLeaf,
            String witnessAuthority,
            KeyPair witnessLeaf,
            boolean revokeWitness) {
        var material = new TestSecretAuthorityServingInventoryTrustRootPublication.Material(
                TestSecretAuthorityServingInventoryTrustRootPublication.Material.SCHEMA_VERSION,
                "inventory-dual-roots", sequence, previous,
                "test-secret-fleet", ToolStudioResourceGatewayProtocol.VERSION,
                "deployment-root.example", "witness-root.example",
                "test-secret-deployment.example", "test-secret-witness.example", 1, 1,
                List.of(keyMaterial(deploymentAuthority,
                        "deployment-key-" + suffix(deploymentAuthority),
                        deploymentLeaf, false)),
                List.of(keyMaterial(witnessAuthority,
                        "witness-key-" + suffix(witnessAuthority),
                        witnessLeaf, revokeWitness)),
                POLICY, NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(3600));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new TestSecretAuthorityServingInventoryTrustRootPublication(
                TestSecretAuthorityServingInventoryTrustRootPublication.SCHEMA_VERSION,
                material, fingerprint,
                signatures(fingerprint,
                        signer("deployment-root-a", "deployment-root-key-a", deploymentRootA),
                        signer("deployment-root-b", "deployment-root-key-b", deploymentRootB)),
                signatures(fingerprint,
                        signer("witness-root-a", "witness-root-key-a", witnessRootA),
                        signer("witness-root-b", "witness-root-key-b", witnessRootB)));
    }

    private TestSecretAuthorityServingInventoryPublication inventoryPublication(
            long sequence,
            String previousPublication,
            String previousWitness,
            long revision,
            String deploymentAuthority,
            KeyPair deploymentLeaf,
            String witnessAuthority,
            KeyPair witnessLeaf) {
        var inventoryMaterial = new TestSecretAuthorityServingInventory.Material(
                TestSecretAuthorityServingInventory.Material.SCHEMA_VERSION,
                "test-secret-deployment.example", "inventory-" + revision, revision,
                "test-secret-fleet", "test-secret-cohort-2026-07-19", ARTIFACT,
                ToolStudioResourceGatewayProtocol.VERSION,
                "test-secret-authority.example", List.of("replica-a"), POLICY,
                NOW.minusSeconds(120), NOW.minusSeconds(120), NOW.plusSeconds(3600));
        String inventoryFingerprint = ProtocolFingerprint.of(objectMapper, inventoryMaterial);
        var inventory = new TestSecretAuthorityServingInventory(
                TestSecretAuthorityServingInventory.SCHEMA_VERSION,
                inventoryMaterial, inventoryFingerprint,
                signatures(inventoryFingerprint, signer(deploymentAuthority,
                        "deployment-key-" + suffix(deploymentAuthority), deploymentLeaf)));
        var publicationMaterial = new TestSecretAuthorityServingInventoryPublication.Material(
                TestSecretAuthorityServingInventoryPublication.Material.SCHEMA_VERSION,
                "test-secret-deployment.example", "publication-" + sequence, sequence,
                inventoryFingerprint, TestSecretAuthorityServingInventoryPublication.State.ACTIVE,
                POLICY, previousPublication, NOW.minusSeconds(60), NOW.minusSeconds(60),
                NOW.plusSeconds(600), "");
        String publicationFingerprint = ProtocolFingerprint.of(objectMapper, publicationMaterial);
        var witnessMaterial = new TestSecretAuthorityServingInventoryPublication.WitnessMaterial(
                TestSecretAuthorityServingInventoryPublication.WitnessMaterial.SCHEMA_VERSION,
                "test-secret-witness.example", "checkpoint-" + sequence, sequence,
                publicationFingerprint, previousWitness,
                NOW.minusSeconds(30), NOW.minusSeconds(30), NOW.plusSeconds(600));
        String witnessFingerprint = ProtocolFingerprint.of(objectMapper, witnessMaterial);
        var witness = new TestSecretAuthorityServingInventoryPublication.WitnessCheckpoint(
                TestSecretAuthorityServingInventoryPublication.WitnessCheckpoint.SCHEMA_VERSION,
                witnessMaterial, witnessFingerprint,
                signatures(witnessFingerprint, signer(witnessAuthority,
                        "witness-key-" + suffix(witnessAuthority), witnessLeaf)));
        return new TestSecretAuthorityServingInventoryPublication(
                TestSecretAuthorityServingInventoryPublication.SCHEMA_VERSION,
                inventory, publicationMaterial, publicationFingerprint,
                signatures(publicationFingerprint, signer(deploymentAuthority,
                        "deployment-key-" + suffix(deploymentAuthority), deploymentLeaf)),
                witness);
    }

    private ConfiguredTestSecretAuthorityServingInventoryAuthority.ExpectedBinding
            inventoryBinding() {
        return new ConfiguredTestSecretAuthorityServingInventoryAuthority.ExpectedBinding(
                "test-secret-fleet", "test-secret-cohort-2026-07-19", ARTIFACT,
                ToolStudioResourceGatewayProtocol.VERSION,
                "test-secret-authority.example", "replica-a");
    }

    private DynamicTestSecretAuthorityServingInventoryAuthority.Settings inventorySettings() {
        return new DynamicTestSecretAuthorityServingInventoryAuthority.Settings(
                URI.create("https://inventory.example/current"), Duration.ofSeconds(30),
                Duration.ofSeconds(1), Duration.ofSeconds(60), false);
    }

    private DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument inventoryDocument(
            TestSecretAuthorityServingInventoryPublication publication,
            String etag) throws Exception {
        return DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument.modified(
                objectMapper.writeValueAsBytes(publication), etag);
    }

    private static String suffix(String authority) {
        return authority.substring(authority.length() - 1);
    }

    private static TestSecretAuthorityServingInventoryTrustRootPublication.AuthorityKeyMaterial
            keyMaterial(String authority, String keyId, KeyPair pair, boolean revoked) {
        return new TestSecretAuthorityServingInventoryTrustRootPublication.AuthorityKeyMaterial(
                authority, keyId,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                NOW.minusSeconds(3600), NOW.plusSeconds(7200), true, revoked);
    }

    private List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            deploymentRoots() {
        return List.of(rootKey("deployment-root-a", "deployment-root-key-a", deploymentRootA),
                rootKey("deployment-root-b", "deployment-root-key-b", deploymentRootB));
    }

    private List<ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey>
            witnessRoots() {
        return List.of(rootKey("witness-root-a", "witness-root-key-a", witnessRootA),
                rootKey("witness-root-b", "witness-root-key-b", witnessRootB));
    }

    private static ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey rootKey(
            String authorityId, String keyId, KeyPair pair) {
        return new ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey(
                authorityId, keyId, pair.getPublic(), Instant.MIN, Instant.MAX, true, false);
    }

    private static TestSecretAuthorityServingInventory.AuthoritySignature reference(
            String authorityId, String keyId) {
        return new TestSecretAuthorityServingInventory.AuthoritySignature(
                authorityId, keyId, "Ed25519", NOW,
                Base64.getEncoder().encodeToString(new byte[64]));
    }

    private static List<TestSecretAuthorityServingInventory.AuthoritySignature> signatures(
            String fingerprint, Signer... signers) {
        return java.util.Arrays.stream(signers).map(signer -> signer.sign(fingerprint))
                .sorted(Comparator.comparing(
                        TestSecretAuthorityServingInventory.AuthoritySignature::authorityId))
                .toList();
    }

    private static Signer signer(String authorityId, String keyId, KeyPair pair) {
        return new Signer(authorityId, keyId, pair);
    }

    private static KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
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

    private static final class MutableFetcher implements
            DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.DocumentFetcher {
        private final AtomicReference<byte[]> body = new AtomicReference<>();
        private final AtomicReference<String> etag = new AtomicReference<>("");
        private final AtomicReference<String> lastConditionalEtag = new AtomicReference<>("");
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean failed;

        private void publish(
                TestSecretAuthorityServingInventoryTrustRootPublication publication,
                String nextEtag,
                ObjectMapper objectMapper) throws Exception {
            body.set(objectMapper.writeValueAsBytes(publication));
            etag.set(nextEtag);
            failed = false;
        }

        private void fail() {
            failed = true;
        }

        @Override
        public DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.FetchedDocument fetch(
                URI uri, String conditionalEtag, Duration timeout) {
            calls.incrementAndGet();
            lastConditionalEtag.set(conditionalEtag);
            if (failed || body.get() == null) {
                throw new DynamicTestSecretAuthorityServingInventoryTrustRootAuthority
                        .RemoteTrustRootUnavailableException("root source unavailable");
            }
            if (etag.get().equals(conditionalEtag)) {
                return DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.FetchedDocument
                        .notModified(etag.get());
            }
            return DynamicTestSecretAuthorityServingInventoryTrustRootAuthority.FetchedDocument
                    .modified(body.get(), etag.get());
        }

        private int calls() {
            return calls.get();
        }

        private String lastConditionalEtag() {
            return lastConditionalEtag.get();
        }
    }

    private static final class InMemoryFloor
            implements TestSecretAuthorityServingInventoryTrustRootFloor {
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

    private static final class InventoryFetcher implements
            DynamicTestSecretAuthorityServingInventoryAuthority.DocumentFetcher {
        private final ArrayDeque<DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument>
                documents;

        private InventoryFetcher(
                DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument... documents) {
            this.documents = new ArrayDeque<>(List.of(documents));
        }

        @Override
        public DynamicTestSecretAuthorityServingInventoryAuthority.FetchedDocument fetch(
                URI uri, String etag, Duration timeout) {
            return documents.removeFirst();
        }
    }

    private static final class InMemoryInventoryFloor
            implements TestSecretAuthorityServingInventoryPublicationFloor {
        private Generation current;

        @Override
        public synchronized void accept(Generation generation) {
            if (current == null) {
                if (generation.sequence() != 1) {
                    throw new IllegalArgumentException("inventory floor must begin at one");
                }
                current = generation;
                return;
            }
            if (generation.sequence() == current.sequence()
                    && generation.publicationMaterialFingerprint().equals(
                    current.publicationMaterialFingerprint())
                    && generation.witnessMaterialFingerprint().equals(
                    current.witnessMaterialFingerprint())) {
                return;
            }
            if (generation.sequence() != current.sequence() + 1
                    || !generation.previousPublicationFingerprint().equals(
                    current.publicationMaterialFingerprint())
                    || !generation.previousWitnessFingerprint().equals(
                    current.witnessMaterialFingerprint())) {
                throw new IllegalArgumentException("inventory floor discontinuity");
            }
            current = generation;
        }

        @Override
        public boolean durable() {
            return true;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
