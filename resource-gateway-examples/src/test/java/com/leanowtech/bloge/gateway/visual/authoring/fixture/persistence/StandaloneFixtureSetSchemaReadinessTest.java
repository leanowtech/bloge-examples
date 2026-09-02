package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class StandaloneFixtureSetSchemaReadinessTest {
    @Test
    void acceptsExactV016ThroughV018AndRejectsMissingReviewCompletionAuthority() {
        JdbcDataSource ready = source("ready");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260901_014__reusable_flow_drafts.sql"),
                new ClassPathResource("db/postgresql/V20260901_015__reusable_flow_publications.sql"),
                new ClassPathResource("db/postgresql/V20260901_016__standalone_flow_fixture_sets.sql"),
                new ClassPathResource("db/postgresql/V20260901_017__fixture_share_requests.sql"),
                new ClassPathResource("db/postgresql/V20260901_018__fixture_review_completion.sql"),
                new ClassPathResource(
                        "db/postgresql/V20260902_019__standalone_component_fixture_subjects.sql"))
                .execute(ready);
        assertThatCode(() -> new StandaloneFixtureSetSchemaReadiness(new JdbcTemplate(ready)))
                .doesNotThrowAnyException();

        JdbcDataSource v17Only = source("v17-only");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260901_014__reusable_flow_drafts.sql"),
                new ClassPathResource("db/postgresql/V20260901_015__reusable_flow_publications.sql"),
                new ClassPathResource("db/postgresql/V20260901_016__standalone_flow_fixture_sets.sql"),
                new ClassPathResource("db/postgresql/V20260901_017__fixture_share_requests.sql"))
                .execute(v17Only);
        assertThatThrownBy(() -> new StandaloneFixtureSetSchemaReadiness(
                new JdbcTemplate(v17Only))).isInstanceOf(RuntimeException.class);

        JdbcDataSource v18Only = source("v18-only");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260901_014__reusable_flow_drafts.sql"),
                new ClassPathResource("db/postgresql/V20260901_015__reusable_flow_publications.sql"),
                new ClassPathResource("db/postgresql/V20260901_016__standalone_flow_fixture_sets.sql"),
                new ClassPathResource("db/postgresql/V20260901_017__fixture_share_requests.sql"),
                new ClassPathResource("db/postgresql/V20260901_018__fixture_review_completion.sql"))
                .execute(v18Only);
        assertThatThrownBy(() -> new StandaloneFixtureSetSchemaReadiness(
                new JdbcTemplate(v18Only))).isInstanceOf(RuntimeException.class);
    }

    private static JdbcDataSource source(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:standalone-fixture-readiness-" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return source;
    }
}
