package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityCrossRetentionTrendEvidenceVerifierTest {
    @Test
    void independentlyVerifiesEveryCompactSignatureAndDerivedTrend() {
        var fixture = TestSuiteStabilityCrossRetentionTrendTestFixtures.stableFixture();

        var result = verifier().verify(fixture.analysis(),
                Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(result.verified()).isTrue();
        assertThat(result.verifiedObservations()).isEqualTo(2);
        assertThat(fixture.analysis().status())
                .isEqualTo(TestSuiteStabilityTrendAnalysis.Status.STABLE_PASS);
    }

    @Test
    void projectsBySignedSourceTimeWhenLedgerAppendOrderDiffers() {
        var fixture = TestSuiteStabilityCrossRetentionTrendTestFixtures
                .ledgerOrderDiffersFromSourceOrderFixture();
        var analysis = fixture.analysis();
        String firstLedgerRun = analysis.range().entries().getFirst()
                .observation().source().stabilityRunId();

        var result = verifier().verify(analysis,
                Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(result.verified()).isTrue();
        assertThat(analysis.caseTrends().getFirst().sourceRunIds().getFirst())
                .isNotEqualTo(firstLedgerRun);
    }

    @Test
    void rejectsResignedProducerLabelAndResignedArbitraryTrendIdentity() {
        var fixture = TestSuiteStabilityCrossRetentionTrendTestFixtures.stableFixture();
        ObjectNode forgedLabel = fixture.copyResponse();
        ((ObjectNode) forgedLabel.path("evidence"))
                .put("status", "CONSISTENT_FAILURE_OBSERVED");
        TestSuiteStabilityCrossRetentionTrendTestFixtures.resealEnvelope(
                forgedLabel, fixture.keyPair());
        ObjectNode forgedIdentity = fixture.copyResponse();
        String arbitraryId = "stability-cross-retention-trend-" + "f".repeat(64);
        forgedIdentity.put("trendAnalysisId", arbitraryId);
        ((ObjectNode) forgedIdentity.path("evidence")).put("trendAnalysisId", arbitraryId);
        ((ObjectNode) forgedIdentity.path("attestation")).put("trendAnalysisId", arbitraryId);
        TestSuiteStabilityCrossRetentionTrendTestFixtures.resealOuter(
                forgedIdentity, fixture.keyPair());

        var labelResult = verifier().verify(
                TestSuiteStabilityCrossRetentionTrendAnalysis.from(forgedLabel),
                Map.of(fixture.key().keyId(), fixture.key()));
        var identityResult = verifier().verify(
                TestSuiteStabilityCrossRetentionTrendAnalysis.from(forgedIdentity),
                Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(labelResult.reasonCode())
                .isEqualTo("CROSS_RETENTION_TREND_DERIVATION_INVALID");
        assertThat(identityResult.reasonCode())
                .isEqualTo("CROSS_RETENTION_TREND_IDENTITY_INVALID");
    }

    @Test
    void rejectsResignedObservationWhoseDeterministicIdentityWasNotUpdated() {
        var fixture = TestSuiteStabilityCrossRetentionTrendTestFixtures.stableFixture();
        ObjectNode forged = fixture.copyResponse();
        ((ObjectNode) forged.at("/evidence/range/entries/0/observation/evidence"))
                .put("sourceRequestFingerprint",
                        TestSuiteStabilityCrossRetentionTrendTestFixtures.fingerprint('e'));
        TestSuiteStabilityCrossRetentionTrendTestFixtures.resealObservation(
                forged, 0, fixture.keyPair());
        TestSuiteStabilityCrossRetentionTrendTestFixtures.resealEnvelope(
                forged, fixture.keyPair());

        var result = verifier().verify(
                TestSuiteStabilityCrossRetentionTrendAnalysis.from(forged),
                Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(result.reasonCode()).isEqualTo("OBSERVATION_IDENTITY_INVALID");
    }

    @Test
    void rejectsInvalidObservationAndOuterSignaturesAndUnavailableKey() {
        var fixture = TestSuiteStabilityCrossRetentionTrendTestFixtures.stableFixture();
        ObjectNode badObservation = fixture.copyResponse();
        ObjectNode observation = (ObjectNode) badObservation.at(
                "/evidence/range/entries/0/observation");
        ((ObjectNode) observation.path("attestation")).put("signature",
                Base64.getEncoder().encodeToString(new byte[64]));
        observation.put("attestationFingerprint",
                EvidenceVerificationSupport.sha256(observation.path("attestation")));
        TestSuiteStabilityCrossRetentionTrendTestFixtures.resealEnvelope(
                badObservation, fixture.keyPair());
        ObjectNode badOuter = fixture.copyResponse();
        ((ObjectNode) badOuter.path("attestation")).put("signature",
                Base64.getEncoder().encodeToString(new byte[64]));

        var observationResult = verifier().verify(
                TestSuiteStabilityCrossRetentionTrendAnalysis.from(badObservation),
                Map.of(fixture.key().keyId(), fixture.key()));
        var outerResult = verifier().verify(
                TestSuiteStabilityCrossRetentionTrendAnalysis.from(badOuter),
                Map.of(fixture.key().keyId(), fixture.key()));
        var unavailable = verifier().verify(fixture.analysis(), Map.of());

        assertThat(observationResult.reasonCode()).isEqualTo("OBSERVATION_SIGNATURE_INVALID");
        assertThat(outerResult.reasonCode())
                .isEqualTo("CROSS_RETENTION_ATTESTATION_SIGNATURE_INVALID");
        assertThat(unavailable.outcome())
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendEvidenceVerifier
                        .Outcome.KEY_UNAVAILABLE);
    }

    @Test
    void parserRejectsUnknownFieldsBrokenStructureAndObservationClosure() {
        var fixture = TestSuiteStabilityCrossRetentionTrendTestFixtures.stableFixture();
        ObjectNode unknown = fixture.copyResponse();
        ((ObjectNode) unknown.path("evidence")).put("causalOwner", "payments-team");
        ObjectNode brokenEntry = fixture.copyResponse();
        ((ObjectNode) brokenEntry.at("/evidence/range/entries/0"))
                .put("appendedAt", EvidenceTrustTestFixtures.NOW.plusSeconds(90).toString());
        ObjectNode missingRef = fixture.copyResponse();
        ((ArrayNode) missingRef.at("/attestation/observationRefs")).remove(1);
        ObjectNode reversedRefs = fixture.copyResponse();
        ArrayNode refs = (ArrayNode) reversedRefs.at("/attestation/observationRefs");
        ObjectNode first = (ObjectNode) refs.get(0);
        ObjectNode second = (ObjectNode) refs.get(1);
        refs.removeAll();
        refs.add(second).add(first);

        assertThatThrownBy(() -> TestSuiteStabilityCrossRetentionTrendAnalysis.from(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");
        assertThatThrownBy(() -> TestSuiteStabilityCrossRetentionTrendAnalysis.from(brokenEntry))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint");
        assertThatThrownBy(() -> TestSuiteStabilityCrossRetentionTrendAnalysis.from(missingRef))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closure");
        assertThatThrownBy(() -> TestSuiteStabilityCrossRetentionTrendAnalysis.from(reversedRefs))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifiesPinnedKeySetAndRejectsPinOrSigningTimePolicy() {
        var fixture = TestSuiteStabilityCrossRetentionTrendTestFixtures.stableFixture();
        var verified = verifier().verify(fixture.analysis(), fixture.keySet(),
                fixture.keySet().snapshotFingerprint());
        var wrongPin = verifier().verify(fixture.analysis(), fixture.keySet(),
                TestSuiteStabilityCrossRetentionTrendTestFixtures.fingerprint('f'));
        ObjectNode signedTooEarly = fixture.copyResponse();
        ((ObjectNode) signedTooEarly.at(
                "/evidence/range/entries/0/observation/attestation"))
                .put("signedAt", EvidenceTrustTestFixtures.NOW.minusSeconds(700).toString());
        TestSuiteStabilityCrossRetentionTrendTestFixtures.resealObservation(
                signedTooEarly, 0, fixture.keyPair());
        TestSuiteStabilityCrossRetentionTrendTestFixtures.resealEnvelope(
                signedTooEarly, fixture.keyPair());
        var lifecycle = verifier().verify(
                TestSuiteStabilityCrossRetentionTrendAnalysis.from(signedTooEarly),
                fixture.keySet(), fixture.keySet().snapshotFingerprint());

        assertThat(verified.verified()).isTrue();
        assertThat(wrongPin.outcome())
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendEvidenceVerifier
                        .Outcome.POLICY_REJECTED);
        assertThat(wrongPin.reasonCode()).isEqualTo("KEY_SET_PIN_MISMATCH");
        assertThat(lifecycle.outcome())
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendEvidenceVerifier
                        .Outcome.POLICY_REJECTED);
    }

    private static TestSuiteStabilityCrossRetentionTrendEvidenceVerifier verifier() {
        return new TestSuiteStabilityCrossRetentionTrendEvidenceVerifier(Clock.fixed(
                EvidenceTrustTestFixtures.NOW, ZoneOffset.UTC));
    }
}
