package com.leanowtech.bloge.gateway.visual.authoring.fixture.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ReusableFlowFixtureModule;
import com.leanowtech.bloge.gateway.visual.authoring.application.fixture.ReusableFlowFixtureShareModule;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.WholeFlowFixtureMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.ParentFlowApplyCaseCompiler;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.JdbcStandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ComposableCatalog;
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

public class StandaloneFixtureSetRuntimeConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StandaloneFixtureSetRuntimeConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(ComposableCatalog.class, () -> mock(ComposableCatalog.class))
            .withBean(ReusableFlowPublicationStore.class,
                    () -> mock(ReusableFlowPublicationStore.class))
            .withBean(ReusableFlowDraftStore.class,
                    () -> mock(ReusableFlowDraftStore.class));

    @Test
    void disabledRuntimeIsAbsentAndEnabledV016V017CreatesExactModules() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(StandaloneFixtureSetStore.class);
            assertThat(context).doesNotHaveBean(ReusableFlowFixtureModule.class);
            assertThat(context).doesNotHaveBean(ReusableFlowFixtureShareModule.class);
        });

        DataSource dataSource = source("ready");
        migrate(dataSource);
        runner.withPropertyValues("gateway.authoring.reusable-flow.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(StandaloneFixtureSetSchemaReadiness.class);
                    assertThat(context).hasSingleBean(StandaloneFixtureSetStore.class);
                    assertThat(context).getBean(StandaloneFixtureSetStore.class)
                            .isInstanceOf(JdbcStandaloneFixtureSetStore.class);
                    assertThat(context).hasSingleBean(WholeFlowFixtureMaterializer.class);
                    assertThat(context).hasSingleBean(ParentFlowApplyCaseCompiler.class);
                    assertThat(context).hasSingleBean(ReusableFlowFixtureModule.class);
                    assertThat(context).hasSingleBean(ReusableFlowFixtureShareModule.class);
                });
    }

    @Test
    void enabledRuntimeFailsClosedWithoutV016() {
        DataSource dataSource = source("missing");
        runner.withPropertyValues("gateway.authoring.reusable-flow.enabled=true")
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(dataSource))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(dataSource))
                .run(context -> assertThat(context).hasFailed());
    }

    private static void migrate(DataSource source) {
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260901_014__reusable_flow_drafts.sql"),
                new ClassPathResource("db/postgresql/V20260901_015__reusable_flow_publications.sql"),
                new ClassPathResource("db/postgresql/V20260901_016__standalone_flow_fixture_sets.sql"),
                new ClassPathResource("db/postgresql/V20260901_017__fixture_share_requests.sql"))
                .execute(source);
    }

    private static DataSource source(String name) {
        return new DriverManagerDataSource("jdbc:h2:mem:standalone-fixture-config-" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
