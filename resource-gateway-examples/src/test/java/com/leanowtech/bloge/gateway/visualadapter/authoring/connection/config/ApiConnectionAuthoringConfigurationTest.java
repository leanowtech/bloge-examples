package com.leanowtech.bloge.gateway.visualadapter.authoring.connection.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringRequest;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.JdbcApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Conditional production assembly contract for standalone Connection authoring. */
class ApiConnectionAuthoringConfigurationTest {
    private final ApplicationContextRunner applicationRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiConnectionAuthoringApplicationConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void disabledFeatureCreatesNoConnectionAuthoringBeans() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ApiConnectionAuthoringApplicationConfiguration.class,
                        ApiConnectionAuthoringRuntimeConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ApiConnectionAuthoringFacade.class);
                    assertThat(context).doesNotHaveBean(ApiConnectionAuthoringStore.class);
                    assertThat(context).doesNotHaveBean(ApiConnectionAuthoringSchemaReadiness.class);
                });
    }

    @Test
    void enabledApplicationUsesOneExplicitLifecycleStore() {
        applicationRunner
                .withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ApiConnectionAuthoringStore.class, () -> mock(ApiConnectionAuthoringStore.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiConnectionDecisions.class);
                    assertThat(context).hasSingleBean(ApiConnectionAuthoringFacade.class);
                });
    }

    @Test
    void enabledApplicationFailsClosedWithoutLifecycleStore() {
        applicationRunner
                .withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("ApiConnectionAuthoringStore");
                });
    }

    @Test
    void enabledRuntimeCreatesOneLifecycleStoreAndPersistsThroughTheFacade() {
        DataSource dataSource = readyDataSource();

        runtimeRunner(dataSource)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ApiConnectionAuthoringSchemaReadiness.class);
                    assertThat(context).hasSingleBean(ApiConnectionAuthoringStore.class);
                    assertThat(context).getBean(ApiConnectionAuthoringStore.class)
                            .isInstanceOf(JdbcApiConnectionAuthoringStore.class);
                    assertThat(context).hasSingleBean(ApiConnectionAuthoringFacade.class);

                    ApiConnectionAuthoringFacade facade = context.getBean(ApiConnectionAuthoringFacade.class);
                    var saved = facade.save(new ApiConnectionAuthoringRequest(
                            new AuthoringScope("tenant", "project", "dev"), "author", "customer",
                            "runtime-create", ApiConnectionAuthoringPrecondition.create(),
                            new ApiConnectionCommand("Customer API", "https://customer.example.com",
                                    ApiConnectionCommand.Auth.none())));
                    assertThat(facade.read(new AuthoringScope("tenant", "project", "dev"), "customer").view())
                            .isEqualTo(saved.view());
                });
    }

    @Test
    void enabledRuntimeFailsClosedWhenSchemaIsMissing() {
        runtimeRunner(dataSource())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("API Connection authoring schema is not ready");
                });
    }

    @Test
    void enabledRuntimeRejectsThePreV010AttemptAuthority() {
        runtimeRunner(migratedDataSource(9))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("V20260831_010");
                });
    }

    @Test
    void enabledRuntimeRejectsAChangedAttemptProvenanceIndex() {
        DataSource dataSource = readyDataSource();
        new JdbcTemplate(dataSource).execute("DROP INDEX rg_api_connection_heads_attempt_idx");

        runtimeRunner(dataSource)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("V20260831_010");
                });
    }

    @Test
    void enabledRuntimeRejectsAnAttemptStatusClosureWithoutSuperseded() {
        DataSource dataSource = readyDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE rg_authoring_command_attempts "
                + "DROP CONSTRAINT rg_authoring_command_attempts_status_ck");
        jdbc.execute("ALTER TABLE rg_authoring_command_attempts "
                + "ADD CONSTRAINT rg_authoring_command_attempts_status_ck "
                + "CHECK (status IN ('PREPARING', 'COMMITTED', 'FAILED'))");

        runtimeRunner(dataSource)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("V20260831_010");
                });
    }

    @Test
    void enabledRuntimeRejectsNonPositiveLeaseDuration() {
        runtimeRunner(readyDataSource())
                .withPropertyValues("gateway.authoring.api-resource.lease-seconds=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("lease-seconds must be positive");
                });
    }

    private static ApplicationContextRunner runtimeRunner(DataSource dataSource) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ApiConnectionAuthoringApplicationConfiguration.class,
                        ApiConnectionAuthoringRuntimeConfiguration.class))
                .withPropertyValues("gateway.authoring.api-resource.enabled=true")
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(DataSource.class, () -> dataSource)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource));
    }

    private static DataSource readyDataSource() {
        return migratedDataSource(10);
    }

    private static DataSource migratedDataSource(int count) {
        DataSource dataSource = dataSource();
        ClassPathResource[] migrations = java.util.stream.Stream.of(
                        "V20260830_001__api_resource_authoring.sql",
                        "V20260830_002__api_resource_concurrent_staging.sql",
                        "V20260830_003__api_connection_secret_staging.sql",
                        "V20260830_004__connection_metadata_authority.sql",
                        "V20260830_005__pending_secret_store_protocol.sql",
                        "V20260830_006__pending_secret_store_hardening.sql",
                        "V20260831_007__pending_secret_store_protocol_closure.sql",
                        "V20260831_008__pending_secret_store_child_cas_closure.sql",
                        "V20260831_009__authoring_command_attempt_authority.sql",
                        "V20260831_010__attempt_provenance_closure.sql")
                .limit(count).map(ApiConnectionAuthoringConfigurationTest::migration)
                .toArray(ClassPathResource[]::new);
        new ResourceDatabasePopulator(migrations)
                .execute(dataSource);
        return dataSource;
    }

    private static ClassPathResource migration(String name) {
        return new ClassPathResource("db/postgresql/" + name);
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.h2.Driver");
        dataSource.setUrl("jdbc:h2:mem:api-connection-runtime-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
