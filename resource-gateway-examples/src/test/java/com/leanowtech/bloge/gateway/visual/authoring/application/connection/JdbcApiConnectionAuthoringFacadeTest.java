package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.JdbcApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Real same-database claim/Connection facade evidence using H2 PostgreSQL mode. */
class JdbcApiConnectionAuthoringFacadeTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private DataSource dataSource;

    @AfterEach
    void dropDatabaseObjects() {
        if (dataSource != null) new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute("DROP ALL OBJECTS");
    }

    @Test
    void sameDatabaseClaimAndConnectionStoreCreateAndReplayExactly() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:connection-facade-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        for (String migration : new String[]{
                "db/postgresql/V20260830_001__api_resource_authoring.sql",
                "db/postgresql/V20260830_002__api_resource_concurrent_staging.sql",
                "db/postgresql/V20260830_003__api_connection_secret_staging.sql",
                "db/postgresql/V20260830_004__connection_metadata_authority.sql",
                "db/postgresql/V20260830_005__pending_secret_store_protocol.sql",
                "db/postgresql/V20260830_006__pending_secret_store_hardening.sql",
                "db/postgresql/V20260831_007__pending_secret_store_protocol_closure.sql",
                "db/postgresql/V20260831_008__pending_secret_store_child_cas_closure.sql",
                "db/postgresql/V20260831_009__authoring_command_attempt_authority.sql",
                "db/postgresql/V20260831_010__attempt_provenance_closure.sql"}) {
            new ResourceDatabasePopulator(new ClassPathResource(migration)).execute(dataSource);
        }
        ApiConnectionDecisions decisions = new ApiConnectionDecisions();
        ObjectMapper mapper = new ObjectMapper();
        var store = new JdbcApiConnectionAuthoringStore(dataSource, mapper, Duration.ofSeconds(30),
                decisions, Clock.systemUTC());
        var facade = new ApiConnectionAuthoringFacade(store, decisions, mapper);
        var request = new ApiConnectionAuthoringRequest(SCOPE, "actor", "customer", "jdbc-key",
                ApiConnectionAuthoringPrecondition.create(),
                new ApiConnectionCommand("Customer API", "https://customer.example.com",
                        ApiConnectionCommand.Auth.none()));

        ApiConnectionAuthoringResult first = facade.save(request);
        ApiConnectionAuthoringResult replay = facade.save(request);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.view()).isEqualTo(first.view());
        assertThat(replay.strongEtag()).isEqualTo(first.strongEtag());
    }
}
