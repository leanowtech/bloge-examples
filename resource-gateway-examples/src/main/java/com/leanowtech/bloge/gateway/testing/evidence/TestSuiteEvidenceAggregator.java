package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoverageVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure server-side evaluator for suite status, semantic coverage, and promotion eligibility.
 *
 * <p>Only child evidence facts are accepted as observations. Suite declarations provide required
 * coordinates but can never mark themselves covered. This keeps coverage and promotion decisions
 * independent from author-controlled metadata.</p>
 */
public final class TestSuiteEvidenceAggregator {
    private final ObjectMapper objectMapper;

    /** Creates an evaluator with a plain JSON mapper for direct unit use. */
    public TestSuiteEvidenceAggregator() {
        this(new ObjectMapper());
    }

    /** @param objectMapper mapper used for sanitized decision-output evaluation */
    public TestSuiteEvidenceAggregator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Evaluates one complete or partial ordered suite execution.
     *
     * @param suite immutable policy and expected case inventory
     * @param observations one observation for every suite case in suite order
     * @param targetState execution-time target fingerprint and certification state
     * @return aggregate status and fail-closed verdicts
     */
    public Aggregate aggregate(TestSuiteProtocol suite, List<CaseObservation> observations,
                               TargetState targetState) {
        List<CaseObservation> safe = observations == null ? List.of() : List.copyOf(observations);
        TestSuiteRunEvidence.CoverageVerdict coverage = coverage(suite, safe);
        SemanticCoverageVerdict semanticCoverage = semanticCoverage(suite, safe);
        TestSuiteRunEvidence.Status status = aggregateStatus(safe, coverage, semanticCoverage);
        TestSuiteRunEvidence.PromotionVerdict promotion = promotion(
                suite, safe, coverage, semanticCoverage,
                targetState == null ? new TargetState(false, false) : targetState);
        return new Aggregate(status, coverage, semanticCoverage, promotion);
    }

    private static TestSuiteRunEvidence.Status aggregateStatus(
            List<CaseObservation> observations,
            TestSuiteRunEvidence.CoverageVerdict coverage,
            SemanticCoverageVerdict semanticCoverage) {
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
        boolean semanticSatisfied = semanticCoverage.status()
                == SemanticCoverageVerdict.Status.NOT_EVALUATED
                || semanticCoverage.status() == SemanticCoverageVerdict.Status.SATISFIED;
        return coverage.status() == TestSuiteRunEvidence.CoverageStatus.SATISFIED
                && semanticSatisfied
                ? TestSuiteRunEvidence.Status.PASSED
                : TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES;
    }

