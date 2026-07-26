package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OnlineReadOnlyShadowSourceResolutionAttestationVerifierTest {
    private OnlineReadOnlyShadowSourceResolutionCompatibilityFixture
            fixture;
    private OnlineReadOnlyShadowSourceResolutionAttestationVerifier
            verifier;

    @BeforeEach
    void setUp() {
        fixture = CapabilityMirrorProtocol
                .onlineReadOnlyShadowSourceResolutionCompatibilityFixture();
        verifier =
                new OnlineReadOnlyShadowSourceResolutionAttestationVerifier();
    }

    @Test
    void verifiesServerProducedThreeAuthorityOnlinePair() {
        var result = fixture.verify();

        assertThat(result.verified())
                .as(result.reasonCode())
                .isTrue();
        assertThat(result.zeroWrite()).isTrue();
        assertThat(result.attestationId())
                .startsWith("source-resolution-");
        assertThat(result.requestId())
                .isEqualTo("synthetic-pair");
        assertThat(result.executionId())
                .isEqualTo(
                        "execution-synthetic-pair");
        assertThat(result.baselineReason())
                .isEqualTo("VERIFIED");
        assertThat(result.candidateReason())
                .isEqualTo("VERIFIED");
        assertThat(result.keyId())
                .isEqualTo(
                        fixture.attestationKey()
                                .keyId());
    }

    @Test
    void rejectsEitherExactCommandDriftingFromTheSignedPair() {
        ObjectNode changedBaseline =
                (ObjectNode) fixture
                        .baselineCommand();
        changedBaseline.put(
                "unitId", "other-unit");

        var baselineDrift =
                verifier.verify(
                        fixture.attestation(),
                        fixture.attestationKey(),
                        context(
                                changedBaseline,
                                fixture.baselineObservation(),
                                fixture.candidateCommand(),
                                fixture.candidateEvidenceBundle(),
                                fixture.verificationTime()));

        assertThat(baselineDrift.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.EXPECTATION_MISMATCH);
        assertThat(baselineDrift.reasonCode())
                .isEqualTo(
                        "ONLINE_SOURCE_RESOLUTION_EXPECTATION_MISMATCH");

        ObjectNode changedCandidate =
                (ObjectNode) fixture
                        .candidateCommand();
        changedCandidate.put(
                "requestContextFingerprint",
                fingerprint('f'));
        assertThat(verifier.verify(
                fixture.attestation(),
                fixture.attestationKey(),
                context(
                        fixture.baselineCommand(),
                        fixture.baselineObservation(),
                        changedCandidate,
                        fixture.candidateEvidenceBundle(),
                        fixture.verificationTime()))
                .outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.EXPECTATION_MISMATCH);
    }

    @Test
    void distinguishesInvalidBaselineFromInvalidCandidateEvidence() {
        ObjectNode changedBaseline =
                (ObjectNode) fixture
                        .baselineObservation();
        changedBaseline.put(
                "semanticResultFingerprint",
                fingerprint('a'));
        var baselineInvalid =
                verifier.verify(
                        fixture.attestation(),
                        fixture.attestationKey(),
                        context(
                                fixture.baselineCommand(),
                                changedBaseline,
                                fixture.candidateCommand(),
                                fixture.candidateEvidenceBundle(),
                                fixture.verificationTime()));

        assertThat(baselineInvalid.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.BASELINE_INVALID);
        assertThat(baselineInvalid.baselineReason())
                .isEqualTo(
                        "ONLINE_BASELINE_FINGERPRINT_INVALID");

        ObjectNode changedCandidate =
                (ObjectNode) fixture
                        .candidateEvidenceBundle();
        changedCandidate.withObject("/evidence")
                .put(
                        "semanticResultFingerprint",
                        fingerprint('b'));
        var candidateInvalid =
                verifier.verify(
                        fixture.attestation(),
                        fixture.attestationKey(),
                        context(
                                fixture.baselineCommand(),
                                fixture.baselineObservation(),
                                fixture.candidateCommand(),
                                changedCandidate,
                                fixture.verificationTime()));

        assertThat(candidateInvalid.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.CANDIDATE_INVALID);
        assertThat(candidateInvalid.candidateReason())
                .isEqualTo(
                        "MIRROR_EVIDENCE_FINGERPRINT_INVALID");
    }

    @Test
    void rejectsStaleExactReadTimeAndPayloadBearingProofs() {
        ObjectNode stale =
                (ObjectNode) fixture.attestation();
        stale.withObject("/baseline")
                .put(
                        "resolvedAt",
                        stale.path("admittedAt")
                                .asText());
        var staleResult =
                verifier.verify(
                        stale,
                        fixture.attestationKey(),
                        context());

        assertThat(staleResult.outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.INVALID);
        assertThat(staleResult.reasonCode())
                .isEqualTo(
                        "ONLINE_SOURCE_RESOLUTION_TIME_INVALID");

        ObjectNode payloadBearing =
                (ObjectNode) fixture.attestation();
        payloadBearing.put(
                "responsePayload",
                "customer-secret");
        var payloadResult =
                verifier.verify(
                        payloadBearing,
                        fixture.attestationKey(),
                        context());
        assertThat(payloadResult.reasonCode())
                .isEqualTo(
                        "ONLINE_SOURCE_RESOLUTION_SCHEMA_INVALID");
        assertThat(payloadResult.toString())
                .doesNotContain("customer-secret");
    }

    @Test
    void distinguishesUnavailableWrongFutureAndInvalidProofSignatures() {
        assertThat(verifier.verify(
                fixture.attestation(),
                null,
                context()).outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.KEY_UNAVAILABLE);

        assertThat(verifier.verify(
                fixture.attestation(),
                fixture.baselineKey(),
                context()).outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.POLICY_REJECTED);

        assertThat(verifier.verify(
                fixture.attestation(),
                fixture.attestationKey(),
                context(
                        fixture.baselineCommand(),
                        fixture.baselineObservation(),
                        fixture.candidateCommand(),
                        fixture.candidateEvidenceBundle(),
                        fixture.verificationTime()
                                .minus(
                                        Duration.ofMinutes(
                                                5))))
                .outcome())
                .isEqualTo(
                        OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                                .Outcome.WINDOW_REJECTED);

        ObjectNode badSignature =
                (ObjectNode) fixture.attestation();
        badSignature.withObject(
                        "/attestationSeal")
                .put(
                        "signature",
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==");
        assertThat(verifier.verify(
                badSignature,
                fixture.attestationKey(),
                context()).reasonCode())
                .isEqualTo(
                        "ONLINE_SOURCE_RESOLUTION_SIGNATURE_INVALID");
    }

    @Test
    void fixtureIsPublicOnlyAndAccessorsReturnDetachedDocuments() {
        String publicMaterial =
                fixture.baselineCommand().toString()
                        + fixture.baselineObservation()
                        + fixture.candidateCommand()
                        + fixture.candidateEvidenceBundle()
                        + fixture.attestation();
        assertThat(publicMaterial)
                .doesNotContain("privateKey")
                .doesNotContain("requestPayload")
                .doesNotContain("responsePayload")
                .doesNotContain("endpointUri")
                .doesNotContain("credential");

        ObjectNode command =
                (ObjectNode) fixture
                        .candidateCommand();
        command.put("unitId", "mutated");
        ObjectNode proof =
                (ObjectNode) fixture.attestation();
        proof.put(
                "attestationId", "mutated");

        var reloaded = CapabilityMirrorProtocol
                .onlineReadOnlyShadowSourceResolutionCompatibilityFixture();
        assertThat(reloaded.candidateCommand()
                .path("unitId").asText())
                .isEqualTo("refund-golden");
        assertThat(reloaded.attestation()
                .path("attestationId").asText())
                .startsWith("source-resolution-");
        assertThat(reloaded.verify().verified())
                .isTrue();
    }

    private
    OnlineReadOnlyShadowSourceResolutionAttestationVerifier
            .VerificationContext context() {
        return context(
                fixture.baselineCommand(),
                fixture.baselineObservation(),
                fixture.candidateCommand(),
                fixture.candidateEvidenceBundle(),
                fixture.verificationTime());
    }

    private
    OnlineReadOnlyShadowSourceResolutionAttestationVerifier
            .VerificationContext context(
            JsonNode baselineCommand,
            JsonNode baselineObservation,
            JsonNode candidateCommand,
            JsonNode candidateEvidence,
            java.time.Instant verificationTime) {
        return new OnlineReadOnlyShadowSourceResolutionAttestationVerifier
                .VerificationContext(
                fixture.expectedScope(),
                fixture.expectedAttestationRef(),
                fixture.expectedRequestId(),
                fixture.expectedExecutionId(),
                fixture.expectedAdmissionFingerprint(),
                baselineCommand,
                baselineObservation,
                fixture.baselineKey(),
                candidateCommand,
                candidateEvidence,
                fixture.candidateEvidenceKey(),
                verificationTime);
    }

    private static String fingerprint(
            char value) {
        return "sha256:"
                + String.valueOf(value).repeat(64);
    }
}
