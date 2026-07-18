package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityStatisticalPolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityEvidenceEvaluatorTest {
    private static final String TARGET = fingerprint('a');
    private static final String SUITE_FINGERPRINT = fingerprint('b');
    private static final String FIXTURE = fingerprint('c');
    private static final String PLAN = fingerprint('d');
    private static final String STABILITY_RUN = "stability-" + "e".repeat(64);
    private static final Instant START = Instant.parse("2026-07-18T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final TestSuiteStabilityEvidenceEvaluator evaluator =
            new TestSuiteStabilityEvidenceEvaluator(mapper);
    private final TestSuite suite = suite();
    private final TestSuiteExecutionRequest.SuiteRef suiteRef =
            new TestSuiteExecutionRequest.SuiteRef("orders-suite", 7, SUITE_FINGERPRINT);

    @Test
    void provesStableOnlyWhenEveryVerifiedSemanticOutcomeIsIdentical() {
        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3, List.of(
                        attempt(1, Map.of("golden", outcome(TestRunEvidence.Status.PASSED, "same"),
                                "negative", outcome(TestRunEvidence.Status.PASSED, "same-negative"))),
                        attempt(2, Map.of("golden", outcome(TestRunEvidence.Status.PASSED, "same"),
                                "negative", outcome(TestRunEvidence.Status.PASSED, "same-negative"))),
                        attempt(3, Map.of("golden", outcome(TestRunEvidence.Status.PASSED, "same"),
                                "negative", outcome(TestRunEvidence.Status.PASSED, "same-negative")))),
                Map.of("pipeline", "pr"));

        assertThat(evidence.status()).isEqualTo(TestSuiteStabilityEvidence.Status.STABLE);
        assertThat(evidence.caseResults())
                .extracting(TestSuiteStabilityEvidence.CaseStabilityResult::status)
                .containsExactly(TestSuiteStabilityEvidence.CaseStatus.STABLE_PASS,
                        TestSuiteStabilityEvidence.CaseStatus.STABLE_PASS);
        assertThat(evidence.promotion().status())
                .isEqualTo(TestSuiteStabilityEvidence.PromotionStatus.ELIGIBLE);
        assertThat(evidence.promotion().allAttemptsVerified()).isTrue();
        assertThat(evidence.promotion().allSourceSuitesPromotionEligible()).isTrue();
        assertThat(evidence.quarantine().status())
                .isEqualTo(TestSuiteStabilityEvidence.QuarantineStatus.NOT_REQUIRED);
    }

    @Test
    void derivesSatisfiedStatisticalConfidenceFromTheExactCleanHorizon() {
        TestSuiteStabilityEvidence evidence = evaluator.evaluateStatistical(suite, suiteRef,
                STABILITY_RUN, "statistical-request", 29,
                statisticalAttempts("same"), Map.of("pipeline", "release"), statisticalPolicy());

        assertThat(evidence.schemaVersion())
                .isEqualTo(TestSuiteStabilityEvidence.SCHEMA_VERSION);
        assertThat(evidence.status()).isEqualTo(TestSuiteStabilityEvidence.Status.STABLE);
        assertThat(evidence.statisticalAssessment())
                .extracting(TestSuiteStabilityEvidence.StatisticalAssessment::requiredAttempts,
                        TestSuiteStabilityEvidence.StatisticalAssessment::verifiedAttempts,
                        TestSuiteStabilityEvidence.StatisticalAssessment::censoredAttempts,
                        TestSuiteStabilityEvidence.StatisticalAssessment::observedInstabilityEvents,
                        TestSuiteStabilityEvidence.StatisticalAssessment::status)
                .containsExactly(29, 29, 0, 0,
                        TestSuiteStabilityEvidence.StatisticalStatus.SATISFIED);
        assertThat(evidence.statisticalAssessment().achievedConfidenceBps())
                .isGreaterThanOrEqualTo(9_500);
        assertThat(evidence.promotion().status())
                .isEqualTo(TestSuiteStabilityEvidence.PromotionStatus.ELIGIBLE);
        assertThat(evidence.promotion().statisticalConfidenceSatisfied()).isTrue();
    }

    @Test
    void rejectsStatisticalConfidenceWhenOneVerifiedSuiteVectorChanges() {
        List<TestSuiteStabilityEvidenceEvaluator.AttemptObservation> attempts =
                new ArrayList<>(statisticalAttempts("same"));
        attempts.set(1, attempt(2, outcomes("variant")));

        TestSuiteStabilityEvidence evidence = evaluator.evaluateStatistical(suite, suiteRef,
                STABILITY_RUN, "statistical-request", 29, attempts, Map.of(), statisticalPolicy());

        assertThat(evidence.status()).isEqualTo(TestSuiteStabilityEvidence.Status.FLAKY);
        assertThat(evidence.statisticalAssessment().status())
                .isEqualTo(TestSuiteStabilityEvidence.StatisticalStatus.REJECTED);
        assertThat(evidence.statisticalAssessment().observedInstabilityEvents()).isEqualTo(1);
        assertThat(evidence.statisticalAssessment().achievedConfidenceBps()).isZero();
        assertThat(evidence.promotion().reasons())
                .containsExactly("FLAKY_CASE_OBSERVED", "STATISTICAL_CONFIDENCE_REJECTED");
        assertThat(evidence.quarantine().status())
                .isEqualTo(TestSuiteStabilityEvidence.QuarantineStatus.REQUIRED);
    }

    @Test
    void treatsOneCensoredAttemptAsStatisticallyInconclusiveWithoutDenominatorRepair() {
        List<TestSuiteStabilityEvidenceEvaluator.AttemptObservation> attempts =
                new ArrayList<>(statisticalAttempts("same"));
        attempts.set(10, TestSuiteStabilityEvidenceEvaluator.AttemptObservation.missing(
                11, START.plusSeconds(110), "WORKER_UNAVAILABLE"));

        TestSuiteStabilityEvidence evidence = evaluator.evaluateStatistical(suite, suiteRef,
                STABILITY_RUN, "statistical-request", 29, attempts, Map.of(), statisticalPolicy());

        assertThat(evidence.statisticalAssessment())
                .extracting(TestSuiteStabilityEvidence.StatisticalAssessment::observedAttempts,
                        TestSuiteStabilityEvidence.StatisticalAssessment::verifiedAttempts,
                        TestSuiteStabilityEvidence.StatisticalAssessment::censoredAttempts,
                        TestSuiteStabilityEvidence.StatisticalAssessment::status)
                .containsExactly(29, 28, 1,
                        TestSuiteStabilityEvidence.StatisticalStatus.INCONCLUSIVE);
        assertThat(evidence.promotion().reasons())
                .contains("STATISTICAL_CONFIDENCE_INCONCLUSIVE");
    }

    @Test
    void keepsRepeatableConsistentFailureBlockedDespiteSatisfiedConfidence() {
        Map<String, Outcome> failed = Map.of(
                "golden", outcome(TestRunEvidence.Status.ASSERTION_FAILED, "same-failure"),
                "negative", outcome(TestRunEvidence.Status.PASSED, "same-negative"));
        List<TestSuiteStabilityEvidenceEvaluator.AttemptObservation> attempts =
                IntStream.rangeClosed(1, 29).mapToObj(value -> attempt(value, failed)).toList();

        TestSuiteStabilityEvidence evidence = evaluator.evaluateStatistical(suite, suiteRef,
                STABILITY_RUN, "statistical-request", 29, attempts, Map.of(), statisticalPolicy());

        assertThat(evidence.status())
                .isEqualTo(TestSuiteStabilityEvidence.Status.CONSISTENT_FAILURE);
        assertThat(evidence.statisticalAssessment().status())
                .isEqualTo(TestSuiteStabilityEvidence.StatisticalStatus.SATISFIED);
        assertThat(evidence.promotion().status())
                .isEqualTo(TestSuiteStabilityEvidence.PromotionStatus.BLOCKED);
        assertThat(evidence.promotion().reasons())
                .containsExactly("CONSISTENT_TEST_FAILURE");
    }

    @Test
    void sourcePromotionBlockCannotBeLaunderedBySatisfiedStatisticalConfidence() {
        List<TestSuiteStabilityEvidenceEvaluator.AttemptObservation> attempts =
                new ArrayList<>(statisticalAttempts("same"));
        attempts.set(1, withSourcePromotion(attempt(2, outcomes("same")),
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.BLOCKED,
                        List.of("NO_CERTIFIABLE_CASES"), true, 0, 2, true, true, true)));

        TestSuiteStabilityEvidence evidence = evaluator.evaluateStatistical(suite, suiteRef,
                STABILITY_RUN, "statistical-request", 29, attempts, Map.of(), statisticalPolicy());

        assertThat(evidence.statisticalAssessment().status())
                .isEqualTo(TestSuiteStabilityEvidence.StatisticalStatus.SATISFIED);
        assertThat(evidence.promotion().status())
                .isEqualTo(TestSuiteStabilityEvidence.PromotionStatus.BLOCKED);
        assertThat(evidence.promotion().reasons())
                .containsExactly("SOURCE_SUITE_PROMOTION_BLOCKED");
    }

    @Test
    void keepsStableBehaviorButBlocksPromotionWhenAnySourceSuiteIsNotEligible() {
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation blocked = withSourcePromotion(
                attempt(2, outcomes("same")), new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.BLOCKED,
                        List.of("NO_CERTIFIABLE_CASES"), true, 0, 2, true, true, true));

        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3, List.of(
                        attempt(1, outcomes("same")), blocked,
                        attempt(3, outcomes("same"))), Map.of());

        assertThat(evidence.status()).isEqualTo(TestSuiteStabilityEvidence.Status.STABLE);
        assertThat(evidence.attempts().get(1).sourcePromotionStatus())
                .isEqualTo(TestSuiteRunEvidence.PromotionStatus.BLOCKED);
        assertThat(evidence.attempts().get(1).sourcePromotionReasons())
                .containsExactly("NO_CERTIFIABLE_CASES");
        assertThat(evidence.promotion().status())
                .isEqualTo(TestSuiteStabilityEvidence.PromotionStatus.BLOCKED);
        assertThat(evidence.promotion().reasons())
                .containsExactly("SOURCE_SUITE_PROMOTION_BLOCKED");
        assertThat(evidence.promotion().allAttemptsVerified()).isTrue();
        assertThat(evidence.promotion().allSourceSuitesPromotionEligible()).isFalse();
        assertThat(evidence.quarantine().status())
                .isEqualTo(TestSuiteStabilityEvidence.QuarantineStatus.NOT_REQUIRED);
    }

    @Test
    void rejectsNestedMetadataAtTheEvidenceDomainBoundary() {
        List<TestSuiteStabilityEvidenceEvaluator.AttemptObservation> observations = List.of(
                attempt(1, outcomes("same")),
                attempt(2, outcomes("same")),
                attempt(3, outcomes("same")));

        assertThatThrownBy(() -> evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3, observations,
                Map.of("pipeline", Map.of("name", "nightly"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded scalar provenance");
    }

    @Test
    void detectsDifferentPassingSemanticsAndRequiresQuarantine() {
        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3, List.of(
                        attempt(1, outcomes("first")),
                        attempt(2, outcomes("second")),
                        attempt(3, outcomes("first"))), Map.of());

        assertThat(evidence.status()).isEqualTo(TestSuiteStabilityEvidence.Status.FLAKY);
        assertThat(evidence.caseResults().getFirst().status())
                .isEqualTo(TestSuiteStabilityEvidence.CaseStatus.FLAKY);
        assertThat(evidence.caseResults().getFirst().distinctVerifiedOutcomes()).isEqualTo(2);
        assertThat(evidence.promotion().reasons()).containsExactly("FLAKY_CASE_OBSERVED");
        assertThat(evidence.quarantine())
                .extracting(TestSuiteStabilityEvidence.QuarantineVerdict::status,
                        TestSuiteStabilityEvidence.QuarantineVerdict::caseIds)
                .containsExactly(TestSuiteStabilityEvidence.QuarantineStatus.REQUIRED,
                        List.of("golden"));
    }

    @Test
    void keepsAnIdenticalAssertionFailureAsConsistentFailureInsteadOfAStablePass() {
        Map<String, Outcome> failed = Map.of(
                "golden", outcome(TestRunEvidence.Status.ASSERTION_FAILED, "same-failure"),
                "negative", outcome(TestRunEvidence.Status.PASSED, "same-negative"));

        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3,
                List.of(attempt(1, failed), attempt(2, failed), attempt(3, failed)), Map.of());

        assertThat(evidence.status())
                .isEqualTo(TestSuiteStabilityEvidence.Status.CONSISTENT_FAILURE);
        assertThat(evidence.caseResults().getFirst().status())
                .isEqualTo(TestSuiteStabilityEvidence.CaseStatus.CONSISTENT_FAILURE);
        assertThat(evidence.promotion().reasons())
                .containsExactly("CONSISTENT_TEST_FAILURE");
        assertThat(evidence.quarantine().status())
                .isEqualTo(TestSuiteStabilityEvidence.QuarantineStatus.NOT_REQUIRED);
    }

    @Test
    void provesFlakinessFromTwoTrustedVariantsEvenWhenAnotherAttemptIsMissing() {
        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3, List.of(
                        attempt(1, outcomes("first")),
                        attempt(2, outcomes("second")),
                        TestSuiteStabilityEvidenceEvaluator.AttemptObservation.missing(
                                3, START.plusSeconds(30), "WORKER_UNAVAILABLE")), Map.of());

        assertThat(evidence.status()).isEqualTo(TestSuiteStabilityEvidence.Status.FLAKY);
        assertThat(evidence.promotion().reasons())
                .containsExactly("FLAKY_CASE_OBSERVED", "STABILITY_EVIDENCE_INCOMPLETE");
        assertThat(evidence.attempts().get(2).status())
                .isEqualTo(TestSuiteStabilityEvidence.AttemptStatus.INCONCLUSIVE);
    }

    @Test
    void treatsEffectivePlanDriftAsInconclusiveRatherThanFlaky() {
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation second = attempt(
                2, outcomes("same"), fingerprint('f'));

        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3, List.of(
                        attempt(1, outcomes("same")), second, attempt(3, outcomes("same"))),
                Map.of());

        assertThat(evidence.status())
                .isEqualTo(TestSuiteStabilityEvidence.Status.INCONCLUSIVE);
        assertThat(evidence.caseResults())
                .allMatch(value -> value.status()
                        == TestSuiteStabilityEvidence.CaseStatus.INCONCLUSIVE);
        assertThat(evidence.diagnostics())
                .containsExactly(TestSuiteStabilityEvidenceEvaluator.PLAN_DRIFT);
        assertThat(evidence.quarantine().status())
                .isEqualTo(TestSuiteStabilityEvidence.QuarantineStatus.UNDETERMINED);
    }

    @Test
    void rejectsUnsignedOrFingerprintMismatchedChildrenAsInconclusive() {
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation unsigned =
                attempt(2, outcomes("same"), PLAN, false, false);
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation mismatched =
                attempt(3, outcomes("same"), PLAN, true, true);

        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3,
                List.of(attempt(1, outcomes("same")), unsigned, mismatched), Map.of());

        assertThat(evidence.status())
                .isEqualTo(TestSuiteStabilityEvidence.Status.INCONCLUSIVE);
        assertThat(evidence.promotion().status())
                .isEqualTo(TestSuiteStabilityEvidence.PromotionStatus.BLOCKED);
        assertThat(evidence.diagnostics())
                .contains(TestSuiteStabilityEvidenceEvaluator.CHILD_EVIDENCE_INVALID);
    }

    @Test
    void rejectsUnverifiedSuiteAttestationBeforeAnyChildCanClaimStability() {
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation invalid = attempt(
                2, outcomes("same"), PLAN, false, false, false);

        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3,
                List.of(attempt(1, outcomes("same")), invalid,
                        attempt(3, outcomes("same"))), Map.of());

        assertThat(evidence.status())
                .isEqualTo(TestSuiteStabilityEvidence.Status.INCONCLUSIVE);
        assertThat(evidence.attempts().get(1).diagnosticCode())
                .isEqualTo(TestSuiteStabilityEvidenceEvaluator.SOURCE_EVIDENCE_INVALID);
    }

    @Test
    void rejectsARepeatedSourceOrChildRunAsIndependentStabilitySamples() {
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation first =
                attempt(1, outcomes("same"));
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation repeatedSource =
                new TestSuiteStabilityEvidenceEvaluator.AttemptObservation(
                        2, first.suiteExecution(), true, first.childrenByRunId(),
                        START.plusSeconds(20), "");
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation repeatedChildren =
                reuseChildren(first, 3);

        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3,
                List.of(first, repeatedSource, repeatedChildren), Map.of());

        assertThat(evidence.status())
                .isEqualTo(TestSuiteStabilityEvidence.Status.INCONCLUSIVE);
        assertThat(evidence.attempts())
                .extracting(TestSuiteStabilityEvidence.AttemptResult::status)
                .containsExactly(TestSuiteStabilityEvidence.AttemptStatus.VERIFIED,
                        TestSuiteStabilityEvidence.AttemptStatus.INCONCLUSIVE,
                        TestSuiteStabilityEvidence.AttemptStatus.INCONCLUSIVE);
        assertThat(evidence.diagnostics())
                .contains(TestSuiteStabilityEvidenceEvaluator.SOURCE_RUN_REUSED,
                        TestSuiteStabilityEvidenceEvaluator.CHILD_RUN_REUSED);
    }

    @Test
    void rejectsAChildRunReusedByAnotherCaseInALaterAttempt() {
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation first =
                attempt(1, outcomes("same"));
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation second =
                reuseGoldenChildAsNegative(first, attempt(2, outcomes("same")));

        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3,
                List.of(first, second, attempt(3, outcomes("same"))), Map.of());

        assertThat(evidence.status())
                .isEqualTo(TestSuiteStabilityEvidence.Status.INCONCLUSIVE);
        assertThat(evidence.attempts().get(1).diagnosticCode())
                .isEqualTo(TestSuiteStabilityEvidenceEvaluator.CHILD_RUN_REUSED);
        assertThat(evidence.caseResults()).filteredOn(result ->
                        result.caseId().equals("negative"))
                .singleElement().satisfies(result ->
                        assertThat(result.observations().get(1).diagnosticCode())
                                .isEqualTo(TestSuiteStabilityEvidenceEvaluator.CHILD_RUN_REUSED));
    }

    @Test
    void rejectsAggregatePassThatContradictsVerifiedChildFailure() {
        Map<String, Outcome> failed = Map.of(
                "golden", outcome(TestRunEvidence.Status.ASSERTION_FAILED, "same-failure"),
                "negative", outcome(TestRunEvidence.Status.PASSED, "same-negative"));
        TestSuiteStabilityEvidenceEvaluator.AttemptObservation contradictory =
                withSuiteStatus(attempt(2, failed), TestSuiteRunEvidence.Status.PASSED);

        TestSuiteStabilityEvidence evidence = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3, List.of(
                        attempt(1, failed), contradictory, attempt(3, failed)), Map.of());

        assertThat(evidence.status())
                .isEqualTo(TestSuiteStabilityEvidence.Status.INCONCLUSIVE);
        assertThat(evidence.attempts().get(1).diagnosticCode())
                .isEqualTo(TestSuiteStabilityEvidenceEvaluator.SOURCE_EVIDENCE_INVALID);
    }

    @Test
    void evidenceConstructorRejectsForgedPromotionOrAggregateStatus() {
        TestSuiteStabilityEvidence valid = evaluator.evaluate(suite, suiteRef,
                STABILITY_RUN, "stability-request", 3, List.of(
                        attempt(1, outcomes("same")), attempt(2, outcomes("same")),
                        attempt(3, outcomes("same"))), Map.of());

        assertThatThrownBy(() -> new TestSuiteStabilityEvidence(
                valid.schemaVersion(), valid.stabilityRunId(), valid.clientRequestId(),
                valid.suiteRef(), valid.target(), valid.requestedAttempts(),
                TestSuiteStabilityEvidence.Status.FLAKY, valid.attempts(), valid.caseResults(),
                valid.promotion(), valid.quarantine(), valid.startedAt(), valid.completedAt(),
                valid.diagnostics(), valid.metadata()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server-derived");

        TestSuiteStabilityEvidence.PromotionVerdict forged =
                new TestSuiteStabilityEvidence.PromotionVerdict(
                        TestSuiteStabilityEvidence.PromotionStatus.ELIGIBLE, List.of(),
                        2, 0, 0, 0, false, false);
        assertThatThrownBy(() -> new TestSuiteStabilityEvidence(
                valid.schemaVersion(), valid.stabilityRunId(), valid.clientRequestId(),
                valid.suiteRef(), valid.target(), valid.requestedAttempts(), valid.status(),
                valid.attempts(), valid.caseResults(), forged, valid.quarantine(),
                valid.startedAt(), valid.completedAt(), valid.diagnostics(), valid.metadata()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("server-derived");
    }

    @Test
    void rejectsSuiteGenerationsWithoutExecutableChildEvidence() {
        TestSuiteV3 admission = new TestSuiteV3("", "admission", 1, suite.target(), "INTERNAL",
                suite.cases(), suite.coveragePolicy(), SemanticCoveragePolicy.empty(),
                suite.promotionPolicy(), TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                fingerprint('6'), fingerprint('7'), Map.of(
                "golden", new TestSuiteV3.AdmissionExpectation(
                        TestSuiteV3.ExpectedOutcome.ACCEPTED, List.of()),
                "negative", new TestSuiteV3.AdmissionExpectation(
                        TestSuiteV3.ExpectedOutcome.SCHEMA_REJECTED,
                        List.of("SCHEMA_TYPE_MISMATCH"))), Map.of());

        assertThatThrownBy(() -> evaluator.evaluate(admission,
                new TestSuiteExecutionRequest.SuiteRef("admission", 1, SUITE_FINGERPRINT),
                STABILITY_RUN, "request", 3, List.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("executable child evidence");
    }

    private Map<String, Outcome> outcomes(String goldenVariant) {
        return Map.of("golden", outcome(TestRunEvidence.Status.PASSED, goldenVariant),
                "negative", outcome(TestRunEvidence.Status.PASSED, "same-negative"));
    }

    private List<TestSuiteStabilityEvidenceEvaluator.AttemptObservation> statisticalAttempts(
            String goldenVariant) {
        return IntStream.rangeClosed(1, 29)
                .mapToObj(value -> attempt(value, outcomes(goldenVariant))).toList();
    }

    private static TestSuiteStabilityStatisticalPolicy statisticalPolicy() {
        return new TestSuiteStabilityStatisticalPolicy(
                TestSuiteStabilityStatisticalPolicy.Model.ZERO_INSTABILITY_EXACT_BINOMIAL,
                TestSuiteStabilityStatisticalPolicy.ClaimScope.SUITE_ATTEMPT_ANY_CASE,
                TestSuiteStabilityStatisticalPolicy.StoppingRule.PRECOMMITTED_FIXED_HORIZON,
                TestSuiteStabilityStatisticalPolicy.CensoringPolicy.FAIL_CLOSED,
                9_500, 1_000);
    }

    private TestSuiteStabilityEvidenceEvaluator.AttemptObservation attempt(
            int attempt, Map<String, Outcome> outcomes) {
        return attempt(attempt, outcomes, PLAN);
    }

    private TestSuiteStabilityEvidenceEvaluator.AttemptObservation attempt(
            int attempt, Map<String, Outcome> outcomes, String plan) {
        return attempt(attempt, outcomes, plan, true, false);
    }

    private TestSuiteStabilityEvidenceEvaluator.AttemptObservation attempt(
            int attempt, Map<String, Outcome> outcomes, String plan,
            boolean childVerified, boolean corruptFingerprint) {
        return attempt(attempt, outcomes, plan, childVerified, corruptFingerprint, true);
    }

    private TestSuiteStabilityEvidenceEvaluator.AttemptObservation attempt(
            int attempt, Map<String, Outcome> outcomes, String plan,
            boolean childVerified, boolean corruptFingerprint, boolean suiteVerified) {
        List<TestSuiteRunEvidence.CaseResult> caseResults = new ArrayList<>();
        List<TestSuiteRunAttestation.ChildEvidenceRef> childRefs = new ArrayList<>();
        Map<String, TestSuiteStabilityEvidenceEvaluator.ChildObservation> children =
                new LinkedHashMap<>();
        for (TestSuite.TestCase testCase : suite.cases()) {
            Outcome outcome = outcomes.get(testCase.caseId());
            TestRunEvidence childEvidence = childEvidence(
                    attempt, testCase, outcome, plan);
            String evidenceFingerprint = ProtocolFingerprint.of(mapper, childEvidence);
            String referencedFingerprint = corruptFingerprint && "golden".equals(testCase.caseId())
                    ? fingerprint('9') : evidenceFingerprint;
            String runId = childEvidence.runId();
            TestSuiteRunEvidence.CaseStatus caseStatus = outcome.status()
                    == TestRunEvidence.Status.PASSED
                    ? TestSuiteRunEvidence.CaseStatus.PASSED
                    : TestSuiteRunEvidence.CaseStatus.FAILED;
            int passed = outcome.status() == TestRunEvidence.Status.PASSED ? 1 : 0;
            caseResults.add(new TestSuiteRunEvidence.CaseResult(testCase.caseId(),
                    testCase.caseType(), testCase.fixtureBundleRef(), caseStatus, runId,
                    outcome.status(), TestRunEvidence.EvidenceClass.CERTIFIABLE,
                    1, passed, "", ""));
            childRefs.add(new TestSuiteRunAttestation.ChildEvidenceRef(
                    testCase.caseId(), runId, referencedFingerprint));
            TestEvidenceIntegrity integrity = new TestEvidenceIntegrity("", evidenceFingerprint,
                    TestEvidenceIntegrity.SignatureStatus.VERIFIED, "test-key", "Ed25519",
                    START.plusSeconds(attempt), "signature", TestEvidenceIntegrity.Projection.FULL,
                    evidenceFingerprint, true);
            TestExecutionApiResponse childResponse = new TestExecutionApiResponse("", runId,
                    new TestExecutionApiRequest.Target("GRAPH", "orders", TARGET),
                    new TestExecutionApiResponse.ResolvedFixtureBundleRef(
                            "STORED", testCase.fixtureBundleRef().fixtureBundleId(),
                            testCase.fixtureBundleRef().revision(), FIXTURE),
                    (EffectiveExecutionPlan) null, integrity, childEvidence);
            children.put(runId, new TestSuiteStabilityEvidenceEvaluator.ChildObservation(
                    childResponse, childVerified));
        }
        TestSuiteRunEvidence.Status status = caseResults.stream().allMatch(value ->
                value.status() == TestSuiteRunEvidence.CaseStatus.PASSED)
                ? TestSuiteRunEvidence.Status.PASSED
                : TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES;
        TestSuiteRunEvidence suiteEvidence = new TestSuiteRunEvidence("",
                "suite-run-" + attempt, "derived-" + attempt, status,
                TestSuiteExecutionServicePurpose.VALUE, suiteRef, suite.target(),
                START.plusSeconds(attempt * 10L), START.plusSeconds(attempt * 10L + 1),
                caseResults, TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                eligibleSourcePromotion(), List.of(), Map.of());
        String aggregateFingerprint = new TestSuiteRunEvidenceProtocolCodec(mapper)
                .fingerprint(suiteEvidence);
        TestSuiteRunAttestation attestation = new TestSuiteRunAttestation("",
                TestSuiteRunAttestation.SignatureStatus.VERIFIED,
                TestSuiteRunAttestation.Scope.TERMINAL, suiteEvidence.suiteRunId(), suiteRef,
                fingerprint('8'), aggregateFingerprint, childRefs,
                START.plusSeconds(attempt * 10L + 2), "test-key", "Ed25519", "signature", true);
        TestSuiteExecutionResponse suiteResponse = new TestSuiteExecutionResponse("",
                suiteEvidence.suiteRunId(), aggregateFingerprint, suiteEvidence, attestation);
        return new TestSuiteStabilityEvidenceEvaluator.AttemptObservation(
                attempt, suiteResponse, suiteVerified, children,
                START.plusSeconds(attempt * 10L + 2), "");
    }

    private TestRunEvidence childEvidence(
            int attempt,
            TestSuite.TestCase testCase,
            Outcome outcome,
            String plan) {
        int passed = outcome.status() == TestRunEvidence.Status.PASSED ? 1 : 0;
        TestRunEvidence raw = new TestRunEvidence("",
                "child-%d-%s".formatted(attempt, testCase.caseId()), outcome.status(),
                TestRunEvidence.EvidenceClass.CERTIFIABLE, "GRAPH_CONTRACT_TEST",
                TARGET, FIXTURE, plan, "", START, START.plusSeconds(1),
                List.of(new TestRunEvidence.NodeTrace("result", "operator.result", "SUCCESS",
                        "OUTPUT_LEVEL", Map.of(), Map.of("variant", outcome.variant()), "", 1)),
                List.of(), List.of(),
                List.of(new TestRunEvidence.AssertionResult("GRAPH_OUTPUT", "/ok",
                        passed == 1, true, passed == 1, "")), List.of(), Map.of());
        return TestSemanticResultFingerprint.attach(mapper, raw);
    }

    private TestSuiteStabilityEvidenceEvaluator.AttemptObservation reuseChildren(
            TestSuiteStabilityEvidenceEvaluator.AttemptObservation source,
            int attempt) {
        TestSuiteExecutionResponse original = source.suiteExecution();
        TestSuiteRunEvidence evidence = (TestSuiteRunEvidence) original.evidence();
        TestSuiteRunEvidence replacement = new TestSuiteRunEvidence("",
                "suite-run-" + attempt, "derived-" + attempt, evidence.status(),
                evidence.executionPurpose(), evidence.suiteRef(), evidence.target(),
                START.plusSeconds(attempt * 10L), START.plusSeconds(attempt * 10L + 1),
                evidence.caseResults(), evidence.coverage(), evidence.promotion(),
                evidence.diagnostics(), evidence.metadata());
        return attemptObservation(attempt, replacement,
                original.attestation().childEvidenceRefs(), source.childrenByRunId());
    }

    private TestSuiteStabilityEvidenceEvaluator.AttemptObservation reuseGoldenChildAsNegative(
            TestSuiteStabilityEvidenceEvaluator.AttemptObservation source,
            TestSuiteStabilityEvidenceEvaluator.AttemptObservation destination) {
        TestSuiteRunAttestation.ChildEvidenceRef reusedRef = source.suiteExecution().attestation()
                .childEvidenceRefs().stream().filter(ref -> ref.caseId().equals("golden"))
                .findFirst().orElseThrow();
        TestSuiteRunEvidence destinationEvidence =
                (TestSuiteRunEvidence) destination.suiteExecution().evidence();
        List<TestSuiteRunEvidence.CaseResult> results = destinationEvidence.caseResults().stream()
                .map(result -> result.caseId().equals("negative")
                        ? new TestSuiteRunEvidence.CaseResult(result.caseId(), result.caseType(),
                        result.fixtureBundleRef(), result.status(), reusedRef.runId(),
                        result.evidenceStatus(), result.evidenceClass(), result.assertionsEvaluated(),
                        result.assertionsPassed(), result.diagnosticCode(), result.diagnostic())
                        : result)
                .toList();
        TestSuiteRunEvidence replacement = new TestSuiteRunEvidence("",
                destinationEvidence.suiteRunId(), destinationEvidence.clientRequestId(),
                destinationEvidence.status(), destinationEvidence.executionPurpose(),
                destinationEvidence.suiteRef(), destinationEvidence.target(),
                destinationEvidence.startedAt(), destinationEvidence.completedAt(), results,
                destinationEvidence.coverage(), destinationEvidence.promotion(),
                destinationEvidence.diagnostics(), destinationEvidence.metadata());
        List<TestSuiteRunAttestation.ChildEvidenceRef> refs = destination.suiteExecution()
                .attestation().childEvidenceRefs().stream()
                .map(ref -> ref.caseId().equals("negative")
                        ? new TestSuiteRunAttestation.ChildEvidenceRef(
                        "negative", reusedRef.runId(), reusedRef.evidenceFingerprint()) : ref)
                .toList();
        Map<String, TestSuiteStabilityEvidenceEvaluator.ChildObservation> children =
                new LinkedHashMap<>(destination.childrenByRunId());
        children.put(reusedRef.runId(), source.childrenByRunId().get(reusedRef.runId()));
        return attemptObservation(destination.attempt(), replacement, refs, children);
    }

    private TestSuiteStabilityEvidenceEvaluator.AttemptObservation withSuiteStatus(
            TestSuiteStabilityEvidenceEvaluator.AttemptObservation source,
            TestSuiteRunEvidence.Status status) {
        TestSuiteRunEvidence evidence = (TestSuiteRunEvidence) source.suiteExecution().evidence();
        TestSuiteRunEvidence replacement = new TestSuiteRunEvidence("",
                evidence.suiteRunId(), evidence.clientRequestId(), status,
                evidence.executionPurpose(), evidence.suiteRef(), evidence.target(),
                evidence.startedAt(), evidence.completedAt(), evidence.caseResults(),
                evidence.coverage(), evidence.promotion(), evidence.diagnostics(),
                evidence.metadata());
        return attemptObservation(source.attempt(), replacement,
                source.suiteExecution().attestation().childEvidenceRefs(),
                source.childrenByRunId());
    }

    private TestSuiteStabilityEvidenceEvaluator.AttemptObservation withSourcePromotion(
            TestSuiteStabilityEvidenceEvaluator.AttemptObservation source,
            TestSuiteRunEvidence.PromotionVerdict promotion) {
        TestSuiteRunEvidence evidence = (TestSuiteRunEvidence) source.suiteExecution().evidence();
        TestSuiteRunEvidence replacement = new TestSuiteRunEvidence("",
                evidence.suiteRunId(), evidence.clientRequestId(), evidence.status(),
                evidence.executionPurpose(), evidence.suiteRef(), evidence.target(),
                evidence.startedAt(), evidence.completedAt(), evidence.caseResults(),
                evidence.coverage(), promotion, evidence.diagnostics(), evidence.metadata());
        return attemptObservation(source.attempt(), replacement,
                source.suiteExecution().attestation().childEvidenceRefs(),
                source.childrenByRunId());
    }

    private TestSuiteStabilityEvidenceEvaluator.AttemptObservation attemptObservation(
            int attempt,
            TestSuiteRunEvidence evidence,
            List<TestSuiteRunAttestation.ChildEvidenceRef> childRefs,
            Map<String, TestSuiteStabilityEvidenceEvaluator.ChildObservation> children) {
        String aggregateFingerprint = new TestSuiteRunEvidenceProtocolCodec(mapper)
                .fingerprint(evidence);
        TestSuiteRunAttestation attestation = new TestSuiteRunAttestation("",
                TestSuiteRunAttestation.SignatureStatus.VERIFIED,
                TestSuiteRunAttestation.Scope.TERMINAL, evidence.suiteRunId(), suiteRef,
                fingerprint('8'), aggregateFingerprint, childRefs,
                START.plusSeconds(attempt * 10L + 2), "test-key", "Ed25519", "signature", true);
        TestSuiteExecutionResponse response = new TestSuiteExecutionResponse("",
                evidence.suiteRunId(), aggregateFingerprint, evidence, attestation);
        return new TestSuiteStabilityEvidenceEvaluator.AttemptObservation(
                attempt, response, true, children,
                START.plusSeconds(attempt * 10L + 2), "");
    }

    private static TestSuite suite() {
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "orders-fixture", 3, FIXTURE);
        return new TestSuite("", "orders-suite", 7,
                new TestSuite.Target("GRAPH", "orders", TARGET), "INTERNAL",
                List.of(new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                                Map.of(), fixture, List.of(), Map.of()),
                        new TestSuite.TestCase("negative", TestSuite.CaseType.NEGATIVE,
                                Map.of(), fixture, List.of(), Map.of())),
                new TestSuite.CoveragePolicy(2, List.of(), List.of(), List.of(), 1, true),
                new TestSuite.PromotionPolicy(true, 2, true), Map.of());
    }

    private static TestSuiteRunEvidence.PromotionVerdict eligibleSourcePromotion() {
        return new TestSuiteRunEvidence.PromotionVerdict(
                TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(), true,
                2, 2, true, true, true);
    }

    private static Outcome outcome(TestRunEvidence.Status status, String variant) {
        return new Outcome(status, variant);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record Outcome(TestRunEvidence.Status status, String variant) {
    }

    private static final class TestSuiteExecutionServicePurpose {
        private static final String VALUE = "TEST_SUITE_EXECUTION";

        private TestSuiteExecutionServicePurpose() {
        }
    }
}
