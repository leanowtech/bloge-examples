package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionGuard;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionSubjects;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.testing.planning.TestDslMutationPlanner;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMutationSuiteExecutionServiceTest {
    private static final String TARGET = fingerprint('a');
    private static final String SUITE = fingerprint('b');
    private static final String ORACLE = fingerprint('c');
    private static final String FIXTURE_1 = fingerprint('d');
    private static final String FIXTURE_2 = fingerprint('e');
    private static final String SOURCE = fingerprint('f');
    private static final String GRAPH = fingerprint('1');
    private static final String PLAN = fingerprint('2');

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private TestSuiteRegistryService registry;
    private TestExecutionApiService executions;
    private InMemoryRuns runs;
    private TestRuntimeAdmissionGate admissions;
    private AdmissionGuard admission;
    private TestMutationSuiteExecutionService service;
    private IntegrationRequestContext identity;

    @BeforeEach
    void setUp() {
        registry = mock(TestSuiteRegistryService.class);
        executions = mock(TestExecutionApiService.class);
        runs = new InMemoryRuns();
        admissions = mock(TestRuntimeAdmissionGate.class);
        admission = mock(AdmissionGuard.class);
        when(admissions.admit(any(), any())).thenReturn(admission);
        service = new TestMutationSuiteExecutionService(registry, executions, runs, mapper,
                mock(TestSecurityEventRepository.class), Duration.ofDays(30),
                TestSuiteRunLeaseCoordinator.passive(Duration.ofMinutes(5)),
                new TestSuiteRunAttestationService(
                        mapper, new InMemoryVisualEvidenceSigner()), admissions);
        identity = new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test",
                "local", "WORKLOAD", "runner", "", "TEST_EXECUTION", "correlation-a",
                Set.of("quality"), "CONFIDENTIAL", "");
        when(executions.verifyEvidence(any())).thenReturn(true);
        when(executions.admissionSubjects(any(), eq(identity)))
                .thenReturn(new AdmissionSubjects(Set.of("graph-operator"), Set.of("resource-a")));
    }

    @Test
    void collectAllExecutesBaselineThenCompleteMutantMatrixAndScoresIt() {
        Scenario scenario = scenario(new TestSuiteV5.MutationScorePolicy(
                5_000, 0, false, false));
        prepare(scenario);
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(child("baseline-golden", "golden", TARGET,
                        TestExecutionApiService.AUTHORIZED_PURPOSE, TestRunEvidence.Status.PASSED))
                .thenReturn(child("baseline-negative", "negative", TARGET,
                        TestExecutionApiService.AUTHORIZED_PURPOSE, TestRunEvidence.Status.PASSED));
        when(executions.executeAdmittedMutationGraphCase(any(), eq(TARGET), any(), eq(identity)))
                .thenReturn(child("m1-golden", "golden", mutantTarget(1),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE,
                        TestRunEvidence.Status.ASSERTION_FAILED))
                .thenReturn(child("m1-negative", "negative", mutantTarget(1),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE, TestRunEvidence.Status.PASSED))
                .thenReturn(child("m2-golden", "golden", mutantTarget(2),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE, TestRunEvidence.Status.PASSED))
                .thenReturn(child("m2-negative", "negative", mutantTarget(2),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE, TestRunEvidence.Status.PASSED));

        TestSuiteExecutionResponse response = service.execute("mutations",
                request("collect-all", TestMutationSuiteExecutionRequest.Strategy.COLLECT_ALL),
                identity);
        TestSuiteExecutionResponse retry = service.execute("mutations",
                request("collect-all", TestMutationSuiteExecutionRequest.Strategy.COLLECT_ALL),
                identity);

        assertThat(retry).isEqualTo(response);
        assertThat(response.schemaVersion()).isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V6);
        assertThat(response.evidence()).isInstanceOfSatisfying(TestSuiteRunEvidenceV5.class,
                evidence -> {
                    assertThat(evidence.status()).as("diagnostics=%s baseline=%s mutants=%s score=%s",
                                    evidence.diagnostics(), evidence.baselineStatus(),
                                    evidence.mutantResults(), evidence.mutationScore())
                            .isEqualTo(TestSuiteRunEvidence.Status.PASSED);
                    assertThat(evidence.baselineStatus())
                            .isEqualTo(TestSuiteRunEvidenceV5.BaselineStatus.PASSED);
                    assertThat(evidence.mutantResults())
                            .extracting(TestSuiteRunEvidenceV5.MutantResult::status)
                            .containsExactly(TestSuiteRunEvidenceV5.MutantStatus.KILLED,
                                    TestSuiteRunEvidenceV5.MutantStatus.SURVIVED);
                    assertThat(evidence.mutationScore().scoreBasisPoints()).isEqualTo(5_000);
                    assertThat(evidence.mutationScore().status())
                            .isEqualTo(TestSuiteRunEvidenceV5.MutationScoreStatus.SATISFIED);
                    assertThat(evidence.promotion().status())
                            .isEqualTo(TestSuiteRunEvidence.PromotionStatus.ELIGIBLE);
                    assertThat(evidence.metadata()).containsEntry("baselineChildRunCount", 2)
                            .containsEntry("mutantChildRunCount", 4)
                            .doesNotContainKey("requestMetadata");
                });
        assertThat(response.attestation().terminallyVerifiable()).isTrue();
        assertThat(response.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V5);
        assertThat(response.attestation().childEvidenceRefs())
                .extracting(TestSuiteRunAttestation.ChildEvidenceRef::caseId)
                .containsExactly("baseline/golden", "baseline/negative",
                        "mutant-001/golden", "mutant-001/negative",
                        "mutant-002/golden", "mutant-002/negative");
        verify(executions, times(2)).executeAdmittedSuiteGraphCase(any(), eq(identity));
        verify(executions, times(4)).executeAdmittedMutationGraphCase(
                any(), eq(TARGET), any(), eq(identity));
        verify(admission).checkpoint();
        verify(admission).close();
        assertThat(runs.records).hasSize(1);
    }

    @Test
    void stopAfterKillSkipsOnlyCurrentMutantAndStillClassifiesEveryMutant() {
        Scenario scenario = scenario(new TestSuiteV5.MutationScorePolicy(
                5_000, 0, false, false));
        prepare(scenario);
        passingBaseline();
        when(executions.executeAdmittedMutationGraphCase(any(), eq(TARGET), any(), eq(identity)))
                .thenReturn(child("m1-golden", "golden", mutantTarget(1),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE,
                        TestRunEvidence.Status.ASSERTION_FAILED))
                .thenReturn(child("m2-golden", "golden", mutantTarget(2),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE, TestRunEvidence.Status.PASSED))
                .thenReturn(child("m2-negative", "negative", mutantTarget(2),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE, TestRunEvidence.Status.PASSED));

        TestSuiteExecutionResponse response = service.execute("mutations",
                request("stop-after-kill",
                        TestMutationSuiteExecutionRequest.Strategy.STOP_AFTER_KILL), identity);
        TestSuiteRunEvidenceV5 evidence = (TestSuiteRunEvidenceV5) response.evidence();

        assertThat(evidence.status()).as("diagnostics=%s baseline=%s mutants=%s score=%s",
                        evidence.diagnostics(), evidence.baselineStatus(),
                        evidence.mutantResults(), evidence.mutationScore())
                .isEqualTo(TestSuiteRunEvidence.Status.PASSED);
        assertThat(evidence.mutantResults().getFirst().caseResults())
                .extracting(TestSuiteRunEvidenceV5.MutantCaseResult::status)
                .containsExactly(TestSuiteRunEvidenceV5.MutantCaseStatus.ASSERTION_KILLED,
                        TestSuiteRunEvidenceV5.MutantCaseStatus.NOT_SCHEDULED);
        assertThat(evidence.mutantResults().getFirst().caseResults().get(1).diagnosticCode())
                .isEqualTo("MUTANT_KILL_SHORT_CIRCUIT");
        assertThat(evidence.mutantResults().get(1).status())
                .isEqualTo(TestSuiteRunEvidenceV5.MutantStatus.SURVIVED);
        verify(executions, times(3)).executeAdmittedMutationGraphCase(
                any(), eq(TARGET), any(), eq(identity));
    }

    @Test
    void failedBaselinePreventsEveryMutantFromBeingScheduled() {
        Scenario scenario = scenario(new TestSuiteV5.MutationScorePolicy(
                0, 2, false, false));
        prepare(scenario);
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(child("baseline-golden", "golden", TARGET,
                        TestExecutionApiService.AUTHORIZED_PURPOSE,
                        TestRunEvidence.Status.ASSERTION_FAILED))
                .thenReturn(child("baseline-negative", "negative", TARGET,
                        TestExecutionApiService.AUTHORIZED_PURPOSE, TestRunEvidence.Status.PASSED));

        TestSuiteExecutionResponse response = service.execute("mutations",
                request("baseline-failed",
                        TestMutationSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);
        TestSuiteRunEvidenceV5 evidence = (TestSuiteRunEvidenceV5) response.evidence();

        assertThat(evidence.status()).as("diagnostics=%s baseline=%s mutants=%s score=%s",
                        evidence.diagnostics(), evidence.baselineStatus(),
                        evidence.mutantResults(), evidence.mutationScore())
                .isEqualTo(TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES);
        assertThat(evidence.baselineStatus())
                .isEqualTo(TestSuiteRunEvidenceV5.BaselineStatus.FAILED);
        assertThat(evidence.mutationScore().status())
                .isEqualTo(TestSuiteRunEvidenceV5.MutationScoreStatus.UNSATISFIED);
        assertThat(evidence.mutationScore().killedMutants()).isZero();
        assertThat(evidence.mutantResults())
                .allSatisfy(result -> assertThat(result.status())
                        .isEqualTo(TestSuiteRunEvidenceV5.MutantStatus.NOT_SCHEDULED));
        verify(executions, never()).executeAdmittedMutationGraphCase(
                any(), any(), any(), any());
    }

    @Test
    void runtimeFailureIsInconclusiveAndCannotInflateTheKilledCount() {
        Scenario scenario = scenario(new TestSuiteV5.MutationScorePolicy(
                10_000, 1, false, false));
        prepare(scenario);
        passingBaseline();
        when(executions.executeAdmittedMutationGraphCase(any(), eq(TARGET), any(), eq(identity)))
                .thenReturn(child("m1-golden", "golden", mutantTarget(1),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE,
                        TestRunEvidence.Status.EXECUTION_FAILED))
                .thenReturn(child("m1-negative", "negative", mutantTarget(1),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE, TestRunEvidence.Status.PASSED))
                .thenReturn(child("m2-golden", "golden", mutantTarget(2),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE,
                        TestRunEvidence.Status.ASSERTION_FAILED))
                .thenReturn(child("m2-negative", "negative", mutantTarget(2),
                        TestSuiteRunEvidenceV5.EXECUTION_PURPOSE, TestRunEvidence.Status.PASSED));

        TestSuiteRunEvidenceV5 evidence = (TestSuiteRunEvidenceV5) service.execute("mutations",
                request("runtime-failure",
                        TestMutationSuiteExecutionRequest.Strategy.COLLECT_ALL), identity).evidence();

        assertThat(evidence.mutantResults())
                .extracting(TestSuiteRunEvidenceV5.MutantResult::status)
                .containsExactly(TestSuiteRunEvidenceV5.MutantStatus.INCONCLUSIVE,
                        TestSuiteRunEvidenceV5.MutantStatus.KILLED);
        assertThat(evidence.mutationScore().killedMutants()).isEqualTo(1);
        assertThat(evidence.mutationScore().inconclusiveMutants()).isEqualTo(1);
        assertThat(evidence.mutationScore().denominatorMutants()).isEqualTo(1);
        assertThat(evidence.mutationScore().scoreBasisPoints()).isEqualTo(10_000);
    }

    @Test
    void checkpointFailureStopsSchedulingAndReturnsSignedIncompleteEvidence() {
        Scenario scenario = scenario(new TestSuiteV5.MutationScorePolicy(
                0, 2, false, false));
        prepare(scenario);
        runs.failUpdateNumber = 1;
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(child("baseline-golden", "golden", TARGET,
                        TestExecutionApiService.AUTHORIZED_PURPOSE, TestRunEvidence.Status.PASSED));

        TestSuiteExecutionResponse response = service.execute("mutations",
                request("checkpoint-failure",
                        TestMutationSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);
        TestSuiteRunEvidenceV5 evidence = (TestSuiteRunEvidenceV5) response.evidence();

        assertThat(evidence.status()).isEqualTo(TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);
        assertThat(evidence.baselineStatus())
                .isEqualTo(TestSuiteRunEvidenceV5.BaselineStatus.EVIDENCE_INCOMPLETE);
        assertThat(evidence.caseResults())
                .extracting(TestSuiteRunEvidence.CaseResult::status)
                .containsExactly(TestSuiteRunEvidence.CaseStatus.PASSED,
                        TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED);
        assertThat(evidence.mutantResults())
                .allSatisfy(result -> assertThat(result.status())
                        .isEqualTo(TestSuiteRunEvidenceV5.MutantStatus.NOT_SCHEDULED));
        assertThat(evidence.diagnostics()).contains("SUITE_RUN_STORE_UNAVAILABLE");
        assertThat(response.attestation().terminallyVerifiable()).isTrue();
        verify(executions, times(1)).executeAdmittedSuiteGraphCase(any(), eq(identity));
        verify(executions, never()).executeAdmittedMutationGraphCase(
                any(), any(), any(), any());
    }

    private void prepare(Scenario scenario) {
        when(registry.find("mutations", 3, identity)).thenReturn(scenario.stored());
        when(registry.find("oracle", 1, identity)).thenReturn(scenario.oracle());
        when(executions.regenerateGraphMutations(eq("graph-a"), any(), eq(identity)))
                .thenReturn(scenario.regenerated());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(
                new TestGraphTargetDescriptor("", new TestExecutionApiRequest.Target(
                        "GRAPH", "graph-a", TARGET), null, Map.of(), "NONE", true, List.of()));
    }

    private void passingBaseline() {
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(child("baseline-golden", "golden", TARGET,
                        TestExecutionApiService.AUTHORIZED_PURPOSE, TestRunEvidence.Status.PASSED))
                .thenReturn(child("baseline-negative", "negative", TARGET,
                        TestExecutionApiService.AUTHORIZED_PURPOSE, TestRunEvidence.Status.PASSED));
    }

    private Scenario scenario(TestSuiteV5.MutationScorePolicy scorePolicy) {
        List<TestSuite.TestCase> cases = List.of(testCase("golden", TestSuite.CaseType.GOLDEN,
                        "fixture-golden", 1, FIXTURE_1),
                testCase("negative", TestSuite.CaseType.NEGATIVE,
                        "fixture-negative", 2, FIXTURE_2));
        TestSuite oracleSuite = new TestSuite("", "oracle", 1,
                new TestSuite.Target("GRAPH", "graph-a", TARGET), "INTERNAL", cases,
                new TestSuite.CoveragePolicy(2,
                        List.of(TestSuite.CaseType.GOLDEN, TestSuite.CaseType.NEGATIVE),
                        List.of(), List.of(), 1, false),
                new TestSuite.PromotionPolicy(true, 2, true), Map.of());
        List<TestSuiteV5.MutantRef> mutants = List.of(mutant(1), mutant(2));
        TestSuiteV5 suite = new TestSuiteV5("", "mutations", 3, oracleSuite.target(),
                "INTERNAL", cases, oracleSuite.coveragePolicy(), SemanticCoveragePolicy.empty(),
                oracleSuite.promotionPolicy(), TestSuiteV5.EvaluationMode.PURE_DSL_MUTATION,
                TestSuiteV5.SOURCE_FORMAT, SOURCE, GRAPH, PLAN,
                new TestSuiteV5.MutationPolicy(TestSuiteV5.PLANNER_VERSION, 2,
                        TestSuiteV5.SOURCE_FORMAT, TestSuiteV5.VERIFICATION_MODE, false, false),
                TestSuiteV5.SourcePlanStatus.GENERATED, false, List.of(), mutants,
                new TestSuiteV5.OracleSuiteRef("oracle", 1, ORACLE, TestSuite.SCHEMA_VERSION),
                scorePolicy, Map.of());
        StoredTestSuite stored = new StoredTestSuite("", "tenant-a", "test", "mutations", 3,
                SUITE, suite, Instant.EPOCH, "author");
        StoredTestSuite oracle = new StoredTestSuite("", "tenant-a", "test", "oracle", 1,
                ORACLE, oracleSuite, Instant.EPOCH, "author");
        Graph graph = new GraphBuilder("graph-a")
                .node("subject", (Operator<Object, Object>) (input, context) -> input).build();
        List<TestDslMutationPlanner.RegeneratedMutant> regenerated = mutants.stream()
                .map(value -> new TestDslMutationPlanner.RegeneratedMutant(PLAN,
                        planned(value), graph)).toList();
        return new Scenario(stored, oracle, regenerated);
    }

    private static TestSuite.TestCase testCase(
            String caseId,
            TestSuite.CaseType type,
            String fixtureId,
            long revision,
            String fixtureFingerprint) {
        return new TestSuite.TestCase(caseId, type, Map.of("case", caseId),
                new TestSuite.FixtureBundleRef(
                        fixtureId, revision, fixtureFingerprint), List.of(), Map.of());
    }

    private static TestSuiteV5.MutantRef mutant(int index) {
        return new TestSuiteV5.MutantRef("mutant-%03d".formatted(index),
                TestSuiteV5.MutationKind.DECISION_CONDITION_NEGATED,
                "/members/%d/predicate".formatted(index), index, 1,
                indexedFingerprint(index), indexedFingerprint(100 + index), mutantTarget(index),
                TestSuiteV5.EquivalenceClassification.UNKNOWN);
    }

    private static TestMutationCasePlan.PlannedMutant planned(TestSuiteV5.MutantRef value) {
        return new TestMutationCasePlan.PlannedMutant(value.mutantId(),
                TestMutationCasePlan.MutationKind.valueOf(value.kind().name()), value.astPath(),
                value.sourceLine(), value.sourceColumn(), value.mutantSourceFingerprint(),
                value.mutantGraphArtifactFingerprint(), value.mutantTargetFingerprint(),
                TestMutationCasePlan.EquivalenceClassification.UNKNOWN);
    }

    private static TestMutationSuiteExecutionRequest request(
            String clientRequestId,
            TestMutationSuiteExecutionRequest.Strategy strategy) {
        return new TestMutationSuiteExecutionRequest("",
                new TestSuiteExecutionRequest.SuiteRef("mutations", 3, SUITE),
                clientRequestId, strategy, Map.of("pipeline", "nightly"));
    }

    private static TestExecutionApiResponse child(
            String runId,
            String caseId,
            String target,
            String purpose,
            TestRunEvidence.Status status) {
        boolean passed = status == TestRunEvidence.Status.PASSED;
        String fixtureId = "golden".equals(caseId) ? "fixture-golden" : "fixture-negative";
        long revision = "golden".equals(caseId) ? 1 : 2;
        String fixture = "golden".equals(caseId) ? FIXTURE_1 : FIXTURE_2;
        Instant now = Instant.now();
        TestRunEvidence evidence = new TestRunEvidence("", runId, status,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, purpose, target, fixture,
                fingerprint('9'), fingerprint('8'), now, now, List.of(), List.of(), List.of(),
                List.of(new TestRunEvidence.AssertionResult(
                        "OUTPUT", "/ok", passed, true, passed,
                        passed ? "" : "expected output did not match")),
                passed ? List.of() : List.of(status.name()), Map.of("caseId", caseId));
        return new TestExecutionApiResponse("", runId,
                new TestExecutionApiRequest.Target("GRAPH", "graph-a", target),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef(
                        "STORED", fixtureId, revision, fixture), null, evidence);
    }

    private static String mutantTarget(int index) {
        return indexedFingerprint(200 + index);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String indexedFingerprint(int value) {
        return "sha256:" + "%064x".formatted(value);
    }

    private record Scenario(
            StoredTestSuite stored,
            StoredTestSuite oracle,
            List<TestDslMutationPlanner.RegeneratedMutant> regenerated
    ) {
    }

    private static final class InMemoryRuns implements TestSuiteRunRepository {
        private final Map<String, TestSuiteRunRecord> records = new LinkedHashMap<>();
        private int updateNumber;
        private int failUpdateNumber;

        @Override
        public TestSuiteRunRecord create(TestSuiteRunRecord record, TestSuiteRunLease lease) {
            if (records.values().stream().anyMatch(value ->
                    value.clientRequestId().equals(record.clientRequestId()))) {
                throw new TestSuiteRunConflictException("duplicate idempotency key");
            }
            records.put(record.suiteRunId(), record);
            return record;
        }

        @Override
        public TestSuiteRunRecord update(
                TestSuiteRunRecord record,
                TestSuiteRunLease lease,
                Instant observedAt) {
            updateNumber++;
            if (failUpdateNumber == updateNumber) {
                throw new IllegalStateException("checkpoint store unavailable");
            }
            records.put(record.suiteRunId(), record);
            return record;
        }

        @Override
        public boolean renewLease(String tenantId, String environmentId, String suiteRunId,
                                  String ownerId, Instant expiresAt, Instant observedAt) {
            return records.containsKey(suiteRunId);
        }

        @Override
        public List<AbandonedTestSuiteRun> findAbandoned(Instant observedAt, int limit) {
            return List.of();
        }

        @Override
        public boolean reconcileAbandoned(
                AbandonedTestSuiteRun abandoned,
                TestSuiteRunRecord terminal,
                Instant observedAt) {
            return false;
        }

        @Override
        public Optional<TestSuiteRunRecord> find(
                String tenantId,
                String environmentId,
                String suiteRunId) {
            return Optional.ofNullable(records.get(suiteRunId));
        }

        @Override
        public Optional<TestSuiteRunRecord> findByClientRequestId(
                String tenantId,
                String environmentId,
                String clientRequestId) {
            return records.values().stream()
                    .filter(value -> value.tenantId().equals(tenantId)
                            && value.environmentId().equals(environmentId)
                            && value.clientRequestId().equals(clientRequestId))
                    .findFirst();
        }
    }
}
