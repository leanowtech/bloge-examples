package com.leanowtech.bloge.gateway.visual.authoring.simulation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.JdbcSimulationRunStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.JdbcSimulationRunV2Store;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunStore;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunV2Store;
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

class SimulationRunRuntimeConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SimulationRunRuntimeConfiguration.class)
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());

    @Test
    void disabledRuntimeIsAbsentAndEnabledRuntimeRequiresV013() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SimulationRunStore.class);
            assertThat(context).doesNotHaveBean(SimulationRunV2Store.class);
        });
        DataSource missing = dataSource("missing");
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(missing))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(missing))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledRuntimeCreatesOneJdbcAuthorityAndCustomStoreBacksOff() {
        DataSource ready = readyDataSource("ready");
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(ready))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(ready))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SimulationRunSchemaReadiness.class);
                    assertThat(context).getBean(SimulationRunStore.class)
                            .isInstanceOf(JdbcSimulationRunStore.class);
                    assertThat(context).getBean(SimulationRunV2Store.class)
                            .isInstanceOf(JdbcSimulationRunV2Store.class);
                });

        SimulationRunStore custom = mock(SimulationRunStore.class);
        DataSource other = readyDataSource("custom");
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(other))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(other))
                .withBean(SimulationRunStore.class, () -> custom)
                .run(context -> {
                    assertThat(context).getBean(SimulationRunStore.class).isSameAs(custom);
                    assertThat(context).hasSingleBean(SimulationRunV2Store.class);
                });
    }

    private static DataSource readyDataSource(String name) {
        DataSource dataSource = dataSource(name);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260831_013__authoring_simulation_runs.sql")).execute(dataSource);
        return dataSource;
    }

    private static DataSource dataSource(String name) {
        return new DriverManagerDataSource("jdbc:h2:mem:simulation-config-" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
