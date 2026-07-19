package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityCrossRetentionTrendProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityCrossRetentionTrendAnalysisRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationLedgerRange;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityCrossRetentionTrendEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityTrendEvidence;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteStabilityCrossRetentionTrendAttestationServiceTest {
    private ObjectMapper mapper;
    private TestSuiteStabilityCrossRetentionTrendEvidence evidence;
    private TestSuiteStabilityCrossRetentionTrendAttestationService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        InMemoryVisualEvidenceSigner signer = new InMemoryVisualEvidenceSigner();
        var sources = new TestSuiteStabilityAttestationService(mapper, signer);
        var observations = new TestSuiteStabilityObservationAttestationService(
                mapper, signer, sources);
        var fixture = TestSuiteStabilityCrossRetentionTrendProtocolFixtures.range(
                mapper, sources, observations, '1', '2');
        var request = new TestSuiteStabilityCrossRetentionTrendAnalysisRequest(
                TestSuiteStabilityCrossRetentionTrendAnalysisRequest.SCHEMA_VERSION,
                TestSuiteStabilityProtocolFixtures.SUITE_REF, 0, 2, 10, "");
        String requestFingerprint = ProtocolFingerprint.of(mapper, request);
        var projection = new TestSuiteStabilityTrendEvidenceEvaluator(mapper).project(
                fixture.entries().stream()
                        .map(value -> value.observation().evidence().source()).toList(),
                2, true, List.of());
        String trendId = "stability-cross-retention-trend-"
                + ProtocolFingerprint.of(mapper, new Identity(
                TestSuiteStabilityCrossRetentionTrendEvidence.SCHEMA_VERSION,
                requestFingerprint, fixture.range().rangeFingerprint()))
                .substring("sha256:".length());
        evidence = new TestSuiteStabilityCrossRetentionTrendEvidence(
                TestSuiteStabilityCrossRetentionTrendEvidence.SCHEMA_VERSION,
                trendId, requestFingerprint, request.suiteRef(), 2, 10, 2,
                TestSuiteStabilityCrossRetentionTrendEvidence.SourceOrder
                        .SOURCE_CREATED_AT_THEN_STABILITY_RUN_ID,
                fixture.range(), projection.status(), projection.caseTrends(),
                projection.correlationSignals(),
                TestSuiteStabilityTrendEvidence.CausalityStatus.NOT_PROVEN,
                projection.diagnostics(), fixture.range().observedAt());
        service = new TestSuiteStabilityCrossRetentionTrendAttestationService(mapper, signer);
    }

    @Test
    void sealsAndVerifiesExactRangeAndObservationClosure() {
        var sealed = service.seal(evidence);

        assertThat(sealed.verified()).isTrue();
        assertThat(sealed.attestation().rangeFingerprint()).isEqualTo(
                evidence.range().rangeFingerprint());
        assertThat(sealed.attestation().observationRefs()).hasSize(2);
        assertThat(service.verify(evidence, sealed.attestation()))
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendAttestationService
                        .Verification.VERIFIED);
    }

    @Test
    void rejectsRewrittenObservationClosureAndDetachedSignature() {
        var original = service.seal(evidence).attestation();
        var missingSource = copy(original, original.observationRefs().subList(1, 2),
                original.signature());
        var invalidSignature = copy(original, original.observationRefs(), "invalid-signature");

        assertThat(service.verify(evidence, missingSource))
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendAttestationService
                        .Verification.INVALID);
        assertThat(service.verify(evidence, invalidSignature))
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendAttestationService
                        .Verification.INVALID);
    }

    @Test
    void failsClosedWithoutRangeSigner() {
        var unavailable = new TestSuiteStabilityCrossRetentionTrendAttestationService(
                mapper, VisualEvidenceSigner.unavailable());

        var sealed = unavailable.seal(evidence);

        assertThat(sealed.verified()).isFalse();
        assertThat(sealed.failureCode()).isEqualTo(
                TestSuiteStabilityCrossRetentionTrendAttestationService.SIGNER_UNAVAILABLE);
        assertThat(sealed.attestation().terminallyVerifiable()).isFalse();
    }

    @Test
    void refusesToSignARewrittenRangeFingerprint() {
        TestSuiteStabilityObservationLedgerRange range = evidence.range();
        TestSuiteStabilityObservationLedgerRange forgedRange =
                new TestSuiteStabilityObservationLedgerRange(
                        range.schemaVersion(), range.scopeFingerprint(), range.suiteRef(),
                        range.floorSequence(), range.floorPreviousObservationId(),
                        range.floorPreviousEntryFingerprint(), range.floorObservationId(),
                        range.floorEntryFingerprint(), range.head(), range.afterSequence(),
                        range.previousObservationId(), range.previousEntryFingerprint(),
                        range.entries(), range.hasMore(), range.observedAt(),
                        TestSuiteStabilityProtocolFixtures.fingerprint('f'));
        TestSuiteStabilityCrossRetentionTrendEvidence forged = copy(forgedRange);

        var sealed = service.seal(forged);

        assertThat(sealed.verified()).isFalse();
        assertThat(sealed.failureCode()).isEqualTo(
                TestSuiteStabilityCrossRetentionTrendAttestationService.EVIDENCE_INVALID);
        assertThat(service.verify(forged, sealed.attestation()))
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendAttestationService
                        .Verification.INVALID);
        assertThat(service.verify(forged, service.seal(evidence).attestation()))
                .isEqualTo(TestSuiteStabilityCrossRetentionTrendAttestationService
                        .Verification.INVALID);
    }

    private TestSuiteStabilityCrossRetentionTrendEvidence copy(
            TestSuiteStabilityObservationLedgerRange range) {
        return new TestSuiteStabilityCrossRetentionTrendEvidence(
                evidence.schemaVersion(), evidence.trendAnalysisId(),
                evidence.requestFingerprint(), evidence.suiteRef(), evidence.minimumRuns(),
                evidence.maximumRuns(), evidence.observedRuns(), evidence.sourceOrder(), range,
                evidence.status(), evidence.caseTrends(), evidence.correlationSignals(),
                evidence.causalityStatus(), evidence.diagnostics(), evidence.evaluatedAt());
    }

    private static TestSuiteStabilityCrossRetentionTrendAttestation copy(
            TestSuiteStabilityCrossRetentionTrendAttestation source,
            List<TestSuiteStabilityCrossRetentionTrendAttestation.ObservationRef> observations,
            String signature) {
        return new TestSuiteStabilityCrossRetentionTrendAttestation(
                source.schemaVersion(), source.signatureStatus(), source.trendAnalysisId(),
                source.requestFingerprint(), source.evidenceFingerprint(),
                source.rangeFingerprint(), observations, source.signedAt(), source.keyId(),
                source.algorithm(), signature, source.independentlyVerifiable());
    }

    private record Identity(
            String schemaVersion,
            String requestFingerprint,
            String rangeFingerprint) {
    }
}
