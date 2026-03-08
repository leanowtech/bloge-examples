package com.leanowtech.bloge.examples.chatbot;

import java.nio.charset.StandardCharsets;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.core.spi.ExecutionListener;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.ReplHelper;

import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class CustomerServiceChatbotLongRunningReplExample {

    private static final String DSL = """

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

    private static Graph buildGraph(DefaultOperatorRegistry registry) {
        registry.register("CsLrGreeter", CustomerServiceChatbotLongRunningDslExample.CS_LR_GREETER);
        registry.registerRaw("CsLrAwaitUserInput", CustomerServiceChatbotLongRunningDslExample.CS_LR_AWAIT_USER_INPUT);
        registry.register("CsLrInputParser", CustomerServiceChatbotLongRunningDslExample.CS_LR_INPUT_PARSER);
        registry.register("CsLrIntentClassifier", CustomerServiceChatbotLongRunningDslExample.CS_LR_INTENT_CLASSIFIER);
        registry.register("CsLrOrderQuerySolver", CustomerServiceChatbotLongRunningDslExample.CS_LR_ORDER_QUERY_SOLVER);
        registry.register("CsLrComplaintHandler", CustomerServiceChatbotLongRunningDslExample.CS_LR_COMPLAINT_HANDLER);
        registry.register("CsLrFaqResolver", CustomerServiceChatbotLongRunningDslExample.CS_LR_FAQ_RESOLVER);
        registry.register("CsLrFallbackResponder", CustomerServiceChatbotLongRunningDslExample.CS_LR_FALLBACK_RESPONDER);
        return new GraphLoader(registry).load(DSL);
    }

    @SuppressWarnings("unchecked")
    private static String runRound(String sessionId, String userMessage) throws Exception {
        var registry = new DefaultOperatorRegistry();
        Graph graph = buildGraph(registry);

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
                    suspended.countDown();
                }
            }
        };

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();
        var ctx = new GraphContext(Map.of("sessionId", sessionId));

        CompletableFuture<GraphResult> resultFuture = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> resultFuture.complete(engine.execute(graph, ctx)));

        if (!suspended.await(5, TimeUnit.SECONDS)) {
            return "[suspend timeout]";
        }

        String execId = execIdRef.get();
        engine.signal(execId, "awaitUserInput", Map.of("userMessage", userMessage, "sessionId", sessionId));

        GraphResult result = resultFuture.get(5, TimeUnit.SECONDS);
        for (String solver : List.of("orderQuerySolver", "complaintHandler", "faqResolver", "fallbackResponder")) {
            if (result.getStatus(solver) == NodeStatus.COMPLETED) {
                Object raw = result.results().getRaw(solver);
                if (raw instanceof Map<?, ?> m) {
                    Object text = m.get("responseText");
                    return text == null ? "[no response]" : text.toString();
                }
            }
        }
        return "[no response]";
    }

    public static void main(String[] args) throws Exception {
        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            ReplHelper.header("Customer Service Chatbot Long Running REPL");
            String sessionId = ReplHelper.promptString(scanner, "sessionId", "SESSION-LR-DSL-001");
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
                System.out.println("Bot: " + runRound(sessionId, userMessage));
            }
        }
    }
}
