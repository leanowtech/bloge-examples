package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReadOnlyShadowJobServiceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicReference<java.time.Instant> now =
            new AtomicReference<>(
                    ReadOnlyShadowJobTestFixtures.NOW);
    private EmbeddedDatabase database;
    private ReadOnlyShadowJobService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        JdbcTemplate jdbc =
                new JdbcTemplate(database);
        DatabaseReadOnlyShadowJobRepository
                repository =
                new DatabaseReadOnlyShadowJobRepository(
                        jdbc,
                        mapper,
                        ReadOnlyShadowJobTestFixtures
                                .integrity(mapper),
                        new DataSourceTransactionManager(
                                database),
                        now::get);
        repository.init();
        service = new ReadOnlyShadowJobService(
                repository,
                ReadOnlyShadowJobTestFixtures.POLICY,
                MirrorOperationObservability.noop());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void admitsAndReadsTheIndependentVerificationClosure() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-service", 11);
        IntegrationRequestContext execution =
                ReadOnlyShadowJobTestFixtures.identity(
                        "support",
                        ReadOnlyShadowJobService
                                .EXECUTION_PURPOSE);

        ReadOnlyShadowJobRepository.Submission admitted =
                service.submit(
                        request, execution);
        IntegrationRequestContext evidence =
                ReadOnlyShadowJobTestFixtures.identity(
                        "support",
                        ReadOnlyShadowJobService
                                .EVIDENCE_PURPOSE);

        assertThat(admitted.idempotentReplay())
                .isFalse();
        assertThat(service.submit(
                request, execution).idempotentReplay())
                .isTrue();
        assertThat(service.find(
                admitted.job().jobId(), evidence))
                .isEqualTo(admitted.job());
        assertThat(service.findRequest(
                admitted.job().jobId(), evidence))
                .isEqualTo(request);
        ReadOnlyShadowJobLifecyclePage page =
                service.lifecycle(
                        admitted.job().jobId(),
                        0,
                        10,
                        evidence);
        assertThat(page.events())
                .extracting(
                        ReadOnlyShadowJobLifecycleEvent
                                ::transition)
                .containsExactly(
                        ReadOnlyShadowJobLifecycleEvent
                                .Transition.ADMITTED);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextSequence())
                .isPositive();
    }

    @Test
    void rejectsCrossScopeProductionAndWrongPurposeWithoutLookupLeakage() {
        ReadOnlyShadowJobRequest request =
                ReadOnlyShadowJobTestFixtures.request(
                        "shadow-scope", 12);
        IntegrationRequestContext otherTenant =
                ReadOnlyShadowJobTestFixtures.identity(
                        "other",
                        ReadOnlyShadowJobService
                                .EXECUTION_PURPOSE);
        assertCode(
                () -> service.submit(
                        request, otherTenant),
                "RG.MIRROR.SHADOW.SCOPE_NOT_FOUND");

        IntegrationRequestContext wrongPurpose =
                copy(
                        ReadOnlyShadowJobTestFixtures.identity(
                                "support",
                                "MIRROR_REHEARSAL"),
                        "staging",
                        "MIRROR_REHEARSAL");
        assertCode(
                () -> service.submit(
                        request, wrongPurpose),
                "RG.MIRROR.SHADOW.PURPOSE_FORBIDDEN");

        IntegrationRequestContext production =
                copy(
                        ReadOnlyShadowJobTestFixtures.identity(
                                "support",
                                ReadOnlyShadowJobService
                                        .EXECUTION_PURPOSE),
                        "production",
                        ReadOnlyShadowJobService
                                .EXECUTION_PURPOSE);
        assertCode(
                () -> service.submit(
                        request, production),
                "RG.MIRROR.SHADOW.ENVIRONMENT_FORBIDDEN");

        IntegrationRequestContext namedNonProduction =
                copy(
                        ReadOnlyShadowJobTestFixtures.identity(
                                "support",
                                ReadOnlyShadowJobService
                                        .EVIDENCE_PURPOSE),
                        "shadow-staging",
                        ReadOnlyShadowJobService
                                .EVIDENCE_PURPOSE);
        assertCode(
                () -> service.find(
                        "shadow-missing",
                        namedNonProduction),
                "RG.MIRROR.SHADOW.JOB_NOT_FOUND");
    }

    private static IntegrationRequestContext copy(
            IntegrationRequestContext source,
            String environmentId,
            String purpose) {
        return new IntegrationRequestContext(
                source.tenantId(),
                source.organizationId(),
                source.projectId(),
                environmentId,
                source.region(),
                source.actorType(),
                source.actorId(),
                source.delegatedBy(),
                purpose,
                source.correlationId(),
                Set.copyOf(source.groups()),
                source.clearance(),
                source.delegationGrantId());
    }

    private static void assertCode(
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
