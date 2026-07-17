package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.StoredFixtureBundle;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestBoundaryCasePlan;
import com.leanowtech.bloge.gateway.testing.api.TestRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSecurityEvent;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunConflictException;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunLease;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRunRecord;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoverageVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestRuntimePersistenceTest {

    private ObjectMapper mapper;
    private DatabaseFixtureBundleRepository fixtures;
    private DatabaseTestRunRepository runs;
    private DatabaseTestSecurityEventRepository securityEvents;
    private DatabaseTestSuiteRepository suites;
    private DatabaseTestSuiteRunRepository suiteRuns;
    private TestSuiteRunAttestationService suiteAttestations;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:test-runtime-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        fixtures = new DatabaseFixtureBundleRepository(jdbc, mapper);
        runs = new DatabaseTestRunRepository(jdbc, mapper);
        securityEvents = new DatabaseTestSecurityEventRepository(jdbc, mapper);
        suites = new DatabaseTestSuiteRepository(jdbc, mapper);
        suiteRuns = new DatabaseTestSuiteRunRepository(jdbc, mapper);
        suiteAttestations = new TestSuiteRunAttestationService(
                mapper, new InMemoryVisualEvidenceSigner());
        fixtures.init();
        runs.init();
        securityEvents.init();
        suites.init();
        suiteRuns.init();
    }

    @Test
    void immutableFixtureRevisionSurvivesRepositoryReconstructionAndRejectsConflict() {
        FixtureBundle bundle = new FixtureBundle("", "fixture-a", 2, "sha256:target",
                "INTERNAL", null, null, List.of(), List.of(), Map.of("owner", "quality"));
        StoredFixtureBundle stored = new StoredFixtureBundle("", "tenant-a", "test", "fixture-a", 2,
                "sha256:fixture-a", bundle, Instant.now(), "runner");

        fixtures.create(stored);

        assertThat(fixtures.find("tenant-a", "test", "fixture-a", 2)).contains(stored);
        assertThat(fixtures.find("tenant-b", "test", "fixture-a", 2)).isEmpty();
        StoredFixtureBundle conflict = new StoredFixtureBundle("", "tenant-a", "test", "fixture-a", 2,
                "sha256:different", bundle, stored.createdAt(), "runner");
        assertThatThrownBy(() -> fixtures.create(conflict))
                .isInstanceOf(FixtureBundleConflictException.class)
                .hasMessageContaining("different immutable content");
    }

    @Test
    void immutableSuiteRevisionRoundTripsWithPolicyAndRejectsCrossScopeOrOverwrite() {
        String target = "sha256:" + "a".repeat(64);
        String fixture = "sha256:" + "b".repeat(64);
        TestSuite suite = new TestSuite("", "suite-a", 3,
                new TestSuite.Target("GRAPH", "graph-a", target), "INTERNAL",
                List.of(new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                        Map.of("orderId", "O-1"), new TestSuite.FixtureBundleRef(
                        "fixture-a", 2, fixture), List.of("ci"), Map.of())),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.GOLDEN),
                        List.of("/root/fetch#PRIMARY"), List.of(new TestSuite.EdgeTransferRef(
                        "/root/fetch#PRIMARY", "/root/output#PRIMARY")), 1, true),
                new TestSuite.PromotionPolicy(true, 1, true), Map.of("owner", "quality"));
        StoredTestSuite stored = new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                "sha256:" + "c".repeat(64), suite, Instant.now(), "runner");

        suites.create(stored);

        assertThat(suites.find("tenant-a", "test", "suite-a", 3)).contains(stored);
        assertThat(suites.find("tenant-b", "test", "suite-a", 3)).isEmpty();
        assertThat(suites.find("tenant-a", "staging", "suite-a", 3)).isEmpty();
        StoredTestSuite conflict = new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                "sha256:" + "d".repeat(64), suite, stored.createdAt(), "runner");
        assertThatThrownBy(() -> suites.create(conflict))
                .isInstanceOf(TestSuiteConflictException.class)
                .hasMessageContaining("different immutable content");
    }

    @Test
    void semanticSuiteAndCheckpointRetainTheirConcreteV2Generations() {
        String targetFingerprint = "sha256:" + "a".repeat(64);
        String fixtureFingerprint = "sha256:" + "b".repeat(64);
        var retry = new SemanticCoveragePolicy.RetryRequirement("retry",
                SemanticCoveragePolicy.Kind.RETRY, "/root/remote#PRIMARY", 2);
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "fixture-v2", 1, fixtureFingerprint);
        TestSuiteV2 suite = new TestSuiteV2("", "suite-v2", 1,
                new TestSuite.Target("GRAPH", "graph-v2", targetFingerprint), "INTERNAL",
                List.of(new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                        Map.of(), fixture, List.of(), Map.of())), TestSuite.CoveragePolicy.defaults(),
                new SemanticCoveragePolicy(List.of(retry)),
                new TestSuite.PromotionPolicy(true, 1, true), Map.of());
        StoredTestSuite storedSuite = new StoredTestSuite("", "tenant-a", "test", "suite-v2", 1,
                "sha256:" + "c".repeat(64), suite, Instant.now(), "runner");
        suites.create(storedSuite);

        Instant now = suiteRuns.currentTime();
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                "suite-v2", 1, storedSuite.fingerprint());
        TestSuiteRunEvidenceV2 evidence = new TestSuiteRunEvidenceV2("", "suite-run-v2", "request-v2",
                TestSuiteRunEvidence.Status.RUNNING, "TEST_SUITE_EXECUTION", suiteRef, suite.target(),
                now, null, List.of(), TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                SemanticCoverageVerdict.notEvaluated(List.of(retry)),
                TestSuiteRunEvidence.PromotionVerdict.notEvaluated(), List.of(), Map.of());
        String requestFingerprint = "sha256:" + "d".repeat(64);
        var attestation = suiteAttestations.seal(evidence, requestFingerprint, List.of(),
                com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.Scope.CHECKPOINT)
                .attestation();
        TestSuiteRunRecord record = new TestSuiteRunRecord("suite-run-v2", "request-v2",
                requestFingerprint, "tenant-a", "org-a", "project-a", "test", "runner", "INTERNAL",
                "", evidence, attestation, now, now.plusSeconds(3600));
        var downgradedAttestation = new com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation(
                com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.SCHEMA_VERSION,
                attestation.signatureStatus(), attestation.scope(), attestation.suiteRunId(),
                attestation.suiteRef(), attestation.requestFingerprint(),
                attestation.aggregateEvidenceFingerprint(), attestation.childEvidenceRefs(),
                attestation.signedAt(), attestation.keyId(), attestation.algorithm(),
                attestation.signature(), true);
        TestSuiteRunRecord mixedGeneration = new TestSuiteRunRecord(record.suiteRunId(),
                record.clientRequestId(), record.requestFingerprint(), record.tenantId(),
                record.organizationId(), record.projectId(), record.environmentId(), record.actorId(),
                record.classification(), record.evidenceFingerprint(), record.evidence(),
                downgradedAttestation, record.createdAt(), record.expiresAt());

        assertThatThrownBy(() -> suiteRuns.create(mixedGeneration,
                new TestSuiteRunLease("instance-a", now.plusSeconds(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed attestation");
        TestSuiteRunEvidenceV2 mislabeledEvidence = new TestSuiteRunEvidenceV2(
                TestSuiteRunEvidence.SCHEMA_VERSION, evidence.suiteRunId(), evidence.clientRequestId(),
                evidence.status(), evidence.executionPurpose(), evidence.suiteRef(), evidence.target(),
                evidence.startedAt(), evidence.completedAt(), evidence.caseResults(), evidence.coverage(),
                evidence.semanticCoverage(), evidence.promotion(), evidence.diagnostics(), evidence.metadata());
        TestSuiteRunRecord mislabeledGeneration = new TestSuiteRunRecord(record.suiteRunId(),
                record.clientRequestId(), record.requestFingerprint(), record.tenantId(),
                record.organizationId(), record.projectId(), record.environmentId(), record.actorId(),
                record.classification(), record.evidenceFingerprint(), mislabeledEvidence,
                record.attestation(), record.createdAt(), record.expiresAt());
        assertThatThrownBy(() -> suiteRuns.create(mislabeledGeneration,
                new TestSuiteRunLease("instance-a", now.plusSeconds(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed attestation");
        suiteRuns.create(record, new TestSuiteRunLease("instance-a", now.plusSeconds(30)));

        assertThat(suites.find("tenant-a", "test", "suite-v2", 1).orElseThrow().suite())
                .isInstanceOf(TestSuiteV2.class).isEqualTo(suite);
        TestSuiteRunRecord restored = suiteRuns.find("tenant-a", "test", "suite-run-v2").orElseThrow();
        assertThat(restored.evidence()).isInstanceOf(TestSuiteRunEvidenceV2.class).isEqualTo(evidence);
        assertThat(restored.attestation().schemaVersion())
                .isEqualTo(com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.SCHEMA_VERSION_V2);
    }

    @Test
    void admissionCheckpointRetainsV3EvidenceAndSignatureGeneration() {
        Instant now = suiteRuns.currentTime();
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "fixture-v3", 1, "sha256:" + "f".repeat(64));
        TestSuiteRunEvidence.CaseResult pendingCase = new TestSuiteRunEvidence.CaseResult(
                "required", TestSuite.CaseType.BOUNDARY, fixture,
                TestSuiteRunEvidence.CaseStatus.PENDING, "", null, null,
                0, 0, "", "");
        TestSuiteRunEvidenceV3.AdmissionCaseResult pendingAdmission =
                new TestSuiteRunEvidenceV3.AdmissionCaseResult(
                        "required", TestSuiteRunEvidenceV3.AdmissionCaseStatus.PENDING,
                        TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED, null,
                        List.of("visual.context.required"), List.of(), "");
        TestSuiteRunEvidenceV3 evidence = new TestSuiteRunEvidenceV3(
                "", "suite-run-v3", "request-v3", TestSuiteRunEvidence.Status.RUNNING,
                TestSuiteRunEvidenceV3.EXECUTION_PURPOSE,
                new TestSuiteExecutionRequest.SuiteRef(
                        "suite-v3", 1, "sha256:" + "a".repeat(64)),
                new TestSuite.Target("GRAPH", "graph-v3", "sha256:" + "b".repeat(64)),
                now, null, List.of(pendingCase),
                TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.BLOCKED,
                        List.of(TestSuiteRunEvidenceV3.BUSINESS_EXECUTION_NOT_PERFORMED,
                                TestSuiteRunEvidenceV3.SCHEMA_ADMISSION_ONLY),
                        false, 0, 0, false, false, false),
                TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                "sha256:" + "c".repeat(64), "sha256:" + "d".repeat(64),
                "boundary-cases-v1", TestSuiteRunEvidenceV3.VERIFICATION_MODE,
                TestBoundaryCasePlan.Status.GENERATED, 0, false, List.of(pendingAdmission),
                TestSuiteRunEvidenceV3.AdmissionCoverageVerdict.notEvaluated(1),
                List.of(), Map.of());
        String requestFingerprint = "sha256:" + "e".repeat(64);
        var attestation = suiteAttestations.seal(evidence, requestFingerprint, List.of(),
                com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.Scope.CHECKPOINT)
                .attestation();
        TestSuiteRunRecord record = new TestSuiteRunRecord(
                evidence.suiteRunId(), evidence.clientRequestId(), requestFingerprint,
                "tenant-a", "org-a", "project-a", "test", "runner", "INTERNAL", "",
                evidence, attestation, now, now.plusSeconds(3600));
        var downgradedAttestation = new TestSuiteRunAttestation(
                TestSuiteRunAttestation.SCHEMA_VERSION,
                attestation.signatureStatus(), attestation.scope(), attestation.suiteRunId(),
                attestation.suiteRef(), attestation.requestFingerprint(),
                attestation.aggregateEvidenceFingerprint(), attestation.childEvidenceRefs(),
                attestation.signedAt(), attestation.keyId(), attestation.algorithm(),
                attestation.signature(), true);
        TestSuiteRunRecord mixedGeneration = new TestSuiteRunRecord(
                record.suiteRunId(), record.clientRequestId(), record.requestFingerprint(),
                record.tenantId(), record.organizationId(), record.projectId(),
                record.environmentId(), record.actorId(), record.classification(),
                record.evidenceFingerprint(), record.evidence(), downgradedAttestation,
                record.createdAt(), record.expiresAt());

        assertThatThrownBy(() -> suiteRuns.create(mixedGeneration,
                new TestSuiteRunLease("instance-a", now.plusSeconds(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed attestation");

        suiteRuns.create(record, new TestSuiteRunLease("instance-a", now.plusSeconds(30)));

        TestSuiteRunRecord restored = suiteRuns.find(
                "tenant-a", "test", evidence.suiteRunId()).orElseThrow();
        assertThat(restored.evidence()).isInstanceOf(TestSuiteRunEvidenceV3.class)
                .isEqualTo(evidence);
        assertThat(restored.attestation().schemaVersion())
                .isEqualTo(com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation
                        .SCHEMA_VERSION_V3);
        assertThat(restored.attestation().childEvidenceRefs()).isEmpty();

        Instant reconciledAt = now.plusSeconds(31);
        var abandoned = suiteRuns.findAbandoned(reconciledAt, 10).getFirst();
        TestSuiteRunEvidence.CaseResult incompleteCase = new TestSuiteRunEvidence.CaseResult(
                pendingCase.caseId(), pendingCase.caseType(), pendingCase.fixtureBundleRef(),
                TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE, "", null, null,
                0, 0, "ABANDONED_RUN_RECONCILED", "");
        TestSuiteRunEvidenceV3.AdmissionCaseResult incompleteAdmission =
                new TestSuiteRunEvidenceV3.AdmissionCaseResult(
                        pendingAdmission.caseId(),
                        TestSuiteRunEvidenceV3.AdmissionCaseStatus.EVIDENCE_INCOMPLETE,
                        pendingAdmission.expectedOutcome(), null,
                        pendingAdmission.expectedValidationCodes(), List.of(),
                        "ABANDONED_RUN_RECONCILED");
        TestSuiteRunEvidenceV3 terminalEvidence = new TestSuiteRunEvidenceV3(
                "", evidence.suiteRunId(), evidence.clientRequestId(),
                TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE, evidence.executionPurpose(),
                evidence.suiteRef(), evidence.target(), evidence.startedAt(), reconciledAt,
                List.of(incompleteCase), TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.BLOCKED,
                        List.of(TestSuiteRunEvidenceV3.BUSINESS_EXECUTION_NOT_PERFORMED,
                                TestSuiteRunEvidenceV3.SCHEMA_ADMISSION_ONLY,
                                "ABANDONED_RUN_RECONCILED", "EVIDENCE_INCOMPLETE"),
                        false, 0, 0, false, false, false),
                evidence.evaluationMode(), evidence.boundaryPlanFingerprint(),
                evidence.inputSchemaFingerprint(), evidence.generatorVersion(),
                evidence.verificationMode(), evidence.sourcePlanStatus(),
                evidence.sourceCoverageGapCount(), evidence.coverageGapsAccepted(),
                List.of(incompleteAdmission),
                new TestSuiteRunEvidenceV3.AdmissionCoverageVerdict(
                        TestSuiteRunEvidenceV3.AdmissionCoverageStatus.INCOMPLETE,
                        1, 0, 0, List.of(), List.of(), List.of(pendingCase.caseId()), false),
                List.of("ABANDONED_RUN_RECONCILED"),
                Map.of("reconciliationMode", "LEASE_EXPIRY_TERMINALIZATION"));
        var terminalAttestation = suiteAttestations.seal(terminalEvidence, requestFingerprint,
                List.of(), TestSuiteRunAttestation.Scope.TERMINAL).attestation();
        TestSuiteRunRecord terminalRecord = new TestSuiteRunRecord(
                record.suiteRunId(), record.clientRequestId(), record.requestFingerprint(),
                record.tenantId(), record.organizationId(), record.projectId(),
                record.environmentId(), record.actorId(), record.classification(),
                terminalAttestation.aggregateEvidenceFingerprint(), terminalEvidence,
                terminalAttestation, record.createdAt(), record.expiresAt());

        assertThat(suiteRuns.reconcileAbandoned(abandoned, terminalRecord, reconciledAt)).isTrue();
        TestSuiteRunRecord reconciled = suiteRuns.find(
                "tenant-a", "test", evidence.suiteRunId()).orElseThrow();
        assertThat(reconciled.evidence()).isInstanceOf(TestSuiteRunEvidenceV3.class)
                .isEqualTo(terminalEvidence);
        assertThat(reconciled.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V3);
        assertThat(reconciled.attestation().childEvidenceRefs()).isEmpty();
    }

    @Test
    void terminalEvidenceRoundTripsAndLookupAlwaysAppliesScope() {
        Instant now = Instant.now();
        TestRunEvidence evidence = TestSemanticResultFingerprint.attach(mapper,
                new TestRunEvidence("", "run-1", TestRunEvidence.Status.PASSED,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, "GRAPH_CONTRACT_TEST", "sha256:target",
                "sha256:fixture", "sha256:plan", now, now, List.of(), List.of(), List.of(), List.of(),
                List.of(), Map.of("payloadSanitized", true)));
        var integrity = new TestEvidenceIntegrityService(mapper, new InMemoryVisualEvidenceSigner())
                .seal(evidence).integrity();
        TestRunRecord record = new TestRunRecord("run-1", "tenant-a", "org-a", "project-a", "test",
                "runner", new TestExecutionApiRequest.Target("GRAPH", "graph-a", "sha256:target"),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef("STORED", "fixture-a", 2,
                        "sha256:fixture"), TestExecutionApiRequest.Verbosity.FULL, null, evidence,
                integrity, now, now.plusSeconds(3600));

        runs.create(record);

        assertThat(runs.find("tenant-a", "test", "run-1")).contains(record);
        assertThat(runs.find("tenant-b", "test", "run-1")).isEmpty();
        assertThat(runs.find("tenant-a", "prod", "run-1")).isEmpty();
    }

    @Test
    void certifiableEvidenceCannotCrossPersistenceBoundaryUnsigned() {
        Instant now = Instant.now();
        TestRunEvidence evidence = new TestRunEvidence("", "run-unsigned", TestRunEvidence.Status.PASSED,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, "GRAPH_CONTRACT_TEST", "sha256:target",
                "sha256:fixture", "sha256:plan", now, now, List.of(), List.of(), List.of(), List.of(),
                List.of(), Map.of());
        TestRunRecord record = new TestRunRecord("run-unsigned", "tenant-a", "org-a", "project-a", "test",
                "runner", new TestExecutionApiRequest.Target("GRAPH", "graph-a", "sha256:target"),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef("STORED", "fixture-a", 2,
                        "sha256:fixture"), TestExecutionApiRequest.Verbosity.FULL, null, evidence,
                null, now, now.plusSeconds(3600));

        assertThatThrownBy(() -> runs.create(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("verified full-evidence signature");
    }

    @Test
    void suiteRunCheckpointsAreScopedRecoverableAndDatabaseIdempotent() {
        Instant now = Instant.now();
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                "suite-a", 3, "sha256:" + "a".repeat(64));
        TestSuite.Target target = new TestSuite.Target(
                "GRAPH", "graph-a", "sha256:" + "b".repeat(64));
        TestSuiteRunEvidence running = new TestSuiteRunEvidence("", "suite-run-1", "request-1",
                TestSuiteRunEvidence.Status.RUNNING, "TEST_SUITE_EXECUTION", suiteRef, target,
                now, null, List.of(), TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                TestSuiteRunEvidence.PromotionVerdict.notEvaluated(), List.of(), Map.of());
        String requestFingerprint = "sha256:" + "c".repeat(64);
        var runningAttestation = suiteAttestations.seal(running, requestFingerprint, List.of(),
                com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.Scope.CHECKPOINT)
                .attestation();
        TestSuiteRunRecord initial = new TestSuiteRunRecord("suite-run-1", "request-1",
                requestFingerprint, "tenant-a", "org-a", "project-a", "test",
                "runner", "INTERNAL", "", running, runningAttestation,
                now, now.plusSeconds(3600));

        TestSuiteRunLease lease = new TestSuiteRunLease("instance-a", now.plusSeconds(30));
        suiteRuns.create(initial, lease);

        assertThat(suiteRuns.find("tenant-a", "test", "suite-run-1")).contains(initial);
        assertThat(suiteRuns.findByClientRequestId("tenant-a", "test", "request-1"))
                .contains(initial);
        assertThat(suiteRuns.find("tenant-b", "test", "suite-run-1")).isEmpty();
        TestSuiteRunEvidence terminal = new TestSuiteRunEvidence("", "suite-run-1", "request-1",
                TestSuiteRunEvidence.Status.PASSED, "TEST_SUITE_EXECUTION", suiteRef, target,
                now, now.plusSeconds(1), List.of(), new TestSuiteRunEvidence.CoverageVerdict(
                TestSuiteRunEvidence.CoverageStatus.SATISFIED, 0, 0, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0,
                List.of(), List.of(), true), new TestSuiteRunEvidence.PromotionVerdict(
                TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(), true, 0, 0,
                true, true, true), List.of(), Map.of());
        var terminalAttestation = suiteAttestations.seal(terminal, requestFingerprint, List.of(),
                com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.Scope.TERMINAL)
                .attestation();
        TestSuiteRunRecord completed = new TestSuiteRunRecord("suite-run-1", "request-1",
                initial.requestFingerprint(), "tenant-a", "org-a", "project-a", "test", "runner",
                "INTERNAL", terminalAttestation.aggregateEvidenceFingerprint(), terminal,
                terminalAttestation, now, now.plusSeconds(3600));

        suiteRuns.update(completed, new TestSuiteRunLease("instance-a", now.plusSeconds(60)), now);

        assertThat(suiteRuns.find("tenant-a", "test", "suite-run-1")).contains(completed);
        assertThat(suiteRuns.findTerminalBySuite("tenant-a", "test", "suite-a", 3, 10))
                .containsExactly(completed);
        assertThat(suiteRuns.findTerminalBySuite("tenant-b", "test", "suite-a", 3, 10))
                .isEmpty();
        assertThat(suiteRuns.findTerminalBySuite("tenant-a", "test", "suite-a", 4, 10))
                .isEmpty();
        TestSuiteRunEvidence duplicateEvidence = new TestSuiteRunEvidence("", "suite-run-2",
                "request-1", running.status(), running.executionPurpose(), running.suiteRef(),
                running.target(), running.startedAt(), null, running.caseResults(), running.coverage(),
                running.promotion(), running.diagnostics(), running.metadata());
        String duplicateFingerprint = "sha256:" + "e".repeat(64);
        var duplicateAttestation = suiteAttestations.seal(duplicateEvidence, duplicateFingerprint,
                List.of(), com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.Scope.CHECKPOINT)
                .attestation();
        TestSuiteRunRecord duplicate = new TestSuiteRunRecord("suite-run-2", "request-1",
                duplicateFingerprint, "tenant-a", "org-a", "project-a", "test", "runner",
                "INTERNAL", "", duplicateEvidence, duplicateAttestation,
                now, now.plusSeconds(3600));
        assertThatThrownBy(() -> suiteRuns.create(duplicate, lease))
                .isInstanceOf(TestSuiteRunConflictException.class);
    }

    @Test
    void unsignedSuiteCheckpointCannotCrossPersistenceBoundary() {
        Instant now = suiteRuns.currentTime();
        TestSuiteRunRecord signed = suiteRunRecord("suite-run-unsigned", "request-unsigned", now,
                TestSuiteRunEvidence.Status.RUNNING);
        TestSuiteRunRecord unsigned = new TestSuiteRunRecord(signed.suiteRunId(),
                signed.clientRequestId(), signed.requestFingerprint(), signed.tenantId(),
                signed.organizationId(), signed.projectId(), signed.environmentId(),
                signed.actorId(), signed.classification(), signed.evidenceFingerprint(),
                signed.evidence(), signed.createdAt(), signed.expiresAt());

        assertThatThrownBy(() -> suiteRuns.create(unsigned,
                new TestSuiteRunLease("instance-a", now.plusSeconds(30))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed attestation");
    }

    @Test
    void suiteRunLeaseRenewalAndCheckpointVersionFenceAbandonedReconciliation() {
        Instant now = suiteRuns.currentTime();
        TestSuiteRunRecord initial = suiteRunRecord("suite-run-lease", "request-lease", now,
                TestSuiteRunEvidence.Status.RUNNING);
        TestSuiteRunLease initialLease = new TestSuiteRunLease("instance-a", now.plusSeconds(10));
        suiteRuns.create(initial, initialLease);

        assertThat(suiteRuns.findAbandoned(now.plusSeconds(9), 10)).isEmpty();
        assertThat(suiteRuns.renewLease("tenant-a", "test", initial.suiteRunId(),
                "instance-a", now.plusSeconds(40), now.plusSeconds(5))).isTrue();
        assertThat(suiteRuns.renewLease("tenant-a", "test", initial.suiteRunId(),
                "instance-b", now.plusSeconds(50), now.plusSeconds(6))).isFalse();
        assertThat(suiteRuns.findAbandoned(now.plusSeconds(11), 10)).isEmpty();

        var abandoned = suiteRuns.findAbandoned(now.plusSeconds(41), 10).getFirst();
        assertThat(abandoned.record()).isEqualTo(initial);
        assertThat(abandoned.leaseOwner()).isEqualTo("instance-a");
        assertThat(abandoned.checkpointVersion()).isEqualTo(1);

        TestSuiteRunRecord terminal = suiteRunRecord("suite-run-lease", "request-lease", now,
                TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);
        assertThat(suiteRuns.reconcileAbandoned(abandoned, terminal, now.plusSeconds(41))).isTrue();
        assertThat(suiteRuns.reconcileAbandoned(abandoned, terminal, now.plusSeconds(42))).isFalse();
        assertThat(suiteRuns.find("tenant-a", "test", initial.suiteRunId()))
                .contains(terminal);
    }

    @Test
    void staleAbandonedCandidateCannotOverwriteConcurrentRunnerCheckpoint() {
        Instant now = suiteRuns.currentTime();
        TestSuiteRunRecord initial = suiteRunRecord("suite-run-race", "request-race", now,
                TestSuiteRunEvidence.Status.RUNNING);
        suiteRuns.create(initial, new TestSuiteRunLease("instance-a", now.plusSeconds(10)));
        var staleCandidate = suiteRuns.findAbandoned(now.plusSeconds(11), 10).getFirst();

        assertThat(suiteRuns.renewLease("tenant-a", "test", initial.suiteRunId(),
                "instance-a", now.plusSeconds(60), now.plusSeconds(9))).isTrue();
        TestSuiteRunRecord terminal = suiteRunRecord("suite-run-race", "request-race", now,
                TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);

        assertThat(suiteRuns.reconcileAbandoned(staleCandidate, terminal, now.plusSeconds(12))).isFalse();
        assertThat(suiteRuns.find("tenant-a", "test", initial.suiteRunId()))
                .contains(initial);
        assertThat(suiteRuns.findAbandoned(now.plusSeconds(12), 10)).isEmpty();
    }

    private TestSuiteRunRecord suiteRunRecord(String suiteRunId, String requestId,
                                              Instant now, TestSuiteRunEvidence.Status status) {
        TestSuiteExecutionRequest.SuiteRef suiteRef = new TestSuiteExecutionRequest.SuiteRef(
                "suite-a", 3, "sha256:" + "a".repeat(64));
        TestSuite.Target target = new TestSuite.Target(
                "GRAPH", "graph-a", "sha256:" + "b".repeat(64));
        boolean terminal = status != TestSuiteRunEvidence.Status.RUNNING;
        TestSuiteRunEvidence evidence = new TestSuiteRunEvidence("", suiteRunId, requestId,
                status, "TEST_SUITE_EXECUTION", suiteRef, target, now,
                terminal ? now.plusSeconds(41) : null, List.of(),
                terminal ? new TestSuiteRunEvidence.CoverageVerdict(
                        TestSuiteRunEvidence.CoverageStatus.INCOMPLETE, 0, 0, List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        0, List.of(), List.of(), false)
                        : TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                terminal ? new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.BLOCKED,
                        List.of("EVIDENCE_INCOMPLETE"), false, 0, 0, false, false, false)
                        : TestSuiteRunEvidence.PromotionVerdict.notEvaluated(),
                terminal ? List.of("ABANDONED_RUN_RECONCILED") : List.of(), Map.of());
        String requestFingerprint = "sha256:" + "c".repeat(64);
        var attestation = suiteAttestations.seal(evidence, requestFingerprint, List.of(), terminal
                ? com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.Scope.TERMINAL
                : com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation.Scope.CHECKPOINT)
                .attestation();
        return new TestSuiteRunRecord(suiteRunId, requestId, requestFingerprint,
                "tenant-a", "org-a", "project-a", "test", "runner", "INTERNAL",
                terminal ? attestation.aggregateEvidenceFingerprint() : "", evidence, attestation,
                now, now.plusSeconds(3600));
    }

    @Test
    void securityEventsAreAppendOnlyAndCredentialFree() {
        TestSecurityEvent event = new TestSecurityEvent(0, Instant.now(), "correlation-1", "tenant-a",
                "prod", "runner", "TEST_PURPOSE_PRODUCTION_TOUCH", "REJECTED",
                "RG.TEST.ENVIRONMENT_FORBIDDEN", Map.of("endpoint", "/api/testing/executions"));

        TestSecurityEvent stored = securityEvents.append(event);

        assertThat(stored.sequence()).isPositive();
        assertThat(securityEvents.recent(10)).containsExactly(stored);
        assertThat(mapper.valueToTree(stored).toString())
                .doesNotContain("credential", "authorization", "token");
    }
}
