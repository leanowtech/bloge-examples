package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt.IsolationMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.Binding;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfigurationTest {

    private static final String PREFIX =
            TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration.Properties
                    .PREFIX + ".";
    private static final String POLICY = fingerprint('b');
    private static final String ARTIFACT = fingerprint('a');

    private ObjectMapper objectMapper;
    private KeyPair deployment;
    private KeyPair witness;
    private TestSuiteStabilityPhysicalAttemptObservationAuthority provider;
    private HttpServer server;
    private Map<String, Object> enabledProperties;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        deployment = generator.generateKeyPair();
        witness = generator.generateKeyPair();
        provider = mock(TestSuiteStabilityPhysicalAttemptObservationAuthority.class);
        when(provider.descriptor()).thenReturn(binding().descriptor());

        byte[] publication = objectMapper.writeValueAsBytes(publication());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/inventory", exchange -> {
            exchange.getResponseHeaders().set("Content-Type",
                    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                            .MEDIA_TYPE);
            exchange.getResponseHeaders().set(
                    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
                            .PROTOCOL_HEADER,
                    TestSuiteStabilityPhysicalAttemptProviderInventoryPublication
                            .SCHEMA_VERSION);
            exchange.getResponseHeaders().set("ETag", "\"generation-1\"");
            exchange.sendResponseHeaders(200, publication.length);
            exchange.getResponseBody().write(publication);
            exchange.close();
        });
        server.start();
        enabledProperties = properties(true);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void disabledRuntimeInstallsNoProviderInventoryBeans() throws Exception {
        try (var context = context(properties(false), true, "test")) {
            assertRuntimeAbsent(context);
        }
    }

    @Test
    void productionPresencePhysicallyExcludesRuntime() {
        try (var production = context(enabledProperties, true, "production");
             var mixed = context(enabledProperties, true, "production", "test")) {
            assertRuntimeAbsent(production);
            assertRuntimeAbsent(mixed);
        }
    }

    @Test
    void enabledTestProfileAssemblesDynamicAuthorityCohortMonitorAndHealth() {
        var context = context(enabledProperties, true, "test");
        var authority = context.getBean(
                DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class);
        var repository = context.getBean(
                DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository.class);
        var monitor = context.getBean(
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor.class);
        try {
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.class))
                    .hasSize(1);
            assertThat(authority.descriptor().properties())
                    .containsEntry("dynamicInventory", true)
                    .containsEntry("automaticRefresh", true)
                    .containsEntry("signedRevocation", true)
                    .containsEntry("witnessedPublications", true)
                    .containsEntry("durablePublicationFloor", true);
            assertThat(repository.observation()).satisfies(observed -> {
                assertThat(observed.available()).isTrue();
                assertThat(observed.status()).isEqualTo("CONVERGED");
                assertThat(observed.expectedReplicas()).isOne();
                assertThat(observed.readyReplicas()).isOne();
            });
            assertThat(context.getBean(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryHealth.class)
                    .health().getStatus()).isEqualTo(Status.UP);
        } finally {
            context.close();
        }

        assertThat(authority.observation().status()).isEqualTo("CLOSED");
        assertThat(monitor.heartbeatNow()).isFalse();
    }

    @Test
    void enabledRuntimeRequiresExactlyOneInstalledAdapterCatalog() {
        var missing = unrefreshedContext(enabledProperties, false, "test");
        try {
            assertThatThrownBy(missing::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Physical provider-inventory runtime configuration is invalid");
        } finally {
            missing.close();
        }

        var duplicate = unrefreshedContext(enabledProperties, true, "test");
        duplicate.registerBean("secondCatalog",
                TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog.class,
                () -> () -> Map.of(binding().identity(), provider));
        try {
            assertThatThrownBy(duplicate::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Physical provider-inventory runtime configuration is invalid");
        } finally {
            duplicate.close();
        }
    }

    @Test
    void unknownAndUnsafeConfigurationFailWithoutEchoingSensitiveValues() {
        Map<String, Object> unknown = new LinkedHashMap<>(enabledProperties);
        unknown.put(PREFIX + "expected-replica-ids", "must-not-bind");
        var unknownContext = unrefreshedContext(unknown, true, "test");
        try {
            assertThatThrownBy(unknownContext::refresh)
                    .hasStackTraceContaining("expected-replica-ids");
        } finally {
            unknownContext.close();
        }

        Map<String, Object> unsafe = new LinkedHashMap<>(enabledProperties);
        unsafe.put(PREFIX + "replica-id", "sensitive-replica-name");
        unsafe.put(PREFIX + "heartbeat-interval-millis", "9000");
        unsafe.put(PREFIX + "lease-duration-millis", "10000");
        var unsafeContext = unrefreshedContext(unsafe, true, "test");
        try {
            assertThatThrownBy(unsafeContext::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Physical provider-inventory runtime configuration is invalid")
                    .hasMessageNotContaining("sensitive-replica-name");
        } finally {
            unsafeContext.close();
        }
    }

    @Test
    void actuatorHealthOmitsPrivateInventoryAndTransportIdentities() {
        try (var context = context(enabledProperties, true, "test")) {
            Map<String, Object> details = context.getBean(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryHealth.class)
                    .health().getDetails();
            assertThat(details)
                    .containsEntry("expectedReplicaCount", 1)
                    .containsEntry("readyReplicaCount", 1)
                    .doesNotContainKeys("replicaId", "expectedReplicaIds", "providerId",
                            "deploymentId", "publicationId", "etag", "uri",
                            "materialFingerprint", "generationFingerprint", "keyId",
                            "publicKey", "privateKey");
        }
    }

    private AnnotationConfigApplicationContext context(
            Map<String, Object> properties,
            boolean catalog,
            String... profiles) {
        var context = unrefreshedContext(properties, catalog, profiles);
        context.refresh();
        return context;
    }

    private AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> properties,
            boolean catalog,
            String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("physical-provider-inventory-runtime",
                        new LinkedHashMap<>(properties)));
        context.registerBean(ObjectMapper.class, () -> objectMapper);
        context.registerBean(TestRuntimeDatabase.class,
                () -> new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:physical-provider-inventory-runtime-"
                                + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "", 4)),
                definition -> definition.setDestroyMethodName("close"));
        if (catalog) {
            context.registerBean(TestSuiteStabilityPhysicalAttemptRuntimeAdapterCatalog.class,
                    () -> () -> Map.of(binding().identity(), provider));
        }
        context.register(
                TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration.class);
        return context;
    }

    private Map<String, Object> properties(boolean enabled) throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PREFIX + "enabled", Boolean.toString(enabled));
        if (!enabled) {
            return properties;
        }
        properties.put(PREFIX + "trust-domain", "provider.inventory.example");
        properties.put(PREFIX + "scope-id", "physical-attempt-providers");
        properties.put(PREFIX + "cohort-id", "release-2026-07-22");
        properties.put(PREFIX + "provider-protocol-version",
                "bloge.physical-attempt-provider.v1");
        properties.put(PREFIX + "accepted-policy-fingerprints", POLICY);
        properties.put(PREFIX + "signature-threshold", "1");
        properties.put(PREFIX + "authority-keys-json", keysJson(
                "deployment-a", "deployment-key-a", deployment));
        properties.put(PREFIX + "publication-uri", "http://127.0.0.1:"
                + server.getAddress().getPort() + "/inventory");
        properties.put(PREFIX + "refresh-interval-seconds", "10");
        properties.put(PREFIX + "request-timeout-millis", "1000");
        properties.put(PREFIX + "maximum-snapshot-age-seconds", "30");
        properties.put(PREFIX + "allow-insecure-loopback", "true");
        properties.put(PREFIX + "witness-domain", "provider.inventory.witness.example");
        properties.put(PREFIX + "witness-signature-threshold", "1");
        properties.put(PREFIX + "witness-authority-keys-json", keysJson(
                "witness-a", "witness-key-a", witness));
        properties.put(PREFIX + "replica-id", "replica-a");
        properties.put(PREFIX + "artifact-fingerprint", ARTIFACT);
        properties.put(PREFIX + "heartbeat-interval-millis", "1000");
        properties.put(PREFIX + "lease-duration-millis", "4000");
        properties.put(PREFIX + "record-retention-seconds", "10");
        return properties;
    }

    private String keysJson(String authorityId, String keyId, KeyPair pair) throws Exception {
        return objectMapper.writeValueAsString(List.of(Map.of(
                "authorityId", authorityId,
                "keyId", keyId,
                "publicKeyBase64", Base64.getEncoder().encodeToString(
                        pair.getPublic().getEncoded()),
                "enabled", true,
                "revoked", false)));
    }

    private TestSuiteStabilityPhysicalAttemptProviderInventoryPublication publication() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var inventoryMaterial = new TestSuiteStabilityPhysicalAttemptProviderInventory.Material(
                TestSuiteStabilityPhysicalAttemptProviderInventory.Material.SCHEMA_VERSION,
                "provider.inventory.example", "provider-inventory-1", 1,
                "physical-attempt-providers", "release-2026-07-22",
                "bloge.physical-attempt-provider.v1", POLICY, List.of(binding()),
                now.minusSeconds(60), now.minusSeconds(60), now.plusSeconds(3_600));
        String inventoryFingerprint = ProtocolFingerprint.of(objectMapper, inventoryMaterial);
        var inventory = new TestSuiteStabilityPhysicalAttemptProviderInventory(
                TestSuiteStabilityPhysicalAttemptProviderInventory.SCHEMA_VERSION,
                inventoryMaterial, inventoryFingerprint,
                signatures(inventoryFingerprint,
                        signer("deployment-a", "deployment-key-a", deployment, now)));
        var material = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.Material
                        .SCHEMA_VERSION,
                "provider.inventory.example", "publication-1", 1,
                "physical-attempt-providers", "release-2026-07-22",
                inventoryFingerprint, List.of("replica-a"),
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.State.ACTIVE,
                POLICY, "", now.minusSeconds(30), now.minusSeconds(30),
                now.plusSeconds(600), "");
        String publicationFingerprint = ProtocolFingerprint.of(objectMapper, material);
        var witnessMaterial = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessMaterial(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessMaterial
                        .SCHEMA_VERSION,
                "provider.inventory.witness.example", "checkpoint-1", 1,
                publicationFingerprint, "", now.minusSeconds(20), now.minusSeconds(20),
                now.plusSeconds(600));
        String witnessFingerprint = ProtocolFingerprint.of(objectMapper, witnessMaterial);
        var checkpoint = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessCheckpoint(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.WitnessCheckpoint
                        .SCHEMA_VERSION,
                witnessMaterial, witnessFingerprint, signatures(witnessFingerprint,
                signer("witness-a", "witness-key-a", witness, now)));
        return new TestSuiteStabilityPhysicalAttemptProviderInventoryPublication(
                TestSuiteStabilityPhysicalAttemptProviderInventoryPublication.SCHEMA_VERSION,
                inventory, material, publicationFingerprint,
                signatures(publicationFingerprint,
                        signer("deployment-a", "deployment-key-a", deployment, now)),
                checkpoint);
    }

    private static Binding binding() {
        return new Binding(Binding.SCHEMA_VERSION, "provider-a", "deployment-1",
                ARTIFACT, "observation-key-a", List.of(IsolationMode.PROCESS),
                5_000, 86_400_000);
    }

    private static List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures(
            String fingerprint,
            Signer... signers) {
        return java.util.Arrays.stream(signers).map(signer -> signer.sign(fingerprint))
                .sorted(Comparator.comparing(
                        TestSuiteStabilityServingInventory.AuthoritySignature::authorityId))
                .toList();
    }

    private static Signer signer(
            String authorityId, String keyId, KeyPair pair, Instant now) {
        return new Signer(authorityId, keyId, pair, now);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Signer(String authorityId, String keyId, KeyPair pair, Instant now) {
        private TestSuiteStabilityServingInventory.AuthoritySignature sign(String fingerprint) {
            try {
                Signature signature = Signature.getInstance("Ed25519");
                signature.initSign(pair.getPrivate());
                signature.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new TestSuiteStabilityServingInventory.AuthoritySignature(
                        authorityId, keyId, "Ed25519", now.minusSeconds(10),
                        Base64.getEncoder().encodeToString(signature.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static void assertRuntimeAbsent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(
                DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptProviderInventoryCohortMonitor.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptProviderInventoryHealth.class)).isEmpty();
    }
}
