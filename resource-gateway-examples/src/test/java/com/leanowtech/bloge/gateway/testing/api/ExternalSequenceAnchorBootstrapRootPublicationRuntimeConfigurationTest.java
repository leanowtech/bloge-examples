package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfigurationTest {

    private static final String PREFIX =
            ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfiguration.Properties
                    .PREFIX + ".";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void disabledTestProfileInstallsNoPublicationRuntime() {
        try (var context = context(Map.of(), "test")) {
            assertRuntimeAbsent(context);
        }
    }

    @Test
    void productionPresencePhysicallyExcludesTheRuntimeEvenWhenEnabled() throws Exception {
        Map<String, Object> properties = enabledProperties();
        try (var production = context(properties, "production");
             var mixed = context(properties, "production", "staging")) {
            assertRuntimeAbsent(production);
            assertRuntimeAbsent(mixed);
        }
    }

    @Test
    void enabledTestProfileAssemblesOneDatabaseFencedPublicationLane() throws Exception {
        try (var context = context(enabledProperties(), "test")) {
            assertThat(context.getBeansOfType(
                    DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal.class))
                    .hasSize(1);
            assertThat(context.getBeansOfType(
                    ExternalSequenceAnchorBootstrapRootPublicationOutbox.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    HttpExternalSequenceAnchorBootstrapRootPublisher.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    ExternalSequenceAnchorBootstrapRootPublicationService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    ExternalSequenceAnchorBootstrapRootPublicationScheduler.class)).hasSize(1);

            var health = context.getBean(
                    ExternalSequenceAnchorBootstrapRootPublicationHealth.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("runtimeStatus", "READY");
            assertThat(health.getDetails())
                    .containsEntry("transportSystemTrustStore", true)
                    .containsEntry("transportServerSpkiPinned", false)
                    .containsEntry("transportMutualTls", false);
            assertThat(health.getDetails().toString())
                    .doesNotContain("scope-sensitive", "root-set-sensitive",
                            "worker-sensitive", "publisher-sensitive",
                            "response-key-sensitive", "127.0.0.1");
        }
    }

    @Test
    void enabledRotationPolicyCannotSilentlyFallBackWhenCompositionRootIsMissing()
            throws Exception {
        Map<String, Object> properties = enabledProperties();
        properties.put(ControlPlaneCertificateRotationRuntimeProperties.PREFIX + ".enabled",
                "true");

        var context = unrefreshedContext(properties, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Enabled certificate rotation runtime is unavailable");
        } finally {
            context.close();
        }
    }

    @Test
    void stagingRejectsPublisherTransportDowngradeBeforeDatabaseOrNetworkUse()
            throws Exception {
        Map<String, Object> properties = enabledProperties();
        properties.put(PREFIX + "allow-insecure-loopback", "false");
        properties.put(PREFIX + "endpoint", "https://publisher.example/publications");
        TestRuntimeDatabase database = mock(TestRuntimeDatabase.class);

        var context = unrefreshedContext(properties, database, "staging");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Bootstrap-root publication runtime configuration is invalid");
            verify(database, never()).jdbc();
        } finally {
            context.close();
        }
    }

    @Test
    void stagingAssemblesPinnedMutualTlsPublisherFromOpaqueCredentials()
            throws Exception {
        var material = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "spring-publisher");
        Map<String, Object> properties = enabledProperties();
        properties.put(PREFIX + "endpoint", "https://publisher.example/publications");
        properties.put(PREFIX + "allow-insecure-loopback", "false");
        properties.put(PREFIX + "transport.enabled", "true");
        properties.put(PREFIX + "transport.required", "true");
        properties.put(PREFIX + "transport.trust-store-path",
                material.trustStore().toString());
        properties.put(PREFIX + "transport.trust-store-password-ref",
                "test:publisher-trust");
        properties.put(PREFIX + "transport.client-key-store-path",
                material.clientKeyStore().toString());
        properties.put(PREFIX + "transport.client-key-store-password-ref",
                "test:publisher-client");
        properties.put(PREFIX + "transport.server-spki-pins",
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.serverCertificate()));
        properties.put(PREFIX + "transport.certificate-identity-required", "true");
        properties.put(PREFIX + "transport.expected-client-subject-dn",
                material.clientCertificate().getSubjectX500Principal().getName());
        properties.put(PREFIX + "transport.expected-client-uri-san",
                material.clientUriSan());
        properties.put(PREFIX + "transport.client-issuer-spki-pins",
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.certificateAuthority()));
        properties.put(PREFIX + "transport.expected-server-uri-san",
                material.serverUriSan());
        properties.put(PREFIX + "transport.server-issuer-spki-pins",
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.certificateAuthority()));
        properties.put(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                        .DynamicInventoryProperties.PREFIX + ".transport.required", "true");
        properties.put(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                        .ManagedTrustRootProperties.PREFIX + ".transport.required", "true");

        var context = unrefreshedContext(properties, "staging");
        context.registerBean(RecoveryFleetPublicationTransport.SecretResolver.class,
                () -> reference -> RecoveryFleetPublicationTlsFixture.password());
        try (context) {
            context.refresh();

            var publisher = context.getBean(
                    HttpExternalSequenceAnchorBootstrapRootPublisher.class);
            assertThat(publisher.transportDescriptor()).satisfies(descriptor -> {
                assertThat(descriptor.systemTrustStore()).isFalse();
                assertThat(descriptor.privateTrustStore()).isTrue();
                assertThat(descriptor.serverSpkiPinned()).isTrue();
                assertThat(descriptor.mutualTls()).isTrue();
                assertThat(descriptor.certificateIdentityBound()).isTrue();
            });
            assertThat(context.getBean(
                    ExternalSequenceAnchorBootstrapRootPublicationHealth.class)
                    .health().getDetails())
                    .containsEntry("transportServerSpkiPinned", true)
                    .containsEntry("transportMutualTls", true)
                    .containsEntry("transportCertificateIdentityBound", true);
        }
    }

    @Test
    void stagingRejectsPublisherClientIdentityReuseBeforeDatabaseOrSecretResolution()
            throws Exception {
        Path sharedIdentity = Files.createFile(
                temporaryDirectory.resolve("shared-client-identity.p12"));
        Map<String, Object> properties = enabledProperties();
        properties.put(PREFIX + "endpoint", "https://publisher.example/publications");
        properties.put(PREFIX + "allow-insecure-loopback", "false");
        configureTransport(properties, PREFIX + "transport.", sharedIdentity,
                "env:SHARED_CLIENT_IDENTITY");
        configureTransport(properties,
                ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                        .DynamicInventoryProperties.PREFIX + ".transport.",
                sharedIdentity, "env:SHARED_CLIENT_IDENTITY");
        properties.put(
                ExternalSequenceAnchorBootstrapRootRecoveryFleetDynamicInventoryConfiguration
                        .DynamicInventoryProperties.PREFIX + ".enabled", "true");
        TestRuntimeDatabase database = mock(TestRuntimeDatabase.class);

        var context = unrefreshedContext(properties, database, "staging");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Bootstrap-root publication runtime configuration is invalid")
                    .hasMessageNotContaining("SHARED_CLIENT_IDENTITY");
            verify(database, never()).jdbc();
        } finally {
            context.close();
        }
    }

    @Test
    void enabledPartialConfigurationFailsStartupWithoutEchoingSensitiveValues()
            throws Exception {
        Map<String, Object> properties = enabledProperties();
        properties.remove(PREFIX + "root-set-id");
        properties.put(PREFIX + "response-key-id", "must-not-echo-key-id");

        var context = unrefreshedContext(properties, "staging");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Bootstrap-root publication runtime configuration is invalid")
                    .hasMessageNotContaining("must-not-echo-key-id");
        } finally {
            context.close();
        }
    }

    @Test
    void unsafeDeadlineOrUnknownConfigurationFailsStartup() throws Exception {
        Map<String, Object> unsafe = enabledProperties();
        unsafe.put(PREFIX + "publisher-call-timeout-millis", "3000");
        assertStartupFails(unsafe);

        Map<String, Object> unknown = enabledProperties();
        unknown.put(PREFIX + "silent-fallback", "true");
        var context = unrefreshedContext(unknown, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining("silent-fallback");
        } finally {
            context.close();
        }
    }

    @Test
    void publicationServiceClosesItsSupervisorButLeavesCallerOwnedPublisherOpen() {
        var outbox = mock(ExternalSequenceAnchorBootstrapRootPublicationOutbox.class);
        when(outbox.durablePublicationOutbox()).thenReturn(true);
        var publisher = mock(ExternalSequenceAnchorBootstrapRootPublisher.class);
        var service = new ExternalSequenceAnchorBootstrapRootPublicationService(
                outbox, publisher,
                new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Policy(
                        java.time.Duration.ofMillis(100), 1));

        service.close();

        assertThatThrownBy(() -> service.publishNext("worker-after-close", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        verify(publisher, never()).close();
    }

    @Test
    void scheduledFatalFailureIsVisibleAfterTheScheduledFutureStops() throws Exception {
        var outbox = mock(ExternalSequenceAnchorBootstrapRootPublicationOutbox.class);
        when(outbox.durablePublicationOutbox()).thenReturn(true);
        when(outbox.acquirePublication(any())).thenThrow(new AssertionError("fatal-store"));
        var publisher = mock(ExternalSequenceAnchorBootstrapRootPublisher.class);
        try (var service = new ExternalSequenceAnchorBootstrapRootPublicationService(
                outbox, publisher,
                new ExternalSequenceAnchorBootstrapRootPublisherCallSupervisor.Policy(
                        Duration.ofMillis(100), 1));
             var scheduler = new ExternalSequenceAnchorBootstrapRootPublicationScheduler(
                     service, "fatal-worker", 3,
                     new ExternalSequenceAnchorBootstrapRootPublicationScheduler.SchedulePolicy(
                             Duration.ZERO, Duration.ofHours(1), Duration.ZERO))) {
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (scheduler.snapshot().pollFailureCount() == 0
                    && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }

            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.pollFailureCount()).isOne();
                assertThat(snapshot.lastPollFailed()).isTrue();
            });
        }
    }

    private static void assertStartupFails(Map<String, Object> properties) {
        var context = unrefreshedContext(properties, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Bootstrap-root publication runtime configuration is invalid");
        } finally {
            context.close();
        }
    }

    private static AnnotationConfigApplicationContext context(
            Map<String, Object> properties, String... profiles) {
        var context = unrefreshedContext(properties, profiles);
        context.refresh();
        return context;
    }

    private static AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> overrides, String... profiles) {
        return unrefreshedContext(overrides, null, profiles);
    }

    private static AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> overrides,
            TestRuntimeDatabase suppliedDatabase,
            String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        Map<String, Object> properties = new LinkedHashMap<>(overrides);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("bootstrap-root-publication", properties));
        context.registerBean(ObjectMapper.class,
                () -> new ObjectMapper().findAndRegisterModules());
        if (suppliedDatabase == null) {
            context.registerBean(TestRuntimeDatabase.class,
                    () -> new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                            "jdbc:h2:mem:bootstrap-root-publication-" + UUID.randomUUID()
                                    + ";DB_CLOSE_DELAY=-1", "sa", "", 2)),
                    definition -> definition.setDestroyMethodName("close"));
        } else {
            context.registerBean(TestRuntimeDatabase.class, () -> suppliedDatabase);
        }
        context.register(
                ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfiguration.class);
        return context;
    }

    private static Map<String, Object> enabledProperties() throws Exception {
        String publicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PREFIX + "enabled", "true");
        properties.put(PREFIX + "scope-id", "scope-sensitive");
        properties.put(PREFIX + "root-set-id", "root-set-sensitive");
        properties.put(PREFIX + "worker-id", "worker-sensitive");
        properties.put(PREFIX + "endpoint", "http://127.0.0.1:1/publications");
        properties.put(PREFIX + "trust-domain", "publisher-sensitive");
        properties.put(PREFIX + "publisher-id", "publisher-sensitive");
        properties.put(PREFIX + "response-key-id", "response-key-sensitive");
        properties.put(PREFIX + "response-public-key-base64", publicKey);
        properties.put(PREFIX + "response-key-not-before", "2020-01-01T00:00:00Z");
        properties.put(PREFIX + "response-key-expires-at", "2099-01-01T00:00:00Z");
        properties.put(PREFIX + "allow-insecure-loopback", "true");
        properties.put(PREFIX + "initial-delay-millis", "300000");
        return properties;
    }

    private static void configureTransport(
            Map<String, Object> properties,
            String prefix,
            Path clientIdentity,
            String secretReference) {
        properties.put(prefix + "enabled", "true");
        properties.put(prefix + "required", "true");
        properties.put(prefix + "client-key-store-path", clientIdentity.toString());
        properties.put(prefix + "client-key-store-password-ref", secretReference);
        properties.put(prefix + "server-spki-pins", "sha256:" + "a".repeat(64));
    }

    private static void assertRuntimeAbsent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootPublicationOutbox.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootPublisher.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootPublicationService.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootPublicationScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootPublicationHealth.class)).isEmpty();
    }
}
