package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority.LaneResolver;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.AuthoritySignature;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation.Material;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.State;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.WitnessCheckpoint;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.WitnessMaterial;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication.AuthorityKeyMaterial;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor.Generation;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import com.sun.net.httpserver.HttpServer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfigurationTest {

    private static final String FLEET_PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration
                    .FleetProperties.PREFIX + ".";
    private static final String DYNAMIC_PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                    .DynamicInventoryProperties.PREFIX + ".";
    private static final String SCOPE = "recovery-staging";
    private static final String FLEET = "bootstrap-recovery";
    private static final String ARTIFACT = "sha256:" + "a".repeat(64);
    private static final String POLICY = "sha256:" + "b".repeat(64);
    private static final String TRUST_DOMAIN = "fleet-inventory.example";
    private static final String WITNESS_DOMAIN = "fleet-witness.example";
    private static final String DEPLOYMENT_ROOT_DOMAIN = "fleet-deployment-root.example";
    private static final String WITNESS_ROOT_DOMAIN = "fleet-witness-root.example";
    private static final String ROOT_SET = "fleet-inventory-runtime-roots";
    private static final String ROOT_PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                    .ManagedTrustRootProperties.PREFIX + ".";
    private static final String EXTERNAL_PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties.PREFIX + ".";

    private ObjectMapper objectMapper;
    private Instant now;
    private KeyPair deployment;
    private KeyPair witness;
    private KeyPair deploymentRoot;
    private KeyPair witnessRoot;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        deployment = generator.generateKeyPair();
        witness = generator.generateKeyPair();
        deploymentRoot = generator.generateKeyPair();
        witnessRoot = generator.generateKeyPair();
    }

    @Test
    void managedModeBootstrapsDualHttpSourcesPersistsBothFloorsAndRebuilds()
            throws Exception {
        try (var inventorySource = source(activePublication());
             var rootSource = rootSource(trustRootPublication());
             var database = database()) {
            Map<String, Object> properties = managedProperties(
                    inventorySource.uri(), rootSource.uri());
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority inventory;
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                    roots;
            try (var first = context(properties, database, null, List.of(key -> null),
                    null, "test")) {
                inventory = first.getBean(
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                                .class);
                roots = first.getBean(
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                                .class);
                assertManagedRuntime(first, inventory, roots);
                assertThat(rootFloorTableCount(database)).isEqualTo(2);
            }
            assertThat(inventory.observation().status()).isEqualTo("CLOSED");
            assertThat(roots.snapshot().status()).isEqualTo("CLOSED");

            try (var rebuilt = context(properties, database, null, List.of(key -> null),
                    null, "test")) {
                assertManagedRuntime(rebuilt, rebuilt.getBean(
                                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                                        .class),
                        rebuilt.getBean(
                                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                                        .class));
            }
            assertThat(inventorySource.requests()).isEqualTo(2);
            assertThat(rootSource.requests()).isEqualTo(2);
        }
    }

    @Test
    void customExternalAnchorWrapsBothManagedStreamsAndProjectsCombinedByzantineTruth()
            throws Exception {
        try (var inventorySource = source(activePublication());
             var rootSource = rootSource(trustRootPublication());
             var database = database()) {
            Map<String, Object> properties = managedProperties(
                    inventorySource.uri(), rootSource.uri());
            externalProperties(properties, 0, 0);
            RecordingExternalAnchor anchor = new RecordingExternalAnchor(true);
            var context = unrefreshedContext(properties, database, null,
                    List.of(key -> null), null, "test");
            context.registerBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor.class,
                    () -> anchor);
            context.refresh();
            try {
                var inventory = context.getBean(
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                                .class);
                assertThat(anchor.heads).extracting(
                        TestSuiteStabilityExternalSequenceAnchor.Head::streamKind)
                        .containsExactly(
                                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                                        .SERVING_INVENTORY_TRUST_ROOT,
                                TestSuiteStabilityExternalSequenceAnchor.StreamKind
                                        .SERVING_INVENTORY_PUBLICATION);
                assertThat(inventory.descriptor().properties())
                        .containsEntry("externallyAnchoredPublicationFloor", true)
                        .containsEntry("byzantineQuorumAnchoredPublicationFloor", true)
                        .containsEntry("externallyAnchoredTrustRootFloor", true)
                        .containsEntry("byzantineQuorumAnchoredTrustRootFloor", true)
                        .containsEntry("externalInventoryNonEquivocation", true)
                        .containsEntry("byzantineQuorumInventoryNonEquivocation", true);
                assertThat(context.getBean(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                                .class).externallyAnchored()).isTrue();
                assertThat(context.getBean(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootFloor
                                .class).byzantineQuorumAnchored()).isTrue();
                var anchorHealth = context.getBean(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchorHealth
                                .class).health();
                assertThat(anchorHealth.getStatus()).isEqualTo(Status.UP);
                assertThat(anchorHealth.getDetails().toString())
                        .doesNotContain("endpoint", "stream", "fingerprint", "authorityId", "key");
                assertThat(inventorySource.requests()).isOne();
                assertThat(rootSource.requests()).isOne();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void configuredByzantinePolicyRejectsCrashFaultOnlyCustomAnchorBeforeNetworkOrState()
            throws Exception {
        try (var inventorySource = source(activePublication());
             var rootSource = rootSource(trustRootPublication());
             var database = database()) {
            Map<String, Object> properties = managedProperties(
                    inventorySource.uri(), rootSource.uri());
            externalProperties(properties, 1, 1);
            RecordingExternalAnchor anchor = new RecordingExternalAnchor(false);
            var context = unrefreshedContext(properties, database, null,
                    List.of(key -> null), null, "test");
            context.registerBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor.class,
                    () -> anchor);
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause().isInstanceOf(IllegalStateException.class)
                        .hasMessage(
                                "Recovery-fleet external non-equivocation anchor is unavailable "
                                        + "or unsafe");
                assertThat(anchor.heads).isEmpty();
                assertThat(inventorySource.requests()).isZero();
                assertThat(rootSource.requests()).isZero();
                assertThat(allRecoveryTables(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void managedModeRejectsStaticKeyMixingAndSharedSourceBeforeStateOrNetwork()
            throws Exception {
        try (var inventorySource = source(activePublication());
             var rootSource = rootSource(trustRootPublication());
             var database = database()) {
            Map<String, Object> mixed = managedProperties(
                    inventorySource.uri(), rootSource.uri());
            mixed.put(DYNAMIC_PREFIX + "trust-domain", TRUST_DOMAIN);
            var context = unrefreshedContext(mixed, database, null,
                    List.of(key -> null), null, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Dynamic recovery-fleet inventory configuration is invalid");
                assertThat(inventorySource.requests()).isZero();
                assertThat(rootSource.requests()).isZero();
                assertThat(allRecoveryTables(database)).isZero();
                assertThat(rootFloorTableCount(database)).isZero();
            } finally {
                context.close();
            }
        }

        try (var sharedSource = rootSource(trustRootPublication());
             var database = database()) {
            Map<String, Object> shared = managedProperties(
                    sharedSource.uri(), sharedSource.uri());
            var context = unrefreshedContext(shared, database, null,
                    List.of(key -> null), null, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Dynamic recovery-fleet inventory configuration is invalid");
                assertThat(sharedSource.requests()).isZero();
                assertThat(allRecoveryTables(database)).isZero();
                assertThat(rootFloorTableCount(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void enabledDynamicModeBootstrapsRealAuthorityFloorWorkerAndHealth() throws Exception {
        try (var source = source(activePublication()); var database = database()) {
            Map<String, Object> properties = dynamicProperties(source.uri());
            properties.put(DYNAMIC_PREFIX + "required", "true");
            var context = context(properties, database, null, List.of(key -> null), null,
                    "test");
            var authority = context.getBean(
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                            .class);
            var worker = context.getBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetWorker.class);
            try {
                assertThat(source.requests()).isOne();
                assertThat(authority.observation()).satisfies(observed -> {
                    assertThat(observed.available()).isTrue();
                    assertThat(observed.generation()).isEqualTo(17L);
                });
                assertThat(authority.descriptor().properties())
                        .containsEntry("automaticRefresh", true)
                        .containsEntry("signedRevocation", true)
                        .containsEntry("durableGenerationFloor", true)
                        .containsEntry("witnessedPublications", true);
                assertThat(context.getBean(
                        DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                                .class)).isNotNull();
                assertThat(context.getBean(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.class)
                        .runOnce().inventoryGeneration()).isEqualTo(17L);
                assertThat(context.getBean(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth.class)
                        .health().getStatus()).isEqualTo(Status.UP);
                assertThat(floorTableCount(database)).isEqualTo(1);
            } finally {
                context.close();
            }
            assertThat(authority.observation().status()).isEqualTo("CLOSED");
            assertThat(worker.runtimeSnapshot().closed()).isTrue();
        }
    }

    @Test
    void dynamicInventoryHealthDoesNotDependOnConfigurationRegistrationOrder()
            throws Exception {
        try (var source = source(activePublication()); var database = database()) {
            Map<String, Object> properties = dynamicProperties(source.uri());
            properties.put(DYNAMIC_PREFIX + "required", "true");
            var context = unrefreshedContext(properties, database, null,
                    List.of(key -> null), null, true, "test");
            context.refresh();
            try {
                assertThat(context.getBeansOfType(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth.class))
                        .hasSize(1);
                assertThat(context.getBean(
                        ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth.class)
                        .health().getStatus()).isEqualTo(Status.UP);
            } finally {
                context.close();
            }
        }
    }

    @Test
    void disabledDynamicModeCreatesNoAuthorityOrFloorAndKeepsTestFallback() {
        Map<String, Object> properties = fleetProperties();
        properties.put(DYNAMIC_PREFIX + "enabled", "false");
        try (var database = database(); var context = context(properties, database,
                emptyInventory(), List.of(), null, "test")) {
            assertThat(context.getBeansOfType(
                    DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                            .class)).isEmpty();
            assertThat(context.getBeansOfType(
                    DatabaseExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                            .class)).isEmpty();
            assertThat(context.getBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetScheduler.class)
                    .runOnce().inventoryGeneration()).isOne();
        }
    }

    @Test
    void productionPresencePhysicallyExcludesDynamicSourceWithoutNetworkOrTables()
            throws Exception {
        try (var source = source(activePublication()); var database = database()) {
            var context = context(dynamicProperties(source.uri()), database, null,
                    List.of(key -> null), null, "production", "test");
            try {
                assertThat(context.getBeansOfType(
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                                .class)).isEmpty();
                assertThat(source.requests()).isZero();
                assertThat(floorTableCount(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void productionPresencePhysicallyExcludesManagedRootAndInventorySources()
            throws Exception {
        try (var inventorySource = source(activePublication());
             var rootSource = rootSource(trustRootPublication());
             var database = database()) {
            var context = context(managedProperties(inventorySource.uri(), rootSource.uri()),
                    database, null, List.of(key -> null), null, "production", "test");
            try {
                assertThat(context.getBeansOfType(
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                                .class)).isEmpty();
                assertThat(context.getBeansOfType(
                        DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                                .class)).isEmpty();
                assertThat(inventorySource.requests()).isZero();
                assertThat(rootSource.requests()).isZero();
                assertThat(allRecoveryTables(database)).isZero();
                assertThat(rootFloorTableCount(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void requiredModeRejectsStaticFallbackBeforeCoordinatorStateExists() {
        Map<String, Object> properties = fleetProperties();
        properties.put(DYNAMIC_PREFIX + "enabled", "false");
        properties.put(DYNAMIC_PREFIX + "required", "true");
        try (var database = database()) {
            var context = unrefreshedContext(properties, database, emptyInventory(),
                    List.of(), null, "staging");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(
                                "Bootstrap-root recovery fleet runtime configuration is invalid");
                assertThat(allRecoveryTables(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void stagingCannotDisableDynamicRequirementOrUseInsecureLoopback() throws Exception {
        Map<String, Object> bypass = fleetProperties();
        bypass.put(DYNAMIC_PREFIX + "enabled", "false");
        bypass.put(DYNAMIC_PREFIX + "required", "false");
        try (var database = database()) {
            var context = unrefreshedContext(
                    bypass, database, emptyInventory(), List.of(), null, "staging");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(
                                "Bootstrap-root recovery fleet runtime configuration is invalid");
                assertThat(allRecoveryTables(database)).isZero();
            } finally {
                context.close();
            }
        }

        try (var source = source(activePublication()); var database = database()) {
            Map<String, Object> insecure = dynamicProperties(source.uri());
            insecure.put(DYNAMIC_PREFIX + "required", "true");
            insecure.put(ROOT_PREFIX + "enabled", "true");
            insecure.put(ROOT_PREFIX + "required", "true");
            insecure.put(ROOT_PREFIX + "trust-root-set-id", ROOT_SET);
            insecure.put(ROOT_PREFIX + "accepted-policy-fingerprints", POLICY);
            insecure.put(ROOT_PREFIX + "deployment-root-domain", DEPLOYMENT_ROOT_DOMAIN);
            insecure.put(ROOT_PREFIX + "deployment-root-signature-threshold", "1");
            insecure.put(ROOT_PREFIX + "deployment-root-authority-keys-json",
                    keysJson("deployment-root", "deployment-root-key", deploymentRoot));
            insecure.put(ROOT_PREFIX + "witness-root-domain", WITNESS_ROOT_DOMAIN);
            insecure.put(ROOT_PREFIX + "witness-root-signature-threshold", "1");
            insecure.put(ROOT_PREFIX + "witness-root-authority-keys-json",
                    keysJson("witness-root", "witness-root-key", witnessRoot));
            insecure.put(ROOT_PREFIX + "publication-uri", "https://roots.example/current");
            var context = unrefreshedContext(
                    insecure, database, null, List.of(key -> null), null, "staging");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(
                                "Dynamic recovery-fleet inventory configuration is invalid");
                assertThat(source.requests()).isZero();
                assertThat(allRecoveryTables(database)).isZero();
            } finally {
                context.close();
            }
        }

        try (var rootSource = rootSource(trustRootPublication());
             var database = database()) {
            Map<String, Object> insecureRoot = managedProperties(
                    "https://inventory.example/current", rootSource.uri());
            insecureRoot.put(DYNAMIC_PREFIX + "allow-insecure-loopback", "false");
            var context = unrefreshedContext(
                    insecureRoot, database, null, List.of(key -> null), null, "staging");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(
                                "Bootstrap-root recovery fleet runtime configuration is invalid");
                assertThat(rootSource.requests()).isZero();
                assertThat(allRecoveryTables(database)).isZero();
                assertThat(rootFloorTableCount(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void malformedUnknownAndDisabledHalfConfigurationFailBeforeStateOrNetwork()
            throws Exception {
        try (var source = source(activePublication())) {
            Map<String, Object> unknown = dynamicProperties(source.uri());
            unknown.put(DYNAMIC_PREFIX + "signer-private-key", "forbidden");
            assertStatelessFailure(unknown, source);
        }

        Map<String, Object> halfConfigured = fleetProperties();
        halfConfigured.put(DYNAMIC_PREFIX + "enabled", "false");
        halfConfigured.put(DYNAMIC_PREFIX + "publication-uri", "https://unused.example");
        try (var database = database()) {
            var context = unrefreshedContext(halfConfigured, database, emptyInventory(),
                    List.of(), null, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(
                                "Dynamic recovery-fleet inventory configuration is invalid");
                assertThat(allRecoveryTables(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void missingOrMultipleLaneResolversFailBeforeFloorAndRemoteBootstrap()
            throws Exception {
        try (var missingSource = source(activePublication())) {
            assertResolverFailure(dynamicProperties(missingSource.uri()), missingSource,
                    List.of());
        }
        try (var duplicateSource = source(activePublication())) {
            assertResolverFailure(dynamicProperties(duplicateSource.uri()), duplicateSource,
                    List.of(key -> null, key -> null));
        }
    }

    @Test
    void customFloorMustBeDurableAndIsCheckedBeforeRemoteBootstrap() throws Exception {
        var nonDurable = new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor() {
                    @Override
                    public void accept(Generation generation) {
                    }

                    @Override
                    public boolean durable() {
                        return false;
                    }
                };
        try (var source = source(activePublication()); var database = database()) {
            var context = unrefreshedContext(dynamicProperties(source.uri()), database, null,
                    List.of(key -> null), nonDurable, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("durable publication floor");
                assertThat(source.requests()).isZero();
                assertThat(floorTableCount(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    @Test
    void dynamicModeNeverSilentlyFallsBackToAnExtraCallerInventory() throws Exception {
        try (var source = source(activePublication()); var database = database()) {
            var context = unrefreshedContext(dynamicProperties(source.uri()), database,
                    emptyInventory(), List.of(key -> null), null, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .hasStackTraceContaining("expected single matching bean")
                        .hasStackTraceContaining(
                                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.class
                                        .getName());
                assertThat(source.requests()).isZero();
                assertThat(allRecoveryTables(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    private void assertStatelessFailure(Map<String, Object> properties, SignedSource source) {
        try (var database = database()) {
            var context = unrefreshedContext(properties, database, null,
                    List.of(key -> null), null, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .hasStackTraceContaining("signer-private-key")
                        .hasStackTraceContaining("ignoreUnknownFields");
                assertThat(source.requests()).isZero();
                assertThat(allRecoveryTables(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    private void assertResolverFailure(
            Map<String, Object> properties,
            SignedSource source,
            List<LaneResolver> resolvers) {
        try (var database = database()) {
            var context = unrefreshedContext(properties, database, null, resolvers,
                    null, "test");
            try {
                assertThatThrownBy(context::refresh)
                        .rootCause().isInstanceOf(IllegalArgumentException.class)
                        .hasMessage(
                                "Dynamic recovery-fleet inventory configuration is invalid");
                assertThat(source.requests()).isZero();
                assertThat(allRecoveryTables(database)).isZero();
            } finally {
                context.close();
            }
        }
    }

    private AnnotationConfigApplicationContext context(
            Map<String, Object> properties,
            TestRuntimeDatabase database,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            List<LaneResolver> resolvers,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor floor,
            String... profiles) {
        var context = unrefreshedContext(
                properties, database, inventory, resolvers, floor, profiles);
        context.refresh();
        return context;
    }

    private AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> properties,
            TestRuntimeDatabase database,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            List<LaneResolver> resolvers,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor floor,
            String... profiles) {
        return unrefreshedContext(properties, database, inventory, resolvers, floor,
                false, profiles);
    }

    private AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> properties,
            TestRuntimeDatabase database,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory inventory,
            List<LaneResolver> resolvers,
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor floor,
            boolean dynamicConfigurationFirst,
            String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("dynamic-recovery-fleet", new LinkedHashMap<>(properties)));
        context.registerBean(ObjectMapper.class, () -> objectMapper);
        context.registerBean(TestRuntimeDatabase.class, () -> database,
                definition -> definition.setDestroyMethodName(""));
        if (inventory != null) {
            context.registerBean(ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.class,
                    () -> inventory);
        }
        for (int index = 0; index < resolvers.size(); index++) {
            int resolverIndex = index;
            context.registerBean("testLaneResolver" + index, LaneResolver.class,
                    () -> resolvers.get(resolverIndex));
        }
        if (floor != null) {
            context.registerBean(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublicationFloor
                            .class,
                    () -> floor);
        }
        if (dynamicConfigurationFirst) {
            context.register(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                            .class,
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration.class);
        } else {
            context.register(
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration.class,
                    ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                            .class);
        }
        return context;
    }

    private Map<String, Object> dynamicProperties(String uri) throws Exception {
        Map<String, Object> properties = fleetProperties();
        properties.put(DYNAMIC_PREFIX + "enabled", "true");
        properties.put(DYNAMIC_PREFIX + "required", "false");
        properties.put(DYNAMIC_PREFIX + "deployment-scope-id", SCOPE);
        properties.put(DYNAMIC_PREFIX + "artifact-fingerprint", ARTIFACT);
        properties.put(DYNAMIC_PREFIX + "trust-domain", TRUST_DOMAIN);
        properties.put(DYNAMIC_PREFIX + "accepted-policy-fingerprints", POLICY);
        properties.put(DYNAMIC_PREFIX + "signature-threshold", "1");
        properties.put(DYNAMIC_PREFIX + "authority-keys-json",
                keysJson("deployment", "deployment-key", deployment));
        properties.put(DYNAMIC_PREFIX + "publication-uri", uri);
        properties.put(DYNAMIC_PREFIX + "refresh-interval-seconds", "30");
        properties.put(DYNAMIC_PREFIX + "request-timeout-millis", "1000");
        properties.put(DYNAMIC_PREFIX + "maximum-snapshot-age-seconds", "60");
        properties.put(DYNAMIC_PREFIX + "allow-insecure-loopback", "true");
        properties.put(DYNAMIC_PREFIX + "witness-domain", WITNESS_DOMAIN);
        properties.put(DYNAMIC_PREFIX + "witness-signature-threshold", "1");
        properties.put(DYNAMIC_PREFIX + "witness-authority-keys-json",
                keysJson("witness", "witness-key", witness));
        return properties;
    }

    private Map<String, Object> managedProperties(String inventoryUri, String rootUri)
            throws Exception {
        Map<String, Object> properties = dynamicProperties(inventoryUri);
        properties.put(DYNAMIC_PREFIX + "required", "true");
        properties.put(DYNAMIC_PREFIX + "trust-domain", "");
        properties.put(DYNAMIC_PREFIX + "signature-threshold", "0");
        properties.put(DYNAMIC_PREFIX + "authority-keys-json", "[]");
        properties.put(DYNAMIC_PREFIX + "witness-domain", "");
        properties.put(DYNAMIC_PREFIX + "witness-signature-threshold", "0");
        properties.put(DYNAMIC_PREFIX + "witness-authority-keys-json", "[]");
        properties.put(ROOT_PREFIX + "enabled", "true");
        properties.put(ROOT_PREFIX + "required", "true");
        properties.put(ROOT_PREFIX + "trust-root-set-id", ROOT_SET);
        properties.put(ROOT_PREFIX + "accepted-policy-fingerprints", POLICY);
        properties.put(ROOT_PREFIX + "deployment-root-domain", DEPLOYMENT_ROOT_DOMAIN);
        properties.put(ROOT_PREFIX + "deployment-root-signature-threshold", "1");
        properties.put(ROOT_PREFIX + "deployment-root-authority-keys-json",
                keysJson("deployment-root", "deployment-root-key", deploymentRoot));
        properties.put(ROOT_PREFIX + "witness-root-domain", WITNESS_ROOT_DOMAIN);
        properties.put(ROOT_PREFIX + "witness-root-signature-threshold", "1");
        properties.put(ROOT_PREFIX + "witness-root-authority-keys-json",
                keysJson("witness-root", "witness-root-key", witnessRoot));
        properties.put(ROOT_PREFIX + "publication-uri", rootUri);
        properties.put(ROOT_PREFIX + "refresh-interval-seconds", "30");
        properties.put(ROOT_PREFIX + "request-timeout-millis", "1000");
        properties.put(ROOT_PREFIX + "unknown-key-refresh-interval-seconds", "5");
        properties.put(ROOT_PREFIX + "maximum-snapshot-age-seconds", "60");
        properties.put(ROOT_PREFIX + "allow-insecure-loopback", "true");
        return properties;
    }

    private void externalProperties(
            Map<String, Object> properties,
            int maximumFaults,
            int minimumFaults) throws Exception {
        properties.put(EXTERNAL_PREFIX + "enabled", "true");
        properties.put(EXTERNAL_PREFIX + "required", "true");
        properties.put(EXTERNAL_PREFIX + "trust-domain", "recovery-notary.example");
        properties.put(EXTERNAL_PREFIX + "anchor-set-id", "recovery-notaries");
        properties.put(EXTERNAL_PREFIX + "signature-threshold",
                maximumFaults > 0 ? "3" : "1");
        properties.put(EXTERNAL_PREFIX + "maximum-faults", maximumFaults);
        properties.put(EXTERNAL_PREFIX + "minimum-faults", minimumFaults);
        properties.put(EXTERNAL_PREFIX + "authority-keys-json",
                keysJson("notary", "notary-key", deploymentRoot));
        properties.put(EXTERNAL_PREFIX + "endpoints-json",
                "[{\"authorityId\":\"notary\",\"failureDomain\":\"zone-a\","
                        + "\"uri\":\"http://127.0.0.1:1/checkpoint\"}]");
        properties.put(EXTERNAL_PREFIX + "request-timeout-millis", "1000");
        properties.put(EXTERNAL_PREFIX + "clock-skew-seconds", "5");
        properties.put(EXTERNAL_PREFIX + "maximum-receipt-lifetime-seconds", "15");
        properties.put(EXTERNAL_PREFIX + "allow-insecure-loopback", "true");
    }

    private static void assertManagedRuntime(
            AnnotationConfigApplicationContext context,
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority inventory,
            DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                    roots) {
        assertThat(roots.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.available()).isTrue();
            assertThat(snapshot.status()).isEqualTo("HEALTHY");
            assertThat(snapshot.sequence()).isOne();
        });
        assertThat(inventory.observation()).satisfies(observed -> {
            assertThat(observed.available()).isTrue();
            assertThat(observed.generation()).isEqualTo(17L);
        });
        assertThat(inventory.descriptor().properties())
                .containsEntry("managedTrustRootRefresh", true)
                .containsEntry("managedTrustRootAvailable", true)
                .containsEntry("managedTrustRootStatus", "HEALTHY")
                .containsEntry("managedTrustRootSequence", 1L)
                .containsEntry("atomicDualTrustRootPublication", true)
                .containsEntry("durableTrustRootFloor", true);
        assertThat(context.getBean(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootHealth.class)
                .health().getStatus()).isEqualTo(Status.UP);
        assertThat(context.getBean(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryHealth.class)
                .health().getStatus()).isEqualTo(Status.UP);
    }

    private static Map<String, Object> fleetProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(FLEET_PREFIX + "enabled", "true");
        properties.put(FLEET_PREFIX + "fleet-id", FLEET);
        properties.put(FLEET_PREFIX + "worker-id", "replica-a");
        properties.put(FLEET_PREFIX + "partition-count", "4");
        properties.put(FLEET_PREFIX + "lease-duration-seconds", "3");
        properties.put(FLEET_PREFIX + "maximum-lanes-per-cycle", "8");
        properties.put(FLEET_PREFIX + "initial-delay-millis", "300000");
        properties.put(FLEET_PREFIX + "poll-interval-millis", "1000");
        properties.put(FLEET_PREFIX + "maximum-cycle-duration-millis", "10000");
        properties.put(FLEET_PREFIX + "drain-timeout-millis", "1000");
        properties.put(ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration
                .RecoveryFleetSloProperties.PREFIX + ".startup-grace-millis", "301000");
        properties.put(ExternalSequenceAnchorBootstrapRootRecoveryFleetRuntimeConfiguration
                .RecoveryFleetSloProperties.PREFIX
                + ".maximum-poll-success-age-millis", "2000");
        return properties;
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
            activePublication() {
        Material inventoryMaterial = new Material(Material.SCHEMA_VERSION, TRUST_DOMAIN,
                "inventory-17", 17L, SCOPE, FLEET, ARTIFACT, 4, List.of(), POLICY,
                now.minusSeconds(120), now.minusSeconds(120), now.plusSeconds(3_600));
        String inventoryFingerprint = ProtocolFingerprint.of(objectMapper, inventoryMaterial);
        var inventory = new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAttestation
                        .SCHEMA_VERSION,
                inventoryMaterial, inventoryFingerprint,
                List.of(sign("deployment", "deployment-key", deployment,
                        inventoryFingerprint)));
        var publicationMaterial = new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.Material(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication.Material
                        .SCHEMA_VERSION,
                TRUST_DOMAIN, "publication-1", SCOPE, FLEET, 1L, inventoryFingerprint,
                State.ACTIVE, POLICY, "", now.minusSeconds(60), now.minusSeconds(60),
                now.plusSeconds(600), "");
        String publicationFingerprint = ProtocolFingerprint.of(
                objectMapper, publicationMaterial);
        var witnessMaterial = new WitnessMaterial(WitnessMaterial.SCHEMA_VERSION,
                WITNESS_DOMAIN, "checkpoint-1", SCOPE, FLEET, 1L,
                publicationFingerprint, "", now.minusSeconds(30), now.minusSeconds(30),
                now.plusSeconds(600));
        String witnessFingerprint = ProtocolFingerprint.of(objectMapper, witnessMaterial);
        var checkpoint = new WitnessCheckpoint(WitnessCheckpoint.SCHEMA_VERSION,
                witnessMaterial, witnessFingerprint,
                List.of(sign("witness", "witness-key", witness, witnessFingerprint)));
        return new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                inventory, publicationMaterial, publicationFingerprint,
                List.of(sign("deployment", "deployment-key", deployment,
                        publicationFingerprint)), checkpoint);
    }

    private ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
            trustRootPublication() {
        var material = new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                .Material(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .Material.SCHEMA_VERSION,
                ROOT_SET, 1L, "", SCOPE, FLEET,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                DEPLOYMENT_ROOT_DOMAIN, WITNESS_ROOT_DOMAIN, TRUST_DOMAIN, WITNESS_DOMAIN,
                1, 1,
                List.of(keyMaterial("deployment", "deployment-key", deployment)),
                List.of(keyMaterial("witness", "witness-key", witness)),
                POLICY, now.minusSeconds(60), now.minusSeconds(60), now.plusSeconds(3_600));
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        return new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .SCHEMA_VERSION,
                material, fingerprint,
                List.of(sign("deployment-root", "deployment-root-key", deploymentRoot,
                        fingerprint)),
                List.of(sign("witness-root", "witness-root-key", witnessRoot,
                        fingerprint)));
    }

    private AuthorityKeyMaterial keyMaterial(
            String authorityId,
            String keyId,
            KeyPair keyPair) {
        return new AuthorityKeyMaterial(authorityId, keyId,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                now.minusSeconds(3_600), now.plusSeconds(7_200), true, false);
    }

    private AuthoritySignature sign(
            String authorityId,
            String keyId,
            KeyPair keyPair,
            String fingerprint) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            return new AuthoritySignature(authorityId, keyId, "Ed25519",
                    now.minusSeconds(20), Base64.getEncoder().encodeToString(signer.sign()));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private String keysJson(String authorityId, String keyId, KeyPair keyPair)
            throws Exception {
        return objectMapper.writeValueAsString(List.of(Map.of(
                "authorityId", authorityId,
                "keyId", keyId,
                "publicKeyBase64", Base64.getEncoder().encodeToString(
                        keyPair.getPublic().getEncoded()),
                "enabled", true,
                "revoked", false)));
    }

    private SignedSource source(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication publication)
            throws Exception {
        return source(publication,
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .MEDIA_TYPE,
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryAuthority
                        .PROTOCOL_HEADER,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryPublication
                        .SCHEMA_VERSION,
                "\"publication-1\"");
    }

    private SignedSource rootSource(
            ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                    publication) throws Exception {
        return source(publication,
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                        .MEDIA_TYPE,
                DynamicExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootAuthority
                        .PROTOCOL_HEADER,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventoryTrustRootPublication
                        .SCHEMA_VERSION,
                "\"root-generation-1\"");
    }

    private SignedSource source(
            Object publication,
            String mediaType,
            String protocolHeader,
            String protocolVersion,
            String etag) throws Exception {
        byte[] body = objectMapper.writeValueAsBytes(publication);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/inventory", exchange -> {
            requests.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", mediaType);
            exchange.getResponseHeaders().set(protocolHeader, protocolVersion);
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return new SignedSource(server, requests);
    }

    private static TestRuntimeDatabase database() {
        return new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:dynamic-recovery-fleet-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 4));
    }

    private static ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory emptyInventory() {
        return () -> new ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetInventory.Snapshot
                        .SCHEMA_VERSION,
                1L, List.of());
    }

    private static int floorTableCount(TestRuntimeDatabase database) {
        return database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME = 'RG_EXT_ANCHOR_RECOVERY_INVENTORY_FLOORS'",
                Integer.class);
    }

    private static int allRecoveryTables(TestRuntimeDatabase database) {
        return database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_NAME LIKE 'RG_EXT_ANCHOR_RECOVERY_%' "
                        + "OR TABLE_NAME LIKE 'RG_EXTERNAL_SEQUENCE_ANCHOR_%'",
                Integer.class);
    }

    private static int rootFloorTableCount(TestRuntimeDatabase database) {
        return database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME IN "
                        + "('RG_TEST_SUITE_STABILITY_INVENTORY_TRUST_ROOT_FLOOR_LOCKS', "
                        + "'RG_TEST_SUITE_STABILITY_INVENTORY_TRUST_ROOT_FLOORS')",
                Integer.class);
    }

    private record SignedSource(HttpServer server, AtomicInteger requestCount)
            implements AutoCloseable {

        private String uri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/inventory";
        }

        private int requests() {
            return requestCount.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    private static final class RecordingExternalAnchor
            implements ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalSequenceAnchor {

        private final boolean byzantine;
        private final List<TestSuiteStabilityExternalSequenceAnchor.Head> heads =
                new java.util.ArrayList<>();

        private RecordingExternalAnchor(boolean byzantine) {
            this.byzantine = byzantine;
        }

        @Override
        public void accept(TestSuiteStabilityExternalSequenceAnchor.Head head) {
            heads.add(head);
        }

        @Override
        public TestSuiteStabilityExternalSequenceAnchor.Descriptor descriptor() {
            int authorities = byzantine ? 4 : 1;
            return new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                    TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                    true, true, true, byzantine, authorities, byzantine ? 3 : 1,
                    byzantine ? 1 : 0, authorities, Map.of());
        }

        @Override
        public TestSuiteStabilityExternalSequenceAnchor.Snapshot snapshot() {
            int authorities = byzantine ? 4 : 1;
            return new TestSuiteStabilityExternalSequenceAnchor.Snapshot(
                    TestSuiteStabilityExternalSequenceAnchor.Snapshot.SCHEMA_VERSION,
                    true, "HEALTHY", null, heads.size(), 0, 0, authorities,
                    byzantine ? 3 : 1, byzantine ? 1 : 0, authorities);
        }
    }
}
