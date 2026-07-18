package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure evaluator that classifies an exact V5 mutant-case execution matrix.
 *
 * <p>The evaluator accepts only one ordered common child observation per frozen mutant-case
 * coordinate. It verifies run, target, fixture, status, and assertion-counter identity before
 * retaining a child reference. Only assertion failure maps to a kill; every other non-pass is
 * execution failure or evidence incomplete. No child input or output payload is copied into the
 * aggregate.</p>
 */
public final class TestMutationSuiteEvidenceEvaluator {
    private final ObjectMapper objectMapper;

    /** @param objectMapper canonical child-evidence fingerprint mapper */
    public TestMutationSuiteEvidenceEvaluator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Derives the complete typed mutant closure and score from verified child observations.
     *
     * @param suite exact immutable mutation suite
     * @param baselineStatus server-derived unmodified-graph oracle status
     * @param observations one mutant row per suite mutant and one case column per oracle case
     * @return typed mutant results and deterministic score verdict
     */
    public Evaluation evaluate(
            TestSuiteV5 suite,
            TestSuiteRunEvidenceV5.BaselineStatus baselineStatus,
            List<List<TestSuiteEvidenceAggregator.CaseObservation>> observations) {
        Objects.requireNonNull(suite, "suite");
        Objects.requireNonNull(baselineStatus, "baselineStatus");
        List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix = immutableMatrix(observations);
        if (matrix.size() != suite.mutants().size()) {
            throw new IllegalArgumentException(
                    "Mutation evaluation requires one observation row per frozen mutant");
        }
        List<TestSuiteRunEvidenceV5.MutantResult> results = new ArrayList<>();
        for (int mutantIndex = 0; mutantIndex < suite.mutants().size(); mutantIndex++) {
            TestSuiteV5.MutantRef mutant = suite.mutants().get(mutantIndex);
            List<TestSuiteEvidenceAggregator.CaseObservation> row = matrix.get(mutantIndex);
            if (row.size() != suite.cases().size()) {
                throw new IllegalArgumentException(
                        "Mutation evaluation requires one observation per frozen oracle case");
            }
            List<TestSuiteRunEvidenceV5.MutantCaseResult> cases = new ArrayList<>();
            for (int caseIndex = 0; caseIndex < suite.cases().size(); caseIndex++) {
                cases.add(result(suite.cases().get(caseIndex), mutant, row.get(caseIndex)));
            }
            List<TestSuiteRunEvidenceV5.MutantCaseResult> frozenCases = List.copyOf(cases);
            results.add(new TestSuiteRunEvidenceV5.MutantResult(mutant,
                    TestSuiteRunEvidenceV5.classify(frozenCases), frozenCases,
                    frozenCases.stream().filter(value -> value.status()
                            == TestSuiteRunEvidenceV5.MutantCaseStatus.ASSERTION_KILLED)
                            .map(TestSuiteRunEvidenceV5.MutantCaseResult::caseId).toList()));
        }
        List<TestSuiteRunEvidenceV5.MutantResult> frozen = List.copyOf(results);
        return new Evaluation(frozen,
                TestSuiteRunEvidenceV5.score(baselineStatus, frozen, suite.scorePolicy()));
    }

