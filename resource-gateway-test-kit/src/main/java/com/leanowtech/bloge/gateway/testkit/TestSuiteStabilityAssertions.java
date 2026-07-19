package com.leanowtech.bloge.gateway.testkit;

import org.opentest4j.AssertionFailedError;

/** JUnit 5 assertions for independently verified suite-stability evidence. */
public final class TestSuiteStabilityAssertions {
    private TestSuiteStabilityAssertions() {
    }

    /**
     * Requires complete independent observations to prove one invariant passing outcome per case.
     *
     * @param run stability analysis to assert
     */
    public static void assertStable(TestSuiteStabilityRun run) {
        required(run);
        if (!run.stable()) {
            throw new AssertionFailedError("Resource Gateway stability analysis "
                    + run.stabilityRunId() + " is not stable; status=" + run.status(),
                    TestSuiteStabilityRun.Status.STABLE, run.status());
        }
    }

    /**
     * Requires a verifier result for the exact stability analysis to pass every cryptographic and
     * key-lifecycle policy check.
     *
     * @param run stability analysis whose identity must be verified
     * @param verification offline verification result
     */
    public static void assertVerified(
            TestSuiteStabilityRun run,
            TestSuiteStabilityEvidenceVerifier.VerificationResult verification) {
        required(run);
        if (verification == null || !verification.verified()
                || !run.stabilityRunId().equals(verification.stabilityRunId())) {
            String outcome = verification == null ? "MISSING"
                    : verification.outcome() + ":" + verification.reasonCode();
            throw new AssertionFailedError("Resource Gateway stability analysis "
                    + run.stabilityRunId() + " is not independently verified; outcome=" + outcome,
                    "VERIFIED for the exact stabilityRunId", outcome);
        }
    }

    /**
     * Requires stable behavior, promotion eligibility, and exact independently verified trust.
     * This is a CI input and does not itself publish or quarantine a suite.
     *
     * @param run stability analysis to gate
     * @param verification offline verification result for the same analysis
     */
    public static void assertReleaseEligible(
            TestSuiteStabilityRun run,
            TestSuiteStabilityEvidenceVerifier.VerificationResult verification) {
        assertVerified(run, verification);
        assertStable(run);
        if (!run.sourcePromotionClosureAvailable()) {
            throw new AssertionFailedError("Resource Gateway stability analysis "
                    + run.stabilityRunId()
                    + " cannot enter a release gate; v2 source-promotion closure is required",
                    TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V2,
                    run.schemaVersion());
        }
        if (!run.promotionEligible()) {
            throw new AssertionFailedError("Resource Gateway stability analysis "
                    + run.stabilityRunId() + " is not promotion eligible; reasons="
                    + String.join(",", run.promotion().reasons()),
                    TestSuiteStabilityRun.PromotionStatus.ELIGIBLE,
                    run.promotion().status());
        }
    }

    /**
     * Requires an independently reconstructed v3-v5 assessment to satisfy its exact stop rule.
     *
     * <p>This assertion checks the probability claim only. It does not replace
     * {@link #assertStable(TestSuiteStabilityRun)} or source-suite promotion checks.</p>
     *
     * @param run statistical stability analysis to assert
     */
    public static void assertStatisticalConfidenceSatisfied(TestSuiteStabilityRun run) {
        required(run);
        if (!run.statisticalConfidenceAvailable()) {
            throw new AssertionFailedError("Resource Gateway stability analysis "
                    + run.stabilityRunId()
                    + " has no independently reconstructable statistical confidence",
                    java.util.Set.of(
                            TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V3,
                            TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V4,
                            TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V5),
                    run.schemaVersion());
        }
        if (!run.statisticalConfidenceSatisfied()) {
            throw new AssertionFailedError("Resource Gateway stability analysis "
                    + run.stabilityRunId() + " did not satisfy statistical confidence; status="
                    + run.statisticalAssessment().status(),
                    TestSuiteStabilityRun.StatisticalStatus.SATISFIED,
                    run.statisticalAssessment().status());
        }
    }

    /**
     * Requires exact trust, deterministic correctness, source promotion, and v3-v5 confidence.
     *
     * @param run statistical stability analysis to gate
     * @param verification offline verification result for the exact analysis
     */
    public static void assertStatisticalReleaseEligible(
            TestSuiteStabilityRun run,
            TestSuiteStabilityEvidenceVerifier.VerificationResult verification) {
        assertVerified(run, verification);
        assertStable(run);
        assertStatisticalConfidenceSatisfied(run);
        if (!run.statisticalPromotionEligible()) {
            throw new AssertionFailedError("Resource Gateway statistical stability analysis "
                    + run.stabilityRunId() + " is not promotion eligible; reasons="
                    + String.join(",", run.promotion().reasons()),
                    TestSuiteStabilityRun.PromotionStatus.ELIGIBLE,
                    run.promotion().status());
        }
    }

    private static void required(TestSuiteStabilityRun run) {
        if (run == null) {
            throw new AssertionFailedError(
                    "A Resource Gateway stability analysis is required", "non-null", null);
        }
    }
}
