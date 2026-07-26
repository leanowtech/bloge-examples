package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;
import java.util.Objects;

/**
 * External-authority orchestration for durable selected-population completeness.
 *
 * <p>Selection and deletion authorities are called before repository mutation. Assessment freezes
 * a short database cut, verifies every selected population, outcome, and legal disposition outside
 * the lock, then commits only if the cut remains current. A bounded retry absorbs ordinary
 * concurrent outcome arrivals without allowing unbounded starvation.</p>
 */
public final class AuthoritativeOutcomeSelectedPopulationService {
    /** Maximum complete cut/project/commit attempts for one user operation. */
    public static final int MAXIMUM_CUT_ATTEMPTS = 4;

    private final AuthoritativeOutcomeSelectedPopulationRepository
            repository;
    private final AuthoritativeOutcomeSelectedPopulationIntegrity
            populationIntegrity;
    private final
    AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
            dispositionIntegrity;
    private final AuthoritativeOutcomeSelectedPopulationCompletenessProjector
            projector;

    /**
     * Creates a durable selected-population application service.
     *
     * @param repository append-only registry and shared-cut coordinator
     * @param populationIntegrity selection-authority and RG signing boundary
     * @param dispositionIntegrity deletion-authority and RG signing boundary
     * @param projector business-authority completeness projector
     */
    public AuthoritativeOutcomeSelectedPopulationService(
            AuthoritativeOutcomeSelectedPopulationRepository
                    repository,
            AuthoritativeOutcomeSelectedPopulationIntegrity
                    populationIntegrity,
            AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                    dispositionIntegrity,
            AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                    projector) {
        this.repository = Objects.requireNonNull(
                repository, "repository");
        this.populationIntegrity = Objects.requireNonNull(
                populationIntegrity, "populationIntegrity");
        this.dispositionIntegrity = Objects.requireNonNull(
                dispositionIntegrity, "dispositionIntegrity");
        this.projector = Objects.requireNonNull(
                projector, "projector");
    }

    /**
     * Verifies the external selection authority, signs, and durably appends one population.
     *
     * @param manifest unsigned or exactly addressed population root
     * @param chunks exact content-addressed member chunks
     * @param expectedPredecessorFingerprint blank for revision one, exact current root otherwise
     * @return durable admission
     */
    public AuthoritativeOutcomeSelectedPopulationRepository
            .PopulationAdmission register(
            AuthoritativeOutcomeSelectedPopulationManifest
                    manifest,
            List<AuthoritativeOutcomeSelectedPopulationChunk>
                    chunks,
            String expectedPredecessorFingerprint) {
        AuthoritativeOutcomeSelectedPopulationManifest signed =
                populationIntegrity.sign(
                        manifest, chunks);
        return repository.registerPreverified(
                signed,
                chunks,
                expectedPredecessorFingerprint);
    }

    /**
     * Verifies the independent deletion authority, signs, and appends one disposition.
     */
    public AuthoritativeOutcomeSelectedPopulationRepository
            .DispositionAdmission appendDisposition(
            AuthoritativeOutcomeSelectedPopulationDisposition
                    disposition,
            String expectedPredecessorFingerprint) {
        AuthoritativeOutcomeSelectedPopulationDisposition
                signed = dispositionIntegrity.sign(
                disposition);
        return repository.appendDispositionPreverified(
                signed,
                expectedPredecessorFingerprint);
    }

    /**
     * Projects and durably appends one exact current-head completeness assessment.
     *
     * @param scope exact enterprise namespace
     * @param populationId stable selected-population identity
     * @param populationRevision exact immutable population revision
     * @param assessmentId stable assessment identity
     * @param assessmentRevision positive immutable assessment revision
     * @param expectedPredecessorFingerprint blank for revision one, exact assessment head otherwise
     * @return durable signed assessment admission
     */
    public AuthoritativeOutcomeSelectedPopulationRepository
            .AssessmentAdmission assess(
            CapabilitySnapshot.Scope scope,
            String populationId,
            long populationRevision,
            String assessmentId,
            long assessmentRevision,
            String expectedPredecessorFingerprint) {
        for (int attempt = 1;
             attempt <= MAXIMUM_CUT_ATTEMPTS;
             attempt++) {
            AuthoritativeOutcomeSelectedPopulationRepository
                    .AssessmentCut cut =
                    repository.prepareAssessment(
                            scope,
                            populationId,
                            populationRevision);
            AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                    assessment = projector.assess(
                    assessmentId,
                    assessmentRevision,
                    cut.population().manifest(),
                    cut.population().chunks(),
                    cut.observations(),
                    cut.dispositions());
            if (!assessment
                    .observationSetFingerprint()
                    .equals(
                            cut.observationSetFingerprint())
                    || !assessment
                    .dispositionSetFingerprint()
                    .equals(
                            cut.dispositionSetFingerprint())) {
                throw new AuthoritativeOutcomeSelectedPopulationRepository
                        .Violation(
                        AuthoritativeOutcomeSelectedPopulationRepository
                                .Reason.ASSESSMENT_MISMATCH);
            }
            try {
                return repository.appendAssessment(
                        cut,
                        assessment,
                        expectedPredecessorFingerprint);
            } catch (AuthoritativeOutcomeSelectedPopulationRepository
                     .Violation conflict) {
                if (conflict.reason()
                        != AuthoritativeOutcomeSelectedPopulationRepository
                        .Reason.CUT_STALE
                        || attempt == MAXIMUM_CUT_ATTEMPTS) {
                    throw conflict;
                }
            }
        }
        throw new IllegalStateException(
                "unreachable selected-population cut retry state");
    }

    /** @return whether all three independent authorities and the RG signer are currently usable */
    public boolean available() {
        return populationIntegrity.available()
                && dispositionIntegrity.available()
                && projector.available();
    }
}