    private TestSuiteRunEvidenceV5.MutantCaseResult result(
            TestSuite.TestCase testCase,
            TestSuiteV5.MutantRef mutant,
            TestSuiteEvidenceAggregator.CaseObservation observation) {
        if (observation == null || observation.result() == null) {
            throw new IllegalArgumentException("Mutation matrix contains a missing observation");
        }
        TestSuiteRunEvidence.CaseResult common = observation.result();
        TestRunEvidence evidence = observation.evidence();
        if (!testCase.caseId().equals(common.caseId())
                || !Objects.equals(testCase.fixtureBundleRef(), common.fixtureBundleRef())) {
            throw new IllegalArgumentException(
                    "Mutation observations must preserve exact oracle case identity and order");
        }

        if (common.status() == TestSuiteRunEvidence.CaseStatus.PENDING) {
            return withoutChild(testCase, mutant,
                    TestSuiteRunEvidenceV5.MutantCaseStatus.PENDING, "");
        }
        if (common.status() == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED) {
            return withoutChild(testCase, mutant,
                    TestSuiteRunEvidenceV5.MutantCaseStatus.NOT_SCHEDULED,
                    code(common.diagnosticCode(), "MUTATION_CASE_NOT_SCHEDULED"));
        }
        if (!validIdentity(testCase, mutant, common, evidence)) {
            return withoutChild(testCase, mutant,
                    TestSuiteRunEvidenceV5.MutantCaseStatus.EVIDENCE_INCOMPLETE,
                    "MUTATION_CHILD_EVIDENCE_IDENTITY_INVALID");
        }

        TestSuiteRunEvidenceV5.MutantCaseStatus status;
        if (common.status() == TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE
                || evidence.status() == TestRunEvidence.Status.EVIDENCE_INCOMPLETE) {
            status = TestSuiteRunEvidenceV5.MutantCaseStatus.EVIDENCE_INCOMPLETE;
        } else if (evidence.status() == TestRunEvidence.Status.PASSED) {
            status = TestSuiteRunEvidenceV5.MutantCaseStatus.SURVIVED;
        } else if (evidence.status() == TestRunEvidence.Status.ASSERTION_FAILED) {
            status = TestSuiteRunEvidenceV5.MutantCaseStatus.ASSERTION_KILLED;
        } else {
            status = TestSuiteRunEvidenceV5.MutantCaseStatus.EXECUTION_FAILED;
        }
        String diagnostic = status == TestSuiteRunEvidenceV5.MutantCaseStatus.SURVIVED
                ? "" : code(common.diagnosticCode(), evidence.status().name());
        return new TestSuiteRunEvidenceV5.MutantCaseResult(testCase.caseId(),
                testCase.fixtureBundleRef(), mutant.mutantTargetFingerprint(), status,
                common.runId(), ProtocolFingerprint.of(objectMapper, evidence),
                evidence.status(), evidence.evidenceClass(), common.assertionsEvaluated(),
                common.assertionsPassed(), diagnostic);
    }

    private static boolean validIdentity(
            TestSuite.TestCase testCase,
            TestSuiteV5.MutantRef mutant,
            TestSuiteRunEvidence.CaseResult common,
            TestRunEvidence evidence) {
        if (evidence == null || common.runId().isBlank()
                || !common.runId().equals(evidence.runId())
                || common.evidenceStatus() != evidence.status()
                || common.evidenceClass() != evidence.evidenceClass()
                || !mutant.mutantTargetFingerprint().equals(evidence.targetFingerprint())
                || !testCase.fixtureBundleRef().fingerprint()
                .equals(evidence.fixtureBundleFingerprint())) {
            return false;
        }
        int assertions = evidence.assertionResults().size();
        int passed = (int) evidence.assertionResults().stream()
                .filter(TestRunEvidence.AssertionResult::passed).count();
        return common.assertionsEvaluated() == assertions && common.assertionsPassed() == passed;
    }

    private static TestSuiteRunEvidenceV5.MutantCaseResult withoutChild(
            TestSuite.TestCase testCase,
            TestSuiteV5.MutantRef mutant,
            TestSuiteRunEvidenceV5.MutantCaseStatus status,
            String diagnostic) {
        return new TestSuiteRunEvidenceV5.MutantCaseResult(testCase.caseId(),
                testCase.fixtureBundleRef(), mutant.mutantTargetFingerprint(), status,
                "", "", null, null, 0, 0, diagnostic);
    }

    private static List<List<TestSuiteEvidenceAggregator.CaseObservation>> immutableMatrix(
            List<List<TestSuiteEvidenceAggregator.CaseObservation>> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(row -> row == null ? List
                .<TestSuiteEvidenceAggregator.CaseObservation>of() : List.copyOf(row)).toList();
    }

    private static String code(String candidate, String fallback) {
        String safe = candidate == null ? "" : candidate.trim();
        return safe.isBlank() ? fallback : safe;
    }

    /**
     * Pure evaluator output.
     *
     * @param mutantResults complete ordered mutant closure
     * @param score deterministic immutable-policy verdict
     */
    public record Evaluation(
            List<TestSuiteRunEvidenceV5.MutantResult> mutantResults,
            TestSuiteRunEvidenceV5.MutationScoreVerdict score
    ) {
        /** Freezes the derived execution result. */
        public Evaluation {
            mutantResults = mutantResults == null ? List.of() : List.copyOf(mutantResults);
            score = Objects.requireNonNull(score, "score");
        }
    }
}
