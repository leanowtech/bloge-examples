package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactObligationRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.RiskLevel;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.CaseType;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.GivenV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.InlineValue;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseScenarioDraftSetV2RepositoryTest {

    private static final Instant FIRST_SAVE = Instant.parse("2026-08-15T13:00:00Z");
    private static final Instant SECOND_SAVE = Instant.parse("2026-08-15T14:00:00Z");

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DatabaseScenarioDraftSetV2Repository repository;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-scenario-v2-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        repository = repositoryAt(FIRST_SAVE);
    }

    @Test
    void persistsHistoryCurrentMatrixPageFulfillmentAndPayloadFreeEvent() throws Exception {
        StoredScenarioDraftSetV2 first = repository.saveIfRevision(
                0, scenarioSet(scope("tenant-a"), 0, "Prime decision"), author())
                .orElseThrow();
        StoredScenarioDraftSetV2 second = repositoryAt(SECOND_SAVE).saveIfRevision(
                1, scenarioSet(scope("tenant-a"), 1, "Prime decision revised"), reviewer())
                .orElseThrow();

        assertThat(first.scenarioDraftSet().revision()).isEqualTo(1);
        assertThat(second.scenarioDraftSet().revision()).isEqualTo(2);
        assertThat(second.scenarioDraftSet().metadata().createdAt()).isEqualTo(FIRST_SAVE);
        assertThat(repository.revisions(scope("tenant-a"), "loan-scenarios"))
                .extracting(value -> value.scenarioDraftSet().revision())
                .containsExactly(2L, 1L);
        assertThat(repository.findHead(scope("tenant-b"), "loan-scenarios")).isEmpty();

        var firstPage = repository.pageByTarget(scope("tenant-a"), target(), "", 1);
        assertThat(firstPage.total()).isEqualTo(2);
        assertThat(firstPage.rows()).extracting(value -> value.caseId())
                .containsExactly("case-canonical");
        assertThat(firstPage.nextCursor()).startsWith("v1.");
        var secondPage = repository.pageByTarget(
                scope("tenant-a"), target(), firstPage.nextCursor(), 1);
        assertThat(secondPage.rows()).extracting(value -> value.caseId())
                .containsExactly("case-exploratory");
        assertThat(secondPage.rows().getFirst().name()).isEqualTo("Prime decision revised");
        assertThat(secondPage.nextCursor()).isEmpty();

        assertThat(repository.fulfilledObligationIds(
                scope("tenant-a"), target(), inventoryRef()))
                .containsExactly("policy.eligibility");
        String eventJson = jdbc.queryForObject("""
                SELECT event_json FROM rg_correctness_outbox WHERE aggregate_revision = 2
                """, String.class);
        ScenarioDraftSetV2Changed event = mapper.readValue(
                eventJson, ScenarioDraftSetV2Changed.class);
        assertThat(event.caseCount()).isEqualTo(2);
        assertThat(event.canonicalCount()).isEqualTo(1);
        assertPayloadFree(eventJson);
    }

    @Test
    void rejectsInvalidCursorStaleCasAndDetectsCaseIndexTampering() {
        repository.saveIfRevision(
                0, scenarioSet(scope("tenant-a"), 0, "Prime decision"), author())
                .orElseThrow();

        assertThat(repository.saveIfRevision(
                0, scenarioSet(scope("tenant-a"), 0, "Stale"), reviewer())).isEmpty();
        assertThatThrownBy(() -> repository.pageByTarget(
                scope("tenant-a"), target(), "case-canonical", 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cursor");
        jdbc.update("""
                UPDATE rg_scenario_case_v2_index SET risk = 'LOW'
                WHERE tenant_id = 'tenant-a' AND case_id = 'case-canonical'
                """);
        assertThatThrownBy(() -> repository.findHead(scope("tenant-a"), "loan-scenarios"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void rollsBackHeadHistoryAndBothIndexesWhenOutboxFails() {
        jdbc.execute("DROP TABLE rg_correctness_outbox");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                repository.saveIfRevision(
                        0, scenarioSet(scope("tenant-a"), 0, "Prime decision"), author())))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_scenario_draft_set_v2_heads", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_scenario_draft_set_v2_revisions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_scenario_case_v2_index", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_scenario_case_obligation_ref_index", Integer.class))
                .isZero();
    }

    @Test
    void machineSchemasTrackStoredEnvelopeAndPayloadFreeEvent() throws Exception {
        StoredScenarioDraftSetV2 stored = repository.saveIfRevision(
                0, scenarioSet(scope("tenant-a"), 0, "Prime decision"), author())
                .orElseThrow();
        String eventJson = jdbc.queryForObject(
                "SELECT event_json FROM rg_correctness_outbox", String.class);

        assertSchema("bloge-stored-scenario-draft-set-v2.schema.json", stored);
        assertSchema("bloge-scenario-draft-set-changed-v2.schema.json",
                mapper.readValue(eventJson, ScenarioDraftSetV2Changed.class));
    }

    private DatabaseScenarioDraftSetV2Repository repositoryAt(Instant time) {
        return new DatabaseScenarioDraftSetV2Repository(
                jdbc, mapper, Clock.fixed(time, ZoneOffset.UTC));
    }

    private ScenarioDraftSetV2 scenarioSet(
            EnterpriseScope scope,
            long revision,
            String exploratoryName
    ) {
        return new ScenarioDraftSetV2(
                "", "loan-scenarios", revision, scope, target(),
                new ExactAssetRef("CONTRACT", "loan-contract", 2, fingerprint('c')),
                List.of(
                        scenario("case-canonical", "Canonical decision",
                                ScenarioLifecycle.CANONICAL, true),
                        scenario("case-exploratory", exploratoryName,
                                ScenarioLifecycle.EXPLORATORY, false)),
                metadata());
    }

    private ScenarioDraftV2 scenario(
            String id,
            String name,
            ScenarioLifecycle lifecycle,
            boolean governed
    ) {
        return new ScenarioDraftV2(
                id, name, "Prove " + name, "", CaseType.GOLDEN, RiskLevel.HIGH,
                owner(), lifecycle,
                governed ? List.of(obligationRef()) : List.of(),
                governed ? List.of(new ExactAssetRef(
                        "ORACLE", "loan-approved", 2, fingerprint('d'))) : List.of(),
                governed ? List.of(new ExactAssetRef(
                        "ASSERTION_SET", "loan-checks", 2, fingerprint('e'))) : List.of(),
                List.of(), new GivenV2(new InlineValue(Map.of("applicantId", "A-100"))),
                List.of(), governed
                        ? new ReviewRecord(
                                ReviewStatus.APPROVED, reviewer(), FIRST_SAVE, "Approved")
                        : ReviewRecord.pending(),
                List.of("loan", governed ? "canonical" : "draft"));
    }

    private ExactObligationRef obligationRef() {
        return new ExactObligationRef(
                inventoryRef(), "policy.eligibility", fingerprint('f'));
    }

    private ExactAssetRef inventoryRef() {
        return new ExactAssetRef("INVENTORY", "loan-inventory", 2, fingerprint('b'));
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private AuditMetadata metadata() {
        Instant forged = Instant.parse("2001-01-01T00:00:00Z");
        return new AuditMetadata(forged, forged, author(), author());
    }

    private EnterpriseScope scope(String tenant) {
        return new EnterpriseScope(tenant, "org-a", "credit", "test", "sg");
    }

    private PrincipalRef owner() {
        return new PrincipalRef("credit-owner", PrincipalKind.TEAM, "Credit Owner");
    }

    private PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private PrincipalRef reviewer() {
        return new PrincipalRef("reviewer-a", PrincipalKind.USER, "Reviewer A");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }

    private static void assertPayloadFree(String json) {
        assertThat(json)
                .doesNotContain("businessIntent", "description", "given", "dependencies")
                .doesNotContain("Prime decision", "applicantId", "A-100");
    }

    private void assertSchema(String name, Object value) throws Exception {
        var schema = mapper.readTree(Files.readString(
                Path.of("..", "docs", "schemas", name)));
        Set<String> serialized = new HashSet<>();
        mapper.valueToTree(value).fieldNames().forEachRemaining(serialized::add);
        Set<String> documented = new HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(documented::add);
        assertThat(documented).as(name).isEqualTo(serialized);
    }
}
