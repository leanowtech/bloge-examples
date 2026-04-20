package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.operators.ai.AiTypes;
import com.leanowtech.bloge.operators.ai.LlmChatOperator;
import com.leanowtech.bloge.operators.spi.LlmProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal tool-calling example built on top of the expanded {@link LlmProvider} SPI.
 *
 * <p>The graph shows a manual two-turn agent loop before the dedicated {@code agent}
 * DSL arrives:
 *
 * <pre>
 * think → executeTool → respond
 * </pre>
 *
 * <p>The first LLM turn requests {@code lookupWeather} via {@link LlmProvider.ToolCall}.
 * The graph executes the tool in a regular node, feeds the result back as a
 * {@code tool} role message via {@link LlmProvider.LlmMessage#tool(String, String)},
 * and runs a second turn to produce the final answer.
 */
@SuppressWarnings("preview")
public final class AiToolCallingExample {

    static final String K_QUESTION = "question";

    static final Operator<WeatherQuestion, AiTypes.LlmChatOutput> THINK = new ThinkOperator();
    static final Operator<ToolExecutionInput, ToolExecutionResult> EXECUTE_TOOL = new ExecuteToolOperator();
    static final Operator<FollowUpInput, AiTypes.LlmChatOutput> RESPOND = new RespondOperator();

    private static final LlmProvider.ToolDefinition WEATHER_TOOL = new LlmProvider.ToolDefinition(
            "lookupWeather",
            "Look up the current weather for a city",
            Map.of(
                    "type", "object",
                    "properties", Map.of("city", Map.of("type", "string")),
                    "required", List.of("city")
            )
    );

    private AiToolCallingExample() {
    }

    /**
     * Builds the fluent Java graph.
     *
     * @return graph that performs a manual tool-calling loop
     */
    public static Graph buildGraph() {
        return Graph.builder("aiToolCalling")
                .node("think", THINK)
                    .input((results, ctx) -> new WeatherQuestion(ctx.get(K_QUESTION, String.class)))
                .node("executeTool", EXECUTE_TOOL)
                    .dependsOn("think")
                    .input((results, ctx) -> new ToolExecutionInput(
                            ctx.get(K_QUESTION, String.class),
                            results.get("think", AiTypes.LlmChatOutput.class).toolCalls()
                    ))
                .node("respond", RESPOND)
                    .dependsOn("executeTool")
                    .input((results, ctx) -> new FollowUpInput(
                            ctx.get(K_QUESTION, String.class),
                            results.get("think", AiTypes.LlmChatOutput.class).toolCalls(),
                            results.get("executeTool", ToolExecutionResult.class)
                    ))
                .build();
    }

    /**
     * Executes the example with a single user question.
     *
     * @param question weather question asked by the user
     * @return graph result containing both the tool-call turn and the final answer
     * @throws Exception when graph execution fails
     */
    public static GraphResult execute(String question) throws Exception {
        var engine = GraphEngine.builder()
                .registry(new DefaultOperatorRegistry())
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.executeWithOperators(
                buildGraph(),
                new GraphContext(Map.of(K_QUESTION, question)),
                operatorMap()
        );
    }

    /**
     * Operator map required by {@link GraphEngine#executeWithOperators(Graph, GraphContext, Map)}.
     *
     * @return node-id to operator mapping
     */
    public static Map<String, Operator<?, ?>> operatorMap() {
        return Map.of(
                "think", THINK,
                "executeTool", EXECUTE_TOOL,
                "respond", RESPOND
        );
    }

    /**
     * Runs the example with a default Paris weather question.
     *
     * @param args optional first argument overrides the question
     * @throws Exception when execution fails
     */
    public static void main(String[] args) throws Exception {
        String question = args.length > 0 ? args[0] : "What is the weather in Paris right now?";
        GraphResult result = execute(question);
        AiTypes.LlmChatOutput thought = result.getOutput("think", AiTypes.LlmChatOutput.class);
        AiTypes.LlmChatOutput finalReply = result.getOutput("respond", AiTypes.LlmChatOutput.class);

        System.out.println("Success: " + result.isSuccess());
        System.out.println("Tool calls: " + thought.toolCalls());
        System.out.println("Final answer: " + finalReply.content());
    }

    /**
     * Typed question passed into the first LLM turn.
     *
     * @param question user question
     */
    public record WeatherQuestion(String question) {
        public WeatherQuestion {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question must not be blank");
            }
        }
    }

    /**
     * Tool-execution input assembled from the assistant's tool-call request.
     *
     * @param question original user question
     * @param toolCalls tool calls requested by the assistant
     */
    public record ToolExecutionInput(String question, List<LlmProvider.ToolCall> toolCalls) {
        public ToolExecutionInput {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question must not be blank");
            }
            if (toolCalls == null || toolCalls.isEmpty()) {
                throw new IllegalArgumentException("toolCalls must not be empty");
            }
            toolCalls = List.copyOf(toolCalls);
        }
    }

    /**
     * Serialized tool result that will be fed back to the model.
     *
     * @param toolCallId tool-call identifier to acknowledge
     * @param toolName executed tool name
     * @param payload serialized JSON result
     */
    public record ToolExecutionResult(String toolCallId, String toolName, String payload) {
        public ToolExecutionResult {
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("toolCallId must not be blank");
            }
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName must not be blank");
            }
            if (payload == null || payload.isBlank()) {
                throw new IllegalArgumentException("payload must not be blank");
            }
        }
    }

    /**
     * Input for the second LLM turn that observes the tool result.
     *
     * @param question original user question
     * @param toolCalls assistant tool calls from the first turn
     * @param toolResult executed tool result
     */
    public record FollowUpInput(String question, List<LlmProvider.ToolCall> toolCalls, ToolExecutionResult toolResult) {
        public FollowUpInput {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question must not be blank");
            }
            if (toolCalls == null || toolCalls.isEmpty()) {
                throw new IllegalArgumentException("toolCalls must not be empty");
            }
            toolCalls = List.copyOf(toolCalls);
            toolResult = Objects.requireNonNull(toolResult, "toolResult");
        }
    }

    private static final class ThinkOperator implements Operator<WeatherQuestion, AiTypes.LlmChatOutput> {
        private final LlmChatOperator llmChat = new LlmChatOperator(new MockToolCallingLlmProvider());

        @Override
        public AiTypes.LlmChatOutput execute(WeatherQuestion input, OperatorContext ctx) throws Exception {
            return llmChat.execute(new AiTypes.LlmChatInput(
                    "mock-gpt-4o",
                    List.of(
                            LlmProvider.LlmMessage.system("Use the lookupWeather tool for live weather questions."),
                            LlmProvider.LlmMessage.user(input.question())
                    ),
                    0.1,
                    128,
                    List.of(),
                    List.of(WEATHER_TOOL),
                    "auto",
                    new LlmProvider.ResponseFormat.Text()
            ), ctx);
        }
    }

    private static final class ExecuteToolOperator implements Operator<ToolExecutionInput, ToolExecutionResult> {
        @Override
        public ToolExecutionResult execute(ToolExecutionInput input, OperatorContext ctx) {
            LlmProvider.ToolCall toolCall = input.toolCalls().getFirst();
            Map<String, Object> arguments = parseJsonObject(toolCall.arguments());
            String city = String.valueOf(arguments.getOrDefault("city", "Paris"));
            String payload = JsonCodec.DEFAULT.serialize(Map.of(
                    "city", city,
                    "conditions", "sunny",
                    "temperatureC", 22
            ));
            return new ToolExecutionResult(toolCall.id(), toolCall.name(), payload);
        }
    }

    private static final class RespondOperator implements Operator<FollowUpInput, AiTypes.LlmChatOutput> {
        private final LlmChatOperator llmChat = new LlmChatOperator(new MockToolCallingLlmProvider());

        @Override
        public AiTypes.LlmChatOutput execute(FollowUpInput input, OperatorContext ctx) throws Exception {
            return llmChat.execute(new AiTypes.LlmChatInput(
                    "mock-gpt-4o",
                    List.of(
                            LlmProvider.LlmMessage.system("Use the lookupWeather tool for live weather questions."),
                            LlmProvider.LlmMessage.user(input.question()),
                            new LlmProvider.LlmMessage("assistant", null, List.of(), input.toolCalls(), null),
                            LlmProvider.LlmMessage.tool(input.toolResult().toolCallId(), input.toolResult().payload())
                    ),
                    0.1,
                    128,
                    List.of(),
                    List.of(WEATHER_TOOL),
                    "auto",
                    new LlmProvider.ResponseFormat.Text()
            ), ctx);
        }
    }

    private static final class MockToolCallingLlmProvider implements LlmProvider {
        @Override
        public LlmResponse chat(LlmRequest request) {
            boolean hasToolResult = request.messages().stream().anyMatch(message -> "tool".equals(message.role()));
            if (!hasToolResult) {
                String city = inferCity(request.messages());
                return new LlmResponse(
                        null,
                        "tool_calls",
                        request.messages().size() * 12,
                        8,
                        List.of(new ToolCall(
                                "weather-call-1",
                                WEATHER_TOOL.name(),
                                JsonCodec.DEFAULT.serialize(Map.of("city", city))
                        ))
                );
            }

            String toolPayload = request.messages().stream()
                    .filter(message -> "tool".equals(message.role()))
                    .map(LlmMessage::content)
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("missing tool payload"));
            Map<String, Object> payload = parseJsonObject(toolPayload);
            String city = String.valueOf(payload.getOrDefault("city", "Paris"));
            String conditions = String.valueOf(payload.getOrDefault("conditions", "sunny"));
            Object rawTemperature = payload.getOrDefault("temperatureC", 22L);
            long temperature = rawTemperature instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(rawTemperature));
            return new LlmResponse(
                    "The weather in %s is %s and %dC.".formatted(city, conditions, temperature),
                    "stop",
                    request.messages().size() * 12,
                    18
            );
        }

        @Override
        public java.util.stream.Stream<LlmChunk> streamChat(LlmRequest request) {
            throw new UnsupportedOperationException("streaming is not used in this example");
        }

        private String inferCity(List<LlmMessage> messages) {
            return messages.stream()
                    .filter(message -> "user".equals(message.role()))
                    .map(LlmMessage::content)
                    .filter(Objects::nonNull)
                    .map(content -> {
                        if (content.contains("Paris")) {
                            return "Paris";
                        }
                        if (content.contains("Tokyo")) {
                            return "Tokyo";
                        }
                        return "Paris";
                    })
                    .findFirst()
                    .orElse("Paris");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String json) {
        Object parsed = JsonCodec.DEFAULT.deserialize(json);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Expected a JSON object payload");
        }
        var result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
