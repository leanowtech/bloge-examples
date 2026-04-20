package com.leanowtech.bloge.graphengine.ai;

import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.graphengine.ai.prompt.PromptContextBuilder;
import com.leanowtech.bloge.graphengine.ai.validate.DslValidationPipeline;
import com.leanowtech.bloge.operators.spi.LlmProvider;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphAuthoringServiceTest {

    @Test
    void generateRepairsInvalidFirstDraft() {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("echo", new EchoOperator());
        GraphAuthoringService service = new GraphAuthoringService(
                new StubLlmProvider(
                        """
                        ```bloge
                        graph greet {
                          node hello : echo {
                            input {
                              message = ctx.message
                            }
                        ```
                        """,
                        """
                        /// Greeting workflow.
                        graph greet {
                          node hello : echo {
                            input {
                              message = ctx.message
                            }
                            timeout = 1s
                          }
                        }
                        """
                ),
                new PromptContextBuilder(registry),
                DslValidationPipeline.builder().operatorRegistry(registry).build()
        );

        GraphAuthoringResult result = service.generate(new GraphAuthoringRequest(
                "Create a greeting workflow",
                "fake-model",
                2,
                2,
                null,
                null
        ));

        assertTrue(result.validation().valid());
        assertTrue(result.repaired());
        assertEquals(2, result.attempts().size());
        assertEquals(GraphAuthoringPhase.GENERATE, result.attempts().get(0).phase());
        assertEquals(GraphAuthoringPhase.REPAIR, result.attempts().get(1).phase());
        assertEquals("fake-model", result.model());
    }

    private static final class EchoOperator implements Operator<String, String> {
        @Override
        public String execute(String input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            return input;
        }
    }

    private static final class StubLlmProvider implements LlmProvider {
        private final ArrayDeque<String> responses;

        private StubLlmProvider(String... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public LlmResponse chat(LlmRequest request) {
            String next = responses.removeFirst();
            return new LlmResponse(next, "stop", 42, 21);
        }

        @Override
        public java.util.stream.Stream<LlmChunk> streamChat(LlmRequest request) {
            throw new UnsupportedOperationException("streamChat");
        }
    }
}
