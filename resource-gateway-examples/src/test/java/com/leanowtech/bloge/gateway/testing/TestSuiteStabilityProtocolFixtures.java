package com.leanowtech.bloge.gateway.testing;

import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityStatisticalPolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared payload-free stability protocol fixtures for persistence and signature tests. */
public final class TestSuiteStabilityProtocolFixtures {
    /** Exact suite fingerprint used by the fixture. */
    public static final String SUITE_FINGERPRINT = fingerprint('a');
    /** Exact target fingerprint used by the fixture. */
    public static final String TARGET_FINGERPRINT = fingerprint('b');
    /** Exact fixture fingerprint used by the fixture. */
    public static final String FIXTURE_FINGERPRINT = fingerprint('c');
    /** Exact plan fingerprint used by the fixture. */
    public static final String PLAN_FINGERPRINT = fingerprint('d');
    /** Deterministic stability analysis id used by the fixture. */
    public static final String STABILITY_RUN_ID = "stability-" + "e".repeat(64);
    /** Exact immutable suite reference used by the fixture. */
    public static final TestSuiteExecutionRequest.SuiteRef SUITE_REF =
            new TestSuiteExecutionRequest.SuiteRef("suite-a", 3, SUITE_FINGERPRINT);
    /** Exact target used by the fixture. */
    public static final TestSuite.Target TARGET =
            new TestSuite.Target("GRAPH", "graph-a", TARGET_FINGERPRINT);
    private static final Instant START = Instant.parse("2026-07-18T02:00:00Z");

    private TestSuiteStabilityProtocolFixtures() {
    }

    /**
     * Creates internally consistent stable evidence with three independent source runs.
     *
     * @return immutable stable evidence
     */
    public static TestSuiteStabilityEvidence stableEvidence() {
        return stableEvidence(3, null);
    }

    /**
     * Creates v3 evidence satisfying a 95% confidence claim at a 10% instability ceiling.
     *
     * @return immutable statistical stability evidence
     */
    public static TestSuiteStabilityEvidence statisticalStableEvidence() {
        TestSuiteStabilityStatisticalPolicy policy = new TestSuiteStabilityStatisticalPolicy(
                TestSuiteStabilityStatisticalPolicy.Model.ZERO_INSTABILITY_EXACT_BINOMIAL,
                TestSuiteStabilityStatisticalPolicy.ClaimScope.SUITE_ATTEMPT_ANY_CASE,
                TestSuiteStabilityStatisticalPolicy.StoppingRule.PRECOMMITTED_FIXED_HORIZON,
                TestSuiteStabilityStatisticalPolicy.CensoringPolicy.FAIL_CLOSED,
                9_500, 1_000);
        return stableEvidence(29, policy);
    }

    /**
     * Creates v4 evidence satisfying the corrected baseline-conditional exact-rate claim.
     *
     * @return immutable baseline-conditional statistical evidence
     */
    public static TestSuiteStabilityEvidence rateStableEvidence() {
        return stableEvidence(30,
                TestSuiteStabilityStatisticalPolicy.baselineConditionalExactBinomial(
                        9_500, 1_000));
    }

    /**
     * Creates v5 evidence that stops at the first clean anytime-valid boundary.
     *
     * @return immutable 57-of-100 sequential stability evidence
     */
    public static TestSuiteStabilityEvidence sequentialStableEvidence() {
        return stableEvidence(57, 100,
                TestSuiteStabilityStatisticalPolicy.anytimeValidEProcess(
                        9_500, 1_000, 500));
    }

    private static TestSuiteStabilityEvidence stableEvidence(
            int attemptCount,
            TestSuiteStabilityStatisticalPolicy statisticalPolicy) {
        return stableEvidence(attemptCount, attemptCount, statisticalPolicy);
    }

    private static TestSuiteStabilityEvidence stableEvidence(
            int observedAttempts,
            int requestedAttempts,
            TestSuiteStabilityStatisticalPolicy statisticalPolicy) {
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "fixture-a", 2, FIXTURE_FINGERPRINT);
        List<TestSuiteStabilityEvidence.AttemptResult> attempts = new ArrayList<>();
        List<TestSuiteStabilityEvidence.CaseObservation> observations = new ArrayList<>();
        for (int attempt = 1; attempt <= observedAttempts; attempt++) {
            attempts.add(new TestSuiteStabilityEvidence.AttemptResult(attempt,
                    TestSuiteStabilityEvidence.AttemptStatus.VERIFIED,
                    "suite-run-" + attempt, indexedFingerprint(attempt),
                    TestSuiteRunEvidence.Status.PASSED,
                    TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(),
                    START.plusSeconds(attempt),
                    START.plusSeconds(attempt + 1L), ""));
            observations.add(new TestSuiteStabilityEvidence.CaseObservation(attempt,
                    TestSuiteStabilityEvidence.ObservationStatus.VERIFIED,
                    "child-run-" + attempt, indexedFingerprint(10 + attempt),
                    TestRunEvidence.Status.PASSED,
                    TestRunEvidence.EvidenceClass.CERTIFIABLE,
                    FIXTURE_FINGERPRINT, PLAN_FINGERPRINT, fingerprint('f'), ""));
        }
        TestSuiteStabilityEvidence.CaseStabilityResult result =
                new TestSuiteStabilityEvidence.CaseStabilityResult(
                        "golden", TestSuite.CaseType.GOLDEN, fixture,
                        TestSuiteStabilityEvidence.CaseStatus.STABLE_PASS,
                        observations, 1, List.of());
        List<TestSuiteStabilityEvidence.CaseStabilityResult> cases = List.of(result);
        TestSuiteStabilityEvidence.Status status = TestSuiteStabilityEvidence.Status.STABLE;
        TestSuiteStabilityEvidence.StatisticalAssessment statistics = statisticalPolicy == null
                ? null : TestSuiteStabilityEvidence.deriveStatisticalAssessment(
                statisticalPolicy, requestedAttempts, attempts, cases);
        TestSuiteStabilityEvidence.PromotionVerdict promotion = statisticalPolicy == null
                ? TestSuiteStabilityEvidence.derivePromotion(attempts, cases, status)
                : TestSuiteStabilityEvidence.deriveStatisticalPromotion(
                attempts, cases, status, statistics);
        return new TestSuiteStabilityEvidence(
                statisticalPolicy == null ? TestSuiteStabilityEvidence.SCHEMA_VERSION_V2
                        : statisticalPolicy.model()
                        == TestSuiteStabilityStatisticalPolicy.Model
                        .ZERO_INSTABILITY_EXACT_BINOMIAL
                        ? TestSuiteStabilityEvidence.SCHEMA_VERSION_V3
                        : statisticalPolicy.model()
                        == TestSuiteStabilityStatisticalPolicy.Model
                        .BASELINE_CONDITIONAL_EXACT_BINOMIAL
                        ? TestSuiteStabilityEvidence.SCHEMA_VERSION_V4
                        : TestSuiteStabilityEvidence.SCHEMA_VERSION,
                STABILITY_RUN_ID, "stability-request",
                SUITE_REF, TARGET, requestedAttempts, status, attempts, cases, promotion,
                TestSuiteStabilityEvidence.deriveQuarantine(cases, status),
                statistics, START.plusSeconds(1), START.plusSeconds(observedAttempts + 1L), List.of(),
                Map.of("pipeline", "nightly"));
    }

    /** @return canonical test fingerprint filled with one hexadecimal character */
    public static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static String indexedFingerprint(int value) {
        return "sha256:" + "%064x".formatted(value);
    }
}
