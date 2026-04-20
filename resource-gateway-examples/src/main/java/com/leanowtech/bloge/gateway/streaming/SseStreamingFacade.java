package com.leanowtech.bloge.gateway.streaming;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.StreamingOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring bean that bridges bloge {@link GraphEngine} streaming executions to
 * Server-Sent Events via {@link SseEmitter}.
 *
 * <p>For each streaming node provided by the caller, this facade wraps the operator
 * in an {@link SseBridgedStreamingOperator} so that every chunk emitted by the
 * operator is also pushed to the client as an SSE event.
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>Creates an {@link SseEmitter} with a generous timeout.</li>
 *   <li>Wraps each streaming operator in an {@link SseBridgedStreamingOperator}.</li>
 *   <li>Starts graph execution on a virtual thread so the calling HTTP thread is
 *       released immediately.</li>
 *   <li>On completion, emits a final {@code "complete"} event and completes the emitter.</li>
 *   <li>On failure, emits an {@code "error"} event and completes the emitter with an error.</li>
 * </ol>
 *
 * <p>This is an example-oriented facade. A production system would add cancellation
 * support, back-pressure signalling, and heartbeat keepalives.
 */
@Component
public class SseStreamingFacade {

    private static final Logger log = LoggerFactory.getLogger(SseStreamingFacade.class);

    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L; // 5 minutes

    private final GraphEngine graphEngine;

    /**
     * @param graphEngine the bloge graph execution engine
     */
    public SseStreamingFacade(GraphEngine graphEngine) {
        this.graphEngine = graphEngine;
    }

    /**
     * Starts a streaming graph execution and returns an {@link SseEmitter} that the
     * caller can return from a Spring MVC controller.
     *
     * <p>Each entry in {@code streamingOperators} is wrapped so that its chunks are
     * forwarded to the SSE emitter under the event name looked up from {@code eventNames}
     * (defaulting to the node ID if no mapping exists).
     *
     * @param graph              the graph to execute
     * @param ctx                the initial graph context
     * @param streamingOperators map of nodeId → streaming operator
     * @param eventNames         map of nodeId → SSE event name (optional overrides)
     * @return an SSE emitter that will push chunk events and a final completion event
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public SseEmitter stream(Graph graph,
                             GraphContext ctx,
                             Map<String, StreamingOperator<?, ?>> streamingOperators,
                             Map<String, String> eventNames) {

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        // Wrap each streaming operator with SSE bridging
        Map<String, Object> wrappedOperators = new HashMap<>();
        for (var entry : streamingOperators.entrySet()) {
            String nodeId = entry.getKey();
            StreamingOperator delegate = entry.getValue();
            String eventName = eventNames.getOrDefault(nodeId, nodeId);
            wrappedOperators.put(nodeId, new SseBridgedStreamingOperator<>(
                    delegate, emitter, eventName, nodeId));
        }

        // Execute on a virtual thread to free the HTTP thread
        Thread.startVirtualThread(() -> {
            try {
                GraphResult result = graphEngine.executeWithOperators(graph, ctx, wrappedOperators);

                if (result.isSuccess()) {
                    emitter.send(SseEmitter.event()
                            .name("complete")
                            .data(Map.of("status", "success", "graph", graph.name())));
                    emitter.complete();
                    log.info("Streaming execution of '{}' completed successfully", graph.name());
                } else {
                    String errorMsg = result.errors().isEmpty()
                            ? "Unknown execution error"
                            : result.errors().getFirst().exception().getMessage();
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("status", "failed", "message", errorMsg)));
                    Throwable cause = result.errors().isEmpty()
                            ? new RuntimeException(errorMsg)
                            : result.errors().getFirst().exception();
                    emitter.completeWithError(
                            cause != null ? cause : new RuntimeException(errorMsg));
                    log.warn("Streaming execution of '{}' failed: {}", graph.name(), errorMsg);
                }
            } catch (Exception ex) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(Map.of("status", "failed", "message", ex.getMessage())));
                } catch (IOException ignored) {
                    // Client may have disconnected
                }
                emitter.completeWithError(ex);
                log.error("Streaming execution of '{}' threw an exception", graph.name(), ex);
            }
        });

        return emitter;
    }
}
