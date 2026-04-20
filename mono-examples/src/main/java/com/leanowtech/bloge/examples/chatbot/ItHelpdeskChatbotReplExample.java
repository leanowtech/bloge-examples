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

public class ItHelpdeskChatbotReplExample {

    private static final String DSL = """

            /// IT Helpdesk chatbot — single-round intent routing
            graph itHelpdeskChatbot {

              node parseInput : ItChatInputParser {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
                output {
                  normalizedText: String
                  language: String
                }
              }

              node classifyIntent : ItChatIntentClassifier {
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
                "password_reset"     -> passwordResetHandler
                "permission_request" -> permissionRequestHandler
                "incident_report"    -> incidentReporter
                otherwise            -> faqResolver
              }

              node passwordResetHandler : ItChatPasswordResetHandler {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }

              node permissionRequestHandler : ItChatPermissionRequestHandler {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }

              node incidentReporter : ItChatIncidentReporter {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }

              node faqResolver : ItChatFaqResolver {
                input {
                  userMessage = ctx.userMessage
                  sessionId   = ctx.sessionId
                }
              }
            }
            
            """;

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ItChatInputParser", ItHelpdeskChatbotDslExample.IT_INPUT_PARSER);
        registry.register("ItChatIntentClassifier", ItHelpdeskChatbotDslExample.IT_INTENT_CLASSIFIER);
        registry.register("ItChatPasswordResetHandler", ItHelpdeskChatbotDslExample.IT_PASSWORD_RESET_HANDLER);
        registry.register("ItChatPermissionRequestHandler", ItHelpdeskChatbotDslExample.IT_PERMISSION_REQUEST_HANDLER);
        registry.register("ItChatIncidentReporter", ItHelpdeskChatbotDslExample.IT_INCIDENT_REPORTER);
        registry.register("ItChatFaqResolver", ItHelpdeskChatbotDslExample.IT_FAQ_RESOLVER);
        return new GraphLoader(registry).load(DSL);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveResponse(GraphResult result) {
        for (String solver : List.of("passwordResetHandler", "permissionRequestHandler", "incidentReporter", "faqResolver")) {
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
            ReplHelper.header("IT Helpdesk Chatbot REPL");
            String sessionId = ReplHelper.promptString(scanner, "sessionId", "SESSION-IT-DSL-001");
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
