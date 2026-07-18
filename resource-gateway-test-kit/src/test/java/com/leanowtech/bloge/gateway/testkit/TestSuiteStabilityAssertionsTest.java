package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteStabilityAssertionsTest {

    @Test
    void acceptsOnlyStablePromotionEligibleAndExactlyVerifiedEvidence() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        TestSuiteStabilityRun run = fixture.run();
        TestSuiteStabilityEvidenceVerifier.VerificationResult verification =
                verifier().verify(run, fixture.keySet(), fixture.keySet().snapshotFingerprint());

        assertThatNoException().isThrownBy(() -> {
            TestSuiteStabilityAssertions.assertStable(run);
            TestSuiteStabilityAssertions.assertVerified(run, verification);
            TestSuiteStabilityAssertions.assertReleaseEligible(run, verification);
        });
    }

    @Test
    void rejectsFlakyEvidenceAndMismatchedOrInvalidVerificationResults() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        ObjectNode response = fixture.copyResponse();
        TestSuiteStabilityTestFixtures.makeFlaky(response, fixture.keyPair());
        TestSuiteStabilityRun flaky = TestSuiteStabilityRun.from(response);
        TestSuiteStabilityEvidenceVerifier.VerificationResult verified =
                verifier().verify(flaky, fixture.keySet(), fixture.keySet().snapshotFingerprint());
        TestSuiteStabilityEvidenceVerifier.VerificationResult mismatched =
                new TestSuiteStabilityEvidenceVerifier.VerificationResult(
                        TestSuiteStabilityEvidenceVerifier.Outcome.VERIFIED, "VERIFIED",
                        "stability-" + "8".repeat(64), fixture.key().keyId());

        assertThatThrownBy(() -> TestSuiteStabilityAssertions.assertReleaseEligible(
                flaky, verified)).isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("not stable");
        assertThatThrownBy(() -> TestSuiteStabilityAssertions.assertVerified(flaky, mismatched))
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("not independently verified");
        assertThatThrownBy(() -> TestSuiteStabilityAssertions.assertVerified(flaky, null))
                .isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("outcome=MISSING");
    }

    @Test
    void keepsStableSourceBlockedEvidenceVerifiableButNotReleaseEligible() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        ObjectNode response = fixture.copyResponse();
        TestSuiteStabilityTestFixtures.blockSourcePromotion(response, fixture.keyPair());
        TestSuiteStabilityRun run = TestSuiteStabilityRun.from(response);
        TestSuiteStabilityEvidenceVerifier.VerificationResult verification =
                verifier().verify(run, fixture.keySet(), fixture.keySet().snapshotFingerprint());

        assertThat(run.stable()).isTrue();
        assertThat(run.promotion().status())
                .isEqualTo(TestSuiteStabilityRun.PromotionStatus.BLOCKED);
        assertThat(run.promotion().reasons())
                .containsExactly("SOURCE_SUITE_PROMOTION_BLOCKED");
        assertThat(verification.verified()).isTrue();
        assertThatThrownBy(() -> TestSuiteStabilityAssertions.assertReleaseEligible(
                run, verification)).isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("not promotion eligible");
    }

    @Test
    void verifiesLegacyV1ForAuditButFailsClosedAtTheReleaseGate() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        ObjectNode response = fixture.copyResponse();
        TestSuiteStabilityTestFixtures.downgradeToLegacyV1(response, fixture.keyPair());
        TestSuiteStabilityRun run = TestSuiteStabilityRun.from(response);
        TestSuiteStabilityEvidenceVerifier.VerificationResult verification =
                verifier().verify(run, fixture.keySet(), fixture.keySet().snapshotFingerprint());

        assertThat(run.stable()).isTrue();
        assertThat(run.promotion().status())
                .isEqualTo(TestSuiteStabilityRun.PromotionStatus.ELIGIBLE);
        assertThat(run.sourcePromotionClosureAvailable()).isFalse();
        assertThat(run.promotionEligible()).isFalse();
        assertThat(verification.verified()).isTrue();
        assertThatThrownBy(() -> TestSuiteStabilityAssertions.assertReleaseEligible(
                run, verification)).isInstanceOf(AssertionFailedError.class)
                .hasMessageContaining("v2 source-promotion closure is required");
    }

    private static TestSuiteStabilityEvidenceVerifier verifier() {
        return new TestSuiteStabilityEvidenceVerifier(Clock.fixed(
                TestSuiteStabilityTestFixtures.SIGNED_AT, ZoneOffset.UTC));
    }
}
