package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.engine.CheckpointFailurePolicy;
import com.leanowtech.bloge.core.engine.ExecutionOptions;
import com.leanowtech.bloge.core.engine.ExecutionServices;
import com.leanowtech.bloge.core.exception.DurabilityException;
import com.leanowtech.bloge.core.exception.GraphExecutionException;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.runtime.wait.WaitStatus;
import com.leanowtech.bloge.core.runtime.wait.WaitType;
import com.leanowtech.bloge.core.runtime.execution.ExecutionStatus;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.durable.codec.JacksonCheckpointCodec;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeDurableStateStore;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndependentDurableTestEngineFactoryTest {

    @Test
    void freezesExactlyOneInitialSignalSuspensionAndItsFixtureCursor() {
        try (Harness harness = harness("initial-signal")) {
            SuspendableOperator<Void, String> wait = (input, context) ->
                    OperatorResult.suspend("approval-key");
            Graph graph = new GraphBuilder("initial-signal")
                    .suspendNode("approval", wait)
                    .build();
            ExecutionOptions options = options("engine-initial-signal");
            InvocationRecorder recorder = new InvocationRecorder(harness.mapper());

            try (var session = harness.factory().openSession(
                    "engine-initial-signal", recorder, options)) {
                session.execute(graph, new GraphContext());

                var prepared = session.prepareInitialSuspension("checkpoint-initial");

                assertThat(prepared.boundary().executionStatus())
                        .isEqualTo(ExecutionStatus.SUSPENDED);
                assertThat(prepared.boundary().nodeId()).isEqualTo("approval");
                assertThat(prepared.boundary().boundaryType()).isEqualTo("SUSPEND");
                assertThat(prepared.engineStateMutation().engineState().boundarySequence())
                        .isEqualTo(1);
                assertThat(prepared.fixtureConsumptionState().stateFingerprint())
                        .startsWith("sha256:");
            }

            assertThat(harness.store().executionStore().get("engine-initial-signal"))
                    .isEmpty();
        }
    }

    @Test
    void rejectsTerminalInitialExecutionAndDropsItsStagedRows() {
        try (Harness harness = harness("terminal-boundary")) {
            Operator<Void, String> operator = (ignored, context) -> "done";
            Graph graph = new GraphBuilder("terminal-boundary")
                    .node("only", operator)
                    .build();
            ExecutionOptions options = options("engine-terminal-boundary");

            try (var session = harness.factory().openSession(
                    "engine-terminal-boundary",
                    new InvocationRecorder(harness.mapper()), options)) {
                session.execute(graph, new GraphContext());

                assertThatThrownBy(() -> session.prepareInitialSuspension("checkpoint-terminal"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("initial suspended");
            }

            assertThat(harness.store().executionStore().get("engine-terminal-boundary"))
                    .isEmpty();
        }
    }

    @Test
    void rejectsAmbiguousParallelSignalSuspensions() {
        try (Harness harness = harness("parallel-suspensions")) {
            SuspendableOperator<Void, String> wait = (input, context) ->
                    OperatorResult.suspend("approval-key");
            Graph graph = new GraphBuilder("parallel-suspensions")
                    .suspendNode("first", wait)
                    .suspendNode("second", wait)
                    .build();
            ExecutionOptions options = options("engine-parallel-suspensions");

            try (var session = harness.factory().openSession(
                    "engine-parallel-suspensions",
                    new InvocationRecorder(harness.mapper()), options)) {
                session.execute(graph, new GraphContext());

                assertThatThrownBy(() -> session.prepareInitialSuspension("checkpoint-parallel"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("exactly one initial signal suspension");
            }
        }
    }

    @Test
    void requiresExecutionBeforePreparingInitialSuspension() {
        try (Harness harness = harness("prepare-before-execute")) {
            ExecutionOptions options = options("engine-before-execute");
            try (var session = harness.factory().openSession(
                    "engine-before-execute",
                    new InvocationRecorder(harness.mapper()), options)) {
                assertThatThrownBy(() -> session.prepareInitialSuspension("checkpoint-early"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("must execute");
            }
        }
    }

    @Test
    @Timeout(10)
    void attachesTheStagedWaitStoreToRealSuspendAndSignalExecution() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = new TestRuntimeDatabase(
                new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:durable-test-wait-" + System.nanoTime()
                                + ";DB_CLOSE_DELAY=-1", "sa", "", 2))) {
            StagedBlogeDurableStateStore store =
                    new StagedBlogeDurableStateStore(database.jdbc(), mapper);
            store.init();
            IndependentDurableTestEngineFactory factory = new IndependentDurableTestEngineFactory(
                    new DefaultOperatorRegistry(), new JacksonCheckpointCodec(mapper), store);
            SuspendableOperator<Void, String> wait = (input, context) ->
                    OperatorResult.suspend("approval-key");
            Graph graph = new GraphBuilder("strict-durable-suspend")
                    .suspendNode("approval", wait)
                    .build();
            ExecutionServices services = ExecutionServices.builder()
                    .idGenerator(scope -> "engine-a")
                    .build();
            ExecutionOptions options = ExecutionOptions.builder()
                    .executionServices(services)
                    .build();

            try (StagedBlogeDurableStateStore.Stage ignored =
                         store.begin("engine-a", services.timeSource())) {
                var engine = factory.create(new InvocationRecorder(mapper), services.timeSource());
                AtomicReference<Throwable> executionFailure = new AtomicReference<>();
                Thread execution = Thread.ofVirtual().start(() -> {
                    try {
                        engine.execute(graph, new GraphContext(), options);
                    } catch (Throwable failure) {
                        executionFailure.set(failure);
                    }
                });
                try {
                    awaitSignalWait(store);
                    assertThat(store.waitStore().findByExecution("engine-a"))
                            .singleElement()
                            .satisfies(executionWait -> {
                                assertThat(executionWait.waitType())
                                        .isEqualTo(WaitType.WAIT_SIGNAL);
                                assertThat(executionWait.status())
                                        .isEqualTo(WaitStatus.WAITING);
                                assertThat(executionWait.waitKey())
                                        .isEqualTo("approval-key");
                            });

                    engine.signal(graph, "engine-a", "approval", "approved", options);
                    execution.join(5_000);
                    assertThat(execution.isAlive()).isFalse();
                    assertThat(executionFailure.get()).isNull();
                    assertThat(store.waitStore().findByExecution("engine-a")).isEmpty();
                } finally {
                    execution.interrupt();
                    engine.shutdown();
                }
            }
        }
    }

    @Test
    void usesFailFastPolicyAndDoesNotSilentlyRunWithoutACompositeStage() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = new TestRuntimeDatabase(
                new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:durable-test-engine-" + System.nanoTime()
                                + ";DB_CLOSE_DELAY=-1", "sa", "", 2))) {
            StagedBlogeDurableStateStore store =
                    new StagedBlogeDurableStateStore(database.jdbc(), mapper);
            store.init();
            IndependentDurableTestEngineFactory factory = new IndependentDurableTestEngineFactory(
                    new DefaultOperatorRegistry(), new JacksonCheckpointCodec(mapper), store);
            Operator<Void, String> operator = (ignored, context) -> "done";
            var graph = new GraphBuilder("strict-durable-test")
                    .node("only", operator)
                    .build();

            var engine = factory.create(new InvocationRecorder(mapper), null);
            try {
                assertThatThrownBy(() -> engine.execute(graph, new GraphContext()))
                        .isInstanceOf(GraphExecutionException.class)
                        .hasRootCauseInstanceOf(DurabilityException.class)
                        .hasMessageContaining("execution failed");
                assertThat(factory.configuration().checkpointFailurePolicy())
                        .isEqualTo(CheckpointFailurePolicy.FAIL_FAST);
                assertThat(factory.configuration().durableStores()).isTrue();
                assertThat(factory.configuration().productionContextCarriers()).isFalse();
            } finally {
                engine.shutdown();
            }
        }
    }

    private static void awaitSignalWait(StagedBlogeDurableStateStore store)
            throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (store.waitStore().findByExecution("engine-a").isEmpty()
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(store.waitStore().findByExecution("engine-a")).isNotEmpty();
    }

    private static ExecutionOptions options(String executionId) {
        return ExecutionOptions.builder()
                .executionServices(ExecutionServices.builder()
                        .idGenerator(scope -> executionId)
                        .build())
                .build();
    }

    private static Harness harness(String name) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TestRuntimeDatabase database = new TestRuntimeDatabase(
                new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:durable-test-" + name + "-" + System.nanoTime()
                                + ";DB_CLOSE_DELAY=-1", "sa", "", 2));
        StagedBlogeDurableStateStore store =
                new StagedBlogeDurableStateStore(database.jdbc(), mapper);
        store.init();
        IndependentDurableTestEngineFactory factory = new IndependentDurableTestEngineFactory(
                new DefaultOperatorRegistry(), new JacksonCheckpointCodec(mapper), store);
        return new Harness(mapper, database, store, factory);
    }

    private record Harness(
            ObjectMapper mapper,
            TestRuntimeDatabase database,
            StagedBlogeDurableStateStore store,
            IndependentDurableTestEngineFactory factory) implements AutoCloseable {
        @Override
        public void close() {
            database.close();
        }
    }
}
