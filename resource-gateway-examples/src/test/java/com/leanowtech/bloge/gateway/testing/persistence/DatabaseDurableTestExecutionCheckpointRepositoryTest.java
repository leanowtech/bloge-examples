package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseDurableTestExecutionCheckpointRepositoryTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final String SHA_D = "sha256:" + "d".repeat(64);

    private TestRuntimeDatabase database;
    private DatabaseDurableTestExecutionCheckpointRepository repository;
    private DurableTestExecutionCheckpointIntegrity integrity;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:durable-control-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "", 4));
        integrity = new DurableTestExecutionCheckpointIntegrity(mapper);
        repository = new DatabaseDurableTestExecutionCheckpointRepository(
                database.jdbc(), database.transactionManager(), mapper, integrity);
        repository.init();
        database.jdbc().execute("""
                CREATE TABLE rg_test_engine_state (
                    candidate_id VARCHAR(255) PRIMARY KEY,
                    engine_execution_id VARCHAR(255) NOT NULL,
                    state_version BIGINT NOT NULL
                )
                """);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void createsAndAdvancesControlAndEngineStateInOneTransaction() {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, jdbc -> jdbc.update(
                "INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                "initial", initial.engineExecutionId(), 0));

        DurableTestExecutionCheckpoint next = checkpoint(1, "checkpoint-1");
        repository.advance(next, new DurableTestExecutionCheckpointRepository.Fence(
                        "instance-a", 1, 0),
                jdbc -> jdbc.update("UPDATE rg_test_engine_state SET state_version = ? WHERE candidate_id = ?",
                        1, "initial"));

        assertThat(repository.find("tenant-a", "test", "run-a")).contains(next);
        assertThat(repository.findByEngineExecutionId("tenant-a", "test", "engine-a"))
                .contains(next);
        assertThat(repository.find("tenant-b", "test", "run-a")).isEmpty();
        assertThat(repository.find("tenant-a", "staging", "run-a")).isEmpty();
        assertThat(database.jdbc().queryForObject(
                "SELECT state_version FROM rg_test_engine_state WHERE candidate_id = 'initial'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void equivalentCreateIsIdempotentAndGlobalEngineIdentityCannotCrossTenant() {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, DurableTestExecutionCheckpointRepository.EngineStateMutation.none());
        AtomicBoolean duplicateMutationRan = new AtomicBoolean();

        assertThat(repository.create(initial, ignored -> duplicateMutationRan.set(true)))
                .isEqualTo(initial);
        assertThat(duplicateMutationRan).isFalse();

        DurableTestExecutionCheckpoint crossTenant = integrity.seal(
                new DurableTestExecutionCheckpoint(initial.schemaVersion(),
                        new DurableTestExecutionCheckpoint.Scope(
                                "tenant-b", "org-b", "project-b", "test", "runner-b"),
                        "run-b", initial.engineExecutionId(), initial.dependencies(),
                        initial.fixtureConsumptionState(), initial.executionServiceState(),
                        initial.engineState(), new DurableTestExecutionCheckpoint.Lifecycle(
                        initial.lifecycle().status(), "instance-b", 1, 0,
                        initial.lifecycle().createdAt(), initial.lifecycle().updatedAt(),
                        initial.lifecycle().leaseExpiresAt()), ""));
        assertThatThrownBy(() -> repository.create(crossTenant,
                DurableTestExecutionCheckpointRepository.EngineStateMutation.none()))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error).reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.DUPLICATE_IDENTITY);
        assertThat(repository.findByEngineExecutionId("tenant-b", "test", "engine-a")).isEmpty();
        assertThat(repository.findByEngineExecutionId("tenant-a", "test", "engine-a"))
                .contains(initial);
    }

    @Test
    void rollsBackBothSidesWhenEngineMutationOrCheckpointCasFails() {
        DurableTestExecutionCheckpoint failedCreate = checkpoint(0, "checkpoint-create-failure");
        assertThatThrownBy(() -> repository.create(failedCreate, jdbc -> {
            jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                    "failed-create", failedCreate.engineExecutionId(), 0);
            throw new IllegalStateException("injected engine-store failure");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected engine-store failure");
        assertThat(repository.find("tenant-a", "test", "run-a")).isEmpty();
        assertThat(engineCandidateCount("failed-create")).isZero();

        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, DurableTestExecutionCheckpointRepository.EngineStateMutation.none());
        DurableTestExecutionCheckpoint next = checkpoint(1, "checkpoint-1");
        assertThatThrownBy(() -> repository.advance(next,
                new DurableTestExecutionCheckpointRepository.Fence("stale-owner", 1, 0),
                jdbc -> jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                        "stale", next.engineExecutionId(), 1)))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error).reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
        assertThat(engineCandidateCount("stale")).isZero();
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(initial);
    }

    @Test
    void concurrentCheckpointCasCommitsExactlyOneEngineMutation() throws Exception {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, DurableTestExecutionCheckpointRepository.EngineStateMutation.none());
        CountDownLatch bothMutationsEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> advanceCandidate("candidate-a", bothMutationsEntered, release));
            var second = executor.submit(() -> advanceCandidate("candidate-b", bothMutationsEntered, release));
            assertThat(bothMutationsEntered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            assertThat(successes).isEqualTo(1);
        }

        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_engine_state WHERE candidate_id IN ('candidate-a', 'candidate-b')",
                Integer.class)).isEqualTo(1);
        assertThat(repository.find("tenant-a", "test", "run-a")).get()
                .extracting(value -> value.lifecycle().revision()).isEqualTo(1L);
    }

    @Test
    void failsClosedWhenStoredJsonAndIndexedIdentityDiverge() {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, DurableTestExecutionCheckpointRepository.EngineStateMutation.none());
        database.jdbc().update("""
                UPDATE rg_test_durable_execution_checkpoints
                SET plan_fingerprint = ? WHERE run_id = ?
                """, SHA_D, initial.runId());

        assertThatThrownBy(() -> repository.find("tenant-a", "test", "run-a"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void rejectsCursorRewindBeforeAnyEngineMutation() {
        DurableTestExecutionCheckpoint initial = checkpoint(0, "checkpoint-0");
        repository.create(initial, DurableTestExecutionCheckpointRepository.EngineStateMutation.none());
        DurableTestExecutionCheckpoint validNext = checkpoint(1, "checkpoint-1");
        DurableTestExecutionCheckpoint rewound = integrity.seal(
                validNext.withFixtureConsumptionState(new FixtureConsumptionStateSnapshot(
                        FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                        Map.of("rule-a", 0L), Map.of(), Map.of(), ""))
                        .withCheckpointFingerprint(""));

        assertThatThrownBy(() -> repository.advance(rewound,
                new DurableTestExecutionCheckpointRepository.Fence("instance-a", 1, 0),
                jdbc -> jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                        "rewound", rewound.engineExecutionId(), 1)))
                .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                .extracting(error -> ((DurableTestExecutionCheckpointConflictException) error).reason())
                .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.INVALID_TRANSITION);
        assertThat(engineCandidateCount("rewound")).isZero();
        assertThat(repository.find("tenant-a", "test", "run-a")).contains(initial);
    }

    private boolean advanceCandidate(String candidate, CountDownLatch entered, CountDownLatch release) {
        try {
            repository.advance(checkpoint(1, "checkpoint-" + candidate),
                    new DurableTestExecutionCheckpointRepository.Fence("instance-a", 1, 0), jdbc -> {
                        jdbc.update("INSERT INTO rg_test_engine_state VALUES (?, ?, ?)",
                                candidate, "engine-a", 1);
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("concurrency test did not release");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("concurrency test interrupted", interrupted);
                        }
                    });
            return true;
        } catch (DurableTestExecutionCheckpointConflictException expected) {
            assertThat(expected.reason())
                    .isEqualTo(DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
            return false;
        }
    }

    private int engineCandidateCount(String candidate) {
        return database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM rg_test_engine_state WHERE candidate_id = ?",
                Integer.class, candidate);
    }

    private DurableTestExecutionCheckpoint checkpoint(long revision, String checkpointRef) {
        Instant now = Instant.parse("2026-07-16T08:00:00Z").plusSeconds(revision);
        EffectiveExecutionPlan plan = new EffectiveExecutionPlan(
                EffectiveExecutionPlan.SCHEMA_VERSION, "plan-a", SHA_A,
                "GRAPH_CONTRACT_TEST", SHA_B, SHA_C, List.of(), List.of(), List.of(),
                Map.of("unmatchedExternalEffect", "DENY"), List.of());
        ExecutionServiceStateSnapshot unsealedProvider = new ExecutionServiceStateSnapshot(
                ExecutionServiceStateSnapshot.SCHEMA_VERSION, SHA_A, SHA_B, now,
                Map.of(SHA_C, revision + 1), Map.of(), List.of(), true, List.of(), SHA_D);
        ExecutionServiceStateSnapshot provider = new ExecutionServiceStateSnapshot(
                unsealedProvider.schemaVersion(), unsealedProvider.planFingerprint(),
                unsealedProvider.bindingSetFingerprint(), unsealedProvider.logicalTime(),
                unsealedProvider.randomScopeCursors(), unsealedProvider.uuidScopeCursors(),
                unsealedProvider.usages(), unsealedProvider.restorable(),
                unsealedProvider.restoreGaps(), ProtocolFingerprint.of(
                new ObjectMapper().findAndRegisterModules(), unsealedProvider.fingerprintMaterial()));
        return integrity.seal(new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", "test", "runner"),
                "run-a", "engine-a",
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        plan, new DurableTestExecutionCheckpoint.ExactFixtureRef(
                        "fixture-a", 3, SHA_C), "DENY_REAL",
                        new DurableTestExecutionCheckpoint.AuthoritySnapshot("FAIL_CLOSED", SHA_D)),
                new FixtureConsumptionStateSnapshot(
                        FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                        Map.of("rule-a", revision + 1), Map.of(SHA_A, revision + 1),
                        Map.of(SHA_B, revision + 1), ""),
                provider,
                new DurableTestExecutionCheckpoint.EngineState(
                        checkpointRef, "fetch", "NODE_BOUNDARY", revision + 1,
                        revision, ProtocolFingerprint.ofText("engine-" + checkpointRef)),
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.SUSPENDED, "instance-a", 1,
                        revision, Instant.parse("2026-07-16T08:00:00Z"), now,
                        now.plusSeconds(30)), ""));
    }
}
