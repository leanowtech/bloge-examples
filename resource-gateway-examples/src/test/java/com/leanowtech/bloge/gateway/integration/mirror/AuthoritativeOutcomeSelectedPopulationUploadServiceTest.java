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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthoritativeOutcomeSelectedPopulationUploadServiceTest {
    private static final Instant NOW =
            Instant.parse("2026-07-27T06:30:00Z");

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<Instant> databaseTime =
            new AtomicReference<>(NOW);

    private EmbeddedDatabase database;
    private AuthoritativeOutcomeSelectedPopulationUploadService
            service;
    private AuthoritativeOutcomeSelectedPopulationApplicationService
            populationService;
    private AuthoritativeOutcomeSelectedPopulationTestFixtures.Population
            fixture;
    private AuthoritativeOutcomeSelectedPopulationAdmission
            admission;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(database);
        DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository
                repository =
                new DatabaseAuthoritativeOutcomeSelectedPopulationUploadRepository(
                        new JdbcTemplate(database),
                        mapper,
                        new AuthoritativeOutcomeSelectedPopulationUploadPolicy(
                                4,
                                16 * 1024 * 1024,
                                64 * 1024 * 1024,
                                Duration.ofHours(1),
                                Duration.ofMinutes(2),
                                Duration.ofDays(1)),
                        transactions,
                        databaseTime::get);
        repository.init();
        populationService = mock(
                AuthoritativeOutcomeSelectedPopulationApplicationService
                        .class);
        service =
                new AuthoritativeOutcomeSelectedPopulationUploadService(
                        repository,
                        populationService,
                        AuthoritativeOutcomeSelectedPopulationAccessPolicy
                                .defaults(),
                        MirrorOperationObservability.noop(),
                        transactions);
        AuthoritativeOutcomeSelectedPopulationIntegrity integrity =
                new AuthoritativeOutcomeSelectedPopulationIntegrity(
                        mapper,
                        InMemoryVisualEvidenceSigner.usingClock(
                                DomainFidelityTestFixtures.CLOCK),
                        AuthoritativeOutcomeSelectedPopulationTestFixtures
                                .populationAuthority(),
                        DomainFidelityTestFixtures.CLOCK);
        fixture =
                AuthoritativeOutcomeSelectedPopulationTestFixtures
                        .signedPopulation(integrity);
        admission =
                new AuthoritativeOutcomeSelectedPopulationAdmission(
                        "",
                        new AuthoritativeOutcomeSelectedPopulationBundle(
                                "",
                                fixture.manifest(),
                                fixture.chunks(),
                                ""),
                        false);
        when(populationService.ingestPopulation(
                any(), any())).thenReturn(admission);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void completesStagedUploadOnceAndReplaysTerminalAdmission() {
        IntegrationRequestContext identity =
                selectionIdentity(
                        fixture.manifest().scope()
                                .environmentId());
        AuthoritativeOutcomeSelectedPopulationUploadRequest
                request =
                new AuthoritativeOutcomeSelectedPopulationUploadRequest(
                        "",
                        "upload-service",
                        "",
                        fixture.manifest());

        service.begin(request, identity);
        service.stageChunk(
                "upload-service",
                1,
                fixture.chunks().get(1),
                2_048,
                identity);
        service.stageChunk(
                "upload-service",
                0,
                fixture.chunks().getFirst(),
                1_024,
                identity);

        AuthoritativeOutcomeSelectedPopulationAdmission first =
                service.finalizeUpload(
                        "upload-service", identity);
        AuthoritativeOutcomeSelectedPopulationAdmission replay =
                service.finalizeUpload(
                        "upload-service", identity);

        assertThat(first).isEqualTo(admission);
        assertThat(replay).isEqualTo(admission);
        assertThat(service.find(
                "upload-service", identity)
                .status().state())
                .isEqualTo(
                        AuthoritativeOutcomeSelectedPopulationUploadStatus
                                .State.FINALIZED);
        verify(populationService, times(1))
                .ingestPopulation(any(), any());
    }

    @Test
    void rejectsWrongRoleScopeAndReservedProductionEnvironment() {
        AuthoritativeOutcomeSelectedPopulationUploadRequest
                request =
                new AuthoritativeOutcomeSelectedPopulationUploadRequest(
                        "",
                        "upload-forbidden",
                        "",
                        fixture.manifest());

        assertThatThrownBy(() ->
                service.begin(
                        request,
                        assessmentIdentity("staging")))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(value ->
                        ((IntegrationProblemException) value)
                                .problem().status())
                .isEqualTo(403);
        assertThatThrownBy(() ->
                service.begin(
                        request,
                        selectionIdentity("other")))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(value ->
                        ((IntegrationProblemException) value)
                                .problem().status())
                .isEqualTo(404);
        assertThatThrownBy(() ->
                service.begin(
                        request,
                        selectionIdentity("production")))
                .isInstanceOf(IntegrationProblemException.class)
                .extracting(value ->
                        ((IntegrationProblemException) value)
                                .problem().status())
                .isEqualTo(403);
    }

    private IntegrationRequestContext selectionIdentity(
            String environment) {
        return identity(
                environment,
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .SELECTION_PURPOSE,
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .DEFAULT_SELECTION_GROUP);
    }

    private IntegrationRequestContext assessmentIdentity(
            String environment) {
        return identity(
                environment,
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .ASSESSMENT_PURPOSE,
                AuthoritativeOutcomeSelectedPopulationAccessPolicy
                        .DEFAULT_ASSESSMENT_GROUP);
    }

    private IntegrationRequestContext identity(
            String environment,
            String purpose,
            String group) {
        CapabilitySnapshot.Scope scope =
                fixture.manifest().scope();
        return new IntegrationRequestContext(
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                environment,
                scope.region(),
                "WORKLOAD",
                "selection-authority",
                "",
                purpose,
                "correlation-upload",
                Set.of(group),
                "RESTRICTED",
                "");
    }
}
