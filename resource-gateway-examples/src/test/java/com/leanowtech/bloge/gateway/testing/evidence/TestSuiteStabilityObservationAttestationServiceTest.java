package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures;
import com.leanowtech.bloge.gateway.testing.TestSuiteStabilityTrendProtocolFixtures.CaseMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityRunRecord;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityObservationAttestation;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class TestSuiteStabilityObservationAttestationServiceTest {
    private static final Instant CREATED_AT = Instant.parse("2026-07-19T01:00:00Z");
    private static final Instant SIGNED_AT = CREATED_AT.plusSeconds(5);

    private ObjectMapper mapper;
    private InMemoryVisualEvidenceSigner signer;
    private TestSuiteStabilityAttestationService sourceAttestations;
    private TestSuiteStabilityObservationAttestationService service;
    private TestSuiteStabilityRunRecord source;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        signer = new InMemoryVisualEvidenceSigner();
        sourceAttestations = new TestSuiteStabilityAttestationService(mapper, signer);
        source = TestSuiteStabilityTrendProtocolFixtures.record(
                mapper, sourceAttestations, '4', CREATED_AT, CREATED_AT.plusSeconds(3_600),
                TestSuiteStabilityProtocolFixtures.PLAN_FINGERPRINT,
                CaseMode.STABLE, CaseMode.STABLE, '1', '2');
        service = new TestSuiteStabilityObservationAttestationService(
                mapper, signer, sourceAttestations,
                Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    }

    @Test
    void verifiesSourceBeforeSigningDeterministicPayloadFreeObservation() {
        var first = service.seal(source);
        var second = service.seal(source);

        assertThat(first.verified()).isTrue();
        assertThat(first.observation().evidence().source().stabilityRunId())
                .isEqualTo(source.stabilityRunId());
        assertThat(first.observation().evidence().source().cases()).hasSize(2);
        assertThat(first.observation().evidence().scopeFingerprint())
                .startsWith("sha256:");
        assertThat(first.observation().evidence().observationId())
                .isEqualTo(second.observation().evidence().observationId());
        assertThat(first.observation().evidenceFingerprint())
                .isEqualTo(second.observation().evidenceFingerprint());
        assertThat(service.verify(first.observation()))
                .isEqualTo(TestSuiteStabilityObservationAttestationService.Verification.VERIFIED);
    }

    @Test
    void refusesToObserveARecordWhoseSourceFingerprintWasRewritten() {
        TestSuiteStabilityRunRecord forged = new TestSuiteStabilityRunRecord(
                source.stabilityRunId(), source.clientRequestId(), source.requestFingerprint(),
                source.tenantId(), source.organizationId(), source.projectId(),
                source.environmentId(), source.actorId(), source.classification(),
                TestSuiteStabilityProtocolFixtures.fingerprint('f'), source.evidence(),
                source.attestation(), source.createdAt(), source.expiresAt());

        var result = service.seal(forged);

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCode()).isEqualTo(
                TestSuiteStabilityObservationAttestationService.SOURCE_INVALID);
    }

    @Test
    void rejectsTamperedDetachedObservationSignature() {
        TestSuiteStabilityObservation original = service.seal(source).observation();
        TestSuiteStabilityObservationAttestation signed = original.attestation();
        TestSuiteStabilityObservationAttestation tampered =
                new TestSuiteStabilityObservationAttestation(
                        signed.schemaVersion(), signed.signatureStatus(),
                        signed.observationId(), signed.observationFingerprint(),
                        signed.sourceEvidenceFingerprint(),
                        signed.sourceAttestationFingerprint(), signed.signedAt(),
                        signed.keyId(), signed.algorithm(),
                        Base64.getEncoder().encodeToString(new byte[64]), true);
        TestSuiteStabilityObservation forged = new TestSuiteStabilityObservation(
                original.evidenceFingerprint(), original.evidence(),
                ProtocolFingerprint.of(mapper, tampered), tampered);

        assertThat(service.verify(forged))
                .isEqualTo(TestSuiteStabilityObservationAttestationService.Verification.INVALID);
    }

    @Test
    void failsClosedWithoutASecondSignatureAuthority() {
        TestSuiteStabilityObservationAttestationService unavailable =
                new TestSuiteStabilityObservationAttestationService(
                        mapper, VisualEvidenceSigner.unavailable(), sourceAttestations,
                        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

        var result = unavailable.seal(source);

        assertThat(result.verified()).isFalse();
        assertThat(result.failureCode()).isEqualTo(
                TestSuiteStabilityObservationAttestationService.SIGNER_UNAVAILABLE);
    }
}
