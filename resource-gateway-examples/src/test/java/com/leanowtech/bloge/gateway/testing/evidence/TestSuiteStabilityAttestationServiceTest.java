package com.leanowtech.bloge.gateway.testing.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
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
