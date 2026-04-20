package com.leanowtech.bloge.examples.customerservice;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode.GraphDef;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.dsl.lexer.Lexer;
import com.leanowtech.bloge.dsl.parser.Parser;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DSL version of the smart ticket handling pipeline with sequential sub-graphs and branching.
 * <p>
 * Sub-graphs are built via Java API and registered with the DslCompiler,
 * then referenced in DSL using {@code subgraph("name")} syntax.
 */
@SuppressWarnings("preview")
public class SmartTicketHandlingDslExample {

    // --- Main graph operators (Map-based for DSL) ---

    static final Operator<Map<String, Object>, Map<String, Object>> RECEIVE_TICKET = (input, ctx) -> {
        Thread.sleep(25);
        return Map.of(
                "ticketId", input.get("ticketId"),
                "customerId", input.get("customerId"),
                "channel", input.get("channel"),
                "message", input.get("message"),
                "status", "RECEIVED");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CLASSIFY_INTENT = (input, ctx) -> {
        Thread.sleep(45);
        String message = ((String) input.get("message")).toLowerCase();
        String intent = message.contains("refund") ? "refund_request"
                : message.contains("broken") || message.contains("defect") ? "product_defect"
                : message.contains("billing") ? "billing_inquiry"
                : "general_inquiry";
        double confidence = intent.equals("general_inquiry") ? 0.65 : 0.92;
        return Map.of(
                "ticketId", input.get("ticketId"),
                "intent", intent,
                "confidence", confidence,
                "category", intent.contains("product") ? "product" : "account");
    };

    @SuppressWarnings("unchecked")
    static final Operator<Map<String, Object>, Map<String, Object>> DETERMINE_PRIORITY = (input, ctx) -> {
        Thread.sleep(20);
        var sentiment = (Map<String, Object>) input.get("sentiment");
        double sentimentScore = ((Number) sentiment.get("sentimentScore")).doubleValue();
        String sentimentLabel = (String) sentiment.get("sentimentLabel");
        String intent = (String) input.get("intent");
        String priority;
        String reason;
        if (sentimentScore < -0.5 || "refund_request".equals(intent)) {
            priority = "high";
            reason = "Negative sentiment (" + sentimentLabel + ") or escalation-worthy intent";
        } else if (sentimentScore < 0.0) {
            priority = "medium";
            reason = "Mildly negative sentiment requires attention";
        } else {
            priority = "low";
            reason = "Positive/neutral sentiment, standard handling";
        }
        return Map.of(
                "ticketId", input.get("ticketId"),
                "priority", priority,
                "reason", reason);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> GENERATE_REPLY = (input, ctx) -> {
        Thread.sleep(30);
        String replyText = "Dear customer, regarding ticket " + input.get("ticketId")
                + ": your " + input.get("intent") + " has been processed with " + input.get("priority")
                + " priority. Your request is being handled.";
        return Map.of(
                "ticketId", input.get("ticketId"),
                "customerId", input.get("customerId"),
                "replyText", replyText,
                "channel", "email",
                "status", "SENT");
    };

    // --- Sentiment analysis sub-graph operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> TEXT_PREPROCESSING = (input, ctx) -> {
        Thread.sleep(30);
        String raw = (String) input.get("rawText");
        String cleaned = raw.replaceAll("[^a-zA-Z0-9\\s.,!?]", "").trim().toLowerCase();
        int tokenCount = cleaned.split("\\s+").length;
        return Map.of(
                "ticketId", input.get("ticketId"),
                "cleanedText", cleaned,
                "tokenCount", tokenCount,
                "language", "en");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> NLP_CLASSIFICATION = (input, ctx) -> {
        Thread.sleep(80);
        String text = (String) input.get("cleanedText");
        boolean hasNegative = text.contains("broken") || text.contains("terrible") || text.contains("refund");
        double positive = hasNegative ? 0.1 : 0.7;
        double negative = hasNegative ? 0.75 : 0.1;
        double neutral = 1.0 - positive - negative;
        String sentiment = negative > 0.5 ? "negative" : positive > 0.5 ? "positive" : "neutral";
        return Map.of(
                "ticketId", input.get("ticketId"),
                "sentiment", sentiment,
                "positive", positive,
                "negative", negative,
                "neutral", neutral);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SENTIMENT_SCORING = (input, ctx) -> {
        Thread.sleep(20);
        double positive = ((Number) input.get("positive")).doubleValue();
        double negative = ((Number) input.get("negative")).doubleValue();
        double composite = positive - negative;
        String label = composite > 0.3 ? "positive" : composite < -0.3 ? "negative" : "neutral";
        return Map.of(
                "ticketId", input.get("ticketId"),
                "compositeScore", Math.round(composite * 100.0) / 100.0,
                "label", label);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> PRIORITY_ASSIGNMENT = (input, ctx) -> {
        Thread.sleep(15);
        double compositeScore = ((Number) input.get("compositeScore")).doubleValue();
        String suggested = compositeScore < -0.3 ? "high" : compositeScore < 0.0 ? "medium" : "low";
        return Map.of(
                "ticketId", input.get("ticketId"),
                "sentimentLabel", input.get("sentiment"),
                "sentimentScore", input.get("compositeScore"),
                "suggestedPriority", suggested);
    };

    // --- Escalation workflow sub-graph operators ---

    static final Operator<Map<String, Object>, Map<String, Object>> SUPERVISOR_NOTIFICATION = (input, ctx) -> {
        Thread.sleep(40);
        String ticketId = (String) input.get("ticketId");
        return Map.of(
                "ticketId", ticketId,
                "supervisorId", "SUP-" + ticketId.hashCode() % 100,
                "notified", true,
                "notificationChannel", "slack");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SLA_CHECK = (input, ctx) -> {
        Thread.sleep(35);
        int slaMinutes = "high".equals(input.get("priority")) ? 30 : 120;
        return Map.of(
                "ticketId", input.get("ticketId"),
                "slaMinutes", slaMinutes,
                "slaDeadline", "2025-01-15T12:00:00Z",
                "withinSla", true);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ESCALATION_ROUTING = (input, ctx) -> {
        Thread.sleep(25);
        return Map.of(
                "ticketId", input.get("ticketId"),
                "assignedTeam", "tier-2-support",
                "escalationLevel", "L2",
                "queuePosition", "Q-" + input.get("ticketId"));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CUSTOMER_CALLBACK_SCHEDULE = (input, ctx) -> {
        Thread.sleep(30);
        return Map.of(
                "ticketId", input.get("ticketId"),
                "callbackTime", "2025-01-15T14:00:00Z",
                "callbackNumber", "+1-555-0100",
                "confirmationId", "CB-" + input.get("ticketId"));
    };

    // --- Sub-graph construction (Java API, Map-based) ---

    public static Graph buildSentimentAnalysisSubGraph() {
        return Graph.builder("sentiment-analysis")
                .node("textPreprocessing", TEXT_PREPROCESSING)
                    .input((results, ctx) -> Map.of(
                            "ticketId", ctx.get("ticketId", String.class),
                            "rawText", ctx.get("message", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("nlpClassification", NLP_CLASSIFICATION)
                    .dependsOn("textPreprocessing")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var preprocess = (Map<String, Object>) results.getRaw("textPreprocessing");
                        return Map.of(
                                "ticketId", ctx.get("ticketId", String.class),
                                "cleanedText", preprocess.get("cleanedText"));
                    })
                    .retry(2, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                    .timeout(Duration.ofSeconds(10))
                .node("sentimentScoring", SENTIMENT_SCORING)
                    .dependsOn("nlpClassification")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var nlp = (Map<String, Object>) results.getRaw("nlpClassification");
                        return Map.of(
                                "ticketId", ctx.get("ticketId", String.class),
                                "positive", nlp.get("positive"),
                                "negative", nlp.get("negative"),
                                "neutral", nlp.get("neutral"));
                    })
                .node("priorityAssignment", PRIORITY_ASSIGNMENT)
                    .dependsOn("sentimentScoring", "nlpClassification")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var nlp = (Map<String, Object>) results.getRaw("nlpClassification");
                        @SuppressWarnings("unchecked")
                        var scoring = (Map<String, Object>) results.getRaw("sentimentScoring");
                        return Map.of(
                                "ticketId", ctx.get("ticketId", String.class),
                                "sentiment", nlp.get("sentiment"),
                                "compositeScore", scoring.get("compositeScore"),
                                "intent", ctx.get("intent", String.class));
                    })
                .build();
    }

    public static Graph buildEscalationWorkflowSubGraph() {
        return Graph.builder("escalation-workflow")
                .node("supervisorNotification", SUPERVISOR_NOTIFICATION)
                    .input((results, ctx) -> Map.of(
                            "ticketId", ctx.get("ticketId", String.class),
                            "customerId", ctx.get("customerId", String.class),
                            "priority", ctx.get("priority", String.class),
                            "reason", ctx.get("reason", String.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.FIXED)
                .node("slaCheck", SLA_CHECK)
                    .dependsOn("supervisorNotification")
                    .input((results, ctx) -> Map.of(
                            "ticketId", ctx.get("ticketId", String.class),
                            "priority", ctx.get("priority", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("escalationRouting", ESCALATION_ROUTING)
                    .dependsOn("slaCheck")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var supervisor = (Map<String, Object>) results.getRaw("supervisorNotification");
                        return Map.of(
                                "ticketId", ctx.get("ticketId", String.class),
                                "supervisorId", supervisor.get("supervisorId"),
                                "priority", ctx.get("priority", String.class));
                    })
                .node("customerCallbackSchedule", CUSTOMER_CALLBACK_SCHEDULE)
                    .dependsOn("escalationRouting")
                    .input((results, ctx) -> {
                        @SuppressWarnings("unchecked")
                        var routing = (Map<String, Object>) results.getRaw("escalationRouting");
                        return Map.of(
                                "ticketId", ctx.get("ticketId", String.class),
                                "customerId", ctx.get("customerId", String.class),
                                "assignedTeam", routing.get("assignedTeam"));
                    })
                .build();
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // Register main graph operators
        // RECEIVE_TICKET: reads ctx.ticketId/customerId/channel/message → {ticketId, customerId, channel, message, status}
        registry.register("ReceiveTicketOperator", RECEIVE_TICKET);
        // CLASSIFY_INTENT: reads receiveTicket.message → {ticketId, intent, confidence, category}
        registry.register("ClassifyIntentOperator", CLASSIFY_INTENT);
        // DETERMINE_PRIORITY: reads sentiment + intent → {ticketId, priority, reason}
        registry.register("DeterminePriorityOperator", DETERMINE_PRIORITY);
        // GENERATE_REPLY: reads ticketId/customerId/intent/priority → {ticketId, customerId, replyText, channel, status}
        registry.register("GenerateReplyOperator", GENERATE_REPLY);

        // Register sub-graph operators (resolved by operatorRef = node ID for lambdas)
        // TEXT_PREPROCESSING: reads ctx.message → {ticketId, cleanedText, tokenCount, language}
        registry.register("textPreprocessing", TEXT_PREPROCESSING);
        // NLP_CLASSIFICATION: reads cleanedText → {ticketId, sentiment, positive, negative, neutral}
        registry.register("nlpClassification", NLP_CLASSIFICATION);
        // SENTIMENT_SCORING: reads positive/negative scores → {ticketId, compositeScore, label}
        registry.register("sentimentScoring", SENTIMENT_SCORING);
        // PRIORITY_ASSIGNMENT: reads compositeScore + nlp.sentiment → {ticketId, sentimentLabel, sentimentScore, suggestedPriority}
        registry.register("priorityAssignment", PRIORITY_ASSIGNMENT);
        // SUPERVISOR_NOTIFICATION: reads ticketId/customerId/priority/reason → {ticketId, supervisorId, notified, notificationChannel}
        registry.register("supervisorNotification", SUPERVISOR_NOTIFICATION);
        // SLA_CHECK: reads priority → {ticketId, slaMinutes, slaDeadline, withinSla}
        registry.register("slaCheck", SLA_CHECK);
        // ESCALATION_ROUTING: reads ticketId/supervisorId/priority → {ticketId, assignedTeam, escalationLevel, queuePosition}
        registry.register("escalationRouting", ESCALATION_ROUTING);
        // CUSTOMER_CALLBACK_SCHEDULE: reads ticketId/customerId/assignedTeam → {ticketId, callbackTime, callbackNumber, confirmationId}
        registry.register("customerCallbackSchedule", CUSTOMER_CALLBACK_SCHEDULE);

        // Build sub-graphs via Java API
        Graph sentimentGraph = buildSentimentAnalysisSubGraph();
        Graph escalationGraph = buildEscalationWorkflowSubGraph();

        // Compile main graph from DSL with registered sub-graphs
        var compiler = new DslCompiler(registry);
        // register sub-graphs before loading main DSL
        compiler.registerSubGraph("sentiment-analysis", sentimentGraph);
        compiler.registerSubGraph("escalation-workflow", escalationGraph);

        String dsl = """
                graph smartTicketHandling {
                  ///  receiveTicket: reads ctx.ticketId/customerId/channel/message → {ticketId, customerId, channel, message, status}
                  node receiveTicket : ReceiveTicketOperator {
                    input {
                      ticketId   = ctx.ticketId
                      customerId = ctx.customerId
                      channel    = ctx.channel
                      message    = ctx.message
                    }
                    timeout = 3s
                  }
                  ///  classifyIntent: reads receiveTicket.message → {ticketId, intent, confidence, category}
                  node classifyIntent : ClassifyIntentOperator {
                    depends_on = [receiveTicket]
                    input {
                      ticketId = receiveTicket.output.ticketId
                      message  = receiveTicket.output.message
                    }
                    timeout = 5s
                  }
                  ///  sentimentAnalysis: sub-graph textPreprocessing → nlpClassification → sentimentScoring → priorityAssignment
                  node sentimentAnalysis : subgraph("sentiment-analysis") {
                    depends_on = [classifyIntent]
                    input {
                      ticketId = receiveTicket.output.ticketId
                      message  = receiveTicket.output.message
                      intent   = classifyIntent.output.intent
                    }
                    timeout = 30s
                  }
                  ///  determinePriority: reads sentimentAnalysis.priorityAssignment + classifyIntent.intent → {ticketId, priority, reason}
                  node determinePriority : DeterminePriorityOperator {
                    depends_on = [sentimentAnalysis, classifyIntent]
                    input {
                      ticketId  = classifyIntent.output.ticketId
                      intent    = classifyIntent.output.intent
                      sentiment = sentimentAnalysis.output.priorityAssignment
                    }
                  }
                  ///  escalationWorkflow: sub-graph supervisorNotification → slaCheck → escalationRouting → customerCallbackSchedule
                  node escalationWorkflow : subgraph("escalation-workflow") {
                    depends_on = [determinePriority]
                    input {
                      ticketId   = determinePriority.output.ticketId
                      customerId = ctx.customerId
                      priority   = determinePriority.output.priority
                      reason     = determinePriority.output.reason
                    }
                    timeout = 30s
                  }
                  ///  generateReply: reads ticketId/intent/priority/resolution → {ticketId, replyText, channel, status}
                  node generateReply : GenerateReplyOperator {
                    depends_on = [determinePriority]
                    input {
                      ticketId   = determinePriority.output.ticketId
                      customerId = ctx.customerId
                      intent     = classifyIntent.output.intent
                      priority   = determinePriority.output.priority
                      resolution = determinePriority.output.reason
                    }
                  }
                  ///  branch: "high" priority → escalationWorkflow sub-graph; otherwise → generateReply
                  branch on determinePriority.output.priority {
                    "high"    -> escalationWorkflow
                    otherwise -> generateReply
                  }
                }
                """;

        // compile DSL; operators resolved by PascalCase name
        var tokens = new Lexer(dsl).tokenize();
        GraphDef ast = new Parser(tokens).parse();
        Graph graph = compiler.compile(ast);

        // Execute
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new OrderProcessingExample.LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "ticketId", "TKT-20250115-001",
                "customerId", "CUST-8842",
                "channel", "web-chat",
                "message", "My order arrived broken and I want a refund immediately! This is terrible service."
        ));

        // execute; results keyed by node ID
        GraphResult result = engine.execute(graph, ctx);

        // Print results
        System.out.println("\n═══ DSL Smart Ticket Handling Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        // getRaw returns Object; sub-graph nodes return Map of child-node outputs keyed by node ID
        if (result.getStatus("determinePriority") == NodeStatus.COMPLETED) {
            System.out.println("Priority: " + result.results().getRaw("determinePriority"));
        }

        if (result.getStatus("sentimentAnalysis") == NodeStatus.COMPLETED) {
            System.out.println("Sentiment sub-graph output: " + result.results().getRaw("sentimentAnalysis"));
        }

        if (result.getStatus("escalationWorkflow") == NodeStatus.COMPLETED) {
            System.out.println("Escalation sub-graph output: " + result.results().getRaw("escalationWorkflow"));
        }

        if (result.getStatus("generateReply") == NodeStatus.COMPLETED) {
            System.out.println("Reply: " + result.results().getRaw("generateReply"));
        }
    }
}
