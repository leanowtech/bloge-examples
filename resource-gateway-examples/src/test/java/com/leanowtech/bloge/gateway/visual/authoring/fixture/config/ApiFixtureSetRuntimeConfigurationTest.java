package com.leanowtech.bloge.gateway.visual.authoring.fixture.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.DefaultFixtureSetMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.JdbcApiFixtureSetCommitStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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

/** Startup contract for the opt-in private Default Fixture authority. */
class ApiFixtureSetRuntimeConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiFixtureSetRuntimeConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void remainsAbsentWhenResourceAuthoringIsDisabled() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ApiFixtureSetSchemaReadiness.class);
            assertThat(context).doesNotHaveBean(DefaultFixtureSetMaterializer.class);
            assertThat(context).doesNotHaveBean(ApiFixtureSetCommitStore.class);
        });
    }

    @Test
    void enabledRuntimeCreatesOneMaterializerReadinessAndJdbcStore() {
        DataSource dataSource = readyDataSource();
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiFixtureSetSchemaReadiness.class);
                    assertThat(context).hasSingleBean(DefaultFixtureSetMaterializer.class);
                    assertThat(context).hasSingleBean(ApiFixtureSetCommitStore.class);
                    assertThat(context).getBean(ApiFixtureSetCommitStore.class)
                            .isInstanceOf(JdbcApiFixtureSetCommitStore.class);
                });
    }

    @Test
    void enabledRuntimeFailsClosedWithoutV012() {
        DataSource dataSource = dataSource();
        applyThroughV011(dataSource);
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("V20260831_012");
                });
    }

    @Test
    void customFixtureStoreBacksOffDefaultJdbcStore() {
        DataSource dataSource = readyDataSource();
        ApiFixtureSetCommitStore custom = mock(ApiFixtureSetCommitStore.class);
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .withBean(ApiFixtureSetCommitStore.class, () -> custom)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiFixtureSetCommitStore.class);
                    assertThat(context).getBean(ApiFixtureSetCommitStore.class).isSameAs(custom);
                    assertThat(context).hasSingleBean(ApiFixtureSetSchemaReadiness.class);
                    assertThat(context).hasSingleBean(DefaultFixtureSetMaterializer.class);
                });
    }

    @Test
    void enabledRuntimeRejectsTransactionManagerForAnotherDataSource() {
        DataSource dataSource = readyDataSource();
        DataSource other = dataSource();
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(other))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("must share one DataSource");
                });
    }

    private static DataSource readyDataSource() {
        DataSource dataSource = dataSource();
        applyThroughV011(dataSource);
        apply(dataSource, "V20260831_012__api_fixture_set_authority.sql");
        return dataSource;
    }

    private static void applyThroughV011(DataSource dataSource) {
        for (String migration : new String[]{
                "V20260830_001__api_resource_authoring.sql",
                "V20260830_002__api_resource_concurrent_staging.sql",
                "V20260830_003__api_connection_secret_staging.sql",
                "V20260830_004__connection_metadata_authority.sql",
                "V20260830_005__pending_secret_store_protocol.sql",
                "V20260830_006__pending_secret_store_hardening.sql",
                "V20260831_007__pending_secret_store_protocol_closure.sql",
                "V20260831_008__pending_secret_store_child_cas_closure.sql",
                "V20260831_009__authoring_command_attempt_authority.sql",
                "V20260831_010__attempt_provenance_closure.sql",
                "V20260831_011__api_resource_connection_snapshot.sql"}) {
            apply(dataSource, migration);
        }
    }

    private static void apply(DataSource dataSource, String migration) {
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/" + migration))
                .execute(dataSource);
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:fixture-runtime-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
