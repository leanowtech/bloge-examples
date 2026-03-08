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
 * IT Helpdesk chatbot using the Java Fluent API (Plan A — single-round graph).
 *
 * <h2>Graph layout</h2>
 * <pre>
 * parseInput → classifyIntent → branch on intent
 *   ├── "password_reset"    → passwordResetHandler
 *   ├── "permission_request"→ permissionRequestHandler
 *   ├── "incident_report"   → incidentReporter
 *   └── otherwise           → faqResolver
 * </pre>
 */
@SuppressWarnings("preview")
public class ItHelpdeskChatbotExample {

    // ── Domain operators ──────────────────────────────────────────────────────

    static final Operator<ParsedInput, IntentResult> INTENT_CLASSIFIER = (input, ctx) -> {
        String text = input.normalizedText();
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
        return new IntentResult(intent, 0.9, Map.of());
    };

    static final Operator<RoundInput, BotResponse> PASSWORD_RESET_HANDLER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "I've verified your identity and sent a password reset link to your "
                    + "registered email. Please check your inbox.",
                    "password_reset", true, input);

    static final Operator<RoundInput, BotResponse> PERMISSION_REQUEST_HANDLER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "Your permission request has been submitted for manager approval. "
                    + "You will be notified within 2 business hours.",
                    "permission_request", true, input);

    static final Operator<RoundInput, BotResponse> INCIDENT_REPORTER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "I've created incident ticket INC-2024-0042 for your issue. "
                    + "The on-call engineer has been paged and will respond within 15 minutes.",
                    "incident_report", true, input);

    static final Operator<RoundInput, BotResponse> FAQ_RESOLVER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "For general IT questions please visit our knowledge base at "
                    + "helpdesk.company.com or contact support@company.com.",
                    "faq", true, input);

    // ── Graph builder ─────────────────────────────────────────────────────────

    /**
     * Builds the single-round IT helpdesk chatbot graph.
     *
     * @return configured graph
     */
    public static Graph buildGraph() {
        return Graph.builder("itHelpdeskChatbot")
                .node("parseInput", ChatbotCommon.INPUT_PARSER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("classifyIntent", INTENT_CLASSIFIER)
                    .dependsOn("parseInput")
                    .input((results, ctx) -> results.get("parseInput", ParsedInput.class))
                .node("passwordResetHandler", PASSWORD_RESET_HANDLER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("permissionRequestHandler", PERMISSION_REQUEST_HANDLER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("incidentReporter", INCIDENT_REPORTER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .node("faqResolver", FAQ_RESOLVER)
                    .input((results, ctx) -> ctx.get("roundInput", RoundInput.class))
                .branch("classifyIntent")
                    .on("intent")
                    .when(v -> "password_reset".equals(v),    "passwordResetHandler")
                    .when(v -> "permission_request".equals(v),"permissionRequestHandler")
                    .when(v -> "incident_report".equals(v),   "incidentReporter")
                    .otherwise("faqResolver")
                .build();
    }

    /** Operator map for {@code engine.executeWithOperators()}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Operator<?, ?>> buildOperatorMap() {
        Map<String, Operator<?, ?>> ops = new HashMap<>();
        ops.put("parseInput",               (Operator) ChatbotCommon.INPUT_PARSER);
        ops.put("classifyIntent",           (Operator) INTENT_CLASSIFIER);
        ops.put("passwordResetHandler",     (Operator) PASSWORD_RESET_HANDLER);
        ops.put("permissionRequestHandler", (Operator) PERMISSION_REQUEST_HANDLER);
        ops.put("incidentReporter",         (Operator) INCIDENT_REPORTER);
        ops.put("faqResolver",              (Operator) FAQ_RESOLVER);
        return ops;
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    /**
     * Runs a multi-round IT helpdesk chatbot conversation.
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
                "I forgot my password and got locked out",
                "I need access to the production database",
                "The main application is down and throwing errors",
                "How do I configure VPN?"
        );

        ChatHistory history = ChatHistory.empty();
        String sessionId = "SESSION-IT-001";

        System.out.println("═══ IT Helpdesk Chatbot ═══\n");

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
            for (String solver : List.of("passwordResetHandler", "permissionRequestHandler",
                                          "incidentReporter", "faqResolver")) {
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
