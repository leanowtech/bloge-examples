package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.chatbot.ChatbotCommon.*;

import java.util.List;
import java.util.Map;

/**
 * Customer-service chatbot — DSL version (Plan A — single-round graph).
 *
 * <p>Operators use {@code Map<String,Object>} I/O matching the DSL runtime convention.
 * Operator names use the {@code CsChat} prefix to avoid registry collisions.
 *
 * @see CustomerServiceChatbotExample for the typed Java API version
 */
@SuppressWarnings("preview")
public class CustomerServiceChatbotDslExample {

    // ── DSL operators (Map → Map) ─────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> CS_INPUT_PARSER =
            (input, ctx) -> {
                String msg = ((String) input.getOrDefault("userMessage", "")).toLowerCase().trim();
                List<String> entities = new java.util.ArrayList<>();
                if (msg.contains("order")) entities.add("order");
                if (msg.contains("password")) entities.add("password");
                return Map.of("normalizedText", msg, "language", "en", "entities", List.copyOf(entities));
            };

    static final Operator<Map<String, Object>, Map<String, Object>> CS_INTENT_CLASSIFIER =
            (input, ctx) -> {
                String text = (String) input.getOrDefault("parsedText", "");
                String intent;
                if (text.contains("order") || text.contains("track") || text.contains("delivery"))
                    intent = "query_order";
                else if (text.contains("complaint") || text.contains("upset") || text.contains("wrong"))
                    intent = "make_complaint";
                else if (text.contains("how") || text.contains("what") || text.contains("policy"))
                    intent = "faq";
                else
                    intent = "fallback";
                return Map.of("intent", intent, "confidence", 0.9);
            };

    static final Operator<Map<String, Object>, Map<String, Object>> CS_ORDER_QUERY_SOLVER =
            (input, ctx) -> Map.of(
                    "responseText", "Your order is currently in transit and will arrive by tomorrow.",
                    "intent", "query_order",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> CS_COMPLAINT_HANDLER =
            (input, ctx) -> Map.of(
                    "responseText", "I'm sorry to hear about your experience. "
                            + "I've logged your complaint and a manager will contact you within 24 hours.",
                    "intent", "make_complaint",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> CS_FAQ_RESOLVER =
            (input, ctx) -> Map.of(
                    "responseText", "Our return policy allows returns within 30 days of purchase.",
                    "intent", "faq",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> CS_FALLBACK_RESPONDER =
            (input, ctx) -> Map.of(
                    "responseText", "I'm sorry, I didn't understand that. Could you please rephrase?",
                    "intent", "fallback",
                    "resolved", false);

    // ── DSL source ────────────────────────────────────────────────────────────

    static final String DSL = """
            /// Customer-service chatbot — single-round intent routing
            graph customerServiceChatbot {

              /// Normalise the raw user message
              node parseInput : CsChatInputParser {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
                output {
                  normalizedText: String
                  language: String
                }
              }

              /// Classify the intent from the normalised text
              node classifyIntent : CsChatIntentClassifier {
                depends_on = [parseInput]
                input {
                  parsedText = parseInput.output.normalizedText
                }
                output {
                  intent: String
                  confidence: Number
                }
              }

              /// Branch exactly one solver based on the classified intent
              branch on classifyIntent.output.intent {
                "query_order"    -> orderQuerySolver
                "make_complaint" -> complaintHandler
                "faq"            -> faqResolver
                otherwise        -> fallbackResponder
              }

              node orderQuerySolver : CsChatOrderQuerySolver {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }

              node complaintHandler : CsChatComplaintHandler {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }

              node faqResolver : CsChatFaqResolver {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }

              node fallbackResponder : CsChatFallbackResponder {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }
            }
            """;

    // ── Graph / engine helpers ────────────────────────────────────────────────

    static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("CsChatInputParser",      CS_INPUT_PARSER);
        registry.register("CsChatIntentClassifier", CS_INTENT_CLASSIFIER);
        registry.register("CsChatOrderQuerySolver", CS_ORDER_QUERY_SOLVER);
        registry.register("CsChatComplaintHandler", CS_COMPLAINT_HANDLER);
        registry.register("CsChatFaqResolver",      CS_FAQ_RESOLVER);
        registry.register("CsChatFallbackResponder",CS_FALLBACK_RESPONDER);
        return new GraphLoader(registry).load(DSL);
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    /**
     * Runs a multi-round customer-service chatbot conversation using the DSL graph.
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
                "Where is my order?",
                "I want to make a complaint",
                "What is your return policy?",
                "Goodbye"
        );

        System.out.println("═══ Customer Service Chatbot (DSL) ═══\n");

        for (String userMsg : messages) {
            System.out.println("User: " + userMsg);

            var ctx = new GraphContext(Map.of(
                    "userMessage", userMsg,
                    "sessionId",   "SESSION-DSL-001"
            ));

            GraphResult result = engine.execute(graph, ctx);

            if (!result.isSuccess()) {
                System.out.println("Bot: [error]");
                continue;
            }

            for (String solver : List.of("orderQuerySolver", "complaintHandler",
                                          "faqResolver", "fallbackResponder")) {
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
