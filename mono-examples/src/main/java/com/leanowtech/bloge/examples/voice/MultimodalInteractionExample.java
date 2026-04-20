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
 * Demonstrates a multimodal interaction pipeline combining video and audio streams.
 *
 * <p>Graph layout:
 * <pre>
 * stream videoStream ─┐
 *                     ├→ stream mergeStreams → saveRecording
 * stream audioStream ─┘
 * </pre>
 *
 * <p>Features: multiple parallel {@code stream node}s, stream merging, materialization.
 *
 * <p>Run {@link #main(String[])} to execute the pipeline with simulated media input.
 */
@SuppressWarnings({"unchecked", "preview"})
public class MultimodalInteractionExample {

    // --- Records ---

    public record VideoFrame(int frameId, int width, int height, String codec) {}
    public record AudioChunk(int sequenceId, byte[] samples, int sampleRate) {}
    public record MediaFrame(int sequenceId, VideoFrame video, AudioChunk audio) {}
    public record RecordingInput(List<MediaFrame> frames) {}
    public record RecordingResult(String recordingId, int totalFrames, long durationMs, String format) {}

    // --- Streaming operators ---

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"voice", "video"},
            description = "Captures video chunks from the camera", owner = "media-team")
    static final StreamingOperator<Void, VideoFrame> VIDEO_CAPTURE = (input, channel, ctx) -> {
        System.out.println("    [VideoCapture] Starting video capture at 30fps...");
        for (int i = 0; i < 6; i++) {
            Thread.sleep(33); // ~30fps
            channel.send(new VideoFrame(i, 1920, 1080, "H.264"));
            System.out.printf("    [VideoCapture] Frame #%d captured%n", i);
        }
        System.out.println("    [VideoCapture] Video capture complete");
    };

    @OperatorMeta(layer = OperatorLayer.INFRASTRUCTURE, tags = {"voice", "audio"},
            description = "Captures audio chunks from the microphone", owner = "media-team")
    static final StreamingOperator<Void, AudioChunk> AUDIO_CAPTURE = (input, channel, ctx) -> {
        System.out.println("    [AudioCapture] Starting audio capture...");
        for (int i = 0; i < 6; i++) {
            Thread.sleep(20);
            channel.send(new AudioChunk(i, new byte[1024], 48000));
            System.out.printf("    [AudioCapture] Audio chunk #%d captured%n", i);
        }
        System.out.println("    [AudioCapture] Audio capture complete");
    };

    @OperatorMeta(layer = OperatorLayer.CAPABILITY, tags = {"voice", "media"},
            description = "Merges video and audio streams into a single media stream", owner = "media-team")
    static final StreamingOperator<Map<String, List<?>>, MediaFrame> MEDIA_MERGER = (input, channel, ctx) -> {
        var videoFrames = (List<VideoFrame>) input.get("video");
        var audioChunks = (List<AudioChunk>) input.get("audio");
        System.out.printf("    [MediaMerger] Merging %d video frames + %d audio chunks...%n",
                videoFrames.size(), audioChunks.size());
        int count = Math.min(videoFrames.size(), audioChunks.size());
        for (int i = 0; i < count; i++) {
            Thread.sleep(10);
            channel.send(new MediaFrame(i, videoFrames.get(i), audioChunks.get(i)));
            System.out.printf("    [MediaMerger] Merged frame #%d%n", i);
        }
        System.out.println("    [MediaMerger] Merge complete");
    };

    // --- Normal operator ---

    @OperatorMeta(layer = OperatorLayer.DOMAIN, tags = {"voice", "recording"},
            description = "Saves the final merged recording (materialized from stream)", owner = "storage-team")
    static final Operator<RecordingInput, RecordingResult> RECORDING_SAVER = (input, ctx) -> {
        Thread.sleep(50);
        int totalFrames = input.frames().size();
        long durationMs = (long) totalFrames * 33;
        return new RecordingResult("REC-" + System.currentTimeMillis(), totalFrames, durationMs, "MP4");
    };

    // --- Graph construction ---

    public static Graph buildGraph() {
        return Graph.builder("multimodalInteraction")
                .node("videoStream", (input, ctx) -> null)
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                .node("audioStream", (input, ctx) -> null)
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "32")
                .node("mergeStreams", (input, ctx) -> null)
                    .dependsOn("videoStream", "audioStream")
                    .meta("__streaming__", "true")
                    .meta("__bufferSize__", "64")
                    .input((results, ctx) -> Map.<String, List<?>>of(
                            "video", (List<VideoFrame>) results.getRaw("videoStream"),
                            "audio", (List<AudioChunk>) results.getRaw("audioStream")))
                .node("saveRecording", RECORDING_SAVER)
                    .dependsOn("mergeStreams")
                    .input((results, ctx) -> new RecordingInput((List<MediaFrame>) results.getRaw("mergeStreams")))
                .build();
    }

    public static void main(String[] args) {
        var registry = new DefaultOperatorRegistry();
        registry.registerRaw("videoStream", VIDEO_CAPTURE);
        registry.registerRaw("audioStream", AUDIO_CAPTURE);
        registry.registerRaw("mergeStreams", MEDIA_MERGER);
        registry.registerRaw("saveRecording", RECORDING_SAVER);

        var engine = GraphEngine.builder()
                .registry(registry)
                .interceptors(List.of())
                .listeners(List.of(new LoggingListener()))
                .build();
        Graph graph = buildGraph();

        var ctx = new GraphContext(Map.of("sessionId", "MM-001", "userId", "U-42"));

        GraphResult result = engine.execute(graph, ctx);

        System.out.println("\n═══ Multimodal Interaction Result ═══");
        System.out.println("Success: " + result.isSuccess());
        System.out.println("Elapsed: " + result.elapsed().toMillis() + "ms");
        System.out.println();

        for (var entry : result.statusMap().entrySet()) {
            System.out.printf("  %-20s → %s%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        if (result.getStatus("saveRecording") == NodeStatus.COMPLETED) {
            RecordingResult rec = result.getOutput("saveRecording", RecordingResult.class);
            System.out.println("Recording ID:    " + rec.recordingId());
            System.out.println("Total frames:    " + rec.totalFrames());
            System.out.println("Duration:        " + rec.durationMs() + "ms");
            System.out.println("Format:          " + rec.format());
        }

        if (result.getStatus("videoStream") == NodeStatus.COMPLETED) {
            System.out.println("\nVideo frames:    " + ((List<?>) result.results().getRaw("videoStream")).size());
        }
        if (result.getStatus("audioStream") == NodeStatus.COMPLETED) {
            System.out.println("Audio chunks:    " + ((List<?>) result.results().getRaw("audioStream")).size());
        }
        if (result.getStatus("mergeStreams") == NodeStatus.COMPLETED) {
            System.out.println("Merged frames:   " + ((List<?>) result.results().getRaw("mergeStreams")).size());
        }
    }
}
