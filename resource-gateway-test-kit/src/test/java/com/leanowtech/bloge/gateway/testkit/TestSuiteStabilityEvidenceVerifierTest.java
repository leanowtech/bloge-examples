package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityEvidenceVerifierTest {

    @Test
    void independentlyReconstructsAndVerifiesStatisticalV3Evidence() {
        TestSuiteStabilityTestFixtures.Fixture fixture =
                TestSuiteStabilityTestFixtures.statisticalFixture();
        TestSuiteStabilityRun run = fixture.run();

        TestSuiteStabilityEvidenceVerifier.VerificationResult result =
                verifier().verify(run, fixture.key());

        assertThat(result.verified()).isTrue();
        assertThat(run.statisticalConfidenceAvailable()).isTrue();
        assertThat(run.statisticalConfidenceSatisfied()).isTrue();
        assertThat(run.statisticalPromotionEligible()).isTrue();
        assertThat(run.statisticalAssessment().requiredAttempts()).isEqualTo(29);
        assertThat(run.statisticalAssessment().observedAttempts()).isEqualTo(29);
        assertThat(run.statisticalAssessment().verifiedAttempts()).isEqualTo(29);
        assertThat(run.statisticalAssessment().observedInstabilityEvents()).isZero();
        assertThat(run.statisticalAssessment().achievedConfidenceBps())
                .isGreaterThanOrEqualTo(9_500);
    }

    @Test
    void independentlyReconstructsV4NonZeroRateWithoutLaunderingFlakiness() {
        TestSuiteStabilityTestFixtures.Fixture fixture =
                TestSuiteStabilityTestFixtures.rateFixture();
        TestSuiteStabilityRun run = fixture.run();

        TestSuiteStabilityEvidenceVerifier.VerificationResult result =
                verifier().verify(run, fixture.key());

        assertThat(result.verified()).isTrue();
        assertThat(run.schemaVersion())
                .isEqualTo(TestingProtocol.TEST_SUITE_STABILITY_EXECUTION_RESPONSE_V4);
        assertThat(run.status()).isEqualTo(TestSuiteStabilityRun.Status.FLAKY);
        assertThat(run.statisticalAssessment())
                .extracting(TestSuiteStabilityRun.StatisticalAssessment::requiredAttempts,
                        TestSuiteStabilityRun.StatisticalAssessment::observedAttempts,
                        TestSuiteStabilityRun.StatisticalAssessment::comparisonAttempts,
                        TestSuiteStabilityRun.StatisticalAssessment::observedInstabilityEvents,
                        TestSuiteStabilityRun.StatisticalAssessment::upperInstabilityRateBps,
                        TestSuiteStabilityRun.StatisticalAssessment::status)
                .containsExactly(30, 60, 59, 1, 779,
                        TestSuiteStabilityRun.StatisticalStatus.SATISFIED);
        assertThat(run.statisticalConfidenceSatisfied()).isTrue();
        assertThat(run.statisticalPromotionEligible()).isFalse();
        assertThat(run.promotion().reasons()).containsExactly("FLAKY_CASE_OBSERVED");
        assertThat(run.quarantineRequired()).isTrue();
    }

    @Test
    void rejectsResignedProducerV4UpperRateArithmeticThatDoesNotReconstruct() {
        TestSuiteStabilityTestFixtures.Fixture fixture =
                TestSuiteStabilityTestFixtures.rateFixture();
        ObjectNode response = fixture.copyResponse();
        ((ObjectNode) response.at("/evidence/statisticalAssessment"))
                .put("upperInstabilityRateBps", 1);
        TestSuiteStabilityTestFixtures.seal(response, fixture.keyPair(), false);

        assertThatThrownBy(() -> TestSuiteStabilityRun.from(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate");
    }

    @Test
    void rejectsResignedProducerStatisticalArithmeticThatDoesNotReconstruct() {
        TestSuiteStabilityTestFixtures.Fixture fixture =
                TestSuiteStabilityTestFixtures.statisticalFixture();
        ObjectNode response = fixture.copyResponse();
        ((ObjectNode) response.at("/evidence/statisticalAssessment"))
                .put("achievedConfidenceBps", 9_999);
        TestSuiteStabilityTestFixtures.seal(response, fixture.keyPair(), false);

        assertThatThrownBy(() -> TestSuiteStabilityRun.from(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate");
    }

    @Test
    void preservesNegativeProofWhenAStatisticalAttemptVectorChanges() {
        TestSuiteStabilityTestFixtures.Fixture fixture =
                TestSuiteStabilityTestFixtures.statisticalFixture();
        ObjectNode response = fixture.copyResponse();
        TestSuiteStabilityTestFixtures.makeStatisticalFlaky(response, fixture.keyPair());

        TestSuiteStabilityRun run = TestSuiteStabilityRun.from(response);
        TestSuiteStabilityEvidenceVerifier.VerificationResult result =
                verifier().verify(run, fixture.key());

        assertThat(result.verified()).isTrue();
        assertThat(run.statisticalAssessment().status())
                .isEqualTo(TestSuiteStabilityRun.StatisticalStatus.REJECTED);
        assertThat(run.statisticalAssessment().observedInstabilityEvents()).isEqualTo(1);
        assertThat(run.statisticalConfidenceSatisfied()).isFalse();
        assertThat(run.promotion().reasons())
                .containsExactly("FLAKY_CASE_OBSERVED", "STATISTICAL_CONFIDENCE_REJECTED");
    }

    @Test
    void failsClosedWhenOneAttemptIsCensoredWithoutRepairingTheDenominator() {
        TestSuiteStabilityTestFixtures.Fixture fixture =
                TestSuiteStabilityTestFixtures.statisticalFixture();
        ObjectNode response = fixture.copyResponse();
        TestSuiteStabilityTestFixtures.makeStatisticalCensored(response, fixture.keyPair());

        TestSuiteStabilityRun run = TestSuiteStabilityRun.from(response);

        assertThat(verifier().verify(run, fixture.key()).verified()).isTrue();
        assertThat(run.statisticalAssessment().status())
                .isEqualTo(TestSuiteStabilityRun.StatisticalStatus.INCONCLUSIVE);
        assertThat(run.statisticalAssessment().observedAttempts()).isEqualTo(29);
        assertThat(run.statisticalAssessment().verifiedAttempts()).isEqualTo(28);
        assertThat(run.statisticalAssessment().censoredAttempts()).isEqualTo(1);
        assertThat(run.statisticalAssessment().achievedConfidenceBps()).isZero();
        assertThat(run.promotion().reasons()).containsExactly(
                "STABILITY_EVIDENCE_INCOMPLETE", "STATISTICAL_CONFIDENCE_INCONCLUSIVE");
    }

    @Test
    void keepsRepeatabilityConfidenceOrthogonalToConsistentBusinessFailure() {
        TestSuiteStabilityTestFixtures.Fixture fixture =
                TestSuiteStabilityTestFixtures.statisticalFixture();
        ObjectNode response = fixture.copyResponse();
        TestSuiteStabilityTestFixtures.makeStatisticalConsistentFailure(
                response, fixture.keyPair());

        TestSuiteStabilityRun run = TestSuiteStabilityRun.from(response);

        assertThat(verifier().verify(run, fixture.key()).verified()).isTrue();
        assertThat(run.status()).isEqualTo(TestSuiteStabilityRun.Status.CONSISTENT_FAILURE);
        assertThat(run.statisticalConfidenceSatisfied()).isTrue();
        assertThat(run.promotionEligible()).isFalse();
        assertThat(run.promotion().reasons()).containsExactly("CONSISTENT_TEST_FAILURE");
    }

    @Test
    void projectsStableEvidenceAndVerifiesItsRealDetachedSignature() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        TestSuiteStabilityRun run = fixture.run();

        TestSuiteStabilityEvidenceVerifier.VerificationResult result =
                verifier().verify(run, fixture.key());

        assertThat(run.status()).isEqualTo(TestSuiteStabilityRun.Status.STABLE);
        assertThat(run.stable()).isTrue();
        assertThat(run.promotionEligible()).isTrue();
        assertThat(run.quarantineRequired()).isFalse();
        assertThat(result.verified()).isTrue();
        assertThat(result.reasonCode()).isEqualTo("VERIFIED");
    }

    @Test
    void rejectsCryptographicallySignedAggregateContradictions() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        ObjectNode response = fixture.copyResponse();
        ((ObjectNode) response.at("/evidence/promotion")).put("stableCases", 0);
        TestSuiteStabilityTestFixtures.seal(response, fixture.keyPair(), false);

        assertThatThrownBy(() -> TestSuiteStabilityRun.from(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate");
    }

    @Test
    void rejectsResignedEligibilityThatIgnoresABlockedSourceSuite() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        ObjectNode response = fixture.copyResponse();
        ObjectNode attempt = (ObjectNode) response.at("/evidence/attempts/1");
        attempt.put("sourcePromotionStatus", "BLOCKED");
        attempt.putArray("sourcePromotionReasons").add("NO_CERTIFIABLE_CASES");
        TestSuiteStabilityTestFixtures.seal(response, fixture.keyPair(), true);

        assertThatThrownBy(() -> TestSuiteStabilityRun.from(response))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate");
    }

    @Test
    void rejectsResignedIdentityTimeWindowAndDiagnosticContradictions() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        ObjectNode wrongIdentity = fixture.copyResponse();
        ((ObjectNode) wrongIdentity.path("evidence"))
                .put("stabilityRunId", "stability-" + "3".repeat(64));
        TestSuiteStabilityTestFixtures.seal(wrongIdentity, fixture.keyPair(), false);
        assertThatThrownBy(() -> TestSuiteStabilityRun.from(wrongIdentity))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate");

        ObjectNode wrongTime = fixture.copyResponse();
        ((ObjectNode) wrongTime.path("evidence"))
                .put("startedAt", TestSuiteStabilityTestFixtures.SIGNED_AT
                        .minusSeconds(139).toString());
        TestSuiteStabilityTestFixtures.seal(wrongTime, fixture.keyPair(), false);
        assertThatThrownBy(() -> TestSuiteStabilityRun.from(wrongTime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate");

        ObjectNode wrongDiagnostics = fixture.copyResponse();
        ((ArrayNode) wrongDiagnostics.at("/evidence/diagnostics")).add("FORGED_DIAGNOSTIC");
        TestSuiteStabilityTestFixtures.seal(wrongDiagnostics, fixture.keyPair(), false);
        assertThatThrownBy(() -> TestSuiteStabilityRun.from(wrongDiagnostics))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("aggregate");
    }

    @Test
    void rejectsDuplicateSourceAndCrossCaseChildSamplesEvenWhenResigned() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        ObjectNode duplicateSource = fixture.copyResponse();
        ((ObjectNode) duplicateSource.at("/evidence/attempts/1"))
                .put("suiteRunId", "suite-run-1");
        TestSuiteStabilityTestFixtures.seal(duplicateSource, fixture.keyPair(), true);

        assertThatThrownBy(() -> TestSuiteStabilityRun.from(duplicateSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("independent ordered samples");

        ObjectNode duplicateChild = fixture.copyResponse();
        ObjectNode secondCase = ((ObjectNode) duplicateChild.at("/evidence/caseResults/0"))
                .deepCopy();
        secondCase.put("caseId", "regression");
        ArrayNode observations = (ArrayNode) secondCase.path("observations");
        observations.forEach(value -> ((ObjectNode) value).put("runId",
                "regression-child-" + value.path("attempt").asInt()));
        ((ObjectNode) observations.get(1)).put("runId", "child-run-1");
        ((ArrayNode) duplicateChild.at("/evidence/caseResults")).add(secondCase);
        ((ObjectNode) duplicateChild.at("/evidence/promotion")).put("stableCases", 2);
        TestSuiteStabilityTestFixtures.seal(duplicateChild, fixture.keyPair(), false);

        assertThatThrownBy(() -> TestSuiteStabilityRun.from(duplicateChild))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("child observations");
    }

    @Test
    void rejectsEvidenceSourceClosureAndSignatureTampering() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        ObjectNode evidenceMutation = fixture.copyResponse();
        ((ObjectNode) evidenceMutation.at("/evidence/metadata")).put("pipeline", "tampered");
        assertThatThrownBy(() -> TestSuiteStabilityRun.from(evidenceMutation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");

        ObjectNode closureMutation = fixture.copyResponse();
        ((ObjectNode) closureMutation.at("/attestation/sourceSuiteEvidenceRefs/1"))
                .put("suiteRunId", "different-source");
        assertThatThrownBy(() -> TestSuiteStabilityRun.from(closureMutation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source closure");

        ObjectNode signatureMutation = fixture.copyResponse();
        ((ObjectNode) signatureMutation.path("attestation")).put("signature", "AA==");
        TestSuiteStabilityRun run = TestSuiteStabilityRun.from(signatureMutation);
        assertThat(verifier().verify(run, fixture.key()).outcome())
                .isEqualTo(TestSuiteStabilityEvidenceVerifier.Outcome.INVALID);
    }

    @Test
    void failsClosedForMissingWrongAndRevokedVerificationKeys() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();
        TestSuiteStabilityRun run = fixture.run();
        EvidenceVerificationKey wrong = new EvidenceVerificationKey(
                fixture.key().schemaVersion(), "wrong-key", fixture.key().algorithm(),
                fixture.key().encodedPublicKey(), fixture.key().createdAt(), "ACTIVE", "test");
        EvidenceVerificationKey revoked = new EvidenceVerificationKey(
                fixture.key().schemaVersion(), fixture.key().keyId(), fixture.key().algorithm(),
                fixture.key().encodedPublicKey(), fixture.key().createdAt(), "REVOKED", "test");

        assertThat(verifier().verify(run, (EvidenceVerificationKey) null).outcome())
                .isEqualTo(TestSuiteStabilityEvidenceVerifier.Outcome.KEY_UNAVAILABLE);
        assertThat(verifier().verify(run, wrong).reasonCode())
                .isEqualTo("VERIFICATION_KEY_ID_MISMATCH");
        assertThat(verifier().verify(run, revoked).outcome())
                .isEqualTo(TestSuiteStabilityEvidenceVerifier.Outcome.POLICY_REJECTED);
    }

    @Test
    void verifiesAgainstAnExternallyPinnedCompleteKeySet() {
        TestSuiteStabilityTestFixtures.Fixture fixture = TestSuiteStabilityTestFixtures.fixture();

        TestSuiteStabilityEvidenceVerifier.VerificationResult result = verifier().verify(
                fixture.run(), fixture.keySet(), fixture.keySet().snapshotFingerprint());

        assertThat(result.verified()).isTrue();
        assertThat(verifier().verify(fixture.run(), fixture.keySet(),
                "sha256:" + "9".repeat(64)).verified()).isFalse();
    }

    private static TestSuiteStabilityEvidenceVerifier verifier() {
        return new TestSuiteStabilityEvidenceVerifier(Clock.fixed(
                TestSuiteStabilityTestFixtures.SIGNED_AT, ZoneOffset.UTC));
    }
}
