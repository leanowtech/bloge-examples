package com.leanowtech.bloge.gateway.testkit;

import java.math.BigInteger;

/**
 * Independently evaluated exact-binomial policy for statistical suite stability.
 *
 * <p>The test-kit deliberately owns this implementation instead of depending on server classes.
 * The legacy model counts complete attempts directly; the corrected model reserves the first
 * verified attempt as a baseline and counts only later comparisons. Exact integer arithmetic
 * prevents floating-point rounding from changing a CI decision.</p>
 *
 * @param model supported probability model
 * @param claimScope event-counting boundary
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
    /** Minimum protocol horizon. */
    public static final int MIN_ATTEMPTS = 3;
    /** Maximum statistical protocol horizon. */
    public static final int MAX_ATTEMPTS = 1_000;
    /** Maximum attempt-by-case work admitted by the server protocol. */
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

    /** Supported probability model generations. */
    public enum Model {
        /** Exact probability of zero events under a binomial event-rate threshold. */
        ZERO_INSTABILITY_EXACT_BINOMIAL,
        /** Exact one-sided rate bound conditional on the first verified attempt as baseline. */
        BASELINE_CONDITIONAL_EXACT_BINOMIAL
    }

    /** Scope at which one instability event is counted. */
    public enum ClaimScope {
        /** One suite attempt is unstable when any case outcome differs from the baseline vector. */
        SUITE_ATTEMPT_ANY_CASE
    }

    /** Sampling stop discipline. */
    public enum StoppingRule {
        /** The complete request horizon is frozen before execution and cannot stop early. */
        PRECOMMITTED_FIXED_HORIZON
    }

    /** Incomplete-observation treatment. */
    public enum CensoringPolicy {
        /** One incomplete attempt blocks confidence and remains in the observed denominator. */
        FAIL_CLOSED
    }

    /** Validates the supported bounded fixed-horizon models. */
    public TestSuiteStabilityStatisticalPolicy {
        if (model == null
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
     * Creates the generation-one fixed-horizon policy.
     *
     * @param confidenceLevelBps requested one-sided confidence in basis points
     * @param maximumInstabilityRateBps admitted instability-rate ceiling in basis points
     * @return validated exact-binomial policy
     */
    public static TestSuiteStabilityStatisticalPolicy exactBinomial(
            int confidenceLevelBps,
            int maximumInstabilityRateBps) {
        return new TestSuiteStabilityStatisticalPolicy(
                Model.ZERO_INSTABILITY_EXACT_BINOMIAL,
                ClaimScope.SUITE_ATTEMPT_ANY_CASE,
                StoppingRule.PRECOMMITTED_FIXED_HORIZON,
                CensoringPolicy.FAIL_CLOSED,
                confidenceLevelBps,
                maximumInstabilityRateBps);
    }

    /**
     * Creates the corrected baseline-conditional fixed-horizon policy.
     *
     * @param confidenceLevelBps requested one-sided confidence in basis points
     * @param maximumInstabilityRateBps admitted conditional instability-rate ceiling
     * @return validated baseline-conditional policy
     */
    public static TestSuiteStabilityStatisticalPolicy baselineConditionalExactBinomial(
            int confidenceLevelBps,
            int maximumInstabilityRateBps) {
        return new TestSuiteStabilityStatisticalPolicy(
                Model.BASELINE_CONDITIONAL_EXACT_BINOMIAL,
                ClaimScope.SUITE_ATTEMPT_ANY_CASE,
                StoppingRule.PRECOMMITTED_FIXED_HORIZON,
                CensoringPolicy.FAIL_CLOSED,
                confidenceLevelBps,
                maximumInstabilityRateBps);
    }

    /**
     * Returns the first bounded horizon satisfying the exact one-sided zero-event inequality.
     *
     * @return minimum admitted clean-attempt horizon
     * @throws IllegalArgumentException when the coordinates need more than 1000 attempts
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
     * Tests {@code (1-q)^n <= 1-C} with exact integer arithmetic.
     *
     * @param attempts proposed fixed horizon
     * @return true when the horizon supports the requested confidence claim
     */
    public boolean horizonSufficient(int attempts) {
        if (attempts < 0 || attempts > MAX_ATTEMPTS) {
            return false;
        }
        int comparisons = comparisonAttempts(attempts);
        BigInteger noEventNumerator = BigInteger.valueOf(
                10_000L - maximumInstabilityRateBps).pow(comparisons);
        BigInteger denominator = BASIS_POINTS.pow(comparisons);
        BigInteger alphaBps = BigInteger.valueOf(10_000L - confidenceLevelBps);
        return noEventNumerator.multiply(BASIS_POINTS)
                .compareTo(alphaBps.multiply(denominator)) <= 0;
    }

    /**
     * Returns the event-comparison trials represented by an execution horizon.
     *
     * @param executionAttempts complete suite execution count
     * @return legacy trial count or baseline-excluded comparison count
     */
    public int comparisonAttempts(int executionAttempts) {
        if (executionAttempts < 0 || executionAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Execution attempt count is outside protocol bounds");
        }
        return model == Model.BASELINE_CONDITIONAL_EXACT_BINOMIAL
                ? Math.max(0, executionAttempts - 1) : executionAttempts;
    }

    /**
     * Computes the legacy zero-event confidence implied by a clean fixed horizon.
     *
     * <p>This overload belongs only to {@link Model#ZERO_INSTABILITY_EXACT_BINOMIAL}. The
     * baseline-conditional model must call {@link #achievedConfidenceBps(int, int)} so the first
     * verified attempt cannot be counted as a comparison trial.</p>
     *
     * @param cleanAttempts verified attempts with zero observed instability events
     * @return floor of {@code 1 - (1-q)^n}, expressed in basis points
     */
    public int achievedConfidenceBps(int cleanAttempts) {
        if (model != Model.ZERO_INSTABILITY_EXACT_BINOMIAL) {
            throw new IllegalStateException(
                    "Legacy zero-event confidence requires ZERO_INSTABILITY_EXACT_BINOMIAL");
        }
        if (cleanAttempts < 0 || cleanAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("Clean attempt count is outside protocol bounds");
        }
        BigInteger noEventNumerator = BigInteger.valueOf(
                10_000L - maximumInstabilityRateBps).pow(cleanAttempts);
        BigInteger denominator = BASIS_POINTS.pow(cleanAttempts);
        return denominator.subtract(noEventNumerator).multiply(BASIS_POINTS)
                .divide(denominator).intValueExact();
    }

    /**
     * Independently tests the exact one-sided configured rate ceiling.
     *
     * @param comparisons verified post-baseline comparison trials
     * @param observedEvents comparisons differing from the baseline vector
     * @return true when the Clopper-Pearson upper bound admits the configured ceiling
     */
    public boolean rateAdmissionSatisfied(int comparisons, int observedEvents) {
        requireRateCoordinates(comparisons, observedEvents);
        return cdfNumerator(comparisons, observedEvents, maximumInstabilityRateBps)
                .multiply(BASIS_POINTS)
                .compareTo(BigInteger.valueOf(10_000L - confidenceLevelBps)
                        .multiply(BASIS_POINTS.pow(comparisons))) <= 0;
    }

    /**
     * Independently computes the conservative confidence floor against the configured ceiling.
     *
     * @param comparisons verified post-baseline comparison trials
     * @param observedEvents comparisons differing from the baseline vector
     * @return floor of {@code 1 - P_q(X <= observedEvents)} in basis points
     */
    public int achievedConfidenceBps(int comparisons, int observedEvents) {
        requireRateCoordinates(comparisons, observedEvents);
        BigInteger denominator = BASIS_POINTS.pow(comparisons);
        BigInteger cdf = cdfNumerator(
                comparisons, observedEvents, maximumInstabilityRateBps);
        return denominator.subtract(cdf).multiply(BASIS_POINTS)
                .divide(denominator).intValueExact();
    }

    /**
     * Independently computes the upward-rounded one-sided exact upper rate bound.
     *
     * @param comparisons verified post-baseline comparison trials
     * @param observedEvents comparisons differing from the baseline vector
     * @return conservative Clopper-Pearson upper rate bound in basis points
     */
    public int upperInstabilityRateBps(int comparisons, int observedEvents) {
        requireRateCoordinates(comparisons, observedEvents);
        if (observedEvents == comparisons) {
            return 10_000;
        }
        BigInteger denominator = BASIS_POINTS.pow(comparisons);
        BigInteger alpha = BigInteger.valueOf(10_000L - confidenceLevelBps)
                .multiply(denominator);
        int lower = 0;
        int upper = 10_000;
        while (lower < upper) {
            int candidate = lower + (upper - lower) / 2;
            boolean admitted = cdfNumerator(comparisons, observedEvents, candidate)
                    .multiply(BASIS_POINTS).compareTo(alpha) <= 0;
            if (admitted) {
                upper = candidate;
            } else {
                lower = candidate + 1;
            }
        }
        return lower;
    }

    private void requireRateCoordinates(int comparisons, int observedEvents) {
        if (model != Model.BASELINE_CONDITIONAL_EXACT_BINOMIAL
                || comparisons < 1 || comparisons >= MAX_ATTEMPTS
                || observedEvents < 0 || observedEvents > comparisons) {
            throw new IllegalArgumentException(
                    "Complete baseline-conditional event coordinates are required");
        }
    }

    private static BigInteger cdfNumerator(int trials, int events, int rateBps) {
        BigInteger rate = BigInteger.valueOf(rateBps);
        BigInteger complement = BASIS_POINTS.subtract(rate);
        BigInteger[] ratePowers = powers(rate, trials);
        BigInteger[] complementPowers = powers(complement, trials);
        BigInteger combination = BigInteger.ONE;
        BigInteger sum = BigInteger.ZERO;
        for (int index = 0; index <= events; index++) {
            sum = sum.add(combination.multiply(ratePowers[index])
                    .multiply(complementPowers[trials - index]));
            if (index < events) {
                combination = combination.multiply(BigInteger.valueOf(trials - index))
                        .divide(BigInteger.valueOf(index + 1L));
            }
        }
        return sum;
    }

    private static BigInteger[] powers(BigInteger value, int maximumExponent) {
        BigInteger[] result = new BigInteger[maximumExponent + 1];
        result[0] = BigInteger.ONE;
        for (int exponent = 1; exponent <= maximumExponent; exponent++) {
            result[exponent] = result[exponent - 1].multiply(value);
        }
        return result;
    }
}
