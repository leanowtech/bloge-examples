package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.engine.ExecutionOptions;
import com.leanowtech.bloge.core.engine.ExecutionServices;
import com.leanowtech.bloge.core.exception.DurabilityException;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.runtime.checkpoint.CheckpointType;
import com.leanowtech.bloge.core.runtime.checkpoint.ExecutionCheckpoint;
import com.leanowtech.bloge.core.runtime.identity.ExecutionIdentity;
import com.leanowtech.bloge.core.runtime.identity.ExecutionType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.durable.codec.JacksonCheckpointCodec;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointConflictException;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpointIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.IndependentDurableTestEngineFactory;
import com.leanowtech.bloge.gateway.testing.runtime.InvocationRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StagedBlogeExecutionCheckpointStoreTest {

    private static final String SHA_A = "sha256:" + "a".repeat(64);
    private static final String SHA_B = "sha256:" + "b".repeat(64);
    private static final String SHA_C = "sha256:" + "c".repeat(64);
    private static final String SHA_D = "sha256:" + "d".repeat(64);

    private ObjectMapper objectMapper;
    private TestRuntimeDatabase database;
    private DurableTestExecutionCheckpointIntegrity integrity;
    private DatabaseDurableTestExecutionCheckpointRepository repository;
    private StagedBlogeExecutionCheckpointStore store;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:staged-bloge-checkpoint-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "sa", "", 6));
        integrity = new DurableTestExecutionCheckpointIntegrity(objectMapper);
        repository = new DatabaseDurableTestExecutionCheckpointRepository(
                database.jdbc(), database.transactionManager(), objectMapper, integrity);
        repository.init();
        store = new StagedBlogeExecutionCheckpointStore(database.jdbc(), objectMapper);
        store.init();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void rejectsWritesOutsideStageAndDiscardsUncommittedStage() {
        ExecutionCheckpoint checkpoint = checkpoint("engine-a", "only", null, "one", 0);

        assertThatThrownBy(() -> store.save(checkpoint))
                .isInstanceOf(DurabilityException.class)
                .hasMessageContaining("active composite checkpoint stage");

        try (StagedBlogeExecutionCheckpointStore.Stage ignored = store.begin("engine-a")) {
            store.save(checkpoint);
            assertThat(store.loadAll("engine-a")).containsExactly(checkpoint);
        }

        assertThat(store.loadAll("engine-a")).isEmpty();
    }

    @Test
    void commitsStagedBlogeRowsWithTheControlCheckpoint() {
        ExecutionCheckpoint checkpoint = checkpoint("engine-a", "only", null, "one", 0);
        DurableTestExecutionCheckpoint storedControl;

        try (StagedBlogeExecutionCheckpointStore.Stage stage = store.begin("engine-a")) {
            store.save(checkpoint);
            StagedBlogeExecutionCheckpointStore.PreparedMutation mutation = stage.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);
            DurableTestExecutionCheckpoint control = control(0, mutation.engineState());

            storedControl = repository.create(control, mutation);

            assertThat(store.load("engine-a", CheckpointType.NODE_OUTPUT, "only"))
                    .contains(checkpoint);
            assertThat(storedControl.engineState().closureFingerprint())
                    .isEqualTo(mutation.engineState().closureFingerprint());
        }

        assertThat(repository.find("tenant-a", "test", "run-a")).contains(storedControl);
        assertThat(store.loadAll("engine-a")).containsExactly(checkpoint);
    }

    @Test
    void rollsBackBlogeRowsWhenTheCompositeMutationFails() {
        ExecutionCheckpoint checkpoint = checkpoint("engine-a", "only", null, "one", 0);

        try (StagedBlogeExecutionCheckpointStore.Stage stage = store.begin("engine-a")) {
            store.save(checkpoint);
            StagedBlogeExecutionCheckpointStore.PreparedMutation mutation = stage.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);
            DurableTestExecutionCheckpoint control = control(0, mutation.engineState());

            assertThatThrownBy(() -> repository.create(control, boundMutation(mutation, jdbc -> {
                mutation.apply(jdbc);
                throw new IllegalStateException("failure after BLOGE checkpoint writes");
            }))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("failure after BLOGE checkpoint writes");
        }

        assertThat(repository.find("tenant-a", "test", "run-a")).isEmpty();
        assertThat(store.loadAll("engine-a")).isEmpty();
    }

    @Test
    void freezesPreparedMutationAndRejectsCrossExecutionWrites() {
        try (StagedBlogeExecutionCheckpointStore.Stage stage = store.begin("engine-a")) {
            store.save(checkpoint("engine-a", "only", null, "one", 0));
            StagedBlogeExecutionCheckpointStore.PreparedMutation mutation = stage.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);

            assertThat(mutation.engineState().closureFingerprint()).startsWith("sha256:");
            assertThatThrownBy(() -> store.save(
                    checkpoint("engine-a", "late", null, "late", 1)))
                    .isInstanceOf(DurabilityException.class)
                    .hasMessageContaining("already prepared");
            assertThatThrownBy(() -> store.save(
                    checkpoint("engine-b", "foreign", null, "foreign", 1)))
                    .isInstanceOf(DurabilityException.class)
                    .hasMessageContaining("active composite checkpoint stage");
        }
    }

    @Test
    void rejectsPreparedMutationBoundToDifferentControlEngineState() {
        try (StagedBlogeExecutionCheckpointStore.Stage stage = store.begin("engine-a")) {
            store.save(checkpoint("engine-a", "only", null, "one", 0));
            StagedBlogeExecutionCheckpointStore.PreparedMutation mutation = stage.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);
            DurableTestExecutionCheckpoint.EngineState mismatched =
                    new DurableTestExecutionCheckpoint.EngineState(
                            "checkpoint-other", "only", "NODE_BOUNDARY", 1, 0,
                            mutation.engineState().closureFingerprint());

            assertThatThrownBy(() -> repository.create(control(0, mismatched), mutation))
                    .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                    .hasMessageContaining("engine-state mutation");
        }

        assertThat(repository.find("tenant-a", "test", "run-a")).isEmpty();
        assertThat(store.loadAll("engine-a")).isEmpty();
    }

    @Test
    void rejectsPreparedMutationBoundToDifferentEngineExecution() {
        try (StagedBlogeExecutionCheckpointStore.Stage stage = store.begin("engine-a")) {
            store.save(checkpoint("engine-a", "only", null, "one", 0));
            StagedBlogeExecutionCheckpointStore.PreparedMutation mutation = stage.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);

            assertThatThrownBy(() -> repository.create(
                    control(0, "engine-b", mutation.engineState()), mutation))
                    .isInstanceOf(DurableTestExecutionCheckpointConflictException.class)
                    .hasMessageContaining("identity or engine state");
        }

        assertThat(repository.find("tenant-a", "test", "run-a")).isEmpty();
        assertThat(store.loadAll("engine-a")).isEmpty();
    }

    @Test
    void replaysTheSamePreparedMutationAfterTransactionRollback() {
        ExecutionCheckpoint checkpoint = checkpoint("engine-a", "only", null, "one", 0);

        try (StagedBlogeExecutionCheckpointStore.Stage stage = store.begin("engine-a")) {
            store.save(checkpoint);
            StagedBlogeExecutionCheckpointStore.PreparedMutation mutation = stage.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);
            DurableTestExecutionCheckpoint control = control(0, mutation.engineState());

            assertThatThrownBy(() -> repository.create(control, boundMutation(mutation, jdbc -> {
                mutation.apply(jdbc);
                throw new IllegalStateException("transient transaction failure");
            }))).isInstanceOf(IllegalStateException.class);
            assertThat(store.loadPage(0, 10)).isEmpty();

            assertThat(repository.create(control, mutation)).isEqualTo(control);
        }

        assertThat(store.loadAll("engine-a")).containsExactly(checkpoint);
    }

    @Test
    void rejectsTargetDatasourceWhenOnlyAnUnrelatedTransactionIsActive() {
        try (StagedBlogeExecutionCheckpointStore.Stage stage = store.begin("engine-a");
             TestRuntimeDatabase unrelated = new TestRuntimeDatabase(
                     new TestRuntimeDatabase.Settings(
                             "jdbc:h2:mem:unrelated-checkpoint-" + System.nanoTime()
                                     + ";DB_CLOSE_DELAY=-1", "sa", "", 2))) {
            store.save(checkpoint("engine-a", "only", null, "one", 0));
            StagedBlogeExecutionCheckpointStore.PreparedMutation mutation = stage.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);
            TransactionTemplate unrelatedTransaction =
                    new TransactionTemplate(unrelated.transactionManager());

            assertThatThrownBy(() -> unrelatedTransaction.executeWithoutResult(
                    ignored -> mutation.apply(database.jdbc())))
                    .isInstanceOf(DurabilityException.class)
                    .hasMessageContaining("not bound to the active transaction");
        }

        assertThat(store.loadPage(0, 10)).isEmpty();
    }

    @Test
    void rejectsPreparedMutationAfterItsStageCloses() {
        StagedBlogeExecutionCheckpointStore.PreparedMutation mutation;
        try (StagedBlogeExecutionCheckpointStore.Stage stage = store.begin("engine-a")) {
            store.save(checkpoint("engine-a", "only", null, "one", 0));
            mutation = stage.prepare("checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);
        }

        DurableTestExecutionCheckpoint control = control(0, mutation.engineState());
        assertThatThrownBy(() -> repository.create(control, mutation))
                .isInstanceOf(DurabilityException.class)
                .hasMessageContaining("closed stage");
        assertThat(repository.find("tenant-a", "test", "run-a")).isEmpty();
        assertThat(store.loadPage(0, 10)).isEmpty();
    }

    @Test
    void runsWithCallerAssignedExecutionIdAndCommitsTheEngineClosure() {
        IndependentDurableTestEngineFactory factory = new IndependentDurableTestEngineFactory(
                new DefaultOperatorRegistry(), new JacksonCheckpointCodec(objectMapper), store);
        Operator<Void, String> operator = (ignored, context) -> "real";
        var graph = new GraphBuilder("controlled-durable-test")
                .node("only", operator)
                .build();
        ExecutionServices services = ExecutionServices.builder()
                .idGenerator(scope -> "fixture-id")
                .build();

        try (IndependentDurableTestEngineFactory.RunSession session = factory.openSession(
                "engine-a", new InvocationRecorder(objectMapper), ExecutionOptions.builder()
                        .operatorResolver(request ->
                                (Operator<Void, String>) (ignored, context) -> "fixture")
                        .executionServices(services)
                        .build())) {
            var result = session.execute(graph, new GraphContext());
            assertThat(result.isSuccess()).isTrue();
            assertThat(result.executionId()).isEqualTo("engine-a");
            assertThat(result.results().get("only", String.class)).isEqualTo("fixture");
            assertThat(store.load("engine-a", CheckpointType.NODE_OUTPUT, "only")).isPresent();

            StagedBlogeExecutionCheckpointStore.PreparedMutation mutation = session.prepare(
                    "checkpoint-0", "only", "NODE_BOUNDARY", 1, 0);
            repository.create(control(0, mutation.engineState()), mutation);
        }

        assertThat(repository.find("tenant-a", "test", "run-a")).isPresent();
        assertThat(store.load("engine-a", CheckpointType.NODE_OUTPUT, "only")).isPresent();
    }

    @Test
    void concurrentStoreInstancesRollbackTheLosingBlogeClosure() throws Exception {
        DurableTestExecutionCheckpoint.EngineState initialState =
                new DurableTestExecutionCheckpoint.EngineState(
                        "checkpoint-initial", "root", "NODE_BOUNDARY", 1, 0, SHA_D);
        DurableTestExecutionCheckpoint initialControl = control(0, initialState);
        repository.create(initialControl, boundNoop(initialControl));
        StagedBlogeExecutionCheckpointStore competingStore =
                new StagedBlogeExecutionCheckpointStore(database.jdbc(), objectMapper);
        competingStore.init();
        CountDownLatch bothMutationsEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (StagedBlogeExecutionCheckpointStore.Stage firstStage = store.begin("engine-a");
             StagedBlogeExecutionCheckpointStore.Stage secondStage =
                     competingStore.begin("engine-a")) {
            store.save(checkpoint("engine-a", "candidate-a", null, "a", 1));
            competingStore.save(checkpoint("engine-a", "candidate-b", null, "b", 1));
            var firstMutation = firstStage.prepare(
                    "checkpoint-a", "candidate-a", "NODE_BOUNDARY", 2, 1);
            var secondMutation = secondStage.prepare(
                    "checkpoint-b", "candidate-b", "NODE_BOUNDARY", 2, 1);

            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = executor.submit(() -> advanceCandidate(
                        firstMutation, bothMutationsEntered, release));
                var second = executor.submit(() -> advanceCandidate(
                        secondMutation, bothMutationsEntered, release));
                assertThat(bothMutationsEntered.await(5, TimeUnit.SECONDS)).isTrue();
                release.countDown();

                int successes = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
                assertThat(successes).isEqualTo(1);
            }
        }

        assertThat(store.loadAll("engine-a"))
                .extracting(ExecutionCheckpoint::nodeId)
                .containsExactlyInAnyOrderElementsOf(List.of(
                        repository.find("tenant-a", "test", "run-a").orElseThrow()
                                .engineState().nodeId()));
    }

    private ExecutionCheckpoint checkpoint(String executionId, String nodeId,
                                             String iterationKey, String payload, long version) {
        Instant now = Instant.parse("2026-07-16T08:00:00Z").plusSeconds(version);
        ExecutionIdentity identity = new ExecutionIdentity(
                "tenant-a", "test-runtime", null, executionId, ExecutionType.GRAPH,
                "controlled-graph", "1", SHA_A, null, null, null, "run-a");
        return ExecutionCheckpoint.builder(identity, CheckpointType.NODE_OUTPUT, nodeId)
                .iterationKey(iterationKey)
                .payload(payload)
                .operatorFingerprint(SHA_B)
                .schemaVersion("1")
                .version(version)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private boolean advanceCandidate(
            StagedBlogeExecutionCheckpointStore.PreparedMutation mutation,
            CountDownLatch entered,
            CountDownLatch release) {
        DurableTestExecutionCheckpointRepository.BoundEngineStateMutation coordinated =
                boundMutation(mutation, jdbc -> {
                    mutation.apply(jdbc);
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("concurrency test did not release");
                        }
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "concurrency test was interrupted", interrupted);
                    }
                });
        try {
            repository.advance(control(1, mutation.engineState()),
                    new DurableTestExecutionCheckpointRepository.Fence("instance-a", 1, 0),
                    coordinated);
            return true;
        } catch (DurableTestExecutionCheckpointConflictException expected) {
            assertThat(expected.reason()).isEqualTo(
                    DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE);
            return false;
        }
    }

    private DurableTestExecutionCheckpointRepository.BoundEngineStateMutation boundMutation(
            StagedBlogeExecutionCheckpointStore.PreparedMutation mutation,
            Consumer<JdbcTemplate> action) {
        return new DurableTestExecutionCheckpointRepository.BoundEngineStateMutation() {
            @Override
            public String engineExecutionId() {
                return mutation.engineExecutionId();
            }

            @Override
            public DurableTestExecutionCheckpoint.EngineState engineState() {
                return mutation.engineState();
            }

            @Override
            public void apply(JdbcTemplate jdbc) {
                action.accept(jdbc);
            }
        };
    }

    private DurableTestExecutionCheckpointRepository.BoundEngineStateMutation boundNoop(
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
                // Test setup represents an engine state that predates the candidate mutations.
            }
        };
    }

    private DurableTestExecutionCheckpoint control(
            long revision, DurableTestExecutionCheckpoint.EngineState engineState) {
        return control(revision, "engine-a", engineState);
    }

    private DurableTestExecutionCheckpoint control(
            long revision,
            String engineExecutionId,
            DurableTestExecutionCheckpoint.EngineState engineState) {
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
                objectMapper, unsealedProvider.fingerprintMaterial()));
        return integrity.seal(new DurableTestExecutionCheckpoint(
                DurableTestExecutionCheckpoint.SCHEMA_VERSION,
                new DurableTestExecutionCheckpoint.Scope(
                        "tenant-a", "org-a", "project-a", "test", "runner"),
                "run-a", engineExecutionId,
                new DurableTestExecutionCheckpoint.ControlDependencies(
                        plan, new DurableTestExecutionCheckpoint.ExactFixtureRef(
                        "fixture-a", 3, SHA_C), "DENY_REAL",
                        new DurableTestExecutionCheckpoint.AuthoritySnapshot("FAIL_CLOSED", SHA_D)),
                new FixtureConsumptionStateSnapshot(
                        FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                        Map.of("rule-a", revision + 1), Map.of(SHA_A, revision + 1),
                        Map.of(SHA_B, revision + 1), ""),
                provider,
                engineState,
                new DurableTestExecutionCheckpoint.Lifecycle(
                        DurableTestExecutionCheckpoint.Status.SUSPENDED, "instance-a", 1,
                        revision, Instant.parse("2026-07-16T08:00:00Z"), now,
                        now.plusSeconds(30)), ""));
    }
}
