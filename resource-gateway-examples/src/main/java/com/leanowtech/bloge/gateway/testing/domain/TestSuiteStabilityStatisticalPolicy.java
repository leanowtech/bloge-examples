package com.leanowtech.bloge.gateway.testing.domain;

import java.math.BigInteger;

/**
 * Precommitted exact-binomial policy for one statistical suite-stability analysis.
 *
 * <p>The policy treats a complete suite attempt as one trial and any change in its ordered semantic
 * outcome vector as one instability event. Horizon admission uses exact integer arithmetic, so
 * floating-point rounding cannot change a release decision.</p>
 *
 * @param model supported probability model
 * @param claimScope event multiplicity boundary
 * @param stoppingRule precommitted stopping discipline
 * @param censoringPolicy treatment of incomplete source or child evidence
 * @param confidenceLevelBps requested one-sided confidence in basis points
 * @param maximumInstabilityRateBps largest instability probability admitted by the claim
 */
public record TestSuiteStabilityStatisticalPolicy(
        Model model,
        ClaimScope claimScope,
        StoppingRule stoppingRule,
        CensoringPolicy censoringPolicy,
        int confidenceLevelBps,
        int maximumInstabilityRateBps
) {
    /** Minimum request horizon retained for compatibility with deterministic stability analysis. */
    public static final int MIN_ATTEMPTS = 3;
    /** Hard generation-one bound for statistical suite attempts. */
    public static final int MAX_ATTEMPTS = 1_000;
    /** Hard generation-one bound for attempt-by-case observations. */
    public static final int MAX_CASE_OBSERVATIONS = 10_000;
    /** Minimum accepted confidence target. */
    public static final int MIN_CONFIDENCE_BPS = 5_000;
    /** Maximum accepted confidence target, excluding an impossible certainty claim. */
    public static final int MAX_CONFIDENCE_BPS = 9_999;
    /** Minimum accepted instability ceiling. */
    public static final int MIN_INSTABILITY_RATE_BPS = 1;
    /** Maximum accepted instability ceiling. */
    public static final int MAX_INSTABILITY_RATE_BPS = 5_000;
    private static final BigInteger BASIS_POINTS = BigInteger.valueOf(10_000);

    /** Supported generation-one probability model. */
    public enum Model {
        /** Exact probability of zero events under a binomial event-rate threshold. */
        ZERO_INSTABILITY_EXACT_BINOMIAL
    }

    /** Scope at which one instability event is counted. */
    public enum ClaimScope {
        /** One attempt is unstable when any case changes its verified semantic outcome. */
        SUITE_ATTEMPT_ANY_CASE
    }

    /** Sampling stop discipline. */
    public enum StoppingRule {
        /** The complete request horizon is frozen before and executed despite intermediate results. */
        PRECOMMITTED_FIXED_HORIZON
    }

    /** Incomplete-observation treatment. */
    public enum CensoringPolicy {
        /** One incomplete attempt blocks the statistical conclusion and remains in the denominator. */
        FAIL_CLOSED
    }

    /** Validates the only supported generation-one model and bounded coordinates. */
    public TestSuiteStabilityStatisticalPolicy {
        if (model != Model.ZERO_INSTABILITY_EXACT_BINOMIAL
                || claimScope != ClaimScope.SUITE_ATTEMPT_ANY_CASE
                || stoppingRule != StoppingRule.PRECOMMITTED_FIXED_HORIZON
                || censoringPolicy != CensoringPolicy.FAIL_CLOSED
                || confidenceLevelBps < MIN_CONFIDENCE_BPS
                || confidenceLevelBps > MAX_CONFIDENCE_BPS
                || maximumInstabilityRateBps < MIN_INSTABILITY_RATE_BPS
                || maximumInstabilityRateBps > MAX_INSTABILITY_RATE_BPS) {
            throw new IllegalArgumentException(
                    "A supported bounded statistical stability policy is required");
        }
    }

    /**
     * Returns the first bounded horizon satisfying the exact one-sided zero-event inequality.
     *
     * @return minimum admitted clean-attempt horizon
     * @throws IllegalArgumentException when the requested coordinates need more than 1000 attempts
     */
    public int minimumRequiredAttempts() {
        for (int attempts = MIN_ATTEMPTS; attempts <= MAX_ATTEMPTS; attempts++) {
            if (horizonSufficient(attempts)) {
                return attempts;
            }
        }
        throw new IllegalArgumentException(
                "Statistical stability target exceeds the bounded attempt horizon");
    }

    /**
     * Tests the exact inequality {@code (1-q)^n <= 1-C} without floating-point arithmetic.
     *
     * @param attempts proposed fixed horizon
     * @return true when the horizon supports the requested confidence claim
     */
    public boolean horizonSufficient(int attempts) {
        if (attempts < 0 || attempts > MAX_ATTEMPTS) {
            return false;
        }
        BigInteger noEventNumerator = BigInteger.valueOf(
                10_000L - maximumInstabilityRateBps).pow(attempts);
        BigInteger denominator = BASIS_POINTS.pow(attempts);
        BigInteger alphaBps = BigInteger.valueOf(10_000L - confidenceLevelBps);
        return noEventNumerator.multiply(BASIS_POINTS)
                .compareTo(alphaBps.multiply(denominator)) <= 0;
    }

    /**
     * Computes the conservative basis-point confidence implied by a clean fixed horizon.
     *
     * @param cleanAttempts verified attempts with zero observed instability events
     * @return floor of {@code 1 - (1-q)^n}, expressed in basis points
     */
    public int achievedConfidenceBps(int cleanAttempts) {
        if (cleanAttempts < 0 || cleanAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Clean attempt count is outside protocol bounds");
        }
        BigInteger noEventNumerator = BigInteger.valueOf(
                10_000L - maximumInstabilityRateBps).pow(cleanAttempts);
        BigInteger denominator = BASIS_POINTS.pow(cleanAttempts);
        return denominator.subtract(noEventNumerator).multiply(BASIS_POINTS)
                .divide(denominator).intValueExact();
    }
}
