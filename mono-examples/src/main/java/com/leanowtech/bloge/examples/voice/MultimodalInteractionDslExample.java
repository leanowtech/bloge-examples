package com.leanowtech.bloge.examples.voice;

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
 * DSL-based multimodal interaction pipeline compiled from inline DSL.
 *
 * <p>Compiles and executes the multimodal interaction graph with parallel streaming operators
 * for video and audio capture, merging into a single recording.
 *
 * <p>Graph layout:
 * <pre>
 * stream videoStream ─┐
 *                     ├→ stream mergeStreams → saveRecording
 * stream audioStream ─┘
 * </pre>
 *
 * <p>Run {@link #main(String[])} to compile and execute the DSL graph.
 */
@SuppressWarnings({"unchecked", "preview"})
public class MultimodalInteractionDslExample {

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> VIDEO_CAPTURE =
            (input, channel, ctx) -> {
                // Simulates 30-fps camera: emits 6 frames at 1080p H.264.
                // input is null (no upstream — this is a source node with no input { } block).
                System.out.println("    [VideoCapture] Starting video capture...");
                for (int i = 0; i < 6; i++) {
                    Thread.sleep(33);
                    channel.send(Map.of("frameId", i, "width", 1920, "height", 1080, "codec", "H.264"));
                    System.out.printf("    [VideoCapture] Frame #%d captured%n", i);
                }
                System.out.println("    [VideoCapture] Video capture complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> AUDIO_CAPTURE =
            (input, channel, ctx) -> {
                // Simulates microphone at 48 kHz — runs concurrently with VIDEO_CAPTURE.
                // No input block in the DSL, so input here is null.
                System.out.println("    [AudioCapture] Starting audio capture...");
                for (int i = 0; i < 6; i++) {
                    Thread.sleep(20);
                    channel.send(Map.of("sequenceId", i, "sampleRate", 48000, "bytes", 1024));
                    System.out.printf("    [AudioCapture] Audio chunk #%d captured%n", i);
                }
                System.out.println("    [AudioCapture] Audio capture complete");
            };

    static final StreamingOperator<Map<String, Object>, Map<String, Object>> MEDIA_MERGER =
            (input, channel, ctx) -> {
                // input.get("video") and input.get("audio") are materialized Lists from
                // videoStream.output and audioStream.output (both DirectEdge).
                // The merger zips frames and audio chunks pairwise and emits merged frames.
                var videoFrames = (List<Map<String, Object>>) input.get("video");
                var audioChunks = (List<Map<String, Object>>) input.get("audio");
                System.out.printf("    [MediaMerger] Merging %d video + %d audio...%n",
                        videoFrames.size(), audioChunks.size());
                int count = Math.min(videoFrames.size(), audioChunks.size());
                for (int i = 0; i < count; i++) {
                    Thread.sleep(10);
                    channel.send(Map.of("sequenceId", i, "video", videoFrames.get(i), "audio", audioChunks.get(i)));
                    System.out.printf("    [MediaMerger] Merged frame #%d%n", i);
                }
                System.out.println("    [MediaMerger] Merge complete");
            };

    static final Operator<Map<String, Object>, Map<String, Object>> RECORDING_SAVER = (input, ctx) -> {
        // Receives the materialized List<Map> from mergeStreams.output (DirectEdge).
        // input.get("recording") is the complete list of merged A/V frames.
        Thread.sleep(50);
        var frames = (List<Map<String, Object>>) input.get("recording");
        int total = frames != null ? frames.size() : 0;
        return Map.of(
                "recordingId", "REC-" + System.currentTimeMillis(),
                "totalFrames", total,
                "durationMs", (long) total * 33,
                "format", "MP4");
    };

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        // All three streaming operators need registerRaw() so the engine can detect
        // StreamingOperator at runtime and use the streaming execution path.
        registry.registerRaw("VideoCapture", VIDEO_CAPTURE);
        registry.registerRaw("AudioCapture", AUDIO_CAPTURE);
        registry.registerRaw("MediaMerger", MEDIA_MERGER);
        // RECORDING_SAVER is a plain Operator — register() works fine here.
        registry.register("RecordingSaver", RECORDING_SAVER);

        var loader = new GraphLoader(registry);

        String dsl = """
                graph multimodalInteraction {

                  /// Captures video frames from the camera (1920×1080, H.264).
                  /// No input block — source node, starts immediately when the graph executes.
                  stream node videoStream : VideoCapture {
                    buffer = 32
                  }

                  /// Captures audio chunks from the microphone (48 kHz).
                  /// Runs concurrently with videoStream — both are source nodes with no dependencies.
                  stream node audioStream : AudioCapture {
                    buffer = 32
                  }

                  /// Merges the two streams into a single interleaved A/V stream.
                  /// Uses .output (DirectEdge) on both inputs: mergeStreams waits for videoStream
                  /// and audioStream to complete before it starts, then zips the materialized lists.
                  /// To merge frames in real-time as they arrive, use .stream (StreamEdge) instead.
                  stream node mergeStreams : MediaMerger {
                    input {
                      video = videoStream.output
                      audio = audioStream.output
                    }
                    buffer = 64
                  }

                  /// Saves the complete recording as a single artifact.
                  /// recording = mergeStreams.output provides the full List<Map> after merge finishes.
                  node saveRecording : RecordingSaver {
                    depends_on = [mergeStreams]
                    input {
                      recording = mergeStreams.output
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
        var ctx = new GraphContext(Map.of("sessionId", "MM-DSL-001", "userId", "U-42"));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ DSL Multimodal Interaction Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("saveRecording") == NodeStatus.COMPLETED) {
            System.out.println("Recording: " + result.results().getRaw("saveRecording"));
        }
        if (result.getStatus("mergeStreams") == NodeStatus.COMPLETED) {
            System.out.println("Merged frames: " + ((List<?>) result.results().getRaw("mergeStreams")).size());
        }
    }
}
