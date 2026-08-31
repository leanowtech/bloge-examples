package com.leanowtech.bloge.gateway.visual.authoring.application.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringRequest;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.JdbcApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.JdbcApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.ApiConnectionStoreResourceProjectionResolver;
import com.leanowtech.bloge.gateway.visualadapter.authoring.resource.DefaultApiResourceProjectionCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Same-database evidence for the first compound Resource-save tracer. */
class JdbcApiResourceAuthoringFacadeTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final ObjectMapper JSON = new ObjectMapper();
    private DataSource dataSource;

    @AfterEach
    void dropDatabaseObjects() {
        if (dataSource != null) new JdbcTemplate(dataSource).execute("DROP ALL OBJECTS");
    }

    @Test
    void existingConnectionResourceCreateAndReplayPersistExactSnapshot() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:resource-facade-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        applyMigrations(dataSource);
        ApiConnectionDecisions connectionDecisions = new ApiConnectionDecisions(JSON);
        JdbcApiConnectionAuthoringStore connections = new JdbcApiConnectionAuthoringStore(
                dataSource, JSON, Duration.ofSeconds(30), connectionDecisions, Clock.systemUTC());
        new ApiConnectionAuthoringFacade(connections, connectionDecisions).save(
                new ApiConnectionAuthoringRequest(SCOPE, "actor", "customer", "connection-key",
                        ApiConnectionAuthoringPrecondition.create(), new ApiConnectionCommand(
                        "Customer", "https://customer.example.test", ApiConnectionCommand.Auth.none(),
                        new ApiConnectionCommand.Defaults(5000, Map.of("X-Mode", "demo")))));
        ApiResourceDecisions resourceDecisions = new ApiResourceDecisions(JSON);
        JdbcApiResourceCommitStore resources = new JdbcApiResourceCommitStore(dataSource, JSON,
                Duration.ofSeconds(30), resourceDecisions, new DefaultApiResourceProjectionCompiler(
                new ApiConnectionStoreResourceProjectionResolver(connections)));
        ApiResourceAuthoringFacade facade = new ApiResourceAuthoringFacade(
                resources, connections, resourceDecisions);
        ApiResourceAuthoringRequest request = new ApiResourceAuthoringRequest(
                SCOPE, "actor", "profile", "resource-key", ApiResourceAuthoringPrecondition.create(),
                new ApiResourceSaveCommand(ApiResourceSaveCommand.SCHEMA_VERSION,
                        ApiResourceSaveCommand.Connection.existing("customer"), command(),
                        ApiResourceSaveCommand.DefaultFixture.none()));

        ApiResourceAuthoringResult first = facade.save(request);
        ApiResourceAuthoringResult replay = facade.save(request);

        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.stored().scope()).isEqualTo(first.stored().scope());
        assertThat(replay.stored().resource().ref()).isEqualTo(first.stored().resource().ref());
        assertThat(replay.stored().receipt()).isEqualTo(first.stored().receipt());
        assertThat(replay.stored().projections().connectionSnapshot())
                .isEqualTo(first.stored().projections().connectionSnapshot());
        assertThat(first.stored().projections().connectionSnapshot().revision()).isEqualTo(1);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT connection_revision FROM rg_api_resource_revisions",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT connection_metadata_fingerprint FROM rg_api_resource_revisions", String.class))
                .isEqualTo(connections.findHead(SCOPE, "customer").orElseThrow().metadataFingerprint());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions WHERE state='STAGED'",
                Integer.class)).isZero();

        com.fasterxml.jackson.databind.node.ObjectNode tampered = first.stored().receipt().body().deepCopy();
        tampered.withObject("/connection").put("revision", 2);
        jdbc.update("UPDATE rg_authoring_command_journal SET receipt_json=?, receipt_fingerprint=? "
                        + "WHERE command_id=(SELECT command_id FROM rg_api_resource_revisions)",
                tampered.toString(), AuthoringFingerprints.of(tampered));
        assertThatThrownBy(() -> facade.save(request))
                .isInstanceOf(ApiResourceAuthoringFailure.class)
                .extracting("code").isEqualTo(ApiResourceAuthoringFailure.Code.INTEGRITY);
    }

    private static ApiResourceCommand command() {
        SchemaEnvelope schema = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id"));
        JsonNode value = JSON.createObjectNode().put("id", "one");
        return new ApiResourceCommand("Get profile", null,
                new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(schema, schema),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.READ_ONLY,
                List.of(new ApiResourceCommand.Example("one", value, value)));
    }

    private static void applyMigrations(DataSource dataSource) {
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
            new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/" + migration))
                    .execute(dataSource);
        }
    }
}
