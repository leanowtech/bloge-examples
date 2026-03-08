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
import com.leanowtech.bloge.dsl.cache.GraphRegistry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Long-running customer-service chatbot — DSL version (Plan B — suspend/resume).
 *
 * <p>Operators use {@code Map<String,Object>} I/O matching the DSL runtime.
 * Operator names use the {@code CsLr} prefix.
 *
 * @see CustomerServiceChatbotLongRunningExample for the typed Java API version
 */
@SuppressWarnings("preview")
public class CustomerServiceChatbotLongRunningDslExample {

    private static final String GRAPH_NAME = "customerServiceChatbotLongRunning";

    // ── DSL operators ─────────────────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> CS_LR_GREETER =
            (input, ctx) -> {
                String sessionId = (String) input.getOrDefault("sessionId", "");
                System.out.println("  [greet]  session=" + sessionId);
                return Map.of("greeting", "Hello! How can I help you today?", "sessionId", sessionId);
            };

    /**
     * Suspends, waiting for a user message signal.
     * The signal payload ({@code Map} with {@code userMessage} and {@code sessionId})
     * becomes this node's output.
     */
    static final SuspendableOperator<Map<String, Object>, Map<String, Object>> CS_LR_AWAIT_USER_INPUT =
            (input, ctx) -> {
                String key = (String) input.getOrDefault("sessionId", "default");
                System.out.println("  [awaitUserInput]  SUSPENDING — key='" + key + "'");
                return OperatorResult.suspend(key, null, Duration.ofMinutes(30));
            };

    static final Operator<Map<String, Object>, Map<String, Object>> CS_LR_INPUT_PARSER =
            (input, ctx) -> {
                String msg = ((String) input.getOrDefault("userMessage", "")).toLowerCase().trim();
                List<String> entities = new ArrayList<>();
                if (msg.contains("order"))   entities.add("order");
                if (msg.contains("password")) entities.add("password");
                return Map.of("normalizedText", msg, "language", "en", "entities", List.copyOf(entities));
            };

    static final Operator<Map<String, Object>, Map<String, Object>> CS_LR_INTENT_CLASSIFIER =
            (input, ctx) -> {
                String text = (String) input.getOrDefault("parsedText", "");
                String intent;
                if (text.contains("order") || text.contains("track"))
                    intent = "query_order";
                else if (text.contains("complaint") || text.contains("upset"))
                    intent = "make_complaint";
                else if (text.contains("how") || text.contains("what") || text.contains("policy"))
                    intent = "faq";
                else
                    intent = "fallback";
                return Map.of("intent", intent, "confidence", 0.9);
            };

