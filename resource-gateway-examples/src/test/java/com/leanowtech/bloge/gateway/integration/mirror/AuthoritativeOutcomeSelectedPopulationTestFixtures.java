package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Shared deterministic, payload-free selected-population fixtures. */
final class AuthoritativeOutcomeSelectedPopulationTestFixtures {
    static final Instant SELECTED_AT =
            Instant.parse("2026-07-01T00:00:00Z");
    static final Instant ACTION_AT =
            Instant.parse("2026-07-02T00:00:00Z");
    static final Instant WINDOW_CLOSES_AT =
            Instant.parse("2026-07-09T00:00:00Z");
    static final Instant RECONCILED_AT =
            Instant.parse("2026-07-10T00:00:00Z");

    private AuthoritativeOutcomeSelectedPopulationTestFixtures() {
    }

    static Population signedPopulation(
            AuthoritativeOutcomeSelectedPopulationIntegrity
                    integrity) {
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
                                                'a'),
                                        member(
                                                2,
                                                "refund-boundary",
                                                "small",
                                                2,
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
                                        'c'))));
        List<AuthoritativeOutcomeSelectedPopulationChunk>
                chunks = List.of(first, second);
        AuthoritativeOutcomeSelectedPopulationManifest unsigned =
                new AuthoritativeOutcomeSelectedPopulationManifest(
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
                        DomainFidelityTestFixtures.NOW,
                        VisualRunEvidenceSeal.unsigned());
        AuthoritativeOutcomeSelectedPopulationManifest signed =
                integrity.sign(unsigned, chunks);
        return new Population(
                signed,
                chunks,
                chunks.stream()
                        .flatMap(chunk ->
                                chunk.members().stream())
                        .toList());
    }

    static AuthoritativeOutcomeObservation signedObservation(
            AuthoritativeOutcomeObservationIntegrity integrity,
            AuthoritativeOutcomeSelectedPopulationManifest
                    population,
            AuthoritativeOutcomeSelectedPopulationChunk.Member
                    member,
            String observationId,
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation) {
        OutcomeClosure closure = closure(
                member,
                reconciliation);
        AuthoritativeOutcomeSelectedPopulationManifest
                .Stratum stratum = population.strata().stream()
                .filter(candidate ->
                        candidate.unitId().equals(member.unitId())
                                && candidate.stratumId().equals(
                                member.stratumId()))
                .findFirst()
                .orElseThrow();
        AuthoritativeOutcomeObservation unsigned =
                new AuthoritativeOutcomeObservation(
                        AuthoritativeOutcomeObservation
                                .SCHEMA_VERSION,
                        observationId,
                        1,
                        "",
                        population.scope(),
                        population.inventoryRef(),
                        member.unitId(),
                        ref(
                                "SCENARIO_CASE",
                                member.unitId(),
                                '1'),
                        ref(
                                "CAPABILITY",
                                "refund",
                                '2'),
                        ref(
                                "OUTCOME_DEFINITION",
                                "refund-settled",
                                '3'),
                        ref(
                                "OUTCOME_ATTRIBUTION_POLICY",
                                "refund-seven-day-window",
                                '4'),
                        ref(
                                "OUTCOME_AUTHORITY_SET",
                                "refund-ledgers",
                                '5'),
                        new AuthoritativeOutcomeObservation
                                .SelectionProof(
                                population.cohortRef(),
                                population.samplingFrameRef(),
                                member.stratumId(),
                                member.inclusionFingerprint(),
                                population.selectedAt(),
                                stratum.eligiblePopulationSize(),
                                stratum.selectedPopulationSize(),
                                member.sampleOrdinal(),
                                stratum.selectionMode()),
                        member.subjectFingerprint(),
                        member.attributionKeyFingerprint(),
                        fingerprint('d'),
                        new AuthoritativeOutcomeObservation
                                .AttributionWindow(
                                ACTION_AT,
                                ACTION_AT,
                                WINDOW_CLOSES_AT),
                        RECONCILED_AT,
                        RECONCILED_AT,
                        closure.watermarks(),
                        closure.facts(),
                        reconciliation,
                        true,
                        VisualRunEvidenceSeal.unsigned());
        return integrity.sign(unsigned);
    }

    static AuthoritativeOutcomeSelectedPopulationDisposition
    signedDisposition(
            AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                    integrity,
            AuthoritativeOutcomeSelectedPopulationManifest
                    population,
            AuthoritativeOutcomeSelectedPopulationChunk.Member
                    member,
            String dispositionId) {
        return integrity.sign(
                new AuthoritativeOutcomeSelectedPopulationDisposition(
                        AuthoritativeOutcomeSelectedPopulationDisposition
                                .SCHEMA_VERSION,
                        dispositionId,
                        1,
                        "",
                        population.scope(),
                        population.artifactRef(),
                        member.unitId(),
                        member.stratumId(),
                        member.sampleOrdinal(),
                        member.inclusionFingerprint(),
                        member.subjectFingerprint(),
                        member.attributionKeyFingerprint(),
                        AuthoritativeOutcomeSelectedPopulationDisposition
                                .Disposition.LEGALLY_DELETED,
                        AuthoritativeOutcomeSelectedPopulationDisposition
                                .DeletionReason
                                .LEGAL_RETENTION_EXPIRY,
                        ref(
                                "OUTCOME_DATA_RETENTION_POLICY",
                                "refund-retention-v1",
                                '6'),
                        ref(
                                "OUTCOME_MEMBER_DELETION_APPROVAL",
                                dispositionId + "-approval",
                                '7'),
                        ref(
                                "OUTCOME_DELETION_AUTHORITY_SET",
                                "refund-deletion-authorities",
                                '8'),
                        RECONCILED_AT,
                        DomainFidelityTestFixtures.NOW,
                        VisualRunEvidenceSeal.unsigned()));
    }

    static AuthoritativeOutcomeSelectedPopulationChunk.Member
    memberWithSubject(
            AuthoritativeOutcomeSelectedPopulationChunk.Member
                    source,
            char subject) {
        return new AuthoritativeOutcomeSelectedPopulationChunk.Member(
                source.globalOrdinal(),
                source.unitId(),
                source.stratumId(),
                source.sampleOrdinal(),
                source.inclusionFingerprint(),
                fingerprint(subject),
                source.attributionKeyFingerprint());
    }

    static AuthoritativeOutcomeSelectedPopulationAuthorityVerifier
    populationAuthority() {
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

    static AuthoritativeOutcomeAuthorityVerifier outcomeAuthority() {
        return new AuthoritativeOutcomeAuthorityVerifier() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public void verify(
                    AuthoritativeOutcomeObservation observation) {
            }
        };
    }

    static
    AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier
    dispositionAuthority() {
        return new
                AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier() {
                    @Override
                    public boolean available() {
                        return true;
                    }

                    @Override
                    public void verify(
                            AuthoritativeOutcomeSelectedPopulationDisposition
                                    disposition) {
                    }
                };
    }

    static String fingerprint(char material) {
        char safe = Character.toLowerCase(material);
        if (safe < 'a' || safe > 'f') {
            safe = 'a';
        }
        return "sha256:" + String.valueOf(safe).repeat(64);
    }

    private static OutcomeClosure closure(
            AuthoritativeOutcomeSelectedPopulationChunk.Member
                    member,
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation) {
        List<AuthoritativeOutcomeObservation.AuthorityWatermark>
                watermarks = new ArrayList<>();
        List<AuthoritativeOutcomeObservation.AuthorityFact>
                facts = new ArrayList<>();
        boolean pending =
                reconciliation
                        == AuthoritativeOutcomeObservation
                        .Reconciliation.PENDING;
        watermarks.add(
                watermark(
                        "ledger-a",
                        pending
                                ? WINDOW_CLOSES_AT.minusSeconds(1)
                                : WINDOW_CLOSES_AT));
        if (reconciliation
                == AuthoritativeOutcomeObservation
                .Reconciliation.MATCH
                || reconciliation
                == AuthoritativeOutcomeObservation
                .Reconciliation.MISMATCH
                || reconciliation
                == AuthoritativeOutcomeObservation
                .Reconciliation.CONFLICT) {
            facts.add(
                    fact(
                            "ledger-a",
                            "record-a-"
                                    + member.globalOrdinal(),
                            member,
                            reconciliation
                                    == AuthoritativeOutcomeObservation
                                    .Reconciliation.MATCH
                                    ? 'd' : 'e',
                            'a'));
        }
        if (reconciliation
                == AuthoritativeOutcomeObservation
                .Reconciliation.CONFLICT) {
            watermarks.add(
                    watermark(
                            "ledger-b",
                            WINDOW_CLOSES_AT));
            facts.add(
                    fact(
                            "ledger-b",
                            "record-b-"
                                    + member.globalOrdinal(),
                            member,
                            'f',
                            'b'));
        }
        return new OutcomeClosure(
                List.copyOf(watermarks),
                List.copyOf(facts));
    }

    private static AuthoritativeOutcomeObservation.AuthorityWatermark
    watermark(
            String authorityId,
            Instant eventTimeThrough) {
        return new AuthoritativeOutcomeObservation
                .AuthorityWatermark(
                authorityId,
                ref(
                        "AUTHORITATIVE_OUTCOME_SOURCE_WATERMARK",
                        authorityId + "-watermark",
                        authorityId.endsWith("a")
                                ? 'a' : 'b'),
                eventTimeThrough,
                RECONCILED_AT.minusSeconds(1));
    }

    private static AuthoritativeOutcomeObservation.AuthorityFact
    fact(
            String authorityId,
            String sourceId,
            AuthoritativeOutcomeSelectedPopulationChunk.Member
                    member,
            char outcome,
            char source) {
        return new AuthoritativeOutcomeObservation.AuthorityFact(
                authorityId,
                ref(
                        "AUTHORITATIVE_OUTCOME_SOURCE_RECORD",
                        sourceId,
                        source),
                member.subjectFingerprint(),
                member.attributionKeyFingerprint(),
                fingerprint(outcome),
                WINDOW_CLOSES_AT.minusSeconds(60),
                WINDOW_CLOSES_AT.plusSeconds(30),
                true);
    }

    private static AuthoritativeOutcomeSelectedPopulationChunk
    chunk(
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
            char material) {
        return new AuthoritativeOutcomeSelectedPopulationChunk
                .Member(
                globalOrdinal,
                unitId,
                stratumId,
                sampleOrdinal,
                fingerprint(material),
                fingerprint(material),
                fingerprint(material));
    }

    private static AuthoritativeOutcomeSelectedPopulationManifest
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

    record Population(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            List<AuthoritativeOutcomeSelectedPopulationChunk
                    .Member> members
    ) {
    }

    private record OutcomeClosure(
            List<AuthoritativeOutcomeObservation
                    .AuthorityWatermark> watermarks,
            List<AuthoritativeOutcomeObservation
                    .AuthorityFact> facts
    ) {
    }
}
