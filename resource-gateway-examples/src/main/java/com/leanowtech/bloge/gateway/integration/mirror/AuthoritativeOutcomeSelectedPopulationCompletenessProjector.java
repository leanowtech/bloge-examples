package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Denominator-preserving projector from a verified selected population to signed completeness
 * evidence.
 *
 * <p>The projector verifies all three independent closures before counting: selected-population
 * authority, business-outcome authority, and legal-disposition authority. Each selected member
 * may resolve to exactly one current outcome observation or one legal disposition. Any duplicate,
 * cross-member reuse, observation/disposition overlap, or coordinate drift fails closed. Unresolved
 * members remain {@code missing}; they are never dropped from the denominator.</p>
 */
public final class
AuthoritativeOutcomeSelectedPopulationCompletenessProjector {
    private static final int MAXIMUM_SOURCE_SET_BYTES =
            32 * 1024 * 1024;
    private static final Duration MAXIMUM_CLOCK_SKEW =
            Duration.ofMinutes(2);

    private final ObjectMapper mapper;
    private final AuthoritativeOutcomeSelectedPopulationIntegrity
            populationIntegrity;
    private final AuthoritativeOutcomeObservationIntegrity
            observationIntegrity;
    private final
    AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
            dispositionIntegrity;
    private final VisualEvidenceSigner signer;
    private final Clock clock;

    /**
     * Creates a production completeness projector.
     *
     * @param mapper canonical protocol mapper
     * @param populationIntegrity selected-population dual-authority boundary
     * @param observationIntegrity business-outcome dual-authority boundary
     * @param dispositionIntegrity legal-disposition dual-authority boundary
     * @param signer governed completeness-evidence signer
     */
    public AuthoritativeOutcomeSelectedPopulationCompletenessProjector(
            ObjectMapper mapper,
            AuthoritativeOutcomeSelectedPopulationIntegrity
                    populationIntegrity,
            AuthoritativeOutcomeObservationIntegrity
                    observationIntegrity,
            AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                    dispositionIntegrity,
            VisualEvidenceSigner signer) {
        this(
                mapper,
                populationIntegrity,
                observationIntegrity,
                dispositionIntegrity,
                signer,
                Clock.systemUTC());
    }

    /** Deterministic constructor for cut, authority, and seal tests. */
    AuthoritativeOutcomeSelectedPopulationCompletenessProjector(
            ObjectMapper mapper,
            AuthoritativeOutcomeSelectedPopulationIntegrity
                    populationIntegrity,
            AuthoritativeOutcomeObservationIntegrity
                    observationIntegrity,
            AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                    dispositionIntegrity,
            VisualEvidenceSigner signer,
            Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.populationIntegrity = Objects.requireNonNull(
                populationIntegrity, "populationIntegrity");
        this.observationIntegrity = Objects.requireNonNull(
                observationIntegrity, "observationIntegrity");
        this.dispositionIntegrity = Objects.requireNonNull(
                dispositionIntegrity, "dispositionIntegrity");
        this.signer = Objects.requireNonNull(signer, "signer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Verifies all authorities and signs one exact completeness snapshot.
     *
     * @param assessmentId stable assessment identity
     * @param revision positive immutable assessment revision
     * @param manifest signed selected-population root
     * @param chunks exact ordered member chunks
     * @param observations exact current observation heads, at most one per selected member
     * @param dispositions exact current legal dispositions, at most one per selected member
     * @return signed denominator-preserving completeness assessment
     */
    public
    AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
    assess(
            String assessmentId,
            long revision,
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            List<AuthoritativeOutcomeObservation>
                    observations,
            List<AuthoritativeOutcomeSelectedPopulationDisposition>
                    dispositions) {
        Instant cut = clock.instant();
        AuthoritativeOutcomeSelectedPopulationManifest
                population = verifyPopulation(
                manifest, chunks);
        List<AuthoritativeOutcomeSelectedPopulationChunk>
                exactChunks = List.copyOf(chunks);
        if ((observations != null
                && observations.size()
                > population.totalSelectedPopulation())
                || (dispositions != null
                && dispositions.size()
                > population.totalSelectedPopulation())) {
            throw new Violation(
                    Reason.SOURCE_SET_TOO_LARGE);
        }
        Map<MemberKey, MemberCoordinate> members =
                members(exactChunks);
        Map<StratumKey,
                AuthoritativeOutcomeSelectedPopulationManifest
                        .Stratum> strata = strata(population);
        Map<MemberKey, ObservationCoordinate>
                observed = observations(
                population,
                members,
                strata,
                observations,
                cut);
        Map<MemberKey, DispositionCoordinate>
                disposed = dispositions(
                population,
                members,
                dispositions,
                cut);
        Set<MemberKey> overlap = new HashSet<>(
                observed.keySet());
        overlap.retainAll(disposed.keySet());
        if (!overlap.isEmpty()) {
            throw new Violation(
                    Reason.OBSERVATION_DISPOSITION_CONFLICT);
        }
        List<SourceEntry> observationEntries =
                observed.values().stream()
                        .sorted(java.util.Comparator
                                .comparingLong(
                                        ObservationCoordinate
                                                ::globalOrdinal))
                        .map(value -> new SourceEntry(
                                value.globalOrdinal(),
                                value.reference()))
                        .toList();
        List<SourceEntry> dispositionEntries =
                disposed.values().stream()
                        .sorted(java.util.Comparator
                                .comparingLong(
                                        DispositionCoordinate
                                                ::globalOrdinal))
                        .map(value -> new SourceEntry(
                                value.globalOrdinal(),
                                value.reference()))
                        .toList();
        String observationSetFingerprint =
                sourceSetFingerprint(
                        "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_CURRENT_HEAD_SET_V1",
                        population.artifactRef(),
                        observationEntries);
        String dispositionSetFingerprint =
                sourceSetFingerprint(
                        "RESOURCE_GATEWAY_AUTHORITATIVE_OUTCOME_LEGAL_DISPOSITION_SET_V1",
                        population.artifactRef(),
                        dispositionEntries);
        Map<StratumKey, MutableCounts> counts =
                new LinkedHashMap<>();
        for (AuthoritativeOutcomeSelectedPopulationManifest
                .Stratum stratum : population.strata()) {
            counts.put(
                    new StratumKey(
                            stratum.unitId(),
                            stratum.stratumId()),
                    new MutableCounts());
        }
        for (MemberCoordinate member : members.values()) {
            MutableCounts value = counts.get(
                    member.key().stratumKey());
            value.expected++;
            ObservationCoordinate observation =
                    observed.get(member.key());
            if (observation != null) {
                value.increment(observation.reconciliation());
            } else if (disposed.containsKey(member.key())) {
                value.legallyDeleted++;
            } else {
                value.missing++;
            }
        }
        List<AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                .StratumAssessment> results =
                new ArrayList<>();
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                .Counts totals =
                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                        .Counts.zero();
        for (AuthoritativeOutcomeSelectedPopulationManifest
                .Stratum stratum : population.strata()) {
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    .Counts exact = counts.get(
                    new StratumKey(
                            stratum.unitId(),
                            stratum.stratumId()))
                    .freeze();
            results.add(
                    new AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                            .StratumAssessment(
                            stratum.unitId(),
                            stratum.stratumId(),
                            exact));
            totals = totals.plus(exact);
        }
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                unsigned =
                new AuthoritativeOutcomeSelectedPopulationCompletenessAssessment(
                        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                                .SCHEMA_VERSION,
                        assessmentId,
                        revision,
                        "",
                        population.scope(),
                        population.artifactRef(),
                        cut,
                        observationSetFingerprint,
                        dispositionSetFingerprint,
                        results,
                        totals,
                        totals.missing() == 0,
                        totals.missing() == 0
                                && totals.pending() == 0,
                        VisualRunEvidenceSeal.unsigned());
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                addressed =
                AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                        .addressed(
                                unsigned,
                                unsigned.calculateFingerprint(
                                        mapper));
        if (!signer.available()) {
            throw new Violation(
                    Reason.SIGNER_UNAVAILABLE);
        }
        VisualRunEvidenceSeal seal = signer.seal(
                addressed.attestationMaterialFingerprint(mapper),
                "authoritative-outcome-population-completeness:"
                        + addressed.assessmentFingerprint()
                        .substring("sha256:".length()));
        return verifyAssessment(
                addressed.withAssessmentSeal(seal));
    }

    /**
     * Verifies a persisted completeness assessment without replaying customer authority I/O.
     *
     * @param assessment untrusted signed assessment
     * @return exact locally verified assessment
     */
    public
    AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
    verifyAssessment(
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment) {
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                exact = Objects.requireNonNull(
                assessment, "assessment");
        try {
            exact.verify(mapper);
        } catch (RuntimeException invalid) {
            throw new Violation(
                    Reason.ASSESSMENT_INVALID);
        }
        VisualRunEvidenceSeal seal = exact.assessmentSeal();
        if (!seal.signed()) {
            throw new Violation(
                    Reason.ASSESSMENT_UNSIGNED);
        }
        VisualEvidenceSigner.Verification verification =
                signer.verify(
                        seal,
                        exact.attestationMaterialFingerprint(
                                mapper));
        if (!verification.valid()) {
            throw new Violation(
                    "UNAVAILABLE".equals(
                            verification.status())
                            || "KEY_UNAVAILABLE".equals(
                            verification.status())
                            ? Reason.SIGNER_UNAVAILABLE
                            : Reason.ASSESSMENT_SIGNATURE_INVALID);
        }
        Instant now = clock.instant();
        if (exact.assessedAt().isAfter(
                now.plus(MAXIMUM_CLOCK_SKEW))
                || seal.signedAt().isBefore(
                exact.assessedAt().minus(
                        MAXIMUM_CLOCK_SKEW))
                || seal.signedAt().isAfter(
                exact.assessedAt().plus(
                        MAXIMUM_CLOCK_SKEW))) {
            throw new Violation(
                    Reason.ASSESSMENT_SIGNING_TIME_INVALID);
        }
        return exact;
    }

    /** @return whether every required external authority and local signer is currently usable */
    public boolean available() {
        return populationIntegrity.available()
                && observationIntegrity.available()
                && dispositionIntegrity.available()
                && signer.available();
    }

    private AuthoritativeOutcomeSelectedPopulationManifest
    verifyPopulation(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks) {
        try {
            return populationIntegrity.verify(
                    manifest, chunks);
        } catch (AuthoritativeOutcomeSelectedPopulationIntegrity
                 .Violation failure) {
            throw new Violation(
                    failure.reason()
                    == AuthoritativeOutcomeSelectedPopulationIntegrity
                    .Reason.AUTHORITY_UNAVAILABLE
                    || failure.reason()
                    == AuthoritativeOutcomeSelectedPopulationIntegrity
                    .Reason.KEY_UNAVAILABLE
                            ? Reason.POPULATION_UNAVAILABLE
                            : Reason.POPULATION_INVALID);
        } catch (RuntimeException failure) {
            throw new Violation(
                    Reason.POPULATION_INVALID);
        }
    }

    private Map<MemberKey, ObservationCoordinate>
    observations(
            AuthoritativeOutcomeSelectedPopulationManifest
                    population,
            Map<MemberKey, MemberCoordinate> members,
            Map<StratumKey,
                    AuthoritativeOutcomeSelectedPopulationManifest
                            .Stratum> strata,
            List<AuthoritativeOutcomeObservation> values,
            Instant cut) {
        List<AuthoritativeOutcomeObservation> sources =
                values == null ? List.of()
                        : List.copyOf(values);
        Map<MemberKey, ObservationCoordinate> result =
                new HashMap<>();
        Set<String> observationIds = new HashSet<>();
        for (AuthoritativeOutcomeObservation source
                : sources) {
            AuthoritativeOutcomeObservation observation;
            try {
                observation = observationIntegrity.verify(
                        source);
            } catch (AuthoritativeOutcomeObservationIntegrity
                     .Violation failure) {
                throw new Violation(
                        failure.reason()
                        == AuthoritativeOutcomeObservationIntegrity
                        .Reason.AUTHORITY_UNAVAILABLE
                        || failure.reason()
                        == AuthoritativeOutcomeObservationIntegrity
                        .Reason.KEY_UNAVAILABLE
                                ? Reason.OUTCOME_UNAVAILABLE
                                : Reason.OUTCOME_INVALID);
            } catch (RuntimeException failure) {
                throw new Violation(
                        Reason.OUTCOME_INVALID);
            }
            AuthoritativeOutcomeObservation.SelectionProof proof =
                    observation.selectionProof();
            MemberKey key = new MemberKey(
                    observation.unitId(),
                    proof.stratumId(),
                    proof.sampleOrdinal());
            MemberCoordinate member = members.get(key);
            AuthoritativeOutcomeSelectedPopulationManifest
                    .Stratum stratum = strata.get(
                    key.stratumKey());
            if (member == null
                    || stratum == null
                    || !observation.scope().equals(
                    population.scope())
                    || !observation.inventoryRef().equals(
                    population.inventoryRef())
                    || !proof.cohortRef().equals(
                    population.cohortRef())
                    || !proof.samplingFrameRef().equals(
                    population.samplingFrameRef())
                    || !proof.selectedAt().equals(
                    population.selectedAt())
                    || proof.eligiblePopulationSize()
                    != stratum.eligiblePopulationSize()
                    || proof.selectedPopulationSize()
                    != stratum.selectedPopulationSize()
                    || proof.selectionMode()
                    != stratum.selectionMode()
                    || !proof.inclusionFingerprint().equals(
                    member.inclusionFingerprint())
                    || !observation.subjectFingerprint().equals(
                    member.subjectFingerprint())
                    || !observation.attributionKeyFingerprint()
                    .equals(
                            member.attributionKeyFingerprint())
                    || observation.reconciledAt().isAfter(cut)
                    || !observationIds.add(
                    observation.observationId())
                    || result.putIfAbsent(
                    key,
                    new ObservationCoordinate(
                            member.globalOrdinal(),
                            observation.artifactRef(),
                            observation.reconciliation()))
                    != null) {
                throw new Violation(
                        Reason.OUTCOME_MEMBER_MISMATCH);
            }
        }
        return Map.copyOf(result);
    }

    private Map<MemberKey, DispositionCoordinate>
    dispositions(
            AuthoritativeOutcomeSelectedPopulationManifest
                    population,
            Map<MemberKey, MemberCoordinate> members,
            List<AuthoritativeOutcomeSelectedPopulationDisposition>
                    values,
            Instant cut) {
        List<AuthoritativeOutcomeSelectedPopulationDisposition>
                sources = values == null ? List.of()
                : List.copyOf(values);
        Map<MemberKey, DispositionCoordinate> result =
                new HashMap<>();
        Set<String> dispositionIds = new HashSet<>();
        for (AuthoritativeOutcomeSelectedPopulationDisposition
                source : sources) {
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition;
            try {
                disposition = dispositionIntegrity.verify(
                        source);
            } catch (AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                     .Violation failure) {
                throw new Violation(
                        failure.reason()
                        == AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                        .Reason.AUTHORITY_UNAVAILABLE
                        || failure.reason()
                        == AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                        .Reason.KEY_UNAVAILABLE
                                ? Reason.DISPOSITION_UNAVAILABLE
                                : Reason.DISPOSITION_INVALID);
            } catch (RuntimeException failure) {
                throw new Violation(
                        Reason.DISPOSITION_INVALID);
            }
            MemberKey key = new MemberKey(
                    disposition.unitId(),
                    disposition.stratumId(),
                    disposition.sampleOrdinal());
            MemberCoordinate member = members.get(key);
            if (member == null
                    || !disposition.scope().equals(
                    population.scope())
                    || !disposition.populationRef().equals(
                    population.artifactRef())
                    || !disposition.inclusionFingerprint()
                    .equals(member.inclusionFingerprint())
                    || !disposition.subjectFingerprint()
                    .equals(member.subjectFingerprint())
                    || !disposition.attributionKeyFingerprint()
                    .equals(
                            member.attributionKeyFingerprint())
                    || disposition.effectiveAt().isAfter(cut)
                    || !dispositionIds.add(
                    disposition.dispositionId())
                    || result.putIfAbsent(
                    key,
                    new DispositionCoordinate(
                            member.globalOrdinal(),
                            disposition.artifactRef()))
                    != null) {
                throw new Violation(
                        Reason.DISPOSITION_MEMBER_MISMATCH);
            }
        }
        return Map.copyOf(result);
    }

    private static Map<MemberKey, MemberCoordinate> members(
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks) {
        Map<MemberKey, MemberCoordinate> result =
                new LinkedHashMap<>();
        for (AuthoritativeOutcomeSelectedPopulationChunk
                chunk : chunks) {
            for (AuthoritativeOutcomeSelectedPopulationChunk
                    .Member member : chunk.members()) {
                MemberKey key = new MemberKey(
                        member.unitId(),
                        member.stratumId(),
                        member.sampleOrdinal());
                MemberCoordinate previous = result.putIfAbsent(
                        key,
                        new MemberCoordinate(
                                key,
                                member.globalOrdinal(),
                                member.inclusionFingerprint(),
                                member.subjectFingerprint(),
                                member.attributionKeyFingerprint()));
                if (previous != null) {
                    throw new Violation(
                            Reason.POPULATION_INVALID);
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Map<StratumKey,
            AuthoritativeOutcomeSelectedPopulationManifest
                    .Stratum> strata(
            AuthoritativeOutcomeSelectedPopulationManifest
                    population) {
        Map<StratumKey,
                AuthoritativeOutcomeSelectedPopulationManifest
                        .Stratum> result = new HashMap<>();
        for (AuthoritativeOutcomeSelectedPopulationManifest
                .Stratum stratum : population.strata()) {
            result.put(
                    new StratumKey(
                            stratum.unitId(),
                            stratum.stratumId()),
                    stratum);
        }
        return Map.copyOf(result);
    }

    private String sourceSetFingerprint(
            String domain,
            MirrorArtifactRef populationRef,
            List<SourceEntry> entries) {
        return ProtocolFingerprint.ofBounded(
                mapper,
                new SourceSet(
                        domain,
                        populationRef,
                        entries),
                MAXIMUM_SOURCE_SET_BYTES);
    }

    private record StratumKey(
            String unitId,
            String stratumId
    ) {
    }

    private record MemberKey(
            String unitId,
            String stratumId,
            long sampleOrdinal
    ) {
        private StratumKey stratumKey() {
            return new StratumKey(
                    unitId, stratumId);
        }
    }

    private record MemberCoordinate(
            MemberKey key,
            long globalOrdinal,
            String inclusionFingerprint,
            String subjectFingerprint,
            String attributionKeyFingerprint
    ) {
    }

    private record ObservationCoordinate(
            long globalOrdinal,
            MirrorArtifactRef reference,
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation
    ) {
    }

    private record DispositionCoordinate(
            long globalOrdinal,
            MirrorArtifactRef reference
    ) {
    }

    private record SourceSet(
            String domain,
            MirrorArtifactRef populationRef,
            List<SourceEntry> entries
    ) {
    }

    private record SourceEntry(
            long globalOrdinal,
            MirrorArtifactRef reference
    ) {
    }

    private static final class MutableCounts {
        private long expected;
        private long matched;
        private long mismatched;
        private long pending;
        private long censored;
        private long conflicting;
        private long legallyDeleted;
        private long missing;

        private void increment(
                AuthoritativeOutcomeObservation.Reconciliation
                        reconciliation) {
            switch (reconciliation) {
                case MATCH -> matched++;
                case MISMATCH -> mismatched++;
                case PENDING -> pending++;
                case CENSORED -> censored++;
                case CONFLICT -> conflicting++;
            }
        }

        private
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                .Counts freeze() {
            return new
                    AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                            .Counts(
                            expected,
                            matched,
                            mismatched,
                            pending,
                            censored,
                            conflicting,
                            legallyDeleted,
                            missing);
        }
    }

    /** Closed payload-free completeness-projection rejection vocabulary. */
    public enum Reason {
        POPULATION_UNAVAILABLE,
        POPULATION_INVALID,
        OUTCOME_UNAVAILABLE,
        OUTCOME_INVALID,
        OUTCOME_MEMBER_MISMATCH,
        DISPOSITION_UNAVAILABLE,
        DISPOSITION_INVALID,
        DISPOSITION_MEMBER_MISMATCH,
        OBSERVATION_DISPOSITION_CONFLICT,
        SOURCE_SET_TOO_LARGE,
        SIGNER_UNAVAILABLE,
        ASSESSMENT_INVALID,
        ASSESSMENT_UNSIGNED,
        ASSESSMENT_SIGNATURE_INVALID,
        ASSESSMENT_SIGNING_TIME_INVALID
    }

    /** Stable payload-free completeness projection failure. */
    public static final class Violation
            extends RuntimeException {
        private final Reason reason;

        /** Creates one stable completeness-projection violation. */
        public Violation(Reason reason) {
            super("Authoritative outcome selected population completeness rejected: "
                    + Objects.requireNonNull(
                    reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable rejection reason */
        public Reason reason() {
            return reason;
        }
    }
}
