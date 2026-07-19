package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityTrendEvidenceVerifierTest {
    @Test
    void independentlyVerifiesEverySourceAndReconstructsStableTrend() {
        TestSuiteStabilityTrendTestFixtures.Fixture fixture =
                TestSuiteStabilityTrendTestFixtures.stableFixture();

        TestSuiteStabilityTrendEvidenceVerifier.VerificationResult result = verifier().verify(
                fixture.analysis(), fixture.sources(), Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(result.verified()).isTrue();
        assertThat(result.verifiedSources()).isEqualTo(2);
        assertThat(fixture.analysis().status())
                .isEqualTo(TestSuiteStabilityTrendAnalysis.Status.STABLE_PASS);
    }

    @Test
    void reconstructsSameRegimeOutcomeChangeAsInstability() {
        TestSuiteStabilityTrendTestFixtures.Fixture fixture =
                TestSuiteStabilityTrendTestFixtures.outcomeShiftFixture();

        TestSuiteStabilityTrendEvidenceVerifier.VerificationResult result = verifier().verify(
                fixture.analysis(), fixture.sources(), Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(result.verified()).isTrue();
        assertThat(fixture.analysis().status())
                .isEqualTo(TestSuiteStabilityTrendAnalysis.Status.INSTABILITY_OBSERVED);
        assertThat(fixture.analysis().caseTrends()).singleElement().satisfies(trend -> {
            assertThat(trend.status()).isEqualTo(
                    TestSuiteStabilityTrendAnalysis.CaseTrendStatus.INSTABILITY_OBSERVED);
            assertThat(trend.changedAtRunIds()).containsExactly(
                    fixture.sources().get(1).stabilityRunId());
        });
    }

    @Test
    void keepsPlanChangeAsRegimeDriftRatherThanFlakiness() {
        TestSuiteStabilityTrendTestFixtures.Fixture fixture =
                TestSuiteStabilityTrendTestFixtures.regimeDriftFixture();

        TestSuiteStabilityTrendEvidenceVerifier.VerificationResult result = verifier().verify(
                fixture.analysis(), fixture.sources(), Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(result.verified()).isTrue();
        assertThat(fixture.analysis().status())
                .isEqualTo(TestSuiteStabilityTrendAnalysis.Status.REGIME_DRIFT_OBSERVED);
        assertThat(fixture.analysis().caseTrends()).singleElement().satisfies(trend -> {
            assertThat(trend.status()).isEqualTo(
                    TestSuiteStabilityTrendAnalysis.CaseTrendStatus.REGIME_DRIFT_OBSERVED);
            assertThat(trend.changedAtRunIds()).isEmpty();
        });
    }

    @Test
    void rejectsResignedProducerTrendLabelThatDoesNotReconstruct() {
        TestSuiteStabilityTrendTestFixtures.Fixture fixture =
                TestSuiteStabilityTrendTestFixtures.stableFixture();
        ObjectNode forged = fixture.copyResponse();
        ((ObjectNode) forged.path("evidence")).put("status", "CONSISTENT_FAILURE_OBSERVED");
        TestSuiteStabilityTrendTestFixtures.sealTrend(forged, fixture.keyPair());
        TestSuiteStabilityTrendAnalysis analysis = TestSuiteStabilityTrendAnalysis.from(forged);

        TestSuiteStabilityTrendEvidenceVerifier.VerificationResult result = verifier().verify(
                analysis, fixture.sources(), Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(result.outcome())
                .isEqualTo(TestSuiteStabilityTrendEvidenceVerifier.Outcome.INVALID);
        assertThat(result.reasonCode()).isEqualTo("TREND_DERIVATION_INVALID");
    }

    @Test
    void rejectsMissingOrReorderedSourceClosureBeforeTrendSignatureTrust() {
        TestSuiteStabilityTrendTestFixtures.Fixture fixture =
                TestSuiteStabilityTrendTestFixtures.stableFixture();
        List<TestSuiteStabilityRun> reversed = List.of(
                fixture.sources().get(1), fixture.sources().get(0));

        var missing = verifier().verify(fixture.analysis(), fixture.sources().subList(0, 1),
                Map.of(fixture.key().keyId(), fixture.key()));
        var reordered = verifier().verify(fixture.analysis(), reversed,
                Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(missing.reasonCode()).isEqualTo("TREND_SOURCE_CLOSURE_INCOMPLETE");
        assertThat(reordered.reasonCode()).isEqualTo("TREND_SOURCE_ORDER_INVALID");
    }

    @Test
    void rejectsTamperedTrendSignatureAndUnavailableKey() {
        TestSuiteStabilityTrendTestFixtures.Fixture fixture =
                TestSuiteStabilityTrendTestFixtures.stableFixture();
        ObjectNode tampered = fixture.copyResponse();
        ((ObjectNode) tampered.path("attestation")).put("signature",
                java.util.Base64.getEncoder().encodeToString(new byte[64]));
        TestSuiteStabilityTrendAnalysis analysis = TestSuiteStabilityTrendAnalysis.from(tampered);

        var invalid = verifier().verify(analysis, fixture.sources(),
                Map.of(fixture.key().keyId(), fixture.key()));
        var unavailable = verifier().verify(fixture.analysis(), fixture.sources(), Map.of());

        assertThat(invalid.reasonCode()).isEqualTo("TREND_ATTESTATION_SIGNATURE_INVALID");
        assertThat(unavailable.outcome())
                .isEqualTo(TestSuiteStabilityTrendEvidenceVerifier.Outcome.KEY_UNAVAILABLE);
    }

    @Test
    void verifiesAgainstExternallyPinnedCompleteKeyLifecycle() {
        TestSuiteStabilityTrendTestFixtures.Fixture fixture =
                TestSuiteStabilityTrendTestFixtures.stableFixture();

        var result = verifier().verify(fixture.analysis(), fixture.sources(), fixture.keySet(),
                fixture.keySet().snapshotFingerprint());

        assertThat(result.verified()).isTrue();
    }

    @Test
    void rejectsAKeySetFingerprintThatWasNotPinnedByAnIndependentChannel() {
        TestSuiteStabilityTrendTestFixtures.Fixture fixture =
                TestSuiteStabilityTrendTestFixtures.stableFixture();

        var result = verifier().verify(fixture.analysis(), fixture.sources(), fixture.keySet(),
                TestSuiteStabilityTrendTestFixtures.fingerprint('f'));

        assertThat(result.verified()).isFalse();
        assertThat(result.outcome())
                .isEqualTo(TestSuiteStabilityTrendEvidenceVerifier.Outcome.POLICY_REJECTED);
        assertThat(result.reasonCode()).isEqualTo("KEY_SET_PIN_MISMATCH");
    }

    @Test
    void parserRejectsUnknownFieldsAndContradictoryRequestFingerprint() {
        TestSuiteStabilityTrendTestFixtures.Fixture fixture =
                TestSuiteStabilityTrendTestFixtures.stableFixture();
        ObjectNode unknown = fixture.copyResponse();
        ((ObjectNode) unknown.path("evidence")).put("causalOwner", "payments-team");
        ObjectNode mismatched = fixture.copyResponse();
        ((ObjectNode) mismatched.path("evidence")).put(
                "requestFingerprint", TestSuiteStabilityTrendTestFixtures.fingerprint('f'));
        TestSuiteStabilityTrendTestFixtures.sealTrend(mismatched, fixture.keyPair());
        ObjectNode outsideWindow = fixture.copyResponse();
        ((ObjectNode) outsideWindow.at("/evidence/sources/0"))
                .put("createdAt",
                        fixture.analysis().request().toExclusive().plusSeconds(1).toString());
        TestSuiteStabilityTrendTestFixtures.sealTrend(outsideWindow, fixture.keyPair());

        assertThatThrownBy(() -> TestSuiteStabilityTrendAnalysis.from(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");
        assertThatThrownBy(() -> TestSuiteStabilityTrendAnalysis.from(mismatched))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request fingerprint");
        assertThatThrownBy(() -> TestSuiteStabilityTrendAnalysis.from(outsideWindow))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consistent stability trend");
    }

    private static TestSuiteStabilityTrendEvidenceVerifier verifier() {
        return new TestSuiteStabilityTrendEvidenceVerifier(Clock.fixed(
                EvidenceTrustTestFixtures.NOW, ZoneOffset.UTC));
    }
}
