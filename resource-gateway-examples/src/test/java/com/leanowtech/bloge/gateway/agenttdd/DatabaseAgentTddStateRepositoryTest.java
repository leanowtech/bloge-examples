package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.jdbc.core.ConnectionCallback;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies durable overlay revisions and exact idempotency replay across repository restarts. */
class DatabaseAgentTddStateRepositoryTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private EmbeddedDatabase database;
    private DatabaseAgentTddStateRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2).build();
        repository = new DatabaseAgentTddStateRepository(new JdbcTemplate(database), mapper);
        repository.init();
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    @Test
    void retainsOverlayRevisionAndExactReplayAcrossRestart() {
        var first = repository.save("tenant-a|test", "CASE_SET", "cases-1",
                mapper.valueToTree(Map.of("rows", 1)));
        var second = repository.save("tenant-a|test", "CASE_SET", "cases-1",
                mapper.valueToTree(Map.of("rows", 2)));
        repository.record("tenant-a|test", "rg.scenario.upsertCases", "idem-1", "sha256:req",
                mapper.valueToTree(Map.of("revision", first.revision())));

        DatabaseAgentTddStateRepository restarted = new DatabaseAgentTddStateRepository(
                new JdbcTemplate(database), mapper);
        restarted.init();

        assertThat(second.revision()).isEqualTo(2);
        assertThat(restarted.find("tenant-a|test", "CASE_SET", "cases-1"))
                .hasValueSatisfying(value -> assertThat(value.data().path("rows").asInt()).isEqualTo(2));
        assertThat(restarted.replay("tenant-a|test", "rg.scenario.upsertCases", "idem-1", "sha256:req"))
                .hasValueSatisfying(value -> assertThat(value.path("revision").asLong()).isEqualTo(1));
    }

    @Test
    void listsABoundedCrossScopeLifecycleInventoryByExactKind() {
        repository.save("tenant-b|org|project|test|sg", "CASE_SET", "cases-b",
                mapper.valueToTree(Map.of("status", "ACTIVE")));
        repository.save("tenant-a|org|project|test|sg", "CASE_SET", "cases-a",
                mapper.valueToTree(Map.of("status", "ACTIVE")));
        repository.save("tenant-0|org|project|test|sg", "EVIDENCE", "evidence-a",
                mapper.valueToTree(Map.of("status", "GREEN")));

        assertThat(repository.listByKind("CASE_SET", 1))
                .extracting(AgentTddStoredAsset::scopeKey, AgentTddStoredAsset::assetRef)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "tenant-a|org|project|test|sg", "cases-a"));
    }

    @Test
    void versionedPostgresMigrationProvidesTheRepositoryWireSchema() {
        EmbeddedDatabase migrated = new EmbeddedDatabaseBuilder().generateUniqueName(true)
                .setType(EmbeddedDatabaseType.H2)
                .addScript("db/postgresql/V20260903_020__agent_tdd_runtime.sql")
                .build();
        try {
            DatabaseAgentTddStateRepository migratedRepository = new DatabaseAgentTddStateRepository(
                    new JdbcTemplate(migrated), mapper);
            migratedRepository.init();

            assertThat(migratedRepository.save("tenant|project|test", "CASE_SET", "cases-1",
                    mapper.valueToTree(Map.of("status", "READY"))).revision()).isEqualTo(1);
        } finally {
            migrated.shutdown();
        }
    }

    @Test
    void rejectsReuseOfIdempotencyKeyForDifferentMaterial() {
        repository.record("tenant-a|test", "rg.tool.compose", "idem-1", "sha256:first",
                mapper.valueToTree(Map.of("toolRef", "tool-1")));

        assertThatThrownBy(() -> repository.replay(
                "tenant-a|test", "rg.tool.compose", "idem-1", "sha256:other"))
                .isInstanceOf(AgentTddToolException.class)
                .hasMessageContaining("different request material");
    }

    @Test
    void atomicRevisionFenceRejectsAStaleHumanApprovalWithoutDeletingCurrentAsset() {
        var reviewed = repository.save("tenant-a|test", "CASE_SET", "cases-1",
                mapper.valueToTree(Map.of("status", "PENDING")));
        var concurrent = repository.saveIfRevision("tenant-a|test", "CASE_SET", "cases-1",
                reviewed.revision(), mapper.valueToTree(Map.of("status", "CHANGED")));

        assertThatThrownBy(() -> repository.saveIfRevision(
                "tenant-a|test", "CASE_SET", "cases-1", reviewed.revision(),
                mapper.valueToTree(Map.of("status", "APPROVED"))))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("GATE_REJECTED"));
        assertThat(repository.find("tenant-a|test", "CASE_SET", "cases-1"))
                .hasValueSatisfying(current -> {
                    assertThat(current.revision()).isEqualTo(concurrent.revision());
                    assertThat(current.data().path("status").asText()).isEqualTo("CHANGED");
                });
    }

    @Test
    void executeOncePersistsTheBusinessResultAsTheExactReplay() {
        var first = repository.executeOnce("tenant-a|test", "rg.tool.publish", "publish-1", "sha256:req",
                () -> mapper.valueToTree(Map.of("publicationId", "pub-1")));
        var replay = repository.executeOnce("tenant-a|test", "rg.tool.publish", "publish-1", "sha256:req",
                () -> mapper.valueToTree(Map.of("publicationId", "must-not-run")));

        assertThat(first).isEqualTo(replay);
        assertThat(replay.path("publicationId").asText()).isEqualTo("pub-1");
    }

    @Test
    void externalExecutionReservationSurvivesRestartAndPreventsImplicitReexecution() {
        AgentTddStateRepository.ExternalExecutionReservation acquired = repository.reserveExternalExecution(
                "tenant-a|test", "ATTESTATION_EXECUTE", "green-1", "sha256:green");

        DatabaseAgentTddStateRepository restarted = new DatabaseAgentTddStateRepository(
                new JdbcTemplate(database), mapper);
        restarted.init();
        AgentTddStateRepository.ExternalExecutionReservation recovered = restarted.reserveExternalExecution(
                "tenant-a|test", "ATTESTATION_EXECUTE", "green-1", "sha256:green");

        assertThat(acquired.status())
                .isEqualTo(AgentTddStateRepository.ExternalExecutionStatus.ACQUIRED);
        assertThat(recovered.status())
                .isEqualTo(AgentTddStateRepository.ExternalExecutionStatus.IN_PROGRESS);
        JsonNode completed = restarted.completeExternalExecution(
                "tenant-a|test", "ATTESTATION_EXECUTE", "green-1", "sha256:green",
                mapper.valueToTree(Map.of("status", "ATTESTED")));
        AgentTddStateRepository.ExternalExecutionReservation replay = restarted.reserveExternalExecution(
                "tenant-a|test", "ATTESTATION_EXECUTE", "green-1", "sha256:green");
        assertThat(completed.path("status").asText()).isEqualTo("ATTESTED");
        assertThat(replay.status())
                .isEqualTo(AgentTddStateRepository.ExternalExecutionStatus.COMPLETED);
        assertThat(replay.response().path("status").asText()).isEqualTo("ATTESTED");
    }

    @Test
    void postgresReservationUsesNonThrowingConflictHandlingSoReplayCanContinue() {
        JdbcTemplate postgresJdbc = mock(JdbcTemplate.class);
        when(postgresJdbc.execute(any(ConnectionCallback.class))).thenReturn("PostgreSQL");
        when(postgresJdbc.queryForObject(any(String.class), any(Class.class), any(), any(), any()))
                .thenReturn(0L);
        when(postgresJdbc.update(contains("ON CONFLICT"), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        DatabaseAgentTddStateRepository postgresRepository =
                new DatabaseAgentTddStateRepository(postgresJdbc, mapper);
        postgresRepository.init();

        assertThat(postgresRepository.reserveIdempotency(
                "tenant|project|test", "rg.tool.publish", "publish-1", "sha256:req"))
                .isFalse();
        verify(postgresJdbc).update(contains("ON CONFLICT (scope_key, operation, idempotency_key)"),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void executeAtomicallyRollsBackEveryAssetWriteWhenTheUnitFails() {
        AgentTddStateRepository transactional = transactionalRepository();

        assertThatThrownBy(() -> transactional.executeAtomically(() -> {
            transactional.save("tenant-a|test", "CASE_SET", "cases-rollback",
                    mapper.valueToTree(Map.of("qualityState", "READY")));
            transactional.save("tenant-a|test", "EVIDENCE", "evidence-rollback",
                    mapper.valueToTree(Map.of("side", "GREEN")));
            throw new IllegalStateException("commit fence failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(repository.find("tenant-a|test", "CASE_SET", "cases-rollback")).isEmpty();
        assertThat(repository.find("tenant-a|test", "EVIDENCE", "evidence-rollback")).isEmpty();
    }

    @Test
    void revisionLockBlocksConcurrentCaseEditUntilEvidenceUnitCompletes() throws Exception {
        AgentTddStateRepository transactional = transactionalRepository();
        AgentTddStoredAsset cases = transactional.save("tenant-a|test", "CASE_SET", "cases-locked",
                mapper.valueToTree(Map.of("qualityState", "READY")));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<JsonNode> evidence = executor.submit(
                    () -> transactional.executeAtomically(() -> {
                        transactional.lockRevision(
                                "tenant-a|test", "CASE_SET", "cases-locked", cases.revision());
                        locked.countDown();
                        try {
                            if (!release.await(2, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test did not release revision lock");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                        return mapper.valueToTree(Map.of("evidence", "committed"));
                    }));
            assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();
            var concurrentEdit = executor.submit(() -> transactional.saveIfRevision(
                    "tenant-a|test", "CASE_SET", "cases-locked", cases.revision(),
                    mapper.valueToTree(Map.of("qualityState", "STALE"))));

            assertThatThrownBy(() -> concurrentEdit.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(evidence.get(2, TimeUnit.SECONDS).path("evidence").asText()).isEqualTo("committed");
            assertThat(concurrentEdit.get(2, TimeUnit.SECONDS).data().path("qualityState").asText())
                    .isEqualTo("STALE");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private AgentTddStateRepository transactionalRepository() {
        ProxyFactory factory = new ProxyFactory();
        factory.setInterfaces(AgentTddStateRepository.class);
        factory.setTarget(repository);
        factory.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(database),
                new AnnotationTransactionAttributeSource()));
        return (AgentTddStateRepository) factory.getProxy();
    }
}
