package com.leanowtech.bloge.gateway.testing.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityStatisticalPolicyTest {

    @Test
    void derivesPublishedExactBinomialHorizonBoundaries() {
        assertThat(policy(9_500, 1_000).minimumRequiredAttempts()).isEqualTo(29);
        assertThat(policy(9_500, 500).minimumRequiredAttempts()).isEqualTo(59);
        assertThat(policy(9_500, 100).minimumRequiredAttempts()).isEqualTo(299);
        assertThat(policy(9_900, 100).minimumRequiredAttempts()).isEqualTo(459);
    }

    @Test
    void admitsTheExactMinimumAndRejectsOneFewerAttempt() {
        TestSuiteStabilityStatisticalPolicy policy = policy(9_500, 1_000);

        assertThat(policy.horizonSufficient(28)).isFalse();
        assertThat(policy.horizonSufficient(29)).isTrue();
        assertThat(policy.achievedConfidenceBps(28)).isLessThan(9_500);
        assertThat(policy.achievedConfidenceBps(29)).isGreaterThanOrEqualTo(9_500);
    }

    @Test
    void rejectsCoordinatesThatCannotFitTheBoundedGeneration() {
        TestSuiteStabilityStatisticalPolicy policy = policy(9_999, 1);

        assertThatThrownBy(policy::minimumRequiredAttempts)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bounded attempt horizon");
    }

    @Test
    void excludesTheObservedBaselineFromTheCorrectedExecutionHorizon() {
        TestSuiteStabilityStatisticalPolicy policy = correctedPolicy(9_500, 1_000);

        assertThat(policy.minimumRequiredAttempts()).isEqualTo(30);
        assertThat(policy.comparisonAttempts(30)).isEqualTo(29);
        assertThat(policy.horizonSufficient(29)).isFalse();
        assertThat(policy.horizonSufficient(30)).isTrue();
    }

    @Test
    void derivesConservativeNonZeroClopperPearsonRateBounds() {
        TestSuiteStabilityStatisticalPolicy policy = correctedPolicy(9_500, 1_000);

        assertThat(policy.upperInstabilityRateBps(29, 0)).isEqualTo(982);
        assertThat(policy.rateAdmissionSatisfied(29, 0)).isTrue();
        assertThat(policy.achievedConfidenceBps(29, 0)).isEqualTo(9_528);

        assertThat(policy.upperInstabilityRateBps(29, 1)).isEqualTo(1_534);
        assertThat(policy.rateAdmissionSatisfied(29, 1)).isFalse();
        assertThat(policy.achievedConfidenceBps(29, 1)).isEqualTo(8_011);

        assertThat(policy.upperInstabilityRateBps(59, 1)).isEqualTo(779);
        assertThat(policy.rateAdmissionSatisfied(59, 1)).isTrue();
        assertThat(policy.upperInstabilityRateBps(29, 29)).isEqualTo(10_000);
    }

    @Test
    void rejectsRateCoordinatesOnTheLegacyModelOrOutsideTheSample() {
        assertThatThrownBy(() -> policy(9_500, 1_000)
                .upperInstabilityRateBps(29, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> correctedPolicy(9_500, 1_000)
                .achievedConfidenceBps(29))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legacy zero-event confidence");
        assertThatThrownBy(() -> correctedPolicy(9_500, 1_000)
                .upperInstabilityRateBps(29, 30))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TestSuiteStabilityStatisticalPolicy policy(
            int confidenceBps,
            int instabilityBps) {
        return new TestSuiteStabilityStatisticalPolicy(
                TestSuiteStabilityStatisticalPolicy.Model.ZERO_INSTABILITY_EXACT_BINOMIAL,
                TestSuiteStabilityStatisticalPolicy.ClaimScope.SUITE_ATTEMPT_ANY_CASE,
                TestSuiteStabilityStatisticalPolicy.StoppingRule.PRECOMMITTED_FIXED_HORIZON,
                TestSuiteStabilityStatisticalPolicy.CensoringPolicy.FAIL_CLOSED,
                confidenceBps, instabilityBps);
    }

    private static TestSuiteStabilityStatisticalPolicy correctedPolicy(
            int confidenceBps,
            int instabilityBps) {
        return TestSuiteStabilityStatisticalPolicy.baselineConditionalExactBinomial(
                confidenceBps, instabilityBps);
    }
}
