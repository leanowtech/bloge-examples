package com.leanowtech.bloge.gateway.testing.evidence;

import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pure server-side evaluator for suite status, semantic coverage, and promotion eligibility.
 *
 * <p>Only child evidence facts are accepted as observations. Suite declarations provide required
 * coordinates but can never mark themselves covered. This keeps coverage and promotion decisions
 * independent from author-controlled metadata.</p>
 */
public final class TestSuiteEvidenceAggregator {

    /**
     * Evaluates one complete or partial ordered suite execution.
     *
     * @param suite immutable policy and expected case inventory
     * @param observations one observation for every suite case in suite order
     * @param targetState execution-time target fingerprint and certification state
     * @return aggregate status and fail-closed verdicts
     */
    public Aggregate aggregate(TestSuite suite, List<CaseObservation> observations,
                               TargetState targetState) {
        List<CaseObservation> safe = observations == null ? List.of() : List.copyOf(observations);
        TestSuiteRunEvidence.CoverageVerdict coverage = coverage(suite, safe);
        TestSuiteRunEvidence.Status status = aggregateStatus(safe, coverage);
        TestSuiteRunEvidence.PromotionVerdict promotion = promotion(
                suite, safe, coverage, targetState == null ? new TargetState(false, false) : targetState);
        return new Aggregate(status, coverage, promotion);
    }

    private static TestSuiteRunEvidence.Status aggregateStatus(
            List<CaseObservation> observations,
            TestSuiteRunEvidence.CoverageVerdict coverage) {
        if (observations.stream().anyMatch(item -> item.result().status()
                == TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE)) {
            return TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE;
        }
        if (observations.stream().anyMatch(item -> item.result().status()
                == TestSuiteRunEvidence.CaseStatus.PENDING
                || item.result().status() == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED)) {
            return TestSuiteRunEvidence.Status.PARTIAL;
        }
        if (observations.stream().anyMatch(item -> item.result().status()
                == TestSuiteRunEvidence.CaseStatus.FAILED)) {
            return TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES;
        }
        return coverage.status() == TestSuiteRunEvidence.CoverageStatus.SATISFIED
                ? TestSuiteRunEvidence.Status.PASSED
                : TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES;
    }

    private static TestSuiteRunEvidence.CoverageVerdict coverage(
            TestSuite suite, List<CaseObservation> observations) {
        TestSuite.CoveragePolicy policy = suite.coveragePolicy();
        Set<TestSuite.CaseType> observedTypes = new LinkedHashSet<>();
        Set<String> observedSites = new LinkedHashSet<>();
        Set<TestSuite.EdgeTransferRef> observedEdges = new LinkedHashSet<>();
        Set<String> assertionViolations = new LinkedHashSet<>();
        Set<String> consumptionViolations = new LinkedHashSet<>();
        int completed = 0;
        boolean incompleteEvidence = false;

        for (CaseObservation observation : observations) {
            TestSuiteRunEvidence.CaseResult result = observation.result();
            TestRunEvidence evidence = observation.evidence();
            if (evidence == null) {
                incompleteEvidence = true;
            } else {
                completed++;
                observedTypes.add(result.caseType());
                evidence.nodeTrace().stream()
                        .filter(node -> !node.invocationSiteId().isBlank())
                        .filter(node -> !("SKIPPED".equals(node.status())
                                || "CANCELLED".equals(node.status())
                                || "NOT_INVOKED".equals(node.status())))
                        .map(TestRunEvidence.NodeTrace::invocationSiteId)
                        .forEach(observedSites::add);
                evidence.edgeTrace().stream()
                        .filter(edge -> "TRANSFERRED".equals(edge.status()))
                        .filter(edge -> !edge.fromInvocationSiteId().isBlank()
                                && !edge.toInvocationSiteId().isBlank())
                        .map(edge -> new TestSuite.EdgeTransferRef(
                                edge.fromInvocationSiteId(), edge.toInvocationSiteId()))
                        .forEach(observedEdges::add);
                if (policy.requireAllFixtureRulesConsumed() && evidence.fixtureConsumptions().stream()
                        .anyMatch(item -> item.required() && !"SATISFIED".equals(item.status()))) {
                    consumptionViolations.add(result.caseId());
                }
            }
            if (result.assertionsEvaluated() < policy.minimumAssertionsPerCase()) {
                assertionViolations.add(result.caseId());
            }
            if (result.status() == TestSuiteRunEvidence.CaseStatus.PENDING
                    || result.status() == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED
                    || result.status() == TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE) {
                incompleteEvidence = true;
            }
        }

        Set<TestSuite.CaseType> missingTypes = difference(policy.requiredCaseTypes(), observedTypes);
        Set<String> missingSites = difference(policy.requiredInvocationSiteIds(), observedSites);
        Set<TestSuite.EdgeTransferRef> missingEdges = difference(policy.requiredEdgeTransfers(), observedEdges);
        boolean allCasesCompleted = observations.size() == suite.cases().size()
                && observations.stream().noneMatch(item -> item.result().status()
                == TestSuiteRunEvidence.CaseStatus.PENDING
                || item.result().status() == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED);
        boolean satisfied = completed >= policy.minimumCases() && missingTypes.isEmpty()
                && missingSites.isEmpty() && missingEdges.isEmpty() && assertionViolations.isEmpty()
                && consumptionViolations.isEmpty();
        TestSuiteRunEvidence.CoverageStatus coverageStatus = incompleteEvidence
                ? TestSuiteRunEvidence.CoverageStatus.INCOMPLETE
                : satisfied ? TestSuiteRunEvidence.CoverageStatus.SATISFIED
                : TestSuiteRunEvidence.CoverageStatus.UNSATISFIED;
        return new TestSuiteRunEvidence.CoverageVerdict(coverageStatus, policy.minimumCases(), completed,
                policy.requiredCaseTypes(), List.copyOf(observedTypes), List.copyOf(missingTypes),
                policy.requiredInvocationSiteIds(), List.copyOf(observedSites), List.copyOf(missingSites),
                policy.requiredEdgeTransfers(), List.copyOf(observedEdges), List.copyOf(missingEdges),
                policy.minimumAssertionsPerCase(), List.copyOf(assertionViolations),
                List.copyOf(consumptionViolations), allCasesCompleted);
    }

