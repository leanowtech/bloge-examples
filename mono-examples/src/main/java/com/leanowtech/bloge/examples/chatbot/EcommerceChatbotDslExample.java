package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * E-commerce chatbot — DSL version (Plan A — single-round graph).
 *
 * @see EcommerceChatbotExample for the typed Java API version
 */
@SuppressWarnings("preview")
public class EcommerceChatbotDslExample {

    // ── DSL operators (Map → Map) ─────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> EC_INPUT_PARSER =
            (input, ctx) -> {
                String msg = ((String) input.getOrDefault("userMessage", "")).toLowerCase().trim();
                List<String> entities = new ArrayList<>();
                if (msg.contains("product")) entities.add("product");
                if (msg.contains("order"))   entities.add("order");
                return Map.of("normalizedText", msg, "language", "en", "entities", List.copyOf(entities));
            };

    static final Operator<Map<String, Object>, Map<String, Object>> EC_INTENT_CLASSIFIER =
            (input, ctx) -> {
                String text = (String) input.getOrDefault("parsedText", "");
                String intent;
                if (text.contains("search") || text.contains("find") || text.contains("looking for"))
                    intent = "search_product";
                else if (text.contains("compare") || text.contains("vs"))
                    intent = "compare";
                else if (text.contains("recommend") || text.contains("suggest") || text.contains("best"))
                    intent = "recommend";
                else
                    intent = "fallback";
                return Map.of("intent", intent, "confidence", 0.9);
            };

    static final Operator<Map<String, Object>, Map<String, Object>> EC_PRODUCT_SEARCH_SOLVER =
            (input, ctx) -> Map.of(
                    "responseText", "I found 5 matching products. Top result: BloeMax Pro — rated 4.8★.",
                    "intent", "search_product",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> EC_PRODUCT_COMPARATOR =
            (input, ctx) -> Map.of(
                    "responseText", "BloeMax Pro has 2x RAM and a longer battery but costs $100 more.",
                    "intent", "compare",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> EC_RECOMMENDATION_ENGINE =
            (input, ctx) -> Map.of(
                    "responseText", "Based on your history I recommend the BloeMax Pro — 20% off today!",
                    "intent", "recommend",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> EC_FALLBACK_RESPONDER =
            (input, ctx) -> Map.of(
                    "responseText", "I'm sorry, I didn't understand that. Could you please rephrase?",
                    "intent", "fallback",
                    "resolved", false);

    // ── DSL source ────────────────────────────────────────────────────────────

    static final String DSL = """
            /// E-commerce chatbot — single-round intent routing
            graph ecommerceChatbot {

              node parseInput : EcChatInputParser {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
                output {
                  normalizedText: String
                  language: String
                }
              }

              node classifyIntent : EcChatIntentClassifier {
                depends_on = [parseInput]
                input {
                  parsedText = parseInput.output.normalizedText
                }
                output {
                  intent: String
                  confidence: Number
                }
              }

              branch on classifyIntent.output.intent {
                "search_product" -> productSearchSolver
                "compare"        -> productComparator
                "recommend"      -> recommendationEngine
                otherwise        -> fallbackResponder
              }

              node productSearchSolver : EcChatProductSearchSolver {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }

              node productComparator : EcChatProductComparator {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }

              node recommendationEngine : EcChatRecommendationEngine {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }

              node fallbackResponder : EcChatFallbackResponder {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }
            }
            """;

    // ── Graph / engine helpers ────────────────────────────────────────────────

    static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("EcChatInputParser",       EC_INPUT_PARSER);
        registry.register("EcChatIntentClassifier",  EC_INTENT_CLASSIFIER);
        registry.register("EcChatProductSearchSolver", EC_PRODUCT_SEARCH_SOLVER);
        registry.register("EcChatProductComparator", EC_PRODUCT_COMPARATOR);
        registry.register("EcChatRecommendationEngine", EC_RECOMMENDATION_ENGINE);
        registry.register("EcChatFallbackResponder", EC_FALLBACK_RESPONDER);
        return new GraphLoader(registry).load(DSL);
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    /**
     * Runs a multi-round e-commerce chatbot using the DSL graph.
     *
     * @param args command-line arguments (unused)
     */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();

        List<String> messages = List.of(
                "I'm looking for a good laptop",
                "Compare the two models",
                "What do you recommend for gaming?"
        );

        System.out.println("═══ E-commerce Chatbot (DSL) ═══\n");

        for (String userMsg : messages) {
            System.out.println("User: " + userMsg);

            var ctx = new GraphContext(Map.of(
                    "userMessage", userMsg,
                    "sessionId",   "SESSION-EC-DSL-001"
            ));

            GraphResult result = engine.execute(graph, ctx);

            for (String solver : List.of("productSearchSolver", "productComparator",
                                          "recommendationEngine", "fallbackResponder")) {
                if (result.getStatus(solver) == NodeStatus.COMPLETED) {
                    var resp = (Map<String, Object>) result.results().getRaw(solver);
                    System.out.println("Bot [" + resp.get("intent") + "]: " + resp.get("responseText"));
                    break;
                }
            }
            System.out.println();
        }
    }
}
