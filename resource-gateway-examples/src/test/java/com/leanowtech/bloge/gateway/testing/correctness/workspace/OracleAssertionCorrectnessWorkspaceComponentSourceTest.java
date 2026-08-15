package com.leanowtech.bloge.gateway.testing.correctness.workspace;

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
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseAssertionSetRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.DatabaseBusinessOracleRepository;
import com.leanowtech.bloge.gateway.testing.correctness.persistence.StoredBusinessOracle;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Coordinate;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.PageRequest;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OracleAssertionCorrectnessWorkspaceComponentSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    private DatabaseBusinessOracleRepository oracles;
    private DatabaseAssertionSetRepository assertionSets;
    private OracleAssertionCorrectnessWorkspaceComponentSource source;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        new ResourceDatabasePopulator(new ClassPathResource(
                "correctness/h2-oracle-assertion-schema.sql")).execute(database);
        JdbcTemplate jdbc = new JdbcTemplate(database);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        oracles = new DatabaseBusinessOracleRepository(jdbc, mapper, clock);
        assertionSets = new DatabaseAssertionSetRepository(jdbc, mapper, clock);
        source = new OracleAssertionCorrectnessWorkspaceComponentSource(
                new DefinitionOnlyCorrectnessWorkspaceComponentSource(), oracles, assertionSets);
    }

    @Test
    void projectsApprovedOracleAndValidAssertionCountsWithoutPayload() {
        StoredBusinessOracle oracle = oracles.saveIfRevision(
                0, approvedOracle(), reviewer()).orElseThrow();
        assertionSets.saveIfRevision(
                scope(), 0, validAssertions(exactOracle(oracle)), author()).orElseThrow();

        var result = source.load(coordinate(target()), page());

        assertThat(result.oracleAssertions().availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(result.oracleAssertions().oracleTotal()).isEqualTo(1);
        assertThat(result.oracleAssertions().approvedOracles()).isEqualTo(1);
        assertThat(result.oracleAssertions().assertionSetTotal()).isEqualTo(1);
        assertThat(result.oracleAssertions().validAssertionSets()).isEqualTo(1);
        assertThat(result.reviews().approved()).isEqualTo(1);
        assertThat(result.capabilities()).contains(
                "BUSINESS_ORACLE_SUMMARY_V1", "ASSERTION_SET_SUMMARY_V1");
    }

    @Test
    void emptyTargetRemainsBlockedWithOneExplicitOracleAction() {
        var result = source.load(coordinate(target()), page());

        assertThat(result.oracleAssertions().oracleTotal()).isZero();
        assertThat(result.verdict().reasons())
                .extracting(value -> value.code())
                .contains("ORACLE_APPROVAL_REQUIRED");
        assertThat(result.verdict().nextActions())
                .extracting(value -> value.command())
                .contains("OPEN_ORACLE_BUILDER");
    }

    private BusinessOracle approvedOracle() {
        return new BusinessOracle(
                "", "loan-approved", 0, scope(), target(),
                "Prime applicants are approved", List.of("manual review"),
                List.of(new ExactBasisRef("POLICY", "loan-policy", 7, fingerprint('b'))),
                owner(), OracleLifecycle.APPROVED,
                new ReviewRecord(ReviewStatus.APPROVED, reviewer(), NOW, "Approved"),
                List.of(), metadata());
    }

    private AssertionSet validAssertions(ExactAssetRef oracleRef) {
        return new AssertionSet(
                "", "loan-checks", 0, target(), oracleRef, AssertionLifecycle.VALID,
                List.of(new OutputAssertion(
                        "decision", EvaluationKind.RUNTIME, "/decision",
                        OutputOperator.EQUALS, "approve")),
                new CompilationCompatibility(
                        true, "bloge.fixtureAssertionEvaluator.v1",
                        List.of("RUNTIME:OUTPUT:EQUALS"), ""), metadata());
    }

    private Coordinate coordinate(ExactTargetRef target) {
        return new Coordinate(
                scope(), new ExactAssetRef("DEFINITION", "loan", 1, fingerprint('d')),
                target, null);
    }

    private PageRequest page() {
        return new PageRequest("", 20, fingerprint('e'));
    }

    private ExactAssetRef exactOracle(StoredBusinessOracle stored) {
        return new ExactAssetRef(
                "ORACLE", stored.oracle().oracleId(), stored.oracle().revision(),
                stored.oracleFingerprint());
    }

    private static ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 3, fingerprint('a'));
    }

    private static EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "credit", "test", "sg");
    }

    private static AuditMetadata metadata() {
        return new AuditMetadata(NOW, NOW, author(), author());
    }

    private static PrincipalRef owner() {
        return new PrincipalRef("credit-owner", PrincipalKind.TEAM, "Credit Owner");
    }

    private static PrincipalRef author() {
        return new PrincipalRef("author-a", PrincipalKind.USER, "Author A");
    }

    private static PrincipalRef reviewer() {
        return new PrincipalRef("reviewer-a", PrincipalKind.USER, "Reviewer A");
    }

    private static String fingerprint(char seed) {
        return "sha256:" + String.valueOf(seed).repeat(64);
    }
}
