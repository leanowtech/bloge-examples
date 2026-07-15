package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoverageVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteEvidenceAggregatorTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);
    private final TestSuiteEvidenceAggregator aggregator =
            new TestSuiteEvidenceAggregator(new ObjectMapper());

    @Test
    void certifiableEvidenceSatisfiesEveryTypedSemanticRequirement() {
        TestSuiteEvidenceAggregator.Aggregate result = aggregator.aggregate(suite(allRequirements()),
                List.of(observation(evidence(TestRunEvidence.EvidenceClass.CERTIFIABLE,
                        completeNodes(), completeEdges()))),
                new TestSuiteEvidenceAggregator.TargetState(true, true));

        assertThat(result.semanticCoverage().status())
                .isEqualTo(SemanticCoverageVerdict.Status.SATISFIED);
        assertThat(result.semanticCoverage().observed())
                .extracting(SemanticCoverageVerdict.Observation::requirementId)
                .containsExactly("branch-skipped", "branch-transferred", "compensation",
                        "decision-rule", "fallback", "retry", "timeout");
        assertThat(result.status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED);
        assertThat(result.promotion().status())
                .isEqualTo(TestSuiteRunEvidence.PromotionStatus.ELIGIBLE);
    }

    @Test
    void completeTrustedEvidenceMarksAbsentFactMissingAndBlocksPromotion() {
        SemanticCoveragePolicy.Requirement requirement = new SemanticCoveragePolicy.SiteRequirement(
                "missing-timeout", SemanticCoveragePolicy.Kind.TIMEOUT, "/root/missing#PRIMARY", "");

        TestSuiteEvidenceAggregator.Aggregate result = aggregator.aggregate(suite(List.of(requirement)),
                List.of(observation(evidence(TestRunEvidence.EvidenceClass.CERTIFIABLE,
                        completeNodes(), completeEdges()))),
                new TestSuiteEvidenceAggregator.TargetState(true, true));

        assertThat(result.semanticCoverage().status())
                .isEqualTo(SemanticCoverageVerdict.Status.UNSATISFIED);
        assertThat(result.semanticCoverage().missingRequirementIds())
                .containsExactly("missing-timeout");
        assertThat(result.promotion().reasons()).contains("SEMANTIC_COVERAGE_UNSATISFIED");
        assertThat(result.status()).isEqualTo(TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES);
    }

    @Test
    void sanitizedOrExploratoryFactsAreUnavailableInsteadOfFalselyMissing() {
        SemanticCoveragePolicy.Requirement decision = new SemanticCoveragePolicy.DecisionRuleRequirement(
                "decision", SemanticCoveragePolicy.Kind.DECISION_RULE,
                "/root/decision#PRIMARY", "/decision/rule", "RISK_HIGH");
        List<TestRunEvidence.NodeTrace> sanitized = List.of(node("decision", "/root/decision#PRIMARY",
                "SUCCESS", Map.of("decision", Map.of()), "", List.of()));

        TestSuiteEvidenceAggregator.Aggregate sanitizedResult = aggregator.aggregate(
                suite(List.of(decision)), List.of(observation(evidence(
                        TestRunEvidence.EvidenceClass.CERTIFIABLE, sanitized, List.of()))),
                new TestSuiteEvidenceAggregator.TargetState(true, true));
        TestSuiteEvidenceAggregator.Aggregate exploratoryResult = aggregator.aggregate(
                suite(List.of(decision)), List.of(observation(evidence(
                        TestRunEvidence.EvidenceClass.EXPLORATORY, completeNodes(), completeEdges()))),
                new TestSuiteEvidenceAggregator.TargetState(true, true));

        assertThat(sanitizedResult.semanticCoverage().status())
                .isEqualTo(SemanticCoverageVerdict.Status.INCOMPLETE);
        assertThat(sanitizedResult.semanticCoverage().unavailable().getFirst().reasonCode())
                .isEqualTo("SEMANTIC_DECISION_OUTPUT_UNAVAILABLE");
        assertThat(exploratoryResult.semanticCoverage().unavailable().getFirst().reasonCode())
                .isEqualTo("SEMANTIC_SOURCE_NOT_CERTIFIABLE");
        assertThat(exploratoryResult.promotion().reasons())
                .contains("SEMANTIC_COVERAGE_INCOMPLETE");
    }

    private static List<SemanticCoveragePolicy.Requirement> allRequirements() {
        return List.of(
                new SemanticCoveragePolicy.BranchRequirement("branch-transferred",
                        SemanticCoveragePolicy.Kind.BRANCH_TRANSFERRED,
                        "/root/input#PRIMARY", "/root/decision#PRIMARY"),
                new SemanticCoveragePolicy.BranchRequirement("branch-skipped",
                        SemanticCoveragePolicy.Kind.BRANCH_SKIPPED,
                        "/root/decision#PRIMARY", "/root/manual#PRIMARY"),
                new SemanticCoveragePolicy.DecisionRuleRequirement("decision-rule",
                        SemanticCoveragePolicy.Kind.DECISION_RULE, "/root/decision#PRIMARY",
                        "/decision/rule", "RISK_HIGH"),
                new SemanticCoveragePolicy.RetryRequirement("retry", SemanticCoveragePolicy.Kind.RETRY,
                        "/root/decision#PRIMARY", 2),
                new SemanticCoveragePolicy.SiteRequirement("fallback", SemanticCoveragePolicy.Kind.FALLBACK,
                        "/root/fallback#PRIMARY", ""),
                new SemanticCoveragePolicy.SiteRequirement("timeout", SemanticCoveragePolicy.Kind.TIMEOUT,
                        "/root/remote#PRIMARY", "UPSTREAM_TIMEOUT"),
                new SemanticCoveragePolicy.SiteRequirement("compensation",
                        SemanticCoveragePolicy.Kind.COMPENSATION, "/root/refund#COMPENSATION", ""));
    }

    private static List<TestRunEvidence.NodeTrace> completeNodes() {
        return List.of(
                node("decision", "/root/decision#PRIMARY", "SUCCESS",
                        Map.of("decision", Map.of("rule", "RISK_HIGH")), "", List.of(
                                attempt(1, "FAILED", "TRANSIENT"), attempt(2, "SUCCESS", ""))),
                node("fallback", "/root/fallback#PRIMARY", "SUCCESS", Map.of("source", "fallback"),
                        "", List.of(attempt(1, "FAILED", "PRIMARY_FAILED"))),
                node("remote", "/root/remote#PRIMARY", "TIMEOUT", null, "UPSTREAM_TIMEOUT",
                        List.of(attempt(1, "TIMEOUT", "UPSTREAM_TIMEOUT"))),
                node("refund", "/root/refund#COMPENSATION", "SUCCESS", Map.of("refunded", true),
                        "", List.of(attempt(1, "SUCCESS", ""))));
    }

    private static List<TestRunEvidence.EdgeTrace> completeEdges() {
        return List.of(
                new TestRunEvidence.EdgeTrace("input->decision", "TRANSFERRED", Map.of(), "/root",
                        "", 1, "/root/input#PRIMARY", "/root/decision#PRIMARY"),
                new TestRunEvidence.EdgeTrace("decision?->manual", "SKIPPED", Map.of(), "/root",
                        "", 1, "/root/decision#PRIMARY", "/root/manual#PRIMARY"));
    }

    private static TestRunEvidence.NodeTrace node(String nodeId, String site, String status,
                                                  Object output, String errorCode,
                                                  List<TestRunEvidence.AttemptTrace> attempts) {
        return new TestRunEvidence.NodeTrace(nodeId, "operator." + nodeId, status, "REAL",
                Map.of(), output, errorCode, 1, site, "/root", "", 1, 1, attempts);
    }

    private static TestRunEvidence.AttemptTrace attempt(int attempt, String status, String errorCode) {
        return new TestRunEvidence.AttemptTrace(attempt, status, "REAL", Map.of(), Map.of(),
                errorCode, 1);
    }

    private static TestRunEvidence evidence(TestRunEvidence.EvidenceClass evidenceClass,
                                            List<TestRunEvidence.NodeTrace> nodes,
                                            List<TestRunEvidence.EdgeTrace> edges) {
        return new TestRunEvidence("", "child-run", TestRunEvidence.Status.PASSED, evidenceClass,
                "GRAPH_CONTRACT_TEST", FINGERPRINT, FINGERPRINT, FINGERPRINT,
                Instant.parse("2026-07-16T00:00:00Z"), Instant.parse("2026-07-16T00:00:01Z"),
                nodes, edges, List.of(), List.of(), List.of(), Map.of());
    }

    private static TestSuiteEvidenceAggregator.CaseObservation observation(TestRunEvidence evidence) {
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef("fixture", 1, FINGERPRINT);
        TestSuiteRunEvidence.CaseResult result = new TestSuiteRunEvidence.CaseResult(
                "golden", TestSuite.CaseType.GOLDEN, fixture, TestSuiteRunEvidence.CaseStatus.PASSED,
                evidence.runId(), evidence.status(), evidence.evidenceClass(), 1, 1, "", "");
        return new TestSuiteEvidenceAggregator.CaseObservation(result, evidence);
    }

    private static TestSuiteV2 suite(List<SemanticCoveragePolicy.Requirement> requirements) {
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef("fixture", 1, FINGERPRINT);
        TestSuite.TestCase testCase = new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                Map.of(), fixture, List.of(), Map.of());
        return new TestSuiteV2("", "suite", 1, new TestSuite.Target("GRAPH", "graph", FINGERPRINT),
                "INTERNAL", List.of(testCase), new TestSuite.CoveragePolicy(1,
                List.of(TestSuite.CaseType.GOLDEN), List.of(), List.of(), 1, false),
                new SemanticCoveragePolicy(requirements), new TestSuite.PromotionPolicy(true, 1, true),
                Map.of());
    }
}
