package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSuiteStabilityAttestationServiceTest {
    private static final String REQUEST_FINGERPRINT =
            TestSuiteStabilityProtocolFixtures.fingerprint('9');
    private static final Instant SIGNED_AT = Instant.parse("2026-07-18T03:00:00Z");

    private ObjectMapper mapper;
    private TestSuiteStabilityAttestationService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        service = new TestSuiteStabilityAttestationService(mapper,
                new InMemoryVisualEvidenceSigner(), Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    }

    @Test
    void signsAndImmediatelyVerifiesCompleteStabilityAndSourceClosure() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.stableEvidence();

        TestSuiteStabilityAttestationService.SealResult result =
                service.seal(evidence, REQUEST_FINGERPRINT);

        assertThat(result.verified()).isTrue();
        assertThat(result.attestation().signedAt()).isEqualTo(SIGNED_AT);
        assertThat(result.attestation().sourceSuiteEvidenceRefs())
                .extracting(TestSuiteStabilityAttestation.SourceSuiteEvidenceRef::attempt)
                .containsExactly(1, 2, 3);
        assertThat(service.verify(evidence, result.attestation()))
                .isEqualTo(TestSuiteStabilityAttestationService.Verification.VERIFIED);
    }

    @Test
    void signsAndVerifiesV3StatisticalEvidenceAndItsFullSourceClosure() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.statisticalStableEvidence();

        TestSuiteStabilityAttestationService.SealResult result =
                service.seal(evidence, REQUEST_FINGERPRINT);

        assertThat(result.verified()).isTrue();
        assertThat(result.attestation().schemaVersion())
                .isEqualTo(TestSuiteStabilityAttestation.SCHEMA_VERSION_V3);
        assertThat(result.attestation().sourceSuiteEvidenceRefs()).hasSize(29);
        assertThat(service.verify(evidence, result.attestation()))
                .isEqualTo(TestSuiteStabilityAttestationService.Verification.VERIFIED);
    }

    @Test
    void signsAndVerifiesV4RateEvidenceUnderItsOwnDomainVersion() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.rateStableEvidence();

        TestSuiteStabilityAttestationService.SealResult result =
                service.seal(evidence, REQUEST_FINGERPRINT);

        assertThat(result.verified()).isTrue();
        assertThat(result.attestation().schemaVersion())
                .isEqualTo(TestSuiteStabilityAttestation.SCHEMA_VERSION_V4);
        assertThat(result.attestation().sourceSuiteEvidenceRefs()).hasSize(30);
        assertThat(service.verify(evidence, result.attestation()))
                .isEqualTo(TestSuiteStabilityAttestationService.Verification.VERIFIED);
    }

    @Test
    void signsAndVerifiesTheActualV5SequentialPrefixUnderItsOwnDomainVersion() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.sequentialStableEvidence();

        TestSuiteStabilityAttestationService.SealResult result =
                service.seal(evidence, REQUEST_FINGERPRINT);

        assertThat(result.verified()).isTrue();
        assertThat(result.attestation().schemaVersion())
                .isEqualTo(TestSuiteStabilityAttestation.SCHEMA_VERSION);
        assertThat(result.attestation().sourceSuiteEvidenceRefs()).hasSize(57);
        assertThat(evidence.requestedAttempts()).isEqualTo(100);
        assertThat(service.verify(evidence, result.attestation()))
                .isEqualTo(TestSuiteStabilityAttestationService.Verification.VERIFIED);
    }

    @Test
    void rejectsAProducerForgedStatisticalAggregateBeforeItCanBeSigned() {
        ObjectNode forged = mapper.valueToTree(
                TestSuiteStabilityProtocolFixtures.statisticalStableEvidence());
        ((ObjectNode) forged.path("statisticalAssessment"))
                .put("achievedConfidenceBps", 9_999);

        assertThatThrownBy(() -> mapper.treeToValue(forged, TestSuiteStabilityEvidence.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasRootCauseMessage(
                        "Stability status, promotion, quarantine, and statistics must be server-derived");
    }

    @Test
    void evidenceOrSourceOrderMutationInvalidatesSignature() {
        TestSuiteStabilityEvidence evidence =
                TestSuiteStabilityProtocolFixtures.stableEvidence();
        TestSuiteStabilityAttestation original =
                service.seal(evidence, REQUEST_FINGERPRINT).attestation();
        List<TestSuiteStabilityAttestation.SourceSuiteEvidenceRef> reversed =
                new ArrayList<>(original.sourceSuiteEvidenceRefs());
        java.util.Collections.reverse(reversed);
        TestSuiteStabilityAttestation reordered = copy(original, reversed,
                original.evidenceFingerprint());
        TestSuiteStabilityAttestation wrongFingerprint = copy(original,
                original.sourceSuiteEvidenceRefs(),
                TestSuiteStabilityProtocolFixtures.fingerprint('8'));

        assertThat(service.verify(evidence, reordered))
                .isEqualTo(TestSuiteStabilityAttestationService.Verification.INVALID);
        assertThat(service.verify(evidence, wrongFingerprint))
                .isEqualTo(TestSuiteStabilityAttestationService.Verification.INVALID);
    }

    @Test
    void unavailableSignerProducesExplicitNonVerifiableManifest() {
        TestSuiteStabilityAttestationService unavailable =
                new TestSuiteStabilityAttestationService(mapper,
                        VisualEvidenceSigner.unavailable(),
                        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

        TestSuiteStabilityAttestationService.SealResult result = unavailable.seal(
                TestSuiteStabilityProtocolFixtures.stableEvidence(), REQUEST_FINGERPRINT);

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCode())
                .isEqualTo(TestSuiteStabilityAttestationService.SIGNER_UNAVAILABLE);
        assertThat(result.attestation().signatureStatus())
                .isEqualTo(TestSuiteStabilityAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE);
    }

    @Test
    void preservesHistoricalV1CanonicalShapeAcrossDecodeAndEncode() throws Exception {
        TestSuiteStabilityEvidence current =
                TestSuiteStabilityProtocolFixtures.stableEvidence();
        ObjectNode legacyEvidence = mapper.valueToTree(current);
        legacyEvidence.put("schemaVersion", TestSuiteStabilityEvidence.SCHEMA_VERSION_V1);
        legacyEvidence.path("attempts").forEach(value -> {
            ObjectNode attempt = (ObjectNode) value;
            attempt.remove("sourcePromotionStatus");
            attempt.remove("sourcePromotionReasons");
        });
        ((ObjectNode) legacyEvidence.path("promotion"))
                .remove("allSourceSuitesPromotionEligible");

        TestSuiteStabilityEvidence decodedEvidence = mapper.treeToValue(
                legacyEvidence, TestSuiteStabilityEvidence.class);
        JsonNode encodedEvidence = mapper.valueToTree(decodedEvidence);
        assertThat(encodedEvidence).isEqualTo(legacyEvidence);
        TestSuiteStabilityAttestation resealedLegacy =
                service.seal(decodedEvidence, REQUEST_FINGERPRINT).attestation();
        assertThat(resealedLegacy.schemaVersion())
                .isEqualTo(TestSuiteStabilityAttestation.SCHEMA_VERSION_V1);
        assertThat(service.verify(decodedEvidence, resealedLegacy))
                .isEqualTo(TestSuiteStabilityAttestationService.Verification.VERIFIED);

        TestSuiteStabilityAttestation currentAttestation =
                service.seal(current, REQUEST_FINGERPRINT).attestation();
        ObjectNode legacyAttestation = mapper.valueToTree(currentAttestation);
        legacyAttestation.put("schemaVersion", TestSuiteStabilityAttestation.SCHEMA_VERSION_V1);
        legacyAttestation.path("sourceSuiteEvidenceRefs").forEach(value -> {
            ObjectNode source = (ObjectNode) value;
            source.remove("sourcePromotionStatus");
            source.remove("sourcePromotionReasons");
        });

        TestSuiteStabilityAttestation decodedAttestation = mapper.treeToValue(
                legacyAttestation, TestSuiteStabilityAttestation.class);
        JsonNode encodedAttestation = mapper.valueToTree(decodedAttestation);
        assertThat(encodedAttestation).isEqualTo(legacyAttestation);
    }

    private static TestSuiteStabilityAttestation copy(
            TestSuiteStabilityAttestation source,
            List<TestSuiteStabilityAttestation.SourceSuiteEvidenceRef> refs,
            String evidenceFingerprint) {
        return new TestSuiteStabilityAttestation(source.schemaVersion(),
                source.signatureStatus(), source.stabilityRunId(), source.suiteRef(),
                source.requestFingerprint(), evidenceFingerprint, refs, source.signedAt(),
                source.keyId(), source.algorithm(), source.signature(), true);
    }
}
