package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioRehearsalEvidenceIntegrityServiceTest {
    private static final Instant SIGNED_AT =
            ScenarioRehearsalEvidenceTestFixtures.COMPLETED
                    .plusSeconds(5);
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            new InMemoryVisualEvidenceSigner();
    private final ScenarioRehearsalEvidenceIntegrityService service =
            new ScenarioRehearsalEvidenceIntegrityService(
                    mapper,
                    signer,
                    Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    private final CapabilitySnapshot.Scope scope =
            MirrorPersistenceTestFixtures.scope("org-a");

    @Test
    void sealsAndImmediatelyVerifiesOnePayloadFreeAggregate() {
        ScenarioRehearsalResult result =
                ScenarioRehearsalEvidenceTestFixtures.result(
                        mapper, scope, '5');

        ScenarioRehearsalEvidenceIntegrityService.SealResult sealed =
                service.seal(
                        runId(),
                        result);

        assertThat(sealed.verified()).isTrue();
        assertThat(sealed.bundle().payloadPolicy())
                .isEqualTo(
                        ScenarioRehearsalEvidenceBundle
                                .PayloadPolicy.HASH_ONLY);
        assertThat(sealed.attestation().signedAt())
                .isEqualTo(SIGNED_AT);
        assertThat(sealed.attestation().runId())
                .isEqualTo(
                        runId());
        assertThat(service.verify(sealed.bundle()))
                .isEqualTo(
                        ScenarioRehearsalEvidenceIntegrityService
                                .Verification.VERIFIED);
        assertThat(mapper.valueToTree(sealed.bundle()).toString())
                .doesNotContain(
                        "\"input\"", "\"output\"", "\"context\"",
                        "\"fixturePayload\"", "\"entity\"");
    }

    @Test
    void rejectsSignatureAndSigningTimeTampering() {
        ScenarioRehearsalEvidenceBundle original =
                service.seal(
                        runId(),
                        ScenarioRehearsalEvidenceTestFixtures.result(
                                mapper, scope, '5'))
                        .bundle();
        ScenarioRehearsalEvidenceAttestation attestation =
                original.attestation();
        ScenarioRehearsalEvidenceAttestation altered =
                new ScenarioRehearsalEvidenceAttestation(
                        attestation.schemaVersion(),
                        attestation.signatureStatus(),
                        attestation.runId(),
                        attestation.requestId(),
                        attestation.compiledPlanFingerprint(),
                        attestation.resultFingerprint(),
                        attestation.signedAt().plusSeconds(1),
                        attestation.keyId(),
                        attestation.algorithm(),
                        attestation.signature(),
                        true);
        ScenarioRehearsalEvidenceBundle tampered =
                new ScenarioRehearsalEvidenceBundle(
                        original.schemaVersion(),
                        original.bundleFingerprint(),
                        original.payloadPolicy(),
                        altered,
                        original.result());

        assertThat(service.verify(tampered))
                .isEqualTo(
                        ScenarioRehearsalEvidenceIntegrityService
                                .Verification.INVALID);
    }

    @Test
    void failsClosedWhenSigningAuthorityIsUnavailableOrClockIsStale() {
        ScenarioRehearsalResult result =
                ScenarioRehearsalEvidenceTestFixtures.result(
                        mapper, scope, '5');
        ScenarioRehearsalEvidenceIntegrityService unavailable =
                new ScenarioRehearsalEvidenceIntegrityService(
                        mapper,
                        VisualEvidenceSigner.unavailable(),
                        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
        ScenarioRehearsalEvidenceIntegrityService stale =
                new ScenarioRehearsalEvidenceIntegrityService(
                        mapper,
                        signer,
                        Clock.fixed(
                                ScenarioRehearsalEvidenceTestFixtures
                                        .STARTED,
                                ZoneOffset.UTC));

        assertThat(unavailable.seal(
                runId(),
                result).failureCode())
                .isEqualTo(
                        ScenarioRehearsalEvidenceIntegrityService
                                .SIGNER_UNAVAILABLE);
        assertThat(stale.seal(
                runId(),
                result).failureCode())
                .isEqualTo(
                        ScenarioRehearsalEvidenceIntegrityService
                                .MATERIAL_INVALID);
    }

    @Test
    void separatesInvalidMaterialFromAnUnavailableVerificationKey() {
        ScenarioRehearsalEvidenceBundle original =
                service.seal(
                        runId(),
                        ScenarioRehearsalEvidenceTestFixtures.result(
                                mapper, scope, '5'))
                        .bundle();
        ScenarioRehearsalEvidenceIntegrityService unknownKey =
                new ScenarioRehearsalEvidenceIntegrityService(
                        mapper,
                        new InMemoryVisualEvidenceSigner(),
                        Clock.systemUTC());

        assertThat(unknownKey.verify(original))
                .isEqualTo(
                        ScenarioRehearsalEvidenceIntegrityService
                                .Verification.INVALID);
        assertThat(new ScenarioRehearsalEvidenceIntegrityService(
                mapper,
                VisualEvidenceSigner.unavailable(),
                Clock.systemUTC()).verify(original))
                .isEqualTo(
                        ScenarioRehearsalEvidenceIntegrityService
                                .Verification.UNAVAILABLE);
    }

    @Test
    void rejectsANonDerivedRunIdentityBeforeSigning() {
        ScenarioRehearsalEvidenceIntegrityService.SealResult sealed =
                service.seal(
                        "scenario-" + "9".repeat(64),
                        ScenarioRehearsalEvidenceTestFixtures.result(
                                mapper, scope, '5'));

        assertThat(sealed.verified()).isFalse();
        assertThat(sealed.failureCode())
                .isEqualTo(
                        ScenarioRehearsalEvidenceIntegrityService
                                .MATERIAL_INVALID);
    }

    private String runId() {
        return ScenarioRehearsalRunIdentity.derive(
                mapper,
                scope,
                ScenarioRehearsalEvidenceTestFixtures.REQUEST_ID);
    }
}
