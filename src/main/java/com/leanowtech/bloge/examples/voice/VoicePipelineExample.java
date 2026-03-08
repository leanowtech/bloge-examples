package com.leanowtech.bloge.examples.voice;

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
 * Demonstrates a real-time voice processing pipeline using streaming operators.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture → stream speechToText → textAnalysis
 * </pre>
 *
 * <p>Features: {@code stream node} (source → processing → materialization) basic pattern.
 *
 * <p>Run {@link #main(String[])} to execute the pipeline with simulated audio input.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoicePipelineExample {

    // --- Records ---

    public record AudioChunk(int sequenceId, byte[] samples, int sampleRate) {}
    public record TextChunk(int sequenceId, String text, double confidence) {}
    public record AnalysisInput(List<TextChunk> chunks) {}
    public record AnalysisResult(String fullTranscript, int wordCount, String language, double avgConfidence) {}

    // --- Streaming operators ---

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"voice", "audio"},
            description = "Captures a continuous audio stream from the microphone", owner = "media-team")
    static final StreamingOperator<Void, AudioChunk> AUDIO_CAPTURE = (input, channel, ctx) -> {
        System.out.println("    [AudioCapture] Starting audio capture...");
        for (int i = 0; i < 8; i++) {
            Thread.sleep(15);
            var chunk = new AudioChunk(i, new byte[1024], 16000);
            channel.send(chunk);
            System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
        }
        System.out.println("    [AudioCapture] Audio capture complete");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "stt"},
            description = "Converts audio chunks to text in real-time", owner = "ai-team")
    static final StreamingOperator<List<AudioChunk>, TextChunk> SPEECH_TO_TEXT = (input, channel, ctx) -> {
        System.out.println("    [SpeechToText] Processing " + input.size() + " audio chunks...");
        String[] words = {"Hello", "this", "is", "a", "voice", "pipeline", "demo"};
        int chunkIdx = 0;
        for (AudioChunk audioChunk : input) {
            Thread.sleep(20);
            String word = words[chunkIdx % words.length];
            var textChunk = new TextChunk(audioChunk.sequenceId(), word, 0.92 - chunkIdx * 0.01);
            channel.send(textChunk);
            System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", audioChunk.sequenceId(), word);
            chunkIdx++;
        }
        System.out.println("    [SpeechToText] Transcription complete");
    };

    // --- Normal operator ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"voice", "nlp"},
            description = "Analyzes the complete transcribed text (materialized from stream)", owner = "ai-team")
    static final Operator<List<TextChunk>, AnalysisResult> TEXT_ANALYZER = (input, ctx) -> {
        Thread.sleep(30);
        String transcript = input.stream().map(TextChunk::text).reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        double avgConf = input.stream().mapToDouble(TextChunk::confidence).average().orElse(0.0);
        return new AnalysisResult(transcript, input.size(), "en", Math.round(avgConf * 100.0) / 100.0);
    };

    // --- Graph construction ---

    public static Graph buildGraph() {
        return Graph.builder("voicePipeline")
                .node("audioCapture", (input, ctx) -> null)
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "64")
                .node("speechToText", (input, ctx) -> null)
                    .dependsOn("audioCapture")
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                    .input((results, ctx) -> (List<AudioChunk>) results.getRaw("audioCapture"))
                .node("textAnalysis", TEXT_ANALYZER)
                    .dependsOn("speechToText")
                    .input((results, ctx) -> (List<TextChunk>) results.getRaw("speechToText"))
                .build();
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        registry.registerRaw("audioCapture", AUDIO_CAPTURE);
        registry.registerRaw("speechToText", SPEECH_TO_TEXT);
        registry.registerRaw("textAnalysis", TEXT_ANALYZER);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of("sessionId", "VOICE-001"));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ Voice Pipeline Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("textAnalysis") == NodeStatus.COMPLETED) {
            AnalysisResult analysis = result.getOutput("textAnalysis", AnalysisResult.class);
            System.out.println("Transcript:   " + analysis.fullTranscript());
            System.out.println("Word count:   " + analysis.wordCount());
            System.out.println("Language:     " + analysis.language());
            System.out.println("Avg conf:     " + analysis.avgConfidence());
        }

        if (result.getStatus("audioCapture") == NodeStatus.COMPLETED) {
            System.out.println("\naudioCapture chunks: " + ((List<?>) result.results().getRaw("audioCapture")).size());
        }
        if (result.getStatus("speechToText") == NodeStatus.COMPLETED) {
            System.out.println("speechToText chunks: " + ((List<?>) result.results().getRaw("speechToText")).size());
        }
    }
}
