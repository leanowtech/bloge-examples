package com.leanowtech.bloge.examples.education;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.engine.operators.LoopOperator;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorLayer;
import com.leanowtech.bloge.core.operator.OperatorMeta;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.examples.common.LoggingListener;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates a voice-based pronunciation tutoring pipeline using streaming and loop operators.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture
 *   → stream speechToText
 *   → pronunciationScoring
 *   → loop feedbackLoop (max=3, until suggestedScore >= 0.8)
 *       └─ generateFeedback → textToSpeech
 *   → generateReport
 * </pre>
 *
 * <p>Features demonstrated:
 * <ul>
 *   <li>Streaming source ({@code audioCapture}) and streaming transform ({@code speechToText})</li>
 *   <li>Materialized streaming output consumed by a normal operator ({@code pronunciationScoring})</li>
 *   <li>{@link LoopOperator} with carry state tracking score improvement across iterations</li>
 *   <li>Loop termination when the feedback's {@code suggestedScore} reaches ≥ 0.8</li>
 * </ul>
 *
 * <p>Run {@link #main(String[])} to execute the tutoring session with simulated audio input.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceTutoringExample {

    // --- Records ---

    public record AudioChunk(int sequenceId, byte[] samples, int sampleRate) {}
    public record TextChunk(int sequenceId, String text, double confidence) {}
    public record ScoringInput(List<TextChunk> transcript, String targetPhrase, String referenceId) {}
    public record ScoringResult(String transcript, double score, List<String> errors) {}
    public record FeedbackInput(int iteration, double lastScore, String targetPhrase, String transcript) {}
    public record FeedbackResult(String feedbackText, double suggestedScore, int iteration) {}
    public record TtsInput(String text, String language) {}
    public record TtsResult(String audioId, int durationMs) {}
    public record ReportInput(String studentId, String targetPhrase, double finalScore, int iterations) {}
    public record ReportResult(String reportId, String studentId, double finalScore,
                               String recommendation, int totalIterations) {}

    // --- Streaming operators ---

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"voice", "audio"},
            description = "Captures a continuous audio stream from the microphone", owner = "media-team")
    static final StreamingOperator<Void, AudioChunk> AUDIO_CAPTURE = (input, channel, ctx) -> {
        System.out.println("    [AudioCapture] Starting audio capture...");
        for (int i = 0; i < 5; i++) {
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
        String[] words = {"The", "quick", "brown", "fox", "jumps"};
        int idx = 0;
        for (AudioChunk audioChunk : input) {
            Thread.sleep(20);
            String word = words[idx % words.length];
            var textChunk = new TextChunk(audioChunk.sequenceId(), word, 0.88 - idx * 0.02);
            channel.send(textChunk);
            System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n",
                    audioChunk.sequenceId(), word);
            idx++;
        }
        System.out.println("    [SpeechToText] Transcription complete");
    };

    // --- Normal operators ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"voice", "pronunciation"},
            description = "Scores pronunciation accuracy against a reference phrase", owner = "ai-team")
    static final Operator<ScoringInput, ScoringResult> PRONUNCIATION_SCORER = (input, ctx) -> {
        Thread.sleep(40);
        String transcript = input.transcript().stream()
                .map(TextChunk::text)
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        System.out.printf("    [PronunciationScorer] Scored transcript: \"%s\" → 0.65%n", transcript);
        return new ScoringResult(transcript, 0.65, List.of("rhythm", "stress"));
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"voice", "feedback"},
            description = "Generates targeted pronunciation feedback for the current iteration", owner = "ai-team")
    static final Operator<FeedbackInput, FeedbackResult> GENERATE_FEEDBACK = (input, ctx) -> {
        Thread.sleep(30);
        // Score improves by 0.1 each iteration: iter 0 → 0.75, iter 1 → 0.75, iter 2 → 0.85
        double suggestedScore = input.lastScore() + 0.10 * (input.iteration() + 1);
        String feedbackText = switch (input.iteration()) {
            case 0 -> "Focus on the rhythm of '" + input.targetPhrase() + "'. Try stressing the first syllable.";
            case 1 -> "Good progress! Now work on the stress pattern — emphasize 'quick' and 'fox'.";
            default -> "Excellent! Your pronunciation is nearly perfect. Maintain this cadence.";
        };
        System.out.printf("    [GenerateFeedback] iter=%d lastScore=%.2f → suggested=%.2f%n",
                input.iteration(), input.lastScore(), suggestedScore);
        return new FeedbackResult(feedbackText, suggestedScore, input.iteration());
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "tts"},
            description = "Converts feedback text to synthesised speech", owner = "media-team")
    static final Operator<TtsInput, TtsResult> TEXT_TO_SPEECH = (input, ctx) -> {
        Thread.sleep(25);
        String audioId = "AUDIO-" + Integer.toHexString(input.text().hashCode()).toUpperCase();
        System.out.printf("    [TextToSpeech] Synthesised audio %s (lang=%s)%n", audioId, input.language());
        return new TtsResult(audioId, 3000);
    };

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"voice", "reporting"},
            description = "Generates a session report summarising pronunciation progress", owner = "edu-team")
    static final Operator<Map<String, Object>, ReportResult> GENERATE_REPORT = (input, ctx) -> {
        Thread.sleep(20);
        String studentId = (String) input.get("studentId");
        String targetPhrase = (String) input.get("targetPhrase");
        double finalScore = ((Number) input.get("finalScore")).doubleValue();
        int iterations = ((Number) input.get("iterations")).intValue();

        String recommendation = finalScore >= 0.8
                ? "Advance to next lesson"
                : "Repeat current exercise";
        String reportId = "RPT-" + studentId + "-" + System.currentTimeMillis() % 10000;
        System.out.printf("    [GenerateReport] student=%s score=%.2f iterations=%d%n",
                studentId, finalScore, iterations);
        return new ReportResult(reportId, studentId, finalScore, recommendation, iterations);
    };

    // --- Graph construction ---

    public static Graph buildLoopSubGraph(DefaultOperatorRegistry registry, LoggingListener listener) {
        return Graph.builder("feedbackLoop__subgraph__")
                .node("generateFeedback", GENERATE_FEEDBACK)
                    .input((results, ctx) -> {
                        int iteration = ctx.get("__loopIteration__", Integer.class);
                        var carry = (Map<String, Object>) ctx.get("__carry__", Map.class);
                        double lastScore = carry != null && carry.containsKey("lastScore")
                                ? ((Number) carry.get("lastScore")).doubleValue() : 0.65;
                        String targetPhrase = ctx.get("targetPhrase", String.class);
                        String transcript = ctx.get("scoringTranscript", String.class);
                        return new FeedbackInput(iteration, lastScore, targetPhrase, transcript);
                    })
                .node("textToSpeech", TEXT_TO_SPEECH)
                    .dependsOn("generateFeedback")
                    .input((results, ctx) -> {
                        var feedback = results.get("generateFeedback", FeedbackResult.class);
                        return new TtsInput(feedback.feedbackText(), ctx.get("language", String.class));
                    })
                .build();
    }

    public static Graph buildGraph(DefaultOperatorRegistry registry, LoggingListener listener) {
        Graph subGraph = buildLoopSubGraph(registry, listener);

        var loopOp = new LoopOperator(
                subGraph,
                registry,
                3,
                Duration.ofMillis(100),
                // untilCondition: stop when suggestedScore >= 0.8
                outputs -> {
                    var feedback = (FeedbackResult) outputs.get("generateFeedback");
                    return feedback != null && feedback.suggestedScore() >= 0.8;
                },
                // carryMapper: carry the suggested score forward
                outputs -> {
                    var feedback = (FeedbackResult) outputs.get("generateFeedback");
                    return Map.of("lastScore", feedback != null ? feedback.suggestedScore() : 0.65);
                },
                null,
                List.of(listener)
        );

        return buildGraphWithLoopOp(loopOp);
    }

    public static Graph buildGraphWithLoopOp(LoopOperator loopOp) {
        return Graph.builder("voiceTutoring")
                .node("audioCapture", (input, ctx) -> null)
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "64")
                .node("speechToText", (input, ctx) -> null)
                    .dependsOn("audioCapture")
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                    .input((results, ctx) -> (List<AudioChunk>) results.getRaw("audioCapture"))
                .node("pronunciationScoring", PRONUNCIATION_SCORER)
                    .dependsOn("speechToText")
                    .input((results, ctx) -> new ScoringInput(
                            (List<TextChunk>) results.getRaw("speechToText"),
                            ctx.get("targetPhrase", String.class),
                            ctx.get("referenceId", String.class)))
                .node("feedbackLoop", loopOp)
                    .dependsOn("pronunciationScoring")
                    .input((results, ctx) -> {
                        var scoring = results.get("pronunciationScoring", ScoringResult.class);
                        return Map.<String, Object>of("lastScore", scoring.score());
                    })
                .node("generateReport", GENERATE_REPORT)
                    .dependsOn("feedbackLoop")
                    .input((results, ctx) -> {
                        var scoring = results.get("pronunciationScoring", ScoringResult.class);
                        var loopOut = (Map<String, Object>) results.getRaw("feedbackLoop");
                        var lastFeedback = (FeedbackResult) loopOut.get("generateFeedback");
                        double finalScore = lastFeedback != null ? lastFeedback.suggestedScore() : scoring.score();
                        int iterations = lastFeedback != null ? lastFeedback.iteration() + 1 : 0;
                        return Map.<String, Object>of(
                                "studentId", ctx.get("studentId", String.class),
                                "targetPhrase", ctx.get("targetPhrase", String.class),
                                "finalScore", finalScore,
                                "iterations", iterations);
                    })
                .build();
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        var listener = new LoggingListener();

        registry.registerRaw("audioCapture", AUDIO_CAPTURE);
        registry.registerRaw("speechToText", SPEECH_TO_TEXT);
        registry.register("pronunciationScoring", PRONUNCIATION_SCORER);
        registry.register("generateFeedback", GENERATE_FEEDBACK);
        registry.register("textToSpeech", TEXT_TO_SPEECH);
        registry.register("generateReport", GENERATE_REPORT);

        Graph subGraph = buildLoopSubGraph(registry, listener);
        var loopOp = new LoopOperator(
                subGraph, registry, 3, Duration.ofMillis(100),
                outputs -> {
                    var feedback = (FeedbackResult) outputs.get("generateFeedback");
                    return feedback != null && feedback.suggestedScore() >= 0.8;
                },
                outputs -> {
                    var feedback = (FeedbackResult) outputs.get("generateFeedback");
                    return Map.of("lastScore", feedback != null ? feedback.suggestedScore() : 0.65);
                },
                null,
                List.of(listener)
        );

        Graph graph = buildGraphWithLoopOp(loopOp);
        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(listener))
                .build();

        var ctx = new GraphContext(Map.of(
                "studentId",    "STU-001",
                "referenceId",  "REF-001",
                "targetPhrase", "The quick brown fox",
                "language",     "en"
        ));

        GraphResult result = engine.executeWithOperators(graph, ctx, Map.of(
                "pronunciationScoring", PRONUNCIATION_SCORER,
                "generateReport",       GENERATE_REPORT,
                "feedbackLoop",         loopOp
        ));

        System.out.println("\n═══ Voice Tutoring Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("pronunciationScoring") == NodeStatus.COMPLETED) {
            ScoringResult scoring = result.getOutput("pronunciationScoring", ScoringResult.class);
            System.out.println("Pronunciation score:  " + scoring.score());
            System.out.println("Transcript:          " + scoring.transcript());
            System.out.println("Errors:              " + scoring.errors());
        }

        if (result.getStatus("feedbackLoop") == NodeStatus.COMPLETED) {
            var loopOut = (Map<String, Object>) result.results().getRaw("feedbackLoop");
            System.out.println("Feedback loop output: " + loopOut);
        }

        if (result.getStatus("generateReport") == NodeStatus.COMPLETED) {
            ReportResult report = result.getOutput("generateReport", ReportResult.class);
            System.out.println();
            System.out.println("Report ID:         " + report.reportId());
            System.out.println("Student:           " + report.studentId());
            System.out.println("Final score:       " + report.finalScore());
            System.out.println("Recommendation:    " + report.recommendation());
            System.out.println("Total iterations:  " + report.totalIterations());
        }

        if (result.getStatus("audioCapture") == NodeStatus.COMPLETED) {
            System.out.println("\naudioCapture chunks:  "
                    + ((List<?>) result.results().getRaw("audioCapture")).size());
        }
        if (result.getStatus("speechToText") == NodeStatus.COMPLETED) {
            System.out.println("speechToText chunks:  "
                    + ((List<?>) result.results().getRaw("speechToText")).size());
        }
    }
}
