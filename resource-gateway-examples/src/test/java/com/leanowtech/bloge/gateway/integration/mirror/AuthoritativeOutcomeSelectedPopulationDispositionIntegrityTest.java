package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSelectedPopulationDispositionIntegrityTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    DomainFidelityTestFixtures.CLOCK);

    @Test
    void signsOnlyAfterIndependentDeletionAuthorityVerification() {
        AtomicInteger authorityCalls = new AtomicInteger();
        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                integrity = integrity(
                authority(authorityCalls, true, false));
        AuthoritativeOutcomeSelectedPopulationTestFixtures
                .Population population = population();

        AuthoritativeOutcomeSelectedPopulationDisposition signed =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedDisposition(
                                integrity,
                                population.manifest(),
                                population.members().getFirst(),
                                "deletion-member-1");

        assertThat(signed.dispositionFingerprint())
                .startsWith("sha256:");
        assertThat(signed.dispositionSeal().signed()).isTrue();
        assertThat(signed.attestedAt())
                .isEqualTo(DomainFidelityTestFixtures.NOW);
        assertThat(integrity.verify(signed)).isEqualTo(signed);
        assertThat(authorityCalls).hasValue(3);
        assertThat(signed.toString())
                .doesNotContain(
                        signed.subjectFingerprint(),
                        signed.attributionKeyFingerprint());
    }

    @Test
    void rejectsUnavailableOrRejectingDeletionAuthorityWithoutLeakingCause() {
        AuthoritativeOutcomeSelectedPopulationTestFixtures
                .Population population = population();
        AuthoritativeOutcomeSelectedPopulationDisposition unsigned =
                unsignedDisposition(
                        population,
                        population.members().getFirst());

        assertThatThrownBy(() ->
                integrity(
                        authority(
                                new AtomicInteger(),
                                false,
                                false))
                        .sign(unsigned))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                                .Violation) failure).reason())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                                .Reason.AUTHORITY_UNAVAILABLE);

        assertThatThrownBy(() ->
                integrity(
                        authority(
                                new AtomicInteger(),
                                true,
                                true))
                        .sign(unsigned))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                                .Violation.class)
                .hasMessage(
                        "Authoritative outcome selected member disposition rejected: AUTHORITY_REJECTED")
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                                .Violation) failure).reason())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                                .Reason.AUTHORITY_REJECTED);
    }

    @Test
    void rejectsDetachedSealTimeDriftBeforeCallingExternalAuthority() {
        AtomicInteger writerCalls = new AtomicInteger();
        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                writer = integrity(
                authority(writerCalls, true, false));
        AuthoritativeOutcomeSelectedPopulationTestFixtures
                .Population population = population();
        AuthoritativeOutcomeSelectedPopulationDisposition signed =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedDisposition(
                                writer,
                                population.manifest(),
                                population.members().getFirst(),
                                "deletion-member-1");
        VisualRunEvidenceSeal seal = signed.dispositionSeal();
        AuthoritativeOutcomeSelectedPopulationDisposition forged =
                signed.withDispositionSeal(
                        new VisualRunEvidenceSeal(
                                seal.schemaVersion(),
                                seal.materialFingerprint(),
                                seal.algorithm(),
                                seal.keyId(),
                                seal.signedAt().plus(
                                        Duration.ofMinutes(10)),
                                seal.signature()));
        AtomicInteger readerCalls = new AtomicInteger();
        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                reader = integrity(
                authority(readerCalls, true, false));

        assertThatThrownBy(() -> reader.verify(forged))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                                .Violation) failure).reason())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                                .Reason.SIGNING_TIME_INVALID);
        assertThat(readerCalls).hasValue(0);
    }

    private AuthoritativeOutcomeSelectedPopulationDisposition
    unsignedDisposition(
            AuthoritativeOutcomeSelectedPopulationTestFixtures
                    .Population population,
            AuthoritativeOutcomeSelectedPopulationChunk.Member
                    member) {
        return new AuthoritativeOutcomeSelectedPopulationDisposition(
                AuthoritativeOutcomeSelectedPopulationDisposition
                        .SCHEMA_VERSION,
                "deletion-member-1",
                1,
                "",
                population.manifest().scope(),
                population.manifest().artifactRef(),
                member.unitId(),
                member.stratumId(),
                member.sampleOrdinal(),
                member.inclusionFingerprint(),
                member.subjectFingerprint(),
                member.attributionKeyFingerprint(),
                AuthoritativeOutcomeSelectedPopulationDisposition
                        .Disposition.LEGALLY_DELETED,
                AuthoritativeOutcomeSelectedPopulationDisposition
                        .DeletionReason.LEGAL_RETENTION_EXPIRY,
                DomainFidelityTestFixtures.ref(
                        "OUTCOME_DATA_RETENTION_POLICY",
                        "refund-retention-v1",
                        'a'),
                DomainFidelityTestFixtures.ref(
                        "OUTCOME_MEMBER_DELETION_APPROVAL",
                        "deletion-member-1-approval",
                        'b'),
                DomainFidelityTestFixtures.ref(
                        "OUTCOME_DELETION_AUTHORITY_SET",
                        "refund-deletion-authorities",
                        'c'),
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .RECONCILED_AT,
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .RECONCILED_AT,
                VisualRunEvidenceSeal.unsigned());
    }

    private AuthoritativeOutcomeSelectedPopulationTestFixtures
            .Population population() {
        return AuthoritativeOutcomeSelectedPopulationTestFixtures
                .signedPopulation(
                        new AuthoritativeOutcomeSelectedPopulationIntegrity(
                                mapper,
                                signer,
                                AuthoritativeOutcomeSelectedPopulationTestFixtures
                                        .populationAuthority(),
                                DomainFidelityTestFixtures.CLOCK));
    }

    private AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
    integrity(
            AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier
                    authority) {
        return new
                AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
                mapper,
                signer,
                authority,
                DomainFidelityTestFixtures.CLOCK);
    }

    private static
    AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier
    authority(
            AtomicInteger calls,
            boolean available,
            boolean reject) {
        return new
                AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier() {
                    @Override
                    public boolean available() {
                        return available;
                    }

                    @Override
                    public void verify(
                            AuthoritativeOutcomeSelectedPopulationDisposition
                                    disposition) {
                        calls.incrementAndGet();
                        if (reject) {
                            throw new IllegalStateException(
                                    "sensitive legal authority reason");
                        }
                    }
                };
    }
}
