package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.agent.builder.AgentBuilder;
import com.leanowtech.bloge.agent.engine.StreamingAgentLoopOperator;
import com.leanowtech.bloge.agent.model.AgentMemoryStrategy;
import com.leanowtech.bloge.agent.model.AgentOutput;
import com.leanowtech.bloge.agent.model.AgentStreamChunk;
import com.leanowtech.bloge.agent.model.AgentToolRef;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.operators.ai.LlmChatOperator;
import com.leanowtech.bloge.operators.ai.LlmStreamingChatOperator;
import com.leanowtech.bloge.operators.spi.LlmProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Demonstrates BLOGE's streaming agent runtime for a data-analysis assistant.
 *
 * <p>This example intentionally sits next to {@link AgentExample}: the older example shows the
 * synchronous {@code AgentLoopOperator}, while this one uses {@link StreamingAgentLoopOperator}
 * to materialize token, tool lifecycle, and final-output chunks from a single agent node.</p>
 */
@SuppressWarnings("preview")
public final class StreamingAnalysisAgentExample {

    static final String K_QUESTION = "question";
    static final String NODE_ID = "analyze";

    private static final LlmProvider.ToolDefinition FETCH_METRICS_TOOL = toolDefinition(
            "fetchMetrics",
            "Fetch time-series metrics by name",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "metricName", Map.of("type", "string"),
                            "timeRange", Map.of("type", "string")
                    ),
                    "required", List.of("metricName", "timeRange")
            )
    );

    private static final LlmProvider.ToolDefinition RUN_QUERY_TOOL = toolDefinition(
            "runQuery",
            "Run a SQL query against the analytics database",
            Map.of(
                    "type", "object",
                    "properties", Map.of("sql", Map.of("type", "string")),
                    "required", List.of("sql")
            )
    );

    private static final Operator<Map<String, Object>, Map<String, Object>> FETCH_METRICS = (input, ctx) -> Map.of(
            "metricName", String.valueOf(input.get("metricName")),
            "timeRange", String.valueOf(input.get("timeRange")),
            "trend", "up",
            "changePct", 12.0
    );

    private static final Operator<Map<String, Object>, Map<String, Object>> RUN_QUERY = (input, ctx) -> Map.of(
            "rows", List.of(Map.of("region", "north", "revenue", 128_000)),
            "sql", String.valueOf(input.get("sql"))
    );

    private static final StreamingAgentLoopOperator DATA_ANALYST_AGENT = buildAgentOperator();

    private StreamingAnalysisAgentExample() {
    }

    /**
     * Builds a graph containing one streaming agent node.
     *
     * @return graph configured for streaming materialization
     */
    public static Graph buildGraph() {
        return Graph.builder("streamingAnalysisAgent")
                .node(NODE_ID, (input, ctx) -> null)
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                    .input((results, ctx) -> ctx.get(K_QUESTION, String.class))
                .build();
    }

    /**
     * Executes the streaming analysis agent.
     *
     * @param question user analysis question
     * @return result whose {@code analyze} output is a list of {@link AgentStreamChunk} values
     */
    public static GraphResult execute(String question) {
        var registry = new DefaultOperatorRegistry();
        registerRuntime(registry, DATA_ANALYST_AGENT);
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(buildGraph(), new GraphContext(Map.of(K_QUESTION, question)));
    }

    /**
     * Registers the mock LLM and tool operators used by this offline example.
     *
     * @param registry operator registry to mutate
     * @param agentOperator streaming agent operator to bind to the graph node
     */
    static void registerRuntime(DefaultOperatorRegistry registry, StreamingAgentLoopOperator agentOperator) {
        registerSharedRuntime(registry);
        registry.registerRaw(NODE_ID, agentOperator);
    }

    static void registerSharedRuntime(DefaultOperatorRegistry registry) {
        var provider = new MockStreamingAnalysisLlmProvider();
        registry.register("llmChat", new LlmChatOperator(provider));
        registry.registerRaw("llmStreamingChat", new LlmStreamingChatOperator(provider));
        registry.register("MetricsFetchOperator", FETCH_METRICS);
        registry.register("QueryRunnerOperator", RUN_QUERY);
    }

    /**
     * Extracts the final {@link AgentOutput} from materialized streaming chunks.
     *
     * @param result graph result
     * @return final agent output
     */
    public static AgentOutput finalOutput(GraphResult result) {
        return chunks(result).stream()
                .filter(AgentStreamChunk.Done.class::isInstance)
                .map(AgentStreamChunk.Done.class::cast)
                .map(AgentStreamChunk.Done::finalOutput)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("streaming agent did not emit Done"));
    }

    /**
     * Returns the materialized chunks emitted by the streaming agent node.
     *
     * @param result graph result
     * @return ordered streaming chunks
     */
    @SuppressWarnings("unchecked")
    public static List<AgentStreamChunk> chunks(GraphResult result) {
        return (List<AgentStreamChunk>) result.results().getRaw(NODE_ID);
    }

    public static void main(String[] args) {
        String question = args.length > 0 ? args[0] : "How is revenue trending this week?";
        GraphResult result = execute(question);
        AgentOutput output = finalOutput(result);

        System.out.println("Success      : " + result.isSuccess());
        System.out.println("Chunks       : " + chunks(result).size());
        System.out.println("Finish reason: " + output.finishReason());
        System.out.println("Content      : " + output.content());
    }

    static StreamingAgentLoopOperator buildAgentOperator() {
        return AgentBuilder.create("dataAnalyst")
                .model("gpt-4o")
                .systemPrompt("You are a data analyst. Use tools to fetch and interpret metrics.")
                .maxTurns(8)
                .maxToolConcurrency(3)
                .temperature(0.1)
                .memory(new AgentMemoryStrategy.TokenBudget(4096))
                .streaming(true)
                .streamingBufferCapacity(32)
                .tool(new AgentToolRef(
                        "fetchMetrics",
                        "MetricsFetchOperator",
                        "Fetch time-series metrics by name",
                        Graph.builder("fetchMetricsTool")
                                .node("fetchMetrics", FETCH_METRICS)
                                    .input((results, ctx) -> toolArgsMap(ctx, "metricName", "timeRange"))
                                .build(),
                        "fetchMetrics",
                        FETCH_METRICS_TOOL
                ))
                .tool(new AgentToolRef(
                        "runQuery",
                        "QueryRunnerOperator",
                        "Run a SQL query against the analytics database",
                        Graph.builder("runQueryTool")
                                .node("runQuery", RUN_QUERY)
                                    .input((results, ctx) -> toolArgsMap(ctx, "sql"))
                                .build(),
                        "runQuery",
                        RUN_QUERY_TOOL
                ))
                .exitCondition("finish_reason == \"stop\"")
                .toStreamingOperator("DataAnalystAgent");
    }

    static LlmProvider.ToolDefinition toolDefinition(String name, String description, Map<String, Object> parameters) {
        return new LlmProvider.ToolDefinition(name, description, parameters);
    }

    static Map<String, Object> toolArgsMap(GraphContext ctx, String... keys) {
        Object raw = ctx.get("tool_args");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("tool_args must be available as a map");
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (String key : keys) {
            values.put(key, rawMap.get(key));
        }
        return values;
    }

    static final class MockStreamingAnalysisLlmProvider implements LlmProvider {
        @Override
        public LlmResponse chat(LlmRequest request) {
            return new LlmResponse("Summary generated for memory compaction.", "stop", 8, 5);
        }

        @Override
        public Stream<LlmChunk> streamChat(LlmRequest request) {
            boolean hasToolResult = request.messages().stream().anyMatch(message -> "tool".equals(message.role()));
            if (!hasToolResult) {
                return Stream.of(new LlmChunk(null, true, new ToolCall(
                        "metrics-call-1",
                        FETCH_METRICS_TOOL.name(),
                        JsonCodec.DEFAULT.serialize(Map.of(
                                "metricName", "revenue",
                                "timeRange", "last_7_days"
                        ))
                )));
            }

            Map<String, Object> payload = latestToolPayload(request);
            String trend = String.valueOf(payload.getOrDefault("trend", "unknown"));
            String change = String.valueOf(payload.getOrDefault("changePct", "0"));
            return Stream.of(
                    new LlmChunk("Revenue ", false),
                    new LlmChunk("is " + trend + " ", false),
                    new LlmChunk("by " + change + "% over the last 7 days.", true)
            );
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> latestToolPayload(LlmRequest request) {
            String content = request.messages().stream()
                    .filter(message -> "tool".equals(message.role()))
                    .map(LlmMessage::content)
                    .filter(Objects::nonNull)
                    .reduce((first, second) -> second)
                    .orElse("{}");
            Object parsed = JsonCodec.DEFAULT.deserialize(content);
            return parsed instanceof Map<?, ?> rawMap
                    ? (Map<String, Object>) rawMap
                    : Map.of();
        }
    }
}