package com.leanowtech.bloge.examples.customerservice;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorLayer;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.engine.operators.SubGraphOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates a voice contact centre pipeline combining streaming audio ingestion,
 * speech-to-text transcription, intent classification, and complaint escalation
 * via a sequential sub-graph.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture → stream speechToText → intentClassification
 *                                                     │
 *               ┌──────────────┬──────────────────────┼──────────────────┐
 *               ▼              ▼                       ▼                  ▼
 *        routeToBilling  routeToTechSupport  sentimentMonitoring    routeToGeneral
 *               │              │             (sub-graph)                  │
 *               └──────────────┴─────────────────┬────────────────────────┘
 *                                                 ▼
 *                                           callSummary
 *                                                 │
 *                                                 ▼
 *                                          saveCallRecord
 *
 * Sub-graph "complaint-escalation":
 *   sentimentAnalysis → escalationDecision → supervisorNotification
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the pipeline with a simulated inbound call.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceContactCenterExample {

    // ── Domain records ────────────────────────────────────────────────────────

    public record AudioChunk(int sequenceId, byte[] samples, int sampleRate) {}

    public record TextChunk(int sequenceId, String text, double confidence) {}

    public record IntentResult(String transcript, String intent) {}

    public record RoutingInput(String callId, String customerId) {}

    public record RoutingResult(String callId, String queue, String agentId) {}

    public record CallSummary(String callId, String customerId, String intent, String resolution) {}

    public record SaveResult(String callId, String recordId, boolean persisted) {}

    // ── Streaming operators ───────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"voice", "audio"},
            description = "Captures a continuous audio stream from the telephony channel", owner = "media-team")
    static final StreamingOperator<Void, AudioChunk> AUDIO_CAPTURE = (input, channel, ctx) -> {
        System.out.println("    [AudioCapture] Starting audio capture...");
        for (int i = 0; i < 6; i++) {
            Thread.sleep(20);
            channel.send(new AudioChunk(i, new byte[1024], 8000));
            System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
        }
        System.out.println("    [AudioCapture] Audio capture complete");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "stt"},
            description = "Converts audio chunks to text tokens in real-time", owner = "ai-team")
    static final StreamingOperator<List<AudioChunk>, TextChunk> SPEECH_TO_TEXT = (input, channel, ctx) -> {
        System.out.println("    [SpeechToText] Processing " + input.size() + " audio chunks...");
        String[] words = {"I", "have", "a", "complaint", "about", "billing"};
        int idx = 0;
        for (AudioChunk chunk : input) {
            Thread.sleep(25);
            String word = words[idx % words.length];
            channel.send(new TextChunk(chunk.sequenceId(), word, 0.95 - idx * 0.01));
            System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", chunk.sequenceId(), word);
            idx++;
        }
        System.out.println("    [SpeechToText] Transcription complete");
    };

    // ── Main-graph operators ──────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "nlp"},
            description = "Classifies caller intent from the materialised transcript", owner = "ai-team")
    static final Operator<List<TextChunk>, IntentResult> INTENT_CLASSIFIER = (input, ctx) -> {
        Thread.sleep(40);
        String transcript = input.stream()
                .map(TextChunk::text)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        String lower = transcript.toLowerCase();
        String intent = lower.contains("billing") ? "billing"
                : lower.contains("technical") || lower.contains("internet") ? "technical"
                : lower.contains("complaint") ? "complaint"
                : "general";
        return new IntentResult(transcript, intent);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"voice", "routing"},
            description = "Routes the call to the billing department", owner = "contact-centre-team")
    static final Operator<RoutingInput, RoutingResult> ROUTE_TO_BILLING = (input, ctx) -> {
        Thread.sleep(20);
        return new RoutingResult(input.callId(), "billing-queue", "AGENT-B-01");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"voice", "routing"},
            description = "Routes the call to the technical support team", owner = "contact-centre-team")
    static final Operator<RoutingInput, RoutingResult> ROUTE_TO_TECH_SUPPORT = (input, ctx) -> {
        Thread.sleep(20);
        return new RoutingResult(input.callId(), "tech-support-queue", "AGENT-T-03");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"voice", "routing"},
            description = "Routes the call to the general-enquiries queue", owner = "contact-centre-team")
    static final Operator<RoutingInput, RoutingResult> ROUTE_TO_GENERAL = (input, ctx) -> {
        Thread.sleep(20);
        return new RoutingResult(input.callId(), "general-queue", "AGENT-G-07");
    };

    // ── Complaint-escalation sub-graph operators ──────────────────────────────

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "sentiment"},
            description = "Analyses the sentiment of the caller transcript", owner = "ai-team")
    static final Operator<Map<String, Object>, Map<String, Object>> SENTIMENT_ANALYSIS = (input, ctx) -> {
        Thread.sleep(50);
        String transcript = (String) input.getOrDefault("transcript", "");
        boolean negative = transcript.toLowerCase().contains("complaint")
                || transcript.toLowerCase().contains("angry");
        return Map.of(
                "callId", input.get("callId"),
                "sentimentScore", negative ? -0.75 : 0.2,
                "sentimentLabel", negative ? "negative" : "neutral",
                "requiresEscalation", negative);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"voice", "escalation"},
            description = "Decides whether to escalate based on sentiment score", owner = "contact-centre-team")
    static final Operator<Map<String, Object>, Map<String, Object>> ESCALATION_DECISION = (input, ctx) -> {
        Thread.sleep(30);
        boolean escalate = Boolean.TRUE.equals(input.get("requiresEscalation"));
        return Map.of(
                "callId", input.get("callId"),
                "escalate", escalate,
                "escalationTier", escalate ? "tier-2" : "tier-1",
                "reason", escalate ? "Negative sentiment detected" : "Standard handling");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "escalation"},
            description = "Notifies a supervisor and assigns the escalated call", owner = "contact-centre-team")
    static final Operator<Map<String, Object>, Map<String, Object>> SUPERVISOR_NOTIFICATION = (input, ctx) -> {
        Thread.sleep(40);
        boolean escalate = Boolean.TRUE.equals(input.get("escalate"));
        return Map.of(
                "callId", input.get("callId"),
                "supervisorId", escalate ? "SUP-001" : "none",
                "notified", escalate,
                "channel", escalate ? "slack" : "none");
    };

    // ── Post-routing operators ────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"voice", "summary"},
            description = "Builds a structured call summary from all upstream results", owner = "contact-centre-team")
    static final Operator<Map<String, Object>, CallSummary> CALL_SUMMARY_BUILDER = (input, ctx) -> {
        Thread.sleep(25);
        String callId = input.containsKey("callId")
                ? (String) input.get("callId") : ctx.graphContext().get("callId", String.class);
        String customerId = input.containsKey("customerId")
                ? (String) input.get("customerId") : ctx.graphContext().get("customerId", String.class);
        String intent = (String) input.getOrDefault("intent", "unknown");
        String resolution = (String) input.getOrDefault("resolution", "routed");
        return new CallSummary(callId, customerId, intent, resolution);
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"voice", "persistence"},
            description = "Persists the call record to the CRM", owner = "crm-team")
    static final Operator<CallSummary, SaveResult> CALL_RECORD_SAVER = (input, ctx) -> {
        Thread.sleep(30);
        return new SaveResult(input.callId(), "REC-" + input.callId(), true);
    };

    // ── Sub-graph construction ────────────────────────────────────────────────

    public static Graph buildComplaintEscalationSubGraph() {
        return Graph.builder("complaint-escalation")
                .node("sentimentAnalysis", SENTIMENT_ANALYSIS)
                    .input((results, ctx) -> Map.of(
                            "callId", ctx.get("callId", String.class),
                            "customerId", ctx.get("customerId", String.class),
                            "transcript", ctx.containsKey("transcript") ? ctx.get("transcript", String.class) : "",
                            "intent", ctx.containsKey("intent") ? ctx.get("intent", String.class) : "complaint"))
                .node("escalationDecision", ESCALATION_DECISION)
                    .dependsOn("sentimentAnalysis")
                    .input((results, ctx) -> {
                        var sentiment = (Map<String, Object>) results.getRaw("sentimentAnalysis");
                        return Map.of(
                                "callId", sentiment.get("callId"),
                                "sentimentScore", sentiment.get("sentimentScore"),
                                "sentimentLabel", sentiment.get("sentimentLabel"),
                                "requiresEscalation", sentiment.get("requiresEscalation"));
                    })
                .node("supervisorNotification", SUPERVISOR_NOTIFICATION)
                    .dependsOn("escalationDecision")
                    .input((results, ctx) -> {
                        var decision = (Map<String, Object>) results.getRaw("escalationDecision");
                        return Map.of(
                                "callId", decision.get("callId"),
                                "escalate", decision.get("escalate"),
                                "escalationTier", decision.get("escalationTier"),
                                "reason", decision.get("reason"));
                    })
                .build();
    }

    // ── Main graph construction ───────────────────────────────────────────────

    public static Graph buildMainGraph(SubGraphOperator complaintEscalation) {
        return Graph.builder("voiceContactCenter")
                // Streaming source — placeholder op; real op registered in registry by node id
                .node("audioCapture", (input, ctx) -> null)
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "64")
                // Streaming transform
                .node("speechToText", (input, ctx) -> null)
                    .dependsOn("audioCapture")
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                    .input((results, ctx) -> (List<AudioChunk>) results.getRaw("audioCapture"))
                // Intent classification (materialises stream)
                .node("intentClassification", INTENT_CLASSIFIER)
                    .dependsOn("speechToText")
                    .input((results, ctx) -> (List<TextChunk>) results.getRaw("speechToText"))
                // Branch targets
                .node("routeToBilling", ROUTE_TO_BILLING)
                    .dependsOn("intentClassification")
                    .input((results, ctx) -> new RoutingInput(
                            ctx.get("callId", String.class),
                            ctx.get("customerId", String.class)))
                .node("routeToTechSupport", ROUTE_TO_TECH_SUPPORT)
                    .dependsOn("intentClassification")
                    .input((results, ctx) -> new RoutingInput(
                            ctx.get("callId", String.class),
                            ctx.get("customerId", String.class)))
                .node("sentimentMonitoring", complaintEscalation)
                    .dependsOn("intentClassification")
                    .input((results, ctx) -> {
                        var intent = results.get("intentClassification", IntentResult.class);
                        return Map.of(
                                "callId", ctx.get("callId", String.class),
                                "customerId", ctx.get("customerId", String.class),
                                "transcript", intent.transcript(),
                                "intent", intent.intent());
                    })
                .node("routeToGeneral", ROUTE_TO_GENERAL)
                    .dependsOn("intentClassification")
                    .input((results, ctx) -> new RoutingInput(
                            ctx.get("callId", String.class),
                            ctx.get("customerId", String.class)))
                // Branch on intent
                .branch("intentClassification")
                    .on("intent")
                    .when(v -> "billing".equals(v), "routeToBilling")
                    .when(v -> "technical".equals(v), "routeToTechSupport")
                    .when(v -> "complaint".equals(v), "sentimentMonitoring")
                    .otherwise("routeToGeneral")
                // Aggregation after branch
                .node("callSummary", CALL_SUMMARY_BUILDER)
                    .dependsOn("intentClassification", "routeToBilling", "routeToTechSupport",
                            "sentimentMonitoring", "routeToGeneral")
                    .input((results, ctx) -> {
                        var intent = results.get("intentClassification", IntentResult.class);
                        String resolution = "routed";
                        if (results.hasResult("routeToBilling")) {
                            resolution = results.get("routeToBilling", RoutingResult.class).queue();
                        } else if (results.hasResult("routeToTechSupport")) {
                            resolution = results.get("routeToTechSupport", RoutingResult.class).queue();
                        } else if (results.hasResult("sentimentMonitoring")) {
                            resolution = "complaint-escalation";
                        } else if (results.hasResult("routeToGeneral")) {
                            resolution = results.get("routeToGeneral", RoutingResult.class).queue();
                        }
                        return Map.of(
                                "callId", ctx.get("callId", String.class),
                                "customerId", ctx.get("customerId", String.class),
                                "intent", intent.intent(),
                                "resolution", resolution);
                    })
                .node("saveCallRecord", CALL_RECORD_SAVER)
                    .dependsOn("callSummary")
                    .input((results, ctx) -> results.get("callSummary", CallSummary.class))
                .build();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Streaming operators — must be registered by node id via registerRaw
        registry.registerRaw("audioCapture", AUDIO_CAPTURE);
        registry.registerRaw("speechToText", SPEECH_TO_TEXT);

        // Normal main-graph operators
        registry.registerRaw("intentClassification", INTENT_CLASSIFIER);
        registry.registerRaw("routeToBilling", ROUTE_TO_BILLING);
        registry.registerRaw("routeToTechSupport", ROUTE_TO_TECH_SUPPORT);
        registry.registerRaw("routeToGeneral", ROUTE_TO_GENERAL);
        registry.registerRaw("callSummary", CALL_SUMMARY_BUILDER);
        registry.registerRaw("saveCallRecord", CALL_RECORD_SAVER);

        // Sub-graph operators — registered by node id so SubGraphOperator can resolve them
        registry.registerRaw("sentimentAnalysis", SENTIMENT_ANALYSIS);
        registry.registerRaw("escalationDecision", ESCALATION_DECISION);
        registry.registerRaw("supervisorNotification", SUPERVISOR_NOTIFICATION);

        // Build sub-graph and wrap it
        Graph complaintEscalationGraph = buildComplaintEscalationSubGraph();
        var complaintEscalation = new SubGraphOperator(complaintEscalationGraph, registry);
        registry.registerRaw("sentimentMonitoring", complaintEscalation);

        // Build and execute main graph
        Graph graph = buildMainGraph(complaintEscalation);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "callId", "CALL-001",
                "customerId", "C-500",
                "message", "I have a complaint about billing"
        ));

        GraphResult result = engine.execute(graph, ctx);

        // ── Results ──────────────────────────────────────────────────────────
        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  Voice Contact Centre Result");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-28s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("intentClassification") == NodeStatus.COMPLETED) {
            IntentResult intent = result.getOutput("intentClassification", IntentResult.class);
            System.out.println("  Intent       : " + intent.intent());
            System.out.println("  Transcript   : " + intent.transcript());
        }

        if (result.getStatus("sentimentMonitoring") == NodeStatus.COMPLETED) {
            System.out.println("  Escalation   : " + result.results().getRaw("sentimentMonitoring"));
        }

        if (result.getStatus("callSummary") == NodeStatus.COMPLETED) {
            CallSummary summary = result.getOutput("callSummary", CallSummary.class);
            System.out.println("  Call summary : " + summary);
        }

        if (result.getStatus("saveCallRecord") == NodeStatus.COMPLETED) {
            SaveResult save = result.getOutput("saveCallRecord", SaveResult.class);
            System.out.println("  Record saved : " + save);
        }

        System.out.println("═══════════════════════════════════════════════");
    }
}
