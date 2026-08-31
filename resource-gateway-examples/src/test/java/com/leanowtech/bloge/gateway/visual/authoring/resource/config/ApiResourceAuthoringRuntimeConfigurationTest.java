package com.leanowtech.bloge.gateway.visual.authoring.resource.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceAuthoringSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionProjectionResolver;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionSnapshotSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceProjectionCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.JdbcApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.config.ApiResourceProjectionAdapterConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Startup contract tests for the opt-in durable API Resource runtime. */
class ApiResourceAuthoringRuntimeConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiResourceAuthoringRuntimeConfiguration.class,
                    ApiResourceProjectionAdapterConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void remainsAbsentWhenFeatureIsDisabled() {
        runner.withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource()))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ApiResourceAuthoringSchemaReadiness.class);
                    assertThat(context).doesNotHaveBean(ApiResourceCommitStore.class);
                });
    }

    @Test
    void enabledRuntimeUsesOneDataSourceAndCreatesUniqueReadinessAndJdbcStore() {
        DataSource dataSource = readyDataSource();
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .withBean(ApiResourceConnectionProjectionResolver.class,
                        () -> (scope, connectionId) -> java.util.Optional.empty())
                .withBean(ApiResourceProjectionCompiler.class,
                        () -> (scope, resource) -> null)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiResourceAuthoringSchemaReadiness.class);
                    assertThat(context).hasSingleBean(ApiResourceConnectionSnapshotSchemaReadiness.class);
                    assertThat(context).hasSingleBean(ApiResourceCommitStore.class);
                    assertThat(context).getBean(ApiResourceCommitStore.class)
                            .isInstanceOf(JdbcApiResourceCommitStore.class);
                });
    }

    @Test
    void enabledRuntimeCreatesDefaultCompilerWhenConnectionResolverIsPresent() {
        DataSource dataSource = readyDataSource();
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .withBean(ApiResourceConnectionProjectionResolver.class,
                        () -> (scope, connectionId) -> java.util.Optional.empty())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiResourceProjectionCompiler.class);
                    assertThat(context).hasSingleBean(ApiResourceCommitStore.class);
                });
    }

    @Test
    void enabledRuntimeFailsWhenSchemaWasNotInstalled() {
        DataSource dataSource = dataSource();
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .withBean(ApiResourceConnectionProjectionResolver.class,
                        () -> (scope, connectionId) -> java.util.Optional.empty())
                .withBean(ApiResourceProjectionCompiler.class,
                        () -> (scope, resource) -> null)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledRuntimeFailsWhenProjectionCompilerIsMissing() {
        DataSource dataSource = readyDataSource();
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("ApiResourceConnectionProjectionResolver");
                });
    }

    @Test
    void enabledRuntimeRejectsNonPositiveLease() {
        DataSource dataSource = readyDataSource();
        runner.withPropertyValues(
                        "gateway.authoring.api-resource.enabled=true",
                        "gateway.authoring.api-resource.lease-seconds=0")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .withBean(ApiResourceProjectionCompiler.class,
                        () -> (scope, resource) -> null)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("lease-seconds must be positive");
                });
    }

    @Test
    void customCommitStoreBacksOffDefaultJdbcStore() {
        DataSource dataSource = readyDataSource();
        ApiResourceCommitStore customStore = mock(ApiResourceCommitStore.class);
        runner.withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .withBean(ApiResourceConnectionProjectionResolver.class,
                        () -> (scope, connectionId) -> java.util.Optional.empty())
                .withBean(ApiResourceProjectionCompiler.class,
                        () -> (scope, resource) -> null)
                .withBean(ApiResourceCommitStore.class, () -> customStore)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiResourceCommitStore.class);
                    assertThat(context).getBean(ApiResourceCommitStore.class).isSameAs(customStore);
                    assertThat(context).hasSingleBean(ApiResourceAuthoringSchemaReadiness.class);
                    assertThat(context).hasSingleBean(ApiResourceConnectionSnapshotSchemaReadiness.class);
                });
    }

    private static DataSource readyDataSource() {
        DataSource dataSource = dataSource();
        new ResourceDatabasePopulator(
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260830_001__api_resource_authoring.sql"),
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260830_002__api_resource_concurrent_staging.sql"),
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260830_003__api_connection_secret_staging.sql"),
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260830_004__connection_metadata_authority.sql"),
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260830_005__pending_secret_store_protocol.sql"),
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260830_006__pending_secret_store_hardening.sql"),
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260831_007__pending_secret_store_protocol_closure.sql"),
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260831_008__pending_secret_store_child_cas_closure.sql"),
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260831_009__authoring_command_attempt_authority.sql"),
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260831_010__attempt_provenance_closure.sql"),
                new org.springframework.core.io.ClassPathResource(
                        "db/postgresql/V20260831_011__api_resource_connection_snapshot.sql"))
                .execute(dataSource);
        return dataSource;
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:api-authoring-runtime-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
