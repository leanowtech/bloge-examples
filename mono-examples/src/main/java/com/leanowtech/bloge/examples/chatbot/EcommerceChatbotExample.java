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
 * E-commerce chatbot using the Java Fluent API (Plan A — single-round graph).
 *
 * <h2>Graph layout</h2>
 * <pre>
 * parseInput → classifyIntent → branch on intent
 *   ├── "search_product"  → productSearchSolver
 *   ├── "compare"         → productComparator
 *   ├── "recommend"       → recommendationEngine
 *   └── otherwise         → fallbackResponder
 * </pre>
 *
 * <p>The {@code place_order} path demonstrates slot-filling: when address or
 * quantity is missing the solver returns {@code resolved=false} and the external
 * loop continues collecting information.
 */
@SuppressWarnings("preview")
public class EcommerceChatbotExample {

    // ── Domain operators ──────────────────────────────────────────────────────

    static final Operator<ParsedInput, IntentResult> INTENT_CLASSIFIER = (input, ctx) -> {
        String text = input.normalizedText();
        String intent;
        if (text.contains("search") || text.contains("find") || text.contains("looking for"))
            intent = "search_product";
        else if (text.contains("compare") || text.contains("difference") || text.contains("vs"))
            intent = "compare";
        else if (text.contains("recommend") || text.contains("suggest") || text.contains("best"))
            intent = "recommend";
        else
            intent = "fallback";
        return new IntentResult(intent, 0.9, Map.of());
    };

    static final Operator<RoundInput, BotResponse> PRODUCT_SEARCH_SOLVER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "I found 5 matching products. The top result is the BloeMax Pro — "
                    + "rated 4.8 stars with free shipping.",
                    "search_product", true, input);

    static final Operator<RoundInput, BotResponse> PRODUCT_COMPARATOR = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "Comparing BloeMax Pro vs BloeMax Lite: Pro has 2x RAM and a longer battery "
                    + "life but costs $100 more.",
                    "compare", true, input);

    static final Operator<RoundInput, BotResponse> RECOMMENDATION_ENGINE = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "Based on your browsing history I recommend the BloeMax Pro. "
                    + "It's on sale today — 20% off!",
                    "recommend", true, input);

    // ── Graph builder ─────────────────────────────────────────────────────────

    /**
     * Builds the single-round e-commerce chatbot graph.
     *
     * @return configured graph
     */
    public static Graph buildGraph() {
        return Graph.builder("ecommerceChatbot")
                .node("parseInput", ChatbotCommon.INPUT_PARSER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("classifyIntent", INTENT_CLASSIFIER)
                    .dependsOn("parseInput")
                    .input((results, ctx) -> results.get("parseInput", ParsedInput.class))
                .node("productSearchSolver", PRODUCT_SEARCH_SOLVER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("productComparator", PRODUCT_COMPARATOR)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("recommendationEngine", RECOMMENDATION_ENGINE)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("fallbackResponder", ChatbotCommon.FALLBACK_RESPONDER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .branch("classifyIntent")
                    .on("intent")
                    .when(v -> "search_product".equals(v), "productSearchSolver")
                    .when(v -> "compare".equals(v),        "productComparator")
                    .when(v -> "recommend".equals(v),      "recommendationEngine")
                    .otherwise("fallbackResponder")
                .build();
    }

    /** Operator map for {@code engine.executeWithOperators()}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Operator<?, ?>> buildOperatorMap() {
        Map<String, Operator<?, ?>> ops = new HashMap<>();
        ops.put("parseInput",          (Operator) ChatbotCommon.INPUT_PARSER);
        ops.put("classifyIntent",      (Operator) INTENT_CLASSIFIER);
        ops.put("productSearchSolver", (Operator) PRODUCT_SEARCH_SOLVER);
        ops.put("productComparator",   (Operator) PRODUCT_COMPARATOR);
        ops.put("recommendationEngine",(Operator) RECOMMENDATION_ENGINE);
        ops.put("fallbackResponder",   (Operator) ChatbotCommon.FALLBACK_RESPONDER);
        return ops;
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    /**
     * Runs a multi-round e-commerce chatbot conversation.
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
                "I'm looking for a good laptop",
                "Can you compare the two models?",
                "What do you recommend for gaming?",
                "Thanks, bye"
        );

        ChatHistory history = ChatHistory.empty();
        String sessionId = "SESSION-EC-001";

        System.out.println("═══ E-commerce Chatbot ═══\n");

        for (String userMsg : messages) {
            System.out.println("User: " + userMsg);

            var roundInput = new RoundInput(userMsg, sessionId, history);
            var ctx = new GraphContext(Map.of("roundInput", roundInput));

            GraphResult result = engine.executeWithOperators(graph, ctx, ops);

            if (!result.isSuccess()) {
                System.out.println("Bot: [error]");
                continue;
            }

            BotResponse response = null;
            for (String solver : List.of("productSearchSolver", "productComparator",
                                          "recommendationEngine", "fallbackResponder")) {
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
        }

        System.out.println("\nConversation ended. History: " + history.messages().size() + " messages.");
    }
}
