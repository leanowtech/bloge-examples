package com.leanowtech.bloge.examples.education;

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
 * DSL-based voice pronunciation tutoring pipeline loaded from an inline {@code .bloge} DSL string.
 *
 * <p>Graph layout:
 * <pre>
 * stream audioCapture
 *   → stream speechToText
 *   → pronunciationScoring
 *   → loop feedbackLoop (max=3, delay=2s, until score >= 0.8)
 *       └─ generateFeedback → textToSpeech
 *         carry { attempts = loopIteration, lastScore = pronunciationScoring.output.score }
 *   → generateReport
 * </pre>
 *
 * <p>The loop body is defined entirely in DSL; inner operators ({@code FeedbackGenerator} and
 * {@code TtsOperator}) are registered in the {@link DefaultOperatorRegistry} and the DSL compiler
 * builds the {@link com.leanowtech.bloge.core.engine.operators.LoopOperator} automatically.
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings({"unchecked", "preview"})
public class VoiceTutoringDslExample {

    // --- DSL operators (Map-based) ---

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> AUDIO_CAPTURE =
            (input, channel, ctx) -> {
                System.out.println("    [AudioCapture] Starting audio capture...");
                for (int i = 0; i < 5; i++) {
                    Thread.sleep(15);
                    channel.send(Map.of("sequenceId", i, "sampleRate", 16000, "bytes", i * 1024));
                    System.out.printf("    [AudioCapture] Emitted chunk #%d%n", i);
                }
                System.out.println("    [AudioCapture] Audio capture complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> SPEECH_TO_TEXT =
            (input, channel, ctx) -> {
                var audioChunks = (List<Map<String, Object>>) input.get("audio");
                System.out.println("    [SpeechToText] Processing " + audioChunks.size() + " audio chunks...");
                String[] words = {"The", "quick", "brown", "fox", "jumps"};
                int idx = 0;
                for (Map<String, Object> chunk : audioChunks) {
                    Thread.sleep(20);
                    int seqId = ((Number) chunk.get("sequenceId")).intValue();
                    String word = words[idx % words.length];
                    channel.send(Map.of("sequenceId", seqId, "text", word, "confidence", 0.88 - idx * 0.02));
                    System.out.printf("    [SpeechToText] Transcribed chunk #%d → \"%s\"%n", seqId, word);
                    idx++;
                }
                System.out.println("    [SpeechToText] Transcription complete");
            };

    static final Operator<Map<String, Object>, Map<String, Object>> PRONUNCIATION_SCORER =
            (input, ctx) -> {
                Thread.sleep(40);
                var chunks = (List<Map<String, Object>>) input.get("transcript");
                String transcript = chunks == null ? "" : chunks.stream()
                        .map(c -> (String) c.get("text"))
                        .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
                System.out.printf("    [PronunciationScorer] Scored: \"%s\" → 0.65%n", transcript);
                return Map.of(
                        "transcript", transcript,
                        "score", 0.65,
                        "errors", List.of("rhythm", "stress"));
            };

    static final Operator<Map<String, Object>, Map<String, Object>> GENERATE_FEEDBACK_DSL =
            (input, ctx) -> {
                Thread.sleep(30);
                int iteration = input.get("iteration") != null
                        ? ((Number) input.get("iteration")).intValue() : 0;
                double lastScore = input.get("score") != null
                        ? ((Number) input.get("score")).doubleValue() : 0.65;
                double suggestedScore = lastScore + 0.10 * (iteration + 1);
                String targetPhrase = input.get("targetPhrase") != null
                        ? (String) input.get("targetPhrase") : "";
                String feedbackText = switch (iteration) {
                    case 0 -> "Focus on the rhythm of '" + targetPhrase + "'. Try stressing the first syllable.";
                    case 1 -> "Good progress! Now work on the stress pattern.";
                    default -> "Excellent! Your pronunciation is nearly perfect.";
                };
                System.out.printf("    [GenerateFeedback] iter=%d lastScore=%.2f → suggested=%.2f%n",
                        iteration, lastScore, suggestedScore);
                return Map.of(
                        "feedbackText",   feedbackText,
                        "suggestedScore", suggestedScore,
                        "iteration",      iteration);
            };

    static final Operator<Map<String, Object>, Map<String, Object>> TEXT_TO_SPEECH_DSL =
            (input, ctx) -> {
                Thread.sleep(25);
                String text = (String) input.get("text");
                String audioId = "AUDIO-" + Integer.toHexString(text != null ? text.hashCode() : 0)
                        .toUpperCase();
                System.out.printf("    [TextToSpeech] Synthesised %s%n", audioId);
                return Map.of("audioId", audioId, "durationMs", 3000);
            };

    static final Operator<Map<String, Object>, Map<String, Object>> GENERATE_REPORT_DSL =
            (input, ctx) -> {
                Thread.sleep(20);
                String studentId = (String) input.get("studentId");
                String targetPhrase = (String) input.get("targetPhrase");
                // finalScore and iterations come from the loop output via ctx / previous node
                double finalScore = input.get("finalScore") != null
                        ? ((Number) input.get("finalScore")).doubleValue() : 0.0;
                int iterations = input.get("iterations") != null
                        ? ((Number) input.get("iterations")).intValue() : 0;
                String recommendation = finalScore >= 0.8 ? "Advance to next lesson" : "Repeat current exercise";
                String reportId = "RPT-" + studentId + "-" + System.currentTimeMillis() % 10000;
                System.out.printf("    [GenerateReport] student=%s score=%.2f iterations=%d%n",
                        studentId, finalScore, iterations);
                return Map.of(
                        "reportId",        reportId,
                        "studentId",       studentId,
                        "targetPhrase",    targetPhrase != null ? targetPhrase : "",
                        "finalScore",      finalScore,
                        "recommendation",  recommendation,
                        "totalIterations", iterations);
            };

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();

        // ── Operator Registrations ─────────────────────────────────────────────
        // NOTE: registerRaw is required for StreamingOperator implementations; register is for standard Operator<?,?> only

        // Streaming operators registered as raw (engine resolves via __streaming__ meta)
        // AUDIO_CAPTURE: no input → emits {sequenceId, sampleRate, bytes} chunks via NodeChannel
        registry.registerRaw("AudioCapture",    AUDIO_CAPTURE);
        // SPEECH_TO_TEXT: reads input.audio (List<chunk>) → emits {sequenceId, text, confidence} tokens via NodeChannel
        registry.registerRaw("SpeechToText",    SPEECH_TO_TEXT);

        // Normal operators
        // PRONUNCIATION_SCORER: reads transcript (List<token>), referenceId, targetPhrase → returns {transcript, score, errors}
        registry.register("PronunciationScorer",  PRONUNCIATION_SCORER);
        // FEEDBACK_GENERATOR: reads score, transcript, targetPhrase, iteration → returns {feedbackText, suggestedScore, iteration}
        registry.register("FeedbackGenerator",    GENERATE_FEEDBACK_DSL);
        // TTS_OPERATOR: reads text, language → returns {audioId, durationMs}
        registry.register("TtsOperator",          TEXT_TO_SPEECH_DSL);
        // SESSION_REPORT_GENERATOR: reads studentId, targetPhrase, finalScore, iterations → returns {reportId, recommendation, totalIterations}
        registry.register("SessionReportGenerator", GENERATE_REPORT_DSL);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph voiceTutoring {

                  /// Captures a continuous audio stream from the microphone
                  /// STREAM NODE: emits audio chunks via NodeChannel; downstream consumes without blocking this producer
                  stream node audioCapture : AudioCapture {
                    buffer = 64
                  }

                  /// Converts audio chunks to text in real-time
                  /// STREAM NODE: emits transcription tokens via NodeChannel as audio chunks arrive
                  stream node speechToText : SpeechToText {
                    input {
                      /// .output on a stream node materialises the full List<chunk> before this operator starts
                      audio = audioCapture.output
                    }
                    buffer = 32
                  }

                  /// Scores pronunciation accuracy against the reference phrase
                  node pronunciationScoring : PronunciationScorer {
                    depends_on = [speechToText]
                    input {
                      /// .output materialises the full List<token> into a single collection before PronunciationScorer runs
                      transcript  = speechToText.output
                      referenceId = ctx.referenceId
                      targetPhrase = ctx.targetPhrase
                    }
                    timeout = 5s
                  }

                  /// Feedback loop: up to 3 rounds of coaching until score >= 0.8
                  loop feedbackLoop {
                    max_iterations = 3
                    delay = 2s
                    depends_on = [pronunciationScoring]
                    node generateFeedback : FeedbackGenerator {
                      input {
                        score        = pronunciationScoring.output.score
                        transcript   = pronunciationScoring.output.transcript
                        targetPhrase = ctx.targetPhrase
                        iteration    = loopIteration
                      }
                    }
                    node textToSpeech : TtsOperator {
                      depends_on = [generateFeedback]
                      input {
                        text     = generateFeedback.output.feedbackText
                        language = ctx.language
                      }
                    }
                    /// carry: values forwarded into the next iteration as loop-scoped variables
                    carry {
                      attempts:  loopIteration
                      lastScore: pronunciationScoring.output.score
                    }
                    /// until: loop exits when this condition evaluates to true; otherwise repeats up to max_iterations
                    until generateFeedback.output.suggestedScore >= 0.8
                  }

                  /// Generates a final session report
                  node generateReport : SessionReportGenerator {
                    depends_on = [feedbackLoop]
                    input {
                      studentId    = ctx.studentId
                      targetPhrase = ctx.targetPhrase
                      finalScore   = feedbackLoop.output.generateFeedback.suggestedScore
                      iterations   = feedbackLoop.output.generateFeedback.iteration
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
                "studentId",    "STU-001",
                "referenceId",  "REF-001",
                "targetPhrase", "The quick brown fox",
                "language",     "en"
        ));

        // execute; streaming results accessible via result.results()
        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Voice Tutoring Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-25s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("pronunciationScoring") == NodeStatus.COMPLETED) {
            System.out.println("Pronunciation scoring: " + result.results().getRaw("pronunciationScoring"));
        }

        if (result.getStatus("feedbackLoop") == NodeStatus.COMPLETED) {
            System.out.println("Feedback loop output:  " + result.results().getRaw("feedbackLoop"));
        }

        if (result.getStatus("generateReport") == NodeStatus.COMPLETED) {
            System.out.println("Report:                " + result.results().getRaw("generateReport"));
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
