package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DurableTestExecutionQueryServiceTest {

    private DurableTestExecutionCheckpointRepository checkpoints;
    private DurableTestExecutionQueryService service;

    @BeforeEach
    void setUp() {
        checkpoints = mock(DurableTestExecutionCheckpointRepository.class);
        service = new DurableTestExecutionQueryService(checkpoints);
    }

    @Test
    void projectsOnlyPayloadFreeLifecycleAndContentIdentities() {
        DurableTestExecutionCheckpoint checkpoint = checkpoint(false);
        when(checkpoints.find("tenant-a", "test", "run-a"))
                .thenReturn(Optional.of(checkpoint));

        DurableTestExecutionQueryResponse response = service.find("run-a", identity());

        assertThat(response.schemaVersion())
                .isEqualTo(DurableTestExecutionQueryResponse.SCHEMA_VERSION);
        assertThat(response.runId()).isEqualTo("run-a");
        assertThat(response.engineExecutionId()).isEqualTo("engine-a");
        assertThat(response.status()).isEqualTo("SUSPENDED");
        assertThat(response.fence().ownerId()).isEqualTo("owner-a");
        assertThat(response.target().id()).isEqualTo("graph-a");
        assertThat(response.fixture().fixtureBundleId()).isEqualTo("fixture-a");
        assertThat(response.engineBoundary().boundaryType()).isEqualTo("SUSPEND");
        assertThat(response.recoverable()).isTrue();
        assertThat(response.migrationRequired()).isFalse();
        assertThat(response.planFingerprint()).isEqualTo(checkpoint.dependencies()
                .plan().planFingerprint());
        assertThat(response.executionServiceStateFingerprint()).isEqualTo(checkpoint
                .executionServiceState().snapshotFingerprint());
        assertThat(response.fixtureConsumptionStateFingerprint()).isEqualTo(checkpoint
                .fixtureConsumptionState().stateFingerprint());
        assertThat(response.engineBoundary().closureFingerprint()).isEqualTo(checkpoint
                .engineState().closureFingerprint());
    }

    @Test
    void exposesLegacyMigrationRequirementWithoutInventingATarget() {
        DurableTestExecutionCheckpoint checkpoint = checkpoint(true);
        when(checkpoints.find("tenant-a", "test", "run-a"))
                .thenReturn(Optional.of(checkpoint));

        DurableTestExecutionQueryResponse response = service.find("run-a", identity());

        assertThat(response.target()).isNull();
        assertThat(response.recoverable()).isFalse();
        assertThat(response.migrationRequired()).isTrue();
    }

    @Test
    void hidesMissingAndCrossOrganizationExecutionsBehindTheSameNotFoundProblem() {
        when(checkpoints.find("tenant-a", "test", "missing"))
                .thenReturn(Optional.empty());
        DurableTestExecutionCheckpoint crossOrganization = checkpoint(false);
        when(checkpoints.find("tenant-a", "test", "run-a"))
                .thenReturn(Optional.of(crossOrganization));

        assertProblem(() -> service.find("missing", identity()), 404,
                "RG.TEST.DURABLE_EXECUTION_NOT_FOUND");
        assertProblem(() -> service.find("run-a", identity("org-b", "project-a")), 404,
                "RG.TEST.DURABLE_EXECUTION_NOT_FOUND");
        assertProblem(() -> service.find("run-a", identity("org-a", "project-b")), 404,
                "RG.TEST.DURABLE_EXECUTION_NOT_FOUND");
    }

    @Test
    void rejectsInvalidRunIdentityAndProductionBeforeStoreAccess() {
        assertProblem(() -> service.find("../run", identity()), 400,
                "RG.TEST.DURABLE_RUN_ID_INVALID");
        assertProblem(() -> service.find("run-a", identity("org-a", "project-a", "production")),
                403, "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN");

        verifyNoInteractions(checkpoints);
    }

    @Test
    void mapsStoreFailureToStableServiceUnavailableProblem() {
        when(checkpoints.find("tenant-a", "test", "run-a"))
                .thenThrow(new IllegalStateException("database details must not escape"));

        assertProblem(() -> service.find("run-a", identity()), 503,
                "RG.TEST.DURABLE_STORE_UNAVAILABLE");
    }

    @Test
    void readsIntegrityVerifiedDatabaseStateAndFailsClosedOnProjectionTampering() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = new TestRuntimeDatabase(
                new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:durable-query-" + System.nanoTime()
                                + ";DB_CLOSE_DELAY=-1", "sa", "", 2))) {
            DurableTestExecutionCheckpointIntegrity integrity =
                    new DurableTestExecutionCheckpointIntegrity(mapper);
            DatabaseDurableTestExecutionCheckpointRepository repository =
                    new DatabaseDurableTestExecutionCheckpointRepository(
                            database.jdbc(), database.transactionManager(), mapper, integrity);
            repository.init();
            DurableTestExecutionCheckpoint stored = persistentCheckpoint(mapper, integrity);
            repository.create(stored, noop(stored));
            DurableTestExecutionQueryService databaseService =
                    new DurableTestExecutionQueryService(repository);

            assertThat(databaseService.find("run-a", identity()).checkpointFingerprint())
                    .isEqualTo(stored.checkpointFingerprint());

            database.jdbc().update("""
                    UPDATE rg_test_durable_execution_checkpoints
                    SET target_id = 'tampered-target'
                    WHERE run_id = 'run-a'
                    """);
            assertProblem(() -> databaseService.find("run-a", identity()), 503,
                    "RG.TEST.DURABLE_STORE_UNAVAILABLE");
        }
    }

    private static void assertProblem(Runnable action, int status, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(status);
                    assertThat(failure.problem().code()).isEqualTo(code);
                    assertThat(failure.problem().title())
                            .doesNotContain("database details")
                            .doesNotContain("org-b")
                            .doesNotContain("project-b");
                });
    }

    private static IntegrationRequestContext identity() {
        return identity("org-a", "project-a");
    }

    private static IntegrationRequestContext identity(String organizationId, String projectId) {
        return identity(organizationId, projectId, "test");
    }

    private static IntegrationRequestContext identity(
            String organizationId, String projectId, String environmentId) {
        return new IntegrationRequestContext(
                "tenant-a", organizationId, projectId, environmentId, "local",
                "WORKLOAD", "runner", "", "TEST_EXECUTION", "correlation-a",
                Set.of("quality"), "CONFIDENTIAL", "");
    }

    private static DurableTestExecutionCheckpoint checkpoint(boolean legacy) {
        DurableTestExecutionCheckpoint checkpoint = mock(DurableTestExecutionCheckpoint.class);
        DurableTestExecutionCheckpoint.Scope scope = new DurableTestExecutionCheckpoint.Scope(
                "tenant-a", "org-a", "project-a", "test", "runner");
        DurableTestExecutionCheckpoint.ControlDependencies dependencies =
                mock(DurableTestExecutionCheckpoint.ControlDependencies.class);
        com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan plan =
                mock(com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan.class);
        DurableTestExecutionCheckpoint.ExactFixtureRef fixture =
                new DurableTestExecutionCheckpoint.ExactFixtureRef(
                        "fixture-a", 3, sha('f'));
        DurableTestExecutionCheckpoint.ExecutionTargetRef target = legacy ? null
                : new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                        "GRAPH", "graph-a", sha('a'));
        var fixtureState = mock(com.leanowtech.bloge.gateway.testing.domain
                .FixtureConsumptionStateSnapshot.class);
        var serviceState = mock(com.leanowtech.bloge.gateway.testing.domain
                .ExecutionServiceStateSnapshot.class);

        when(checkpoint.schemaVersion()).thenReturn(legacy
                ? DurableTestExecutionCheckpoint.SCHEMA_VERSION_V1
                : DurableTestExecutionCheckpoint.SCHEMA_VERSION);
        when(checkpoint.scope()).thenReturn(scope);
        when(checkpoint.runId()).thenReturn("run-a");
        when(checkpoint.engineExecutionId()).thenReturn("engine-a");
        when(checkpoint.dependencies()).thenReturn(dependencies);
        when(dependencies.plan()).thenReturn(plan);
        when(dependencies.fixture()).thenReturn(fixture);
        when(dependencies.sideEffectPolicy()).thenReturn("DENY_REAL");
        when(dependencies.target()).thenReturn(target);
        when(plan.authorizedPurpose()).thenReturn("GRAPH_CONTRACT_TEST");
        when(plan.planFingerprint()).thenReturn(sha('b'));
        when(checkpoint.fixtureConsumptionState()).thenReturn(fixtureState);
        when(fixtureState.stateFingerprint()).thenReturn(sha('c'));
        when(checkpoint.executionServiceState()).thenReturn(serviceState);
        when(serviceState.snapshotFingerprint()).thenReturn(sha('d'));
        when(serviceState.restorable()).thenReturn(true);
        when(checkpoint.engineState()).thenReturn(new DurableTestExecutionCheckpoint.EngineState(
                "checkpoint-a", "approval", "SUSPEND", 4, 7, sha('e')));
        when(checkpoint.lifecycle()).thenReturn(new DurableTestExecutionCheckpoint.Lifecycle(
                DurableTestExecutionCheckpoint.Status.SUSPENDED, "owner-a", 2, 5,
                Instant.parse("2026-07-17T00:00:00Z"),
                Instant.parse("2026-07-17T00:01:00Z"),
                Instant.parse("2026-07-17T00:03:00Z")));
        when(checkpoint.checkpointFingerprint()).thenReturn(sha('a'));
        return checkpoint;
    }

    private static String sha(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static DurableTestExecutionCheckpoint persistentCheckpoint(
            ObjectMapper mapper, DurableTestExecutionCheckpointIntegrity integrity) {
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        EffectiveExecutionPlan plan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION, "plan-a", sha('b'),
                "GRAPH_CONTRACT_TEST", sha('a'), sha('f'),
                List.of(), List.of(), List.of(),
                Map.of("unmatchedExternalEffect", "DENY"), List.of());
        ExecutionServiceStateSnapshot unsealedProvider = new ExecutionServiceStateSnapshot(
                ExecutionServiceStateSnapshot.SCHEMA_VERSION,
                sha('b'), sha('d'), now, Map.of(), Map.of(), List.of(),
                true, List.of(), sha('a'));
        ExecutionServiceStateSnapshot provider = new ExecutionServiceStateSnapshot(
                unsealedProvider.schemaVersion(), unsealedProvider.planFingerprint(),
                unsealedProvider.bindingSetFingerprint(), unsealedProvider.logicalTime(),
                unsealedProvider.randomScopeCursors(), unsealedProvider.uuidScopeCursors(),
                unsealedProvider.usages(), unsealedProvider.restorable(),
                unsealedProvider.restoreGaps(),
                ProtocolFingerprint.of(mapper, unsealedProvider.fingerprintMaterial()));
        return integrity.seal(new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", "test", "runner"),
                "run-a", "engine-a",
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        plan,
                        new DurableTestExecutionCheckpoint.ExactFixtureRef(
                                "fixture-a", 3, sha('f')),
                        "DENY_REAL",
                        new DurableTestExecutionCheckpoint.AuthoritySnapshot(
                                "FAIL_CLOSED", sha('e')),
                        new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                                "GRAPH", "graph-a", sha('a'))),
                new FixtureConsumptionStateSnapshot(
                        FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                        Map.of(), Map.of(), Map.of(), ""),
                provider,
                new DurableTestExecutionCheckpoint.EngineState(
                        "checkpoint-a", "approval", "SUSPEND", 1, 1, sha('c')),
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.SUSPENDED,
                        "owner-a", 1, 0, now, now, now.plusSeconds(120)), ""));
    }

    private static DurableTestExecutionCheckpointRepository.BoundEngineStateMutation noop(
            DurableTestExecutionCheckpoint checkpoint) {
        return new DurableTestExecutionCheckpointRepository.BoundEngineStateMutation() {
            @Override
            public String engineExecutionId() {
                return checkpoint.engineExecutionId();
            }

            @Override
            public DurableTestExecutionCheckpoint.EngineState engineState() {
                return checkpoint.engineState();
            }

            @Override
            public void apply(JdbcTemplate jdbc) {
                // The query test needs only the atomic control row, not concrete BLOGE state.
            }
        };
    }
}
