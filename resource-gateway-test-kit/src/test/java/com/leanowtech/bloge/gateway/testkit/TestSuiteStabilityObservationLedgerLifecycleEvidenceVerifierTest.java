package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifierTest {
    @Test
    void independentlyVerifiesEveryObservationRetirementTransitionAndPageSignature() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleTestFixtures.stableFixture();

        var result = verifier().verify(fixture.page(),
                Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(result.verified()).isTrue();
        assertThat(result.verifiedRetirements()).isEqualTo(1);
        assertThat(result.verifiedObservations()).isEqualTo(2);
        assertThat(result.checkpoint().complete()).isTrue();
        assertThat(result.checkpoint().terminalRetirementGeneration()).isEqualTo(1);
        assertThat(result.checkpoint().terminalFloorFingerprint()).isEqualTo(
                fixture.page().currentFloor().floorFingerprint());
    }

    @Test
    void verifiesPinnedKeySetAndRejectsWrongExternalPin() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleTestFixtures.stableFixture();

        var verified = verifier().verify(fixture.page(), fixture.keySet(),
                fixture.keySet().snapshotFingerprint());
        var wrongPin = verifier().verify(fixture.page(), fixture.keySet(),
                "sha256:" + "f".repeat(64));

        assertThat(verified.verified()).isTrue();
        assertThat(wrongPin.outcome()).isEqualTo(
                TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.Outcome
                        .POLICY_REJECTED);
        assertThat(wrongPin.reasonCode()).isEqualTo("KEY_SET_PIN_MISMATCH");
    }

    @Test
    void advancesOnePinnedCheckpointAcrossTwoIndependentlyVerifiedPages() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleTestFixtures.twoPageFixture();
        var keys = Map.of(fixture.key().keyId(), fixture.key());

        var first = verifier().verify(fixture.firstPage(), keys);
        var continuation = fixture.firstPage().request().continueAfter(fixture.firstPage());
        var second = verifier().verify(fixture.secondPage(), first.checkpoint(), keys);

        assertThat(first.verified()).as(first.reasonCode()).isTrue();
        assertThat(first.checkpoint().complete()).isFalse();
        assertThat(first.checkpoint().terminalRetirementGeneration()).isEqualTo(1);
        assertThat(continuation).isEqualTo(fixture.secondPage().request());
        assertThat(second.verified()).as(second.reasonCode()).isTrue();
        assertThat(second.checkpoint().complete()).isTrue();
        assertThat(second.checkpoint().terminalRetirementGeneration()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidObservationRetirementAndOuterSignaturesIndependently() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleTestFixtures.stableFixture();
        ObjectNode badObservation = fixture.copyResponse();
        ObjectNode successor = (ObjectNode) badObservation.at(
                "/page/retirements/0/evidence/archiveSegment/successorEntry");
        ObjectNode observation = (ObjectNode) successor.path("observation");
        ((ObjectNode) observation.path("attestation")).put("signature",
                Base64.getEncoder().encodeToString(new byte[64]));
        observation.put("attestationFingerprint",
                EvidenceVerificationSupport.sha256(observation.path("attestation")));
        successor.put("entryFingerprint", EvidenceVerificationSupport.sha256(
                without(successor, "entryFingerprint")));
        ObjectNode pinnedHead = (ObjectNode) badObservation.at(
                "/page/retirements/0/evidence/pinnedHead");
        pinnedHead.put("latestEntryFingerprint", successor.path("entryFingerprint").asText());
        pinnedHead.put("headFingerprint", EvidenceVerificationSupport.sha256(
                without(pinnedHead, "headFingerprint")));
        TestSuiteStabilityObservationLedgerLifecycleTestFixtures.resealRetirement(
                badObservation, fixture.keyPair(), true);
        TestSuiteStabilityObservationLedgerLifecycleTestFixtures.resealPage(
                badObservation, fixture.keyPair());

        ObjectNode badRetirement = fixture.copyResponse();
        ((ObjectNode) badRetirement.at("/page/retirements/0/attestation"))
                .put("signature", Base64.getEncoder().encodeToString(new byte[64]));
        TestSuiteStabilityObservationLedgerLifecycleTestFixtures.resealRetirement(
                badRetirement, fixture.keyPair(), false);
        TestSuiteStabilityObservationLedgerLifecycleTestFixtures.resealPage(
                badRetirement, fixture.keyPair());

        ObjectNode badOuter = fixture.copyResponse();
        ((ObjectNode) badOuter.path("attestation")).put("signature",
                Base64.getEncoder().encodeToString(new byte[64]));

        var observationResult = verifier().verify(
                TestSuiteStabilityObservationLedgerLifecyclePage.from(badObservation),
                Map.of(fixture.key().keyId(), fixture.key()));
        var retirementResult = verifier().verify(
                TestSuiteStabilityObservationLedgerLifecyclePage.from(badRetirement),
                Map.of(fixture.key().keyId(), fixture.key()));
        var outerResult = verifier().verify(
                TestSuiteStabilityObservationLedgerLifecyclePage.from(badOuter),
                Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(observationResult.reasonCode()).isEqualTo("OBSERVATION_SIGNATURE_INVALID");
        assertThat(retirementResult.reasonCode())
                .isEqualTo("LIFECYCLE_RETIREMENT_SIGNATURE_INVALID");
        assertThat(outerResult.reasonCode()).isEqualTo("LIFECYCLE_PAGE_SIGNATURE_INVALID");
    }

    @Test
    void rejectsResignedSuccessorFloorDivergenceAndCheckpointMixing() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleTestFixtures.stableFixture();
        ObjectNode divergent = fixture.copyResponse();
        ObjectNode terminal = (ObjectNode) divergent.at("/page/terminalFloor");
        terminal.put("floorObservationId", "stability-observation-" + "f".repeat(64));
        terminal.put("floorFingerprint", EvidenceVerificationSupport.sha256(
                without(terminal, "floorFingerprint")));
        ((ObjectNode) divergent.path("page")).set("currentFloor", terminal.deepCopy());
        TestSuiteStabilityObservationLedgerLifecycleTestFixtures.resealPage(
                divergent, fixture.keyPair());
        var divergentResult = verifier().verify(
                TestSuiteStabilityObservationLedgerLifecyclePage.from(divergent),
                Map.of(fixture.key().keyId(), fixture.key()));

        var wrongCheckpoint = new TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier
                .LifecycleCheckpoint(
                fixture.page().request().suiteId(), fixture.page().request().revision(),
                fixture.page().request().fingerprint(), fixture.page().scopeFingerprint(),
                fixture.page().currentFloor().floorFingerprint(),
                fixture.page().head().headFingerprint(), 1,
                "sha256:" + "f".repeat(64), false);
        var mixedResult = verifier().verify(fixture.page(), wrongCheckpoint,
                Map.of(fixture.key().keyId(), fixture.key()));

        assertThat(divergentResult.reasonCode())
                .isEqualTo("LIFECYCLE_TERMINAL_FLOOR_INVALID");
        assertThat(mixedResult.reasonCode()).isEqualTo("LIFECYCLE_CHECKPOINT_MISMATCH");
    }

    @Test
    void rejectsMissingKeyUnknownFieldsAndBrokenRetirementReferenceClosure() {
        var fixture = TestSuiteStabilityObservationLedgerLifecycleTestFixtures.stableFixture();
        var unavailable = verifier().verify(fixture.page(), Map.of());
        ObjectNode unknown = fixture.copyResponse();
        ((ObjectNode) unknown.path("page")).put("tenantId", "tenant-a");
        ObjectNode brokenRef = fixture.copyResponse();
        ((ObjectNode) brokenRef.at("/attestation/retirementRefs/0"))
                .put("retirementFingerprint", "sha256:" + "f".repeat(64));

        assertThat(unavailable.outcome()).isEqualTo(
                TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier.Outcome
                        .KEY_UNAVAILABLE);
        assertThatThrownBy(() -> TestSuiteStabilityObservationLedgerLifecyclePage.from(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authoritative schema");
        assertThatThrownBy(() -> TestSuiteStabilityObservationLedgerLifecyclePage.from(brokenRef))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("closure");
    }

    private static TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier verifier() {
        return new TestSuiteStabilityObservationLedgerLifecycleEvidenceVerifier(Clock.fixed(
                EvidenceTrustTestFixtures.NOW.plusSeconds(300), ZoneOffset.UTC));
    }

    private static com.fasterxml.jackson.databind.JsonNode without(
            com.fasterxml.jackson.databind.JsonNode value,
            String field) {
        ObjectNode copy = ((ObjectNode) value).deepCopy();
        copy.remove(field);
        return copy;
    }
}
