package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.agent.builder.AgentBuilder;
import com.leanowtech.bloge.agent.engine.AgentLoopOperator;
import com.leanowtech.bloge.agent.model.AgentMemoryStrategy;
import com.leanowtech.bloge.agent.model.AgentOutput;
import com.leanowtech.bloge.agent.model.AgentToolRef;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.operators.ai.LlmChatOperator;
import com.leanowtech.bloge.operators.spi.LlmProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Fluent Java example for the dedicated {@code agent} runtime.
 *
 * <p>The example replaces the manual {@code think -> executeTool -> respond}
 * loop from {@link AiToolCallingExample} with a single agent node built through
 * {@link AgentBuilder}. The mock runtime exposes three tools:
 * {@code searchKnowledgeBase}, {@code createTicket}, and
 * {@code escalateToHuman}.</p>
 */
@SuppressWarnings("preview")
public final class AgentExample {

    static final String K_MESSAGE = "message";
    static final String NODE_ID = "assist";

    private static final Operator<Map<String, Object>, Map<String, Object>> SEARCH_KNOWLEDGE_BASE = (input, ctx) -> {
        String query = String.valueOf(input.getOrDefault("query", ""));
        String articleId = query.toLowerCase(Locale.ROOT).contains("password") ? "KB-RESET-001" : "KB-REFUND-014";
        String summary = articleId.equals("KB-RESET-001")
                ? "Reset the password from Settings > Security and confirm the email link."
                : "Refund requests can be started from Orders > Request refund within 30 days.";
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("articleId", articleId);
        result.put("summary", summary);
        result.put("query", query);
        return result;
    };

    private static final Operator<Map<String, Object>, Map<String, Object>> CREATE_TICKET = (input, ctx) -> {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", "TCK-1007");
        result.put("status", "open");
        result.put("priority", String.valueOf(input.getOrDefault("priority", "normal")));
        result.put("title", String.valueOf(input.getOrDefault("title", "Support request")));
        return result;
    };

    private static final Operator<Map<String, Object>, Map<String, Object>> ESCALATE_TO_HUMAN = (input, ctx) -> {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("queue", "human-support");
        result.put("etaMinutes", 5);
        result.put("reason", String.valueOf(input.getOrDefault("reason", "Customer requested live assistance")));
        return result;
    };

