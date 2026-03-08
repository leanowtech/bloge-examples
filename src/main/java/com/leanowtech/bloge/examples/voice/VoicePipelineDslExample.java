package com.leanowtech.bloge.examples.voice;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.util.List;
import java.util.Map;

/**
 * DSL-based voice processing pipeline loaded from {@code voice-pipeline.bloge}.
 *
 * <p>Compiles and executes the voice pipeline graph using {@link GraphLoader},
 * with streaming operators registered via {@link DefaultOperatorRegistry#registerRaw}.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture → stream speechToText → textAnalysis
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoicePipelineDslExample {

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> AUDIO_CAPTURE =
            (input, channel, ctx) -> {
                // Simulates microphone capture: emits 8 audio chunks at 16 kHz (20 ms each).
                // Each chunk carries { sequenceId, sampleRate, bytes } matching the DSL input schema.
                System.out.println("    [AudioCapture] Starting audio capture...");
                for (int i = 0; i < 8; i++) {
                    Thread.sleep(15);
                    channel.send(Map.of("sequenceId", i, "sampleRate", 16000, "bytes", i * 1024));
                    System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
                }
                System.out.println("    [AudioCapture] Audio capture complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> SPEECH_TO_TEXT =
            (input, channel, ctx) -> {
                // input.get("audio") receives the materialized List<Map> from audioCapture.output
                // (DirectEdge) — the full list is available here because the DSL uses .output, not .stream.
                // Each emitted chunk: { sequenceId, text, confidence } — one word token per audio chunk.
                var audioChunks = (List<Map<String, Object>>) input.get("audio");
                System.out.println("    [SpeechToText] Processing " + audioChunks.size() + " audio chunks...");
                String[] words = {"Hello", "this", "is", "a", "voice", "pipeline", "demo"};
                int idx = 0;
                for (Map<String, Object> chunk : audioChunks) {
                    Thread.sleep(20);
                    int seqId = (Integer) chunk.get("sequenceId");
                    String word = words[idx % words.length];
                    channel.send(Map.of("sequenceId", seqId, "text", word, "confidence", 0.92 - idx * 0.01));
                    System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", seqId, word);
                    idx++;
                }
                System.out.println("    [SpeechToText] Transcription complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> TEXT_ANALYZER =
            (input, channel, ctx) -> {
                // For DSL materialized input: input contains transcript field
                // textAnalysis uses speechToText.output (materialized list), not stream
                // This is a streaming operator that emits a single analysis result
                Thread.sleep(30);
                var chunks = (List<Map<String, Object>>) input.get("filteredTokens");
                if (chunks == null) {
                    // Called via depends_on materialized: input.transcript is the list
                    chunks = (List<Map<String, Object>>) input.get("transcript");
                }
                String transcript = "";
                if (chunks != null) {
                    transcript = chunks.stream()
                            .map(c -> (String) c.get("text"))
                            .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
                }
                channel.send(Map.of(
                        "fullTranscript", transcript,
                        "wordCount", chunks != null ? chunks.size() : 0,
                        "language", "en"));
            };

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        // StreamingOperator instances MUST use registerRaw() — the standard register() method
        // only accepts Operator<?,?>. registerRaw() stores the object as-is; the engine checks
        // instanceof StreamingOperator at resolution time to pick the streaming execution path.
        registry.registerRaw("AudioCapture", AUDIO_CAPTURE);
        registry.registerRaw("SpeechToText", SPEECH_TO_TEXT);
        // TextAnalyzer is a regular Operator (not streaming) — register() is fine here.
        // It receives the full materialized List<Map> via the "transcript" input key.
        registry.registerRaw("TextAnalyzer", (com.leanowtech.bloge.core.operator.Operator<Map<String, Object>, Map<String, Object>>) (input, ctx) -> {
            Thread.sleep(30);
            var transcript = (List<Map<String, Object>>) input.get("transcript");
            if (transcript == null) transcript = List.of();
            String text = transcript.stream()
                    .map(c -> (String) c.get("text"))
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
            return Map.of("fullTranscript", text, "wordCount", transcript.size(), "language", "en",
                    "avgConfidence", 0.91);
        });

        var loader = new GraphLoader(registry);

        String dsl = """
                graph voicePipeline {

                  /// Captures a continuous audio stream from the microphone.
                  /// buffer=64 allows the engine to queue 64 chunks before back-pressuring
                  /// the producer — suitable for bursty audio delivery.
                  stream node audioCapture : AudioCapture {
                    buffer = 64
                  }

                  /// Converts audio chunks to text tokens in real-time.
                  /// audio = audioCapture.output uses a DirectEdge — speechToText waits for
                  /// all audio chunks to be emitted before it starts, then receives them as a
                  /// List<Map>. For live forwarding use audioCapture.stream (StreamEdge).
                  stream node speechToText : SpeechToText {
                    input {
                      audio = audioCapture.output
                    }
                    buffer = 32
                  }

                  /// Analyzes the complete transcribed text.
                  /// depends_on = [speechToText] declares an explicit ordering dependency;
                  /// transcript = speechToText.output provides the materialized token list.
                  /// Returns { fullTranscript, wordCount, language, avgConfidence }.
                  node textAnalysis : TextAnalyzer {
                    depends_on = [speechToText]
                    input {
                      transcript = speechToText.output
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
        var ctx = new GraphContext(Map.of("sessionId", "VOICE-DSL-001"));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Voice Pipeline Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("textAnalysis") == NodeStatus.COMPLETED) {
            System.out.println("Analysis: " + result.results().getRaw("textAnalysis"));
        }
        if (result.getStatus("speechToText") == NodeStatus.COMPLETED) {
            System.out.println("STT chunks: " + ((List<?>) result.results().getRaw("speechToText")).size());
        }
    }
}
