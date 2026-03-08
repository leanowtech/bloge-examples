package com.leanowtech.bloge.examples.chatbot;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.ExecutionListener;
import com.leanowtech.bloge.examples.chatbot.ChatbotCommon.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Long-running customer-service chatbot (Plan B — suspend/resume).
 *
 * <p>The entire conversation runs as a single graph execution.  After the greeting
 * node completes, the graph <em>suspends</em> at {@code awaitUserInput} and waits
 * for an external {@link GraphEngine#signal} call to supply the user's message.
 *
 * <h2>Graph layout</h2>
 * <pre>
 * greet → awaitUserInput [SUSPEND]
 *              ↓  (signal payload = {userMessage, sessionId})
 *         parseInput → classifyIntent → branch on intent
 *           ├── "query_order"    → orderQuerySolver
 *           ├── "make_complaint" → complaintHandler
 *           ├── "faq"            → faqResolver
 *           └── otherwise        → fallbackResponder
 * </pre>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Run {@code engine.executeWithOperators(graph, ctx, ops)} on a virtual thread —
 *       this blocks until the graph suspends.</li>
 *   <li>Detect suspension via {@link ExecutionListener#onNodeSuspended}.</li>
 *   <li>Call {@code engine.signal(execId, "awaitUserInput", payload)} where
 *       {@code payload} is a {@code Map} with {@code userMessage} and
 *       {@code sessionId}.</li>
 *   <li>The graph resumes; the signal payload becomes the output of
 *       {@code awaitUserInput} and flows to {@code parseInput}.</li>
 * </ol>
 */
@SuppressWarnings("preview")
public class CustomerServiceChatbotLongRunningExample {

    // ── Records ───────────────────────────────────────────────────────────────

    public record GreetResult(String greeting, String sessionId) {}

    /** Signal payload supplied by the external caller when the user submits a message. */
    public record UserMessagePayload(String userMessage, String sessionId) {}

    // ── Operators ─────────────────────────────────────────────────────────────

    static final Operator<String, GreetResult> GREETER = (sessionId, ctx) -> {
        System.out.println("  [greet]  session=" + sessionId);
        return new GreetResult("Hello! How can I help you today?", sessionId);
    };

    /**
     * Returns a suspended result and waits for a user message via {@code engine.signal()}.
     * The signal payload ({@link UserMessagePayload}) becomes this node's output.
     */
    static final SuspendableOperator<GreetResult, UserMessagePayload> AWAIT_USER_INPUT = (input, ctx) -> {
        String key = input.sessionId();
        System.out.println("  [awaitUserInput]  SUSPENDING — key='" + key + "'");
        return OperatorResult.suspend(key, null, Duration.ofMinutes(30));
    };

    static final Operator<UserMessagePayload, ParsedInput> INPUT_PARSER_LR = (input, ctx) -> {
        String msg = input.userMessage().toLowerCase().trim();
        List<String> entities = new java.util.ArrayList<>();
        if (msg.contains("order"))   entities.add("order");
        if (msg.contains("password")) entities.add("password");
        return new ParsedInput(msg, "en", List.copyOf(entities));
    };

    static final Operator<ParsedInput, IntentResult> INTENT_CLASSIFIER = (input, ctx) -> {
        String text = input.normalizedText();
        String intent;
        if (text.contains("order") || text.contains("track")) intent = "query_order";
        else if (text.contains("complaint") || text.contains("upset")) intent = "make_complaint";
        else if (text.contains("how") || text.contains("what") || text.contains("policy")) intent = "faq";
        else intent = "fallback";
        return new IntentResult(intent, 0.9, Map.of());
    };

    static final Operator<UserMessagePayload, BotResponse> ORDER_QUERY_SOLVER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "Your order is currently in transit and will arrive by tomorrow.",
                    "query_order", true,
                    new RoundInput(input.userMessage(), input.sessionId(), ChatHistory.empty()));

    static final Operator<UserMessagePayload, BotResponse> COMPLAINT_HANDLER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "I've logged your complaint. A manager will contact you within 24 hours.",
                    "make_complaint", true,
                    new RoundInput(input.userMessage(), input.sessionId(), ChatHistory.empty()));

    static final Operator<UserMessagePayload, BotResponse> FAQ_RESOLVER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "Our return policy allows returns within 30 days of purchase.",
                    "faq", true,
                    new RoundInput(input.userMessage(), input.sessionId(), ChatHistory.empty()));

    static final Operator<UserMessagePayload, BotResponse> FALLBACK_RESPONDER = (input, ctx) ->
            ChatbotCommon.makeBotResponse(
                    "I'm sorry, I didn't understand that. Could you please rephrase?",
                    "fallback", false,
                    new RoundInput(input.userMessage(), input.sessionId(), ChatHistory.empty()));

    // ── Graph builder ─────────────────────────────────────────────────────────

    /**
     * Builds the long-running customer-service chatbot graph.
     *
     * @return configured graph
     */
    public static Graph buildGraph() {
        return Graph.builder("customerServiceChatbotLongRunning")
                .node("greet", GREETER)
                    .input((results, ctx) -> ctx.get("sessionId", String.class))
                .suspendNode("awaitUserInput", AWAIT_USER_INPUT)
                    .dependsOn("greet")
                    .input((results, ctx) -> results.get("greet", GreetResult.class))
                .node("parseInput", INPUT_PARSER_LR)
                    .dependsOn("awaitUserInput")
                    .input((results, ctx) -> results.get("awaitUserInput", UserMessagePayload.class))
                .node("classifyIntent", INTENT_CLASSIFIER)
                    .dependsOn("parseInput")
                    .input((results, ctx) -> results.get("parseInput", ParsedInput.class))
                .node("orderQuerySolver", ORDER_QUERY_SOLVER)
                    .input((results, ctx) -> results.get("awaitUserInput", UserMessagePayload.class))
                .node("complaintHandler", COMPLAINT_HANDLER)
                    .input((results, ctx) -> results.get("awaitUserInput", UserMessagePayload.class))
                .node("faqResolver", FAQ_RESOLVER)
                    .input((results, ctx) -> results.get("awaitUserInput", UserMessagePayload.class))
                .node("fallbackResponder", FALLBACK_RESPONDER)
                    .input((results, ctx) -> results.get("awaitUserInput", UserMessagePayload.class))
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
    public static Map<String, Object> buildOperatorMap() {
        Map<String, Object> ops = new HashMap<>();
        ops.put("greet",               (Operator) GREETER);
        ops.put("awaitUserInput",      AWAIT_USER_INPUT);
        ops.put("parseInput",          (Operator) INPUT_PARSER_LR);
        ops.put("classifyIntent",      (Operator) INTENT_CLASSIFIER);
        ops.put("orderQuerySolver",    (Operator) ORDER_QUERY_SOLVER);
        ops.put("complaintHandler",    (Operator) COMPLAINT_HANDLER);
        ops.put("faqResolver",         (Operator) FAQ_RESOLVER);
        ops.put("fallbackResponder",   (Operator) FALLBACK_RESPONDER);
        return ops;
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    /**
     * Demonstrates the long-running suspend/resume lifecycle.
     *
     * @param args command-line arguments (unused)
     */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var registry = new DefaultOperatorRegistry();

        CountDownLatch suspended = new CountDownLatch(1);
        AtomicReference<String> execIdRef = new AtomicReference<>();

        ExecutionListener listener = new ExecutionListener() {
            @Override
            public void onGraphStart(String graphName, GraphContext ctx) {
                execIdRef.set((String) ctx.get(ReservedKeys.EXECUTION_ID));
            }
            @Override
            public void onNodeSuspended(String graphName, String nodeId, String suspendKey) {
                if ("awaitUserInput".equals(nodeId)) {
                    System.out.println("  ── graph suspended at " + nodeId + " ──");
                    suspended.countDown();
                }
            }
        };

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        Graph graph = buildGraph();
        Map<String, Object> ops = buildOperatorMap();

        var ctx = new GraphContext(Map.of("sessionId", "SESSION-LR-001"));

        System.out.println("═══ Long-Running Customer Service Chatbot ═══\n");
        System.out.println("Phase 1: starting graph...");

        // Run graph on a virtual thread so main thread is free to signal
        CompletableFuture<GraphResult> resultFuture = new CompletableFuture<>();
        Thread.ofVirtual().start(() ->
                resultFuture.complete(engine.executeWithOperators(graph, ctx, ops)));

        // Wait for the awaitUserInput node to suspend
        boolean didSuspend = suspended.await(5, TimeUnit.SECONDS);
        System.out.println("Greeting: Hello! How can I help you today?");
        System.out.println("Suspended: " + didSuspend);

        // Phase 2: user sends a message
        String userMessage = "Where is my order?";
        System.out.println("\nPhase 2: user says: \"" + userMessage + "\"");

        String execId = execIdRef.get();
        // Signal carries the user message payload (Map for engine compatibility)
        engine.signal(execId, "awaitUserInput",
                new UserMessagePayload(userMessage, "SESSION-LR-001"));

        // Wait for graph to complete
        GraphResult result = resultFuture.get(5, TimeUnit.SECONDS);

        System.out.println("\nPhase 3: graph completed");
        System.out.println("Success: " + result.isSuccess());
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }

        // Find the completed solver
        for (String solver : List.of("orderQuerySolver", "complaintHandler",
                                      "faqResolver", "fallbackResponder")) {
            if (result.getStatus(solver) == NodeStatus.COMPLETED) {
                BotResponse response = result.getOutput(solver, BotResponse.class);
                System.out.println("\nBot [" + response.intent() + "]: " + response.text());
                break;
            }
        }
    }
}
