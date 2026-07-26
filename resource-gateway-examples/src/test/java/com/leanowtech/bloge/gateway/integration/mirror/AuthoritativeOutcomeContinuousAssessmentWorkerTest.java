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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeContinuousAssessmentWorkerTest {
    private static final AuthoritativeOutcomeContinuousAssessmentPolicy
            POLICY =
            new AuthoritativeOutcomeContinuousAssessmentPolicy(
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(1),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(8),
                    3);

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> now =
            new AtomicReference<>(
                    DomainFidelityTestFixtures.NOW);

    private EmbeddedDatabase database;
    private DataSourceTransactionManager transactions;
    private AuthoritativeOutcomeSelectedPopulationIntegrity
            populationIntegrity;
    private AuthoritativeOutcomeObservationIntegrity
            observationIntegrity;
    private AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
            dispositionIntegrity;
    private AuthoritativeOutcomeSelectedPopulationCompletenessProjector
            projector;
    private DatabaseAuthoritativeOutcomeInboxRepository outcomes;
    private DatabaseAuthoritativeOutcomeSelectedPopulationRepository
            populations;
    private DatabaseAuthoritativeOutcomeContinuousAssessmentRepository
            projections;
    private AuthoritativeOutcomeSelectedPopulationApplicationService
            assessmentService;
    private AuthoritativeOutcomeSelectedPopulationTestFixtures.Population
            population;
    private AuthoritativeOutcomeContinuousAssessmentRequest request;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc = new JdbcTemplate(database);
        transactions =
                new DataSourceTransactionManager(database);
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(
                        DomainFidelityTestFixtures.CLOCK);
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
                new AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .dispositionAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        projector =
                new AuthoritativeOutcomeSelectedPopulationCompletenessProjector(
                        mapper,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        signer,
                        DomainFidelityTestFixtures.CLOCK);
        outcomes = new DatabaseAuthoritativeOutcomeInboxRepository(
                jdbc,
                mapper,
                observationIntegrity,
                transactions,
                now::get);
        outcomes.init();
        populations =
                new DatabaseAuthoritativeOutcomeSelectedPopulationRepository(
                        jdbc,
                        mapper,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        projector,
                        transactions,
                        now::get);
        populations.init();
        projections =
                new DatabaseAuthoritativeOutcomeContinuousAssessmentRepository(
                        jdbc,
                        mapper,
                        transactions,
                        now::get);
        projections.init();
        assessmentService =
                new AuthoritativeOutcomeSelectedPopulationApplicationService(
                        populations,
                        populationIntegrity,
                        dispositionIntegrity,
                        projector,
                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                .defaults(),
                        mapper,
                        MirrorOperationObservability.noop(),
                        transactions);
        population =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(populationIntegrity);
        populations.register(
                population.manifest(),
                population.chunks(),
                "");
        request = new AuthoritativeOutcomeContinuousAssessmentRequest(
                "",
                "refund-completeness",
                population.manifest().artifactRef());
        projections.register(
                population.manifest().scope(),
                request);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void publishesOnlyWhenSourceCutChangesAndRenewsBoundedFreshness() {
        AuthoritativeOutcomeContinuousAssessmentWorker worker =
                worker(assessmentService);

        worker.runOne("sg", "staging", "worker-1");
        AuthoritativeOutcomeContinuousAssessmentProjection first =
                projection();
        assertThat(first.lastAssessmentRef().revision())
                .isEqualTo(1);
        assertThat(first.freshnessAt(now.get())).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentProjection
                        .Freshness.CURRENT);

        now.set(first.freshUntil());
        worker.runOne("sg", "staging", "worker-2");
        AuthoritativeOutcomeContinuousAssessmentProjection unchanged =
                projection();
        assertThat(unchanged.lastAssessmentRef())
                .isEqualTo(first.lastAssessmentRef());
        assertThat(populations.findLatestAssessment(
                population.manifest().scope(),
                request.assessmentId())
                .orElseThrow().revision()).isEqualTo(1);

        AuthoritativeOutcomeObservation observation =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedObservation(
                                observationIntegrity,
                                population.manifest(),
                                population.members().getFirst(),
                                "member-1-outcome",
                                AuthoritativeOutcomeObservation
                                        .Reconciliation.MATCH);
        outcomes.append(observation, "");
        now.set(unchanged.freshUntil());
        worker.runOne("sg", "staging", "worker-3");

        AuthoritativeOutcomeContinuousAssessmentProjection changed =
                projection();
        assertThat(changed.lastAssessmentRef().revision())
                .isEqualTo(2);
        assertThat(changed.observationSetFingerprint())
                .isNotEqualTo(
                        first.observationSetFingerprint());
        assertThat(populations.findLatestAssessment(
                population.manifest().scope(),
                request.assessmentId())
                .orElseThrow().totals().matched())
                .isEqualTo(1);
    }

    @Test
    void adoptsCommittedAssessmentAfterPreviousOwnerLostProjectionUpdate() {
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                committed =
                new AuthoritativeOutcomeSelectedPopulationService(
                        populations,
                        populationIntegrity,
                        dispositionIntegrity,
                        projector)
                        .assess(
                                population.manifest().scope(),
                                population.manifest()
                                        .populationId(),
                                population.manifest().revision(),
                                request.assessmentId(),
                                1,
                                "")
                        .assessment();

        worker(assessmentService).runOne(
                "sg", "staging", "replacement-owner");

        assertThat(projection().lastAssessmentRef())
                .isEqualTo(committed.artifactRef());
        assertThat(populations.findLatestAssessment(
                population.manifest().scope(),
                request.assessmentId())
                .orElseThrow().revision()).isEqualTo(1);
    }

    @Test
    void exposesUnavailableAuthoritiesAsRetryWaitWithoutExtendingFreshness() {
        AuthoritativeOutcomeSelectedPopulationApplicationService
                unavailable =
                mock(AuthoritativeOutcomeSelectedPopulationApplicationService
                        .class);
        when(unavailable.available()).thenReturn(false);

        worker(unavailable).runOne(
                "sg", "staging", "worker-unavailable");

        AuthoritativeOutcomeContinuousAssessmentProjection failed =
                projection();
        assertThat(failed.status()).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentProjection
                        .Status.RETRY_WAIT);
        assertThat(failed.freshnessAt(now.get())).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentProjection
                        .Freshness.UNINITIALIZED);
        assertThat(failed.failureCode()).isEqualTo(
                "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_AUTHORITY_UNAVAILABLE");
        assertThat(failed.nextEligibleAt())
                .isEqualTo(now.get().plusSeconds(2));
    }

    private AuthoritativeOutcomeContinuousAssessmentWorker worker(
            AuthoritativeOutcomeSelectedPopulationApplicationService
                    service) {
        return new AuthoritativeOutcomeContinuousAssessmentWorker(
                projections,
                populations,
                service,
                POLICY);
    }

    private AuthoritativeOutcomeContinuousAssessmentProjection
    projection() {
        return projections.find(
                population.manifest().scope(),
                request.projectionId())
                .orElseThrow()
                .projection();
    }
}
