package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseAuthoritativeOutcomeSelectedPopulationRepositoryTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final InMemoryVisualEvidenceSigner signer =
            InMemoryVisualEvidenceSigner.usingClock(
                    DomainFidelityTestFixtures.CLOCK);

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AuthoritativeOutcomeSelectedPopulationIntegrity
            populationIntegrity;
    private AuthoritativeOutcomeObservationIntegrity
            observationIntegrity;
    private
    AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
            dispositionIntegrity;
    private AuthoritativeOutcomeSelectedPopulationCompletenessProjector
            projector;
    private DatabaseAuthoritativeOutcomeInboxRepository
            outcomeRepository;
    private
    DatabaseAuthoritativeOutcomeSelectedPopulationRepository
            repository;
    private AuthoritativeOutcomeSelectedPopulationTestFixtures
            .Population population;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions =
                new DataSourceTransactionManager(database);
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
        projector =
                new
                        AuthoritativeOutcomeSelectedPopulationCompletenessProjector(
                        mapper,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        signer,
                        DomainFidelityTestFixtures.CLOCK);
        outcomeRepository =
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        jdbc,
                        mapper,
                        observationIntegrity,
                        transactions,
                        () -> DomainFidelityTestFixtures.NOW);
        outcomeRepository.init();
        repository = repository();
        repository.init();
        population =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(
                                populationIntegrity);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void appendsPopulationOnceAndRecoversExactRootChunksAndMemberIndex()
            throws Exception {
        try (var executor =
                     Executors.newFixedThreadPool(2)) {
            Callable<AuthoritativeOutcomeSelectedPopulationRepository
                    .PopulationAdmission> register =
                    () -> repository.register(
                            population.manifest(),
                            population.chunks(),
                            "");
            Future<AuthoritativeOutcomeSelectedPopulationRepository
                    .PopulationAdmission> first =
                    executor.submit(register);
            Future<AuthoritativeOutcomeSelectedPopulationRepository
                    .PopulationAdmission> second =
                    executor.submit(register);

            assertThat(List.of(
                    first.get().idempotentReplay(),
                    second.get().idempotentReplay()))
                    .containsExactlyInAnyOrder(
                            false, true);
        }
        DatabaseAuthoritativeOutcomeSelectedPopulationRepository
                restarted = repository();
        restarted.init();

        assertThat(restarted.findPopulation(
                population.manifest().scope(),
                population.manifest().populationId(),
                1))
                .contains(
                        new AuthoritativeOutcomeSelectedPopulationRepository
                                .Population(
                                population.manifest(),
                                population.chunks(),
                                ""));
        assertThat(restarted.findLatestPopulation(
                population.manifest().scope(),
                population.manifest().populationId()))
                .isPresent()
                .get()
                .extracting(value ->
                        value.manifest()
                                .artifactRef())
                .isEqualTo(
                        population.manifest()
                                .artifactRef());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mirror_outcome_selected_population_members",
                Long.class)).isEqualTo(3);
    }

    @Test
    void freezesCurrentOutcomeAndDispositionHeadsThenPersistsAssessment() {
        registerPopulation();
        AuthoritativeOutcomeObservation matched =
                appendObservation(
                        0,
                        "observation-member-1",
                        AuthoritativeOutcomeObservation
                                .Reconciliation.MATCH);
        AuthoritativeOutcomeObservation pending =
                appendObservation(
                        1,
                        "observation-member-2",
                        AuthoritativeOutcomeObservation
                                .Reconciliation.PENDING);
        AuthoritativeOutcomeSelectedPopulationDisposition
                disposition =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedDisposition(
                                dispositionIntegrity,
                                population.manifest(),
                                population.members().get(2),
                                "deletion-member-3");
        repository.appendDisposition(
                disposition, "");
        AuthoritativeOutcomeSelectedPopulationService service =
                new AuthoritativeOutcomeSelectedPopulationService(
                        repository,
                        populationIntegrity,
                        dispositionIntegrity,
                        projector);

        AuthoritativeOutcomeSelectedPopulationRepository
                .AssessmentCut cut =
                repository.prepareAssessment(
                        population.manifest().scope(),
                        population.manifest().populationId(),
                        1);
        AuthoritativeOutcomeSelectedPopulationRepository
                .AssessmentAdmission admitted =
                service.assess(
                        population.manifest().scope(),
                        population.manifest().populationId(),
                        1,
                        "assessment-current",
                        1,
                        "");

        assertThat(cut.observations())
                .containsExactly(matched, pending);
        assertThat(cut.dispositions())
                .containsExactly(disposition);
        assertThat(admitted.assessment().totals())
                .isEqualTo(
                        new AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                                .Counts(
                                3, 1, 0, 1,
                                0, 0, 1, 0));
        assertThat(admitted.assessment()
                .submissionComplete()).isTrue();
        assertThat(admitted.assessment()
                .terminalComplete()).isFalse();
        assertThat(repository.findLatestAssessment(
                population.manifest().scope(),
                "assessment-current"))
                .contains(admitted.assessment());
    }

    @Test
    void rejectsPreparedCutAfterAConcurrentOutcomeArrival() {
        registerPopulation();
        appendObservation(
                0,
                "observation-member-1",
                AuthoritativeOutcomeObservation
                        .Reconciliation.MATCH);
        AuthoritativeOutcomeSelectedPopulationRepository
                .AssessmentCut stale =
                repository.prepareAssessment(
                        population.manifest().scope(),
                        population.manifest().populationId(),
                        1);
        appendObservation(
                1,
                "observation-member-2",
                AuthoritativeOutcomeObservation
                        .Reconciliation.PENDING);
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                assessment = projector.assess(
                "assessment-stale",
                1,
                population.manifest(),
                population.chunks(),
                stale.observations(),
                stale.dispositions());

        assertReason(
                () -> repository.appendAssessment(
                        stale,
                        assessment,
                        ""),
                AuthoritativeOutcomeSelectedPopulationRepository
                        .Reason.CUT_STALE);
        assertThat(repository.findAssessment(
                population.manifest().scope(),
                "assessment-stale",
                1)).isEmpty();
    }

    @Test
    void serviceRetriesAStaleCutAfterAuthorityTimeConcurrentArrival() {
        registerPopulation();
        appendObservation(
                0,
                "observation-member-1",
                AuthoritativeOutcomeObservation
                        .Reconciliation.MATCH);
        AtomicBoolean insertDuringAuthority =
                new AtomicBoolean(true);
        AtomicInteger authorityCalls =
                new AtomicInteger();
        AuthoritativeOutcomeObservationIntegrity
                racingObservationIntegrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        new AuthoritativeOutcomeAuthorityVerifier() {
                            @Override
                            public boolean available() {
                                return true;
                            }

                            @Override
                            public void verify(
                                    AuthoritativeOutcomeObservation
                                            observation) {
                                authorityCalls.incrementAndGet();
                                assertThat(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive())
                                        .isFalse();
                                if (insertDuringAuthority
                                        .compareAndSet(
                                                true,
                                                false)) {
                                    appendObservation(
                                            1,
                                            "observation-member-2",
                                            AuthoritativeOutcomeObservation
                                                    .Reconciliation
                                                    .PENDING);
                                }
                            }
                        },
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                racingProjector =
                new
                        AuthoritativeOutcomeSelectedPopulationCompletenessProjector(
                        mapper,
                        populationIntegrity,
                        racingObservationIntegrity,
                        dispositionIntegrity,
                        signer,
                        DomainFidelityTestFixtures.CLOCK);
        DatabaseAuthoritativeOutcomeSelectedPopulationRepository
                racingRepository =
                new
                        DatabaseAuthoritativeOutcomeSelectedPopulationRepository(
                        jdbc,
                        mapper,
                        populationIntegrity,
                        racingObservationIntegrity,
                        dispositionIntegrity,
                        racingProjector,
                        transactions,
                        () -> DomainFidelityTestFixtures.NOW);
        racingRepository.init();
        AuthoritativeOutcomeSelectedPopulationService service =
                new AuthoritativeOutcomeSelectedPopulationService(
                        racingRepository,
                        populationIntegrity,
                        dispositionIntegrity,
                        racingProjector);

        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                assessment = service.assess(
                population.manifest().scope(),
                population.manifest().populationId(),
                1,
                "assessment-raced",
                1,
                "").assessment();

        assertThat(insertDuringAuthority).isFalse();
        assertThat(authorityCalls.get())
                .isGreaterThanOrEqualTo(3);
        assertThat(assessment.totals())
                .isEqualTo(
                        new AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                                .Counts(
                                3, 1, 0, 1,
                                0, 0, 0, 1));
    }

    @Test
    void rejectsTwoCurrentDeletionProofsForTheSameSelectedMember() {
        registerPopulation();
        AuthoritativeOutcomeSelectedPopulationChunk.Member
                member = population.members().getFirst();
        AuthoritativeOutcomeSelectedPopulationDisposition
                first =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedDisposition(
                                dispositionIntegrity,
                                population.manifest(),
                                member,
                                "deletion-member-1-a");
        AuthoritativeOutcomeSelectedPopulationDisposition
                second =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedDisposition(
                                dispositionIntegrity,
                                population.manifest(),
                                member,
                                "deletion-member-1-b");

        assertThat(repository.appendDisposition(
                first, "").idempotentReplay()).isFalse();
        assertThat(repository.appendDisposition(
                first, "").idempotentReplay()).isTrue();
        assertReason(
                () -> repository.appendDisposition(
                        second, ""),
                AuthoritativeOutcomeSelectedPopulationRepository
                        .Reason.MEMBER_CONFLICT);
    }

    @Test
    void failsClosedWhenPersistedMemberIndexIsTampered() {
        registerPopulation();
        jdbc.update("""
                UPDATE mirror_outcome_selected_population_members
                SET subject_fingerprint = ?
                WHERE global_ordinal = 1
                """,
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .fingerprint('f'));

        assertReason(
                () -> repository.findPopulation(
                        population.manifest().scope(),
                        population.manifest()
                                .populationId(),
                        1),
                AuthoritativeOutcomeSelectedPopulationRepository
                        .Reason.STORED_STATE_CORRUPT);
    }

    @Test
    void failsClosedWhenAssessmentHistoryOrHeadIsTampered() {
        registerPopulation();
        AuthoritativeOutcomeSelectedPopulationService service =
                new AuthoritativeOutcomeSelectedPopulationService(
                        repository,
                        populationIntegrity,
                        dispositionIntegrity,
                        projector);
        service.assess(
                population.manifest().scope(),
                population.manifest().populationId(),
                1,
                "assessment-tampered",
                1,
                "");
        jdbc.update("""
                UPDATE mirror_outcome_population_assessments
                SET assessment_json = '{}'
                WHERE assessment_id = 'assessment-tampered'
                """);

        assertReason(
                () -> repository.findLatestAssessment(
                        population.manifest().scope(),
                        "assessment-tampered"),
                AuthoritativeOutcomeSelectedPopulationRepository
                        .Reason.STORED_STATE_CORRUPT);
    }

    private void registerPopulation() {
        repository.register(
                population.manifest(),
                population.chunks(),
                "");
    }

    private AuthoritativeOutcomeObservation appendObservation(
            int memberIndex,
            String observationId,
            AuthoritativeOutcomeObservation.Reconciliation
                    reconciliation) {
        AuthoritativeOutcomeObservation observation =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedObservation(
                                observationIntegrity,
                                population.manifest(),
                                population.members().get(
                                        memberIndex),
                                observationId,
                                reconciliation);
        outcomeRepository.append(observation, "");
        return observation;
    }

    private
    DatabaseAuthoritativeOutcomeSelectedPopulationRepository
    repository() {
        return new
                DatabaseAuthoritativeOutcomeSelectedPopulationRepository(
                jdbc,
                mapper,
                populationIntegrity,
                observationIntegrity,
                dispositionIntegrity,
                projector,
                transactions,
                () -> DomainFidelityTestFixtures.NOW);
    }

    private static void assertReason(
            Runnable action,
            AuthoritativeOutcomeSelectedPopulationRepository
                    .Reason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        AuthoritativeOutcomeSelectedPopulationRepository
                                .Violation.class)
                .extracting(failure ->
                        ((AuthoritativeOutcomeSelectedPopulationRepository
                                .Violation) failure).reason())
                .isEqualTo(reason);
    }
}
