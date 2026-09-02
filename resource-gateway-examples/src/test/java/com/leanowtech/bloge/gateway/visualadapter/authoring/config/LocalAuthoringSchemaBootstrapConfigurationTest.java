package com.leanowtech.bloge.gateway.visualadapter.authoring.config;

import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.ApiConnectionAuthoringSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.StandaloneFixtureSetSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowPublicationSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceAuthoringSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceConnectionSnapshotSchemaReadiness;
import com.leanowtech.bloge.gateway.visual.authoring.simulation.SimulationRunSchemaReadiness;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalAuthoringSchemaBootstrapConfigurationTest {

    @Test
    void installsEveryAuthoringMigrationOnceForTheLocalH2Launcher() {
        JdbcTemplate jdbc = jdbc("complete");

        LocalAuthoringSchemaBootstrapConfiguration.migrate(jdbc);
        LocalAuthoringSchemaBootstrapConfiguration.migrate(jdbc);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_local_authoring_schema_migrations", Integer.class))
                .isEqualTo(19);
        assertThatCode(() -> {
            new ApiResourceAuthoringSchemaReadiness(jdbc);
            new ApiConnectionAuthoringSchemaReadiness(jdbc);
            new ApiResourceConnectionSnapshotSchemaReadiness(jdbc);
            new ApiFixtureSetSchemaReadiness(jdbc);
            new SimulationRunSchemaReadiness(jdbc);
            new ReusableFlowDraftSchemaReadiness(jdbc);
            new ReusableFlowPublicationSchemaReadiness(jdbc);
            new StandaloneFixtureSetSchemaReadiness(jdbc);
        }).doesNotThrowAnyException();
    }

    @Test
    void rejectsAChangedMigrationInsteadOfSilentlyAcceptingSchemaDrift() {
        JdbcTemplate jdbc = jdbc("drift");
        LocalAuthoringSchemaBootstrapConfiguration.migrate(jdbc);
        jdbc.update("UPDATE rg_local_authoring_schema_migrations SET checksum=? WHERE version=?",
                "sha256:" + "0".repeat(64), "V20260830_001");

        assertThatThrownBy(() -> LocalAuthoringSchemaBootstrapConfiguration.migrate(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260830_001", "checksum");
    }

    private static JdbcTemplate jdbc(String name) {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:local-authoring-bootstrap-" + name
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return new JdbcTemplate(source);
    }
}
