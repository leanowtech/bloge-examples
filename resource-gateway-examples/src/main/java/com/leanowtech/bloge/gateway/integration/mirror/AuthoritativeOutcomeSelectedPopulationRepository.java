package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Durable selected-population, legal-disposition, and completeness-assessment registry.
 *
 * <p>Population roots, chunks, dispositions, and assessments are append-only immutable revisions.
 * Mutable heads are rebuildable indexes. Assessment uses an optimistic two-phase cut: current
 * outcome/disposition heads are frozen under the same database partition lock used by the outcome
 * inbox, external authorities are verified outside that transaction, and commit succeeds only if
 * the exact cut is still current.</p>
 */
public interface AuthoritativeOutcomeSelectedPopulationRepository {
    /** Canonical content-address syntax used by predecessor coordinates. */
    Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** One exact selected-population revision and its complete member chunks. */
    record Population(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            String predecessorFingerprint
    ) {
        /** Requires complete immutable root coordinates. */
        public Population {
            manifest = Objects.requireNonNull(
                    manifest, "manifest");
            chunks = chunks == null
                    ? List.of() : List.copyOf(chunks);
            predecessorFingerprint = optionalFingerprint(
                    predecessorFingerprint);
            if ((manifest.revision() == 1)
                    != predecessorFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "selected-population predecessor lineage is invalid");
            }
        }
    }

    /** Durable population admission result. */
    record PopulationAdmission(
            Population population,
            boolean idempotentReplay
    ) {
        /** Requires a concrete verified population. */
        public PopulationAdmission {
            population = Objects.requireNonNull(
                    population, "population");
        }
    }

    /** Durable legal-disposition admission result. */
    record DispositionAdmission(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition,
            String predecessorFingerprint,
            boolean idempotentReplay
    ) {
        /** Requires exact immutable disposition lineage. */
        public DispositionAdmission {
            disposition = Objects.requireNonNull(
                    disposition, "disposition");
            predecessorFingerprint = optionalFingerprint(
                    predecessorFingerprint);
            if ((disposition.revision() == 1)
                    != predecessorFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "selected-member disposition predecessor lineage is invalid");
            }
        }
    }

    /**
     * Exact database-coordinated source cut used for one completeness projection.
     *
     * @param population exact selected denominator
     * @param observations matching current outcome heads
     * @param dispositions matching current legal-disposition heads
     * @param observedAt database observation time
     * @param observationSetFingerprint member-ordered observation-set address
     * @param dispositionSetFingerprint member-ordered disposition-set address
     */
    record AssessmentCut(
            Population population,
            List<AuthoritativeOutcomeObservation>
                    observations,
            List<AuthoritativeOutcomeSelectedPopulationDisposition>
                    dispositions,
            Instant observedAt,
            String observationSetFingerprint,
            String dispositionSetFingerprint
    ) {
        /** Requires complete bounded source coordinates. */
        public AssessmentCut {
            population = Objects.requireNonNull(
                    population, "population");
            observations = observations == null
                    ? List.of() : List.copyOf(observations);
            dispositions = dispositions == null
                    ? List.of() : List.copyOf(dispositions);
            observedAt = Objects.requireNonNull(
                    observedAt, "observedAt");
            observationSetFingerprint = fingerprint(
                    observationSetFingerprint,
                    "observationSetFingerprint");
            dispositionSetFingerprint = fingerprint(
                    dispositionSetFingerprint,
                    "dispositionSetFingerprint");
            long selected = population.manifest()
                    .totalSelectedPopulation();
            if (observations.size() > selected
                    || dispositions.size() > selected) {
                throw new IllegalArgumentException(
                        "selected-population assessment cut is larger than its denominator");
            }
        }
    }

    /** Durable completeness-assessment admission result. */
    record AssessmentAdmission(
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment,
            String predecessorFingerprint,
            boolean idempotentReplay
    ) {
        /** Requires exact immutable assessment lineage. */
        public AssessmentAdmission {
            assessment = Objects.requireNonNull(
                    assessment, "assessment");
            predecessorFingerprint = optionalFingerprint(
                    predecessorFingerprint);
            if ((assessment.revision() == 1)
                    != predecessorFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "selected-population assessment predecessor lineage is invalid");
            }
        }
    }

    /**
     * Fully verifies and appends a signed population revision.
     *
     * @param manifest signed root
     * @param chunks exact complete member chunks
     * @param expectedPredecessorFingerprint blank for revision one, exact current root otherwise
     */
    PopulationAdmission register(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            String expectedPredecessorFingerprint);

    /**
     * Appends a population already verified by the external selection authority.
     *
     * <p>The transaction repeats local structure, address, seal, time, and chunk-closure
     * verification, but performs no customer I/O.</p>
     */
    PopulationAdmission registerPreverified(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            String expectedPredecessorFingerprint);

    /** Reads one exact immutable population revision after local verification. */
    Optional<Population> findPopulation(
            CapabilitySnapshot.Scope scope,
            String populationId,
            long revision);

    /** Reads the current population revision after local verification. */
    Optional<Population> findLatestPopulation(
            CapabilitySnapshot.Scope scope,
            String populationId);

    /**
     * Fully verifies and appends one signed legal-disposition revision.
     */
    DispositionAdmission appendDisposition(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition,
            String expectedPredecessorFingerprint);

    /**
     * Appends a disposition already verified by the independent deletion authority.
     */
    DispositionAdmission appendDispositionPreverified(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition,
            String expectedPredecessorFingerprint);

    /** Reads one exact immutable disposition revision after local verification. */
    Optional<AuthoritativeOutcomeSelectedPopulationDisposition>
    findDisposition(
            CapabilitySnapshot.Scope scope,
            String dispositionId,
            long revision);

    /** Reads the current immutable disposition revision after local verification. */
    Optional<AuthoritativeOutcomeSelectedPopulationDisposition>
    findLatestDisposition(
            CapabilitySnapshot.Scope scope,
            String dispositionId);

    /**
     * Freezes matching current outcome and disposition heads under the shared database lock.
     */
    AssessmentCut prepareAssessment(
            CapabilitySnapshot.Scope scope,
            String populationId,
            long populationRevision);

    /**
     * Appends a signed assessment only if its prepared source cut is still current.
     *
     * @param cut exact previously prepared cut
     * @param assessment signed completeness projection over that cut
     * @param expectedPredecessorFingerprint blank for revision one, exact assessment head otherwise
     */
    AssessmentAdmission appendAssessment(
            AssessmentCut cut,
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment,
            String expectedPredecessorFingerprint);

    /** Reads one exact immutable assessment revision after local verification. */
    Optional<AuthoritativeOutcomeSelectedPopulationCompletenessAssessment>
    findAssessment(
            CapabilitySnapshot.Scope scope,
            String assessmentId,
            long revision);

    /** Reads the current immutable assessment revision after local verification. */
    Optional<AuthoritativeOutcomeSelectedPopulationCompletenessAssessment>
    findLatestAssessment(
            CapabilitySnapshot.Scope scope,
            String assessmentId);

    /** Closed payload-free durable-registry rejection vocabulary. */
    enum Reason {
        LINEAGE_CONFLICT,
        CONTENT_CONFLICT,
        POPULATION_NOT_FOUND,
        MEMBER_CONFLICT,
        SOURCE_CONFLICT,
        CUT_STALE,
        ASSESSMENT_MISMATCH,
        STORED_STATE_CORRUPT
    }

    /** Stable repository failure carrying no business identity or customer exception. */
    final class Violation extends RuntimeException {
        private final Reason reason;

        /** Creates one stable selected-population repository violation. */
        public Violation(Reason reason) {
            super("Authoritative outcome selected population repository rejected: "
                    + Objects.requireNonNull(
                    reason, "reason").name());
            this.reason = reason;
        }

        /** @return stable rejection reason */
        public Reason reason() {
            return reason;
        }
    }

    private static String optionalFingerprint(
            String value) {
        String exact = value == null
                ? "" : value.trim();
        if (!exact.isBlank()
                && !FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    "predecessor fingerprint is invalid");
        }
        return exact;
    }

    private static String fingerprint(
            String value, String field) {
        String exact = value == null
                ? "" : value.trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }
}
