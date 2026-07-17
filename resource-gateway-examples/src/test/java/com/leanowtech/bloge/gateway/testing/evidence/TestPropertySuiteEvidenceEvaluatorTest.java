package com.leanowtech.bloge.gateway.testing.evidence;

import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPropertySuiteEvidenceEvaluatorTest {
    private static final String PLAN = fingerprint('a');
    private static final String INPUT_SCHEMA = fingerprint('b');
    private static final String TARGET = fingerprint('c');
    private static final String FIXTURE = fingerprint('d');

    private final TestPropertySuiteEvidenceEvaluator evaluator =
            new TestPropertySuiteEvidenceEvaluator();

    @Test
    void derivesPathLocalMinimumWithoutClaimingGlobalMinimality() {
        TestPropertySuiteEvidenceEvaluator.Evaluation result = evaluator.evaluate(suite(), List.of(
                observation("property-001", TestRunEvidence.Status.ASSERTION_FAILED),
                observation("property-001-shrink-001", TestRunEvidence.Status.ASSERTION_FAILED),
                observation("property-001-shrink-002", TestRunEvidence.Status.PASSED)));

        TestSuiteRunEvidenceV4.PropertyTrialResult trial = result.trialResults().getFirst();
        assertThat(trial.status())
                .isEqualTo(TestSuiteRunEvidenceV4.PropertyTrialStatus.COUNTEREXAMPLE);
        assertThat(trial.minimalObservedCounterexample())
                .extracting(TestSuiteRunEvidenceV4.CounterexampleRef::caseId,
                        TestSuiteRunEvidenceV4.CounterexampleRef::complexity,
                        TestSuiteRunEvidenceV4.CounterexampleRef::minimalityScope,
                        TestSuiteRunEvidenceV4.CounterexampleRef::globallyMinimal)
                .containsExactly("property-001-shrink-001", 2,
                        TestSuiteRunEvidenceV4.MINIMALITY_SCOPE, false);
        assertThat(result.coverage().status())
                .isEqualTo(TestSuiteRunEvidenceV4.PropertyCoverageStatus.COUNTEREXAMPLE);
        assertThat(result.coverage().counterexampleCases()).isEqualTo(2);
        assertThat(result.coverage().allCasesCompleted()).isTrue();
    }

    @Test
    void keepsRuntimeFailureSeparateFromBusinessCounterexamples() {
        TestPropertySuiteEvidenceEvaluator.Evaluation result = evaluator.evaluate(suite(), List.of(
                observation("property-001", TestRunEvidence.Status.EXECUTION_FAILED),
                observation("property-001-shrink-001", TestRunEvidence.Status.PASSED),
                observation("property-001-shrink-002", TestRunEvidence.Status.PASSED)));

        assertThat(result.trialResults().getFirst().status())
                .isEqualTo(TestSuiteRunEvidenceV4.PropertyTrialStatus.EXECUTION_FAILED);
        assertThat(result.coverage().status())
                .isEqualTo(TestSuiteRunEvidenceV4.PropertyCoverageStatus.EXECUTION_FAILED);
        assertThat(result.coverage().executionFailedCaseIds())
                .containsExactly("property-001");
        assertThat(result.coverage().counterexampleCases()).isZero();
        assertThat(result.coverage().minimalObservedCounterexamples()).isEmpty();
    }

    @Test
    void reconciliationTerminalizesOnlyPendingCasesAndPreservesCompletedFacts() {
        TestPropertySuiteEvidenceEvaluator.Evaluation checkpoint = evaluator.evaluate(suite(), List.of(
                observation("property-001", TestRunEvidence.Status.PASSED),
                pending("property-001-shrink-001"),
                pending("property-001-shrink-002")));

        TestPropertySuiteEvidenceEvaluator.Evaluation recovered = evaluator.markIncomplete(
                checkpoint.trialResults(),
                java.util.Set.of("property-001-shrink-001", "property-001-shrink-002"),
                "ABANDONED_RUN_RECONCILED");

        assertThat(recovered.trialResults().getFirst().rootResult())
                .isEqualTo(checkpoint.trialResults().getFirst().rootResult());
        assertThat(recovered.trialResults().getFirst().shrinkResults())
                .extracting(TestSuiteRunEvidenceV4.PropertyCaseResult::status)
                .containsExactly(TestSuiteRunEvidenceV4.PropertyCaseStatus.EVIDENCE_INCOMPLETE,
                        TestSuiteRunEvidenceV4.PropertyCaseStatus.EVIDENCE_INCOMPLETE);
        assertThat(recovered.coverage().status())
                .isEqualTo(TestSuiteRunEvidenceV4.PropertyCoverageStatus.INCOMPLETE);
        assertThat(recovered.coverage().incompleteCaseIds())
                .containsExactly("property-001-shrink-001", "property-001-shrink-002");
    }

    @Test
    void rejectsObservationOrderDriftAndFalseGlobalMinimalityClaims() {
        assertThatThrownBy(() -> evaluator.evaluate(suite(), List.of(
                observation("property-001-shrink-001", TestRunEvidence.Status.PASSED),
                observation("property-001", TestRunEvidence.Status.PASSED),
                observation("property-001-shrink-002", TestRunEvidence.Status.PASSED))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order and identity");

        assertThatThrownBy(() -> new TestSuiteRunEvidenceV4.CounterexampleRef(
                "property-001", fingerprint('1'), 3,
                TestSuiteRunEvidenceV4.MINIMALITY_SCOPE, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimality claim");
    }

    private static TestSuiteEvidenceAggregator.CaseObservation observation(
            String caseId, TestRunEvidence.Status status) {
        TestRunEvidence evidence = evidence(caseId, status);
        int evaluated = 1;
        int passed = status == TestRunEvidence.Status.PASSED ? 1 : 0;
        TestSuiteRunEvidence.CaseStatus commonStatus = status == TestRunEvidence.Status.PASSED
                ? TestSuiteRunEvidence.CaseStatus.PASSED : TestSuiteRunEvidence.CaseStatus.FAILED;
        TestSuiteRunEvidence.CaseResult common = new TestSuiteRunEvidence.CaseResult(caseId,
                TestSuite.CaseType.PROPERTY, fixture(), commonStatus, evidence.runId(), status,
                evidence.evidenceClass(), evaluated, passed,
                status == TestRunEvidence.Status.PASSED ? "" : "CHILD_" + status.name(), "");
        return new TestSuiteEvidenceAggregator.CaseObservation(common, evidence);
    }

    private static TestSuiteEvidenceAggregator.CaseObservation pending(String caseId) {
        return new TestSuiteEvidenceAggregator.CaseObservation(
                new TestSuiteRunEvidence.CaseResult(caseId, TestSuite.CaseType.PROPERTY, fixture(),
                        TestSuiteRunEvidence.CaseStatus.PENDING, "", null, null,
                        0, 0, "", ""), null);
    }

    private static TestRunEvidence evidence(String caseId, TestRunEvidence.Status status) {
        boolean passed = status == TestRunEvidence.Status.PASSED;
        return new TestRunEvidence("", "run-" + caseId, status,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, "TEST_SUITE_EXECUTION",
                TARGET, FIXTURE, PLAN, fingerprint('9'), Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                List.of(), List.of(), List.of(),
                List.of(new TestRunEvidence.AssertionResult(
                        "OUTPUT", "/ok", passed, true, passed, passed ? "" : "mismatch")),
                List.of(), Map.of());
    }

    private static TestSuiteV4 suite() {
        List<TestSuite.TestCase> cases = List.of(
                testCase("property-001"),
                testCase("property-001-shrink-001"),
                testCase("property-001-shrink-002"));
        return new TestSuiteV4("", "property-suite", 1,
                new TestSuite.Target("GRAPH", "orders", TARGET), "INTERNAL", cases,
                new TestSuite.CoveragePolicy(3, List.of(TestSuite.CaseType.PROPERTY),
                        List.of(), List.of(), 1, false),
                SemanticCoveragePolicy.empty(), new TestSuite.PromotionPolicy(true, 3, true),
                TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION,
                TestSuiteV4.Quantification.BOUNDED_SAMPLED, false, PLAN, INPUT_SCHEMA,
                new TestSuiteV4.PropertyGenerationPolicy(
                        "property-cases-v1", 42, 1, 2, 3, 32, 8, 32,
                        "DRAFT_2020_12_SHARED_VALIDATOR"),
                TestSuiteV4.SourcePlanStatus.GENERATED, false, List.of(),
                List.of(new TestSuiteV4.PropertyTrialRef("property-001", fingerprint('e'), 3,
                        List.of(
                                new TestSuiteV4.PropertyShrinkRef(
                                        "property-001-shrink-001", "property-001", 1,
                                        fingerprint('f'), 2),
                                new TestSuiteV4.PropertyShrinkRef(
                                        "property-001-shrink-002",
                                        "property-001-shrink-001", 2,
                                        fingerprint('0'), 1)))), Map.of());
    }

    private static TestSuite.TestCase testCase(String caseId) {
        return new TestSuite.TestCase(caseId, TestSuite.CaseType.PROPERTY,
                Map.of("caseId", caseId), fixture(), List.of("property"), Map.of());
    }

    private static TestSuite.FixtureBundleRef fixture() {
        return new TestSuite.FixtureBundleRef("fixture", 1, FIXTURE);
    }

    private static String fingerprint(char character) {
        return "sha256:" + String.valueOf(character).repeat(64);
    }
}
