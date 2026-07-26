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
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthoritativeOutcomeInboxServiceTest {
    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();
    private final AtomicBoolean authorityAvailable =
            new AtomicBoolean(true);
    private final AtomicInteger authorityCalls =
            new AtomicInteger();
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private AuthoritativeOutcomeObservationIntegrity integrity;
    private DatabaseAuthoritativeOutcomeInboxRepository repository;
    private AuthoritativeOutcomeInboxService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new JdbcTemplate(database);
        transactions = new DataSourceTransactionManager(database);
        Clock clock = Clock.fixed(
                DomainFidelityTestFixtures.NOW,
                ZoneOffset.UTC);
        integrity = new AuthoritativeOutcomeObservationIntegrity(
                mapper,
                InMemoryVisualEvidenceSigner.usingClock(clock),
                authority(),
                clock);
        repository =
                new DatabaseAuthoritativeOutcomeInboxRepository(
                        jdbc,
                        mapper,
                        integrity,
                        transactions,
                        () -> DomainFidelityTestFixtures.NOW);
        repository.init();
        service = service(MirrorOperationObservability.noop());
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void signsOnceAndRecoversUnsignedConnectorReplayWithoutTransactionIo() {
        AuthoritativeOutcomeObservation unsigned =
                AuthoritativeOutcomeTestFixtures.pending();
        AuthoritativeOutcomeObservationAdmissionRequest request =
                request(unsigned);

        AuthoritativeOutcomeInboxAdmission first =
                service.ingest(request, connector("staging"));
        AuthoritativeOutcomeInboxAdmission replay =
                service.ingest(request, connector("staging"));

        assertThat(first.idempotentReplay()).isFalse();
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(replay.observation())
                .isEqualTo(first.observation());
        assertThat(first.observation()
                .observationSeal().signed()).isTrue();
        assertThat(authorityCalls).hasValue(3);
    }

    @Test
    void readsExactLatestHeadAndChainedLifecycleInExactScope() {
        AuthoritativeOutcomeInboxAdmission admitted =
                service.ingest(
                        request(
                                AuthoritativeOutcomeTestFixtures
                                        .pending()),
                        connector("staging"));
        IntegrationRequestContext reader = reader("support");

        assertThat(service.findObservation(
                admitted.observation().observationId(),
                1,
                reader)).isEqualTo(admitted.observation());
        assertThat(service.findLatestObservation(
                admitted.observation().observationId(),
                reader)).isEqualTo(admitted.observation());
        assertThat(service.findEntry(
                admitted.observation().observationId(),
                reader)).isEqualTo(admitted.entry());
        AuthoritativeOutcomeInboxLifecyclePage page =
                service.lifecycle(
                        admitted.observation().observationId(),
                        0,
                        100,
                        reader);
        assertThat(page.events())
                .extracting(
                        AuthoritativeOutcomeInboxLifecycleEvent
                                ::transition)
                .containsExactly(
                        AuthoritativeOutcomeInboxLifecycleEvent
                                .Transition.OBSERVATION_APPENDED);
        assertThat(page.nextOrdinal()).isEqualTo(1);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void rejectsWrongConnectorScopeAndProductionAndFailsClosedOnAuthorityOutage() {
        AuthoritativeOutcomeObservation unsigned =
                AuthoritativeOutcomeTestFixtures.pending();
        IntegrationRequestContext wrongGroup =
                identity(
                        "support",
                        "staging",
                        AuthoritativeOutcomeInboxAccessPolicy
                                .INGESTION_PURPOSE,
                        Set.of("OTHER_CONNECTOR"));

        assertProblem(
                () -> service.ingest(
                        request(unsigned), wrongGroup),
                "RG.MIRROR.OUTCOME.CONNECTOR_FORBIDDEN");
        assertProblem(
                () -> service.ingest(
                        request(unsigned), connector("production")),
                "RG.MIRROR.OUTCOME.ENVIRONMENT_FORBIDDEN");
        AuthoritativeOutcomeInboxAdmission admitted =
                service.ingest(
                        request(unsigned), connector("staging"));
        assertProblem(
                () -> service.findEntry(
                        admitted.observation().observationId(),
                        reader("other")),
                "RG.MIRROR.OUTCOME.OBSERVATION_NOT_FOUND");

        authorityAvailable.set(false);
        assertProblem(
                () -> service.findObservation(
                        admitted.observation().observationId(),
                        1,
                        reader("support")),
                "RG.MIRROR.OUTCOME.UNAVAILABLE");
    }

    @Test
    void auditFailureRollsBackObservationHeadAndLifecycle() {
        MirrorOperationAuditRepository failing =
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
        AuthoritativeOutcomeInboxService audited =
                service(new MirrorOperationObservability(
                        failing,
                        MirrorOperationTelemetry.noop(),
                        () -> 1L));

        assertProblem(
                () -> audited.ingest(
                        request(
                                AuthoritativeOutcomeTestFixtures
                                        .pending()),
                        connector("staging")),
                "RG.MIRROR.OPERATION_AUDIT_UNAVAILABLE");
        assertThat(count("mirror_outcome_observations")).isZero();
        assertThat(count("mirror_outcome_inbox_heads")).isZero();
        assertThat(count("mirror_outcome_inbox_lifecycle")).isZero();
    }

    private AuthoritativeOutcomeInboxService service(
            MirrorOperationObservability observability) {
        return new AuthoritativeOutcomeInboxService(
                repository,
                integrity,
                AuthoritativeOutcomeInboxAccessPolicy.defaults(),
                mapper,
                observability,
                transactions);
    }

    private AuthoritativeOutcomeAuthorityVerifier authority() {
        return new AuthoritativeOutcomeAuthorityVerifier() {
            @Override
            public boolean available() {
                return authorityAvailable.get();
            }

            @Override
            public void verify(
                    AuthoritativeOutcomeObservation observation) {
                authorityCalls.incrementAndGet();
                assertThat(
                        TransactionSynchronizationManager
                                .isActualTransactionActive())
                        .as("external authority I/O must precede database transaction")
                        .isFalse();
                if (!authorityAvailable.get()) {
                    throw new IllegalStateException(
                            "authority unavailable");
                }
            }
        };
    }

    private static AuthoritativeOutcomeObservationAdmissionRequest
    request(AuthoritativeOutcomeObservation observation) {
        return new AuthoritativeOutcomeObservationAdmissionRequest(
                "",
                "",
                observation);
    }

    private static IntegrationRequestContext connector(
            String environmentId) {
        return identity(
                "support",
                environmentId,
                AuthoritativeOutcomeInboxAccessPolicy
                        .INGESTION_PURPOSE,
                Set.of(
                        AuthoritativeOutcomeInboxAccessPolicy
                                .DEFAULT_CONNECTOR_GROUP));
    }

    private static IntegrationRequestContext reader(
            String organizationId) {
        return identity(
                organizationId,
                "staging",
                "GOVERNANCE_EVIDENCE_INGESTION",
                Set.of());
    }

    private static IntegrationRequestContext identity(
            String organizationId,
            String environmentId,
            String purpose,
            Set<String> groups) {
        return new IntegrationRequestContext(
                "tenant-a",
                organizationId,
                "refunds",
                environmentId,
                "sg",
                "WORKLOAD",
                "outcome-connector",
                "",
                purpose,
                "correlation-outcome",
                groups,
                "CONFIDENTIAL",
                "");
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table,
                Integer.class);
    }

    private static void assertProblem(
            Runnable operation,
            String code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        IntegrationProblemException.class,
                        failure -> assertThat(
                                failure.problem().code())
                                .isEqualTo(code));
    }
}
