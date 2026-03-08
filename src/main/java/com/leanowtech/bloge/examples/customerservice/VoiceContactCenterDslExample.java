package com.leanowtech.bloge.examples.customerservice;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;

/**
 * DSL version of the voice contact centre pipeline.
 *
 * <p>All operators use {@code Map<String,Object>} I/O so they can be registered
 * by PascalCase name and resolved by the DSL compiler.  The complaint-escalation
 * sub-graph is built via the Java API, then registered with
 * {@link GraphLoader#compiler()}{@code .registerSubGraph()} before compiling the
 * main DSL.
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
 *                                           callSummary (transform)
 *                                                 │
 *                                                 ▼
 *                                          saveCallRecord
 *
 * Sub-graph "complaint-escalation":
 *   sentimentAnalysis → escalationDecision → supervisorNotification
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the pipeline.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceContactCenterDslExample {

    // ── Streaming operators ───────────────────────────────────────────────────

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> AUDIO_CAPTURE =
            (input, channel, ctx) -> {
                System.out.println("    [AudioCapture] Starting audio capture...");
                for (int i = 0; i < 6; i++) {
                    Thread.sleep(20);
                    channel.send(Map.of("sequenceId", i, "sampleRate", 8000, "bytes", i * 512));
                    System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
                }
                System.out.println("    [AudioCapture] Audio capture complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> SPEECH_TO_TEXT =
            (input, channel, ctx) -> {
                var audioChunks = (List<Map<String, Object>>) input.get("audio");
                System.out.println("    [SpeechToText] Processing " + audioChunks.size() + " chunks...");
                String[] words = {"I", "have", "a", "complaint", "about", "billing"};
                int idx = 0;
                for (Map<String, Object> chunk : audioChunks) {
                    Thread.sleep(25);
                    int seqId = (Integer) chunk.get("sequenceId");
                    String word = words[idx % words.length];
                    channel.send(Map.of("sequenceId", seqId, "text", word, "confidence", 0.95 - idx * 0.01));
                    System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", seqId, word);
                    idx++;
                }
                System.out.println("    [SpeechToText] Transcription complete");
            };

    // ── Main-graph operators ──────────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> INTENT_CLASSIFIER = (input, ctx) -> {
        Thread.sleep(40);
        var chunks = (List<Map<String, Object>>) input.get("transcript");
        if (chunks == null) chunks = List.of();
        String transcript = chunks.stream()
                .map(c -> (String) c.getOrDefault("text", ""))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        String lower = transcript.toLowerCase();
        String intent = lower.contains("billing") ? "billing"
                : lower.contains("technical") || lower.contains("internet") ? "technical"
                : lower.contains("complaint") ? "complaint"
                : "general";
        return Map.of("transcript", transcript, "intent", intent);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> BILLING_ROUTER = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "callId", input.getOrDefault("callId", ""),
                "queue", "billing-queue",
                "agentId", "AGENT-B-01");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> TECH_SUPPORT_ROUTER = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "callId", input.getOrDefault("callId", ""),
                "queue", "tech-support-queue",
                "agentId", "AGENT-T-03");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> GENERAL_ROUTER = (input, ctx) -> {
        Thread.sleep(20);
        return Map.of(
                "callId", input.getOrDefault("callId", ""),
                "queue", "general-queue",
                "agentId", "AGENT-G-07");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> CALL_RECORD_SAVER = (input, ctx) -> {
        Thread.sleep(30);
        String callId = (String) input.getOrDefault("callId", "");
        return Map.of(
                "callId", callId,
                "recordId", "REC-" + callId,
                "persisted", true);
    };

    // ── Complaint-escalation sub-graph operators ──────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> SENTIMENT_ANALYSIS = (input, ctx) -> {
        Thread.sleep(50);
        String transcript = (String) input.getOrDefault("transcript", "");
        boolean negative = transcript.toLowerCase().contains("complaint")
                || transcript.toLowerCase().contains("angry");
        return Map.of(
                "callId", input.getOrDefault("callId", ""),
                "sentimentScore", negative ? -0.75 : 0.2,
                "sentimentLabel", negative ? "negative" : "neutral",
                "requiresEscalation", negative);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ESCALATION_DECISION = (input, ctx) -> {
        Thread.sleep(30);
        boolean escalate = Boolean.TRUE.equals(input.get("requiresEscalation"));
        return Map.of(
                "callId", input.getOrDefault("callId", ""),
                "escalate", escalate,
                "escalationTier", escalate ? "tier-2" : "tier-1",
                "reason", escalate ? "Negative sentiment detected" : "Standard handling");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> SUPERVISOR_NOTIFICATION = (input, ctx) -> {
        Thread.sleep(40);
        boolean escalate = Boolean.TRUE.equals(input.get("escalate"));
        return Map.of(
                "callId", input.getOrDefault("callId", ""),
                "supervisorId", escalate ? "SUP-001" : "none",
                "notified", escalate,
                "channel", escalate ? "slack" : "none");
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

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // NOTE: registerRaw is required for StreamingOperator implementations; register is for standard Operator<?,?> only

        // Streaming operators registered by PascalCase name
        // AUDIO_CAPTURE: no input → emits {sequenceId, sampleRate, bytes} chunks via NodeChannel
        registry.registerRaw("AudioCapture", AUDIO_CAPTURE);
        // SPEECH_TO_TEXT: reads input.audio (List<chunk>) → emits {sequenceId, text, confidence} tokens via NodeChannel
        registry.registerRaw("SpeechToText", SPEECH_TO_TEXT);

        // Main-graph operators
        // INTENT_CLASSIFIER: reads transcript (List<token>) → returns {transcript, intent}
        registry.register("IntentClassifier", INTENT_CLASSIFIER);
        // BILLING_ROUTER: reads callId, customerId → returns {callId, queue, agentId}
        registry.register("BillingRouter", BILLING_ROUTER);
        // TECH_SUPPORT_ROUTER: reads callId, customerId → returns {callId, queue, agentId}
        registry.register("TechSupportRouter", TECH_SUPPORT_ROUTER);
        // GENERAL_ROUTER: reads callId, customerId → returns {callId, queue, agentId}
        registry.register("GeneralRouter", GENERAL_ROUTER);
        // CALL_RECORD_SAVER: reads callId, customerId, intent → returns {callId, recordId, persisted}
        registry.register("CallRecordSaver", CALL_RECORD_SAVER);

        // Sub-graph operators — registered by node id so SubGraphOperator can resolve them
        // SENTIMENT_ANALYSIS: reads callId, transcript, intent → returns {sentimentScore, sentimentLabel, requiresEscalation}
        registry.registerRaw("sentimentAnalysis", SENTIMENT_ANALYSIS);
        // ESCALATION_DECISION: reads requiresEscalation → returns {callId, escalate, escalationTier, reason}
        registry.registerRaw("escalationDecision", ESCALATION_DECISION);
        // SUPERVISOR_NOTIFICATION: reads escalate, escalationTier, reason → returns {callId, supervisorId, notified, channel}
        registry.registerRaw("supervisorNotification", SUPERVISOR_NOTIFICATION);

        // Build sub-graph and register it with the loader's compiler
        Graph complaintEscalationGraph = buildComplaintEscalationSubGraph();

        var loader = new GraphLoader(registry);
        // register sub-graphs before loading main DSL
        loader.compiler().registerSubGraph("complaint-escalation", complaintEscalationGraph);

        // ── DSL ───────────────────────────────────────────────────────────────
        String dsl = """
                graph voiceContactCenter {

                  /// STREAM NODE: emits audio chunks via NodeChannel; downstream consumes without blocking this producer
                  stream node audioCapture : AudioCapture {
                    buffer = 64
                  }

                  /// STREAM NODE: emits transcription tokens via NodeChannel as audio chunks arrive
                  stream node speechToText : SpeechToText {
                    input {
                      /// .output on a stream node materialises the full List<chunk> before this operator starts
                      audio = audioCapture.output
                    }
                    buffer = 32
                  }

                  node intentClassification : IntentClassifier {
                    depends_on = [speechToText]
                    input {
                      /// .output materialises the full List<token> into a single collection before IntentClassifier runs
                      transcript = speechToText.output
                    }
                    timeout = 5s
                  }

                  node routeToBilling : BillingRouter {
                    depends_on = [intentClassification]
                    input {
                      callId     = ctx.callId
                      customerId = ctx.customerId
                    }
                  }

                  node routeToTechSupport : TechSupportRouter {
                    depends_on = [intentClassification]
                    input {
                      callId     = ctx.callId
                      customerId = ctx.customerId
                    }
                  }

                  /// SUBGRAPH: runs complaint-escalation
                  /// (sentimentAnalysis → escalationDecision → supervisorNotification); result = supervisorNotification output
                  node sentimentMonitoring : subgraph("complaint-escalation") {
                    depends_on = [intentClassification]
                    input {
                      callId     = ctx.callId
                      customerId = ctx.customerId
                      transcript = intentClassification.output.transcript
                      intent     = intentClassification.output.intent
                    }
                    timeout = 30s
                  }

                  node routeToGeneral : GeneralRouter {
                    depends_on = [intentClassification]
                    input {
                      callId     = ctx.callId
                      customerId = ctx.customerId
                    }
                  }

                  /// BRANCH: evaluates intent; exactly one branch node executes, others are skipped
                  branch on intentClassification.output.intent {
                    "billing"   -> routeToBilling
                    "technical" -> routeToTechSupport
                    "complaint" -> sentimentMonitoring
                    otherwise   -> routeToGeneral
                  }

                  /// TRANSFORM: zero-cost field projection — no operator scheduled; fields are resolved from upstream outputs
                  transform callSummary {
                    callId     = ctx.callId
                    customerId = ctx.customerId
                    intent     = intentClassification.output.intent
                  }

                  node saveCallRecord : CallRecordSaver {
                    depends_on = [callSummary]
                    input {
                      callId     = callSummary.callId
                      customerId = callSummary.customerId
                      intent     = callSummary.intent
                    }
                  }
                }
                """;

        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(dsl);

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

        // execute; streaming results accessible via result.results()
        GraphResult result = engine.execute(graph, ctx);

        // ── Results ──────────────────────────────────────────────────────────
        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  DSL Voice Contact Centre Result");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-28s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("intentClassification") == NodeStatus.COMPLETED) {
            System.out.println("  Intent result     : " + result.results().getRaw("intentClassification"));
        }

        if (result.getStatus("sentimentMonitoring") == NodeStatus.COMPLETED) {
            System.out.println("  Escalation output : " + result.results().getRaw("sentimentMonitoring"));
        }

        if (result.getStatus("callSummary") == NodeStatus.COMPLETED) {
            System.out.println("  Call summary      : " + result.results().getRaw("callSummary"));
        }

        if (result.getStatus("saveCallRecord") == NodeStatus.COMPLETED) {
            System.out.println("  Record saved      : " + result.results().getRaw("saveCallRecord"));
        }

        System.out.println("═══════════════════════════════════════════════");
    }
}
