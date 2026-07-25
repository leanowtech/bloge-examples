package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRehearsalBatchEvidenceIntegrityServiceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            new InMemoryVisualEvidenceSigner();
    private final ScenarioRehearsalBatchEvidenceIntegrityService
            service =
            new ScenarioRehearsalBatchEvidenceIntegrityService(
                    mapper,
                    signer,
                    Clock.fixed(
                            ScenarioRehearsalBatchEvidenceTestFixtures
                                    .COMPLETED.plusSeconds(1),
                            ZoneOffset.UTC));

    @Test
    void sealsAndImmediatelyVerifiesTheCompleteTerminalIndex() {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                material =
                ScenarioRehearsalBatchEvidenceTestFixtures.material(
                        mapper);

        ScenarioRehearsalBatchEvidenceIntegrityService.SealResult
                sealed = service.seal(
                material.request(),
                material.manifest(),
                material.job(),
                material.items());

        assertThat(sealed.verified()).isTrue();
        assertThat(sealed.bundle().index().job())
                .isEqualTo(material.job());
        assertThat(sealed.bundle().index().items())
                .isEqualTo(material.items());
        assertThat(service.verify(sealed.bundle()))
                .isEqualTo(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .Verification.VERIFIED);
        assertThat(mapper.valueToTree(sealed.bundle()).toString())
                .doesNotContain(
                        "\"input\"",
                        "\"output\"",
                        "\"context\"",
                        "\"fixturePayload\"",
                        "\"credential\"");
    }

    @Test
    void frozenFinalizationCoordinatesProduceExactlyReplayableEvidence() {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                material =
                ScenarioRehearsalBatchEvidenceTestFixtures.material(
                        mapper);
        Instant signedAt =
                ScenarioRehearsalBatchEvidenceTestFixtures
                        .COMPLETED.plusSeconds(5);

        ScenarioRehearsalBatchEvidenceIntegrityService.SealResult
                first = service.seal(
                material.request(),
                material.manifest(),
                material.job(),
                material.items(),
                signedAt,
                "scenario-batch-finalization:job-1");
        ScenarioRehearsalBatchEvidenceIntegrityService.SealResult
                replay = service.seal(
                material.request(),
                material.manifest(),
                material.job(),
                material.items(),
                signedAt,
                "scenario-batch-finalization:job-1");

        assertThat(first.verified()).isTrue();
        assertThat(replay.bundle()).isEqualTo(first.bundle());
        assertThat(replay.bundle().attestation().signedAt())
                .isEqualTo(signedAt);
    }

    @Test
    void rejectsRequestAndTerminalJobTampering() {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                material =
                ScenarioRehearsalBatchEvidenceTestFixtures.material(
                        mapper);
        ScenarioRehearsalBatchEvidenceBundle original =
                service.seal(
                        material.request(),
                        material.manifest(),
                        material.job(),
                        material.items()).bundle();
        ScenarioRehearsalBatchEvidenceIndex altered =
                original.index().withFingerprint(
                        ScenarioRehearsalBatchEvidenceTestFixtures
                                .fingerprint('9'));
        ScenarioRehearsalBatchEvidenceAttestation attestation =
                original.attestation();
        ScenarioRehearsalBatchEvidenceAttestation alteredAttestation =
                new ScenarioRehearsalBatchEvidenceAttestation(
                        attestation.schemaVersion(),
                        attestation.signatureStatus(),
                        attestation.jobId(),
                        attestation.requestFingerprint(),
                        attestation.manifestFingerprint(),
                        attestation.terminalJobFingerprint(),
                        altered.indexFingerprint(),
                        attestation.signedAt(),
                        attestation.keyId(),
                        attestation.algorithm(),
                        attestation.signature(),
                        true);
        ScenarioRehearsalBatchEvidenceBundle tampered =
                new ScenarioRehearsalBatchEvidenceBundle(
                        original.schemaVersion(),
                        original.bundleFingerprint(),
                        original.payloadPolicy(),
                        alteredAttestation,
                        altered);

        assertThat(service.verify(tampered))
                .isEqualTo(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .Verification.INVALID);
    }

    @Test
    void failsClosedWhenSigningAuthorityOrClockIsUnavailable() {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                material =
                ScenarioRehearsalBatchEvidenceTestFixtures.material(
                        mapper);
        ScenarioRehearsalBatchEvidenceIntegrityService unavailable =
                new ScenarioRehearsalBatchEvidenceIntegrityService(
                        mapper,
                        VisualEvidenceSigner.unavailable(),
                        Clock.systemUTC());
        ScenarioRehearsalBatchEvidenceIntegrityService stale =
                new ScenarioRehearsalBatchEvidenceIntegrityService(
                        mapper,
                        signer,
                        Clock.fixed(
                                ScenarioRehearsalBatchEvidenceTestFixtures
                                        .CREATED,
                                ZoneOffset.UTC));

        assertThat(unavailable.seal(
                material.request(),
                material.manifest(),
                material.job(),
                material.items()).failureCode())
                .isEqualTo(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .SIGNER_UNAVAILABLE);
        assertThat(stale.seal(
                material.request(),
                material.manifest(),
                material.job(),
                material.items()).failureCode())
                .isEqualTo(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .MATERIAL_INVALID);
    }

    @Test
    void separatesUnknownVerificationAuthorityFromMalformedMaterial() {
        ScenarioRehearsalBatchEvidenceTestFixtures.Material
                material =
                ScenarioRehearsalBatchEvidenceTestFixtures.material(
                        mapper);
        ScenarioRehearsalBatchEvidenceBundle bundle =
                service.seal(
                        material.request(),
                        material.manifest(),
                        material.job(),
                        material.items()).bundle();

        assertThat(new ScenarioRehearsalBatchEvidenceIntegrityService(
                mapper,
                VisualEvidenceSigner.unavailable(),
                Clock.systemUTC()).verify(bundle))
                .isEqualTo(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .Verification.UNAVAILABLE);
        assertThat(new ScenarioRehearsalBatchEvidenceIntegrityService(
                mapper,
                new InMemoryVisualEvidenceSigner(),
                Clock.systemUTC()).verify(bundle))
                .isEqualTo(
                        ScenarioRehearsalBatchEvidenceIntegrityService
                                .Verification.INVALID);
    }
}
