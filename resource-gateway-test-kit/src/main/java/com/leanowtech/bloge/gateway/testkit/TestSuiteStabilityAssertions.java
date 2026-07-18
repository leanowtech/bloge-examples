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
        if (!run.promotionEligible()) {
            throw new AssertionFailedError("Resource Gateway stability analysis "
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
