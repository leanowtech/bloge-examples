package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.AssertionLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.CompilationCompatibility;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.EvaluationKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputAssertion;
import com.leanowtech.bloge.gateway.testing.correctness.domain.AssertionSet.OutputOperator;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.BusinessOracle.OracleLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactBasisRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewRecord;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ReviewStatus;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;

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

class DatabaseOracleAssertionRepositoryTest {

    private static final Instant FIRST_SAVE = Instant.parse("2026-08-15T07:00:00Z");
    private static final Instant SECOND_SAVE = Instant.parse("2026-08-15T08:00:00Z");

    private ObjectMapper mapper;
    private JdbcTemplate jdbc;
    private DatabaseBusinessOracleRepository oracles;
    private DatabaseAssertionSetRepository assertionSets;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-oracle-assertion-schema.sql")).execute(database);
        jdbc = new JdbcTemplate(database);
        oracles = oracleRepositoryAt(FIRST_SAVE);
        assertionSets = assertionRepositoryAt(FIRST_SAVE);
    }

    @Test
    void persistsOracleHistoryIntegrityIndexesAndPayloadFreeApprovalEvent() throws Exception {
        StoredBusinessOracle proposed = oracles.saveIfRevision(
                0, oracle(scope("tenant-a"), 0, OracleLifecycle.PROPOSED), author())
                .orElseThrow();
        StoredBusinessOracle approved = oracleRepositoryAt(SECOND_SAVE).saveIfRevision(
                1, oracle(scope("tenant-a"), 1, OracleLifecycle.APPROVED), reviewer())
                .orElseThrow();

        assertThat(proposed.oracle().revision()).isEqualTo(1);
        assertThat(approved.oracle().revision()).isEqualTo(2);
        assertThat(approved.oracle().metadata().createdAt()).isEqualTo(FIRST_SAVE);
        assertThat(approved.oracle().metadata().updatedAt()).isEqualTo(SECOND_SAVE);
        assertThat(oracles.revisions(scope("tenant-a"), "loan-approved"))
                .extracting(value -> value.oracle().revision())
                .containsExactly(2L, 1L);
        assertThat(oracles.findHead(scope("tenant-b"), "loan-approved")).isEmpty();
        assertThat(oracles.summarize(scope("tenant-a"), target()))
                .isEqualTo(new BusinessOracleRepository.OracleTargetSummary(1, 0, 1, 0));

        String eventJson = jdbc.queryForObject("""
                SELECT event_json FROM rg_correctness_outbox
                WHERE aggregate_kind = 'ORACLE' AND aggregate_revision = 2
                """, String.class);
        BusinessOracleApproved event = mapper.readValue(
                eventJson, BusinessOracleApproved.class);
        assertThat(event.basisCount()).isEqualTo(1);
        assertThat(event.reviewerId()).isEqualTo("reviewer-a");
        assertPayloadFree(eventJson);

        jdbc.update("""
                UPDATE rg_business_oracle_heads SET basis_fingerprint = ?
                WHERE tenant_id = 'tenant-a' AND oracle_id = 'loan-approved'
                """, fingerprint('f'));
        assertThatThrownBy(() -> oracles.findHead(scope("tenant-a"), "loan-approved"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void persistsScopedAssertionHistoryAndNeverLeaksExpectedValuesToOutbox() throws Exception {
        ExactAssetRef oracleRef = oracleRef();
        StoredAssertionSet draft = assertionSets.saveIfRevision(
                scope("tenant-a"), 0,
                assertionSet(0, AssertionLifecycle.DRAFT, oracleRef), author())
                .orElseThrow();
        StoredAssertionSet valid = assertionRepositoryAt(SECOND_SAVE).saveIfRevision(
                scope("tenant-a"), 1,
                assertionSet(1, AssertionLifecycle.VALID, oracleRef), reviewer())
                .orElseThrow();

        assertThat(draft.assertionSet().revision()).isEqualTo(1);
        assertThat(valid.assertionSet().revision()).isEqualTo(2);
        assertThat(valid.scope()).isEqualTo(scope("tenant-a"));
        assertThat(assertionSets.revisions(scope("tenant-a"), "loan-checks"))
                .extracting(value -> value.assertionSet().revision())
                .containsExactly(2L, 1L);
        assertThat(assertionSets.findHead(scope("tenant-b"), "loan-checks")).isEmpty();
        assertThat(assertionSets.summarize(scope("tenant-a"), target()))
                .isEqualTo(new AssertionSetRepository.AssertionTargetSummary(1, 0, 1, 0, 0));

        String eventJson = jdbc.queryForObject("""
                SELECT event_json FROM rg_correctness_outbox
                WHERE aggregate_kind = 'ASSERTION_SET' AND aggregate_revision = 2
                """, String.class);
        AssertionSetChanged event = mapper.readValue(eventJson, AssertionSetChanged.class);
        assertThat(event.assertionCount()).isEqualTo(1);
        assertThat(event.compatibilitySupported()).isTrue();
        assertPayloadFree(eventJson);

        jdbc.update("""
                UPDATE rg_assertion_set_heads SET oracle_fingerprint = ?
                WHERE tenant_id = 'tenant-a' AND assertion_set_id = 'loan-checks'
                """, fingerprint('e'));
        assertThatThrownBy(() -> assertionSets.findHead(scope("tenant-a"), "loan-checks"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    @Test
    void rejectsStaleWritesAndRollsBackCanonicalRowsWhenOutboxFails() {
        oracles.saveIfRevision(
                0, oracle(scope("tenant-a"), 0, OracleLifecycle.PROPOSED), author())
                .orElseThrow();
        assertThat(oracles.saveIfRevision(
                0, oracle(scope("tenant-a"), 0, OracleLifecycle.PROPOSED), reviewer()))
                .isEmpty();
        assertThatThrownBy(() -> assertionSets.saveIfRevision(
                scope("tenant-a"), 1,
                assertionSet(0, AssertionLifecycle.DRAFT, oracleRef()), author()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("matching");

        jdbc.execute("DROP TABLE rg_correctness_outbox");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()));
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                assertionSets.saveIfRevision(
                        scope("tenant-a"), 0,
                        assertionSet(0, AssertionLifecycle.DRAFT, oracleRef()), author())))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_assertion_set_heads", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_assertion_set_revisions", Integer.class)).isZero();
    }

    @Test
    void machineSchemasTrackStoredEnvelopesAndPayloadFreeEvents() throws Exception {
        BusinessOracle approved = oracle(scope("tenant-a"), 1, OracleLifecycle.APPROVED);
        StoredBusinessOracle storedOracle = StoredBusinessOracle.verified(mapper, approved);
        AssertionSet valid = assertionSet(1, AssertionLifecycle.VALID, oracleRef());
        StoredAssertionSet storedAssertions = StoredAssertionSet.verified(
                mapper, scope("tenant-a"), valid);
        ExactAssetRef exactOracle = new ExactAssetRef(
                "ORACLE", approved.oracleId(), 1, storedOracle.oracleFingerprint());
        ExactAssetRef exactAssertions = new ExactAssetRef(
                "ASSERTION_SET", valid.assertionSetId(), 1,
                storedAssertions.assertionSetFingerprint());

        assertSchema("bloge-stored-business-oracle-v1.schema.json", storedOracle);
        assertSchema("bloge-stored-assertion-set-v1.schema.json", storedAssertions);
        assertSchema("bloge-business-oracle-changed-v1.schema.json",
                new BusinessOracleChanged(
                        "", "event-oracle-changed", scope("tenant-a"), exactOracle, target(),
                        "PROPOSED", "credit-owner", 1, 0, "author-a", FIRST_SAVE));
        assertSchema("bloge-business-oracle-approved-v1.schema.json",
                new BusinessOracleApproved(
                        "", "event-oracle-approved", scope("tenant-a"), exactOracle, target(),
                        "credit-owner", 1, "reviewer-a", SECOND_SAVE));
        assertSchema("bloge-assertion-set-changed-v1.schema.json",
                new AssertionSetChanged(
                        "", "event-assertion-changed", scope("tenant-a"), exactAssertions,
                        target(), oracleRef(), "VALID", 1, true, "fixture-evaluator.v1",
                        "reviewer-a", SECOND_SAVE));
    }

    private DatabaseBusinessOracleRepository oracleRepositoryAt(Instant time) {
        return new DatabaseBusinessOracleRepository(
                jdbc, mapper, Clock.fixed(time, ZoneOffset.UTC));
    }

    private DatabaseAssertionSetRepository assertionRepositoryAt(Instant time) {
        return new DatabaseAssertionSetRepository(
                jdbc, mapper, Clock.fixed(time, ZoneOffset.UTC));
    }

    private BusinessOracle oracle(
            EnterpriseScope scope,
            long revision,
            OracleLifecycle lifecycle
    ) {
        Instant forged = Instant.parse("2001-01-01T00:00:00Z");
        ReviewRecord approval = lifecycle == OracleLifecycle.APPROVED
                ? new ReviewRecord(
                        ReviewStatus.APPROVED, reviewer(), forged, "Policy owner approved")
                : ReviewRecord.pending();
        return new BusinessOracle(
                "", "loan-approved", revision, scope, target(),
                "Prime applicants are approved without manual review",
                List.of("Manual review is invoked", "Loan is rejected"),
                List.of(new ExactBasisRef("POLICY", "loan-policy", 7, fingerprint('b'))),
                new PrincipalRef("credit-owner", PrincipalKind.TEAM, "Credit Owner"),
                lifecycle, approval, List.of(), metadata(forged));
    }

    private AssertionSet assertionSet(
            long revision,
            AssertionLifecycle lifecycle,
            ExactAssetRef oracleRef
    ) {
        CompilationCompatibility compatibility = lifecycle == AssertionLifecycle.VALID
                ? new CompilationCompatibility(
                        true, "fixture-evaluator.v1",
                        List.of("RUNTIME:OUTPUT:EQUALS"), "")
                : CompilationCompatibility.unsupported("RG.CORRECTNESS.NOT_VALIDATED");
        return new AssertionSet(
                "", "loan-checks", revision, target(), oracleRef, lifecycle,
                List.of(new OutputAssertion(
                        "decision-approved", EvaluationKind.RUNTIME, "/decision",
                        OutputOperator.EQUALS, Map.of("sensitiveExpected", "approve"))),
                compatibility, metadata(Instant.parse("2001-01-01T00:00:00Z")));
    }

    private ExactAssetRef oracleRef() {
        return new ExactAssetRef("ORACLE", "loan-approved", 2, fingerprint('c'));
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private AuditMetadata metadata(Instant time) {
        return new AuditMetadata(time, time, author(), author());
    }

    private EnterpriseScope scope(String tenant) {
        return new EnterpriseScope(tenant, "org-a", "credit", "test", "sg");
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
                .doesNotContain("statement", "forbidden", "expected", "assertions", "payload")
                .doesNotContain("sensitiveExpected", "Prime applicants");
    }

    private void assertSchema(String name, Object value) throws Exception {
        Path path = Path.of("..", "docs", "schemas", name);
        Set<String> serialized = new HashSet<>();
        mapper.valueToTree(value).fieldNames().forEachRemaining(serialized::add);
        Set<String> documented = new HashSet<>();
        mapper.readTree(Files.readString(path)).path("properties")
                .fieldNames().forEachRemaining(documented::add);
        assertThat(documented).as(name).isEqualTo(serialized);
    }
}
