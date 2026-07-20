package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootRecoveryRuntimeConfigurationTest {

    private static final String PUBLICATION_PREFIX =
            ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfiguration.Properties
                    .PREFIX + ".";
    private static final String RECOVERY_PREFIX =
            ExternalSequenceAnchorBootstrapRootRecoveryRuntimeConfiguration.RecoveryProperties
                    .PREFIX + ".";
    private static final String CEREMONY_POLICY = "sha256:" + "a".repeat(64);
    private static final String GENESIS_POLICY = "sha256:" + "b".repeat(64);

    @Test
    void disabledRecoveryInstallsNoRecoveryBeansWhilePublicationRemainsUsable()
            throws Exception {
        try (var context = context(publicationProperties(), true, "test")) {
            assertThat(context.getBeansOfType(
                    ExternalSequenceAnchorBootstrapRootPublicationService.class)).hasSize(1);
            assertRecoveryAbsent(context);
        }
    }

    @Test
    void productionPresencePhysicallyExcludesRecoveryEvenWhenTestIsAlsoActive()
            throws Exception {
        try (var production = context(enabledProperties(), true, "production");
             var mixed = context(enabledProperties(), true, "production", "test")) {
            assertPublicationAndRecoveryAbsent(production);
            assertPublicationAndRecoveryAbsent(mixed);
        }
    }

    @Test
    void enabledTestProfileAssemblesOneDatabaseFencedRecoveryLane() throws Exception {
        var context = context(enabledProperties(), true, "test");
        var service = context.getBean(
                ExternalSequenceAnchorBootstrapRootCeremonyService.class);
        var scheduler = context.getBean(
                ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.class);
        try {
            assertThat(context.getBeansOfType(
                    ExternalSequenceAnchorBootstrapRootCeremonyProducer.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    ExternalSequenceAnchorBootstrapRootCeremonyService.class)).hasSize(1);
            assertThat(context.getBeansOfType(
                    ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.class))
                    .hasSize(1);
            assertThat(scheduler.runOnce().status()).isEqualTo(
                    ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                            .NO_ACTIVE_CEREMONY);
            var health = context.getBean(
                    ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth.class).health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("runtimeStatus", "READY")
                    .containsEntry("workflowStatus", "NO_ACTIVE_CEREMONY")
                    .containsEntry("schedulerPollCount", 1L);
            assertThat(health.getDetails().toString()).doesNotContain(
                    "scope-sensitive", "root-set-sensitive", "recovery-worker-sensitive",
                    "root-authority", "response-key-sensitive", "127.0.0.1");
        } finally {
            context.close();
        }
        assertThat(scheduler.snapshot().closed()).isTrue();
        assertThat(service.runtimeSnapshot().closed()).isTrue();
    }

    @Test
    void enabledRecoveryRequiresTheSameEnabledPublicationJournal() throws Exception {
        Map<String, Object> properties = enabledProperties();
        properties.put(PUBLICATION_PREFIX + "enabled", "false");
        var context = unrefreshedContext(properties, true, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Bootstrap-root ceremony recovery runtime configuration is invalid");
        } finally {
            context.close();
        }
    }

    @Test
    void enabledRecoveryRequiresAnExplicitOpaqueAuthorityResolver() throws Exception {
        var context = unrefreshedContext(enabledProperties(), false, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .hasStackTraceContaining(
                            ExternalSequenceAnchorBootstrapRootAuthorityResolver.class
                                    .getName());
        } finally {
            context.close();
        }
    }

    @Test
    void malformedOrUnknownRecoveryConfigurationFailsWithoutEchoingGenesis()
            throws Exception {
        Map<String, Object> malformed = enabledProperties();
        malformed.put(RECOVERY_PREFIX + "genesis-json", "must-not-echo-genesis");
        var malformedContext = unrefreshedContext(malformed, true, "test");
        try {
            assertThatThrownBy(malformedContext::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Bootstrap-root ceremony recovery runtime configuration is invalid")
                    .hasMessageNotContaining("must-not-echo-genesis");
        } finally {
            malformedContext.close();
        }

        Map<String, Object> unknown = enabledProperties();
        unknown.put(RECOVERY_PREFIX + "private-key-base64", "must-not-bind");
        var unknownContext = unrefreshedContext(unknown, true, "test");
        try {
            assertThatThrownBy(unknownContext::refresh)
                    .hasStackTraceContaining("private-key-base64");
        } finally {
            unknownContext.close();
        }
    }

    @Test
    void duplicatePolicyOrUnsafeExecutionWindowFailsClosed() throws Exception {
        Map<String, Object> duplicate = enabledProperties();
        duplicate.put(RECOVERY_PREFIX + "accepted-policy-fingerprints",
                CEREMONY_POLICY + "," + CEREMONY_POLICY);
        assertInvalidConfiguration(duplicate);

        Map<String, Object> unsafeWindow = enabledProperties();
        unsafeWindow.put(RECOVERY_PREFIX + "maximum-execution-delay-seconds", "4");
        assertInvalidConfiguration(unsafeWindow);
    }

    @Test
    void stagingRejectsANonByzantineGenesisBeforeStartingTheLane() throws Exception {
        Map<String, Object> properties = enabledProperties();
        properties.put(RECOVERY_PREFIX + "genesis-json", genesisJson(1, 0, 1));
        var context = unrefreshedContext(properties, true, "staging");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Bootstrap-root ceremony recovery runtime configuration is invalid");
        } finally {
            context.close();
        }
    }

    @Test
    void customProducerCannotBypassConfiguredBindingValidation() throws Exception {
        Map<String, Object> properties = enabledProperties();
        properties.put(RECOVERY_PREFIX + "genesis-json", "invalid-public-genesis");
        var context = unrefreshedContext(properties, true, "test");
        context.registerBean(ExternalSequenceAnchorBootstrapRootCeremonyProducer.class,
                () -> org.mockito.Mockito.mock(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.class));
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Bootstrap-root ceremony recovery runtime configuration is invalid")
                    .hasMessageNotContaining("invalid-public-genesis");
        } finally {
            context.close();
        }
    }

    private static AnnotationConfigApplicationContext context(
            Map<String, Object> properties,
            boolean resolver,
            String... profiles) {
        var context = unrefreshedContext(properties, resolver, profiles);
        context.refresh();
        return context;
    }

    private static void assertInvalidConfiguration(Map<String, Object> properties) {
        var context = unrefreshedContext(properties, true, "test");
        try {
            assertThatThrownBy(context::refresh)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Bootstrap-root ceremony recovery runtime configuration is invalid");
        } finally {
            context.close();
        }
    }

    private static AnnotationConfigApplicationContext unrefreshedContext(
            Map<String, Object> overrides,
            boolean resolver,
            String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("bootstrap-root-recovery",
                        new LinkedHashMap<>(overrides)));
        context.registerBean(ObjectMapper.class,
                () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(TestRuntimeDatabase.class,
                () -> new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:bootstrap-root-recovery-" + UUID.randomUUID()
                                + ";DB_CLOSE_DELAY=-1", "sa", "", 2)),
                definition -> definition.setDestroyMethodName("close"));
        if (resolver) {
            context.registerBean(ExternalSequenceAnchorBootstrapRootAuthorityResolver.class,
                    () -> proposal ->
                            new ExternalSequenceAnchorBootstrapRootAuthorityResolver.AuthoritySet(
                                    List.of(), List.of()));
        }
        context.register(
                ExternalSequenceAnchorBootstrapRootPublicationRuntimeConfiguration.class,
                ExternalSequenceAnchorBootstrapRootRecoveryRuntimeConfiguration.class);
        return context;
    }

    private static Map<String, Object> enabledProperties() throws Exception {
        Map<String, Object> properties = publicationProperties();
        properties.put(RECOVERY_PREFIX + "enabled", "true");
        properties.put(RECOVERY_PREFIX + "worker-id", "recovery-worker-sensitive");
        properties.put(RECOVERY_PREFIX + "genesis-json", genesisJson(3, 1, 4));
        properties.put(RECOVERY_PREFIX + "accepted-policy-fingerprints", CEREMONY_POLICY);
        properties.put(RECOVERY_PREFIX + "initial-delay-millis", "300000");
        return properties;
    }

    private static Map<String, Object> publicationProperties() throws Exception {
        String responsePublicKey = Base64.getEncoder().encodeToString(
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                        .getPublic().getEncoded());
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PUBLICATION_PREFIX + "enabled", "true");
        properties.put(PUBLICATION_PREFIX + "scope-id", "scope-sensitive");
        properties.put(PUBLICATION_PREFIX + "root-set-id", "root-set-sensitive");
        properties.put(PUBLICATION_PREFIX + "worker-id", "publisher-worker-sensitive");
        properties.put(PUBLICATION_PREFIX + "endpoint",
                "http://127.0.0.1:1/publications");
        properties.put(PUBLICATION_PREFIX + "trust-domain", "publisher-sensitive");
        properties.put(PUBLICATION_PREFIX + "publisher-id", "publisher-sensitive");
        properties.put(PUBLICATION_PREFIX + "response-key-id", "response-key-sensitive");
        properties.put(PUBLICATION_PREFIX + "response-public-key-base64",
                responsePublicKey);
        properties.put(PUBLICATION_PREFIX + "response-key-not-before",
                "2020-01-01T00:00:00Z");
        properties.put(PUBLICATION_PREFIX + "response-key-expires-at",
                "2099-01-01T00:00:00Z");
        properties.put(PUBLICATION_PREFIX + "allow-insecure-loopback", "true");
        properties.put(PUBLICATION_PREFIX + "initial-delay-millis", "300000");
        return properties;
    }

    private static String genesisJson(
            int threshold,
            int maximumFaults,
            int authorityCount) throws Exception {
        List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> roots =
                new ArrayList<>();
        for (int index = 1; index <= authorityCount; index++) {
            String publicKey = Base64.getEncoder().encodeToString(
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                            .getPublic().getEncoded());
            roots.add(new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                    "root-authority-" + index, "root-key-" + index, publicKey,
                    Instant.parse("2020-01-01T00:00:00Z"),
                    Instant.parse("2099-01-01T00:00:00Z"), true, false));
        }
        var genesis = new ExternalSequenceAnchorBootstrapRootGenesis(
                ExternalSequenceAnchorBootstrapRootGenesis.SCHEMA_VERSION,
                "scope-sensitive", "root-set-sensitive", "root-trust-sensitive",
                threshold, maximumFaults, roots, GENESIS_POLICY);
        return new ObjectMapper().findAndRegisterModules().writeValueAsString(genesis);
    }

    private static void assertRecoveryAbsent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootCeremonyService.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootCeremonyRecoveryHealth.class)).isEmpty();
    }

    private static void assertPublicationAndRecoveryAbsent(
            AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(
                ExternalSequenceAnchorBootstrapRootPublicationService.class)).isEmpty();
        assertRecoveryAbsent(context);
    }
}
