package com.leanowtech.bloge.examples.logistics;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorLayer;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;

/**
 * Demonstrates a voice-driven logistics dispatch pipeline using streaming audio
 * ingestion, speech-to-text transcription, driver request parsing, branch-based
 * dispatch routing, and streaming text-to-speech response delivery.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture
 *   → stream speechToText
 *   → parseDriverRequest
 *        │
 *   branch on requestType
 *        ├── "routeQuery"    → queryOptimalRoute
 *        ├── "anomalyReport" → logAnomaly → notifyDispatcher
 *        └── otherwise       → acknowledgement
 *   → stream textToSpeech
 *   → sendVoiceResponse
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the pipeline with a simulated driver audio
 * input containing a route-query request.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceLogisticsDispatchExample {

    // ── Domain records ────────────────────────────────────────────────────────

    public record AudioChunk(int sequenceId, byte[] samples, int sampleRate) {}

    public record TextChunk(int sequenceId, String text, double confidence) {}

    public record ParseInput(List<TextChunk> transcript, String driverId) {}

    /** requestType: "routeQuery" | "anomalyReport" | "unknown" */
    public record ParseResult(String requestType, String rawText, String destination, String anomalyType) {}

    public record RouteResult(String driverId, String routeId, String eta, List<String> waypoints) {}

    public record AnomalyLog(String anomalyId, String driverId, String anomalyType, String description) {}

    public record DispatchNotification(String anomalyId, String driverId, String dispatcherId, String status) {}

    public record AckResult(String driverId, String requestType, String message) {}

    public record SpeechChunk(int sequenceId, String text, String language) {}

    public record VoiceResponseResult(String driverId, String responseId, int chunksDelivered) {}

    // ── Streaming operators ───────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"voice", "audio"},
            description = "Captures a continuous audio stream from the driver handset", owner = "media-team")
    static final StreamingOperator<Void, AudioChunk> AUDIO_CAPTURE = (input, channel, ctx) -> {
        System.out.println("    [AudioCapture] Starting audio capture...");
        for (int i = 0; i < 6; i++) {
            Thread.sleep(20);
            var chunk = new AudioChunk(i, new byte[1024], 16000);
            channel.send(chunk);
            System.out.printf("    [AudioCapture] Emitted chunk #%d (20ms, 16kHz)%n", i);
        }
        System.out.println("    [AudioCapture] Audio capture complete");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "stt"},
            description = "Converts audio chunks to text tokens in real-time", owner = "ai-team")
    static final StreamingOperator<List<AudioChunk>, TextChunk> SPEECH_TO_TEXT = (input, channel, ctx) -> {
        System.out.println("    [SpeechToText] Processing " + input.size() + " audio chunks...");
        String[] words = {"query", "route", "to", "warehouse", "seven", "eta"};
        int idx = 0;
        for (AudioChunk chunk : input) {
            Thread.sleep(20);
            String word = words[idx % words.length];
            var textChunk = new TextChunk(chunk.sequenceId(), word, 0.94 - idx * 0.01);
            channel.send(textChunk);
            System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", chunk.sequenceId(), word);
            idx++;
        }
        System.out.println("    [SpeechToText] Transcription complete");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "tts"},
            description = "Synthesises speech chunks from the dispatch response text", owner = "ai-team")
    static final StreamingOperator<Map<String, Object>, SpeechChunk> TEXT_TO_SPEECH = (input, channel, ctx) -> {
        String rawText = (String) input.getOrDefault("rawText", "Acknowledged");
        String language = (String) input.getOrDefault("language", "en");
        System.out.println("    [TextToSpeech] Synthesising response: \"" + rawText + "\"");
        String[] parts = rawText.split("\\s+");
        int chunks = Math.max(4, Math.min(parts.length, 4));
        for (int i = 0; i < chunks; i++) {
            Thread.sleep(15);
            var speechChunk = new SpeechChunk(i, parts[i % parts.length], language);
            channel.send(speechChunk);
            System.out.printf("    [TextToSpeech] Emitted speech chunk #%d → \"%s\"%n", i, speechChunk.text());
        }
        System.out.println("    [TextToSpeech] Synthesis complete");
    };

    // ── Normal operators ──────────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"logistics", "nlp"},
            description = "Parses the driver transcript to classify the request type", owner = "dispatch-team")
    static final Operator<ParseInput, ParseResult> PARSE_DRIVER_REQUEST = (input, ctx) -> {
        Thread.sleep(30);
        String transcript = input.transcript().stream()
                .map(TextChunk::text)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        String lower = transcript.toLowerCase();
        String requestType = lower.contains("route") ? "routeQuery"
                : lower.contains("anomaly") || lower.contains("accident") ? "anomalyReport"
                : "unknown";
        String destination = lower.contains("warehouse") ? "warehouse-7" : "";
        String anomalyType = requestType.equals("anomalyReport") ? "road-incident" : "";
        return new ParseResult(requestType, transcript, destination, anomalyType);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"logistics", "routing"},
            description = "Queries optimal route for the driver's destination", owner = "dispatch-team")
    static final Operator<ParseResult, RouteResult> QUERY_OPTIMAL_ROUTE = (input, ctx) -> {
        Thread.sleep(40);
        String driverId = ctx.graphContext().get("driverId", String.class);
        return new RouteResult(
                driverId,
                "ROUTE-" + driverId + "-WH7",
                "14:35",
                List.of("Depot-A", "Junction-12", "Warehouse-7"));
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"logistics", "anomaly"},
            description = "Logs an anomaly report received from the driver", owner = "dispatch-team")
    static final Operator<ParseResult, AnomalyLog> LOG_ANOMALY = (input, ctx) -> {
        Thread.sleep(30);
        String driverId = ctx.graphContext().get("driverId", String.class);
        return new AnomalyLog(
                "ANO-" + System.currentTimeMillis(),
                driverId,
                input.anomalyType(),
                input.rawText());
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"logistics", "notification"},
            description = "Notifies the dispatcher of a logged anomaly", owner = "dispatch-team")
    static final Operator<AnomalyLog, DispatchNotification> NOTIFY_DISPATCHER = (input, ctx) -> {
        Thread.sleep(25);
        String dispatcherId = ctx.graphContext().get("dispatchId", String.class);
        return new DispatchNotification(input.anomalyId(), input.driverId(), dispatcherId, "NOTIFIED");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"logistics", "ack"},
            description = "Generates a generic acknowledgement for unrecognised requests", owner = "dispatch-team")
    static final Operator<ParseResult, AckResult> GENERATE_ACKNOWLEDGEMENT = (input, ctx) -> {
        Thread.sleep(15);
        String driverId = ctx.graphContext().get("driverId", String.class);
        return new AckResult(driverId, input.requestType(), "Request acknowledged");
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"voice", "response"},
            description = "Sends the synthesised voice response back to the driver handset", owner = "media-team")
    static final Operator<Map<String, Object>, VoiceResponseResult> SEND_VOICE_RESPONSE = (input, ctx) -> {
        Thread.sleep(20);
        String driverId = (String) input.getOrDefault("driverId", "");
        var chunks = (List<SpeechChunk>) input.getOrDefault("chunks", List.of());
        return new VoiceResponseResult(driverId, "RESP-" + driverId, chunks.size());
    };

    // ── Graph construction ────────────────────────────────────────────────────

    public static Graph buildGraph() {
        return Graph.builder("voiceLogisticsDispatch")
                // ── Streaming audio ingestion
                .node("audioCapture", (input, ctx) -> null)
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "64")
                // ── Streaming speech-to-text
                .node("speechToText", (input, ctx) -> null)
                    .dependsOn("audioCapture")
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                    .input((results, ctx) -> (List<AudioChunk>) results.getRaw("audioCapture"))
                // ── Parse driver request (materialises stream)
                .node("parseDriverRequest", PARSE_DRIVER_REQUEST)
                    .dependsOn("speechToText")
                    .input((results, ctx) -> new ParseInput(
                            (List<TextChunk>) results.getRaw("speechToText"),
                            ctx.get("driverId", String.class)))
                // ── Branch targets
                .node("queryOptimalRoute", QUERY_OPTIMAL_ROUTE)
                    .dependsOn("parseDriverRequest")
                    .input((results, ctx) -> results.get("parseDriverRequest", ParseResult.class))
                .node("logAnomaly", LOG_ANOMALY)
                    .dependsOn("parseDriverRequest")
                    .input((results, ctx) -> results.get("parseDriverRequest", ParseResult.class))
                .node("notifyDispatcher", NOTIFY_DISPATCHER)
                    .dependsOn("logAnomaly")
                    .input((results, ctx) -> results.get("logAnomaly", AnomalyLog.class))
                .node("acknowledgement", GENERATE_ACKNOWLEDGEMENT)
                    .dependsOn("parseDriverRequest")
                    .input((results, ctx) -> results.get("parseDriverRequest", ParseResult.class))
                // ── Branch on requestType
                .branch("parseDriverRequest")
                    .on("requestType")
                    .when(v -> "routeQuery".equals(v), "queryOptimalRoute")
                    .when(v -> "anomalyReport".equals(v), "logAnomaly")
                    .otherwise("acknowledgement")
                // ── Streaming TTS response
                .node("textToSpeech", (input, ctx) -> null)
                    .dependsOn("parseDriverRequest")
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                    .input((results, ctx) -> {
                        var parsed = results.get("parseDriverRequest", ParseResult.class);
                        return Map.of(
                                "rawText", parsed.rawText(),
                                "language", ctx.get("language", String.class));
                    })
                // ── Send voice response (materialises TTS stream)
                .node("sendVoiceResponse", SEND_VOICE_RESPONSE)
                    .dependsOn("textToSpeech")
                    .input((results, ctx) -> Map.of(
                            "driverId", ctx.get("driverId", String.class),
                            "chunks", results.getRaw("textToSpeech")))
                .build();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Streaming operators — registered by node id via registerRaw
        registry.registerRaw("audioCapture", AUDIO_CAPTURE);
        registry.registerRaw("speechToText", SPEECH_TO_TEXT);
        registry.registerRaw("textToSpeech", TEXT_TO_SPEECH);

        // Normal operators
        registry.registerRaw("parseDriverRequest", PARSE_DRIVER_REQUEST);
        registry.registerRaw("queryOptimalRoute", QUERY_OPTIMAL_ROUTE);
        registry.registerRaw("logAnomaly", LOG_ANOMALY);
        registry.registerRaw("notifyDispatcher", NOTIFY_DISPATCHER);
        registry.registerRaw("acknowledgement", GENERATE_ACKNOWLEDGEMENT);
        registry.registerRaw("sendVoiceResponse", SEND_VOICE_RESPONSE);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of(
                "driverId", "DRV-001",
                "dispatchId", "DISP-007",
                "language", "en"
        ));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  Voice Logistics Dispatch Result");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-24s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("parseDriverRequest") == NodeStatus.COMPLETED) {
            ParseResult parsed = result.getOutput("parseDriverRequest", ParseResult.class);
            System.out.println("  Request type  : " + parsed.requestType());
            System.out.println("  Transcript    : " + parsed.rawText());
            System.out.println("  Destination   : " + parsed.destination());
        }

        if (result.getStatus("queryOptimalRoute") == NodeStatus.COMPLETED) {
            RouteResult route = result.getOutput("queryOptimalRoute", RouteResult.class);
            System.out.println("  Route         : " + route);
        }

        if (result.getStatus("logAnomaly") == NodeStatus.COMPLETED) {
            AnomalyLog anomaly = result.getOutput("logAnomaly", AnomalyLog.class);
            System.out.println("  Anomaly log   : " + anomaly);
        }

        if (result.getStatus("notifyDispatcher") == NodeStatus.COMPLETED) {
            DispatchNotification notification = result.getOutput("notifyDispatcher", DispatchNotification.class);
            System.out.println("  Notification  : " + notification);
        }

        if (result.getStatus("acknowledgement") == NodeStatus.COMPLETED) {
            AckResult ack = result.getOutput("acknowledgement", AckResult.class);
            System.out.println("  Acknowledgement: " + ack);
        }

        if (result.getStatus("textToSpeech") == NodeStatus.COMPLETED) {
            List<SpeechChunk> ttsChunks = (List<SpeechChunk>) result.results().getRaw("textToSpeech");
            System.out.println("  TTS chunks    : " + ttsChunks.size());
        }

        if (result.getStatus("sendVoiceResponse") == NodeStatus.COMPLETED) {
            VoiceResponseResult response = result.getOutput("sendVoiceResponse", VoiceResponseResult.class);
            System.out.println("  Voice response: " + response);
        }

        System.out.println("═══════════════════════════════════════════════");
    }
}
