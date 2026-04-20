package com.leanowtech.bloge.gateway.streaming;

import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.core.stream.NodeChannel;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;

/**
 * A {@link StreamingOperator} decorator that bridges chunk output to a Spring
 * {@link SseEmitter}, enabling Server-Sent Events for streaming graph executions.
 *
 * <p>On each chunk produced by the delegate operator:
 * <ol>
 *   <li>The chunk is forwarded to the real {@link NodeChannel} for downstream graph consumption.</li>
 *   <li>An SSE event is emitted with the configured {@code eventName} and the chunk serialized
 *       as the event {@code data} field. The {@code nodeId} is included as a comment for
 *       debugging.</li>
 * </ol>
 *
 * <p>Errors from the delegate are propagated normally and are <em>not</em> swallowed.
 *
 * @param <I> input type
 * @param <O> output chunk type
 */
public class SseBridgedStreamingOperator<I, O> implements StreamingOperator<I, O> {

    private final StreamingOperator<I, O> delegate;
    private final SseEmitter emitter;
    private final String eventName;
    private final String nodeId;

    /**
     * Creates a bridged streaming operator.
     *
     * @param delegate  the underlying streaming operator that produces chunks
     * @param emitter   the SSE emitter to push events to
     * @param eventName the SSE event name (e.g. {@code "chunk"}, {@code "progress"})
     * @param nodeId    the graph node ID, included in each event for debugging
     */
    public SseBridgedStreamingOperator(StreamingOperator<I, O> delegate,
                                       SseEmitter emitter,
                                       String eventName,
                                       String nodeId) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.emitter = Objects.requireNonNull(emitter, "emitter");
        this.eventName = Objects.requireNonNull(eventName, "eventName");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
    }

    /**
     * Executes the delegate operator with a tapped channel that emits SSE events.
     *
     * @param input  the operator input
     * @param output the node channel for downstream graph consumption
     * @param ctx    the operator context
     * @throws Exception if the delegate operator fails
     */
    @Override
    public void execute(I input, NodeChannel<O> output, OperatorContext ctx) throws Exception {
        var tapped = new TapNodeChannel<>(output, chunk -> {
            try {
                emitter.send(
                        SseEmitter.event()
                                .name(eventName)
                                .data(new ChunkPayload(nodeId, chunk))
                                .comment("node=" + nodeId)
                );
            } catch (IOException e) {
                throw new RuntimeException("SSE send failed for node " + nodeId, e);
            }
        });
        delegate.execute(input, tapped, ctx);
    }

    /**
     * SSE event payload wrapping the chunk data with the source node ID.
     *
     * @param nodeId the graph node that produced the chunk
     * @param data   the chunk data
     */
    public record ChunkPayload(String nodeId, Object data) {}
}
