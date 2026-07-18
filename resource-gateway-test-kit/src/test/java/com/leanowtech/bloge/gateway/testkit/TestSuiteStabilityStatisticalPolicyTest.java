package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityStatisticalPolicyTest {

    @Test
    void derivesCanonicalExactBinomialHorizonsWithoutFloatingPointRounding() {
        assertThat(policy(9_500, 1_000).minimumRequiredAttempts()).isEqualTo(29);
        assertThat(policy(9_500, 500).minimumRequiredAttempts()).isEqualTo(59);
        assertThat(policy(9_500, 100).minimumRequiredAttempts()).isEqualTo(299);
        assertThat(policy(9_900, 100).minimumRequiredAttempts()).isEqualTo(459);
    }

    @Test
    void distinguishesTheExactBoundaryAndConservativelyFloorsDisplayedConfidence() {
        TestSuiteStabilityStatisticalPolicy policy = policy(9_500, 1_000);

        assertThat(policy.horizonSufficient(28)).isFalse();
        assertThat(policy.horizonSufficient(29)).isTrue();
        assertThat(policy.achievedConfidenceBps(29)).isGreaterThanOrEqualTo(9_500);
        assertThat(policy.achievedConfidenceBps(28)).isLessThan(9_500);
    }

    @Test
    void rejectsUnsupportedOrUnboundedProbabilityCoordinates() {
        assertThatThrownBy(() -> policy(4_999, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(9_500, 5_001))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(policy(9_500, 1_000).horizonSufficient(1_001)).isFalse();
    }

    private static TestSuiteStabilityStatisticalPolicy policy(
            int confidenceBps,
            int maximumRateBps) {
        return TestSuiteStabilityStatisticalPolicy.exactBinomial(
                confidenceBps, maximumRateBps);
    }
}
