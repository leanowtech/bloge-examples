package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSelectedPopulationIntegrityTest {
    private static final Instant SELECTED_AT =
            Instant.parse("2026-06-30T23:55:00Z");
    private static final Instant NOW =
            DomainFidelityTestFixtures.NOW;

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void signsAndVerifiesCompleteChunkedPopulationThroughBothAuthorities() {
        AtomicInteger authorityCalls = new AtomicInteger();
        AuthoritativeOutcomeSelectedPopulationIntegrity
                integrity = integrity(new
                AuthoritativeOutcomeSelectedPopulationAuthorityVerifier() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public void verify(
                            AuthoritativeOutcomeSelectedPopulationManifest
                                    manifest,
                            List<AuthoritativeOutcomeSelectedPopulationChunk>
                                    chunks) {
                        authorityCalls.incrementAndGet();
                        assertThat(manifest.totalSelectedPopulation())
                                .isEqualTo(3);
                        assertThat(chunks)
                                .extracting(
                                        AuthoritativeOutcomeSelectedPopulationChunk
                                                ::chunkIndex)
                                .containsExactly(0, 1);
                    }
                });
        Population population = population();

        AuthoritativeOutcomeSelectedPopulationManifest signed =
                integrity.sign(
                        population.manifest(),
                        population.chunks());
        AuthoritativeOutcomeSelectedPopulationManifest verified =
                integrity.verify(
                        signed, population.chunks());

        assertThat(verified.manifestFingerprint())
                .isEqualTo(
                        verified.calculateFingerprint(mapper));
        assertThat(verified.manifestSeal().signed()).isTrue();
        assertThat(verified.totalEligiblePopulation())
                .isEqualTo(15);
        assertThat(authorityCalls).hasValue(3);
        assertThat(verified.toString())
                .doesNotContain(
                        fingerprint('a'),
                        fingerprint('b'),
                        fingerprint('c'));
    }

    @Test
    void rejectsMissingChunkAndCrossChunkMemberEquivocation() {
        AuthoritativeOutcomeSelectedPopulationIntegrity
                integrity = integrity(authority());
        Population population = population();
        AuthoritativeOutcomeSelectedPopulationManifest signed =
                integrity.sign(
                        population.manifest(),
                        population.chunks());

        assertThatThrownBy(() ->
                integrity.verifyLocally(
                        signed,
                        List.of(population.chunks().getFirst())))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation) failure).reason())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Reason.CHUNK_CLOSURE_INVALID);

        AuthoritativeOutcomeSelectedPopulationChunk duplicate =
                integrity.sealChunk(
                        chunk(
                                1,
                                3,
                                List.of(member(
                                        3,
                                        "refund-escalation",
                                        "large",
                                        1,
                                        'a',
                                        'd',
                                        'a'))));
        AuthoritativeOutcomeSelectedPopulationManifest
                duplicateManifest = manifest(
                population.chunks().getFirst(),
                duplicate);

        assertThatThrownBy(() ->
                integrity.sign(
                        duplicateManifest,
                        List.of(
                                population.chunks().getFirst(),
                                duplicate)))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation) failure).reason())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Reason.MEMBER_EQUIVOCATION);
    }

    @Test
    void rejectsStratumDenominatorDriftAndChunkTampering() {
        AuthoritativeOutcomeSelectedPopulationIntegrity
                integrity = integrity(authority());
        AuthoritativeOutcomeSelectedPopulationChunk first =
                integrity.sealChunk(
                        chunk(
                                0,
                                1,
                                List.of(
                                        member(
                                                1,
                                                "refund-boundary",
                                                "small",
                                                1,
                                                'a',
                                                'a',
                                                'a'),
                                        member(
                                                2,
                                                "refund-boundary",
                                                "small",
                                                2,
                                                'b',
                                                'b',
                                                'b'))));
        AuthoritativeOutcomeSelectedPopulationChunk wrongStratum =
                integrity.sealChunk(
                        chunk(
                                1,
                                3,
                                List.of(member(
                                        3,
                                        "refund-escalation",
                                        "unknown",
                                        1,
                                        'c',
                                        'c',
                                        'c'))));
        AuthoritativeOutcomeSelectedPopulationManifest
                wrongManifest = manifest(first, wrongStratum);

        assertThatThrownBy(() ->
                integrity.sign(
                        wrongManifest,
                        List.of(first, wrongStratum)))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation) failure).reason())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Reason.STRATUM_DENOMINATOR_INVALID);

        Population population = population();
        AuthoritativeOutcomeSelectedPopulationManifest signed =
                integrity.sign(
                        population.manifest(),
                        population.chunks());
        AuthoritativeOutcomeSelectedPopulationChunk original =
                population.chunks().getFirst();
        AuthoritativeOutcomeSelectedPopulationChunk tampered =
                new AuthoritativeOutcomeSelectedPopulationChunk(
                        original.schemaVersion(),
                        original.chunkId(),
                        original.chunkFingerprint(),
                        original.populationId(),
                        original.populationRevision(),
                        original.scope(),
                        original.inventoryRef(),
                        original.cohortRef(),
                        original.samplingFrameRef(),
                        original.selectedAt(),
                        original.chunkIndex(),
                        original.firstGlobalOrdinal(),
                        List.of(
                                original.members().getFirst(),
                                member(
                                        2,
                                        "refund-boundary",
                                        "small",
                                        2,
                                        'd',
                                        'd',
                                        'd')));

        assertThatThrownBy(() ->
                integrity.verifyLocally(
                        signed,
                        List.of(
                                tampered,
                                population.chunks().get(1))))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation) failure).reason())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Reason.STRUCTURE_INVALID);
    }

    @Test
    void failsClosedWhenSelectionAuthorityIsUnavailableOrRejects() {
        Population population = population();
        AuthoritativeOutcomeSelectedPopulationIntegrity
                unavailable = integrity(new
                AuthoritativeOutcomeSelectedPopulationAuthorityVerifier() {
                    @Override
                    public boolean available() {
                        return false;
                    }

                    @Override
                    public void verify(
                            AuthoritativeOutcomeSelectedPopulationManifest
                                    manifest,
                            List<AuthoritativeOutcomeSelectedPopulationChunk>
                                    chunks) {
                        throw new AssertionError(
                                "unavailable authority must not be invoked");
                    }
                });

        assertThatThrownBy(() ->
                unavailable.sign(
                        population.manifest(),
                        population.chunks()))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation) failure).reason())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Reason.AUTHORITY_UNAVAILABLE);

        AuthoritativeOutcomeSelectedPopulationIntegrity
                rejecting = integrity(new
                AuthoritativeOutcomeSelectedPopulationAuthorityVerifier() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public void verify(
                            AuthoritativeOutcomeSelectedPopulationManifest
                                    manifest,
                            List<AuthoritativeOutcomeSelectedPopulationChunk>
                                    chunks) {
                        throw new IllegalStateException(
                                "sensitive customer rejection");
                    }
                });

        assertThatThrownBy(() ->
                rejecting.sign(
                        population.manifest(),
                        population.chunks()))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation.class)
                .extracting(
                        Throwable::getMessage,
                        failure ->
                                ((AuthoritativeOutcomeSelectedPopulationIntegrity
                                        .Violation) failure)
                                        .reason())
                .containsExactly(
                        "Authoritative outcome selected population rejected: AUTHORITY_REJECTED",
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Reason.AUTHORITY_REJECTED);
    }

    @Test
    void rejectsForgedResourceGatewaySealAndSigningTime() {
        AuthoritativeOutcomeSelectedPopulationIntegrity
                integrity = integrity(authority());
        Population population = population();
        AuthoritativeOutcomeSelectedPopulationManifest signed =
                integrity.sign(
                        population.manifest(),
                        population.chunks());
        VisualRunEvidenceSeal seal = signed.manifestSeal();
        VisualRunEvidenceSeal forgedTime =
                new VisualRunEvidenceSeal(
                        seal.schemaVersion(),
                        seal.materialFingerprint(),
                        seal.algorithm(),
                        seal.keyId(),
                        seal.signedAt().plusSeconds(600),
                        seal.signature());
        AuthoritativeOutcomeSelectedPopulationManifest forged =
                signed.withManifestSeal(forgedTime);

        assertThatThrownBy(() ->
                integrity.verifyLocally(
                        forged, population.chunks()))
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Violation) failure).reason())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationIntegrity
                                .Reason.SIGNING_TIME_INVALID);
    }

    private Population population() {
        AuthoritativeOutcomeSelectedPopulationIntegrity
                integrity = integrity(authority());
        AuthoritativeOutcomeSelectedPopulationChunk first =
                integrity.sealChunk(
                        chunk(
                                0,
                                1,
                                List.of(
                                        member(
                                                1,
                                                "refund-boundary",
                                                "small",
                                                1,
                                                'a',
                                                'a',
                                                'a'),
                                        member(
                                                2,
                                                "refund-boundary",
                                                "small",
                                                2,
                                                'b',
                                                'b',
                                                'b'))));
        AuthoritativeOutcomeSelectedPopulationChunk second =
                integrity.sealChunk(
                        chunk(
                                1,
                                3,
                                List.of(member(
                                        3,
                                        "refund-escalation",
                                        "large",
                                        1,
                                        'c',
                                        'c',
                                        'c'))));
        return new Population(
                manifest(first, second),
                List.of(first, second));
    }

    private AuthoritativeOutcomeSelectedPopulationManifest
    manifest(
            AuthoritativeOutcomeSelectedPopulationChunk first,
            AuthoritativeOutcomeSelectedPopulationChunk second) {
        return new AuthoritativeOutcomeSelectedPopulationManifest(
                AuthoritativeOutcomeSelectedPopulationManifest
                        .SCHEMA_VERSION,
                "refund-selected-population",
                1,
                "",
                scope(),
                inventoryRef(),
                cohortRef(),
                samplingFrameRef(),
                ref(
                        "OUTCOME_SELECTION_POLICY",
                        "refund-selection-v1",
                        '4'),
                ref(
                        "OUTCOME_SELECTION_AUTHORITY_SET",
                        "refund-selection-authorities",
                        '5'),
                ref(
                        "OUTCOME_SELECTION_ATTESTATION",
                        "refund-selection-attestation",
                        '6'),
                SELECTED_AT,
                List.of(
                        new AuthoritativeOutcomeSelectedPopulationManifest
                                .Stratum(
                                "refund-boundary",
                                "small",
                                10,
                                2,
                                AuthoritativeOutcomeObservation
                                        .SelectionMode
                                        .HASH_PARTITION),
                        new AuthoritativeOutcomeSelectedPopulationManifest
                                .Stratum(
                                "refund-escalation",
                                "large",
                                5,
                                1,
                                AuthoritativeOutcomeObservation
                                        .SelectionMode
                                        .STRATIFIED_RANDOM)),
                List.of(
                        descriptor(first),
                        descriptor(second)),
                15,
                3,
                NOW,
                VisualRunEvidenceSeal.unsigned());
    }

    private AuthoritativeOutcomeSelectedPopulationManifest
            .ChunkDescriptor descriptor(
            AuthoritativeOutcomeSelectedPopulationChunk chunk) {
        return new AuthoritativeOutcomeSelectedPopulationManifest
                .ChunkDescriptor(
                chunk.chunkIndex(),
                chunk.artifactRef(),
                chunk.firstGlobalOrdinal(),
                chunk.members().getLast().globalOrdinal(),
                chunk.members().size());
    }

    private AuthoritativeOutcomeSelectedPopulationChunk chunk(
            int chunkIndex,
            long firstGlobalOrdinal,
            List<AuthoritativeOutcomeSelectedPopulationChunk
                    .Member> members) {
        return new AuthoritativeOutcomeSelectedPopulationChunk(
                AuthoritativeOutcomeSelectedPopulationChunk
                        .SCHEMA_VERSION,
                "refund-selected-population:chunk:"
                        + chunkIndex,
                "",
                "refund-selected-population",
                1,
                scope(),
                inventoryRef(),
                cohortRef(),
                samplingFrameRef(),
                SELECTED_AT,
                chunkIndex,
                firstGlobalOrdinal,
                members);
    }

    private static
    AuthoritativeOutcomeSelectedPopulationChunk.Member member(
            long globalOrdinal,
            String unitId,
            String stratumId,
            long sampleOrdinal,
            char inclusion,
            char subject,
            char attribution) {
        return new AuthoritativeOutcomeSelectedPopulationChunk
                .Member(
                globalOrdinal,
                unitId,
                stratumId,
                sampleOrdinal,
                fingerprint(inclusion),
                fingerprint(subject),
                fingerprint(attribution));
    }

    private AuthoritativeOutcomeSelectedPopulationIntegrity
    integrity(
            AuthoritativeOutcomeSelectedPopulationAuthorityVerifier
                    authority) {
        return new AuthoritativeOutcomeSelectedPopulationIntegrity(
                mapper,
                signer,
                authority,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static
    AuthoritativeOutcomeSelectedPopulationAuthorityVerifier
    authority() {
        return new
                AuthoritativeOutcomeSelectedPopulationAuthorityVerifier() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public void verify(
                            AuthoritativeOutcomeSelectedPopulationManifest
                                    manifest,
                            List<AuthoritativeOutcomeSelectedPopulationChunk>
                                    chunks) {
                    }
                };
    }

    private static CapabilitySnapshot.Scope scope() {
        return DomainFidelityTestFixtures.scope("support");
    }

    private static MirrorArtifactRef inventoryRef() {
        return ref(
                DomainFidelityInventory.ARTIFACT_KIND,
                "refund-support",
                '1');
    }

    private static MirrorArtifactRef cohortRef() {
        return ref(
                "OUTCOME_CALIBRATION_COHORT",
                "refunds-2026-07",
                '2');
    }

    private static MirrorArtifactRef samplingFrameRef() {
        return ref(
                "OUTCOME_SAMPLING_FRAME",
                "eligible-refunds-2026-07",
                '3');
    }

    private static MirrorArtifactRef ref(
            String kind, String id, char material) {
        return DomainFidelityTestFixtures.ref(
                kind, id, material);
    }

    private static String fingerprint(char material) {
        char safe = Character.toLowerCase(material);
        if (safe < 'a' || safe > 'f') {
            safe = 'a';
        }
        return "sha256:" + String.valueOf(safe).repeat(64);
    }

    private record Population(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks
    ) {
    }
}
