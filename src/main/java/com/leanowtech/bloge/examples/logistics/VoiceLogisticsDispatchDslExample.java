package com.leanowtech.bloge.examples.logistics;

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
 * DSL-based voice logistics dispatch pipeline loaded from an inline {@code .bloge} DSL string.
 *
 * <p>All operators use {@code Map<String,Object>} I/O so they can be registered by
 * PascalCase operatorRef name and resolved by the DSL compiler.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture
 *   → stream speechToText
 *   → parseDriverRequest
 *        │
 *   branch on parseDriverRequest.output.requestType
 *        ├── "routeQuery"    → queryOptimalRoute
 *        ├── "anomalyReport" → logAnomaly → notifyDispatcher
 *        └── otherwise       → acknowledgement
 *   → stream textToSpeech
 *   → sendVoiceResponse
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph with a simulated
 * route-query driver request.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceLogisticsDispatchDslExample {

    // ── Streaming operators ───────────────────────────────────────────────────

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> AUDIO_CAPTURE =
            (input, channel, ctx) -> {
                System.out.println("    [AudioCapture] Starting audio capture...");
                for (int i = 0; i < 6; i++) {
                    Thread.sleep(20);
                    channel.send(Map.of("sequenceId", i, "sampleRate", 16000, "bytes", i * 1024));
                    System.out.printf("    [AudioCapture] Emitted chunk #%d (20ms, 16kHz)%n", i);
                }
                System.out.println("    [AudioCapture] Audio capture complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> SPEECH_TO_TEXT =
            (input, channel, ctx) -> {
                var audioChunks = (List<Map<String, Object>>) input.get("audio");
                System.out.println("    [SpeechToText] Processing " + audioChunks.size() + " audio chunks...");
                String[] words = {"query", "route", "to", "warehouse", "seven", "eta"};
                int idx = 0;
                for (Map<String, Object> chunk : audioChunks) {
                    Thread.sleep(20);
                    int seqId = (Integer) chunk.get("sequenceId");
                    String word = words[idx % words.length];
                    channel.send(Map.of("sequenceId", seqId, "text", word, "confidence", 0.94 - idx * 0.01));
                    System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", seqId, word);
                    idx++;
                }
                System.out.println("    [SpeechToText] Transcription complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> TTS_OPERATOR =
            (input, channel, ctx) -> {
                String text = (String) input.getOrDefault("text", "Acknowledged");
                String language = (String) input.getOrDefault("language", "en");
                System.out.println("    [TextToSpeech] Synthesising response: \"" + text + "\"");
                String[] parts = text.split("\\s+");
                int chunks = Math.max(4, Math.min(parts.length, 4));
                for (int i = 0; i < chunks; i++) {
                    Thread.sleep(15);
                    channel.send(Map.of("sequenceId", i, "text", parts[i % parts.length], "language", language));
                    System.out.printf("    [TextToSpeech] Emitted speech chunk #%d → \"%s\"%n", i, parts[i % parts.length]);
                }
                System.out.println("    [TextToSpeech] Synthesis complete");
            };

    // ── Normal operators ──────────────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> DRIVER_REQUEST_PARSER = (input, ctx) -> {
        Thread.sleep(30);
        var transcript = (List<Map<String, Object>>) input.get("transcript");
        if (transcript == null) transcript = List.of();
        String text = transcript.stream()
                .map(c -> (String) c.getOrDefault("text", ""))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        String lower = text.toLowerCase();
        String requestType = lower.contains("route") ? "routeQuery"
                : lower.contains("anomaly") || lower.contains("accident") ? "anomalyReport"
                : "unknown";
        String destination = lower.contains("warehouse") ? "warehouse-7" : "";
        String anomalyType = requestType.equals("anomalyReport") ? "road-incident" : "";
        return Map.of(
                "requestType", requestType,
                "rawText", text,
                "destination", destination,
                "anomalyType", anomalyType,
                "driverId", input.getOrDefault("driverId", ""));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ROUTE_OPTIMIZER = (input, ctx) -> {
        Thread.sleep(40);
        String driverId = (String) input.getOrDefault("driverId", ctx.graphContext().get("driverId", String.class));
        String destination = (String) input.getOrDefault("destination", "");
        return Map.of(
                "driverId", driverId,
                "routeId", "ROUTE-" + driverId + "-WH7",
                "eta", "14:35",
                "waypoints", List.of("Depot-A", "Junction-12", destination.isEmpty() ? "Warehouse-7" : destination));
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ANOMALY_LOGGER = (input, ctx) -> {
        Thread.sleep(30);
        String driverId = (String) input.getOrDefault("driverId", ctx.graphContext().get("driverId", String.class));
        String anomalyType = (String) input.getOrDefault("anomalyType", "unknown");
        String description = (String) input.getOrDefault("description", "");
        return Map.of(
                "anomalyId", "ANO-" + System.currentTimeMillis(),
                "driverId", driverId,
                "anomalyType", anomalyType,
                "description", description);
    };

    static final Operator<Map<String, Object>, Map<String, Object>> DISPATCHER_NOTIFIER = (input, ctx) -> {
        Thread.sleep(25);
        String dispatchId = ctx.graphContext().get("dispatchId", String.class);
        return Map.of(
                "anomalyId", input.getOrDefault("anomalyId", ""),
                "driverId", input.getOrDefault("driverId", ""),
                "dispatcherId", dispatchId,
                "status", "NOTIFIED");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> ACKNOWLEDGEMENT_GENERATOR = (input, ctx) -> {
        Thread.sleep(15);
        String driverId = (String) input.getOrDefault("driverId", ctx.graphContext().get("driverId", String.class));
        String requestType = (String) input.getOrDefault("requestType", "unknown");
        return Map.of(
                "driverId", driverId,
                "requestType", requestType,
                "message", "Request acknowledged");
    };

    static final Operator<Map<String, Object>, Map<String, Object>> VOICE_RESPONSE_SENDER = (input, ctx) -> {
        Thread.sleep(20);
        String driverId = (String) input.getOrDefault("driverId", "");
        var chunks = (List<Map<String, Object>>) input.getOrDefault("audioChunks", List.of());
        return Map.of(
                "driverId", driverId,
                "responseId", "RESP-" + driverId,
                "chunksDelivered", chunks.size());
    };

    // ── DSL string ────────────────────────────────────────────────────────────

    static final String DSL = """
            graph voiceLogisticsDispatch {

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

              node parseDriverRequest : DriverRequestParser {
                depends_on = [speechToText]
                input {
                  /// .output materialises the full List<token> into a single collection before DriverRequestParser runs
                  transcript = speechToText.output
                  driverId   = ctx.driverId
                }
                timeout = 5s
              }

              node queryOptimalRoute : RouteOptimizer {
                depends_on = [parseDriverRequest]
                input {
                  driverId        = ctx.driverId
                  currentLoc      = ctx.currentLocation
                  destination     = parseDriverRequest.output.destination
                }
                timeout = 10s
              }

              node logAnomaly : AnomalyLogger {
                depends_on = [parseDriverRequest]
                input {
                  driverId    = ctx.driverId
                  anomalyType = parseDriverRequest.output.anomalyType
                  description = parseDriverRequest.output.rawText
                }
              }

              node notifyDispatcher : DispatcherNotifier {
                depends_on = [logAnomaly]
                input {
                  anomalyId  = logAnomaly.output.anomalyId
                  driverId   = ctx.driverId
                  dispatchId = ctx.dispatchId
                }
              }

              node acknowledgement : AcknowledgementGenerator {
                depends_on = [parseDriverRequest]
                input {
                  driverId    = ctx.driverId
                  requestType = parseDriverRequest.output.requestType
                }
              }

              /// BRANCH: evaluates requestType; exactly one branch node executes, others are skipped
              branch on parseDriverRequest.output.requestType {
                "routeQuery"    -> queryOptimalRoute
                "anomalyReport" -> logAnomaly
                otherwise       -> acknowledgement
              }

              /// STREAM NODE: synthesises TTS audio and emits speech chunks via NodeChannel without blocking
              stream node textToSpeech : TtsOperator {
                depends_on = [parseDriverRequest]
                input {
                  text     = parseDriverRequest.output.rawText
                  language = ctx.language
                }
                buffer = 32
              }

              node sendVoiceResponse : VoiceResponseSender {
                depends_on = [textToSpeech]
                input {
                  driverId   = ctx.driverId
                  /// .output on a stream node materialises the full List<chunk> before VoiceResponseSender runs
                  audioChunks = textToSpeech.output
                }
              }
            }
            """;

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // NOTE: registerRaw is required for StreamingOperator implementations; register is for standard Operator<?,?> only

        // Streaming operators — registered by PascalCase operatorRef via registerRaw
        // AUDIO_CAPTURE: no input → emits {sequenceId, sampleRate, bytes} chunks via NodeChannel
        registry.registerRaw("AudioCapture", AUDIO_CAPTURE);
        // SPEECH_TO_TEXT: reads input.audio (List<chunk>) → emits {sequenceId, text, confidence} tokens via NodeChannel
        registry.registerRaw("SpeechToText", SPEECH_TO_TEXT);
        // TTS_OPERATOR: reads text, language → emits {sequenceId, text, language} speech chunks via NodeChannel
        registry.registerRaw("TtsOperator", TTS_OPERATOR);

        // Normal operators
        // DRIVER_REQUEST_PARSER: reads transcript (List<token>), driverId → returns {requestType, rawText, destination, anomalyType, driverId}
        registry.register("DriverRequestParser", DRIVER_REQUEST_PARSER);
        // ROUTE_OPTIMIZER: reads driverId, currentLoc, destination → returns {driverId, routeId, eta, waypoints}
        registry.register("RouteOptimizer", ROUTE_OPTIMIZER);
        // ANOMALY_LOGGER: reads driverId, anomalyType, description → returns {anomalyId, driverId, anomalyType, description}
        registry.register("AnomalyLogger", ANOMALY_LOGGER);
        // DISPATCHER_NOTIFIER: reads anomalyId, driverId, dispatchId → returns {anomalyId, driverId, dispatcherId, status}
        registry.register("DispatcherNotifier", DISPATCHER_NOTIFIER);
        // ACKNOWLEDGEMENT_GENERATOR: reads driverId, requestType → returns {driverId, requestType, message}
        registry.register("AcknowledgementGenerator", ACKNOWLEDGEMENT_GENERATOR);
        // VOICE_RESPONSE_SENDER: reads driverId, audioChunks (List) → returns {driverId, responseId, chunksDelivered}
        registry.register("VoiceResponseSender", VOICE_RESPONSE_SENDER);

        var loader = new GraphLoader(registry);
        // compile DSL; operators resolved by PascalCase name
        Graph graph = loader.load(DSL);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "driverId", "DRV-001",
                "dispatchId", "DISP-007",
                "language", "en",
                "currentLocation", "Depot-A"
        ));

        // execute; streaming results accessible via result.results()
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  DSL Voice Logistics Dispatch Result");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-24s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("parseDriverRequest") == NodeStatus.COMPLETED) {
            System.out.println("  Parse result      : " + result.results().getRaw("parseDriverRequest"));
        }

        if (result.getStatus("queryOptimalRoute") == NodeStatus.COMPLETED) {
            System.out.println("  Route             : " + result.results().getRaw("queryOptimalRoute"));
        }

        if (result.getStatus("logAnomaly") == NodeStatus.COMPLETED) {
            System.out.println("  Anomaly log       : " + result.results().getRaw("logAnomaly"));
        }

        if (result.getStatus("notifyDispatcher") == NodeStatus.COMPLETED) {
            System.out.println("  Notification      : " + result.results().getRaw("notifyDispatcher"));
        }

        if (result.getStatus("acknowledgement") == NodeStatus.COMPLETED) {
            System.out.println("  Acknowledgement   : " + result.results().getRaw("acknowledgement"));
        }

        if (result.getStatus("textToSpeech") == NodeStatus.COMPLETED) {
            List<Map<String, Object>> ttsChunks = (List<Map<String, Object>>) result.results().getRaw("textToSpeech");
            System.out.println("  TTS chunks        : " + ttsChunks.size());
        }

        if (result.getStatus("sendVoiceResponse") == NodeStatus.COMPLETED) {
            System.out.println("  Voice response    : " + result.results().getRaw("sendVoiceResponse"));
        }

        System.out.println("═══════════════════════════════════════════════");
    }
}
