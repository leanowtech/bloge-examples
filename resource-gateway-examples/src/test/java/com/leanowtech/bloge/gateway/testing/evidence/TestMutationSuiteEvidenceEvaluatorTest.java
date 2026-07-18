package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestMutationSuiteEvidenceEvaluatorTest {
    private static final String TARGET = fingerprint('a');
    private static final String SOURCE = fingerprint('b');
    private static final String GRAPH = fingerprint('c');
    private static final String PLAN = fingerprint('d');
    private static final String ORACLE = fingerprint('e');
    private static final String FIXTURE = fingerprint('f');

    private final TestMutationSuiteEvidenceEvaluator evaluator =
            new TestMutationSuiteEvidenceEvaluator(new ObjectMapper().findAndRegisterModules());

    @Test
    void derivesKillsOnlyFromAssertionFailureAndScoresTheCompleteDenominator() {
        TestSuiteV5 suite = suite(new TestSuiteV5.MutationScorePolicy(
                5_000, 0, false, false));

        TestMutationSuiteEvidenceEvaluator.Evaluation result = evaluator.evaluate(suite,
                TestSuiteRunEvidenceV5.BaselineStatus.PASSED, List.of(
                        List.of(observation(suite, 0, 0, TestRunEvidence.Status.PASSED),
                                observation(suite, 0, 1,
                                        TestRunEvidence.Status.ASSERTION_FAILED)),
                        List.of(observation(suite, 1, 0, TestRunEvidence.Status.PASSED),
                                observation(suite, 1, 1, TestRunEvidence.Status.PASSED))));

        assertThat(result.mutantResults())
                .extracting(TestSuiteRunEvidenceV5.MutantResult::status)
                .containsExactly(TestSuiteRunEvidenceV5.MutantStatus.KILLED,
                        TestSuiteRunEvidenceV5.MutantStatus.SURVIVED);
        assertThat(result.mutantResults().getFirst().killingCaseIds())
                .containsExactly("negative");
        assertThat(result.score())
                .extracting(TestSuiteRunEvidenceV5.MutationScoreVerdict::status,
                        TestSuiteRunEvidenceV5.MutationScoreVerdict::killedMutants,
                        TestSuiteRunEvidenceV5.MutationScoreVerdict::survivedMutants,
                        TestSuiteRunEvidenceV5.MutationScoreVerdict::denominatorMutants,
                        TestSuiteRunEvidenceV5.MutationScoreVerdict::scoreBasisPoints)
                .containsExactly(TestSuiteRunEvidenceV5.MutationScoreStatus.SATISFIED,
                        1, 1, 2, 5_000);
    }

    @Test
    void excludesInconclusiveRuntimeFailureWithoutInflatingKilledCount() {
        TestSuiteV5 suite = suite(new TestSuiteV5.MutationScorePolicy(
                10_000, 1, false, false));

        TestMutationSuiteEvidenceEvaluator.Evaluation result = evaluator.evaluate(suite,
                TestSuiteRunEvidenceV5.BaselineStatus.PASSED, List.of(
                        List.of(observation(suite, 0, 0,
                                        TestRunEvidence.Status.ASSERTION_FAILED),
                                notScheduled(suite, 0, 1, "MUTANT_KILL_SHORT_CIRCUIT")),
                        List.of(observation(suite, 1, 0,
                                        TestRunEvidence.Status.EXECUTION_FAILED),
                                observation(suite, 1, 1, TestRunEvidence.Status.PASSED))));

        assertThat(result.mutantResults())
                .extracting(TestSuiteRunEvidenceV5.MutantResult::status)
                .containsExactly(TestSuiteRunEvidenceV5.MutantStatus.KILLED,
                        TestSuiteRunEvidenceV5.MutantStatus.INCONCLUSIVE);
        assertThat(result.score().killedMutants()).isEqualTo(1);
        assertThat(result.score().inconclusiveMutants()).isEqualTo(1);
        assertThat(result.score().denominatorMutants()).isEqualTo(1);
        assertThat(result.score().scoreBasisPoints()).isEqualTo(10_000);
        assertThat(result.score().status())
                .isEqualTo(TestSuiteRunEvidenceV5.MutationScoreStatus.SATISFIED);
    }

    @Test
    void appliesInconclusiveAndSurvivorGatePoliciesIndependentlyOfNumericScore() {
        TestSuiteV5 inconclusiveForbidden = suite(new TestSuiteV5.MutationScorePolicy(
                10_000, 0, false, false));
        TestMutationSuiteEvidenceEvaluator.Evaluation inconclusive = evaluator.evaluate(
                inconclusiveForbidden, TestSuiteRunEvidenceV5.BaselineStatus.PASSED, List.of(
                        List.of(observation(inconclusiveForbidden, 0, 0,
                                        TestRunEvidence.Status.ASSERTION_FAILED),
                                notScheduled(inconclusiveForbidden, 0, 1,
                                        "MUTANT_KILL_SHORT_CIRCUIT")),
                        List.of(observation(inconclusiveForbidden, 1, 0,
                                        TestRunEvidence.Status.TIMED_OUT),
                                observation(inconclusiveForbidden, 1, 1,
                                        TestRunEvidence.Status.PASSED))));
        assertThat(inconclusive.score().status())
                .isEqualTo(TestSuiteRunEvidenceV5.MutationScoreStatus.UNSATISFIED);
        assertThat(inconclusive.score().reasons())
                .containsExactly("MUTATION_INCONCLUSIVE_LIMIT_EXCEEDED");

        TestSuiteV5 noSurvivors = suite(new TestSuiteV5.MutationScorePolicy(
                5_000, 0, true, false));
        TestMutationSuiteEvidenceEvaluator.Evaluation survivor = evaluator.evaluate(noSurvivors,
                TestSuiteRunEvidenceV5.BaselineStatus.PASSED, List.of(
                        List.of(observation(noSurvivors, 0, 0,
                                        TestRunEvidence.Status.ASSERTION_FAILED),
                                notScheduled(noSurvivors, 0, 1,
                                        "MUTANT_KILL_SHORT_CIRCUIT")),
                        List.of(observation(noSurvivors, 1, 0, TestRunEvidence.Status.PASSED),
                                observation(noSurvivors, 1, 1,
                                        TestRunEvidence.Status.PASSED))));
        assertThat(survivor.score().scoreBasisPoints()).isEqualTo(5_000);
        assertThat(survivor.score().reasons())
                .containsExactly("MUTATION_SURVIVOR_FORBIDDEN");
    }

    @Test
    void keepsUnclassifiedMatrixIncompleteAndSuppressesProvisionalScore() {
        TestSuiteV5 suite = suite(new TestSuiteV5.MutationScorePolicy(
                0, 2, false, false));

        TestMutationSuiteEvidenceEvaluator.Evaluation result = evaluator.evaluate(suite,
                TestSuiteRunEvidenceV5.BaselineStatus.PASSED, List.of(
                        List.of(observation(suite, 0, 0,
                                        TestRunEvidence.Status.ASSERTION_FAILED),
                                notScheduled(suite, 0, 1, "MUTANT_KILL_SHORT_CIRCUIT")),
                        List.of(observation(suite, 1, 0, TestRunEvidence.Status.PASSED),
                                notScheduled(suite, 1, 1, "SUITE_RUN_LEASE_LOST"))));

        assertThat(result.mutantResults().get(1).status())
                .isEqualTo(TestSuiteRunEvidenceV5.MutantStatus.NOT_SCHEDULED);
        assertThat(result.score().status())
                .isEqualTo(TestSuiteRunEvidenceV5.MutationScoreStatus.INCOMPLETE);
        assertThat(result.score().unclassifiedMutants()).isEqualTo(1);
        assertThat(result.score().denominatorMutants()).isEqualTo(1);
        assertThat(result.score().scoreBasisPoints()).isZero();
        assertThat(result.score().reasons())
                .containsExactly("MUTANT_CLASSIFICATION_INCOMPLETE");
    }

    @Test
    void rejectsChildTargetDriftAsIncompleteInsteadOfAFalseKill() {
        TestSuiteV5 suite = suite(new TestSuiteV5.MutationScorePolicy(
                0, 2, false, false));
        TestSuiteEvidenceAggregator.CaseObservation drifted = observation(
                suite, 0, 0, TestRunEvidence.Status.ASSERTION_FAILED, TARGET);

        TestMutationSuiteEvidenceEvaluator.Evaluation result = evaluator.evaluate(suite,
                TestSuiteRunEvidenceV5.BaselineStatus.PASSED, List.of(
                        List.of(drifted, pending(suite, 0, 1)),
                        List.of(pending(suite, 1, 0), pending(suite, 1, 1))));

        assertThat(result.mutantResults().getFirst().caseResults().getFirst().status())
                .isEqualTo(TestSuiteRunEvidenceV5.MutantCaseStatus.EVIDENCE_INCOMPLETE);
        assertThat(result.mutantResults().getFirst().status())
                .isEqualTo(TestSuiteRunEvidenceV5.MutantStatus.RUNNING);
        assertThat(result.score().killedMutants()).isZero();
    }

    @Test
    void evidenceRecomputesScoreAndRejectsFalseKillOrTamperedVerdict() {
        TestSuiteV5 suite = suite(new TestSuiteV5.MutationScorePolicy(
                5_000, 0, false, false));
        TestMutationSuiteEvidenceEvaluator.Evaluation evaluated = evaluator.evaluate(suite,
                TestSuiteRunEvidenceV5.BaselineStatus.PASSED, List.of(
                        List.of(observation(suite, 0, 0,
                                        TestRunEvidence.Status.ASSERTION_FAILED),
                                notScheduled(suite, 0, 1, "MUTANT_KILL_SHORT_CIRCUIT")),
                        List.of(observation(suite, 1, 0, TestRunEvidence.Status.PASSED),
                                observation(suite, 1, 1, TestRunEvidence.Status.PASSED))));
        List<TestSuiteRunEvidence.CaseResult> baseline = List.of(
                baseline(suite.cases().get(0)), baseline(suite.cases().get(1)));
        TestSuiteRunEvidenceV5 evidence = evidence(suite, baseline, evaluated,
                evaluated.score());

        assertThat(evidence.mutationScore().scoreBasisPoints()).isEqualTo(5_000);
        assertThat(evidence.status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED);

        assertThatThrownBy(() -> new TestSuiteRunEvidenceV5.MutantCaseResult(
                "golden", suite.cases().getFirst().fixtureBundleRef(),
                suite.mutants().getFirst().mutantTargetFingerprint(),
                TestSuiteRunEvidenceV5.MutantCaseStatus.ASSERTION_KILLED,
                "run", fingerprint('1'), TestRunEvidence.Status.EXECUTION_FAILED,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, 1, 0, "EXECUTION_FAILED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("assertion failure");

        TestSuiteRunEvidenceV5.MutationScoreVerdict tampered =
                new TestSuiteRunEvidenceV5.MutationScoreVerdict(
                        evaluated.score().status(), evaluated.score().policy(), 2, 1, 1,
                        0, 0, 2, 5_000, 0, List.of("MUTATION_SCORE_BELOW_THRESHOLD"));
        assertThatThrownBy(() -> evidence(suite, baseline, evaluated, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("derived");
    }

    private static TestSuiteRunEvidenceV5 evidence(
            TestSuiteV5 suite,
            List<TestSuiteRunEvidence.CaseResult> baseline,
            TestMutationSuiteEvidenceEvaluator.Evaluation evaluated,
            TestSuiteRunEvidenceV5.MutationScoreVerdict score) {
        return new TestSuiteRunEvidenceV5("", "suite-run", "request-1",
                TestSuiteRunEvidence.Status.PASSED,
                TestSuiteRunEvidenceV5.EXECUTION_PURPOSE,
                new TestSuiteExecutionRequest.SuiteRef(
                        suite.suiteId(), suite.revision(), fingerprint('9')),
                suite.target(), Instant.EPOCH, Instant.EPOCH.plusSeconds(1), baseline,
                new TestSuiteRunEvidence.CoverageVerdict(
                        TestSuiteRunEvidence.CoverageStatus.SATISFIED, 2, 2,
                        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                        List.of(), List.of(), List.of(), 1, List.of(), List.of(), true),
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(), true,
                        2, 2, true, true, true), suite.evaluationMode(), suite.sourceFormat(),
                suite.baselineSourceFingerprint(), suite.baselineGraphArtifactFingerprint(),
                suite.mutationPlanFingerprint(), suite.mutationPolicy(), suite.sourcePlanStatus(),
                suite.planningGapsAccepted(), suite.planningGaps(), suite.oracleSuiteRef(),
                TestSuiteRunEvidenceV5.BaselineStatus.PASSED, evaluated.mutantResults(), score,
                List.of(), Map.of("suiteFingerprint", fingerprint('9')));
    }

    private static TestSuiteRunEvidence.CaseResult baseline(TestSuite.TestCase testCase) {
        return new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                testCase.fixtureBundleRef(), TestSuiteRunEvidence.CaseStatus.PASSED,
                "baseline-" + testCase.caseId(), TestRunEvidence.Status.PASSED,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, 1, 1, "", "");
    }

    private static TestSuiteEvidenceAggregator.CaseObservation observation(
            TestSuiteV5 suite, int mutantIndex, int caseIndex, TestRunEvidence.Status status) {
        return observation(suite, mutantIndex, caseIndex, status,
                suite.mutants().get(mutantIndex).mutantTargetFingerprint());
    }

    private static TestSuiteEvidenceAggregator.CaseObservation observation(
            TestSuiteV5 suite, int mutantIndex, int caseIndex, TestRunEvidence.Status status,
            String evidenceTarget) {
        TestSuite.TestCase testCase = suite.cases().get(caseIndex);
        TestRunEvidence evidence = childEvidence(testCase.caseId(), status, evidenceTarget);
        int passed = status == TestRunEvidence.Status.PASSED ? 1 : 0;
        TestSuiteRunEvidence.CaseStatus commonStatus = status == TestRunEvidence.Status.PASSED
                ? TestSuiteRunEvidence.CaseStatus.PASSED
                : status == TestRunEvidence.Status.EVIDENCE_INCOMPLETE
                ? TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE
                : TestSuiteRunEvidence.CaseStatus.FAILED;
        return new TestSuiteEvidenceAggregator.CaseObservation(
                new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                        testCase.fixtureBundleRef(), commonStatus, evidence.runId(), status,
                        evidence.evidenceClass(), 1, passed,
                        status == TestRunEvidence.Status.PASSED ? "" : status.name(), ""), evidence);
    }

    private static TestSuiteEvidenceAggregator.CaseObservation pending(
            TestSuiteV5 suite, int mutantIndex, int caseIndex) {
        TestSuite.TestCase testCase = suite.cases().get(caseIndex);
        return new TestSuiteEvidenceAggregator.CaseObservation(
                new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                        testCase.fixtureBundleRef(), TestSuiteRunEvidence.CaseStatus.PENDING,
                        "", null, null, 0, 0, "", ""), null);
    }

    private static TestSuiteEvidenceAggregator.CaseObservation notScheduled(
            TestSuiteV5 suite, int mutantIndex, int caseIndex, String code) {
        TestSuite.TestCase testCase = suite.cases().get(caseIndex);
        return new TestSuiteEvidenceAggregator.CaseObservation(
                new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                        testCase.fixtureBundleRef(), TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED,
                        "", null, null, 0, 0, code, ""), null);
    }

    private static TestRunEvidence childEvidence(
            String caseId, TestRunEvidence.Status status, String target) {
        boolean passed = status == TestRunEvidence.Status.PASSED;
        return new TestRunEvidence("", "run-" + caseId + "-" + status, status,
                TestRunEvidence.EvidenceClass.CERTIFIABLE,
                TestSuiteRunEvidenceV5.EXECUTION_PURPOSE, target, FIXTURE, fingerprint('8'),
                fingerprint('7'), Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                List.of(), List.of(), List.of(),
                List.of(new TestRunEvidence.AssertionResult(
                        "OUTPUT", "/ok", passed, true, passed, passed ? "" : "mismatch")),
                List.of(), Map.of());
    }

    private static TestSuiteV5 suite(TestSuiteV5.MutationScorePolicy scorePolicy) {
        List<TestSuite.TestCase> cases = List.of(
                testCase("golden", TestSuite.CaseType.GOLDEN),
                testCase("negative", TestSuite.CaseType.NEGATIVE));
        List<TestSuiteV5.MutantRef> mutants = java.util.stream.IntStream.rangeClosed(1, 2)
                .mapToObj(index -> new TestSuiteV5.MutantRef(
                        "mutant-%03d".formatted(index),
                        TestSuiteV5.MutationKind.DECISION_CONDITION_NEGATED,
                        "/members/%d/predicate".formatted(index), index, 1,
                        indexedFingerprint(index), indexedFingerprint(100 + index),
                        indexedFingerprint(200 + index),
                        TestSuiteV5.EquivalenceClassification.UNKNOWN)).toList();
        return new TestSuiteV5("", "orders-mutations", 1,
                new TestSuite.Target("GRAPH", "orders", TARGET), "INTERNAL", cases,
                new TestSuite.CoveragePolicy(2, List.of(), List.of(), List.of(), 1, false),
                SemanticCoveragePolicy.empty(), new TestSuite.PromotionPolicy(true, 2, true),
                TestSuiteV5.EvaluationMode.PURE_DSL_MUTATION, TestSuiteV5.SOURCE_FORMAT,
                SOURCE, GRAPH, PLAN,
                new TestSuiteV5.MutationPolicy(TestSuiteV5.PLANNER_VERSION, 2,
                        TestSuiteV5.SOURCE_FORMAT, TestSuiteV5.VERIFICATION_MODE, false, false),
                TestSuiteV5.SourcePlanStatus.GENERATED, false, List.of(), mutants,
                new TestSuiteV5.OracleSuiteRef(
                        "orders-oracle", 7, ORACLE, TestSuite.SCHEMA_VERSION),
                scorePolicy, Map.of());
    }

    private static TestSuite.TestCase testCase(String id, TestSuite.CaseType type) {
        return new TestSuite.TestCase(id, type, Map.of("id", id),
                new TestSuite.FixtureBundleRef("fixture", 1, FIXTURE), List.of(), Map.of());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String indexedFingerprint(int value) {
        return "sha256:" + "%064x".formatted(value);
    }
}
