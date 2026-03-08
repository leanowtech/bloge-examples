package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.chatbot.ChatbotCommon.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Customer-service chatbot using the Java Fluent API (Plan A — single-round graph).
 *
 * <p>Each user message is processed by executing the graph once. An external loop in
 * {@link #main(String[])} drives the multi-round conversation by passing accumulated
 * {@link ChatHistory} through the {@link com.leanowtech.bloge.core.context.GraphContext}.
 *
 * <h2>Graph layout</h2>
 * <pre>
 * parseInput → classifyIntent → branch on intent
 *   ├── "query_order"    → orderQuerySolver
 *   ├── "make_complaint" → complaintHandler
 *   ├── "faq"            → faqResolver
 *   └── otherwise        → fallbackResponder
 * </pre>
 *
 * <p>Branch targets have <em>no</em> {@code dependsOn("classifyIntent")} — the
 * {@code ConditionalEdge} already establishes the scheduling dependency.
 */
@SuppressWarnings("preview")
public class CustomerServiceChatbotExample {

    // ── Domain operators ──────────────────────────────────────────────────────

    static final Operator<ParsedInput, IntentResult> INTENT_CLASSIFIER = (input, ctx) -> {
        String text = input.normalizedText();
        String intent;
        if (text.contains("order") || text.contains("track") || text.contains("delivery"))
            intent = "query_order";
        else if (text.contains("complaint") || text.contains("upset") || text.contains("wrong"))
            intent = "make_complaint";
        else if (text.contains("how") || text.contains("what") || text.contains("policy"))
            intent = "faq";
        else
            intent = "fallback";
        return new IntentResult(intent, 0.9, Map.of());
    };

    static final Operator<RoundInput, BotResponse> ORDER_QUERY_SOLVER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "Your order is currently in transit and will arrive by tomorrow.",
                    "query_order", true, input);

    static final Operator<RoundInput, BotResponse> COMPLAINT_HANDLER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "I'm sorry to hear about your experience. I've logged your complaint "
                    + "and a manager will contact you within 24 hours.",
                    "make_complaint", true, input);

    static final Operator<RoundInput, BotResponse> FAQ_RESOLVER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "Our return policy allows returns within 30 days of purchase. "
                    + "You can initiate a return on our website.",
                    "faq", true, input);

    // ── Graph builder ─────────────────────────────────────────────────────────

    /**
     * Builds the single-round customer-service chatbot graph.
     *
     * <p>The {@code roundInput} context key must be set before each execution.
     *
     * @return configured graph
     */
    public static Graph buildGraph() {
        return Graph.builder("customerServiceChatbot")
                .node("parseInput", ChatbotCommon.INPUT_PARSER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("classifyIntent", INTENT_CLASSIFIER)
                    .dependsOn("parseInput")
                    .input((results, ctx) -> results.get("parseInput", ParsedInput.class))
                .node("orderQuerySolver", ORDER_QUERY_SOLVER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("complaintHandler", COMPLAINT_HANDLER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("faqResolver", FAQ_RESOLVER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("fallbackResponder", ChatbotCommon.FALLBACK_RESPONDER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .branch("classifyIntent")
                    .on("intent")
                    .when(v -> "query_order".equals(v),    "orderQuerySolver")
                    .when(v -> "make_complaint".equals(v), "complaintHandler")
                    .when(v -> "faq".equals(v),            "faqResolver")
                    .otherwise("fallbackResponder")
                .build();
    }

    /** Operator map for {@code engine.executeWithOperators()}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Operator<?, ?>> buildOperatorMap() {
        Map<String, Operator<?, ?>> ops = new HashMap<>();
        ops.put("parseInput",        (Operator) ChatbotCommon.INPUT_PARSER);
        ops.put("classifyIntent",    (Operator) INTENT_CLASSIFIER);
        ops.put("orderQuerySolver",  (Operator) ORDER_QUERY_SOLVER);
        ops.put("complaintHandler",  (Operator) COMPLAINT_HANDLER);
        ops.put("faqResolver",       (Operator) FAQ_RESOLVER);
        ops.put("fallbackResponder", (Operator) ChatbotCommon.FALLBACK_RESPONDER);
        return ops;
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    /**
     * Runs a multi-round customer-service chatbot conversation.
     *
     * @param args command-line arguments (unused)
     */
    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();
        Graph graph = buildGraph();
        Map<String, Operator<?, ?>> ops = buildOperatorMap();

        List<String> messages = List.of(
                "Where is my order?",
                "I want to make a complaint about my last purchase",
                "What is your return policy?",
                "Goodbye"
        );

        ChatHistory history = ChatHistory.empty();
        String sessionId = "SESSION-001";

        System.out.println("═══ Customer Service Chatbot ═══\n");

        for (String userMsg : messages) {
            System.out.println("User: " + userMsg);

            var roundInput = new RoundInput(userMsg, sessionId, history);
            var ctx = new GraphContext(Map.of("roundInput", roundInput));

            GraphResult result = engine.executeWithOperators(graph, ctx, ops);

            if (!result.isSuccess()) {
                System.out.println("Bot: [error] " + result.statusMap());
                continue;
            }

            // Find the completed branch node
            BotResponse response = null;
            for (String solver : List.of("orderQuerySolver", "complaintHandler",
                                          "faqResolver", "fallbackResponder")) {
                if (result.getStatus(solver) == NodeStatus.COMPLETED) {
                    response = result.getOutput(solver, BotResponse.class);
                    break;
                }
            }

            if (response != null) {
                System.out.println("Bot [" + response.intent() + "]: " + response.text());
                history = response.updatedHistory();
            }
            System.out.println();

            if (response != null && response.resolved()) {
                System.out.println("  (issue resolved)");
            }
        }

        System.out.println("\nConversation ended. History: " + history.messages().size() + " messages.");
    }
}