    private static TestSuiteRunEvidence.PromotionVerdict promotion(
            TestSuite suite, List<CaseObservation> observations,
            TestSuiteRunEvidence.CoverageVerdict coverage, TargetState targetState) {
        TestSuite.PromotionPolicy policy = suite.promotionPolicy();
        boolean allCompleted = coverage.allCasesCompleted();
        boolean allPassed = allCompleted && observations.stream().allMatch(item ->
                item.result().status() == TestSuiteRunEvidence.CaseStatus.PASSED);
        int certifiableCases = (int) observations.stream()
                .filter(item -> item.evidence() != null)
                .filter(item -> item.evidence().evidenceClass()
                        == TestRunEvidence.EvidenceClass.CERTIFIABLE)
                .count();
        boolean coverageSatisfied = coverage.status() == TestSuiteRunEvidence.CoverageStatus.SATISFIED;
        List<String> reasons = new ArrayList<>();
        if (!targetState.fingerprintCurrent()) {
            reasons.add("TARGET_FINGERPRINT_STALE");
        }
        if (!allCompleted) {
            reasons.add("SUITE_RUN_INCOMPLETE");
        }
        if (observations.stream().anyMatch(item -> item.result().status()
                == TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE)) {
            reasons.add("EVIDENCE_INCOMPLETE");
        }
        if (!coverageSatisfied) {
            reasons.add(coverage.status() == TestSuiteRunEvidence.CoverageStatus.INCOMPLETE
                    ? "COVERAGE_INCOMPLETE" : "COVERAGE_UNSATISFIED");
        }
        if (policy.requireAllCasesPassed() && !allPassed) {
            reasons.add("CASE_FAILURES_PRESENT");
        }
        if (certifiableCases < policy.minimumCertifiableCases()) {
            reasons.add("CERTIFIABLE_CASE_MINIMUM_UNMET");
        }
        if (policy.requireTargetCertificationEligible() && !targetState.certificationEligible()) {
            reasons.add("TARGET_NOT_CERTIFICATION_ELIGIBLE");
        }
        return new TestSuiteRunEvidence.PromotionVerdict(
                reasons.isEmpty() ? TestSuiteRunEvidence.PromotionStatus.ELIGIBLE
                        : TestSuiteRunEvidence.PromotionStatus.BLOCKED,
                reasons, allPassed, certifiableCases, policy.minimumCertifiableCases(),
                targetState.certificationEligible(), coverageSatisfied, allCompleted);
    }

    private static <T> Set<T> difference(List<T> required, Set<T> observed) {
        Set<T> missing = new LinkedHashSet<>(required);
        missing.removeAll(observed);
        return missing;
    }

    /** Child case result plus full sanitized evidence used only for aggregate evaluation. */
    public record CaseObservation(TestSuiteRunEvidence.CaseResult result, TestRunEvidence evidence) {
    }

    /** Execution-time state of the exact target dependency. */
    public record TargetState(boolean fingerprintCurrent, boolean certificationEligible) {
    }

    /** Derived aggregate fields applied to terminal suite evidence. */
    public record Aggregate(
            TestSuiteRunEvidence.Status status,
            TestSuiteRunEvidence.CoverageVerdict coverage,
            TestSuiteRunEvidence.PromotionVerdict promotion
    ) {
    }
}
