package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures.CaseMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityHistoryWindow;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityTrendAnalysisRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;
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

class TestSuiteStabilityTrendAttestationServiceTest {
    private static final Instant FROM = Instant.parse("2026-07-18T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-07-19T00:00:00Z");
    private static final Instant SIGNED_AT = Instant.parse("2026-07-19T00:01:00Z");

    private ObjectMapper mapper;
    private TestSuiteStabilityTrendEvidence evidence;
    private TestSuiteStabilityTrendAttestationService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        TestSuiteStabilityAttestationService sources =
                new TestSuiteStabilityAttestationService(mapper, signer);
        TestSuiteStabilityRunRecord first = TestSuiteStabilityTrendProtocolFixtures.record(
                mapper, sources, '1', FROM.plusSeconds(100), TO.plusSeconds(3600),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '1', '2');
        TestSuiteStabilityRunRecord second = TestSuiteStabilityTrendProtocolFixtures.record(
                mapper, sources, '2', FROM.plusSeconds(200), TO.plusSeconds(3600),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '1', '2');
        TestSuiteStabilityTrendAnalysisRequest request =
                new TestSuiteStabilityTrendAnalysisRequest("",
                        TestSuiteStabilityProtocolFixtures.SUITE_REF, FROM, TO, 2, 10);
        evidence = new TestSuiteStabilityTrendEvidenceEvaluator(mapper).evaluate(
                "tenant-a", "test", request, ProtocolFingerprint.of(mapper, request),
                new TestSuiteStabilityHistoryWindow(List.of(first, second), 0, false, TO));
        service = new TestSuiteStabilityTrendAttestationService(
                mapper, signer, Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    }

    @Test
    void signsAndVerifiesTheExactOrderedSourceClosure() {
        TestSuiteStabilityTrendAttestationService.SealResult sealed = service.seal(evidence);

        assertThat(sealed.verified()).isTrue();
        assertThat(sealed.attestation().signedAt()).isEqualTo(SIGNED_AT);
        assertThat(sealed.attestation().sourceEvidenceRefs())
                .extracting(TestSuiteStabilityTrendAttestation.SourceEvidenceRef::stabilityRunId)
                .containsExactlyElementsOf(evidence.sources().stream()
                        .map(TestSuiteStabilityTrendEvidence.RunObservation::stabilityRunId)
                        .toList());
        assertThat(service.verify(evidence, sealed.attestation()))
                .isEqualTo(TestSuiteStabilityTrendAttestationService.Verification.VERIFIED);
    }

    @Test
    void rejectsAReorderedSourceClosureEvenWhenTheOldSignatureIsReused() {
        TestSuiteStabilityTrendAttestation signed = service.seal(evidence).attestation();
        List<TestSuiteStabilityTrendAttestation.SourceEvidenceRef> reversed =
                new ArrayList<>(signed.sourceEvidenceRefs());
        java.util.Collections.reverse(reversed);
        TestSuiteStabilityTrendAttestation forged =
                new TestSuiteStabilityTrendAttestation(
                        signed.schemaVersion(), signed.signatureStatus(),
                        signed.trendAnalysisId(), signed.requestFingerprint(),
                        signed.evidenceFingerprint(), reversed, signed.signedAt(),
                        signed.keyId(), signed.algorithm(), signed.signature(), true);

        assertThat(service.verify(evidence, forged))
                .isEqualTo(TestSuiteStabilityTrendAttestationService.Verification.INVALID);
    }

    @Test
    void rejectsAProducerStatusRewriteBoundToTheOriginalAttestation() {
        TestSuiteStabilityTrendAttestation signed = service.seal(evidence).attestation();
        TestSuiteStabilityTrendEvidence forged = new TestSuiteStabilityTrendEvidence(
                evidence.schemaVersion(), evidence.trendAnalysisId(),
                evidence.requestFingerprint(), evidence.suiteRef(), evidence.fromInclusive(),
                evidence.toExclusive(), evidence.minimumRuns(), evidence.maximumRuns(),
                evidence.observedRuns(), evidence.expiredMatchingRuns(), evidence.completeWindow(),
                TestSuiteStabilityTrendEvidence.Status.INSTABILITY_OBSERVED,
                evidence.sources(), evidence.caseTrends(), evidence.correlationSignals(),
                evidence.causalityStatus(), evidence.diagnostics(), evidence.evaluatedAt());

        assertThat(service.verify(forged, signed))
                .isEqualTo(TestSuiteStabilityTrendAttestationService.Verification.INVALID);
    }

    @Test
    void failsClosedWhenTheSigningAuthorityIsUnavailable() {
        TestSuiteStabilityTrendAttestationService unavailable =
                new TestSuiteStabilityTrendAttestationService(
                        mapper, VisualEvidenceSigner.unavailable(),
                        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

        TestSuiteStabilityTrendAttestationService.SealResult result =
                unavailable.seal(evidence);

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCode()).isEqualTo(
                TestSuiteStabilityTrendAttestationService.SIGNER_UNAVAILABLE);
        assertThat(result.attestation().signatureStatus()).isEqualTo(
                TestSuiteStabilityTrendAttestation.SignatureStatus.VERIFICATION_UNAVAILABLE);
    }
}
