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

    @Test
    void independentlyExcludesTheBaselineFromCorrectedTrialCount() {
        TestSuiteStabilityStatisticalPolicy policy = correctedPolicy(9_500, 1_000);

        assertThat(policy.minimumRequiredAttempts()).isEqualTo(30);
        assertThat(policy.comparisonAttempts(30)).isEqualTo(29);
        assertThat(policy.horizonSufficient(29)).isFalse();
        assertThat(policy.horizonSufficient(30)).isTrue();
    }

    @Test
    void independentlyReconstructsNonZeroExactRateBounds() {
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
    void rejectsTheLegacyConfidenceOverloadForTheCorrectedModel() {
        assertThatThrownBy(() -> correctedPolicy(9_500, 1_000)
                .achievedConfidenceBps(29))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Legacy zero-event confidence");
    }

    @Test
    void independentlyDerivesTheAnytimeValidEProcessBoundary() {
        TestSuiteStabilityStatisticalPolicy policy =
                TestSuiteStabilityStatisticalPolicy.anytimeValidEProcess(9_500, 1_000, 500);

        assertThat(policy.minimumRequiredAttempts()).isEqualTo(57);
        assertThat(policy.horizonSufficient(56)).isFalse();
        assertThat(policy.horizonSufficient(57)).isTrue();
        assertThat(policy.sequentialAdmissionSatisfied(55, 0)).isFalse();
        assertThat(policy.sequentialAchievedConfidenceBps(55, 0)).isEqualTo(9_488);
        assertThat(policy.sequentialAdmissionSatisfied(56, 0)).isTrue();
        assertThat(policy.sequentialAchievedConfidenceBps(56, 0)).isEqualTo(9_515);
        assertThat(policy.sequentialAdmissionSatisfied(59, 1)).isFalse();
        assertThat(policy.sequentialAdmissionSatisfied(99, 1)).isTrue();
    }

    @Test
    void independentlyRejectsInvalidAnytimePoliciesAndCoordinates() {
        assertThatThrownBy(() -> TestSuiteStabilityStatisticalPolicy
                .anytimeValidEProcess(9_500, 1_000, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> correctedPolicy(9_500, 1_000)
                .sequentialAdmissionSatisfied(29, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static TestSuiteStabilityStatisticalPolicy policy(
            int confidenceBps,
            int maximumRateBps) {
        return TestSuiteStabilityStatisticalPolicy.exactBinomial(
                confidenceBps, maximumRateBps);
    }

    private static TestSuiteStabilityStatisticalPolicy correctedPolicy(
            int confidenceBps,
            int maximumRateBps) {
        return TestSuiteStabilityStatisticalPolicy.baselineConditionalExactBinomial(
                confidenceBps, maximumRateBps);
    }
}
