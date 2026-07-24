package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorEvidenceRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorFixtureScopeRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationAuditRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationFailureAuditService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationObservability;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorOperationTelemetry;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunRequestRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CompiledScenarioRehearsalPlanRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionCheckpointIntegrityService;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioArtifactRepository;
import com.leanowtech.bloge.gateway.integration.mirror.ScenarioRehearsalCompiler;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlanIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityKeySetIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityPublicationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAuthorityTrustPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationAdmissionPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationBundleIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorDeploymentIsolationAttestationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorRunIntegrationService;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationAdmissionIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationAdmissionPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationPayloadReferenceVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusGovernancePolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusTrajectoryRepository;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusSourceVerifier;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityRetryPolicyProvider;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityObservationReviewRepository;
import com.leanowtech.bloge.gateway.integration.MirrorRuntimeAvailability;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MirrorRuntimeConfigurationTest {

    @Test
    void disabledSwitchInstallsNoMirrorKernelInTestProfile() {
        try (var context = context(false, "test")) {
            assertMirrorKernelAbsent(context);
        }
    }

    @Test
    void explicitSwitchAssemblesMirrorKernelInTestAndStagingOnly() {
        try (var test = context(true, "test");
             var staging = context(true, "staging")) {
            assertMirrorKernelPresent(test);
            assertMirrorKernelPresent(staging);
        }
    }

    @Test
    void productionPresencePhysicallyExcludesMirrorEvenWhenTestIsAlsoActive() {
        try (var production = context(true, "production");
             var mixed = context(true, "production", "test")) {
            assertMirrorKernelAbsent(production);
            assertMirrorKernelAbsent(mixed);
        }
    }

    @Test
    void servingAvailabilityRequiresAnActuallyAvailableEvidenceSigner() {
        MirrorRuntimeConfiguration configuration = new MirrorRuntimeConfiguration();
        MirrorPlanIntegrationService plans = mock(MirrorPlanIntegrationService.class);
        MirrorRunIntegrationService runs = mock(MirrorRunIntegrationService.class);

        MirrorRuntimeAvailability unavailable = configuration.mirrorRuntimeAvailability(
                plans, runs, VisualEvidenceSigner.unavailable());
        MirrorRuntimeAvailability available = configuration.mirrorRuntimeAvailability(
                plans, runs, new InMemoryVisualEvidenceSigner());

        assertThat(unavailable.planCompilationApi()).isTrue();
        assertThat(unavailable.executionApi()).isTrue();
        assertThat(unavailable.executionReady()).isFalse();
        assertThat(available.executionReady()).isTrue();
    }

    @Test
    void servingAvailabilityTracksSignerChangesAndFailsClosed() {
        MirrorRuntimeConfiguration configuration = new MirrorRuntimeConfiguration();
        VisualEvidenceSigner signer = mock(VisualEvidenceSigner.class);
        AtomicBoolean ready = new AtomicBoolean(false);
        when(signer.available()).thenAnswer(invocation -> ready.get());
        MirrorRuntimeAvailability availability = configuration.mirrorRuntimeAvailability(
                mock(MirrorPlanIntegrationService.class),
                mock(MirrorRunIntegrationService.class), signer);

        assertThat(availability.executionReady()).isFalse();
        ready.set(true);
        assertThat(availability.executionReady()).isTrue();
        when(signer.available()).thenThrow(new IllegalStateException("provider unavailable"));
        assertThat(availability.executionReady()).isFalse();
    }

    private static AnnotationConfigApplicationContext context(boolean enabled,
                                                              String... profiles) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "mirror-runtime-test", Map.of("gateway.testing.mirror.enabled", enabled)));
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());
        context.registerBean(OperatorRegistry.class, DefaultOperatorRegistry::new);
        context.registerBean(ResourceRegistry.class, EmptyResourceRegistry::new);
        context.registerBean(BlgeExpressionEvaluator.class,
                () -> new BlgeExpressionEvaluator());
        context.registerBean(VisualEvidenceSigner.class, VisualEvidenceSigner::unavailable);
        context.registerBean(EmbeddedDatabase.class,
                () -> new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                        .generateUniqueName(true).build(),
                definition -> definition.setDestroyMethodName("shutdown"));
        context.registerBean(JdbcTemplate.class,
                () -> new JdbcTemplate(context.getBean(EmbeddedDatabase.class)));
        context.register(TransactionConfiguration.class);
        context.register(MirrorRuntimeConfiguration.class);
        context.refresh();
        return context;
    }

    private static void assertMirrorKernelPresent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(MirrorRuntimeConfiguration.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorPlanCompiler.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRunService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorEvidenceIntegrityService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorPlanRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorSessionCheckpointIntegrityService.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioArtifactRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                ScenarioRehearsalCompiler.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CompiledScenarioRehearsalPlanRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorFixtureScopeRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorEvidenceRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorRunRequestRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorOperationAuditRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorOperationFailureAuditService.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorOperationObservability.class)).hasSize(1);
        assertThat(context.getBeansOfType(MirrorOperationTelemetry.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityPublicationRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityTrustPolicyProvider.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationBundleIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationAdmissionPolicyProvider.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                CapabilityObservationIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationAdmissionIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationAdmissionPolicyProvider.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                CapabilityObservationPayloadReferenceVerifier.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                CapabilityCorpusIntegrity.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityObservationReviewRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityCorpusRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityCorpusTrajectoryRepository.class)).hasSize(1);
        assertThat(context.getBeansOfType(
                CapabilityCorpusGovernancePolicyProvider.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                CapabilityRetryPolicyProvider.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(context.getBeansOfType(
                CapabilityCorpusSourceVerifier.class).values())
                .singleElement()
                .satisfies(provider -> assertThat(provider.available()).isFalse());
        assertThat(AopUtils.isCglibProxy(context.getBean(MirrorPlanRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(ScenarioArtifactRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(
                CompiledScenarioRehearsalPlanRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(MirrorFixtureScopeRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(MirrorEvidenceRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(MirrorRunRequestRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(CapabilityObservationRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(CapabilityObservationReviewRepository.class)))
                .isTrue();
        assertThat(AopUtils.isCglibProxy(
                context.getBean(CapabilityCorpusRepository.class))).isTrue();
        assertThat(AopUtils.isCglibProxy(context.getBean(
                CapabilityCorpusTrajectoryRepository.class))).isTrue();
        assertThat(context.getBean(MirrorRunService.class).engineConfiguration())
                .satisfies(configuration -> {
                    assertThat(configuration.interceptorTypes()).isEmpty();
                    assertThat(configuration.listenerTypes())
                            .containsExactly("com.leanowtech.bloge.gateway.testing.runtime.InvocationRecorder");
                    assertThat(configuration.durableStores()).isFalse();
                    assertThat(configuration.productionContextCarriers()).isFalse();
                    assertThat(configuration.productionExtensionListeners()).isFalse();
                });
    }

    private static void assertMirrorKernelAbsent(AnnotationConfigApplicationContext context) {
        assertThat(context.getBeansOfType(MirrorRuntimeConfiguration.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorPlanCompiler.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorRunService.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorEvidenceIntegrityService.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorPlanRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorSessionCheckpointIntegrityService.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioArtifactRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                ScenarioRehearsalCompiler.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CompiledScenarioRehearsalPlanRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorFixtureScopeRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorEvidenceRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorRunRequestRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorOperationAuditRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorOperationFailureAuditService.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorOperationObservability.class)).isEmpty();
        assertThat(context.getBeansOfType(MirrorOperationTelemetry.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityKeySetIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityPublicationRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAuthorityTrustPolicyProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationBundleIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                MirrorDeploymentIsolationAttestationAdmissionPolicyProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationAdmissionIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationAdmissionPolicyProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationPayloadReferenceVerifier.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityCorpusIntegrity.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityObservationReviewRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityCorpusRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityCorpusTrajectoryRepository.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityCorpusGovernancePolicyProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityRetryPolicyProvider.class)).isEmpty();
        assertThat(context.getBeansOfType(
                CapabilityCorpusSourceVerifier.class)).isEmpty();
    }

    private static final class EmptyResourceRegistry implements ResourceRegistry {
        @Override
        public ResourceDescriptor resolve(String resourceId) {
            throw new ResourceNotFoundException(resourceId);
        }

        @Override
        public boolean contains(String resourceId) {
            return false;
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.of();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TransactionConfiguration {
        @Bean
        PlatformTransactionManager transactionManager(EmbeddedDatabase database) {
            return new DataSourceTransactionManager(database);
        }
    }
}