    private static final LlmProvider.ToolDefinition SEARCH_KNOWLEDGE_BASE_TOOL = toolDefinition(
            "searchKnowledgeBase",
            "Search the knowledge base for a relevant article",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string")
                    ),
                    "required", List.of("query")
            )
    );

    private static final LlmProvider.ToolDefinition CREATE_TICKET_TOOL = toolDefinition(
            "createTicket",
            "Create a support ticket for follow-up",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "title", Map.of("type", "string"),
                            "description", Map.of("type", "string"),
                            "priority", Map.of("type", "string")
                    ),
                    "required", List.of("title", "description", "priority")
            )
    );

    private static final LlmProvider.ToolDefinition ESCALATE_TO_HUMAN_TOOL = toolDefinition(
            "escalateToHuman",
            "Escalate the conversation to a human support agent",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "reason", Map.of("type", "string")
                    ),
                    "required", List.of("reason")
            )
    );

    private static final AgentLoopOperator CUSTOMER_SUPPORT_AGENT = buildAgentOperator();

    private AgentExample() {
    }

    /**
     * Builds the fluent Java graph containing one embedded agent node.
     *
     * @return graph that executes the customer-support agent
     */
    public static Graph buildGraph() {
        return Graph.builder("customerSupportAgent")
                .node(NODE_ID, CUSTOMER_SUPPORT_AGENT)
                    .input((results, ctx) -> ctx.get(K_MESSAGE, String.class))
                .build();
    }

    /**
     * Executes the example for one user message.
     *
     * @param message support question or escalation request
     * @return graph result containing the final {@link AgentOutput}
     */
    public static GraphResult execute(String message) {
        var registry = new DefaultOperatorRegistry();
        registerRuntime(registry);
        GraphEngine engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        return engine.execute(buildGraph(), new GraphContext(Map.of(K_MESSAGE, message)));
    }

    /**
     * Runs the fluent example with a default password-reset question.
     *
     * @param args optional first argument overrides the default message
     */
    public static void main(String[] args) {
        String message = args.length > 0 ? args[0] : "How do I reset my password?";
        GraphResult result = execute(message);
        AgentOutput output = result.getOutput(NODE_ID, AgentOutput.class);

        System.out.println("Success      : " + result.isSuccess());
        System.out.println("Finish reason: " + output.finishReason());
        System.out.println("Turns used   : " + output.turnsUsed());
        System.out.println("Content      : " + output.content());
        System.out.println("Tool results : " + output.toolResults());
    }

    static void registerRuntime(DefaultOperatorRegistry registry) {
        registry.register("llmChat", new LlmChatOperator(new MockAgentLlmProvider()));
        registry.register("KBSearchOperator", SEARCH_KNOWLEDGE_BASE);
        registry.register("CreateTicketOperator", CREATE_TICKET);
        registry.register("EscalateOperator", ESCALATE_TO_HUMAN);
    }

    private static AgentLoopOperator buildAgentOperator() {
        return AgentBuilder.create("customerSupport")
                .model("gpt-4o")
                .systemPrompt("You are a helpful customer support agent.")
                .maxTurns(6)
                .maxToolConcurrency(2)
                .temperature(0.2)
                .memory(new AgentMemoryStrategy.SlidingWindow(20))
                .tool(new AgentToolRef(
                        "searchKnowledgeBase",
                        "KBSearchOperator",
                        "Search the knowledge base for a relevant article",
                        Graph.builder("searchKnowledgeBaseTool")
                                .node("searchKnowledgeBase", SEARCH_KNOWLEDGE_BASE)
                                    .input((results, ctx) -> toolArgsMap(ctx, "query"))
                                .build(),
                        "searchKnowledgeBase",
                        SEARCH_KNOWLEDGE_BASE_TOOL
                ))
                .tool(new AgentToolRef(
                        "createTicket",
                        "CreateTicketOperator",
                        "Create a support ticket for follow-up",
                        Graph.builder("createTicketTool")
                                .node("createTicket", CREATE_TICKET)
                                    .input((results, ctx) -> toolArgsMap(ctx, "title", "description", "priority"))
                                .build(),
                        "createTicket",
                        CREATE_TICKET_TOOL
                ))
                .tool(new AgentToolRef(
                        "escalateToHuman",
                        "EscalateOperator",
                        "Escalate the conversation to a human support agent",
                        Graph.builder("escalateToHumanTool")
                                .node("escalateToHuman", ESCALATE_TO_HUMAN)
                                    .input((results, ctx) -> toolArgsMap(ctx, "reason"))
                                .build(),
                        "escalateToHuman",
                        ESCALATE_TO_HUMAN_TOOL
                ))
                .exitCondition("finish_reason == \"stop\" || tool_call(\"escalateToHuman\")")
                .toOperator("CustomerSupportAgent");
    }

    private static LlmProvider.ToolDefinition toolDefinition(String name, String description, Map<String, Object> parameters) {
        return new LlmProvider.ToolDefinition(name, description, parameters);
    }

    private static Map<String, Object> toolArgsMap(GraphContext ctx, String... keys) {
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

    private static final class MockAgentLlmProvider implements LlmProvider {
        @Override
        public LlmResponse chat(LlmRequest request) {
            boolean hasToolResult = request.messages().stream().anyMatch(message -> "tool".equals(message.role()));
            String userMessage = request.messages().stream()
                    .filter(message -> "user".equals(message.role()))
                    .map(LlmMessage::content)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse("How do I reset my password?");

            if (!hasToolResult) {
                return firstTurn(userMessage);
            }

            String toolPayload = request.messages().stream()
                    .filter(message -> "tool".equals(message.role()))
                    .map(LlmMessage::content)
                    .filter(Objects::nonNull)
                    .reduce((first, second) -> second)
                    .orElseThrow(() -> new IllegalArgumentException("missing tool payload"));
            Map<String, Object> payload = parseJsonObject(toolPayload);
            if (payload.containsKey("articleId")) {
                return new LlmResponse(
                        "I found %s: %s".formatted(payload.get("articleId"), payload.get("summary")),
                        "stop",
                        request.messages().size() * 10,
                        18
                );
            }
            if (payload.containsKey("ticketId")) {
                return new LlmResponse(
                        "I created ticket %s with priority %s.".formatted(payload.get("ticketId"), payload.get("priority")),
                        "stop",
                        request.messages().size() * 10,
                        16
                );
            }
            return new LlmResponse(
                    "I have escalated this conversation to a human support agent.",
                    "stop",
                    request.messages().size() * 10,
                    14
            );
        }

        @Override
        public java.util.stream.Stream<LlmChunk> streamChat(LlmRequest request) {
            throw new UnsupportedOperationException("streaming is not used in this example");
        }

        private LlmResponse firstTurn(String userMessage) {
            String normalized = userMessage.toLowerCase(Locale.ROOT);
            if (normalized.contains("human") || normalized.contains("agent")) {
                return toolResponse(
                        "escalate-call-1",
                        ESCALATE_TO_HUMAN_TOOL.name(),
                        Map.of("reason", "Customer explicitly requested a human agent")
                );
            }
            if (normalized.contains("refund") || normalized.contains("ticket")) {
                return toolResponse(
                        "ticket-call-1",
                        CREATE_TICKET_TOOL.name(),
                        Map.of(
                                "title", "Support follow-up",
                                "description", userMessage,
                                "priority", "high"
                        )
                );
            }
            return toolResponse(
                    "kb-call-1",
                    SEARCH_KNOWLEDGE_BASE_TOOL.name(),
                    Map.of("query", userMessage)
            );
        }

        private LlmResponse toolResponse(String id, String name, Map<String, Object> arguments) {
            return new LlmResponse(
                    null,
                    "tool_calls",
                    24,
                    8,
                    List.of(new ToolCall(id, name, JsonCodec.DEFAULT.serialize(arguments)))
            );
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJsonObject(String json) {
        Object parsed = JsonCodec.DEFAULT.deserialize(json);
        if (!(parsed instanceof Map<?, ?> rawMap)) {
            throw new IllegalArgumentException("Expected a JSON object payload");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
