package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.ExampleDslResources;
import com.leanowtech.bloge.operators.ai.AiTypes;
import com.leanowtech.bloge.operators.spi.LlmProvider;

import java.util.List;
import java.util.Map;

/**
 * DSL version of {@link AiToolCallingExample}.
 * The graph structure lives in {@code /bloge/ai-tool-calling.bloge} while the operators reuse
 * the same mock tool-calling runtime as the fluent Java example.
 */
@SuppressWarnings("preview")
public final class AiToolCallingDslExample {

    private static final String DSL_RESOURCE = "/bloge/ai-tool-calling.bloge";

    private AiToolCallingDslExample() {
    }

    /**
     * Compiles the example DSL resource after registering the mock operators.
     *
     * @param registry operator registry used by the DSL compiler
     * @return compiled graph
     */
    public static Graph buildGraph(DefaultOperatorRegistry registry) {
        registerOperators(registry);
        return ExampleDslResources.loadGraph(DSL_RESOURCE, registry);
    }

    /**
     * Executes the DSL example with a single user question.
     *
     * @param question weather question asked by the user
     * @return graph result
     */
    public static GraphResult execute(String question) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(graph, new GraphContext(Map.of(AiToolCallingExample.K_QUESTION, question)));
    }

    /**
     * Runs the DSL example with a default Paris weather question.
     *
     * @param args optional first argument overrides the question
     */
    public static void main(String[] args) {
        String question = args.length > 0 ? args[0] : "What is the weather in Paris right now?";
        GraphResult result = execute(question);
        AiTypes.LlmChatOutput finalReply = result.getOutput("respond", AiTypes.LlmChatOutput.class);
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Final answer: " + finalReply.content());
    }

    private static void registerOperators(DefaultOperatorRegistry registry) {
        registry.register("AiToolThinking", thinkingWrapper());
        registry.register("AiToolExecutor", executeToolWrapper());
        registry.register("AiToolResponder", respondWrapper());
    }

    private static Operator<Map<String, Object>, AiTypes.LlmChatOutput> thinkingWrapper() {
        return new Operator<>() {
            @Override
            public AiTypes.LlmChatOutput execute(Map<String, Object> input, OperatorContext ctx) throws Exception {
                return AiToolCallingExample.THINK.execute(
                        new AiToolCallingExample.WeatherQuestion(String.valueOf(input.get("question"))),
                        ctx
                );
            }
        };
    }

    private static Operator<Map<String, Object>, AiToolCallingExample.ToolExecutionResult> executeToolWrapper() {
        return new Operator<>() {
            @Override
            public AiToolCallingExample.ToolExecutionResult execute(Map<String, Object> input, OperatorContext ctx) throws Exception {
                return AiToolCallingExample.EXECUTE_TOOL.execute(
                        new AiToolCallingExample.ToolExecutionInput(
                                String.valueOf(input.get("question")),
                                coerceToolCalls(input.get("toolCalls"))
                        ),
                        ctx
                );
            }
        };
    }

    private static Operator<Map<String, Object>, AiTypes.LlmChatOutput> respondWrapper() {
        return new Operator<>() {
            @Override
            public AiTypes.LlmChatOutput execute(Map<String, Object> input, OperatorContext ctx) throws Exception {
                return AiToolCallingExample.RESPOND.execute(
                        new AiToolCallingExample.FollowUpInput(
                                String.valueOf(input.get("question")),
                                coerceToolCalls(input.get("toolCalls")),
                                coerceToolExecutionResult(input.get("toolResult"))
                        ),
                        ctx
                );
            }
        };
    }

    private static List<LlmProvider.ToolCall> coerceToolCalls(Object rawToolCalls) {
        if (!(rawToolCalls instanceof List<?> toolCalls) || toolCalls.isEmpty()) {
            throw new IllegalArgumentException("toolCalls must be a non-empty list");
        }
        return toolCalls.stream()
                .map(AiToolCallingDslExample::coerceToolCall)
                .toList();
    }

    private static LlmProvider.ToolCall coerceToolCall(Object rawToolCall) {
        if (rawToolCall instanceof LlmProvider.ToolCall toolCall) {
            return toolCall;
        }
        if (rawToolCall instanceof Map<?, ?> map) {
            return new LlmProvider.ToolCall(
                    map.get("id") == null ? null : String.valueOf(map.get("id")),
                    String.valueOf(map.get("name")),
                    String.valueOf(map.get("arguments"))
            );
        }
        throw new IllegalArgumentException("Unsupported toolCall payload: " + rawToolCall);
    }

    private static AiToolCallingExample.ToolExecutionResult coerceToolExecutionResult(Object rawToolResult) {
        if (rawToolResult instanceof AiToolCallingExample.ToolExecutionResult toolExecutionResult) {
            return toolExecutionResult;
        }
        if (rawToolResult instanceof Map<?, ?> map) {
            return new AiToolCallingExample.ToolExecutionResult(
                    String.valueOf(map.get("toolCallId")),
                    String.valueOf(map.get("toolName")),
                    String.valueOf(map.get("payload"))
            );
        }
        throw new IllegalArgumentException("Unsupported toolResult payload: " + rawToolResult);
    }
}
