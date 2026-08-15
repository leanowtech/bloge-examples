package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.OutputCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceCoordinate;
import com.leanowtech.bloge.gateway.testing.correctness.compilation.CorrectnessCompilationReport.SourceMapping;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactCaseRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCorrectnessEvidenceRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private DatabaseCorrectnessEvidenceRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:correctness-evidence-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE rg_correctness_evidence_companions (
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL,
                    environment_id VARCHAR(255) NOT NULL,
                    region_id VARCHAR(128) NOT NULL,
                    suite_run_id VARCHAR(512) NOT NULL,
                    evidence_companion_id VARCHAR(512) NOT NULL,
                    companion_fingerprint VARCHAR(80) NOT NULL,
                    publication_id VARCHAR(512) NOT NULL,
                    publication_fingerprint VARCHAR(80) NOT NULL,
                    suite_evidence_fingerprint VARCHAR(80) NOT NULL,
                    canonical_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    created_by VARCHAR(512) NOT NULL,
                    PRIMARY KEY (
                        tenant_id, organization_id, project_id, environment_id, region_id,
                        suite_run_id),
                    UNIQUE (
                        tenant_id, organization_id, project_id, environment_id, region_id,
                        evidence_companion_id)
                )
                """);
        repository = new DatabaseCorrectnessEvidenceRepository(jdbc, mapper);
    }

    @Test
    void persistsReadsAndIdempotentlyReusesExactCompanion() throws Exception {
        StoredCorrectnessEvidenceCompanion candidate = companion("CONFIDENTIAL");

        assertThat(repository.saveIfAbsent(scope(), candidate)).isEqualTo(candidate);
        assertThat(repository.saveIfAbsent(scope(), candidate)).isEqualTo(candidate);
        assertThat(repository.find(scope(), "suite-run-1")).contains(candidate);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_correctness_evidence_companions", Integer.class))
                .isEqualTo(1);
        String json = mapper.writeValueAsString(candidate);
        assertThat(json).doesNotContain("business-payload-marker", "secret-value-marker");
    }

    @Test
    void immutableRunIdentityRejectsDifferentCompanionContent() {
        repository.saveIfAbsent(scope(), companion("CONFIDENTIAL"));

        assertThatThrownBy(() -> repository.saveIfAbsent(
                scope(), companion("RESTRICTED")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable identity");
    }

    @Test
    void scopeIsPartOfTheLookupAndWriteBoundary() {
        StoredCorrectnessEvidenceCompanion candidate = companion("CONFIDENTIAL");
        repository.saveIfAbsent(scope(), candidate);
        EnterpriseScope other = new EnterpriseScope(
                "tenant-b", "org-a", "project-a", "test", "sg");

        assertThat(repository.find(other, "suite-run-1")).isEmpty();
        assertThatThrownBy(() -> repository.saveIfAbsent(other, candidate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scope");
    }

    @Test
    void indexedColumnTamperingFailsClosed() {
        repository.saveIfAbsent(scope(), companion("CONFIDENTIAL"));
        jdbc.update("""
                UPDATE rg_correctness_evidence_companions
                SET publication_fingerprint = ?
                WHERE suite_run_id = 'suite-run-1'
                """, fp('9'));

        assertThatThrownBy(() -> repository.find(scope(), "suite-run-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integrity");
    }

    private StoredCorrectnessEvidenceCompanion companion(String classification) {
        ExactAssetRef scenarioRef = ref("SCENARIO_DRAFT_SET", "scenarios", '3');
        ExactAssetRef fixtureBundleRef = ref("FIXTURE_BUNDLE", "fixture-bundle", '8');
        ExactAssetRef suiteRef = ref("TEST_SUITE", "suite", '9');
        CorrectnessRunRequest.PublicationRef publicationRef =
                new CorrectnessRunRequest.PublicationRef("publication-1", 1, fp('1'));
        CorrectnessRunRequest.Selection selection = new CorrectnessRunRequest.Selection(
                CorrectnessRunRequest.Selection.Mode.ALL, List.of(), fp('2'));
        String suiteEvidenceFingerprint = fp('a');
        TestSuiteRunAttestation attestation = new TestSuiteRunAttestation(
                "", TestSuiteRunAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE,
                TestSuiteRunAttestation.Scope.TERMINAL, "suite-run-1",
                new TestSuiteExecutionRequest.SuiteRef(
                        suiteRef.id(), suiteRef.revision(), suiteRef.fingerprint()),
                fp('b'), suiteEvidenceFingerprint, List.of(), Instant.EPOCH,
                "", "", "", false);
        CorrectnessVerdict verdict = new CorrectnessVerdict(
                CorrectnessVerdict.ExecutionVerdict.SUCCESS,
                CorrectnessVerdict.AssertionVerdict.PASSED,
                CorrectnessVerdict.CoverageVerdict.COMPLETE,
                CorrectnessVerdict.EvidenceVerdict.EXPLORATORY,
                CorrectnessVerdict.GateVerdict.BLOCKED,
                CorrectnessVerdict.ProofLevel.SIMULATED_BUSINESS,
                List.of(new CorrectnessVerdict.Reason(
                        "EVIDENCE_UNATTESTED", "EVIDENCE",
                        "correctness.reason.evidence_unattested")),
                List.of(new CorrectnessVerdict.Remediation(
                        "CONFIGURE_EVIDENCE_ATTESTATION", "EVIDENCE_UNATTESTED")));
        CorrectnessEvidenceCompanion value = new CorrectnessEvidenceCompanion(
                "", "correctness-evidence-suite-run-1", scope(), "suite-run-1",
                suiteEvidenceFingerprint, fp('c'), publicationRef,
                new ExactTargetRef(TargetKind.GRAPH, "loan-graph", 1, fp('d')),
                ref("DEFINITION", "definition", '4'),
                ref("COVERAGE_INVENTORY", "inventory", '5'), scenarioRef,
                List.of(new ExactCaseRef(scenarioRef, "case-1", fp('6'))),
                List.of(ref("BUSINESS_ORACLE", "oracle", '7')),
                List.of(ref("ASSERTION_SET", "assertions", 'e')),
                List.of(ref("FIXTURE_ASSET", "fixture", 'f')),
                List.of(fixtureBundleRef), suiteRef, selection,
                List.of(new CorrectnessEvidenceCompanion.CaseExecutionRef(
                        "case-1", fixtureBundleRef, fp('0'),
                        com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence
                                .CaseStatus.PASSED,
                        "child-run-1",
                        TestRunEvidence.EvidenceClass.CERTIFIABLE)),
                List.of(new SourceMapping(
                        new SourceCoordinate(scenarioRef, "SCENARIO_CASE", "case-1"),
                        new OutputCoordinate(suiteRef, "TEST_CASE", "case-1"))),
                new CorrectnessPreflightReport.RiskSummary(
                        0, 1, 0, 0, 0, 0, 0, 0, 0, true, List.of("READ")),
                List.of(classification), verdict, attestation,
                new AuditMetadata(NOW, NOW, actor(), actor()));
        return StoredCorrectnessEvidenceCompanion.verified(mapper, value);
    }

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "project-a", "test", "sg");
    }

    private PrincipalRef actor() {
        return new PrincipalRef("tester", PrincipalKind.USER, "Tester");
    }

    private ExactAssetRef ref(String kind, String id, char digit) {
        return new ExactAssetRef(kind, id, 1, fp(digit));
    }

    private String fp(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }
}
