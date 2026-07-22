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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
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
    private static final String EXTERNAL_PREFIX =
            TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration.Properties
                    .EXTERNAL_ANCHOR_PREFIX + ".";
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
                    .containsEntry("durablePublicationFloor", true)
                    .containsEntry("externalNonEquivocation", false)
                    .containsEntry("byzantineQuorumNonEquivocation", false);
            assertThat(context.getBeansOfType(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor.class))
                    .isEmpty();
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
    void explicitPhysicalDomainAnchorWrapsFloorAndPublishesByzantineTruth() throws Exception {
        Map<String, Object> properties = new LinkedHashMap<>(enabledProperties);
        externalProperties(properties, 1, 1);
        var anchor = anchor(true, true);

        try (var context = context(properties, true, anchor, "test")) {
            var authority = context.getBean(
                    DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.class);
            assertThat(context.getBean(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor.class))
                    .isInstanceOf(
                            ExternallyAnchoredTestSuiteStabilityPhysicalAttemptProviderInventoryPublicationFloor
                                    .class);
            assertThat(authority.descriptor().properties())
                    .containsEntry("externalNonEquivocation", true)
                    .containsEntry("byzantineQuorumNonEquivocation", true);
            assertThat(context.getBean(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchorHealth
                            .class).health().getStatus()).isEqualTo(Status.UP);
        }
    }

    @Test
    void defaultTestAdapterBuildsOneStaticChallengeBoundPhysicalDomainAnchor()
            throws Exception {
        var configuration =
                new TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration();
        var external = new
                ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties(
                true, true, "physical.notary.example", "physical-notaries",
                1, 0, 0, notaryKeysJson(),
                "[{\"authorityId\":\"notary-a\",\"failureDomain\":\"region-a\","
                        + "\"uri\":\"https://notary.example/v1/append\"}]",
                1_000L, 5L, 15L, false,
                RecoveryFleetPublicationTransportProperties.disabled(),
                null);
        var properties = new
                TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration.Properties(
                true, "provider.inventory.example", "physical-attempt-providers",
                "release-2026-07-22", "bloge.physical-attempt-provider.v1", POLICY,
                1, keysJson("deployment-a", "deployment-key-a", deployment),
                "http://127.0.0.1:1/inventory", 10L, 1_000L, 30L, true,
                "provider.inventory.witness.example", 1,
                keysJson("witness-a", "witness-key-a", witness),
                "replica-a", ARTIFACT, 1_000L, 4_000L, 10L, external);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        ObjectProvider<ControlPlaneCertificateRotationRuntime> rotations = mock(
                ObjectProvider.class);

        var anchor = configuration.physicalProviderInventoryExternalSequenceAnchor(
                objectMapper, environment, mock(TestRuntimeDatabase.class), properties,
                configuration.physicalProviderInventoryRuntimePreflight(
                        properties, environment), reference -> "unused".toCharArray(), rotations);
        try {
            assertThat(anchor.descriptor())
                    .extracting(
                            TestSuiteStabilityExternalSequenceAnchor.Descriptor::available,
                            TestSuiteStabilityExternalSequenceAnchor.Descriptor::challengeBound,
                            TestSuiteStabilityExternalSequenceAnchor.Descriptor::authorityCount,
                            TestSuiteStabilityExternalSequenceAnchor.Descriptor
                                    ::signatureThreshold)
                    .containsExactly(true, true, 1, 1);
        } finally {
            anchor.close();
        }
    }

    @Test
    void profileMetadataExposesTheCompletePhysicalExternalAnchorContract() throws Exception {
        Map<String, Object> test = externalAnchorMetadata("application-test.yml");
        Map<String, Object> staging = externalAnchorMetadata("application-staging.yml");

        assertThat(test).hasSize(82);
        assertThat(test.keySet()).containsExactlyInAnyOrderElementsOf(staging.keySet());
        assertThat(test.keySet()).contains(
                EXTERNAL_PREFIX + "enabled",
                EXTERNAL_PREFIX + "required",
                EXTERNAL_PREFIX + "transport.certificate-identity-required",
                EXTERNAL_PREFIX + "managed-trust.enabled",
                EXTERNAL_PREFIX + "managed-trust.transport.certificate-identity-required",
                EXTERNAL_PREFIX + "managed-trust.bootstrap-roots.enabled",
                EXTERNAL_PREFIX
                        + "managed-trust.bootstrap-roots.transport.certificate-identity-required");
        assertThat(test.values()).allSatisfy(value -> assertThat(value)
                .isInstanceOf(String.class)
                .asString()
                .startsWith("${RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_EXTERNAL_ANCHOR_"));
        Map<String, Object> expectedStaging = new LinkedHashMap<>(test);
        expectedStaging.put(EXTERNAL_PREFIX + "transport.certificate-identity-required",
                "${RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_EXTERNAL_ANCHOR_"
                        + "TRANSPORT_ENABLED:false}");
        expectedStaging.put(
                EXTERNAL_PREFIX + "managed-trust.transport.certificate-identity-required",
                "${RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_EXTERNAL_ANCHOR_"
                        + "TRUST_TRANSPORT_ENABLED:false}");
        expectedStaging.put(EXTERNAL_PREFIX
                        + "managed-trust.bootstrap-roots.transport.certificate-identity-required",
                "${RG_TEST_PHYSICAL_ATTEMPT_PROVIDER_INVENTORY_EXTERNAL_ANCHOR_"
                        + "BOOTSTRAP_ROOT_TRANSPORT_ENABLED:false}");
        assertThat(staging).isEqualTo(expectedStaging);
    }

    @Test
    void stagingRequiresWorkloadIdentityOnEveryExternalAnchorTransport() {
        var configuration =
                new TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration();
        var staging = new MockEnvironment();
        staging.setActiveProfiles("staging");

        assertThatThrownBy(() -> configuration.physicalProviderInventoryRuntimePreflight(
                stagingProperties(false, true, true), staging))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Physical provider-inventory runtime configuration is invalid");
        assertThatThrownBy(() -> configuration.physicalProviderInventoryRuntimePreflight(
                stagingProperties(true, false, true), staging))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Physical provider-inventory runtime configuration is invalid");
        assertThatThrownBy(() -> configuration.physicalProviderInventoryRuntimePreflight(
                stagingProperties(true, true, false), staging))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Physical provider-inventory runtime configuration is invalid");

        assertThat(configuration.physicalProviderInventoryRuntimePreflight(
                stagingProperties(true, true, true), staging).staging()).isTrue();
    }

    @Test
    void hiddenInvalidDefaultAndUnsafePhysicalDomainAnchorsFailClosed() throws Exception {
        var hidden = unrefreshedContext(enabledProperties, true, anchor(true, true), "test");
        try {
            assertThatThrownBy(hidden::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("requires exactly one anchor");
        } finally {
            hidden.close();
        }

        Map<String, Object> external = new LinkedHashMap<>(enabledProperties);
        externalProperties(external, 1, 1);
        var invalidDefault = unrefreshedContext(external, true,
                (TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor) null,
                "test");
        try {
            assertThatThrownBy(invalidDefault::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("quorum policy is invalid");
        } finally {
            invalidDefault.close();
        }

        var unsafe = unrefreshedContext(external, true, anchor(true, false), "test");
        try {
            assertThatThrownBy(unsafe::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("unavailable or unsafe");
        } finally {
            unsafe.close();
        }
    }

    @Test
    void stagingRejectsLocalFloorAndStaticNotaryTrustDuringPreflight() throws Exception {
        var localOnly = unrefreshedContext(enabledProperties, true,
                (TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor) null,
                "staging");
        try {
            assertThatThrownBy(localOnly::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Physical provider-inventory runtime configuration is invalid");
        } finally {
            localOnly.close();
        }

        Map<String, Object> staticAnchor = new LinkedHashMap<>(enabledProperties);
        staticAnchor.put(PREFIX + "allow-insecure-loopback", "false");
        staticAnchor.put(PREFIX + "publication-uri", "https://inventory.example/current");
        externalProperties(staticAnchor, 1, 1);
        var staticTrust = unrefreshedContext(
                staticAnchor, true, anchor(true, true), "staging");
        try {
            assertThatThrownBy(staticTrust::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Physical provider-inventory runtime configuration is invalid");
        } finally {
            staticTrust.close();
        }
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
        return context(properties, catalog, null, profiles);
    }

    private AnnotationConfigApplicationContext context(
            Map<String, Object> properties,
            boolean catalog,
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor anchor,
            String... profiles) {
        var context = unrefreshedContext(properties, catalog, anchor, profiles);
        context.refresh();
        return context;
    }

    private AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> properties,
            boolean catalog,
            String... profiles) {
        return unrefreshedContext(properties, catalog, null, profiles);
    }

    private AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> properties,
            boolean catalog,
            TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor anchor,
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
        if (anchor != null) {
            context.registerBean(
                    TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor.class,
                    () -> anchor);
        }
        context.register(
                TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration.class);
        return context;
    }

    private static Map<String, Object> externalAnchorMetadata(String resource) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        var sources = new YamlPropertySourceLoader().load(
                resource, new FileSystemResource(Path.of(
                        "src", "main", "resources", resource)));
        for (var source : sources) {
            assertThat(source).isInstanceOf(EnumerablePropertySource.class);
            var enumerable = (EnumerablePropertySource<?>) source;
            Arrays.stream(enumerable.getPropertyNames())
                    .filter(name -> name.startsWith(EXTERNAL_PREFIX))
                    .forEach(name -> result.put(name, enumerable.getProperty(name)));
        }
        return Map.copyOf(result);
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration.Properties
            stagingProperties(
            boolean notaryIdentity,
            boolean managedTrustIdentity,
            boolean bootstrapRootIdentity) {
        var properties = mock(
                TestSuiteStabilityPhysicalAttemptProviderInventoryRuntimeConfiguration
                        .Properties.class);
        var anchor = mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties.class);
        var managedTrust = mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                        .ManagedTrustProperties.class);
        var bootstrapRoots = mock(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetExternalAnchorProperties
                        .BootstrapRootProperties.class);
        var notaryTransport = mock(RecoveryFleetPublicationTransportProperties.class);
        var managedTrustTransport = mock(RecoveryFleetPublicationTransportProperties.class);
        var bootstrapRootTransport = mock(RecoveryFleetPublicationTransportProperties.class);

        when(properties.allowInsecureLoopback()).thenReturn(false);
        when(properties.externalAnchor()).thenReturn(anchor);
        when(anchor.enabled()).thenReturn(true);
        when(anchor.required()).thenReturn(true);
        when(anchor.maximumFaults()).thenReturn(1);
        when(anchor.minimumFaults()).thenReturn(1);
        when(anchor.allowInsecureLoopback()).thenReturn(false);
        when(anchor.transport()).thenReturn(notaryTransport);
        when(anchor.managedTrust()).thenReturn(managedTrust);
        when(managedTrust.enabled()).thenReturn(true);
        when(managedTrust.required()).thenReturn(true);
        when(managedTrust.allowInsecureLoopback()).thenReturn(false);
        when(managedTrust.transport()).thenReturn(managedTrustTransport);
        when(managedTrust.bootstrapRoots()).thenReturn(bootstrapRoots);
        when(bootstrapRoots.enabled()).thenReturn(true);
        when(bootstrapRoots.required()).thenReturn(true);
        when(bootstrapRoots.allowInsecureLoopback()).thenReturn(false);
        when(bootstrapRoots.transport()).thenReturn(bootstrapRootTransport);
        requireTransport(notaryTransport, notaryIdentity);
        requireTransport(managedTrustTransport, managedTrustIdentity);
        requireTransport(bootstrapRootTransport, bootstrapRootIdentity);
        return properties;
    }

    private static void requireTransport(
            RecoveryFleetPublicationTransportProperties transport,
            boolean certificateIdentityBound) {
        when(transport.enabled()).thenReturn(true);
        when(transport.required()).thenReturn(true);
        when(transport.certificateIdentityBound()).thenReturn(certificateIdentityBound);
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

    private void externalProperties(
            Map<String, Object> properties,
            int maximumFaults,
            int minimumFaults) throws Exception {
        properties.put(EXTERNAL_PREFIX + "enabled", "true");
        properties.put(EXTERNAL_PREFIX + "required", "true");
        properties.put(EXTERNAL_PREFIX + "trust-domain", "physical.notary.example");
        properties.put(EXTERNAL_PREFIX + "anchor-set-id", "physical-notaries");
        properties.put(EXTERNAL_PREFIX + "signature-threshold",
                maximumFaults > 0 ? "3" : "1");
        properties.put(EXTERNAL_PREFIX + "maximum-faults", maximumFaults);
        properties.put(EXTERNAL_PREFIX + "minimum-faults", minimumFaults);
        properties.put(EXTERNAL_PREFIX + "authority-keys-json",
                keysJson("notary-a", "notary-key-a", deployment));
        properties.put(EXTERNAL_PREFIX + "endpoints-json",
                "[{\"authorityId\":\"notary-a\",\"failureDomain\":\"region-a\","
                        + "\"uri\":\"https://notary.example/v1/append\"}]");
    }

    private static TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor anchor(
            boolean available,
            boolean byzantine) {
        var anchor = mock(
                TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor.class);
        int authorityCount = byzantine ? 4 : 1;
        int threshold = byzantine ? 3 : 1;
        int faults = byzantine ? 1 : 0;
        when(anchor.descriptor()).thenReturn(new TestSuiteStabilityExternalSequenceAnchor.Descriptor(
                TestSuiteStabilityExternalSequenceAnchor.Descriptor.SCHEMA_VERSION,
                available, available, available, available && byzantine,
                available ? authorityCount : 0, available ? threshold : 0,
                available ? faults : 0, available ? authorityCount : 0, Map.of()));
        when(anchor.snapshot()).thenReturn(new TestSuiteStabilityExternalSequenceAnchor.Snapshot(
                TestSuiteStabilityExternalSequenceAnchor.Snapshot.SCHEMA_VERSION,
                available, available ? "HEALTHY" : "UNAVAILABLE", null,
                0, 0, 0, available ? authorityCount : 0,
                available ? threshold : 0, available ? faults : 0,
                available ? authorityCount : 0));
        when(anchor.transportSecurity()).thenReturn(
                ExternalSequenceAnchorTransportSecurity.compatibility());
        when(anchor.trustSnapshot()).thenReturn(
                new ExternalSequenceAnchorReceiptTrustStore.Snapshot(
                        ExternalSequenceAnchorReceiptTrustStore.Snapshot.SCHEMA_VERSION,
                        false, "UNAVAILABLE", 0, 0, 0, null, 0, 0));
        when(anchor.bootstrapRootDescriptor()).thenReturn(
                ExternalSequenceAnchorBootstrapRootTrustStore.unavailableDescriptor());
        when(anchor.bootstrapRootSnapshot()).thenReturn(
                ExternalSequenceAnchorBootstrapRootTrustStore.unavailableSnapshot());
        return anchor;
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

    private String notaryKeysJson() throws Exception {
        return objectMapper.writeValueAsString(List.of(Map.of(
                "authorityId", "notary-a",
                "keyId", "notary-key-a",
                "publicKeyBase64", Base64.getEncoder().encodeToString(
                        deployment.getPublic().getEncoded()),
                "notBefore", "2020-01-01T00:00:00Z",
                "expiresAt", "2099-01-01T00:00:00Z",
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
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchor.class))
                .isEmpty();
        assertThat(context.getBeansOfType(
                TestSuiteStabilityPhysicalAttemptProviderInventoryExternalSequenceAnchorHealth
                        .class)).isEmpty();
    }
}
