package com.leanowtech.bloge.gateway.visual.authoring.flow;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReusableFlowPublicationSchemaReadinessTest {
    @Test
    void acceptsV014AndV015AndRejectsMissingAuthority() {
        JdbcTemplate ready = ready("ready");
        assertThatCode(() -> new ReusableFlowPublicationSchemaReadiness(ready)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new ReusableFlowPublicationSchemaReadiness(
                new JdbcTemplate(dataSource("missing"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V20260901_015");
    }

    @Test
    void rejectsWeakenedPublishedStatusConstraint() {
        JdbcTemplate jdbc = ready("status");
        jdbc.execute("ALTER TABLE rg_authoring_flow_versions "
                + "DROP CONSTRAINT rg_authoring_flow_versions_status_ck");
        jdbc.execute("ALTER TABLE rg_authoring_flow_versions "
                + "ADD CONSTRAINT rg_authoring_flow_versions_status_ck "
                + "CHECK (status IN ('PUBLISHED', 'DRAFT'))");
        assertNotReady(jdbc);
    }

    @Test
    void rejectsMissingExactPublicationIdentityForeignKey() {
        JdbcTemplate jdbc = ready("foreign-key");
        jdbc.execute("ALTER TABLE rg_authoring_flow_versions "
                + "DROP CONSTRAINT rg_authoring_flow_versions_identity_fk");
        assertNotReady(jdbc);
    }

    private static void assertNotReady(JdbcTemplate jdbc) {
        assertThatThrownBy(() -> new ReusableFlowPublicationSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V20260901_015");
    }

    private static JdbcTemplate ready(String name) {
        JdbcDataSource source = dataSource(name);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/postgresql/V20260901_014__reusable_flow_drafts.sql"),
                new ClassPathResource("db/postgresql/V20260901_015__reusable_flow_publications.sql"))
                .execute(source);
        return new JdbcTemplate(source);
    }

    private static JdbcDataSource dataSource(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:flow-publication-readiness-" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return source;
    }
}