    private static TestSuiteRunEvidence.CoverageVerdict coverage(
            TestSuiteProtocol suite, List<CaseObservation> observations) {
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

    private SemanticCoverageVerdict semanticCoverage(
            TestSuiteProtocol suite, List<CaseObservation> observations) {
        if (!(suite instanceof TestSuiteV2 semanticSuite)) {
            return SemanticCoverageVerdict.notEvaluated(List.of());
        }
        List<SemanticCoveragePolicy.Requirement> requirements =
                semanticSuite.semanticCoveragePolicy().requirements();
        List<SemanticCoverageVerdict.Observation> observed = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<SemanticCoverageVerdict.Unavailable> unavailable = new ArrayList<>();
        boolean incomplete = observations.size() != suite.cases().size()
                || observations.stream().anyMatch(item -> item.evidence() == null
                || item.result().status() == TestSuiteRunEvidence.CaseStatus.PENDING
                || item.result().status() == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED
                || item.result().status() == TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE);

        for (SemanticCoveragePolicy.Requirement requirement : requirements) {
            RequirementEvaluation evaluation = evaluate(requirement, observations);
            if (!evaluation.caseIds().isEmpty()) {
                observed.add(new SemanticCoverageVerdict.Observation(requirement.requirementId(),
                        requirement.kind(), evaluation.caseIds()));
            } else if (incomplete) {
                unavailable.add(new SemanticCoverageVerdict.Unavailable(
                        requirement.requirementId(), "SEMANTIC_EVIDENCE_INCOMPLETE"));
            } else if (!evaluation.unavailableReason().isBlank()) {
                unavailable.add(new SemanticCoverageVerdict.Unavailable(
                        requirement.requirementId(), evaluation.unavailableReason()));
            } else {
                missing.add(requirement.requirementId());
            }
        }
        SemanticCoverageVerdict.Status status = incomplete || !unavailable.isEmpty()
                ? SemanticCoverageVerdict.Status.INCOMPLETE
                : !missing.isEmpty() ? SemanticCoverageVerdict.Status.UNSATISFIED
                : SemanticCoverageVerdict.Status.SATISFIED;
        return new SemanticCoverageVerdict(status, requirements, observed, missing, unavailable);
    }

    private RequirementEvaluation evaluate(SemanticCoveragePolicy.Requirement requirement,
                                           List<CaseObservation> observations) {
        List<String> observedCases = new ArrayList<>();
        String trustedUnavailable = "";
        boolean certifiableEvidenceSeen = false;
        boolean nonCertifiableEvidenceSeen = false;
        for (CaseObservation observation : observations) {
            TestRunEvidence evidence = observation.evidence();
            if (evidence == null) {
                continue;
            }
            if (evidence.evidenceClass() != TestRunEvidence.EvidenceClass.CERTIFIABLE) {
                nonCertifiableEvidenceSeen = true;
                continue;
            }
            certifiableEvidenceSeen = true;
            FactMatch match = matches(requirement, evidence);
            if (match.observed()) {
                observedCases.add(observation.result().caseId());
            } else if (!match.unavailableReason().isBlank()) {
                trustedUnavailable = match.unavailableReason();
            }
        }
        if (!certifiableEvidenceSeen && nonCertifiableEvidenceSeen) {
            trustedUnavailable = "SEMANTIC_SOURCE_NOT_CERTIFIABLE";
        }
        return new RequirementEvaluation(List.copyOf(observedCases), trustedUnavailable);
    }

    private FactMatch matches(SemanticCoveragePolicy.Requirement requirement,
                              TestRunEvidence evidence) {
        if (requirement instanceof SemanticCoveragePolicy.BranchRequirement branch) {
            boolean legacyCoordinate = evidence.edgeTrace().stream().anyMatch(edge ->
                    edge.fromInvocationSiteId().isBlank() || edge.toInvocationSiteId().isBlank());
            String requiredStatus = branch.kind() == SemanticCoveragePolicy.Kind.BRANCH_TRANSFERRED
                    ? "TRANSFERRED" : "SKIPPED";
            boolean matched = evidence.edgeTrace().stream().anyMatch(edge ->
                    requiredStatus.equals(edge.status())
                            && branch.fromInvocationSiteId().equals(edge.fromInvocationSiteId())
                            && branch.toInvocationSiteId().equals(edge.toInvocationSiteId()));
            return new FactMatch(matched, !matched && legacyCoordinate
                    ? "SEMANTIC_EDGE_COORDINATE_UNAVAILABLE" : "");
        }
        if (requirement instanceof SemanticCoveragePolicy.DecisionRuleRequirement decision) {
            List<TestRunEvidence.NodeTrace> nodes = nodes(evidence, decision.invocationSiteId());
            boolean unavailable = false;
            for (TestRunEvidence.NodeTrace node : nodes) {
                JsonNode output = objectMapper.valueToTree(node.output());
                JsonNode actual = output.at(decision.outputJsonPointer());
                if (actual.isMissingNode()) {
                    unavailable = true;
                    continue;
                }
                if (actual.equals(objectMapper.valueToTree(decision.expectedScalar()))) {
                    return new FactMatch(true, "");
                }
            }
            return new FactMatch(false, unavailable
                    ? "SEMANTIC_DECISION_OUTPUT_UNAVAILABLE" : "");
        }
        if (requirement instanceof SemanticCoveragePolicy.RetryRequirement retry) {
            List<TestRunEvidence.NodeTrace> nodes = nodes(evidence, retry.invocationSiteId());
            boolean matched = nodes.stream().anyMatch(node ->
                    node.attempts().size() >= retry.minimumAttempts());
            boolean legacy = nodes.stream().anyMatch(node -> node.attempts().isEmpty()
                    && node.occurrence() == 0);
            return new FactMatch(matched, !matched && legacy
                    ? "SEMANTIC_ATTEMPT_TRACE_UNAVAILABLE" : "");
        }
        SemanticCoveragePolicy.SiteRequirement site =
                (SemanticCoveragePolicy.SiteRequirement) requirement;
        List<TestRunEvidence.NodeTrace> nodes = nodes(evidence, site.invocationSiteId());
        return switch (site.kind()) {
            case FALLBACK -> new FactMatch(nodes.stream().anyMatch(node ->
                    "SUCCESS".equals(node.status()) && !node.attempts().isEmpty()
                            && List.of("FAILED", "TIMEOUT").contains(
                            node.attempts().getLast().status())), "");
            case TIMEOUT -> new FactMatch(nodes.stream().anyMatch(node -> timeout(node, site.errorCode())), "");
            case COMPENSATION -> new FactMatch(nodes.stream().anyMatch(node ->
                    !List.of("SKIPPED", "CANCELLED", "NOT_INVOKED").contains(node.status())), "");
            default -> throw new IllegalStateException("Unsupported site semantic kind");
        };
    }

    private static List<TestRunEvidence.NodeTrace> nodes(TestRunEvidence evidence,
                                                         String invocationSiteId) {
        return evidence.nodeTrace().stream()
                .filter(node -> invocationSiteId.equals(node.invocationSiteId()))
                .toList();
    }

    private static boolean timeout(TestRunEvidence.NodeTrace node, String errorCode) {
        if ("TIMEOUT".equals(node.status())
                && (errorCode.isBlank() || errorCode.equals(node.errorCode()))) {
            return true;
        }
        return node.attempts().stream().anyMatch(attempt -> "TIMEOUT".equals(attempt.status())
                && (errorCode.isBlank() || errorCode.equals(attempt.errorCode())));
    }

    private static TestSuiteRunEvidence.PromotionVerdict promotion(
            TestSuiteProtocol suite, List<CaseObservation> observations,
            TestSuiteRunEvidence.CoverageVerdict coverage,
            SemanticCoverageVerdict semanticCoverage, TargetState targetState) {
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
        if (semanticCoverage.status() == SemanticCoverageVerdict.Status.UNSATISFIED) {
            reasons.add("SEMANTIC_COVERAGE_UNSATISFIED");
        } else if (semanticCoverage.status() == SemanticCoverageVerdict.Status.INCOMPLETE) {
            reasons.add("SEMANTIC_COVERAGE_INCOMPLETE");
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
            SemanticCoverageVerdict semanticCoverage,
            TestSuiteRunEvidence.PromotionVerdict promotion
    ) {
    }

    private record FactMatch(boolean observed, String unavailableReason) {
    }

    private record RequirementEvaluation(List<String> caseIds, String unavailableReason) {
    }
}
