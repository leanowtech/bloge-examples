package com.leanowtech.bloge.gateway.visual.authoring.flow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.flow.JdbcReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReusableFlowPublicationRuntimeConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ReusableFlowPublicationRuntimeConfiguration.class)
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());

    @Test
    void disabledRuntimeIsAbsentAndEnabledRuntimeRequiresV015() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ReusableFlowPublicationStore.class));
        DataSource missing = dataSource("missing");
        runner.withPropertyValues("gateway.authoring.reusable-flow.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(missing))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(missing))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledRuntimeCreatesJdbcAuthorityAndCustomAuthorityBacksOff() {
        DataSource ready = readyDataSource("ready");
        runner.withPropertyValues("gateway.authoring.reusable-flow.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(ready))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(ready))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ReusableFlowPublicationSchemaReadiness.class);
                    assertThat(context).getBean(ReusableFlowPublicationStore.class)
                            .isInstanceOf(JdbcReusableFlowPublicationStore.class);
                });

        ReusableFlowPublicationStore custom = mock(ReusableFlowPublicationStore.class);
        DataSource other = readyDataSource("custom");
        runner.withPropertyValues("gateway.authoring.reusable-flow.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(other))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(other))
                .withBean(ReusableFlowPublicationStore.class, () -> custom)
                .run(context -> assertThat(context).getBean(ReusableFlowPublicationStore.class).isSameAs(custom));
    }

    private static DataSource readyDataSource(String name) {
        DataSource source = dataSource(name);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260901_014__reusable_flow_drafts.sql"),
                new ClassPathResource("db/postgresql/V20260901_015__reusable_flow_publications.sql"))
                .execute(source);
        return source;
    }

    private static DataSource dataSource(String name) {
        return new DriverManagerDataSource("jdbc:h2:mem:flow-publication-config-" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
