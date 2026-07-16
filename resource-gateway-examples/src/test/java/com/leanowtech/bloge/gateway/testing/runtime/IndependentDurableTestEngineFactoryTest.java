package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.engine.CheckpointFailurePolicy;
import com.leanowtech.bloge.core.exception.DurabilityException;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.durable.codec.JacksonCheckpointCodec;
import com.leanowtech.bloge.gateway.testing.persistence.StagedBlogeExecutionCheckpointStore;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IndependentDurableTestEngineFactoryTest {

    @Test
    void usesFailFastPolicyAndDoesNotSilentlyRunWithoutACompositeStage() {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (TestRuntimeDatabase database = new TestRuntimeDatabase(
                new TestRuntimeDatabase.Settings(
                        "jdbc:h2:mem:durable-test-engine-" + System.nanoTime()
                                + ";DB_CLOSE_DELAY=-1", "sa", "", 2))) {
            StagedBlogeExecutionCheckpointStore store =
                    new StagedBlogeExecutionCheckpointStore(database.jdbc(), mapper);
            store.init();
            IndependentDurableTestEngineFactory factory = new IndependentDurableTestEngineFactory(
                    new DefaultOperatorRegistry(), new JacksonCheckpointCodec(mapper), store);
            Operator<Void, String> operator = (ignored, context) -> "done";
            var graph = new GraphBuilder("strict-durable-test")
                    .node("only", operator)
                    .build();

            var engine = factory.create(new InvocationRecorder(mapper), null);
            try {
                var result = engine.execute(graph, new GraphContext());

                assertThat(result.isSuccess()).isFalse();
                assertThat(result.errors()).anySatisfy(error ->
                        assertThat(error.exception()).isInstanceOf(DurabilityException.class));
                assertThat(factory.configuration().checkpointFailurePolicy())
                        .isEqualTo(CheckpointFailurePolicy.FAIL_FAST);
                assertThat(factory.configuration().durableStores()).isTrue();
                assertThat(factory.configuration().productionContextCarriers()).isFalse();
            } finally {
                engine.shutdown();
            }
        }
    }
}
