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

public class CustomerServiceChatbotReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("CsChatInputParser", CustomerServiceChatbotDslExample.CS_INPUT_PARSER);
        registry.register("CsChatIntentClassifier", CustomerServiceChatbotDslExample.CS_INTENT_CLASSIFIER);
        registry.register("CsChatOrderQuerySolver", CustomerServiceChatbotDslExample.CS_ORDER_QUERY_SOLVER);
        registry.register("CsChatComplaintHandler", CustomerServiceChatbotDslExample.CS_COMPLAINT_HANDLER);
        registry.register("CsChatFaqResolver", CustomerServiceChatbotDslExample.CS_FAQ_RESOLVER);
        registry.register("CsChatFallbackResponder", CustomerServiceChatbotDslExample.CS_FALLBACK_RESPONDER);
        return new GraphLoader(registry).load(DSL);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveResponse(GraphResult result) {
        for (String solver : List.of("orderQuerySolver", "complaintHandler", "faqResolver", "fallbackResponder")) {
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
            ReplHelper.header("Customer Service Chatbot REPL");
            String sessionId = ReplHelper.promptString(scanner, "sessionId", "SESSION-DSL-001");
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
