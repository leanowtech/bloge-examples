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
    void acceptsExactV016AndRejectsMissingAuthority() {
        JdbcDataSource ready = source("ready");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260901_014__reusable_flow_drafts.sql"),
                new ClassPathResource("db/postgresql/V20260901_015__reusable_flow_publications.sql"),
                new ClassPathResource("db/postgresql/V20260901_016__standalone_flow_fixture_sets.sql"),
                new ClassPathResource("db/postgresql/V20260901_017__fixture_share_requests.sql"))
                .execute(ready);
        assertThatCode(() -> new StandaloneFixtureSetSchemaReadiness(new JdbcTemplate(ready)))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> new StandaloneFixtureSetSchemaReadiness(
                new JdbcTemplate(source("missing")))).isInstanceOf(RuntimeException.class);
    }

    private static JdbcDataSource source(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:standalone-fixture-readiness-" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return source;
    }
}
