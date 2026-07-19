package com.leanowtech.bloge.gateway.testkit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityObservationLedgerLifecycleRequestTest {
    @Test
    void firstPageAndPinnedContinuationAreStrictSchemaValues() {
        var first = TestSuiteStabilityObservationLedgerLifecycleRequest.firstPage(
                TestSuiteStabilityTestFixtures.SUITE_ID,
                TestSuiteStabilityTestFixtures.SUITE_REVISION,
                TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT, 5);
        var fixture = TestSuiteStabilityObservationLedgerLifecycleTestFixtures.stableFixture();

        assertThat(first.afterRetirementGeneration()).isZero();
        assertThat(first.expectedCurrentFloorFingerprint()).isEmpty();
        assertThat(first.toJson().path("schemaVersion").asText()).isEqualTo(
                TestingProtocol.TEST_SUITE_STABILITY_OBSERVATION_LIFECYCLE_REQUEST_V1);
        var continuation = new TestSuiteStabilityObservationLedgerLifecycleRequest(
                first.suiteId(), first.revision(), first.fingerprint(), 1, 5,
                fixture.page().currentFloor().floorFingerprint(),
                fixture.page().head().headFingerprint());
        assertThat(continuation)
                .satisfies(value -> {
                    assertThat(value.afterRetirementGeneration()).isEqualTo(1);
                    assertThat(value.expectedCurrentFloorFingerprint()).isEqualTo(
                            fixture.page().currentFloor().floorFingerprint());
                    assertThat(value.expectedHeadFingerprint()).isEqualTo(
                            fixture.page().head().headFingerprint());
                });
        assertThatThrownBy(() -> first.continueAfter(fixture.page()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-terminal");
    }

    @Test
    void rejectsPinnedRolloutUnpinnedContinuationAndOversizedPage() {
        assertThatThrownBy(() -> new TestSuiteStabilityObservationLedgerLifecycleRequest(
                TestSuiteStabilityTestFixtures.SUITE_ID,
                TestSuiteStabilityTestFixtures.SUITE_REVISION,
                TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT, 0, 1,
                "sha256:" + "a".repeat(64), ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TestSuiteStabilityObservationLedgerLifecycleRequest(
                TestSuiteStabilityTestFixtures.SUITE_ID,
                TestSuiteStabilityTestFixtures.SUITE_REVISION,
                TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT, 1, 1, "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestSuiteStabilityObservationLedgerLifecycleRequest.firstPage(
                TestSuiteStabilityTestFixtures.SUITE_ID,
                TestSuiteStabilityTestFixtures.SUITE_REVISION,
                TestSuiteStabilityTestFixtures.SUITE_FINGERPRINT, 11))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
