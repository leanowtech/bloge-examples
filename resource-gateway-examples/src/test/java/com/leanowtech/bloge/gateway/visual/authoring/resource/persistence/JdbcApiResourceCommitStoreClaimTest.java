package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcApiResourceCommitStoreClaimTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final CommandKey KEY = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k1");
    private static final String FP = "sha256:0000000000000000000000000000000000000000000000000000000000000001";
    private static final String FP2 = "sha256:0000000000000000000000000000000000000000000000000000000000000002";
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private static final String COMMITTED_PROJECTION_SET_FINGERPRINT =
            "sha256:de16d95d1d3ed0de22c35909f622769db684e4c470f0acfdeb30b967969f9d4a";

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:authoring-claim-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/V20260830_001__api_resource_authoring.sql"))
                .execute(dataSource);
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/V20260830_002__api_resource_concurrent_staging.sql"))
                .execute(dataSource);
        for (String migration : List.of(
                "V20260830_003__api_connection_secret_staging.sql",
                "V20260830_004__connection_metadata_authority.sql",
                "V20260830_005__pending_secret_store_protocol.sql",
                "V20260830_006__pending_secret_store_hardening.sql",
                "V20260831_007__pending_secret_store_protocol_closure.sql",
                "V20260831_008__pending_secret_store_child_cas_closure.sql",
                "V20260831_009__authoring_command_attempt_authority.sql")) {
            new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/" + migration)).execute(dataSource);
        }
        jdbc.update("INSERT INTO rg_api_connection_identities"
                + " (tenant_id, project_id, environment_id, connection_id)"
                + " VALUES ('tenant', 'project', 'dev', 'connection')");
    }

    @AfterEach
    void tearDown() { jdbc.execute("DROP ALL OBJECTS"); }

    @Test
    void claimInsertsBusyTakeoverAndReplayOrConflict() throws Exception {
        JdbcApiResourceCommitStore store = store();
        ClaimResult.Acquired first = (ClaimResult.Acquired) store.claim(KEY, FP, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
        assertThat(first.resumed()).isFalse();
        ApiResourceSpec stagedSpec = new ApiResourceDecisions().next(java.util.Optional.empty(), "profile", "connection", command(), com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
        insertStaged(first.lease(), stagedSpec);
        assertThat(store.claim(KEY, FP, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create()))
                .isInstanceOf(ClaimResult.Busy.class);
        assertThat(store.claim(KEY, FP2, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create()))
                .isInstanceOf(ClaimResult.Conflict.class);

        expireLease(first.lease());
        ClaimResult.Acquired takeover = (ClaimResult.Acquired) store.claim(KEY, FP, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.match(3));
        assertThat(takeover.resumed()).isTrue();
        assertThat(takeover.lease().commandId()).isEqualTo(first.lease().commandId());
        assertThat(takeover.lease().attemptNo()).isEqualTo(2);
        assertThat(takeover.lease().attemptToken()).isNotEqualTo(first.lease().attemptToken());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions WHERE command_id=? AND state='STAGED'", Integer.class, first.lease().commandId())).isZero();

        String terminalBody = "{}";
        jdbc.update("UPDATE rg_authoring_command_journal SET request_fingerprint=?, status='COMMITTED', receipt_schema='r', receipt_json=?, receipt_fingerprint=?, receipt_etag='\"etag\"' WHERE command_id=?",
                FP2, terminalBody, AuthoringFingerprints.of(JSON.readTree(terminalBody)), first.lease().commandId());
        assertThat(store.claim(KEY, FP, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create()))
                .isInstanceOf(ClaimResult.Conflict.class);
        assertThat(store.claim(KEY, FP2, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create()))
                .isInstanceOf(ClaimResult.Replay.class);
    }

    @Test
    void readsOnlyCommittedReadyHeadAndRevisionWithFullJson() throws Exception {
        ApiResourceSpec spec = new ApiResourceDecisions().next(java.util.Optional.empty(), "profile", "connection", command(), com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
        JsonNode descriptor = JSON.createObjectNode().put("kind", "DESCRIPTOR");
        JsonNode design = JSON.createObjectNode().put("kind", "DESIGN_CONTRACT");
        JsonNode operator = JSON.createObjectNode().put("kind", "OPERATOR");
        insertCommitted(spec, descriptor, design, operator);

        JdbcApiResourceCommitStore store = store();
        StoredApiResource head = store.findHead(SCOPE, "profile").orElseThrow();
        assertThat(head.resource()).isEqualTo(spec);
        assertThat(head.projections().descriptor().body()).isEqualTo(descriptor);
        assertThat(head.projections().designContract().body()).isEqualTo(design);
        assertThat(head.projections().operator().body()).isEqualTo(operator);
        assertThat(head.receipt().body().get("result").asText()).isEqualTo("profile");
        assertThat(store.findRevision(SCOPE, "profile", 1)).contains(head);

        jdbc.update("DELETE FROM rg_api_resource_heads WHERE resource_id='profile'");
        jdbc.update("UPDATE rg_api_resource_revisions SET state='STAGED' WHERE resource_id='profile'");
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
        assertThat(store.findRevision(SCOPE, "profile", 1)).isEmpty();
    }

    @Test
    void constructorRejectsNonPositiveLease() {
        assertThatThrownBy(() -> new JdbcApiResourceCommitStore(jdbc, transactions, JSON,
                Duration.ZERO, new ApiResourceDecisions(), (scope, resource) -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsMismatchedDataSources() {
        DataSource other = new DriverManagerDataSource(dataSourceUrl(), "sa", "");
        assertThatThrownBy(() -> new JdbcApiResourceCommitStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(other)), JSON,
                Duration.ofSeconds(1), new ApiResourceDecisions(), (scope, resource) -> null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcApiResourceCommitStore(new JdbcTemplate(),
                new TransactionTemplate(new DataSourceTransactionManager()), JSON,
                Duration.ofSeconds(1), new ApiResourceDecisions(), (scope, resource) -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void coordinateDimensionsAreIndependent() {
        List<CommandKey> keys = List.of(
                KEY,
                new CommandKey(new AuthoringScope("other", "project", "dev"), "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k1"),
                new CommandKey(SCOPE, "other-actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "k1"),
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "other-profile", "k1"),
                new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", "other-key"));
        for (CommandKey key : keys) assertThat(store().claim(key, FP, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create()))
                .isInstanceOf(ClaimResult.Acquired.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_authoring_command_journal", Integer.class)).isEqualTo(keys.size());
    }

    private String dataSourceUrl() {
        try { return dataSource.getConnection().getMetaData().getURL(); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    @Test
    void concurrentFirstClaimsProduceOneAcquiredAndOneBusy() throws Exception {
        String url = dataSource.getConnection().getMetaData().getURL();
        DataSource sharedLeft = new DriverManagerDataSource(url, "sa", "");
        DataSource sharedRight = new DriverManagerDataSource(url, "sa", "");
        JdbcApiResourceCommitStore left = store(new JdbcTemplate(sharedLeft),
                new TransactionTemplate(new DataSourceTransactionManager(sharedLeft)));
        JdbcApiResourceCommitStore right = store(new JdbcTemplate(sharedRight),
                new TransactionTemplate(new DataSourceTransactionManager(sharedRight)));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<ClaimResult> claim = () -> { barrier.await(); return left.claim(KEY, FP, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create()); };
            Future<ClaimResult> a = pool.submit(claim);
            Future<ClaimResult> b = pool.submit(() -> { barrier.await(); return right.claim(KEY, FP, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create()); });
            List<ClaimResult> results = List.of(a.get(), b.get());
            assertThat(results).anyMatch(result -> result instanceof ClaimResult.Acquired);
            assertThat(results).anyMatch(result -> result instanceof ClaimResult.Busy);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_authoring_command_journal", Integer.class)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void readsAreScopedAndStoredTamperingFailsClosed() throws Exception {
        ApiResourceSpec spec = new ApiResourceDecisions().next(java.util.Optional.empty(), "profile", "connection", command(), com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
        insertCommitted(spec, JSON.createObjectNode().put("kind", "DESCRIPTOR"), JSON.createObjectNode().put("kind", "DESIGN_CONTRACT"), JSON.createObjectNode().put("kind", "OPERATOR"));
        assertThat(store().findHead(new AuthoringScope("other", "project", "dev"), "profile")).isEmpty();
        assertThat(store().findRevision(SCOPE, "profile", 1)).isPresent();
        jdbc.update("DELETE FROM rg_api_resource_heads WHERE resource_id='profile'");
        assertThat(store().findRevision(SCOPE, "profile", 1)).isPresent();
        jdbc.update("UPDATE rg_api_resource_revisions SET spec_fingerprint=? WHERE resource_id='profile'", FP2);
        assertThatThrownBy(() -> store().findRevision(SCOPE, "profile", 1))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void receiptEtagTamperingFailsClosed() throws Exception {
        ApiResourceSpec spec = new ApiResourceDecisions().next(java.util.Optional.empty(), "profile", "connection", command(), com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
        insertCommitted(spec, JSON.createObjectNode().put("kind", "DESCRIPTOR"), JSON.createObjectNode().put("kind", "DESIGN_CONTRACT"), JSON.createObjectNode().put("kind", "OPERATOR"));
        jdbc.update("UPDATE rg_authoring_command_journal SET receipt_etag='\"tampered\"' WHERE command_id='cmd-read'");
        assertThatThrownBy(() -> store().findHead(SCOPE, "profile"))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void unknownSpecSchemaAndStatusFailClosed() throws Exception {
        ApiResourceSpec spec = new ApiResourceDecisions().next(java.util.Optional.empty(), "profile", "connection", command(), com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
        insertCommitted(spec, JSON.createObjectNode().put("kind", "DESCRIPTOR"), JSON.createObjectNode().put("kind", "DESIGN_CONTRACT"), JSON.createObjectNode().put("kind", "OPERATOR"));
        String json = JSON.writeValueAsString(spec).replace(ApiResourceSpec.SCHEMA_VERSION, "unknown.schema");
        jdbc.update("UPDATE rg_api_resource_revisions SET spec_json=? WHERE resource_id='profile'", json);
        assertThatThrownBy(() -> store().findHead(SCOPE, "profile"))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
        jdbc.update("UPDATE rg_api_resource_revisions SET spec_json=? WHERE resource_id='profile'", JSON.writeValueAsString(spec).replace("\"status\":\"DRAFT\"", "\"status\":\"ACTIVE\""));
        assertThatThrownBy(() -> store().findHead(SCOPE, "profile"))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void journalCoordinateTamperingCannotCrossReadScope() throws Exception {
        ApiResourceSpec spec = new ApiResourceDecisions().next(java.util.Optional.empty(), "profile", "connection", command(), com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
        insertCommitted(spec, JSON.createObjectNode().put("kind", "DESCRIPTOR"), JSON.createObjectNode().put("kind", "DESIGN_CONTRACT"), JSON.createObjectNode().put("kind", "OPERATOR"));
        jdbc.update("UPDATE rg_authoring_command_journal SET target_id='other-profile' WHERE command_id='cmd-read'");
        assertThat(store().findHead(SCOPE, "profile")).isEmpty();
        assertThat(store().findRevision(SCOPE, "profile", 1)).isEmpty();
    }

    private JdbcApiResourceCommitStore store() {
        return store(jdbc, transactions);
    }

    private JdbcApiResourceCommitStore store(JdbcTemplate template, TransactionTemplate tx) {
        return new JdbcApiResourceCommitStore(template, tx, JSON, Duration.ofSeconds(1),
                new ApiResourceDecisions(), (scope, resource) -> null);
    }

    private void insertStaged(CommandLease lease, ApiResourceSpec spec) throws Exception {
        String json = JSON.writeValueAsString(spec);
        jdbc.update("""
                INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state, spec_json,
                     spec_fingerprint, connection_id, strong_etag, command_id, attempt_no, attempt_token)
                VALUES ('tenant','project','dev','profile',1,'STAGED',?,?,?,?,?,?,?)
                """, json, spec.fingerprint(), "connection", "\"staged\"", lease.commandId(), lease.attemptNo(), lease.attemptToken());
        String empty = "{}";
        jdbc.update("""
                INSERT INTO rg_api_resource_projection_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, command_id,
                     descriptor_json, descriptor_fingerprint, descriptor_state,
                     design_contract_json, design_contract_fingerprint, design_contract_state,
                     operator_json, operator_fingerprint, operator_state, set_fingerprint)
                VALUES ('tenant','project','dev','profile',1,?, ?,?, 'READY',?,?, 'READY',?,?, 'READY',?)
                """, lease.commandId(), empty, AuthoringFingerprints.of(JSON.readTree(empty)), empty, AuthoringFingerprints.of(JSON.readTree(empty)),
                empty, AuthoringFingerprints.of(JSON.readTree(empty)), FP);
    }

    private void insertCommitted(ApiResourceSpec spec, JsonNode descriptor, JsonNode design, JsonNode operator) throws Exception {
        String receiptBody = "{\"result\":\"profile\"}";
        jdbc.update("""
                INSERT INTO rg_authoring_command_journal
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id, idempotency_key,
                     command_id, request_fingerprint, status, attempt_no, attempt_token, lease_until,
                     expected_mode, expected_revision, receipt_schema, receipt_json, receipt_fingerprint,
                     receipt_etag, created_at, updated_at)
                VALUES ('tenant','project','dev','actor','API_RESOURCE_SAVE','profile','k1','cmd-read',?,
                        'COMMITTED',1,'token-read',CURRENT_TIMESTAMP,'CREATE',NULL,'r',?,?, '"etag"',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, FP, receiptBody, AuthoringFingerprints.of(JSON.readTree(receiptBody)));
        jdbc.update("""
                INSERT INTO rg_authoring_command_attempts
                    (tenant_id, project_id, environment_id, actor_id, endpoint, target_id, idempotency_key,
                     command_id, request_fingerprint, status, attempt_no, attempt_token, lease_until,
                     expected_mode, expected_revision)
                VALUES ('tenant','project','dev','actor','API_RESOURCE_SAVE','profile','k1','cmd-read',?,
                        'COMMITTED',1,'token-read',CURRENT_TIMESTAMP,'CREATE',NULL)
                """, FP);
        jdbc.update("""
                INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state, spec_json,
                     spec_fingerprint, connection_id, strong_etag, command_id, attempt_no, attempt_token)
                VALUES ('tenant','project','dev','profile',1,'COMMITTED',?,?,?,?,?,1, 'token-read')
                """, JSON.writeValueAsString(spec), spec.fingerprint(), "connection", "\"etag\"", "cmd-read");
        jdbc.update("""
                INSERT INTO rg_api_resource_projection_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, command_id,
                     descriptor_json, descriptor_fingerprint, descriptor_state,
                     design_contract_json, design_contract_fingerprint, design_contract_state,
                     operator_json, operator_fingerprint, operator_state, set_fingerprint)
                VALUES ('tenant','project','dev','profile',1,?, ?,?, 'READY',?,?, 'READY',?,?, 'READY',?)
                """, "cmd-read", JSON.writeValueAsString(descriptor), AuthoringFingerprints.of(descriptor),
                JSON.writeValueAsString(design), AuthoringFingerprints.of(design),
                JSON.writeValueAsString(operator), AuthoringFingerprints.of(operator),
                COMMITTED_PROJECTION_SET_FINGERPRINT);
        jdbc.update("""
                INSERT INTO rg_api_resource_heads
                    (tenant_id, project_id, environment_id, resource_id, revision, command_id, strong_etag, revision_state)
                VALUES ('tenant','project','dev','profile',1,'cmd-read','"etag"','COMMITTED')
                """);
    }

    private void expireLease(CommandLease lease) {
        jdbc.update("UPDATE rg_authoring_command_journal SET lease_until = CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE command_id = ?",
                lease.commandId());
    }

    private static ApiResourceCommand command() {
        Map<String, Object> schema = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id")).schema();
        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
        JsonNode value = JSON.createObjectNode().put("id", "one");
        return new ApiResourceCommand("one", null, new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(envelope, envelope), new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.READ_ONLY, List.of(new ApiResourceCommand.Example("one", value, value)));
    }

}
