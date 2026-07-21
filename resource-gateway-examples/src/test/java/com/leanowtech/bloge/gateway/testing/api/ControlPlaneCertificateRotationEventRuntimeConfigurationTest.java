package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseControlPlaneCertificateRotationEventCursor;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ControlPlaneCertificateRotationEventRuntimeConfigurationTest {

    private static final String INSTANCE = "replica-a";

    @TempDir
    Path temporaryDirectory;

    @Test
    void testProfileCreatesStableCursorWatcherAndHealthFromCompleteDependencies() {
        try (var context = context("test", properties(true))) {
            context.refresh();

            assertThat(context.getBean(ControlPlaneCertificateRotationEventCursor.class))
                    .isInstanceOf(DatabaseControlPlaneCertificateRotationEventCursor.class)
                    .extracting(ControlPlaneCertificateRotationEventCursor::snapshot)
                    .satisfies(snapshot -> {
                        assertThat(snapshot.instanceId()).isEqualTo(INSTANCE);
                        assertThat(snapshot.deploymentScopeId()).isEqualTo("rg-staging");
                        assertThat(snapshot.baselineSequence()).isEqualTo(11);
                        assertThat(snapshot.baselinePageFingerprint())
                                .isEqualTo(fingerprint('a'));
                    });
            assertThat(context.getBean(ControlPlaneCertificateRotationEventWatcher.class)
                    .descriptor()).satisfies(descriptor -> {
                        assertThat(descriptor.durableCursor()).isTrue();
                        assertThat(descriptor.automaticPolling()).isTrue();
                        assertThat(descriptor.authenticatedProtocol()).isTrue();
                        assertThat(descriptor.sourceMutualTls()).isTrue();
                        assertThat(descriptor.sourceCertificateIdentityBound()).isTrue();
                    });
            assertThat(context.getBeansOfType(
                    ControlPlaneCertificateRotationEventWatcherHealth.class)).hasSize(1);
        }
    }

    @Test
    void startupRejectsOptionalConvergenceBehindRequiredEventDelivery() {
        Map<String, Object> values = properties(false);
        try (var context = context("staging", values)) {
            assertThatThrownBy(context::refresh)
                    .hasRootCauseMessage(
                            "Certificate rotation event delivery requires signed rotation and convergence");
        }
    }

    @Test
    void productionProfileCannotComposeEventDeliveryEvenWhenConfigured() {
        try (var context = context("production", properties(true))) {
            context.refresh();

            assertThat(context.getBeansOfType(
                    ControlPlaneCertificateRotationEventCursor.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    ControlPlaneCertificateRotationEventWatcher.class)).isEmpty();
            assertThat(context.getBeansOfType(
                    ControlPlaneCertificateRotationEventWatcherHealth.class)).isEmpty();
        }
    }

    @Test
    void sourceBeanBuildsRealPrivatePinnedMutualTlsWorkloadIdentity() throws Exception {
        var material = RecoveryFleetPublicationTlsFixture.Material.create(
                temporaryDirectory, "rotation-event-source-product");
        var configuration = new ControlPlaneCertificateRotationEventRuntimeConfiguration();

        ControlPlaneCertificateRotationEventSource source =
                configuration.controlPlaneCertificateRotationEventSource(
                        new ObjectMapper().findAndRegisterModules(),
                        reference -> RecoveryFleetPublicationTlsFixture.password(),
                        eventProperties(material), rotationProperties(),
                        convergenceProperties());

        assertThat(source.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.authenticatedProtocol()).isTrue();
            assertThat(descriptor.privateTrustStore()).isTrue();
            assertThat(descriptor.serverSpkiPinned()).isTrue();
            assertThat(descriptor.mutualTls()).isTrue();
            assertThat(descriptor.certificateIdentityBound()).isTrue();
        });
    }

    @Test
    void realRotationAndEventCompositionRootsShareOneRuntimeAndConvergenceAuthority() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("test");
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("certificate-rotation-events", properties(true)));
            context.registerBean(ObjectMapper.class,
                    () -> new ObjectMapper().findAndRegisterModules());
            context.registerBean(TestRuntimeDatabase.class,
                    () -> new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                            "jdbc:h2:mem:rotation-events-full-" + UUID.randomUUID()
                                    + ";DB_CLOSE_DELAY=-1", "sa", "", 2)),
                    bean -> bean.setDestroyMethodName("close"));
            context.registerBean(ControlPlaneCertificateRotationTrustStore.class,
                    ControlPlaneCertificateRotationTrustStore::unavailable);
            context.registerBean(ControlPlaneCertificateRotationMaterialSource.class,
                    () -> (targetId, generation, materialId) -> {
                        throw new IllegalStateException("not needed by composition test");
                    });
            context.registerBean(ControlPlaneHttpTransport.SecretResolver.class,
                    () -> reference -> "unused".toCharArray());
            context.registerBean(ControlPlaneCertificateRotationEventSource.class,
                    ControlPlaneCertificateRotationEventRuntimeConfigurationTest::source);
            context.register(ControlPlaneCertificateRotationRuntimeConfiguration.class,
                    ControlPlaneCertificateRotationEventRuntimeConfiguration.class);

            context.refresh();

            assertThat(context.getBeansOfType(
                    ControlPlaneCertificateRotationRuntime.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    ControlPlaneCertificateRotationConvergenceMonitor.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    ControlPlaneCertificateRotationEventCursor.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    ControlPlaneCertificateRotationEventWatcher.class)).hasSize(1);
        }
    }

    private static AnnotationConfigApplicationContext context(
            String profile,
            Map<String, Object> properties) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profile);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("certificate-rotation-events", properties));
        context.registerBean(ObjectMapper.class,
                () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(TestRuntimeDatabase.class,
                () -> new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:rotation-events-" + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1", "sa", "", 2)),
                bean -> bean.setDestroyMethodName("close"));
        ControlPlaneCertificateRotationRuntime runtime = mock(
                ControlPlaneCertificateRotationRuntime.class);
        when(runtime.descriptor()).thenReturn(new ControlPlaneCertificateRotationRuntime.Descriptor(
                ControlPlaneCertificateRotationRuntime.Descriptor.SCHEMA_VERSION,
                true, false, true, true, 1, 0, true,
                true, true, false, false, false, "FENCED"));
        context.registerBean(ControlPlaneCertificateRotationRuntime.class, () -> runtime);
        context.registerBean(ControlPlaneCertificateRotationConvergenceMonitor.class,
                () -> mock(ControlPlaneCertificateRotationConvergenceMonitor.class));
        context.registerBean(ControlPlaneCertificateRotationEventSource.class,
                ControlPlaneCertificateRotationEventRuntimeConfigurationTest::source);
        context.register(ControlPlaneCertificateRotationEventRuntimeConfiguration.class);
        return context;
    }

    private static ControlPlaneCertificateRotationEventSource source() {
        return new ControlPlaneCertificateRotationEventSource() {
            @Override
            public FetchResult fetch(Position position) {
                return FetchResult.withoutPage(FetchStatus.NO_CHANGE, "NO_EVENTS");
            }

            @Override
            public Descriptor descriptor() {
                return new Descriptor(Descriptor.SCHEMA_VERSION,
                        true, true, true, true, true);
            }
        };
    }

    private static ControlPlaneCertificateRotationEventSourceProperties eventProperties(
            RecoveryFleetPublicationTlsFixture.Material material) {
        String issuer = PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                material.certificateAuthority());
        var transport = new RecoveryFleetPublicationTransportProperties(
                true, true, material.trustStore().toString(), "test:trust",
                material.clientKeyStore().toString(), "test:client",
                PinnedMutualTlsRecoveryFleetPublicationTransport.spkiPin(
                        material.serverCertificate()), true,
                material.clientCertificate().getSubjectX500Principal().getName(),
                material.clientUriSan(), issuer, material.serverUriSan(), issuer);
        return new ControlPlaneCertificateRotationEventSourceProperties(
                true, true, "https://localhost/v1/rotation-events", 0L,
                fingerprint('a'), 5L, 4, 3_000L, 64 * 1024,
                30L, 300L, false, transport);
    }

    private static ControlPlaneCertificateRotationRuntimeProperties rotationProperties() {
        return new ControlPlaneCertificateRotationRuntimeProperties(
                true, true, "rg-staging", "enterprise-pki", fingerprint('e'), 1,
                "[{}]", 300L, 86_400L,
                "{\"" + ControlPlaneCertificateRotationTargets.TEST_SECRET_NOTARY + "\":1}",
                "[{}]");
    }

    private static ControlPlaneCertificateRotationConvergenceProperties
            convergenceProperties() {
        return new ControlPlaneCertificateRotationConvergenceProperties(
                true, true, "fleet-2026-07", INSTANCE, UUID.randomUUID().toString(),
                fingerprint('f'), INSTANCE, "convergence-v1", "ALL_REPLICAS", 1,
                1L, 3L, 3_600L, "LOCAL_CONFIGURED", 0L, "", "", "");
    }

    private static Map<String, Object> properties(boolean convergenceRequired) {
        String event = ControlPlaneCertificateRotationEventSourceProperties.PREFIX;
        String rotation = ControlPlaneCertificateRotationRuntimeProperties.PREFIX;
        String convergence = ControlPlaneCertificateRotationConvergenceProperties.PREFIX;
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        values.put(event + ".enabled", "true");
        values.put(event + ".required", "true");
        values.put(event + ".endpoint-uri", "https://events.example.test/v1/pages");
        values.put(event + ".baseline-sequence", "11");
        values.put(event + ".baseline-page-fingerprint", fingerprint('a'));
        values.put(event + ".poll-interval-seconds", "60");
        values.put(event + ".maximum-pages-per-poll", "4");
        values.put(event + ".request-timeout-millis", "3000");
        values.put(event + ".maximum-page-bytes", Integer.toString(64 * 1024));
        values.put(event + ".clock-skew-seconds", "30");
        values.put(event + ".maximum-page-lifetime-seconds", "300");
        values.put(event + ".transport.enabled", "true");
        values.put(event + ".transport.required", "true");
        values.put(event + ".transport.trust-store-path", "/events/trust.p12");
        values.put(event + ".transport.trust-store-password-ref", "env:EVENT_TRUST");
        values.put(event + ".transport.client-key-store-path", "/events/client.p12");
        values.put(event + ".transport.client-key-store-password-ref", "env:EVENT_CLIENT");
        values.put(event + ".transport.server-spki-pins", fingerprint('b'));
        values.put(event + ".transport.certificate-identity-required", "true");
        values.put(event + ".transport.expected-client-subject-dn", "CN=rg-events");
        values.put(event + ".transport.expected-client-uri-san",
                "spiffe://example.test/resource-gateway/events");
        values.put(event + ".transport.client-issuer-spki-pins", fingerprint('c'));
        values.put(event + ".transport.expected-server-uri-san",
                "spiffe://example.test/ca/events");
        values.put(event + ".transport.server-issuer-spki-pins", fingerprint('d'));
        values.put(rotation + ".enabled", "true");
        values.put(rotation + ".required", "true");
        values.put(rotation + ".deployment-scope-id", "rg-staging");
        values.put(rotation + ".trust-domain", "enterprise-pki");
        values.put(rotation + ".accepted-policy-fingerprints", fingerprint('e'));
        values.put(rotation + ".signature-threshold", "1");
        values.put(rotation + ".authority-keys-json", "[{}]");
        values.put(rotation + ".initial-generations-json",
                "{\"" + ControlPlaneCertificateRotationTargets.TEST_SECRET_NOTARY + "\":1}");
        values.put(rotation + ".material-catalog-json", "[{}]");
        values.put(convergence + ".enabled", "true");
        values.put(convergence + ".required", Boolean.toString(convergenceRequired));
        values.put(convergence + ".fleet-id", "fleet-2026-07");
        values.put(convergence + ".instance-id", INSTANCE);
        values.put(convergence + ".startup-id", UUID.randomUUID().toString());
        values.put(convergence + ".artifact-fingerprint", fingerprint('f'));
        values.put(convergence + ".expected-instance-ids", INSTANCE);
        values.put(convergence + ".protocol-version", "convergence-v1");
        values.put(convergence + ".activation-mode", "ALL_REPLICAS");
        values.put(convergence + ".required-staged-replicas", "1");
        values.put(convergence + ".heartbeat-interval-seconds", "1");
        values.put(convergence + ".lease-duration-seconds", "3");
        return values;
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
