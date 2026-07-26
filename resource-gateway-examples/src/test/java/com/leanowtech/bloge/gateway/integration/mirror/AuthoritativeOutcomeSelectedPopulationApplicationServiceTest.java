package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeSelectedPopulationApplicationServiceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicBoolean authoritiesAvailable =
            new AtomicBoolean(true);
    private final AtomicInteger selectionAuthorityCalls =
            new AtomicInteger();
    private final AtomicInteger dispositionAuthorityCalls =
            new AtomicInteger();
    private final AtomicInteger observationAuthorityCalls =
            new AtomicInteger();

    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AuthoritativeOutcomeSelectedPopulationIntegrity
            populationIntegrity;
    private
    AuthoritativeOutcomeSelectedPopulationDispositionIntegrity
            dispositionIntegrity;
    private AuthoritativeOutcomeSelectedPopulationCompletenessProjector
            projector;
    private
    DatabaseAuthoritativeOutcomeSelectedPopulationRepository
            repository;
    private AuthoritativeOutcomeSelectedPopulationApplicationService
            service;
    private AuthoritativeOutcomeSelectedPopulationTestFixtures
            .Population fixture;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions =
                new DataSourceTransactionManager(database);
        InMemoryVisualEvidenceSigner signer =
                InMemoryVisualEvidenceSigner.usingClock(
                        DomainFidelityTestFixtures.CLOCK);
        populationIntegrity =
                new
                        AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        signer,
                        selectionAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        AuthoritativeOutcomeObservationIntegrity
                observationIntegrity =
                new AuthoritativeOutcomeObservationIntegrity(
                        mapper,
                        signer,
                        observationAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        dispositionIntegrity =
                new
                        AuthoritativeOutcomeSelectedPopulationDispositionIntegrity(
                        mapper,
                        signer,
                        dispositionAuthority(),
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
        DatabaseAuthoritativeOutcomeInboxRepository
                outcomeRepository =
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        jdbc,
                        mapper,
                        observationIntegrity,
                        transactions,
                        () -> DomainFidelityTestFixtures.NOW);
        outcomeRepository.init();
        repository =
                new
                        DatabaseAuthoritativeOutcomeSelectedPopulationRepository(
                        jdbc,
                        mapper,
                        populationIntegrity,
                        observationIntegrity,
                        dispositionIntegrity,
                        projector,
                        transactions,
                        () -> DomainFidelityTestFixtures.NOW);
        repository.init();
        service = service(
                MirrorOperationObservability.noop());

        AuthoritativeOutcomeSelectedPopulationIntegrity
                fixtureIntegrity =
                new
                        AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        signer,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .populationAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        fixture =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(
                                fixtureIntegrity);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void signsOnceAndRecoversUnsignedPopulationDispositionAndAssessmentRetries() {
        AuthoritativeOutcomeSelectedPopulationAdmissionRequest
                populationRequest =
                new
                        AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                        "",
                        "",
                        unsignedManifest(),
                        fixture.chunks());

        AuthoritativeOutcomeSelectedPopulationAdmission
                firstPopulation =
                service.ingestPopulation(
                        populationRequest,
                        selectionIdentity("staging"));
        AuthoritativeOutcomeSelectedPopulationAdmission
                replayedPopulation =
                service.ingestPopulation(
                        populationRequest,
                        selectionIdentity("staging"));

        AuthoritativeOutcomeSelectedPopulationDisposition
                unsignedDisposition =
                unsignedDisposition(
                        firstPopulation.population()
                                .manifest());
        AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest
                dispositionRequest =
                new
                        AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest(
                        "",
                        "",
                        unsignedDisposition);
        AuthoritativeOutcomeSelectedPopulationDispositionAdmission
                firstDisposition =
                service.ingestDisposition(
                        fixture.manifest().populationId(),
                        dispositionRequest,
                        dispositionIdentity("staging"));
        AuthoritativeOutcomeSelectedPopulationDispositionAdmission
                replayedDisposition =
                service.ingestDisposition(
                        fixture.manifest().populationId(),
                        dispositionRequest,
                        dispositionIdentity("staging"));

        AuthoritativeOutcomeSelectedPopulationAssessmentRequest
                assessmentRequest =
                new
                        AuthoritativeOutcomeSelectedPopulationAssessmentRequest(
                        "",
                        1,
                        "assessment-1",
                        1,
                        "");
        AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
                firstAssessment =
                service.assess(
                        fixture.manifest().populationId(),
                        assessmentRequest,
                        assessmentIdentity("staging"));
        AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
                replayedAssessment =
                service.assess(
                        fixture.manifest().populationId(),
                        assessmentRequest,
                        assessmentIdentity("staging"));

        assertThat(firstPopulation.idempotentReplay())
                .isFalse();
        assertThat(replayedPopulation.idempotentReplay())
                .isTrue();
        assertThat(replayedPopulation.population())
                .isEqualTo(firstPopulation.population());
        assertThat(firstPopulation.population()
                .manifest().manifestSeal().signed())
                .isTrue();
        assertThat(firstDisposition.idempotentReplay())
                .isFalse();
        assertThat(replayedDisposition.idempotentReplay())
                .isTrue();
        assertThat(replayedDisposition.disposition())
                .isEqualTo(firstDisposition.disposition());
        assertThat(firstDisposition.disposition()
                .dispositionSeal().signed())
                .isTrue();
        assertThat(firstAssessment.idempotentReplay())
                .isFalse();
        assertThat(replayedAssessment.idempotentReplay())
                .isTrue();
        assertThat(firstAssessment.assessment()
                .totals().legallyDeleted()).isOne();
        assertThat(firstAssessment.assessment()
                .totals().missing()).isEqualTo(2);
        assertThat(selectionAuthorityCalls.get())
                .isGreaterThanOrEqualTo(5);
        assertThat(dispositionAuthorityCalls.get())
                .isGreaterThanOrEqualTo(4);
        assertThat(observationAuthorityCalls).hasValue(0);
    }

    @Test
    void recoversConcurrentUnsignedCommandsAfterTheInitialRecoveryWindow() {
        AuthoritativeOutcomeSelectedPopulationRepository
                concurrentRepository = mock(
                AuthoritativeOutcomeSelectedPopulationRepository
                        .class);
        AuthoritativeOutcomeSelectedPopulationManifest
                signedManifest = populationIntegrity.sign(
                unsignedManifest(), fixture.chunks());
        AuthoritativeOutcomeSelectedPopulationRepository
                .Population storedPopulation =
                new AuthoritativeOutcomeSelectedPopulationRepository
                        .Population(
                        signedManifest,
                        fixture.chunks(),
                        "");
        when(concurrentRepository.findPopulation(
                signedManifest.scope(),
                signedManifest.populationId(),
                signedManifest.revision()))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(storedPopulation));
        when(concurrentRepository.registerPreverified(
                any(), eq(fixture.chunks()), eq("")))
                .thenThrow(contentConflict());

        AuthoritativeOutcomeSelectedPopulationDisposition
                unsignedDisposition =
                unsignedDisposition(signedManifest);
        AuthoritativeOutcomeSelectedPopulationDisposition
                signedDisposition =
                dispositionIntegrity.sign(
                        unsignedDisposition);
        AuthoritativeOutcomeSelectedPopulationRepository
                .DispositionAdmission storedDisposition =
                new AuthoritativeOutcomeSelectedPopulationRepository
                        .DispositionAdmission(
                        signedDisposition, "", true);
        when(concurrentRepository.recoverDisposition(
                signedManifest.scope(),
                unsignedDisposition.dispositionId(),
                unsignedDisposition.revision(),
                ""))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(storedDisposition));
        when(concurrentRepository
                .appendDispositionPreverified(
                        any(), eq("")))
                .thenThrow(contentConflict());

        repository.register(
                signedManifest,
                fixture.chunks(),
                "");
        AuthoritativeOutcomeSelectedPopulationRepository
                .AssessmentCut cut =
                repository.prepareAssessment(
                        signedManifest.scope(),
                        signedManifest.populationId(),
                        signedManifest.revision());
        AuthoritativeOutcomeSelectedPopulationCompletenessAssessment
                signedAssessment = projector.assess(
                "assessment-concurrent",
                1,
                signedManifest,
                fixture.chunks(),
                cut.observations(),
                cut.dispositions());
        AuthoritativeOutcomeSelectedPopulationRepository
                .AssessmentAdmission storedAssessment =
                new AuthoritativeOutcomeSelectedPopulationRepository
                        .AssessmentAdmission(
                        signedAssessment, "", true);
        when(concurrentRepository.recoverAssessment(
                signedManifest.scope(),
                "assessment-concurrent",
                1,
                ""))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(storedAssessment));
        when(concurrentRepository.prepareAssessment(
                signedManifest.scope(),
                signedManifest.populationId(),
                signedManifest.revision()))
                .thenReturn(cut);
        when(concurrentRepository.appendAssessment(
                eq(cut), any(), eq("")))
                .thenThrow(contentConflict());
        AuthoritativeOutcomeSelectedPopulationApplicationService
                concurrentService =
                service(
                        concurrentRepository,
                        MirrorOperationObservability.noop());

        assertThat(concurrentService.ingestPopulation(
                new AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                        "",
                        "",
                        unsignedManifest(),
                        fixture.chunks()),
                selectionIdentity("staging"))
                .idempotentReplay()).isTrue();
        assertThat(concurrentService.ingestDisposition(
                signedManifest.populationId(),
                new
                        AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest(
                        "",
                        "",
                        unsignedDisposition),
                dispositionIdentity("staging"))
                .idempotentReplay()).isTrue();
        assertThat(concurrentService.assess(
                signedManifest.populationId(),
                new AuthoritativeOutcomeSelectedPopulationAssessmentRequest(
                        "",
                        signedManifest.revision(),
                        "assessment-concurrent",
                        1,
                        ""),
                assessmentIdentity("staging"))
                .idempotentReplay()).isTrue();
    }

    @Test
    void readsExactEvidenceAndContentAddressedHistoricalSourcesInScope() {
        AuthoritativeOutcomeSelectedPopulationAdmission
                population =
                service.ingestPopulation(
                        new
                                AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                                "",
                                "",
                                unsignedManifest(),
                                fixture.chunks()),
                        selectionIdentity("staging"));
        AuthoritativeOutcomeSelectedPopulationDispositionAdmission
                disposition =
                service.ingestDisposition(
                        fixture.manifest().populationId(),
                        new
                                AuthoritativeOutcomeSelectedPopulationDispositionAdmissionRequest(
                                "",
                                "",
                                unsignedDisposition(
                                        population.population()
                                                .manifest())),
                        dispositionIdentity("staging"));
        AuthoritativeOutcomeSelectedPopulationAssessmentAdmission
                assessment =
                service.assess(
                        fixture.manifest().populationId(),
                        new
                                AuthoritativeOutcomeSelectedPopulationAssessmentRequest(
                                "",
                                1,
                                "assessment-read",
                                1,
                                ""),
                        assessmentIdentity("staging"));
        IntegrationRequestContext reader =
                reader("support");

        assertThat(service.findPopulation(
                fixture.manifest().populationId(),
                1,
                reader)).isEqualTo(
                population.population());
        assertThat(service.findLatestPopulation(
                fixture.manifest().populationId(),
                reader)).isEqualTo(
                population.population());
        assertThat(service.findDisposition(
                fixture.manifest().populationId(),
                disposition.disposition()
                        .dispositionId(),
                1,
                reader)).isEqualTo(
                disposition.disposition());
        assertThat(service.findAssessment(
                fixture.manifest().populationId(),
                "assessment-read",
                1,
                reader)).isEqualTo(
                assessment.assessment());
        AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                page = service.assessmentSources(
                fixture.manifest().populationId(),
                "assessment-read",
                1,
                0,
                100,
                reader);
        page.verify(mapper);
        assertThat(page.complete()).isTrue();
        assertThat(page.entries())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.globalOrdinal())
                            .isEqualTo(1);
                    assertThat(entry.sourceKind())
                            .isEqualTo(
                                    AuthoritativeOutcomeSelectedPopulationAssessmentSourcePage
                                            .SourceKind
                                            .LEGAL_DISPOSITION);
                });
    }

    @Test
    void rejectsRoleConfusionWrongGroupProductionAndCrossScopeAccess() {
        assertProblem(
                () -> service.ingestPopulation(
                        new
                                AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                                "",
                                "",
                                unsignedManifest(),
                                fixture.chunks()),
                        dispositionIdentity("staging")),
                "RG.MIRROR.OUTCOME.POPULATION_PURPOSE_FORBIDDEN");
        assertProblem(
                () -> service.ingestPopulation(
                        new
                                AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                                "",
                                "",
                                unsignedManifest(),
                                fixture.chunks()),
                        identity(
                                "support",
                                "staging",
                                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                        .SELECTION_PURPOSE,
                                Set.of("OTHER_AUTHORITY"))),
                "RG.MIRROR.OUTCOME.POPULATION_AUTHORITY_FORBIDDEN");
        assertProblem(
                () -> service.ingestPopulation(
                        new
                                AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                                "",
                                "",
                                unsignedManifest(),
                                fixture.chunks()),
                        selectionIdentity("production")),
                "RG.MIRROR.OUTCOME.POPULATION_ENVIRONMENT_FORBIDDEN");
        assertProblem(
                () -> service.ingestPopulation(
                        new
                                AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                                "",
                                "",
                                unsignedManifest(),
                                fixture.chunks()),
                        identity(
                                "other",
                                "staging",
                                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                        .SELECTION_PURPOSE,
                                Set.of(
                                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                                .DEFAULT_SELECTION_GROUP))),
                "RG.MIRROR.OUTCOME.POPULATION_NOT_FOUND");
        assertThat(
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mirror_outcome_selected_populations",
                        Integer.class))
                .isZero();
    }

    @Test
    void externalAuthorityOutageFailsClosedAndAuditFailureRollsBackMutation() {
        authoritiesAvailable.set(false);
        assertProblem(
                () -> service.ingestPopulation(
                        new
                                AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                                "",
                                "",
                                unsignedManifest(),
                                fixture.chunks()),
                        selectionIdentity("staging")),
                "RG.MIRROR.OUTCOME.POPULATION_UNAVAILABLE");
        assertThat(
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mirror_outcome_selected_populations",
                        Integer.class))
                .isZero();

        authoritiesAvailable.set(true);
        MirrorOperationAuditRepository failingAudit =
                new MirrorOperationAuditRepository() {
                    @Override
                    public MirrorOperationAuditEvent append(
                            MirrorOperationAuditEvent event) {
                        throw new IllegalStateException(
                                "audit unavailable");
                    }

                    @Override
                    public List<MirrorOperationAuditEvent> recent(
                            CapabilitySnapshot.Scope scope,
                            int limit) {
                        return List.of();
                    }
                };
        AuthoritativeOutcomeSelectedPopulationApplicationService
                audited =
                service(
                        new MirrorOperationObservability(
                                failingAudit,
                                MirrorOperationTelemetry.noop(),
                                () -> 1L));

        assertProblem(
                () -> audited.ingestPopulation(
                        new
                                AuthoritativeOutcomeSelectedPopulationAdmissionRequest(
                                "",
                                "",
                                unsignedManifest(),
                                fixture.chunks()),
                        selectionIdentity("staging")),
                "RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE");
        assertThat(
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mirror_outcome_selected_populations",
                        Integer.class))
                .isZero();
        assertThat(
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mirror_outcome_selected_population_heads",
                        Integer.class))
                .isZero();
    }

    private AuthoritativeOutcomeSelectedPopulationApplicationService
    service(MirrorOperationObservability observability) {
        return service(repository, observability);
    }

    private AuthoritativeOutcomeSelectedPopulationApplicationService
    service(
            AuthoritativeOutcomeSelectedPopulationRepository
                    selectedPopulationRepository,
            MirrorOperationObservability observability) {
        return new
                AuthoritativeOutcomeSelectedPopulationApplicationService(
                selectedPopulationRepository,
                populationIntegrity,
                dispositionIntegrity,
                projector,
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .defaults(),
                mapper,
                observability,
                transactions);
    }

    private static AuthoritativeOutcomeSelectedPopulationRepository
            .Violation contentConflict() {
        return new AuthoritativeOutcomeSelectedPopulationRepository
                .Violation(
                AuthoritativeOutcomeSelectedPopulationRepository
                        .Reason.CONTENT_CONFLICT);
    }

    private AuthoritativeOutcomeSelectedPopulationManifest
    unsignedManifest() {
        AuthoritativeOutcomeSelectedPopulationManifest source =
                fixture.manifest();
        return new
                AuthoritativeOutcomeSelectedPopulationManifest(
                source.schemaVersion(),
                source.populationId(),
                source.revision(),
                "",
                source.scope(),
                source.inventoryRef(),
                source.cohortRef(),
                source.samplingFrameRef(),
                source.selectionPolicyRef(),
                source.selectionAuthoritySetRef(),
                source.selectionAttestationRef(),
                source.selectedAt(),
                source.strata(),
                source.chunks(),
                source.totalEligiblePopulation(),
                source.totalSelectedPopulation(),
                source.attestedAt(),
                VisualRunEvidenceSeal.unsigned());
    }

    private AuthoritativeOutcomeSelectedPopulationDisposition
    unsignedDisposition(
            AuthoritativeOutcomeSelectedPopulationManifest
                    admittedPopulation) {
        AuthoritativeOutcomeSelectedPopulationChunk.Member
                member = fixture.members().getFirst();
        return new
                AuthoritativeOutcomeSelectedPopulationDisposition(
                AuthoritativeOutcomeSelectedPopulationDisposition
                        .SCHEMA_VERSION,
                "deletion-member-1",
                1,
                "",
                admittedPopulation.scope(),
                admittedPopulation.artifactRef(),
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
                new MirrorArtifactRef(
                        "OUTCOME_DATA_RETENTION_POLICY",
                        "refund-retention-v1",
                        1,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .fingerprint('d')),
                new MirrorArtifactRef(
                        "OUTCOME_MEMBER_DELETION_APPROVAL",
                        "deletion-member-1-approval",
                        1,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .fingerprint('e')),
                new MirrorArtifactRef(
                        "OUTCOME_DELETION_AUTHORITY_SET",
                        "refund-deletion-authorities",
                        1,
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .fingerprint('f')),
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .RECONCILED_AT,
                DomainFidelityTestFixtures.NOW,
                VisualRunEvidenceSeal.unsigned());
    }

    private AuthoritativeOutcomeSelectedPopulationAuthorityVerifier
    selectionAuthority() {
        return new
                AuthoritativeOutcomeSelectedPopulationAuthorityVerifier() {
                    @Override
                    public boolean available() {
                        return authoritiesAvailable.get();
                    }

                    @Override
                    public void verify(
                            AuthoritativeOutcomeSelectedPopulationManifest
                                    manifest,
                            List<AuthoritativeOutcomeSelectedPopulationChunk>
                                    chunks) {
                        selectionAuthorityCalls.incrementAndGet();
                        assertOutsideTransaction();
                        if (!authoritiesAvailable.get()) {
                            throw new IllegalStateException(
                                    "selection authority unavailable");
                        }
                    }
                };
    }

    private
    AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier
    dispositionAuthority() {
        return new
                AuthoritativeOutcomeSelectedPopulationDispositionAuthorityVerifier() {
                    @Override
                    public boolean available() {
                        return authoritiesAvailable.get();
                    }

                    @Override
                    public void verify(
                            AuthoritativeOutcomeSelectedPopulationDisposition
                                    disposition) {
                        dispositionAuthorityCalls.incrementAndGet();
                        assertOutsideTransaction();
                        if (!authoritiesAvailable.get()) {
                            throw new IllegalStateException(
                                    "disposition authority unavailable");
                        }
                    }
                };
    }

    private AuthoritativeOutcomeAuthorityVerifier
    observationAuthority() {
        return new AuthoritativeOutcomeAuthorityVerifier() {
            @Override
            public boolean available() {
                return authoritiesAvailable.get();
            }

            @Override
            public void verify(
                    AuthoritativeOutcomeObservation observation) {
                observationAuthorityCalls.incrementAndGet();
                assertOutsideTransaction();
                if (!authoritiesAvailable.get()) {
                    throw new IllegalStateException(
                            "outcome authority unavailable");
                }
            }
        };
    }

    private static void assertOutsideTransaction() {
        assertThat(
                TransactionSynchronizationManager
                        .isActualTransactionActive())
                .as("external authority I/O must not hold a database transaction")
                .isFalse();
    }

    private static IntegrationRequestContext
    selectionIdentity(String environment) {
        return identity(
                "support",
                environment,
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .SELECTION_PURPOSE,
                Set.of(
                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                .DEFAULT_SELECTION_GROUP));
    }

    private static IntegrationRequestContext
    dispositionIdentity(String environment) {
        return identity(
                "support",
                environment,
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .DISPOSITION_PURPOSE,
                Set.of(
                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                .DEFAULT_DELETION_GROUP));
    }

    private static IntegrationRequestContext
    assessmentIdentity(String environment) {
        return identity(
                "support",
                environment,
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .ASSESSMENT_PURPOSE,
                Set.of(
                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                .DEFAULT_ASSESSMENT_GROUP));
    }

    private static IntegrationRequestContext
    reader(String organization) {
        return identity(
                organization,
                "staging",
                "GOVERNANCE_EVIDENCE_INGESTION",
                Set.of());
    }

    private static IntegrationRequestContext identity(
            String organization,
            String environment,
            String purpose,
            Set<String> groups) {
        return new IntegrationRequestContext(
                "tenant-a",
                organization,
                "refunds",
                environment,
                "sg",
                "WORKLOAD",
                "outcome-population-authority",
                "",
                purpose,
                "correlation-population",
                groups,
                "CONFIDENTIAL",
                "");
    }

    private static void assertProblem(
            Runnable action,
            String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(
                                failure.problem().code())
                                .isEqualTo(code));
    }
}
