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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

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
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:authoring-claim-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        new ResourceDatabasePopulator(new ClassPathResource("db/postgresql/V20260830_001__api_resource_authoring.sql"))
                .execute(dataSource);
        clock = new MutableClock();
    }

    @AfterEach
    void tearDown() { jdbc.execute("DROP ALL OBJECTS"); }

    @Test
    void claimInsertsBusyTakeoverAndReplayOrConflict() throws Exception {
        JdbcApiResourceCommitStore store = store();
        ClaimResult.Acquired first = (ClaimResult.Acquired) store.claim(KEY, FP, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create());
        assertThat(first.resumed()).isFalse();
        assertThat(store.claim(KEY, FP, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create()))
                .isInstanceOf(ClaimResult.Busy.class);
        assertThat(store.claim(KEY, FP2, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.create()))
                .isInstanceOf(ClaimResult.Conflict.class);

        clock.advance(Duration.ofSeconds(2));
        ClaimResult.Acquired takeover = (ClaimResult.Acquired) store.claim(KEY, FP, com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision.match(3));
        assertThat(takeover.resumed()).isTrue();
        assertThat(takeover.lease().commandId()).isEqualTo(first.lease().commandId());
        assertThat(takeover.lease().attemptNo()).isEqualTo(2);
        assertThat(takeover.lease().attemptToken()).isNotEqualTo(first.lease().attemptToken());

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
        assertThatThrownBy(() -> new JdbcApiResourceCommitStore(jdbc, transactions, JSON, clock,
                Duration.ZERO, new ApiResourceDecisions(), (scope, resource) -> null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private JdbcApiResourceCommitStore store() {
        return new JdbcApiResourceCommitStore(jdbc, transactions, JSON, clock, Duration.ofSeconds(1),
                new ApiResourceDecisions(), (scope, resource) -> null);
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
                INSERT INTO rg_api_resource_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision, state, spec_json,
                     spec_fingerprint, connection_id, strong_etag, command_id, attempt_no, attempt_token)
                VALUES ('tenant','project','dev','profile',1,'COMMITTED',?,?,?,?,?,1, 'token-read')
                """, JSON.writeValueAsString(spec), spec.fingerprint(), "connection", "\"etag\"", "cmd-read");
        jdbc.update("""
                INSERT INTO rg_api_resource_projection_revisions
                    (tenant_id, project_id, environment_id, resource_id, revision,
                     descriptor_json, descriptor_fingerprint, descriptor_state,
                     design_contract_json, design_contract_fingerprint, design_contract_state,
                     operator_json, operator_fingerprint, operator_state, set_fingerprint)
                VALUES ('tenant','project','dev','profile',1,?,?, 'READY',?,?, 'READY',?,?, 'READY',?)
                """, JSON.writeValueAsString(descriptor), AuthoringFingerprints.of(descriptor),
                JSON.writeValueAsString(design), AuthoringFingerprints.of(design),
                JSON.writeValueAsString(operator), AuthoringFingerprints.of(operator), FP2);
        jdbc.update("""
                INSERT INTO rg_api_resource_heads
                    (tenant_id, project_id, environment_id, resource_id, revision, strong_etag, revision_state)
                VALUES ('tenant','project','dev','profile',1,'"etag"','COMMITTED')
                """);
    }

    private static ApiResourceCommand command() {
        Map<String, Object> schema = SchemaEnvelope.object(Map.of("id", Map.of("type", "string")), List.of("id")).schema();
        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
        JsonNode value = JSON.createObjectNode().put("id", "one");
        return new ApiResourceCommand("one", null, new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(envelope, envelope), new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.READ_ONLY, List.of(new ApiResourceCommand.Example("one", value, value)));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
