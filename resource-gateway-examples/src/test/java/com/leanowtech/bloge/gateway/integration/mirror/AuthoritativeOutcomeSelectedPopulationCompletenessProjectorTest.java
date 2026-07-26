package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeSelectedPopulationCompletenessProjectorTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    DomainFidelityTestFixtures.CLOCK);

    private AuthoritativeOutcomeSelectedPopulationIntegrity
            populationIntegrity;
    private AuthoritativeOutcomeObservationIntegrity
            observationIntegrity;
    private
    AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
            dispositionIntegrity;
    private AuthoritativeOutcomeSelectedPopulationCompletenessProjector
            projector;
    private AuthoritativeOutcomeSelectedPopulationTestFixtures
            .Population population;

    @BeforeEach
    void setUp() {
        populationIntegrity =
                new AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .populationAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        observationIntegrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .outcomeAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        dispositionIntegrity =
                new
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .dispositionAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        projector = projector(dispositionIntegrity);
        population =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(
                                populationIntegrity);
    }

    @Test
    void preservesDenominatorAcrossMatchedPendingDeletedAndMissingMembers() {
        AuthoritativeOutcomeObservation matched =
                observation(
                        0,
                        "observation-member-1",
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH);
        AuthoritativeOutcomeObservation pending =
                observation(
                        1,
                        "observation-member-2",
                        AuthoritativeOutcomeObservation
                                .Reconciliation.PENDING);
        AuthoritativeOutcomeSelectedPopulationDisposition deleted =
                disposition(
                        2,
                        "deletion-member-3");

        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                partial = projector.assess(
                "assessment-partial",
                1,
                population.manifest(),
                population.chunks(),
                List.of(matched),
                List.of());
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                arrived = projector.assess(
                "assessment-arrived",
                1,
                population.manifest(),
                population.chunks(),
                List.of(pending, matched),
                List.of(deleted));

        assertThat(partial.totals())
                .isEqualTo(
                        new AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                                .Counts(
                                3, 1, 0, 0,
                                0, 0, 0, 2));
        assertThat(partial.submissionComplete()).isFalse();
        assertThat(partial.terminalComplete()).isFalse();
        assertThat(arrived.totals())
                .isEqualTo(
                        new AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                                .Counts(
                                3, 1, 0, 1,
                                0, 0, 1, 0));
        assertThat(arrived.submissionComplete()).isTrue();
        assertThat(arrived.terminalComplete()).isFalse();
        assertThat(projector.verifyAssessment(arrived))
                .isEqualTo(arrived);
        assertThat(arrived.assessmentSeal().signed()).isTrue();
        assertThat(arrived.toString())
                .doesNotContain(
                        matched.subjectFingerprint(),
                        matched.attributionKeyFingerprint());
    }

    @Test
    void derivesTerminalClosureAndOrderIndependentSourceSetFingerprints() {
        AuthoritativeOutcomeObservation matched =
                observation(
                        0,
                        "observation-member-1",
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH);
        AuthoritativeOutcomeObservation mismatched =
                observation(
                        1,
                        "observation-member-2",
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MISMATCH);
        AuthoritativeOutcomeObservation censored =
                observation(
                        2,
                        "observation-member-3",
                        AuthoritativeOutcomeObservation
                                .Reconciliation.CENSORED);

        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                ordered = projector.assess(
                "assessment-terminal-a",
                1,
                population.manifest(),
                population.chunks(),
                List.of(
                        matched,
                        mismatched,
                        censored),
                List.of());
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                reversed = projector.assess(
                "assessment-terminal-b",
                1,
                population.manifest(),
                population.chunks(),
                List.of(
                        censored,
                        mismatched,
                        matched),
                List.of());

        assertThat(ordered.totals())
                .isEqualTo(
                        new AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                                .Counts(
                                3, 1, 1, 0,
                                1, 0, 0, 0));
        assertThat(ordered.submissionComplete()).isTrue();
        assertThat(ordered.terminalComplete()).isTrue();
        assertThat(reversed.observationSetFingerprint())
                .isEqualTo(
                        ordered.observationSetFingerprint());
        assertThat(reversed.dispositionSetFingerprint())
                .isEqualTo(
                        ordered.dispositionSetFingerprint());
    }

    @Test
    void rejectsDuplicateArrivalOverlapAndMemberCoordinateDrift() {
        AuthoritativeOutcomeObservation first =
                observation(
                        0,
                        "observation-member-1-a",
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH);
        AuthoritativeOutcomeObservation duplicate =
                observation(
                        0,
                        "observation-member-1-b",
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH);
        AuthoritativeOutcomeSelectedPopulationDisposition overlap =
                disposition(
                        0,
                        "deletion-member-1");

        assertReason(
                () -> projector.assess(
                        "assessment-duplicate",
                        1,
                        population.manifest(),
                        population.chunks(),
                        List.of(first, duplicate),
                        List.of()),
                AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                        .Reason.OUTCOME_MEMBER_MISMATCH);
        assertReason(
                () -> projector.assess(
                        "assessment-overlap",
                        1,
                        population.manifest(),
                        population.chunks(),
                        List.of(first),
                        List.of(overlap)),
                AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                        .Reason.OBSERVATION_DISPOSITION_CONFLICT);

        AuthoritativeOutcomeSelectedPopulationChunk.Member
                wrongSubject =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .memberWithSubject(
                                population.members().getFirst(),
                                'f');
        AuthoritativeOutcomeObservation drifted =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedObservation(
                                observationIntegrity,
                                population.manifest(),
                                wrongSubject,
                                "observation-drifted",
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.MATCH);
        assertReason(
                () -> projector.assess(
                        "assessment-drifted",
                        1,
                        population.manifest(),
                        population.chunks(),
                        List.of(drifted),
                        List.of()),
                AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                        .Reason.OUTCOME_MEMBER_MISMATCH);
    }

    @Test
    void rejectsDispositionThatIndependentLegalAuthorityNoLongerAccepts() {
        AuthoritativeOutcomeSelectedPopulationDisposition signed =
                disposition(
                        2,
                        "deletion-member-3");
        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                rejecting =
                new
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
                        mapper,
                        signer,
                        new
                                AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier() {
                                    @Override
                                    public boolean available() {
                                        return true;
                                    }

                                    @Override
                                    public void verify(
                                            AuthoritativeOutcomeSelectedPopulationDisposition
                                                    disposition) {
                                        throw new IllegalStateException(
                                                "sensitive legal rejection");
                                    }
                                },
                        DomainFidelityTestFixtures.CLOCK);

        assertReason(
                () -> projector(rejecting).assess(
                        "assessment-illegal-deletion",
                        1,
                        population.manifest(),
                        population.chunks(),
                        List.of(),
                        List.of(signed)),
                AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                        .Reason.DISPOSITION_INVALID);
    }

    @Test
    void rejectsForgedAssessmentSealTime() {
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                signed = projector.assess(
                "assessment-sealed",
                1,
                population.manifest(),
                population.chunks(),
                List.of(),
                List.of());
        VisualRunEvidenceSeal seal = signed.assessmentSeal();
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                forged = signed.withAssessmentSeal(
                new VisualRunEvidenceSeal(
                        seal.schemaVersion(),
                        seal.materialFingerprint(),
                        seal.algorithm(),
                        seal.keyId(),
                        seal.signedAt().plus(
                                Duration.ofMinutes(10)),
                        seal.signature()));

        assertReason(
                () -> projector.verifyAssessment(forged),
                AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                        .Reason.ASSESSMENT_SIGNING_TIME_INVALID);
    }

    private AuthoritativeOutcomeObservation observation(
            int memberIndex,
            String observationId,
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation) {
        return AuthoritativeOutcomeSelectedPopulationTestFixtures
                .signedObservation(
                        observationIntegrity,
                        population.manifest(),
                        population.members().get(memberIndex),
                        observationId,
                        reconciliation);
    }

    private AuthoritativeOutcomeSelectedPopulationDisposition
    disposition(
            int memberIndex,
            String dispositionId) {
        return AuthoritativeOutcomeSelectedPopulationTestFixtures
                .signedDisposition(
                        dispositionIntegrity,
                        population.manifest(),
                        population.members().get(memberIndex),
                        dispositionId);
    }

    private AuthoritativeOutcomeSelectedPopulationCompletenessProjector
    projector(
            AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                    dispositions) {
        return new
                AuthoritativeOutcomeSelectedPopulationCompletenessProjector(
                mapper,
                populationIntegrity,
                observationIntegrity,
                dispositions,
                signer,
                DomainFidelityTestFixtures.CLOCK);
    }

    private static void assertReason(
            Runnable action,
            AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                    .Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                                .Violation) failure).reason())
                .isEqualTo(reason);
    }
}
