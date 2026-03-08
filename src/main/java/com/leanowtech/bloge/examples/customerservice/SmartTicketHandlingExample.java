package com.leanowtech.bloge.examples.customerservice;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.BackoffStrategy;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorLayer;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.ecommerce.OrderProcessingExample;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates sequential sub-graph execution with branching in a customer service pipeline.
 * <p>
 * Main graph: receiveTicket → classifyIntent → sentimentAnalysis (sub-graph) → determinePriority
 *             → branch(high/medium/low) → high: escalationWorkflow (sub-graph)
 *                                        → medium/low: generateReply
 * <p>
 * Sub-graph A (sentiment-analysis): textPreprocessing → nlpClassification → sentimentScoring → priorityAssignment
 * Sub-graph B (escalation-workflow): supervisorNotification → slaCheck → escalationRouting → customerCallbackSchedule
 */
public class SmartTicketHandlingExample {

    // --- Main graph records ---

    public record TicketRequest(String ticketId, String customerId, String channel, String message) {}
    public record ReceivedTicket(String ticketId, String customerId, String channel, String message, String status) {}
    public record IntentInput(String ticketId, String message) {}
    public record IntentResult(String ticketId, String intent, double confidence, String category) {}
    public record PriorityInput(String ticketId, String intent, String sentimentLabel, double sentimentScore) {}
    public record PriorityResult(String ticketId, String priority, String reason) {}
    public record ReplyInput(String ticketId, String customerId, String intent, String priority, String resolution) {}
    public record ReplyResult(String ticketId, String customerId, String replyText, String channel, String status) {}

    // --- Sentiment analysis sub-graph records ---

    public record PreprocessInput(String ticketId, String rawText) {}
    public record PreprocessResult(String ticketId, String cleanedText, int tokenCount, String language) {}
    public record NlpInput(String ticketId, String cleanedText) {}
    public record NlpResult(String ticketId, String sentiment, double positive, double negative, double neutral) {}
    public record ScoringInput(String ticketId, double positive, double negative, double neutral) {}
    public record ScoringResult(String ticketId, double compositeScore, String label) {}
    public record PriorityAssignInput(String ticketId, String sentiment, double compositeScore, String intent) {}
    public record PriorityAssignResult(String ticketId, String sentimentLabel, double sentimentScore, String suggestedPriority) {}

    // --- Escalation workflow sub-graph records ---

    public record SupervisorNotifyInput(String ticketId, String customerId, String priority, String reason) {}
    public record SupervisorNotifyResult(String ticketId, String supervisorId, boolean notified, String notificationChannel) {}
    public record SlaCheckInput(String ticketId, String priority) {}
    public record SlaCheckResult(String ticketId, int slaMinutes, String slaDeadline, boolean withinSla) {}
    public record EscalationRoutingInput(String ticketId, String supervisorId, String priority) {}
    public record EscalationRoutingResult(String ticketId, String assignedTeam, String escalationLevel, String queuePosition) {}
    public record CallbackScheduleInput(String ticketId, String customerId, String assignedTeam) {}
    public record CallbackScheduleResult(String ticketId, String callbackTime, String callbackNumber, String confirmationId) {}

