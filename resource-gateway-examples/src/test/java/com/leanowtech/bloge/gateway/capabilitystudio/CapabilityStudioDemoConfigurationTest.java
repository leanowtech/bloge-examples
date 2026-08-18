package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompiler;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedRegistryGateway;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityStudioDemoConfigurationTest {
    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private final JdbcDataSource dataSource = dataSource();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(OperatorRegistry.class, DefaultOperatorRegistry::new)
            .withBean(ScenarioGovernedCompiler.class, () -> mock(ScenarioGovernedCompiler.class))
            .withBean(ScenarioGovernedRegistryGateway.class,
                    () -> mock(ScenarioGovernedRegistryGateway.class))
            .withBean(TestSuiteExecutionService.class, () -> mock(TestSuiteExecutionService.class))
            .withBean(TestExecutionApiService.class, () -> mock(TestExecutionApiService.class))
            .withBean(IntegrationRequestAuthenticator.class,
                    () -> mock(IntegrationRequestAuthenticator.class))
            .withBean(WritableResourceRegistry.class, () -> mock(WritableResourceRegistry.class))
            .withBean(JdbcDataSource.class, () -> dataSource)
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
            .withBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource))
            .withUserConfiguration(
                    CapabilityStudioDemoConfiguration.class,
                    CapabilityStudioDemoController.class);

    private static JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:capability-studio-config;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        return dataSource;
    }

    @Test
    void isAbsentWithoutTheExplicitDemoProperty() {
        runner.withPropertyValues("spring.profiles.active=test")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CapabilityStudioGoldenDemoPack.class)
                        .doesNotHaveBean(CapabilityStudioScenarioDatasetProjector.class)
                        .doesNotHaveBean(CapabilityStudioScenarioQualityImpactProjection.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalService.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalOracle.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalBaselineService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedCompilationService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedAssetPublisher.class)
                        .doesNotHaveBean(CapabilityStudioGovernedCandidateService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedBaselineService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedRunEvidenceService.class)
                        .doesNotHaveBean(CapabilityStudioTutorialBranchRepository.class)
                        .doesNotHaveBean(CapabilityStudioTutorialBranchAuthority.class)
                        .doesNotHaveBean(CapabilityStudioDemoController.class));
    }

    @Test
    void isAbsentOutsideTestAndStagingEvenWhenExplicitlyEnabled() {
        runner.withPropertyValues("gateway.capability-studio.demo.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CapabilityStudioGoldenDemoPack.class)
                        .doesNotHaveBean(CapabilityStudioScenarioDatasetProjector.class)
                        .doesNotHaveBean(CapabilityStudioScenarioQualityImpactProjection.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalService.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalOracle.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalBaselineService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedCompilationService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedAssetPublisher.class)
                        .doesNotHaveBean(CapabilityStudioGovernedCandidateService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedBaselineService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedRunEvidenceService.class)
                        .doesNotHaveBean(CapabilityStudioTutorialBranchRepository.class)
                        .doesNotHaveBean(CapabilityStudioTutorialBranchAuthority.class)
                        .doesNotHaveBean(CapabilityStudioDemoController.class));
    }

    @ParameterizedTest(name = "production vetoes Capability Studio for profiles {0}")
    @ValueSource(strings = {"production", "production,test", "production,staging"})
    void productionVetoesAllCapabilityStudioDemoBeansEvenWhenExplicitlyEnabled(String profiles) {
        runner.withPropertyValues(
                        "spring.profiles.active=" + profiles,
                        "gateway.capability-studio.demo.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CapabilityStudioGoldenDemoPack.class)
                        .doesNotHaveBean(CapabilityStudioScenarioDatasetProjector.class)
                        .doesNotHaveBean(CapabilityStudioScenarioQualityImpactProjection.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalService.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalOracle.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalBaselineService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedCompilationService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedAssetPublisher.class)
                        .doesNotHaveBean(CapabilityStudioGovernedCandidateService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedBaselineService.class)
                        .doesNotHaveBean(CapabilityStudioGovernedRunEvidenceService.class)
                        .doesNotHaveBean(CapabilityStudioTutorialBranchRepository.class)
                        .doesNotHaveBean(CapabilityStudioTutorialBranchAuthority.class)
                        .doesNotHaveBean(CapabilityStudioDemoController.class));
    }

    @Test
    void isAvailableOnlyInTestOrStagingWhenExplicitlyEnabled() {
        runner.withPropertyValues(
                        "spring.profiles.active=test",
                        "gateway.capability-studio.demo.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(CapabilityStudioGoldenDemoPack.class)
                        .hasSingleBean(CapabilityStudioScenarioDatasetProjector.class)
                        .hasSingleBean(CapabilityStudioScenarioQualityImpactProjection.class)
                        .hasSingleBean(CapabilityStudioFeatureRehearsalService.class)
                        .hasSingleBean(CapabilityStudioFeatureRehearsalOracle.class)
                        .hasSingleBean(CapabilityStudioFeatureRehearsalBaselineService.class)
                        .hasSingleBean(CapabilityStudioGovernedCompilationService.class)
                        .hasSingleBean(CapabilityStudioGovernedAssetPublisher.class)
                        .hasSingleBean(CapabilityStudioDeploymentCandidateAuthority.class)
                        .hasSingleBean(CapabilityStudioGovernedCandidateService.class)
                        .hasSingleBean(CapabilityStudioGovernedBaselineService.class)
                        .hasSingleBean(CapabilityStudioGovernedRunEvidenceService.class)
                        .hasSingleBean(CapabilityStudioTutorialBranchRepository.class)
                        .hasSingleBean(CapabilityStudioTutorialBranchAuthority.class)
                        .hasSingleBean(CapabilityStudioDemoController.class));

        runner.withPropertyValues(
                        "spring.profiles.active=staging",
                        "gateway.capability-studio.demo.enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(CapabilityStudioTutorialBranchRepository.class)
                        .hasSingleBean(CapabilityStudioTutorialBranchAuthority.class)
                        .hasSingleBean(CapabilityStudioGoldenDemoPack.class)
                        .hasSingleBean(CapabilityStudioScenarioDatasetProjector.class)
                        .hasSingleBean(CapabilityStudioScenarioQualityImpactProjection.class)
                        .hasSingleBean(CapabilityStudioFeatureRehearsalService.class)
                        .hasSingleBean(CapabilityStudioFeatureRehearsalOracle.class)
                        .hasSingleBean(CapabilityStudioFeatureRehearsalBaselineService.class)
                        .hasSingleBean(CapabilityStudioGovernedCompilationService.class)
                        .hasSingleBean(CapabilityStudioGovernedAssetPublisher.class)
                        .hasSingleBean(CapabilityStudioDeploymentCandidateAuthority.class)
                        .hasSingleBean(CapabilityStudioGovernedCandidateService.class)
                        .hasSingleBean(CapabilityStudioGovernedBaselineService.class)
                        .hasSingleBean(CapabilityStudioGovernedRunEvidenceService.class));
    }

    @Test
    void demoIdentityProfilesAuthorizeConfidentialCapabilityStudioRehearsal() throws IOException {
        String defaults = Files.readString(
                RESOURCES.resolve("application.yml"), StandardCharsets.UTF_8);
        String test = Files.readString(
                RESOURCES.resolve("application-test.yml"), StandardCharsets.UTF_8);
        String staging = Files.readString(
                RESOURCES.resolve("application-staging.yml"), StandardCharsets.UTF_8);

        assertThat(defaults).contains(
                "demo-token: ${RG_INTEGRATION_DEMO_TOKEN:bloge-aneke-demo-token}",
                "clearance: ${RG_INTEGRATION_CLEARANCE:CONFIDENTIAL}",
                "CAPABILITY_STUDIO_REHEARSAL");
        assertThat(test).contains(
                "clearance: ${RG_INTEGRATION_CLEARANCE:CONFIDENTIAL}",
                "CAPABILITY_STUDIO_REHEARSAL");
        assertThat(staging).contains(
                "clearance: ${RG_INTEGRATION_CLEARANCE:CONFIDENTIAL}",
                "CAPABILITY_STUDIO_REHEARSAL");
    }

    @Test
    void refusesToOverwriteAnExistingResourceDescriptor() {
        WritableResourceRegistry registry = mock(WritableResourceRegistry.class);
        ResourceDescriptor expected = CapabilityStudioFeatureRehearsalService
                .demoResourceDescriptors().getFirst();
        ResourceDescriptor conflicting = new ResourceDescriptor(
                expected.resourceId(),
                "https://existing.example.test/resource",
                expected.method(),
                expected.defaultHeaders(),
                expected.authStrategy(),
                expected.defaultTimeout(),
                expected.parameterMapping(),
                expected.responseProtocol(),
                expected.payloadPath(),
                expected.externalWriteContract());
        when(registry.contains(expected.resourceId())).thenReturn(true);
        when(registry.resolve(expected.resourceId())).thenReturn(conflicting);

        assertThatThrownBy(() -> CapabilityStudioDemoConfiguration
                .bindDemoResourceDescriptors(registry))
                .isInstanceOf(org.springframework.beans.factory.BeanCreationException.class)
                .hasMessageContaining("Conflicting Resource descriptor")
                .hasMessageContaining(expected.resourceId());
    }
}
