package com.leanowtech.bloge.gateway.visual.authoring.application.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringFacade;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringPrecondition;
import com.leanowtech.bloge.gateway.visual.authoring.application.connection.ApiConnectionAuthoringRequest;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.JdbcApiConnectionAuthoringStore;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.DefaultFixtureSetMaterializer;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.ApiFixtureSetCommitStoreException;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.JdbcApiFixtureSetCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringFingerprints;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ClaimResult;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStoreException;
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
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

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
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        JdbcApiResourceCommitStore resources = new JdbcApiResourceCommitStore(jdbc, transactions, JSON,
                Duration.ofSeconds(30), resourceDecisions, new DefaultApiResourceProjectionCompiler(
                new ApiConnectionStoreResourceProjectionResolver(connections)));
        JdbcApiFixtureSetCommitStore fixtures = new JdbcApiFixtureSetCommitStore(jdbc, transactions, JSON);
        ApiResourceAuthoringFacade facade = new ApiResourceAuthoringFacade(
                resources, connections, resourceDecisions, fixtures,
                new DefaultFixtureSetMaterializer(), transactions);
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

    @Test
    void nestedAuthNoneConnectionAndResourceCommitAsOneReplayableAuthority() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:resource-nested-connection-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        applyMigrations(dataSource);
        ApiConnectionDecisions connectionDecisions = new ApiConnectionDecisions(JSON);
        JdbcApiConnectionAuthoringStore connections = new JdbcApiConnectionAuthoringStore(
                dataSource, JSON, Duration.ofSeconds(30), connectionDecisions, Clock.systemUTC());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ApiResourceDecisions resourceDecisions = new ApiResourceDecisions(JSON);
        JdbcApiResourceCommitStore resources = new JdbcApiResourceCommitStore(jdbc, transactions, JSON,
                Duration.ofSeconds(30), resourceDecisions, new DefaultApiResourceProjectionCompiler(
                new ApiConnectionStoreResourceProjectionResolver(connections)));
        ApiResourceAuthoringFacade facade = new ApiResourceAuthoringFacade(resources, connections,
                resourceDecisions, null, null, transactions, connectionDecisions);
        ApiResourceAuthoringRequest request = new ApiResourceAuthoringRequest(
                SCOPE, "actor", "profile", "nested-key", ApiResourceAuthoringPrecondition.create(),
                new ApiResourceSaveCommand(ApiResourceSaveCommand.SCHEMA_VERSION,
                        ApiResourceSaveCommand.Connection.create(new ApiConnectionCommand(
                                "Profile API", "https://created.example.test",
                                ApiConnectionCommand.Auth.none())),
                        command(), ApiResourceSaveCommand.DefaultFixture.none()));

        ApiResourceAuthoringResult first = facade.save(request);
        ApiResourceAuthoringResult replay = facade.save(request);

        String connectionId = first.stored().resource().connectionId();
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.stored()).isEqualTo(first.stored());
        assertThat(connectionId).startsWith("connection-");
        assertThat(connections.findHead(SCOPE, connectionId)).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_heads", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_heads", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions WHERE state='STAGED'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions WHERE state='STAGED'",
                Integer.class)).isZero();
    }

    @Test
    void nestedConnectionRollsBackWhenTheOuterResourceCommitFails() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:resource-nested-rollback-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        applyMigrations(dataSource);
        ApiConnectionDecisions connectionDecisions = new ApiConnectionDecisions(JSON);
        JdbcApiConnectionAuthoringStore connections = new JdbcApiConnectionAuthoringStore(
                dataSource, JSON, Duration.ofSeconds(30), connectionDecisions, Clock.systemUTC());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ApiResourceDecisions resourceDecisions = new ApiResourceDecisions(JSON);
        JdbcApiResourceCommitStore delegate = new JdbcApiResourceCommitStore(jdbc, transactions, JSON,
                Duration.ofSeconds(30), resourceDecisions, new DefaultApiResourceProjectionCompiler(
                new ApiConnectionStoreResourceProjectionResolver(connections)));
        JdbcApiResourceCommitStore resources = spy(delegate);
        doThrow(new ApiResourceCommitStoreException(ApiResourceCommitStoreException.Code.INTEGRITY,
                "forced commit failure")).when(resources).commit(any(), any());
        ApiResourceAuthoringFacade facade = new ApiResourceAuthoringFacade(resources, connections,
                resourceDecisions, null, null, transactions, connectionDecisions);
        ApiResourceSaveCommand save = new ApiResourceSaveCommand(ApiResourceSaveCommand.SCHEMA_VERSION,
                ApiResourceSaveCommand.Connection.create(new ApiConnectionCommand(
                        "Profile API", "https://created.example.test", ApiConnectionCommand.Auth.none())),
                command(), ApiResourceSaveCommand.DefaultFixture.none());

        assertThatThrownBy(() -> facade.save(new ApiResourceAuthoringRequest(
                SCOPE, "actor", "profile", "nested-rollback-key",
                ApiResourceAuthoringPrecondition.create(), save)))
                .isInstanceOf(ApiResourceAuthoringFailure.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_heads", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_heads", Integer.class)).isZero();
    }

    @Test
    void fromExamplesCommitsFixtureAndResourceInOneDatabaseTransaction() throws Exception {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:resource-fixture-facade-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        applyMigrations(dataSource);
        ApiConnectionDecisions connectionDecisions = new ApiConnectionDecisions(JSON);
        JdbcApiConnectionAuthoringStore connections = new JdbcApiConnectionAuthoringStore(
                dataSource, JSON, Duration.ofSeconds(30), connectionDecisions, Clock.systemUTC());
        new ApiConnectionAuthoringFacade(connections, connectionDecisions).save(
                new ApiConnectionAuthoringRequest(SCOPE, "actor", "customer", "connection-key",
                        ApiConnectionAuthoringPrecondition.create(), new ApiConnectionCommand(
                        "Customer", "https://customer.example.test", ApiConnectionCommand.Auth.none())));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ApiResourceDecisions resourceDecisions = new ApiResourceDecisions(JSON);
        JdbcApiResourceCommitStore resources = new JdbcApiResourceCommitStore(jdbc, transactions, JSON,
                Duration.ofSeconds(30), resourceDecisions, new DefaultApiResourceProjectionCompiler(
                new ApiConnectionStoreResourceProjectionResolver(connections)));
        JdbcApiFixtureSetCommitStore fixtures = new JdbcApiFixtureSetCommitStore(jdbc, transactions, JSON);
        ApiResourceAuthoringFacade facade = new ApiResourceAuthoringFacade(resources, connections,
                resourceDecisions, fixtures, new DefaultFixtureSetMaterializer(), transactions);
        ApiResourceAuthoringRequest request = new ApiResourceAuthoringRequest(
                SCOPE, "actor", "profile", "resource-fixture-key", ApiResourceAuthoringPrecondition.create(),
                new ApiResourceSaveCommand(ApiResourceSaveCommand.SCHEMA_VERSION,
                        ApiResourceSaveCommand.Connection.existing("customer"), command(),
                        ApiResourceSaveCommand.DefaultFixture.fromExamples("Profile defaults", List.of("one"))));

        ApiResourceAuthoringResult first = facade.save(request);
        ApiResourceAuthoringResult replay = facade.save(request);

        assertThat(replay.replayed()).isTrue();
        assertThat(first.stored().receipt().body().path("defaultFixture").path("fixtureSetId").asText())
                .isEqualTo("profile:r1");
        assertThat(fixtures.findHead(SCOPE, "profile:r1")).isPresent();
        assertThat(fixtures.findRevision(SCOPE, "profile:r1", 1)).isPresent();
        var fixtureSubject = fixtures.findHead(SCOPE, "profile:r1").orElseThrow()
                .generated().view().subject();
        assertThat(fixtures.listSummariesBySubject(SCOPE, fixtureSubject)).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_fixture_set_revisions WHERE state='STAGED'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_fixture_set_heads", Integer.class))
                .isEqualTo(1);

        jdbc.update("UPDATE rg_authoring_command_journal SET target_id='other-resource' "
                + "WHERE endpoint='API_RESOURCE_SAVE'");
        jdbc.update("UPDATE rg_authoring_command_attempts SET target_id='other-resource' "
                + "WHERE endpoint='API_RESOURCE_SAVE'");
        assertThatThrownBy(() -> fixtures.findHead(SCOPE, "profile:r1"))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);
        assertThatThrownBy(() -> fixtures.findRevision(SCOPE, "profile:r1", 1))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);
        jdbc.update("UPDATE rg_authoring_command_journal SET target_id='profile' "
                + "WHERE endpoint='API_RESOURCE_SAVE'");
        jdbc.update("UPDATE rg_authoring_command_attempts SET target_id='profile' "
                + "WHERE endpoint='API_RESOURCE_SAVE'");

        jdbc.update("UPDATE rg_api_fixture_set_revisions SET subject_revision=2");
        assertThatThrownBy(() -> fixtures.listSummariesBySubject(SCOPE, fixtureSubject))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);
        jdbc.update("UPDATE rg_api_fixture_set_revisions SET subject_revision=1");

        String resourceJson = jdbc.queryForObject(
                "SELECT spec_json FROM rg_api_resource_revisions WHERE resource_id='profile'", String.class);
        com.fasterxml.jackson.databind.node.ObjectNode resourceTamper =
                (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(resourceJson);
        resourceTamper.put("displayName", "tampered resource");
        jdbc.update("UPDATE rg_api_resource_revisions SET spec_json=? WHERE resource_id='profile'",
                resourceTamper.toString());
        assertThatThrownBy(() -> fixtures.findHead(SCOPE, "profile:r1"))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);
        jdbc.update("UPDATE rg_api_resource_revisions SET spec_json=? WHERE resource_id='profile'", resourceJson);

        String generatedJson = jdbc.queryForObject(
                "SELECT generated_json FROM rg_api_fixture_set_revisions", String.class);
        String fixtureFingerprint = jdbc.queryForObject(
                "SELECT fingerprint FROM rg_api_fixture_set_revisions", String.class);
        com.fasterxml.jackson.databind.node.ObjectNode tampered = (com.fasterxml.jackson.databind.node.ObjectNode)
                JSON.readTree(generatedJson);
        tampered.withObject("/view").put("displayName", "tampered");
        jdbc.update("UPDATE rg_api_fixture_set_revisions SET generated_json=?", tampered.toString());
        assertThatThrownBy(() -> fixtures.findHead(SCOPE, "profile:r1"))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);

        tampered = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(generatedJson);
        ((com.fasterxml.jackson.databind.node.ObjectNode) tampered.path("view").path("cases").get(0)
                .path("input")).put("id", "tampered");
        jdbc.update("UPDATE rg_api_fixture_set_revisions SET generated_json=?", tampered.toString());
        assertThatThrownBy(() -> fixtures.findHead(SCOPE, "profile:r1"))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);

        jdbc.update("UPDATE rg_api_fixture_set_revisions SET generated_json=?, fingerprint=?",
                generatedJson, "sha256:" + "0".repeat(64));
        assertThatThrownBy(() -> fixtures.findHead(SCOPE, "profile:r1"))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);

        jdbc.update("UPDATE rg_api_fixture_set_revisions SET fingerprint=?", fixtureFingerprint);
        String originalReceipt = jdbc.queryForObject("SELECT receipt_json FROM rg_authoring_command_journal "
                + "WHERE endpoint='API_RESOURCE_SAVE'", String.class);
        String originalReceiptFingerprint = jdbc.queryForObject(
                "SELECT receipt_fingerprint FROM rg_authoring_command_journal "
                        + "WHERE endpoint='API_RESOURCE_SAVE'", String.class);
        com.fasterxml.jackson.databind.node.ObjectNode receipt =
                (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(originalReceipt);
        receipt.withObject("/connection").put("connectionId", "other-connection");
        jdbc.update("UPDATE rg_authoring_command_journal SET receipt_json=?, receipt_fingerprint=? "
                        + "WHERE endpoint='API_RESOURCE_SAVE'",
                receipt.toString(), AuthoringFingerprints.of(receipt));
        assertThatThrownBy(() -> fixtures.findRevision(SCOPE, "profile:r1", 1))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);

        receipt = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(originalReceipt);
        receipt.withObject("/defaultFixture").put("fingerprint", "sha256:" + "1".repeat(64));
        jdbc.update("UPDATE rg_authoring_command_journal SET receipt_json=?, receipt_fingerprint=? "
                        + "WHERE endpoint='API_RESOURCE_SAVE'",
                receipt.toString(), AuthoringFingerprints.of(receipt));
        assertThatThrownBy(() -> fixtures.findRevision(SCOPE, "profile:r1", 1))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);

        jdbc.update("UPDATE rg_authoring_command_journal SET receipt_json=?, receipt_fingerprint=? "
                        + "WHERE endpoint='API_RESOURCE_SAVE'",
                originalReceipt, originalReceiptFingerprint);
        jdbc.update("DELETE FROM rg_api_resource_heads WHERE resource_id='profile'");
        jdbc.update("DELETE FROM rg_api_resource_revisions WHERE resource_id='profile'");
        assertThatThrownBy(() -> fixtures.findHead(SCOPE, "profile:r1"))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);
        assertThatThrownBy(() -> fixtures.findRevision(SCOPE, "profile:r1", 1))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void replacementAttemptRemovesItsSupersededInvisibleFixtureStage() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:resource-fixture-takeover-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        applyMigrations(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ApiResourceDecisions decisions = new ApiResourceDecisions(JSON);
        JdbcApiResourceCommitStore resources = new JdbcApiResourceCommitStore(jdbc, transactions, JSON,
                Duration.ofSeconds(30), decisions, (scope, resource) -> {
                    throw new AssertionError("projection compilation is not part of claim takeover");
                });
        JdbcApiFixtureSetCommitStore fixtures = new JdbcApiFixtureSetCommitStore(jdbc, transactions, JSON);
        CommandKey key = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE,
                "profile", "takeover-key");
        String requestFingerprint = "sha256:" + "a".repeat(64);
        var first = (ClaimResult.Acquired) resources.claim(key, requestFingerprint, ExpectedRevision.create());
        var resource = decisions.next(Optional.empty(), "profile", "customer", command(),
                ExpectedRevision.create());
        var generated = new DefaultFixtureSetMaterializer().generate(resource,
                (ApiResourceSaveCommand.DefaultFixture.FromExamples)
                        ApiResourceSaveCommand.DefaultFixture.fromExamples("Profile defaults", List.of("one")));
        fixtures.stage(first.lease(), generated);
        jdbc.update("UPDATE rg_authoring_command_journal SET lease_until=? WHERE command_id=?",
                Timestamp.from(Instant.EPOCH), first.lease().commandId());
        jdbc.update("UPDATE rg_authoring_command_attempts SET lease_until=? WHERE command_id=?",
                Timestamp.from(Instant.EPOCH), first.lease().commandId());

        var replacement = (ClaimResult.Acquired) resources.claim(key, requestFingerprint,
                ExpectedRevision.create());
        fixtures.stage(replacement.lease(), generated);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_fixture_set_revisions "
                + "WHERE command_id=? AND state='STAGED'", Integer.class, first.lease().commandId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_no FROM rg_api_fixture_set_revisions "
                + "WHERE command_id=? AND state='STAGED'", Integer.class, first.lease().commandId()))
                .isEqualTo(replacement.lease().attemptNo());
    }

    @Test
    void fixtureChildRejectsAnAmbientTransactionForAnotherDataSource() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:fixture-transaction-fence-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        applyMigrations(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ApiResourceDecisions decisions = new ApiResourceDecisions(JSON);
        JdbcApiResourceCommitStore resources = new JdbcApiResourceCommitStore(jdbc, transactions, JSON,
                Duration.ofSeconds(30), decisions, (scope, resource) -> {
                    throw new AssertionError("projection compilation is not part of the transaction fence");
                });
        JdbcApiFixtureSetCommitStore fixtures = new JdbcApiFixtureSetCommitStore(jdbc, transactions, JSON);
        CommandKey key = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE,
                "profile", "transaction-fence-key");
        var lease = ((ClaimResult.Acquired) resources.claim(
                key, "sha256:" + "b".repeat(64), ExpectedRevision.create())).lease();
        var resource = decisions.next(Optional.empty(), "profile", "customer", command(),
                ExpectedRevision.create());
        var generated = new DefaultFixtureSetMaterializer().generate(resource,
                (ApiResourceSaveCommand.DefaultFixture.FromExamples)
                        ApiResourceSaveCommand.DefaultFixture.fromExamples("Profile defaults", List.of("one")));
        fixtures.stage(lease, generated);
        DataSource otherDataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:fixture-wrong-transaction-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        TransactionTemplate otherTransactions = new TransactionTemplate(
                new DataSourceTransactionManager(otherDataSource));

        assertThatThrownBy(() -> otherTransactions.executeWithoutResult(status -> fixtures.commitChild(lease)))
                .isInstanceOf(ApiFixtureSetCommitStoreException.class)
                .extracting("code").isEqualTo(ApiFixtureSetCommitStoreException.Code.INTEGRITY);
        assertThat(fixtures.findHead(SCOPE, generated.view().fixtureSetId())).isEmpty();
    }

    @Test
    void resourceCommitFailureRollsBackAndCleansTheFixtureChild() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:resource-fixture-rollback-" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        applyMigrations(dataSource);
        ApiConnectionDecisions connectionDecisions = new ApiConnectionDecisions(JSON);
        JdbcApiConnectionAuthoringStore connections = new JdbcApiConnectionAuthoringStore(
                dataSource, JSON, Duration.ofSeconds(30), connectionDecisions, Clock.systemUTC());
        new ApiConnectionAuthoringFacade(connections, connectionDecisions).save(
                new ApiConnectionAuthoringRequest(SCOPE, "actor", "customer", "connection-key",
                        ApiConnectionAuthoringPrecondition.create(), new ApiConnectionCommand(
                        "Customer", "https://customer.example.test", ApiConnectionCommand.Auth.none())));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ApiResourceDecisions decisions = new ApiResourceDecisions(JSON);
        JdbcApiResourceCommitStore delegate = new JdbcApiResourceCommitStore(jdbc, transactions, JSON,
                Duration.ofSeconds(30), decisions, new DefaultApiResourceProjectionCompiler(
                new ApiConnectionStoreResourceProjectionResolver(connections)));
        JdbcApiResourceCommitStore resources = spy(delegate);
        doThrow(new ApiResourceCommitStoreException(ApiResourceCommitStoreException.Code.INTEGRITY,
                "forced commit failure")).when(resources).commit(any(), any());
        JdbcApiFixtureSetCommitStore fixtures = new JdbcApiFixtureSetCommitStore(jdbc, transactions, JSON);
        ApiResourceAuthoringFacade facade = new ApiResourceAuthoringFacade(resources, connections,
                decisions, fixtures, new DefaultFixtureSetMaterializer(), transactions);

        assertThatThrownBy(() -> facade.save(new ApiResourceAuthoringRequest(
                SCOPE, "actor", "profile", "rollback-key", ApiResourceAuthoringPrecondition.create(),
                new ApiResourceSaveCommand(ApiResourceSaveCommand.SCHEMA_VERSION,
                        ApiResourceSaveCommand.Connection.existing("customer"), command(),
                        ApiResourceSaveCommand.DefaultFixture.fromExamples(
                                "Profile defaults", List.of("one"))))))
                .isInstanceOf(ApiResourceAuthoringFailure.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_fixture_set_revisions", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_fixture_set_heads", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_heads", Integer.class))
                .isZero();
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
                "V20260831_011__api_resource_connection_snapshot.sql",
                "V20260831_012__api_fixture_set_authority.sql"}) {
            new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/" + migration))
                    .execute(dataSource);
        }
    }
}
