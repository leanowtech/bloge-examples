package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainFidelityProfileIntegrityTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void signsWithStableMaterialAndRecoversAnAlreadySignedProfile() {
        DomainFidelityProfileIntegrity integrity =
                DomainFidelityTestFixtures.integrity(mapper);
        DomainFidelityInventory inventory =
                DomainFidelityTestFixtures.inventory(
                        mapper,
                        DomainFidelityTestFixtures.scope("support"),
                        1,
                        DomainFidelityTestFixtures.units());
        DomainFidelityProfile unsigned =
                DomainFidelityProfileProjector.project(
                        mapper,
                        inventory,
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory),
                        DomainFidelityTestFixtures
                                .policy().projectionPolicy(),
                        DomainFidelityTestFixtures.NOW);

        DomainFidelityProfile signed =
                integrity.sign(unsigned);

        assertThat(signed.profileSeal().signed()).isTrue();
        assertThat(signed.profileSeal()
                .materialFingerprint())
                .isEqualTo(
                        signed.attestationMaterialFingerprint(
                                mapper));
        assertThat(integrity.sign(signed)).isSameAs(signed);
        assertThat(integrity.verify(signed)).isSameAs(signed);
    }

    @Test
    void rejectsUnsignedAndInvalidSignatures() {
        DomainFidelityProfileIntegrity integrity =
                DomainFidelityTestFixtures.integrity(mapper);
        DomainFidelityInventory inventory =
                DomainFidelityTestFixtures.inventory(
                        mapper,
                        DomainFidelityTestFixtures.scope("support"),
                        1,
                        DomainFidelityTestFixtures.units());
        DomainFidelityProfile unsigned =
                DomainFidelityProfileProjector.project(
                        mapper,
                        inventory,
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory),
                        DomainFidelityTestFixtures
                                .policy().projectionPolicy(),
                        DomainFidelityTestFixtures.NOW);

        assertReason(
                () -> integrity.verify(unsigned),
                DomainFidelityProfileIntegrity.Reason.UNSIGNED);
        VisualRunEvidenceSeal valid =
                integrity.sign(unsigned).profileSeal();
        VisualRunEvidenceSeal forged =
                new VisualRunEvidenceSeal(
                        "",
                        valid.materialFingerprint(),
                        valid.algorithm(),
                        valid.keyId(),
                        valid.signedAt(),
                        Base64.getEncoder().encodeToString(
                                new byte[64]));
        assertReason(
                () -> integrity.verify(
                        unsigned.withProfileSeal(forged)),
                DomainFidelityProfileIntegrity.Reason
                        .SIGNATURE_INVALID);
    }

    @Test
    void rejectsProviderApprovedSignatureWithImpossibleFutureTime() {
        VisualEvidenceSigner permissive =
                new VisualEvidenceSigner() {
                    @Override
                    public VisualRunEvidenceSeal seal(
                            String materialFingerprint) {
                        return new VisualRunEvidenceSeal(
                                "",
                                materialFingerprint,
                                "Ed25519",
                                "permissive",
                                DomainFidelityTestFixtures.NOW.plus(
                                        Duration.ofHours(1)),
                                Base64.getEncoder()
                                        .encodeToString(
                                                new byte[64]));
                    }

                    @Override
                    public Verification verify(
                            VisualRunEvidenceSeal seal,
                            String actualMaterialFingerprint) {
                        return new Verification(
                                true, "VERIFIED", "");
                    }

                    @Override
                    public Optional<VerificationKey> key(
                            String keyId) {
                        return Optional.empty();
                    }

                    @Override
                    public boolean available() {
                        return true;
                    }
                };
        DomainFidelityProfileIntegrity integrity =
                new DomainFidelityProfileIntegrity(
                        mapper,
                        permissive,
                        DomainFidelityTestFixtures.CLOCK);
        DomainFidelityInventory inventory =
                DomainFidelityTestFixtures.inventory(
                        mapper,
                        DomainFidelityTestFixtures.scope("support"),
                        1,
                        DomainFidelityTestFixtures.units());
        DomainFidelityProfile unsigned =
                DomainFidelityProfileProjector.project(
                        mapper,
                        inventory,
                        DomainFidelityTestFixtures
                                .passingMeasurements(inventory),
                        DomainFidelityTestFixtures
                                .policy().projectionPolicy(),
                        DomainFidelityTestFixtures.NOW);

        assertReason(
                () -> integrity.sign(unsigned),
                DomainFidelityProfileIntegrity.Reason
                        .SIGNING_TIME_INVALID);
    }

    private static void assertReason(
            Runnable action,
            DomainFidelityProfileIntegrity.Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        DomainFidelityProfileIntegrity
                                .Violation.class)
                .extracting(failure ->
                        ((DomainFidelityProfileIntegrity
                                .Violation) failure)
                                .reason())
                .isEqualTo(reason);
    }
}