    static final Operator<Map<String, Object>, Map<String, Object>> CS_LR_ORDER_QUERY_SOLVER =
            (input, ctx) -> Map.of(
                    "responseText", "Your order is currently in transit and will arrive by tomorrow.",
                    "intent", "query_order",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> CS_LR_COMPLAINT_HANDLER =
            (input, ctx) -> Map.of(
                    "responseText", "I've logged your complaint. A manager will contact you within 24 hours.",
                    "intent", "make_complaint",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> CS_LR_FAQ_RESOLVER =
            (input, ctx) -> Map.of(
                    "responseText", "Our return policy allows returns within 30 days of purchase.",
                    "intent", "faq",
                    "resolved", true);

    static final Operator<Map<String, Object>, Map<String, Object>> CS_LR_FALLBACK_RESPONDER =
            (input, ctx) -> Map.of(
                    "responseText", "I'm sorry, I didn't understand that. Could you please rephrase?",
                    "intent", "fallback",
                    "resolved", false);

    // ── DSL source ────────────────────────────────────────────────────────────

    static final String DSL = """
            /// Long-running customer-service chatbot (Plan B — suspend/resume)
            graph customerServiceChatbotLongRunning {

              /// Greet the user and return a welcome message
              node greet : CsLrGreeter {
                input {
                  sessionId = ctx.sessionId
                }
                output {
                  greeting: String
                  sessionId: String
                }
              }

              /// Suspend execution and wait for user input via engine.signal()
              node awaitUserInput : CsLrAwaitUserInput {
                depends_on = [greet]
                input {
                  sessionId = greet.output.sessionId
                }
                output {
                  userMessage: String
                  sessionId: String
                }
              }

              /// Parse the user message received via signal
              node parseInput : CsLrInputParser {
                depends_on = [awaitUserInput]
                input {
                  userMessage = awaitUserInput.output.userMessage
                  sessionId   = awaitUserInput.output.sessionId
                }
                output {
                  normalizedText: String
                  language: String
                }
              }

              /// Classify the user intent
              node classifyIntent : CsLrIntentClassifier {
                depends_on = [parseInput]
                input {
                  parsedText = parseInput.output.normalizedText
                }
                output {
                  intent: String
                  confidence: Number
                }
              }

              /// Route to the appropriate solver
              branch on classifyIntent.output.intent {
                "query_order"    -> orderQuerySolver
                "make_complaint" -> complaintHandler
                "faq"            -> faqResolver
                otherwise        -> fallbackResponder
              }

              node orderQuerySolver : CsLrOrderQuerySolver {
                input {
                  userMessage = awaitUserInput.output.userMessage
                  sessionId   = awaitUserInput.output.sessionId
                }
              }

              node complaintHandler : CsLrComplaintHandler {
                input {
                  userMessage = awaitUserInput.output.userMessage
                  sessionId   = awaitUserInput.output.sessionId
                }
              }

              node faqResolver : CsLrFaqResolver {
                input {
                  userMessage = awaitUserInput.output.userMessage
                  sessionId   = awaitUserInput.output.sessionId
                }
              }

              node fallbackResponder : CsLrFallbackResponder {
                input {
                  userMessage = awaitUserInput.output.userMessage
                  sessionId   = awaitUserInput.output.sessionId
                }
              }
            }
            """;

    // ── Graph / engine helpers ────────────────────────────────────────────────

    static GraphRegistry buildGraphRegistry(DefaultOperatorRegistry registry) {
        registry.register("CsLrGreeter",          CS_LR_GREETER);
        registry.registerRaw("CsLrAwaitUserInput",   CS_LR_AWAIT_USER_INPUT);
        registry.register("CsLrInputParser",      CS_LR_INPUT_PARSER);
        registry.register("CsLrIntentClassifier", CS_LR_INTENT_CLASSIFIER);
        registry.register("CsLrOrderQuerySolver", CS_LR_ORDER_QUERY_SOLVER);
        registry.register("CsLrComplaintHandler", CS_LR_COMPLAINT_HANDLER);
        registry.register("CsLrFaqResolver",      CS_LR_FAQ_RESOLVER);
        registry.register("CsLrFallbackResponder",CS_LR_FALLBACK_RESPONDER);
        GraphRegistry graphRegistry = new GraphRegistry(registry);
        graphRegistry.register(GRAPH_NAME, DSL);
        return graphRegistry;
    }

    static Graph buildGraph(DefaultOperatorRegistry registry) {
        return buildGraphRegistry(registry).get(GRAPH_NAME);
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    /**
     * Demonstrates the long-running suspend/resume lifecycle using the DSL graph.
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

        GraphRegistry graphRegistry = buildGraphRegistry(registry);
        Graph graph = graphRegistry.get(GRAPH_NAME);
        Graph reusedGraph = graphRegistry.get(GRAPH_NAME);
        System.out.println("Graph cache reused instance: " + (graph == reusedGraph));
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        var ctx = new GraphContext(Map.of("sessionId", "SESSION-LR-DSL-001"));

        System.out.println("═══ Long-Running Customer Service Chatbot (DSL) ═══\n");
        System.out.println("Phase 1: starting graph...");

        CompletableFuture<GraphResult> resultFuture = new CompletableFuture<>();
        Thread.ofVirtual().start(() ->
                resultFuture.complete(engine.execute(graph, ctx)));

        boolean didSuspend = suspended.await(5, TimeUnit.SECONDS);
        System.out.println("Greeting: Hello! How can I help you today?");
        System.out.println("Suspended: " + didSuspend);

        String userMessage = "Where is my order?";
        System.out.println("\nPhase 2: user says: \"" + userMessage + "\"");

        String execId = execIdRef.get();
        engine.signal(execId, "awaitUserInput",
                Map.of("userMessage", userMessage, "sessionId", "SESSION-LR-DSL-001"));

        GraphResult result = resultFuture.get(5, TimeUnit.SECONDS);

        System.out.println("\nPhase 3: graph completed");
        System.out.println("Success: " + result.isSuccess());
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }

        for (String solver : List.of("orderQuerySolver", "complaintHandler",
                                      "faqResolver", "fallbackResponder")) {
            if (result.getStatus(solver) == NodeStatus.COMPLETED) {
                var resp = (Map<String, Object>) result.results().getRaw(solver);
                System.out.println("\nBot [" + resp.get("intent") + "]: " + resp.get("responseText"));
                break;
            }
        }
    }
}
