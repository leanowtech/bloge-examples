package com.leanowtech.bloge.graphengine.server.rest;

import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.graphengine.ai.GraphAuthoringService;
import com.leanowtech.bloge.graphengine.ai.prompt.PromptContextBuilder;
import com.leanowtech.bloge.graphengine.ai.validate.DslValidationPipeline;
import com.leanowtech.bloge.operators.spi.LlmProvider;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayDeque;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GraphAuthoringControllerTest extends AbstractGraphControllerTest {

    @Test
    void validateEndpointReturnsStructuredValidationResult() throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("echo", new EchoOperator());
        DslValidationPipeline validationPipeline = DslValidationPipeline.builder()
                .operatorRegistry(registry)
                .build();
        MockMvc mockMvc = mockMvc(new GraphAuthoringController(validationPipeline, null));

        mockMvc.perform(post("/api/v1/ai/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "dslSource": "/// Greeting workflow.\\ngraph greet {\\n  node hello : echo {\\n    input {\\n      message = ctx.message\\n    }\\n    timeout = 1s\\n  }\\n}"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.executionMode").value("GRAPH"));
    }

    @Test
    void generateEndpointRepairsFirstInvalidDraft() throws Exception {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        registry.register("echo", new EchoOperator());
        DslValidationPipeline validationPipeline = DslValidationPipeline.builder()
                .operatorRegistry(registry)
                .build();
        GraphAuthoringService authoringService = new GraphAuthoringService(
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
                validationPipeline
        );
        MockMvc mockMvc = mockMvc(new GraphAuthoringController(validationPipeline, authoringService));

        mockMvc.perform(post("/api/v1/ai/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "naturalLanguageRequest": "Create a greeting workflow",
                                  "model": "fake-model",
                                  "fewShotExampleCount": 2,
                                  "maxRepairRounds": 2
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("fake-model"))
                .andExpect(jsonPath("$.repaired").value(true))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.attempts.length()").value(2))
                .andExpect(jsonPath("$.attempts[0].phase").value("GENERATE"))
                .andExpect(jsonPath("$.attempts[1].phase").value("REPAIR"));
    }

    @Test
    void generateEndpointReturns503WhenNoProviderIsConfigured() throws Exception {
        DslValidationPipeline validationPipeline = DslValidationPipeline.builder().build();
        MockMvc mockMvc = mockMvc(new GraphAuthoringController(validationPipeline, null));

        mockMvc.perform(post("/api/v1/ai/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "naturalLanguageRequest": "Create a greeting workflow",
                                  "model": "fake-model"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("RUNTIME_UNAVAILABLE"));
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
            return new LlmResponse(responses.removeFirst(), "stop", 12, 7);
        }

        @Override
        public java.util.stream.Stream<LlmChunk> streamChat(LlmRequest request) {
            throw new UnsupportedOperationException("streamChat");
        }
    }
}
