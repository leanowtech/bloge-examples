package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadOnlyShadowSourceResolutionAttestationServiceTest {
    @Test
    void resolvesOnlyExactScopeRevisionAndFingerprint() {
        ObjectMapper mapper =
                new ObjectMapper().findAndRegisterModules();
        var policy =
                new PayloadFreeEqualityReadOnlyShadowPolicy(
                        mapper);
        Clock clock = Clock.fixed(
                ReadOnlyShadowSourceResolutionTestFixtures
                        .NOW.plusSeconds(4),
                ZoneOffset.UTC);
        ReadOnlyShadowSourceResolutionAttestation signed =
                new ReadOnlyShadowSourceResolutionAttestationIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner
                                .usingClock(clock),
                        clock)
                        .sign(
                                ReadOnlyShadowSourceResolutionTestFixtures
                                        .unsigned(
                                                policy.reference()));
        var repository =
                mock(ReadOnlyShadowSourceResolutionAttestationRepository.class);
        when(repository.find(
                signed.scope(),
                signed.attestationId(),
                signed.revision()))
                .thenReturn(Optional.of(signed));
        var service =
                new ReadOnlyShadowSourceResolutionAttestationService(
                        repository);

        assertThat(service.resolve(
                signed.scope(),
                signed.artifactRef()))
                .isEqualTo(signed);
        MirrorArtifactRef drifted =
                new MirrorArtifactRef(
                        signed.artifactRef().kind(),
                        signed.attestationId(),
                        signed.revision(),
                        ReadOnlyShadowSourceResolutionTestFixtures
                                .fingerprint('f'));
        assertThatThrownBy(() ->
                service.resolve(signed.scope(), drifted))
                .isInstanceOfSatisfying(
                        ReadOnlyShadowSourceResolutionAttestationService
                                .Failure.class,
                        failure -> assertThat(failure.reason())
                                .isEqualTo(
                                        ReadOnlyShadowSourceResolutionAttestationService
                                                .Reason.REFERENCE_MISMATCH));
    }
}
