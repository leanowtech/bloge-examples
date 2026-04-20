package com.leanowtech.bloge.examples.chatbot;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;

public class EcommerceChatbotReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("EcChatInputParser", EcommerceChatbotDslExample.EC_INPUT_PARSER);
        registry.register("EcChatIntentClassifier", EcommerceChatbotDslExample.EC_INTENT_CLASSIFIER);
        registry.register("EcChatProductSearchSolver", EcommerceChatbotDslExample.EC_PRODUCT_SEARCH_SOLVER);
        registry.register("EcChatProductComparator", EcommerceChatbotDslExample.EC_PRODUCT_COMPARATOR);
        registry.register("EcChatRecommendationEngine", EcommerceChatbotDslExample.EC_RECOMMENDATION_ENGINE);
        registry.register("EcChatFallbackResponder", EcommerceChatbotDslExample.EC_FALLBACK_RESPONDER);
        return new GraphLoader(registry).load(DSL);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveResponse(GraphResult result) {
        for (String solver : List.of("productSearchSolver", "productComparator", "recommendationEngine", "fallbackResponder")) {
            if (result.getStatus(solver) == NodeStatus.COMPLETED) {
                Object raw = result.results().getRaw(solver);
                if (raw instanceof Map<?, ?> m) {
                    return (Map<String, Object>) m;
                }
            }
        }
        return Map.of("responseText", "[no response]", "intent", "unknown");
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of())
                .build();

        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            ReplHelper.header("Ecommerce Chatbot REPL");
            String sessionId = ReplHelper.promptString(scanner, "sessionId", "SESSION-EC-DSL-001");
            System.out.println("Type 'exit' or 'quit' to stop.\n");

            while (true) {
                System.out.print("You: ");
                String userMessage = scanner.nextLine().trim();
                if ("exit".equalsIgnoreCase(userMessage) || "quit".equalsIgnoreCase(userMessage)) {
                    break;
                }
                if (userMessage.isEmpty()) {
                    continue;
                }

                GraphResult result = engine.execute(graph, new GraphContext(Map.of(
                        "userMessage", userMessage,
                        "sessionId", sessionId
                )));

                if (!result.isSuccess()) {
                    System.out.println("Bot: [error]");
                    continue;
                }

                Map<String, Object> response = resolveResponse(result);
                System.out.println("Bot: " + response.getOrDefault("responseText", "[no response]"));
            }
        }
    }
}
