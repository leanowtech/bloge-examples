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
 * IT Helpdesk chatbot — DSL version (Plan A — single-round graph).
 *
 * @see ItHelpdeskChatbotExample for the typed Java API version
 */
@SuppressWarnings("preview")
public class ItHelpdeskChatbotDslExample {

    // ── DSL operators (Map → Map) ─────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> IT_INPUT_PARSER =
            (input, ctx) -> {
                String msg = ((String) input.getOrDefault("userMessage", "")).toLowerCase().trim();
                List<String> entities = new ArrayList<>();
                if (msg.contains("password"))   entities.add("password");
                if (msg.contains("permission"))  entities.add("permission");
                if (msg.contains("incident"))    entities.add("incident");
                return Map.of("normalizedText", msg, "language", "en", "entities", List.copyOf(entities));
            };

    static final Operator<Map<String, Object>, Map<String, Object>> IT_INTENT_CLASSIFIER =
            (input, ctx) -> {
                String text = (String) input.getOrDefault("parsedText", "");
                String intent;
                if (text.contains("password") || text.contains("reset") || text.contains("locked"))
                    intent = "password_reset";
                else if (text.contains("permission") || text.contains("access") || text.contains("approve"))
                    intent = "permission_request";
                else if (text.contains("incident") || text.contains("down") || text.contains("broken")
                        || text.contains("error") || text.contains("crash"))
                    intent = "incident_report";
                else
                    intent = "faq";
                return Map.of("intent", intent, "confidence", 0.9);
            };

    static final Operator<Map<String, Object>, Map<String, Object>> IT_PASSWORD_RESET_HANDLER =
            (input, ctx) -> Map.of(
                    "responseText", "Password reset link sent to your registered email.",
                    "intent", "password_reset",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> IT_PERMISSION_REQUEST_HANDLER =
            (input, ctx) -> Map.of(
                    "responseText", "Permission request submitted for manager approval. You will be notified within 2 hours.",
                    "intent", "permission_request",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> IT_INCIDENT_REPORTER =
            (input, ctx) -> Map.of(
                    "responseText", "Incident ticket INC-2024-0042 created. On-call engineer paged.",
                    "intent", "incident_report",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> IT_FAQ_RESOLVER =
            (input, ctx) -> Map.of(
                    "responseText", "For general IT questions please visit helpdesk.company.com.",
                    "intent", "faq",
                    "resolved", true);

    // ── DSL source ────────────────────────────────────────────────────────────

    static final String DSL = """
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

    // ── Graph / engine helpers ────────────────────────────────────────────────

    static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("ItChatInputParser",              IT_INPUT_PARSER);
        registry.register("ItChatIntentClassifier",         IT_INTENT_CLASSIFIER);
        registry.register("ItChatPasswordResetHandler",     IT_PASSWORD_RESET_HANDLER);
        registry.register("ItChatPermissionRequestHandler", IT_PERMISSION_REQUEST_HANDLER);
        registry.register("ItChatIncidentReporter",         IT_INCIDENT_REPORTER);
        registry.register("ItChatFaqResolver",              IT_FAQ_RESOLVER);
        return new GraphLoader(registry).load(DSL);
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    /**
     * Runs a multi-round IT helpdesk chatbot using the DSL graph.
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
                "I forgot my password and got locked out",
                "I need access to the production database",
                "The application is down"
        );

        System.out.println("═══ IT Helpdesk Chatbot (DSL) ═══\n");

        for (String userMsg : messages) {
            System.out.println("User: " + userMsg);

            var ctx = new GraphContext(Map.of(
                    "userMessage", userMsg,
                    "sessionId",   "SESSION-IT-DSL-001"
            ));

            GraphResult result = engine.execute(graph, ctx);

            for (String solver : List.of("passwordResetHandler", "permissionRequestHandler",
                                          "incidentReporter", "faqResolver")) {
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