    // --- Main graph operators ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"customer-service", "ticket"},
            description = "Receives and registers the incoming support ticket", owner = "support-team")
    static final Operator<TicketRequest, ReceivedTicket> RECEIVE_TICKET = (input, ctx) -> {
        Thread.sleep(25);
        return new ReceivedTicket(input.ticketId(), input.customerId(), input.channel(),
                input.message(), "RECEIVED");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"customer-service", "nlp"},
            description = "Classifies the intent of the support ticket", owner = "ai-team")
    static final Operator<IntentInput, IntentResult> CLASSIFY_INTENT = (input, ctx) -> {
        Thread.sleep(45);
        String message = input.message().toLowerCase();
        String intent = message.contains("refund") ? "refund_request"
                : message.contains("broken") || message.contains("defect") ? "product_defect"
                : message.contains("billing") ? "billing_inquiry"
                : "general_inquiry";
        double confidence = intent.equals("general_inquiry") ? 0.65 : 0.92;
        return new IntentResult(input.ticketId(), intent, confidence,
                intent.contains("product") ? "product" : "account");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"customer-service", "priority"},
            description = "Determines ticket priority from sentiment analysis output", owner = "support-team")
    static final Operator<PriorityInput, PriorityResult> DETERMINE_PRIORITY = (input, ctx) -> {
        Thread.sleep(20);
        String priority;
        String reason;
        if (input.sentimentScore() < -0.5 || "refund_request".equals(input.intent())) {
            priority = "high";
            reason = "Negative sentiment (" + input.sentimentLabel() + ") or escalation-worthy intent";
        } else if (input.sentimentScore() < 0.0) {
            priority = "medium";
            reason = "Mildly negative sentiment requires attention";
        } else {
            priority = "low";
            reason = "Positive/neutral sentiment, standard handling";
        }
        return new PriorityResult(input.ticketId(), priority, reason);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"customer-service", "reply"},
            description = "Generates the customer reply based on ticket resolution", owner = "support-team")
    static final Operator<ReplyInput, ReplyResult> GENERATE_REPLY = (input, ctx) -> {
        Thread.sleep(30);
        String replyText = "Dear customer, regarding ticket " + input.ticketId()
                + ": your " + input.intent() + " has been processed with " + input.priority()
                + " priority. " + input.resolution();
        return new ReplyResult(input.ticketId(), input.customerId(), replyText, "email", "SENT");
    };

    // --- Sentiment analysis sub-graph operators ---

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"customer-service", "nlp"},
            description = "Preprocesses ticket text for NLP analysis", owner = "ai-team")
    static final Operator<PreprocessInput, PreprocessResult> TEXT_PREPROCESSING = (input, ctx) -> {
        Thread.sleep(30);
        String cleaned = input.rawText().replaceAll("[^a-zA-Z0-9\\s.,!?]", "").trim().toLowerCase();
        int tokenCount = cleaned.split("\\s+").length;
        return new PreprocessResult(input.ticketId(), cleaned, tokenCount, "en");
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"customer-service", "nlp"},
            description = "Runs NLP classification model on preprocessed text", owner = "ai-team")
    static final Operator<NlpInput, NlpResult> NLP_CLASSIFICATION = (input, ctx) -> {
        Thread.sleep(80);
        boolean hasNegative = input.cleanedText().contains("broken")
                || input.cleanedText().contains("terrible")
                || input.cleanedText().contains("refund");
        double positive = hasNegative ? 0.1 : 0.7;
        double negative = hasNegative ? 0.75 : 0.1;
        double neutral = 1.0 - positive - negative;
        String sentiment = negative > 0.5 ? "negative" : positive > 0.5 ? "positive" : "neutral";
        return new NlpResult(input.ticketId(), sentiment, positive, negative, neutral);
    };

    static final Operator<ScoringInput, ScoringResult> SENTIMENT_SCORING = (input, ctx) -> {
        Thread.sleep(20);
        double composite = input.positive() - input.negative();
        String label = composite > 0.3 ? "positive" : composite < -0.3 ? "negative" : "neutral";
        return new ScoringResult(input.ticketId(), Math.round(composite * 100.0) / 100.0, label);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"customer-service", "priority"},
            description = "Assigns priority suggestion based on sentiment scoring", owner = "ai-team")
    static final Operator<PriorityAssignInput, PriorityAssignResult> PRIORITY_ASSIGNMENT = (input, ctx) -> {
        Thread.sleep(15);
        String suggested = input.compositeScore() < -0.3 ? "high"
                : input.compositeScore() < 0.0 ? "medium" : "low";
        return new PriorityAssignResult(input.ticketId(), input.sentiment(), input.compositeScore(), suggested);
    };

    // --- Escalation workflow sub-graph operators ---

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"customer-service", "escalation"},
            description = "Notifies supervisor about escalated ticket", owner = "support-team")
    static final Operator<SupervisorNotifyInput, SupervisorNotifyResult> SUPERVISOR_NOTIFICATION = (input, ctx) -> {
        Thread.sleep(40);
        return new SupervisorNotifyResult(input.ticketId(), "SUP-" + input.ticketId().hashCode() % 100,
                true, "slack");
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"customer-service", "sla"},
            description = "Checks SLA compliance for the escalated ticket", owner = "operations-team")
    static final Operator<SlaCheckInput, SlaCheckResult> SLA_CHECK = (input, ctx) -> {
        Thread.sleep(35);
        int slaMinutes = "high".equals(input.priority()) ? 30 : 120;
        return new SlaCheckResult(input.ticketId(), slaMinutes,
                "2025-01-15T12:00:00Z", true);
    };

    static final Operator<EscalationRoutingInput, EscalationRoutingResult> ESCALATION_ROUTING = (input, ctx) -> {
        Thread.sleep(25);
        return new EscalationRoutingResult(input.ticketId(), "tier-2-support",
                "L2", "Q-" + input.ticketId());
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"customer-service", "callback"},
            description = "Schedules a customer callback after escalation", owner = "support-team")
    static final Operator<CallbackScheduleInput, CallbackScheduleResult> CUSTOMER_CALLBACK_SCHEDULE = (input, ctx) -> {
        Thread.sleep(30);
        return new CallbackScheduleResult(input.ticketId(), "2025-01-15T14:00:00Z",
                "+1-555-0100", "CB-" + input.ticketId());
    };

    // --- Sub-graph construction ---

    public static Graph buildSentimentAnalysisSubGraph() {
        return Graph.builder("sentiment-analysis")
                .node("textPreprocessing", TEXT_PREPROCESSING)
                    .input((results, ctx) -> new PreprocessInput(
                            ctx.get("ticketId", String.class),
                            ctx.get("message", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("nlpClassification", NLP_CLASSIFICATION)
                    .dependsOn("textPreprocessing")
                    .input((results, ctx) -> new NlpInput(
                            ctx.get("ticketId", String.class),
                            results.get("textPreprocessing", PreprocessResult.class).cleanedText()))
                    .retry(2, Duration.ofMillis(500), BackoffStrategy.EXPONENTIAL)
                    .timeout(Duration.ofSeconds(10))
                .node("sentimentScoring", SENTIMENT_SCORING)
                    .dependsOn("nlpClassification")
                    .input((results, ctx) -> {
                        var nlp = results.get("nlpClassification", NlpResult.class);
                        return new ScoringInput(
                                ctx.get("ticketId", String.class),
                                nlp.positive(), nlp.negative(), nlp.neutral());
                    })
                .node("priorityAssignment", PRIORITY_ASSIGNMENT)
                    .dependsOn("sentimentScoring", "nlpClassification")
                    .input((results, ctx) -> {
                        var nlp = results.get("nlpClassification", NlpResult.class);
                        var scoring = results.get("sentimentScoring", ScoringResult.class);
                        return new PriorityAssignInput(
                                ctx.get("ticketId", String.class),
                                nlp.sentiment(), scoring.compositeScore(),
                                ctx.get("intent", String.class));
                    })
                .build();
    }

    public static Graph buildEscalationWorkflowSubGraph() {
        return Graph.builder("escalation-workflow")
                .node("supervisorNotification", SUPERVISOR_NOTIFICATION)
                    .input((results, ctx) -> new SupervisorNotifyInput(
                            ctx.get("ticketId", String.class),
                            ctx.get("customerId", String.class),
                            ctx.get("priority", String.class),
                            ctx.get("reason", String.class)))
                    .timeout(Duration.ofSeconds(5))
                    .retry(2, Duration.ofMillis(300), BackoffStrategy.FIXED)
                .node("slaCheck", SLA_CHECK)
                    .dependsOn("supervisorNotification")
                    .input((results, ctx) -> new SlaCheckInput(
                            ctx.get("ticketId", String.class),
                            ctx.get("priority", String.class)))
                    .timeout(Duration.ofSeconds(5))
                .node("escalationRouting", ESCALATION_ROUTING)
                    .dependsOn("slaCheck")
                    .input((results, ctx) -> new EscalationRoutingInput(
                            ctx.get("ticketId", String.class),
                            results.get("supervisorNotification", SupervisorNotifyResult.class).supervisorId(),
                            ctx.get("priority", String.class)))
                .node("customerCallbackSchedule", CUSTOMER_CALLBACK_SCHEDULE)
                    .dependsOn("escalationRouting")
                    .input((results, ctx) -> new CallbackScheduleInput(
                            ctx.get("ticketId", String.class),
                            ctx.get("customerId", String.class),
                            results.get("escalationRouting", EscalationRoutingResult.class).assignedTeam()))
                .build();
    }

    @SuppressWarnings({"preview", "unchecked"})
    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Register sub-graph operators (resolved by operatorRef = node ID for lambdas)
        registry.register("textPreprocessing", TEXT_PREPROCESSING);
        registry.register("nlpClassification", NLP_CLASSIFICATION);
        registry.register("sentimentScoring", SENTIMENT_SCORING);
        registry.register("priorityAssignment", PRIORITY_ASSIGNMENT);
        registry.register("supervisorNotification", SUPERVISOR_NOTIFICATION);
        registry.register("slaCheck", SLA_CHECK);
        registry.register("escalationRouting", ESCALATION_ROUTING);
        registry.register("customerCallbackSchedule", CUSTOMER_CALLBACK_SCHEDULE);

        // Build sub-graphs
        Graph sentimentGraph = buildSentimentAnalysisSubGraph();
        Graph escalationGraph = buildEscalationWorkflowSubGraph();

        // Wrap as SubGraphOperators
        SubGraphOperator sentimentSubGraph = new SubGraphOperator(sentimentGraph, registry);
        SubGraphOperator escalationSubGraph = new SubGraphOperator(escalationGraph, registry);

        // Build main graph
        Graph mainGraph = Graph.builder("smartTicketHandling")
                .node("receiveTicket", RECEIVE_TICKET)
                    .input((results, ctx) -> new TicketRequest(
                            ctx.get("ticketId", String.class),
                            ctx.get("customerId", String.class),
                            ctx.get("channel", String.class),
                            ctx.get("message", String.class)))
                    .timeout(Duration.ofSeconds(3))
                .node("classifyIntent", CLASSIFY_INTENT)
                    .dependsOn("receiveTicket")
                    .input((results, ctx) -> {
                        var ticket = results.get("receiveTicket", ReceivedTicket.class);
                        return new IntentInput(ticket.ticketId(), ticket.message());
                    })
                    .timeout(Duration.ofSeconds(5))
                .node("sentimentAnalysis", sentimentSubGraph)
                    .dependsOn("classifyIntent")
                    .input((results, ctx) -> {
                        var ticket = results.get("receiveTicket", ReceivedTicket.class);
                        var intent = results.get("classifyIntent", IntentResult.class);
                        return Map.of(
                                "ticketId", ticket.ticketId(),
                                "message", ticket.message(),
                                "intent", intent.intent());
                    })
                    .timeout(Duration.ofSeconds(30))
                .node("determinePriority", DETERMINE_PRIORITY)
                    .dependsOn("sentimentAnalysis", "classifyIntent")
                    .input((results, ctx) -> {
                        var sentimentOut = (Map<String, Object>) results.getRaw("sentimentAnalysis");
                        var priorityAssign = (PriorityAssignResult) sentimentOut.get("priorityAssignment");
                        var intent = results.get("classifyIntent", IntentResult.class);
                        return new PriorityInput(
                                intent.ticketId(), intent.intent(),
                                priorityAssign.sentimentLabel(), priorityAssign.sentimentScore());
                    })
                .node("escalationWorkflow", escalationSubGraph)
                    .dependsOn("determinePriority")
                    .input((results, ctx) -> {
                        var priority = results.get("determinePriority", PriorityResult.class);
                        return Map.of(
                                "ticketId", priority.ticketId(),
                                "customerId", ctx.get("customerId", String.class),
                                "priority", priority.priority(),
                                "reason", priority.reason());
                    })
                    .timeout(Duration.ofSeconds(30))
                .node("generateReply", GENERATE_REPLY)
                    .dependsOn("determinePriority")
                    .input((results, ctx) -> {
                        var priority = results.get("determinePriority", PriorityResult.class);
                        var intent = results.get("classifyIntent", IntentResult.class);
                        return new ReplyInput(
                                priority.ticketId(),
                                ctx.get("customerId", String.class),
                                intent.intent(),
                                priority.priority(),
                                "Your request is being handled with " + priority.priority() + " priority.");
                    })
                .branch("determinePriority")
                    .on("priority")
                    .when(val -> "high".equals(val), "escalationWorkflow")
                    .otherwise("generateReply")
                .build();

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

        GraphResult result = engine.executeWithOperators(mainGraph, ctx, Map.of(
                "receiveTicket", RECEIVE_TICKET,
                "classifyIntent", CLASSIFY_INTENT,
                "sentimentAnalysis", sentimentSubGraph,
                "determinePriority", DETERMINE_PRIORITY,
                "escalationWorkflow", escalationSubGraph,
                "generateReply", GENERATE_REPLY
        ));

        // Print results
        System.out.println("\n═══ Smart Ticket Handling Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("classifyIntent") == NodeStatus.COMPLETED) {
            IntentResult intent = result.getOutput("classifyIntent", IntentResult.class);
            System.out.println("Intent: " + intent);
        }

        if (result.getStatus("determinePriority") == NodeStatus.COMPLETED) {
            PriorityResult priority = result.getOutput("determinePriority", PriorityResult.class);
            System.out.println("Priority: " + priority);
        }

        if (result.getStatus("sentimentAnalysis") == NodeStatus.COMPLETED) {
            System.out.println("Sentiment sub-graph output: " + result.results().getRaw("sentimentAnalysis"));
        }

        if (result.getStatus("escalationWorkflow") == NodeStatus.COMPLETED) {
            System.out.println("Escalation sub-graph output: " + result.results().getRaw("escalationWorkflow"));
        }

        if (result.getStatus("generateReply") == NodeStatus.COMPLETED) {
            ReplyResult reply = result.getOutput("generateReply", ReplyResult.class);
            System.out.println("Reply: " + reply);
        }
    }
}
