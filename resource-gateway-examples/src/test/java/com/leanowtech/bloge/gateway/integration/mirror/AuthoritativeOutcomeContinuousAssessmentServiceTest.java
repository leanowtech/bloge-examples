package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeContinuousAssessmentServiceTest {
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
    private DatabaseAuthoritativeOutcomeSelectedPopulationRepository
            populations;
    private DatabaseAuthoritativeOutcomeContinuousAssessmentRepository
            projections;
    private AuthoritativeOutcomeSelectedPopulationApplicationService
            populationService;
    private AuthoritativeOutcomeContinuousAssessmentService service;
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
        AuthoritativeOutcomeSelectedPopulationIntegrity
                populationIntegrity =
                new AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .populationAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeObservationIntegrity
                observationIntegrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .outcomeAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
                dispositionIntegrity =
                new AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .dispositionAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeSelectedPopulationCompletenessProjector
                projector =
                new AuthoritativeOutcomeSelectedPopulationCompletenessProjector(
                        mapper,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        signer,
                        DomainFidelityTestFixtures.CLOCK);
        DatabaseAuthoritativeOutcomeInboxRepository outcomes =
                new DatabaseAuthoritativeOutcomeInboxRepository(
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
        populationService =
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
        service = service(populationService);
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
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void registersExactIntentAndReportsEffectiveReadinessAfterWorkerTurn() {
        AuthoritativeOutcomeContinuousAssessmentAdmission first =
                service.register(
                        request,
                        projectorIdentity(
                                "support",
                                "staging",
                                Set.of(
                                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                                .DEFAULT_ASSESSMENT_GROUP)));
        AuthoritativeOutcomeContinuousAssessmentAdmission replay =
                service.register(
                        request,
                        projectorIdentity(
                                "support",
                                "staging",
                                Set.of(
                                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                                .DEFAULT_ASSESSMENT_GROUP)));

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(first.status().sourceFreshness())
                .isEqualTo(
                        AuthoritativeOutcomeContinuousAssessmentProjection
                                .Freshness.UNINITIALIZED);
        assertThat(first.status().authoritiesReady()).isTrue();
        assertThat(first.status().ready()).isFalse();

        new AuthoritativeOutcomeContinuousAssessmentWorker(
                projections,
                populations,
                populationService,
                POLICY)
                .runOne(
                        "sg",
                        "staging",
                        "service-test-worker");
        AuthoritativeOutcomeContinuousAssessmentStatus current =
                service.find(
                        request.projectionId(),
                        reader("support", "staging"));

        assertThat(current.sourceFreshness()).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentProjection
                        .Freshness.CURRENT);
        assertThat(current.authoritiesReady()).isTrue();
        assertThat(current.ready()).isTrue();
        assertThat(current.projection()
                .lastAssessmentRef().revision())
                .isEqualTo(1);
    }

    @Test
    void revokesEffectiveReadinessWhenAuthoritiesBecomeUnavailable() {
        service.register(
                request,
                projectorIdentity(
                        "support",
                        "staging",
                        Set.of(
                                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                        .DEFAULT_ASSESSMENT_GROUP)));
        new AuthoritativeOutcomeContinuousAssessmentWorker(
                projections,
                populations,
                populationService,
                POLICY)
                .runOne(
                        "sg",
                        "staging",
                        "service-test-worker");
        AuthoritativeOutcomeSelectedPopulationApplicationService
                unavailable =
                mock(AuthoritativeOutcomeSelectedPopulationApplicationService
                        .class);
        when(unavailable.available()).thenReturn(false);

        AuthoritativeOutcomeContinuousAssessmentStatus status =
                service(unavailable).find(
                        request.projectionId(),
                        reader("support", "staging"));

        assertThat(status.sourceFreshness()).isEqualTo(
                AuthoritativeOutcomeContinuousAssessmentProjection
                        .Freshness.CURRENT);
        assertThat(status.authoritiesReady()).isFalse();
        assertThat(status.ready()).isFalse();
    }

    @Test
    void registrationUsesAtomicAdmissionObservationWithoutPostCommitRead() {
        AuthoritativeOutcomeContinuousAssessmentRepository tracked =
                spy(projections);
        AuthoritativeOutcomeContinuousAssessmentService trackedService =
                new AuthoritativeOutcomeContinuousAssessmentService(
                        tracked,
                        populationService,
                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                .defaults(),
                        MirrorOperationObservability.noop(),
                        transactions);

        AuthoritativeOutcomeContinuousAssessmentAdmission admission =
                trackedService.register(
                        request,
                        projectorIdentity(
                                "support",
                                "staging",
                                Set.of(
                                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                                .DEFAULT_ASSESSMENT_GROUP)));

        assertThat(admission.status().observedAt())
                .isEqualTo(now.get());
        verify(tracked, never()).find(
                any(CapabilitySnapshot.Scope.class),
                anyString());
    }

    @Test
    void servesBoundedLifecyclePagesAndRejectsUnknownCursor() {
        service.register(
                request,
                projectorIdentity(
                        "support",
                        "staging",
                        Set.of(
                                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                        .DEFAULT_ASSESSMENT_GROUP)));
        new AuthoritativeOutcomeContinuousAssessmentWorker(
                projections,
                populations,
                populationService,
                POLICY)
                .runOne(
                        "sg",
                        "staging",
                        "service-test-worker");

        AuthoritativeOutcomeContinuousAssessmentLifecyclePage first =
                service.lifecycle(
                        request.projectionId(),
                        0,
                        2,
                        reader(
                                "support",
                                "staging"));
        AuthoritativeOutcomeContinuousAssessmentLifecyclePage second =
                service.lifecycle(
                        request.projectionId(),
                        first.nextOrdinal(),
                        2,
                        reader(
                                "support",
                                "staging"));

        assertThat(first.events())
                .extracting(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                ::transition)
                .containsExactly(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                .Transition.REGISTERED,
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                .Transition.CLAIMED);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.events())
                .extracting(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                ::transition)
                .containsExactly(
                        AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                                .Transition.ASSESSMENT_PUBLISHED);
        assertThat(second.predecessorFingerprint())
                .isEqualTo(first.events()
                        .getLast()
                        .eventFingerprint());
        assertProblem(
                () -> service.lifecycle(
                        request.projectionId(),
                        99,
                        10,
                        reader(
                                "support",
                                "staging")),
                "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_LIFECYCLE_CURSOR_INVALID");
    }

    @Test
    void rejectsWrongGroupScopePurposeAndProductionEnvironment() {
        assertProblem(
                () -> service.register(
                        request,
                        projectorIdentity(
                                "support",
                                "staging",
                                Set.of("WRONG_GROUP"))),
                "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_PROJECTOR_FORBIDDEN");
        assertProblem(
                () -> service.register(
                        request,
                        projectorIdentity(
                                "another-organization",
                                "staging",
                                Set.of(
                                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                                .DEFAULT_ASSESSMENT_GROUP))),
                "RG.MIRROR.OUTCOME.POPULATION_NOT_FOUND");
        assertProblem(
                () -> service.find(
                        request.projectionId(),
                        projectorIdentity(
                                "support",
                                "staging",
                                Set.of(
                                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                                .DEFAULT_ASSESSMENT_GROUP),
                                "UNRELATED_PURPOSE")),
                "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_READ_FORBIDDEN");
        assertProblem(
                () -> service.find(
                        request.projectionId(),
                        reader("support", "production")),
                "RG.MIRROR.OUTCOME.CONTINUOUS_ASSESSMENT_ENVIRONMENT_FORBIDDEN");
    }

    private AuthoritativeOutcomeContinuousAssessmentService service(
            AuthoritativeOutcomeSelectedPopulationApplicationService
                    selectedPopulationService) {
        return new AuthoritativeOutcomeContinuousAssessmentService(
                projections,
                selectedPopulationService,
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .defaults(),
                MirrorOperationObservability.noop(),
                transactions);
    }

    private static IntegrationRequestContext projectorIdentity(
            String organization,
            String environment,
            Set<String> groups) {
        return projectorIdentity(
                organization,
                environment,
                groups,
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .ASSESSMENT_PURPOSE);
    }

    private static IntegrationRequestContext projectorIdentity(
            String organization,
            String environment,
            Set<String> groups,
            String purpose) {
        return new IntegrationRequestContext(
                "tenant-a",
                organization,
                "refunds",
                environment,
                "sg",
                "WORKLOAD",
                "fidelity-projector",
                "",
                purpose,
                "correlation-continuous-assessment",
                groups,
                "CONFIDENTIAL",
                "");
    }

    private static IntegrationRequestContext reader(
            String organization,
            String environment) {
        return projectorIdentity(
                organization,
                environment,
                Set.of(),
                "GOVERNANCE_EVIDENCE_INGESTION");
    }

    private static void assertProblem(
            Runnable action,
            String code) {
        assertThatThrownBy(action::run)
                .isInstanceOf(
                        IntegrationProblemException.class)
                .extracting(failure ->
                        ((IntegrationProblemException) failure)
                                .problem().code())
                .isEqualTo(code);
    }
}
