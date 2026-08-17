package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioDemoConfigurationTest {
    private final JdbcDataSource dataSource = dataSource();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
            .withBean(OperatorRegistry.class, DefaultOperatorRegistry::new)
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
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalService.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalOracle.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalBaselineService.class)
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
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalService.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalOracle.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalBaselineService.class)
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
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalService.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalOracle.class)
                        .doesNotHaveBean(CapabilityStudioFeatureRehearsalBaselineService.class)
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
                        .hasSingleBean(CapabilityStudioFeatureRehearsalService.class)
                        .hasSingleBean(CapabilityStudioFeatureRehearsalOracle.class)
                        .hasSingleBean(CapabilityStudioFeatureRehearsalBaselineService.class)
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
                        .hasSingleBean(CapabilityStudioFeatureRehearsalService.class)
                        .hasSingleBean(CapabilityStudioFeatureRehearsalOracle.class)
                        .hasSingleBean(CapabilityStudioFeatureRehearsalBaselineService.class));
    }
}
