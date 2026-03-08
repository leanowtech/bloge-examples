package com.leanowtech.bloge.examples.emergency;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates a real-time voice emergency dispatch pipeline using streaming operators,
 * parallel fan-out, emergency classification, branch-based unit dispatch, and live translation.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture
 *         │
 *    ┌────┴──────────────────────────────────────┐
 *    ▼                                           ▼
 * stream speechToText                  detectCallerLocation
 *    │                                           │
 *    ├── stream realtimeTranslation (buf=16)     │
 *    │              │                            │
 *    └─────────► classifyEmergency ◄─────────────┘
 *                       │
 *                branch on emergencyType
 *                       ├── "fire"    → dispatchFireDept
 *                       ├── "medical" → dispatchAmbulance
 *                       └── otherwise → dispatchPolice
 *
 * classifyEmergency + realtimeTranslation → logDispatchRecord
 * </pre>
 *
 * <p>Run {@link #main(String[])} to execute the pipeline with a simulated 911 call.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceEmergencyDispatchExample {

    // ── Domain records ────────────────────────────────────────────────────────

    public record AudioChunk(int sequenceId, byte[] samples, int sampleRate) {}

    public record TextChunk(int sequenceId, String text, double confidence) {}

    public record LocationResult(String callId, String coordinates, String city, String accuracy) {}

    public record ClassifyInput(List<TextChunk> transcript, LocationResult location) {}

    public record ClassifyResult(String emergencyType, String severity, String description) {}

    public record DispatchResult(String callId, String unitType, String eta, int unitsDispatched) {}

    public record TranslationChunk(int sequenceId, String text, String targetLang) {}

    public record LogResult(String callId, String logId, String timestamp) {}

    // ── Streaming operators ───────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"emergency", "audio"},
            description = "Captures emergency call audio from the telephony channel", owner = "media-team")
    static final StreamingOperator<Void, AudioChunk> AUDIO_CAPTURE = (input, channel, ctx) -> {
        System.out.println("    [AudioCapture] Starting emergency call audio capture...");
        for (int i = 0; i < 6; i++) {
            Thread.sleep(20);
            channel.send(new AudioChunk(i, new byte[1024], 16000));
            System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
        }
        System.out.println("    [AudioCapture] Audio capture complete");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"emergency", "stt"},
            description = "Converts emergency audio chunks to text tokens in real-time", owner = "ai-team")
    static final StreamingOperator<List<AudioChunk>, TextChunk> SPEECH_TO_TEXT = (input, channel, ctx) -> {
        System.out.println("    [SpeechToText] Processing " + input.size() + " audio chunks...");
        String[] words = {"fire", "at", "main", "street", "building", "burning"};
        int idx = 0;
        for (AudioChunk chunk : input) {
            Thread.sleep(20);
            String word = words[idx % words.length];
            channel.send(new TextChunk(chunk.sequenceId(), word, 0.94 - idx * 0.01));
            System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", chunk.sequenceId(), word);
            idx++;
        }
        System.out.println("    [SpeechToText] Transcription complete");
    };

    // ── Normal operators ──────────────────────────────────────────────────────

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"emergency", "location"},
            description = "Detects caller GPS coordinates via carrier lookup", owner = "telecom-team")
    static final Operator<Map<String, Object>, LocationResult> DETECT_CALLER_LOCATION = (input, ctx) -> {
        Thread.sleep(80);
        String callId = (String) input.get("callId");
        System.out.println("    [CallerLocation] GPS fix acquired for call " + callId);
        return new LocationResult(callId, "40.7128,-74.0060", "New York", "GPS");
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"emergency", "classification"},
            description = "Classifies emergency type and severity from transcript and caller location",
            owner = "dispatch-team")
    static final Operator<ClassifyInput, ClassifyResult> CLASSIFY_EMERGENCY = (input, ctx) -> {
        Thread.sleep(30);
        boolean hasFire = input.transcript().stream()
                .anyMatch(tc -> "fire".equalsIgnoreCase(tc.text()) || "burning".equalsIgnoreCase(tc.text()));
        boolean hasMedical = input.transcript().stream()
                .anyMatch(tc -> "medical".equalsIgnoreCase(tc.text()) || "ambulance".equalsIgnoreCase(tc.text()));
        String emergencyType = hasFire ? "fire" : hasMedical ? "medical" : "crime";
        String severity = (hasFire || hasMedical) ? "high" : "medium";
        String description = "Emergency at " + input.location().city()
                + " [" + input.location().coordinates() + "] via " + input.location().accuracy();
        System.out.printf("    [ClassifyEmergency] Classified as %s / %s%n", emergencyType, severity);
        return new ClassifyResult(emergencyType, severity, description);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"emergency", "dispatch"},
            description = "Dispatches fire department units to the emergency location", owner = "dispatch-team")
    static final Operator<ClassifyResult, DispatchResult> DISPATCH_FIRE = (input, ctx) -> {
        Thread.sleep(25);
        System.out.println("    [DispatchFire] Dispatching 3 fire units, severity=" + input.severity());
        return new DispatchResult(ctx.graphContext().get("callId", String.class), "fire-engine", "4 min", 3);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"emergency", "dispatch"},
            description = "Dispatches ambulance units to the emergency location", owner = "dispatch-team")
    static final Operator<ClassifyResult, DispatchResult> DISPATCH_AMBULANCE = (input, ctx) -> {
        Thread.sleep(25);
        System.out.println("    [DispatchAmbulance] Dispatching 2 ambulances, severity=" + input.severity());
        return new DispatchResult(ctx.graphContext().get("callId", String.class), "ambulance", "6 min", 2);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"emergency", "dispatch"},
            description = "Dispatches police units to the emergency location", owner = "dispatch-team")
    static final Operator<ClassifyResult, DispatchResult> DISPATCH_POLICE = (input, ctx) -> {
        Thread.sleep(25);
        System.out.println("    [DispatchPolice] Dispatching 4 police units, severity=" + input.severity());
        return new DispatchResult(ctx.graphContext().get("callId", String.class), "police-car", "3 min", 4);
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"emergency", "translation"},
            description = "Translates emergency transcript chunks to target language in real-time",
            owner = "ai-team")
    static final StreamingOperator<Map<String, Object>, TranslationChunk> REALTIME_TRANSLATOR =
            (input, channel, ctx) -> {
                var transcript = (List<TextChunk>) input.get("transcript");
                String targetLang = (String) input.getOrDefault("targetLang", "es");
                System.out.println("    [RealtimeTranslator] Translating "
                        + transcript.size() + " chunks → " + targetLang);
                String[] translations = {"fuego", "en", "calle principal"};
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(25);
                    channel.send(new TranslationChunk(i, translations[i], targetLang));
                    System.out.printf("    [RealtimeTranslator] Chunk #%d → \"%s\" [%s]%n",
                            i, translations[i], targetLang);
                }
                System.out.println("    [RealtimeTranslator] Translation complete");
            };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"emergency", "logging"},
            description = "Logs the full dispatch record to the CAD system", owner = "ops-team")
    static final Operator<Map<String, Object>, LogResult> LOG_DISPATCH = (input, ctx) -> {
        Thread.sleep(20);
        String callId = (String) input.getOrDefault("callId", "unknown");
        String emergencyType = (String) input.getOrDefault("emergencyType", "unknown");
        System.out.printf("    [LogDispatch] Recording %s dispatch for call %s%n", emergencyType, callId);
        return new LogResult(callId, "LOG-" + callId, String.valueOf(System.currentTimeMillis()));
    };

    // ── Graph construction ────────────────────────────────────────────────────

    public static Graph buildGraph() {
        return Graph.builder("voiceEmergencyDispatch")
                // Streaming source — real op registered in registry by node id
                .node("audioCapture", (input, ctx) -> null)
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                // Parallel fan-out: streaming speech-to-text
                .node("speechToText", (input, ctx) -> null)
                    .dependsOn("audioCapture")
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                    .input((results, ctx) -> (List<AudioChunk>) results.getRaw("audioCapture"))
                // Parallel fan-out: caller location lookup
                .node("detectCallerLocation", DETECT_CALLER_LOCATION)
                    .dependsOn("audioCapture")
                    .input((results, ctx) -> Map.of("callId", ctx.get("callId", String.class)))
                // Converge: classify emergency (waits for both parallel branches)
                .node("classifyEmergency", CLASSIFY_EMERGENCY)
                    .dependsOn("speechToText", "detectCallerLocation")
                    .input((results, ctx) -> new ClassifyInput(
                            (List<TextChunk>) results.getRaw("speechToText"),
                            results.get("detectCallerLocation", LocationResult.class)))
                // Branch dispatch targets — exactly one will execute based on emergencyType
                .node("dispatchFireDept", DISPATCH_FIRE)
                    .dependsOn("classifyEmergency")
                    .input((results, ctx) -> results.get("classifyEmergency", ClassifyResult.class))
                .node("dispatchAmbulance", DISPATCH_AMBULANCE)
                    .dependsOn("classifyEmergency")
                    .input((results, ctx) -> results.get("classifyEmergency", ClassifyResult.class))
                .node("dispatchPolice", DISPATCH_POLICE)
                    .dependsOn("classifyEmergency")
                    .input((results, ctx) -> results.get("classifyEmergency", ClassifyResult.class))
                // Branch on emergencyType field from ClassifyResult
                .branch("classifyEmergency")
                    .on("emergencyType")
                    .when(v -> "fire".equals(v), "dispatchFireDept")
                    .when(v -> "medical".equals(v), "dispatchAmbulance")
                    .otherwise("dispatchPolice")
                // Parallel streaming translator — fans out from speechToText independently
                .node("realtimeTranslation", (input, ctx) -> null)
                    .dependsOn("speechToText")
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "16")
                    .input((results, ctx) -> Map.of(
                            "transcript", results.getRaw("speechToText"),
                            "targetLang", ctx.get("targetLanguage", String.class)))
                // Converge: log dispatch record (waits for classify + translation)
                .node("logDispatchRecord", LOG_DISPATCH)
                    .dependsOn("classifyEmergency", "realtimeTranslation")
                    .input((results, ctx) -> {
                        var map = new HashMap<String, Object>();
                        var classify = results.get("classifyEmergency", ClassifyResult.class);
                        map.put("callId", ctx.get("callId", String.class));
                        map.put("emergencyType", classify.emergencyType());
                        map.put("severity", classify.severity());
                        map.put("description", classify.description());
                        map.put("translation", results.getRaw("realtimeTranslation"));
                        if (results.hasResult("dispatchFireDept"))
                            map.put("dispatch", results.getRaw("dispatchFireDept"));
                        else if (results.hasResult("dispatchAmbulance"))
                            map.put("dispatch", results.getRaw("dispatchAmbulance"));
                        else if (results.hasResult("dispatchPolice"))
                            map.put("dispatch", results.getRaw("dispatchPolice"));
                        return map;
                    })
                .build();
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Streaming operators must be registered by node id via registerRaw
        registry.registerRaw("audioCapture", AUDIO_CAPTURE);
        registry.registerRaw("speechToText", SPEECH_TO_TEXT);
        registry.registerRaw("realtimeTranslation", REALTIME_TRANSLATOR);

        // Normal operators
        registry.registerRaw("detectCallerLocation", DETECT_CALLER_LOCATION);
        registry.registerRaw("classifyEmergency", CLASSIFY_EMERGENCY);
        registry.registerRaw("dispatchFireDept", DISPATCH_FIRE);
        registry.registerRaw("dispatchAmbulance", DISPATCH_AMBULANCE);
        registry.registerRaw("dispatchPolice", DISPATCH_POLICE);
        registry.registerRaw("logDispatchRecord", LOG_DISPATCH);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of(
                "callId", "CALL-911-001",
                "targetLanguage", "es"
        ));

        GraphResult result = engine.execute(graph, ctx);

        // ── Results ──────────────────────────────────────────────────────────
        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  Voice Emergency Dispatch Result");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-26s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("detectCallerLocation") == NodeStatus.COMPLETED) {
            LocationResult loc = result.getOutput("detectCallerLocation", LocationResult.class);
            System.out.println("  Location       : " + loc.coordinates() + " (" + loc.city() + ") via " + loc.accuracy());
        }

        if (result.getStatus("classifyEmergency") == NodeStatus.COMPLETED) {
            ClassifyResult classify = result.getOutput("classifyEmergency", ClassifyResult.class);
            System.out.println("  Emergency type : " + classify.emergencyType());
            System.out.println("  Severity       : " + classify.severity());
            System.out.println("  Description    : " + classify.description());
        }

        if (result.getStatus("dispatchFireDept") == NodeStatus.COMPLETED) {
            DispatchResult dispatch = result.getOutput("dispatchFireDept", DispatchResult.class);
            System.out.printf("  Fire dispatch  : %d %s, ETA %s%n",
                    dispatch.unitsDispatched(), dispatch.unitType(), dispatch.eta());
        }
        if (result.getStatus("dispatchAmbulance") == NodeStatus.COMPLETED) {
            DispatchResult dispatch = result.getOutput("dispatchAmbulance", DispatchResult.class);
            System.out.printf("  Ambulance      : %d %s, ETA %s%n",
                    dispatch.unitsDispatched(), dispatch.unitType(), dispatch.eta());
        }
        if (result.getStatus("dispatchPolice") == NodeStatus.COMPLETED) {
            DispatchResult dispatch = result.getOutput("dispatchPolice", DispatchResult.class);
            System.out.printf("  Police         : %d %s, ETA %s%n",
                    dispatch.unitsDispatched(), dispatch.unitType(), dispatch.eta());
        }

        if (result.getStatus("realtimeTranslation") == NodeStatus.COMPLETED) {
            List<TranslationChunk> translations =
                    (List<TranslationChunk>) result.results().getRaw("realtimeTranslation");
            System.out.println("  Translations   : " + translations.size() + " chunks");
            translations.forEach(tc ->
                    System.out.printf("    #%d → \"%s\" [%s]%n", tc.sequenceId(), tc.text(), tc.targetLang()));
        }

        if (result.getStatus("logDispatchRecord") == NodeStatus.COMPLETED) {
            LogResult log = result.getOutput("logDispatchRecord", LogResult.class);
            System.out.println("  Log record     : " + log.logId() + " @ " + log.timestamp());
        }

        System.out.println("═══════════════════════════════════════════════");
    }
}
