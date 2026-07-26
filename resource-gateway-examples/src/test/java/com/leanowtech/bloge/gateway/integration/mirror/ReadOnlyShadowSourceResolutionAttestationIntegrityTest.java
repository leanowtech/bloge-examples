package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyShadowSourceResolutionAttestationIntegrityTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final PayloadFreeEqualityReadOnlyShadowPolicy policy =
            new PayloadFreeEqualityReadOnlyShadowPolicy(mapper);
    private final ReadOnlyShadowSourceResolutionAttestationIntegrity
            integrity =
            new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                    mapper,
                    InMemoryVisualEvidenceSigner.usingClock(
                            Clock.fixed(
                                    ReadOnlyShadowSourceResolutionTestFixtures
                                            .NOW
                                            .plusSeconds(4),
                                    ZoneOffset.UTC)),
                    Clock.fixed(
                            ReadOnlyShadowSourceResolutionTestFixtures
                                    .NOW.plusSeconds(4),
                            ZoneOffset.UTC));

    @Test
    void addressesSignsAndVerifiesCompletePairedResolution() {
        ReadOnlyShadowSourceResolutionAttestation signed =
                integrity.sign(
                        ReadOnlyShadowSourceResolutionTestFixtures
                                .unsigned(policy.reference()));

        assertThat(signed.attestationFingerprint())
                .matches("sha256:[a-f0-9]{64}");
        assertThat(signed.artifactRef().kind())
                .isEqualTo(
                        ReadOnlyShadowSourceResolutionAttestation
                                .ARTIFACT_KIND);
        assertThat(signed.attestationSeal().signed())
                .isTrue();
        assertThat(integrity.verify(signed))
                .isEqualTo(signed);
    }

    @Test
    void rejectsResolvedFactTamperAndNonZeroWriteClaims() {
        ReadOnlyShadowSourceResolutionAttestation signed =
                integrity.sign(
                        ReadOnlyShadowSourceResolutionTestFixtures
                                .unsigned(policy.reference()));
        var changedBaseline =
                ReadOnlyShadowSourceResolutionTestFixtures
                        .resolution(
                                ReadOnlyShadowComparison.SourceRole.BASELINE,
                                "SHADOW_BASELINE_OBSERVATION",
                                "detached-pair:baseline",
                                'f',
                                ReadOnlyShadowSourceResolutionTestFixtures
                                        .NOW.minusSeconds(20),
                                ReadOnlyShadowSourceResolutionTestFixtures
                                        .NOW.plusSeconds(2));
        ReadOnlyShadowSourceResolutionAttestation tampered =
                new ReadOnlyShadowSourceResolutionAttestation(
                        signed.schemaVersion(),
                        signed.attestationFingerprint(),
                        signed.attestationId(),
                        signed.revision(),
                        signed.scope(),
                        signed.requestId(),
                        signed.executionId(),
                        signed.sourceBindingRef(),
                        signed.comparisonPolicyRef(),
                        signed.requestContextFingerprint(),
                        signed.admissionFingerprint(),
                        signed.admittedAt(),
                        signed.confirmedAt(),
                        changedBaseline,
                        signed.candidate(),
                        signed.issuedAt(),
                        signed.attestationSeal());

        assertThatThrownBy(() -> integrity.verify(tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");

        var candidate = signed.candidate();
        assertThatThrownBy(() ->
                new ReadOnlyShadowSourceResolutionAttestation
                        .SourceResolution(
                        candidate.role(),
                        candidate.artifactRef(),
                        candidate.semanticResultFingerprint(),
                        candidate.normalizedFactFingerprints(),
                        candidate.sourceCompletedAt(),
                        candidate.resolvedAt(),
                        candidate.evidenceClass(),
                        candidate.evidenceComplete(),
                        false,
                        -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("write counters");
    }
}
