package com.leanowtech.bloge.gateway.visual.authoring.flow;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReusableFlowDraftSchemaReadinessTest {
    @Test
    void acceptsV014AndRejectsMissingAuthority() {
        JdbcDataSource ready = ready("ready");
        assertThatCode(() -> new ReusableFlowDraftSchemaReadiness(new JdbcTemplate(ready)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new ReusableFlowDraftSchemaReadiness(
                new JdbcTemplate(dataSource("missing"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V20260901_014");
    }

    @Test
    void rejectsMissingExactHeadForeignKey() {
        JdbcTemplate jdbc = new JdbcTemplate(ready("head-fk"));
        jdbc.execute("ALTER TABLE rg_authoring_flow_heads DROP CONSTRAINT rg_authoring_flow_heads_revision_fk");
        jdbc.execute("""
                ALTER TABLE rg_authoring_flow_heads
                    ADD CONSTRAINT rg_authoring_flow_heads_revision_fk
                    FOREIGN KEY (tenant_id, project_id, environment_id, flow_id)
                    REFERENCES rg_authoring_flow_identities
                        (tenant_id, project_id, environment_id, flow_id)
                """);
        assertNotReady(jdbc);
    }

    @Test
    void rejectsWeakenedCommandExpectationConstraint() {
        JdbcTemplate jdbc = new JdbcTemplate(ready("command-check"));
        jdbc.execute("ALTER TABLE rg_authoring_flow_commands DROP CONSTRAINT rg_authoring_flow_commands_expected_ck");
        jdbc.execute("ALTER TABLE rg_authoring_flow_commands "
                + "ADD CONSTRAINT rg_authoring_flow_commands_expected_ck "
                + "CHECK (expected_mode IN ('CREATE', 'MATCH'))");
        assertNotReady(jdbc);
    }

    @Test
    void rejectsMissingCommandPrimaryKey() {
        JdbcTemplate jdbc = new JdbcTemplate(ready("command-pk"));
        jdbc.execute("ALTER TABLE rg_authoring_flow_commands DROP PRIMARY KEY");
        assertNotReady(jdbc);
    }

    private static void assertNotReady(JdbcTemplate jdbc) {
        assertThatThrownBy(() -> new ReusableFlowDraftSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("V20260901_014");
    }

    private static JdbcDataSource ready(String name) {
        JdbcDataSource source = dataSource(name);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260901_014__reusable_flow_drafts.sql")).execute(source);
        return source;
    }

    private static JdbcDataSource dataSource(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:flow-readiness-" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return source;
    }
}
