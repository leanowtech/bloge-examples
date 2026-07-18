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
}
