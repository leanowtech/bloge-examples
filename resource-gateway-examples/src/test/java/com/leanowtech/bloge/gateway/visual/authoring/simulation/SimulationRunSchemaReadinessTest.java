package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulationRunSchemaReadinessTest {
    @Test
    void acceptsV013AndRejectsMissingAuthority() {
        JdbcDataSource ready = dataSource("ready");
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260831_013__authoring_simulation_runs.sql")).execute(ready);

        assertThatCode(() -> new SimulationRunSchemaReadiness(new JdbcTemplate(ready)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new SimulationRunSchemaReadiness(
                new JdbcTemplate(dataSource("missing"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260831_013");
    }

    @Test
    void rejectsMissingPrimaryKey() {
        JdbcDataSource dataSource = readyDataSource("missing-pk");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE rg_authoring_simulation_runs DROP PRIMARY KEY");

        assertNotReady(jdbc);
    }

    @Test
    void rejectsWeakenedStatusConstraint() {
        JdbcDataSource dataSource = readyDataSource("weak-status");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE rg_authoring_simulation_runs "
                + "DROP CONSTRAINT rg_authoring_simulation_runs_status_ck");
        jdbc.execute("ALTER TABLE rg_authoring_simulation_runs "
                + "ADD CONSTRAINT rg_authoring_simulation_runs_status_ck "
                + "CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED', 'UNKNOWN'))");

        assertNotReady(jdbc);
    }

    @Test
    void rejectsMissingCompletionConstraint() {
        JdbcDataSource dataSource = readyDataSource("missing-completion");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE rg_authoring_simulation_runs "
                + "DROP CONSTRAINT rg_authoring_simulation_runs_completion_ck");

        assertNotReady(jdbc);
    }

    @Test
    void rejectsMissingRecoveryIndex() {
        JdbcDataSource dataSource = readyDataSource("missing-index");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP INDEX rg_authoring_simulation_runs_recovery_idx");

        assertNotReady(jdbc);
    }

    private static JdbcDataSource readyDataSource(String name) {
        JdbcDataSource dataSource = dataSource(name);
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/postgresql/V20260831_013__authoring_simulation_runs.sql")).execute(dataSource);
        return dataSource;
    }

    private static void assertNotReady(JdbcTemplate jdbc) {
        assertThatThrownBy(() -> new SimulationRunSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260831_013");
    }

    private static JdbcDataSource dataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:simulation-readiness-" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return dataSource;
    }
}
