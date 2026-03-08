package com.leanowtech.bloge.examples.emergency;

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
 * DSL variant of the voice emergency dispatch pipeline.
 *
 * <p>All operators use {@code Map<String,Object>} I/O and are registered by PascalCase name
 * so the DSL compiler can resolve them by the type identifiers in the graph definition.
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
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceEmergencyDispatchDslExample {

    // ── Streaming operators ───────────────────────────────────────────────────

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> AUDIO_CAPTURE =
            (input, channel, ctx) -> {
                System.out.println("    [AudioCapture] Starting emergency call audio capture...");
                for (int i = 0; i < 6; i++) {
                    Thread.sleep(20);
                    channel.send(Map.of("sequenceId", i, "sampleRate", 16000, "bytes", i * 1024));
                    System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
                }
                System.out.println("    [AudioCapture] Audio capture complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> SPEECH_TO_TEXT =
            (input, channel, ctx) -> {
                var audioChunks = (List<Map<String, Object>>) input.get("audio");
                System.out.println("    [SpeechToText] Processing " + audioChunks.size() + " audio chunks...");
                String[] words = {"fire", "at", "main", "street", "building", "burning"};
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

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> REALTIME_TRANSLATOR =
            (input, channel, ctx) -> {
                var transcript = (List<Map<String, Object>>) input.get("transcript");
                String targetLang = (String) input.getOrDefault("targetLang", "es");
                System.out.println("    [RealtimeTranslator] Translating "
                        + (transcript != null ? transcript.size() : 0) + " chunks → " + targetLang);
                String[] translations = {"fuego", "en", "calle principal"};
                for (int i = 0; i < 3; i++) {
                    Thread.sleep(25);
                    channel.send(Map.of("sequenceId", i, "text", translations[i], "targetLang", targetLang));
                    System.out.printf("    [RealtimeTranslator] Chunk #%d → \"%s\" [%s]%n",
                            i, translations[i], targetLang);
                }
                System.out.println("    [RealtimeTranslator] Translation complete");
            };

    // ── Normal operators ──────────────────────────────────────────────────────

    static final Operator<Map<String, Object>, Map<String, Object>> CALLER_LOCATION_DETECTOR =
            (input, ctx) -> {
                // Simulates a GPS / cell-tower lookup that takes ~80 ms.
                // Runs in parallel with speechToText (both depend only on audioCapture)
                // so location acquisition overlaps with transcription for lower total latency.
                // Returns { callId, coordinates, city, accuracy }.
                Thread.sleep(80);
                String callId = (String) input.getOrDefault("callId", "");
                System.out.println("    [CallerLocation] GPS fix acquired for call " + callId);
                return Map.of(
                        "callId", callId,
                        "coordinates", "40.7128,-74.0060",
                        "city", "New York",
                        "accuracy", "GPS");
            };

    static final Operator<Map<String, Object>, Map<String, Object>> EMERGENCY_CLASSIFIER =
            (input, ctx) -> {
                // Waits for BOTH speechToText and detectCallerLocation (multi-dependency fan-in).
                // input.get("transcript") = List<Map> from speechToText.output
                // input.get("location")   = Map from detectCallerLocation.output
                // Returns { emergencyType, severity, description } used by branch and logDispatchRecord.
                Thread.sleep(30);
                var transcript = (List<Map<String, Object>>) input.get("transcript");
                if (transcript == null) transcript = List.of();
                boolean hasFire = transcript.stream().anyMatch(tc ->
                        "fire".equalsIgnoreCase((String) tc.getOrDefault("text", ""))
                        || "burning".equalsIgnoreCase((String) tc.getOrDefault("text", "")));
                boolean hasMedical = transcript.stream().anyMatch(tc ->
                        "medical".equalsIgnoreCase((String) tc.getOrDefault("text", ""))
                        || "ambulance".equalsIgnoreCase((String) tc.getOrDefault("text", "")));
                String emergencyType = hasFire ? "fire" : hasMedical ? "medical" : "crime";
                String severity = (hasFire || hasMedical) ? "high" : "medium";
                var location = (Map<String, Object>) input.getOrDefault("location", Map.of());
                String city = (String) location.getOrDefault("city", "unknown");
                System.out.printf("    [EmergencyClassifier] Classified as %s / %s in %s%n",
                        emergencyType, severity, city);
                return Map.of(
                        "emergencyType", emergencyType,
                        "severity", severity,
                        "description", "Emergency at " + city);
            };

    static final Operator<Map<String, Object>, Map<String, Object>> FIRE_DEPT_DISPATCHER =
            (input, ctx) -> {
                Thread.sleep(25);
                String callId = (String) input.getOrDefault("callId", "");
                System.out.println("    [FireDeptDispatcher] Dispatching 3 fire units for call " + callId);
                return Map.of("callId", callId, "unitType", "fire-engine", "eta", "4 min", "unitsDispatched", 3);
            };

    static final Operator<Map<String, Object>, Map<String, Object>> AMBULANCE_DISPATCHER =
            (input, ctx) -> {
                Thread.sleep(25);
                String callId = (String) input.getOrDefault("callId", "");
                System.out.println("    [AmbulanceDispatcher] Dispatching 2 ambulances for call " + callId);
                return Map.of("callId", callId, "unitType", "ambulance", "eta", "6 min", "unitsDispatched", 2);
            };

    static final Operator<Map<String, Object>, Map<String, Object>> POLICE_DISPATCHER =
            (input, ctx) -> {
                Thread.sleep(25);
                String callId = (String) input.getOrDefault("callId", "");
                System.out.println("    [PoliceDispatcher] Dispatching 4 police units for call " + callId);
                return Map.of("callId", callId, "unitType", "police-car", "eta", "3 min", "unitsDispatched", 4);
            };

    static final Operator<Map<String, Object>, Map<String, Object>> DISPATCH_LOGGER =
            (input, ctx) -> {
                Thread.sleep(20);
                String callId = (String) input.getOrDefault("callId", "unknown");
                String emergencyType = (String) input.getOrDefault("emergencyType", "unknown");
                System.out.printf("    [DispatchLogger] Recording %s dispatch for call %s%n",
                        emergencyType, callId);
                return Map.of(
                        "callId", callId,
                        "logId", "LOG-" + callId,
                        "timestamp", String.valueOf(System.currentTimeMillis()),
                        "emergencyType", emergencyType,
                        "location", input.getOrDefault("location", "unknown"));
            };

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // Streaming operators registered by PascalCase type name matching DSL identifiers.
        // registerRaw() is required because StreamingOperator is not a subtype of Operator<?,?>.
        registry.registerRaw("AudioCapture", AUDIO_CAPTURE);
        registry.registerRaw("SpeechToText", SPEECH_TO_TEXT);
        registry.registerRaw("RealtimeTranslator", REALTIME_TRANSLATOR);

        // Normal operators — registerRaw() used here too for convenience (accepts any Object).
        // CallerLocationDetector has no depends_on speechToText so it runs in parallel.
        registry.registerRaw("CallerLocationDetector", CALLER_LOCATION_DETECTOR);
        registry.registerRaw("EmergencyClassifier", EMERGENCY_CLASSIFIER);
        registry.registerRaw("FireDeptDispatcher", FIRE_DEPT_DISPATCHER);
        registry.registerRaw("AmbulanceDispatcher", AMBULANCE_DISPATCHER);
        registry.registerRaw("PoliceDispatcher", POLICE_DISPATCHER);
        registry.registerRaw("DispatchLogger", DISPATCH_LOGGER);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph voiceEmergencyDispatch {

                  /// Audio source — starts immediately; feeds both STT and location detector.
                  stream node audioCapture : AudioCapture {
                    buffer = 64
                  }

                  /// Transcription of the emergency call.
                  /// audio = audioCapture.output uses DirectEdge (waits for all chunks).
                  stream node speechToText : SpeechToText {
                    input {
                      audio = audioCapture.output
                    }
                    buffer = 32
                  }

                  /// GPS / cell-tower location lookup — starts in parallel with speechToText.
                  /// depends_on = [audioCapture] (not speechToText) so it fires as soon as
                  /// any audio has been received, overlapping with transcription.
                  node detectCallerLocation : CallerLocationDetector {
                    depends_on = [audioCapture]
                    input {
                      callId = ctx.callId
                    }
                    timeout = 5s
                  }

                  /// Classification fan-in: waits for BOTH transcription and location.
                  /// depends_on = [speechToText, detectCallerLocation] — the engine holds
                  /// this node until both upstreams have completed.
                  node classifyEmergency : EmergencyClassifier {
                    depends_on = [speechToText, detectCallerLocation]
                    input {
                      transcript = speechToText.output
                      location   = detectCallerLocation.output
                    }
                    timeout = 5s
                  }

                  /// Fire dispatch — only active when emergencyType = "fire".
                  node dispatchFireDept : FireDeptDispatcher {
                    depends_on = [classifyEmergency]
                    input {
                      callId   = ctx.callId
                      location = detectCallerLocation.output.coordinates
                      severity = classifyEmergency.output.severity
                    }
                  }

                  /// Ambulance dispatch — only active when emergencyType = "medical".
                  node dispatchAmbulance : AmbulanceDispatcher {
                    depends_on = [classifyEmergency]
                    input {
                      callId   = ctx.callId
                      location = detectCallerLocation.output.coordinates
                      severity = classifyEmergency.output.severity
                    }
                  }

                  /// Police dispatch — catch-all for all other emergency types.
                  node dispatchPolice : PoliceDispatcher {
                    depends_on = [classifyEmergency]
                    input {
                      callId   = ctx.callId
                      location = detectCallerLocation.output.coordinates
                      severity = classifyEmergency.output.severity
                    }
                  }

                  /// Routes to exactly one dispatcher; unmatched nodes are SKIPPED.
                  branch on classifyEmergency.output.emergencyType {
                    "fire"    -> dispatchFireDept
                    "medical" -> dispatchAmbulance
                    otherwise -> dispatchPolice
                  }

                  /// Real-time translation — runs in parallel with the dispatch branch.
                  /// depends_on = [speechToText] (not on branch targets) so it starts
                  /// immediately after transcription, regardless of dispatch outcome.
                  /// buffer=16 keeps chunk sizes small for near-real-time delivery.
                  stream node realtimeTranslation : RealtimeTranslator {
                    depends_on = [speechToText]
                    input {
                      transcript = speechToText.output
                      targetLang = ctx.targetLanguage
                    }
                    buffer = 16
                  }

                  /// Incident log — waits for both classification and full translation.
                  /// The dot-path detectCallerLocation.output.coordinates extracts a
                  /// nested field from the location detector's output map.
                  node logDispatchRecord : DispatchLogger {
                    depends_on = [classifyEmergency, realtimeTranslation]
                    input {
                      callId        = ctx.callId
                      emergencyType = classifyEmergency.output.emergencyType
                      location      = detectCallerLocation.output.coordinates
                      translation   = realtimeTranslation.output
                    }
                  }
                }
                """;

        Graph graph = loader.load(dsl);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        var ctx = new GraphContext(Map.of(
                "callId", "CALL-911-001",
                "targetLanguage", "es"
        ));

        GraphResult result = engine.execute(graph, ctx);

        // ── Results ──────────────────────────────────────────────────────────
        System.out.println("\n═══════════════════════════════════════════════");
        System.out.println("  DSL Voice Emergency Dispatch Result");
        System.out.println("═══════════════════════════════════════════════");
        System.out.println("  Success : " + result.isSuccess());
        System.out.println("  Elapsed : " + result.elapsed().toMillis() + " ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-26s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("detectCallerLocation") == NodeStatus.COMPLETED) {
            System.out.println("  Location      : " + result.results().getRaw("detectCallerLocation"));
        }

        if (result.getStatus("classifyEmergency") == NodeStatus.COMPLETED) {
            System.out.println("  Classification: " + result.results().getRaw("classifyEmergency"));
        }

        if (result.getStatus("dispatchFireDept") == NodeStatus.COMPLETED) {
            System.out.println("  Fire dispatch : " + result.results().getRaw("dispatchFireDept"));
        }
        if (result.getStatus("dispatchAmbulance") == NodeStatus.COMPLETED) {
            System.out.println("  Ambulance     : " + result.results().getRaw("dispatchAmbulance"));
        }
        if (result.getStatus("dispatchPolice") == NodeStatus.COMPLETED) {
            System.out.println("  Police        : " + result.results().getRaw("dispatchPolice"));
        }

        if (result.getStatus("realtimeTranslation") == NodeStatus.COMPLETED) {
            List<Map<String, Object>> translations =
                    (List<Map<String, Object>>) result.results().getRaw("realtimeTranslation");
            System.out.println("  Translations  : " + translations.size() + " chunks");
            translations.forEach(tc ->
                    System.out.printf("    #%s → \"%s\" [%s]%n",
                            tc.get("sequenceId"), tc.get("text"), tc.get("targetLang")));
        }

        if (result.getStatus("logDispatchRecord") == NodeStatus.COMPLETED) {
            System.out.println("  Log record    : " + result.results().getRaw("logDispatchRecord"));
        }

        System.out.println("═══════════════════════════════════════════════");
    }
}
