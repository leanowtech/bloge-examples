package com.leanowtech.bloge.gateway.testing.evidence;

import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure evaluator that projects signed child observations onto V4 property trial lineage.
 *
 * <p>Only {@link TestRunEvidence.Status#ASSERTION_FAILED} is a counterexample. Missing evidence,
 * target failure, control failure, timeout, and fixture failure remain distinct fail-closed states.
 * This evaluator never reads generated input payloads; canonical input fingerprints and complexity
 * come from the immutable suite lineage.</p>
 */
public final class TestPropertySuiteEvidenceEvaluator {

    /**
     * Derives complete typed property results from one observation per immutable suite case.
     *
     * @param suite exact immutable property suite
     * @param observations ordered common child observations
     * @return typed trial closure and property-specific aggregate coverage
     */
    public Evaluation evaluate(
            TestSuiteV4 suite,
            List<TestSuiteEvidenceAggregator.CaseObservation> observations) {
        Objects.requireNonNull(suite, "suite");
        List<TestSuiteEvidenceAggregator.CaseObservation> safe = observations == null
                ? List.of() : List.copyOf(observations);
        if (safe.size() != suite.cases().size()) {
            throw new IllegalArgumentException(
                    "Property evaluation requires one ordered observation per frozen case");
        }
        Map<String, TestSuiteEvidenceAggregator.CaseObservation> indexed = new HashMap<>();
        for (int index = 0; index < suite.cases().size(); index++) {
            TestSuite.TestCase testCase = suite.cases().get(index);
            TestSuiteEvidenceAggregator.CaseObservation observation = safe.get(index);
            if (!testCase.caseId().equals(observation.result().caseId())
                    || indexed.put(testCase.caseId(), observation) != null) {
                throw new IllegalArgumentException(
                        "Property observations must preserve exact suite case order and identity");
            }
        }

        List<TestSuiteRunEvidenceV4.PropertyTrialResult> trials = new ArrayList<>();
        for (TestSuiteV4.PropertyTrialRef trial : suite.propertyTrials()) {
            TestSuiteRunEvidenceV4.PropertyCaseResult root = result(
                    indexed.get(trial.trialId()), TestSuiteRunEvidenceV4.PropertyCaseRole.ROOT,
                    "", 0, trial.inputFingerprint(), trial.complexity());
            List<TestSuiteRunEvidenceV4.PropertyCaseResult> shrinks = new ArrayList<>();
            for (TestSuiteV4.PropertyShrinkRef shrink : trial.shrinkPath()) {
                shrinks.add(result(indexed.get(shrink.caseId()),
                        TestSuiteRunEvidenceV4.PropertyCaseRole.SHRINK,
                        shrink.parentCaseId(), shrink.step(), shrink.inputFingerprint(),
                        shrink.complexity()));
            }
            trials.add(TestSuiteRunEvidenceV4.trialResult(
                    trial.trialId(), root, List.copyOf(shrinks)));
        }
        List<TestSuiteRunEvidenceV4.PropertyTrialResult> frozen = List.copyOf(trials);
        return new Evaluation(frozen, TestSuiteRunEvidenceV4.coverage(frozen));
    }

    /**
     * Converts only selected unfinished property cases into explicit incomplete recovery results.
     *
     * <p>Completed counterexamples and signed child outcomes remain byte-for-byte stable. This is
     * used by lease-expiry reconciliation and never retries a potentially effectful child.</p>
     *
     * @param previous trusted checkpoint trial results
     * @param incompleteCaseIds cases terminalized without child evidence
     * @param diagnosticCode stable recovery diagnostic
     * @return rebuilt trial closure with derived coverage
     */
    public Evaluation markIncomplete(
            List<TestSuiteRunEvidenceV4.PropertyTrialResult> previous,
            Set<String> incompleteCaseIds,
            String diagnosticCode) {
        Set<String> selected = incompleteCaseIds == null ? Set.of() : Set.copyOf(incompleteCaseIds);
        List<TestSuiteRunEvidenceV4.PropertyTrialResult> trials = new ArrayList<>();
        for (TestSuiteRunEvidenceV4.PropertyTrialResult trial : List.copyOf(previous)) {
            TestSuiteRunEvidenceV4.PropertyCaseResult root = incomplete(
                    trial.rootResult(), selected, diagnosticCode);
            List<TestSuiteRunEvidenceV4.PropertyCaseResult> shrinks = trial.shrinkResults().stream()
                    .map(value -> incomplete(value, selected, diagnosticCode)).toList();
            trials.add(TestSuiteRunEvidenceV4.trialResult(trial.trialId(), root, shrinks));
        }
        List<TestSuiteRunEvidenceV4.PropertyTrialResult> frozen = List.copyOf(trials);
        return new Evaluation(frozen, TestSuiteRunEvidenceV4.coverage(frozen));
    }

    private static TestSuiteRunEvidenceV4.PropertyCaseResult result(
            TestSuiteEvidenceAggregator.CaseObservation observation,
            TestSuiteRunEvidenceV4.PropertyCaseRole role,
            String parentCaseId,
            int shrinkStep,
            String inputFingerprint,
            int complexity) {
        if (observation == null || observation.result() == null) {
            throw new IllegalArgumentException("Property lineage references a missing observation");
        }
        TestSuiteRunEvidence.CaseResult common = observation.result();
        TestRunEvidence evidence = observation.evidence();
        TestSuiteRunEvidenceV4.PropertyCaseStatus status;
        if (common.status() == TestSuiteRunEvidence.CaseStatus.PENDING) {
            status = TestSuiteRunEvidenceV4.PropertyCaseStatus.PENDING;
        } else if (common.status() == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED) {
            status = TestSuiteRunEvidenceV4.PropertyCaseStatus.NOT_SCHEDULED;
        } else if (common.status() == TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE
                || evidence == null) {
            status = TestSuiteRunEvidenceV4.PropertyCaseStatus.EVIDENCE_INCOMPLETE;
        } else if (evidence.status() == TestRunEvidence.Status.PASSED) {
            status = TestSuiteRunEvidenceV4.PropertyCaseStatus.SATISFIED;
        } else if (evidence.status() == TestRunEvidence.Status.ASSERTION_FAILED) {
            status = TestSuiteRunEvidenceV4.PropertyCaseStatus.COUNTEREXAMPLE;
        } else if (evidence.status() == TestRunEvidence.Status.EVIDENCE_INCOMPLETE) {
            status = TestSuiteRunEvidenceV4.PropertyCaseStatus.EVIDENCE_INCOMPLETE;
        } else {
            status = TestSuiteRunEvidenceV4.PropertyCaseStatus.EXECUTION_FAILED;
        }
        String diagnostic = status == TestSuiteRunEvidenceV4.PropertyCaseStatus.SATISFIED
                ? "" : common.diagnosticCode();
        return new TestSuiteRunEvidenceV4.PropertyCaseResult(common.caseId(), role, parentCaseId,
                shrinkStep, inputFingerprint, complexity, status, common.runId(),
                common.evidenceStatus(), common.assertionsEvaluated(), common.assertionsPassed(),
                diagnostic);
    }

    private static TestSuiteRunEvidenceV4.PropertyCaseResult incomplete(
            TestSuiteRunEvidenceV4.PropertyCaseResult previous,
            Set<String> selected,
            String diagnosticCode) {
        if (!selected.contains(previous.caseId())) {
            return previous;
        }
        if (previous.status() != TestSuiteRunEvidenceV4.PropertyCaseStatus.PENDING) {
            throw new IllegalArgumentException(
                    "Only pending property cases may be recovery-terminalized");
        }
        return new TestSuiteRunEvidenceV4.PropertyCaseResult(previous.caseId(), previous.role(),
                previous.parentCaseId(), previous.shrinkStep(), previous.inputFingerprint(),
                previous.complexity(),
                TestSuiteRunEvidenceV4.PropertyCaseStatus.EVIDENCE_INCOMPLETE,
                "", null, 0, 0, diagnosticCode);
    }

    /**
     * Derived property execution closure.
     *
     * @param trialResults ordered root/shrink outcomes
     * @param coverage aggregate property verdict
     */
    public record Evaluation(
            List<TestSuiteRunEvidenceV4.PropertyTrialResult> trialResults,
            TestSuiteRunEvidenceV4.PropertyCoverageVerdict coverage
    ) {
        /** Freezes the evaluator result. */
        public Evaluation {
            trialResults = trialResults == null ? List.of() : List.copyOf(trialResults);
            coverage = Objects.requireNonNull(coverage, "coverage");
        }
    }
}
