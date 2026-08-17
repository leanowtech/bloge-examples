package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityStudioDemoConfigurationTest {
    private final JdbcDataSource dataSource = dataSource();
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules())
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
                        .doesNotHaveBean(CapabilityStudioTutorialBranchRepository.class)
                        .doesNotHaveBean(CapabilityStudioTutorialBranchAuthority.class)
                        .doesNotHaveBean(CapabilityStudioDemoController.class));
    }

    @Test
    void isAbsentOutsideTestAndStagingEvenWhenExplicitlyEnabled() {
        runner.withPropertyValues("gateway.capability-studio.demo.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CapabilityStudioGoldenDemoPack.class)
                        .doesNotHaveBean(CapabilityStudioTutorialBranchRepository.class)
                        .doesNotHaveBean(CapabilityStudioTutorialBranchAuthority.class)
                        .doesNotHaveBean(CapabilityStudioDemoController.class));
    }

    @Test
    void isAbsentInProductionEvenWhenExplicitlyEnabled() {
        runner.withPropertyValues(
                        "spring.profiles.active=production",
                        "gateway.capability-studio.demo.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(CapabilityStudioGoldenDemoPack.class)
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
                        .hasSingleBean(CapabilityStudioGoldenDemoPack.class));
    }
}
