package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeObservationIntegrityTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void signsOnlyAfterIndependentAuthorityVerificationAndReverifiesOnRead() {
        AtomicInteger verifications = new AtomicInteger();
        AuthoritativeOutcomeAuthorityVerifier authority =
                authority(verifications, true);
        AuthoritativeOutcomeObservationIntegrity integrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner.usingClock(
                                DomainFidelityTestFixtures.CLOCK),
                        authority,
                        DomainFidelityTestFixtures.CLOCK);

        AuthoritativeOutcomeObservation signed =
                integrity.sign(
                        AuthoritativeOutcomeTestFixtures.matched());
        AuthoritativeOutcomeObservation verified =
                integrity.verify(signed);

        assertThat(signed.observationFingerprint())
                .startsWith("sha256:");
        assertThat(signed.attestedAt())
                .isEqualTo(DomainFidelityTestFixtures.NOW);
        assertThat(signed.observationSeal().signed()).isTrue();
        assertThat(verified).isEqualTo(signed);
        assertThat(verifications).hasValue(3);
    }

    @Test
    void bindsTrustedAttestationTimeAndRejectsDetachedSealTimeDrift() {
        AuthoritativeOutcomeObservationIntegrity integrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner.usingClock(
                                DomainFidelityTestFixtures.CLOCK),
                        authority(new AtomicInteger(), true),
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeObservation signed =
                integrity.sign(
                        AuthoritativeOutcomeTestFixtures.matched());
        AuthoritativeOutcomeObservation forgedAttestationTime =
                signed.withAttestedAt(
                                signed.attestedAt().plusSeconds(1))
                        .withFingerprint(
                                signed.observationFingerprint())
                        .withObservationSeal(
                                signed.observationSeal());
        VisualRunEvidenceSeal seal =
                signed.observationSeal();
        AuthoritativeOutcomeObservation detachedSealDrift =
                signed.withObservationSeal(
                        new VisualRunEvidenceSeal(
                                seal.schemaVersion(),
                                seal.materialFingerprint(),
                                seal.algorithm(),
                                seal.keyId(),
                                signed.attestedAt()
                                        .plusSeconds(121),
                                seal.signature()));

        assertThatThrownBy(() ->
                integrity.verify(
                        forgedAttestationTime))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
        assertThatThrownBy(() ->
                integrity.verify(
                        detachedSealDrift))
                .isInstanceOf(
                        AuthoritativeOutcomeObservationIntegrity
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        AuthoritativeOutcomeObservationIntegrity
                                .Reason.SIGNING_TIME_INVALID);
    }

    @Test
    void rejectsInvalidGatewaySealBeforeCallingExternalAuthority() {
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeObservation signed =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        authority(new AtomicInteger(), true),
                        DomainFidelityTestFixtures.CLOCK)
                        .sign(
                                AuthoritativeOutcomeTestFixtures
                                        .matched());
        VisualRunEvidenceSeal seal =
                signed.observationSeal();
        AuthoritativeOutcomeObservation invalid =
                signed.withObservationSeal(
                        new VisualRunEvidenceSeal(
                                seal.schemaVersion(),
                                seal.materialFingerprint(),
                                seal.algorithm(),
                                seal.keyId(),
                                seal.signedAt(),
                                Base64.getEncoder()
                                        .encodeToString(
                                                new byte[64])));
        AtomicInteger externalCalls =
                new AtomicInteger();
        AuthoritativeOutcomeObservationIntegrity reader =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        authority(externalCalls, true),
                        DomainFidelityTestFixtures.CLOCK);

        assertThatThrownBy(() ->
                reader.verify(invalid))
                .isInstanceOf(
                        AuthoritativeOutcomeObservationIntegrity
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        AuthoritativeOutcomeObservationIntegrity
                                .Reason.SIGNATURE_INVALID);
        assertThat(externalCalls).hasValue(0);
    }

    @Test
    void rejectsUnavailableAuthorityBeforeTrustingTheGatewaySeal() {
        AuthoritativeOutcomeAuthorityVerifier unavailable =
                new AuthoritativeOutcomeAuthorityVerifier() {
                    @Override
                    public boolean available() {
                        return false;
                    }

                    @Override
                    public void verify(
                            AuthoritativeOutcomeObservation
                                    observation) {
                        throw new AssertionError(
                                "unavailable authority must not verify");
                    }
                };
        AuthoritativeOutcomeObservationIntegrity integrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner.usingClock(
                                DomainFidelityTestFixtures.CLOCK),
                        unavailable,
                        DomainFidelityTestFixtures.CLOCK);

        assertThat(integrity.available()).isFalse();
        assertThatThrownBy(() ->
                integrity.sign(
                        AuthoritativeOutcomeTestFixtures.matched()))
                .isInstanceOf(
                        AuthoritativeOutcomeObservationIntegrity
                                .Violation.class)
                .extracting("reason")
                .isEqualTo(
                        AuthoritativeOutcomeObservationIntegrity
                                .Reason.AUTHORITY_UNAVAILABLE);
    }

    private static AuthoritativeOutcomeAuthorityVerifier authority(
            AtomicInteger verifications,
            boolean available) {
        return new AuthoritativeOutcomeAuthorityVerifier() {
            @Override
            public boolean available() {
                return available;
            }

            @Override
            public void verify(
                    AuthoritativeOutcomeObservation observation) {
                assertThat(observation.authorityWatermarks())
                        .isNotEmpty();
                verifications.incrementAndGet();
            }
        };
    }
}
