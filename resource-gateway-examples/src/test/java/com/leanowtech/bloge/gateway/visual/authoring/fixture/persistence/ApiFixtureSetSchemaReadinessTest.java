package com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiFixtureSetSchemaReadinessTest {
    @Test
    void v012IsRequiredAndExecutable() {
        DataSource dataSource = dataSource();
        applyThroughV011(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        assertThatThrownBy(() -> new ApiFixtureSetSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260831_012");

        apply(dataSource, "V20260831_012__api_fixture_set_authority.sql");
        new ApiFixtureSetSchemaReadiness(jdbc);
    }

    @Test
    void missingAuthorityColumnFailsClosed() {
        DataSource dataSource = dataSource();
        applyThroughV011(dataSource);
        apply(dataSource, "V20260831_012__api_fixture_set_authority.sql");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE rg_api_fixture_set_revisions DROP COLUMN generated_json");

        assertThatThrownBy(() -> new ApiFixtureSetSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260831_012");
    }

    @Test
    void missingAuthorityConstraintFailsClosed() {
        DataSource dataSource = readyDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE rg_api_fixture_set_revisions "
                + "DROP CONSTRAINT rg_api_fixture_set_revisions_status_ck");

        assertThatThrownBy(() -> new ApiFixtureSetSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260831_012");
    }

    @Test
    void sameNamedButWeakenedAuthorityConstraintFailsClosed() {
        DataSource dataSource = readyDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("ALTER TABLE rg_api_fixture_set_revisions "
                + "DROP CONSTRAINT rg_api_fixture_set_revisions_status_ck");
        jdbc.execute("ALTER TABLE rg_api_fixture_set_revisions "
                + "ADD CONSTRAINT rg_api_fixture_set_revisions_status_ck "
                + "CHECK (status IN ('PRIVATE_DRAFT', 'ACTIVE'))");

        assertThatThrownBy(() -> new ApiFixtureSetSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260831_012");
    }

    @Test
    void missingAuthorityIndexFailsClosed() {
        DataSource dataSource = readyDataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP INDEX rg_api_fixture_set_attempt_idx");

        assertThatThrownBy(() -> new ApiFixtureSetSchemaReadiness(jdbc))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("V20260831_012");
    }

    @Test
    void authorityProbeIsBoundToTheConnectionsCurrentSchema() throws Exception {
        DataSource ready = readyDataSource();
        try (Connection connection = ready.getConnection()) {
            SingleConnectionDataSource scoped = new SingleConnectionDataSource(connection, true);
            JdbcTemplate jdbc = new JdbcTemplate(scoped);
            jdbc.execute("CREATE SCHEMA decoy");
            jdbc.execute("SET SCHEMA decoy");
            jdbc.execute("CREATE TABLE rg_api_fixture_set_identities (tenant_id VARCHAR(128))");

            assertThatThrownBy(() -> new ApiFixtureSetSchemaReadiness(jdbc))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("V20260831_012");
        }
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

    private static DataSource readyDataSource() {
        DataSource dataSource = dataSource();
        applyThroughV011(dataSource);
        apply(dataSource, "V20260831_012__api_fixture_set_authority.sql");
        return dataSource;
    }

    private static void apply(DataSource dataSource, String migration) {
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/" + migration))
                .execute(dataSource);
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:fixture-readiness-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
