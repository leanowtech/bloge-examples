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
}
