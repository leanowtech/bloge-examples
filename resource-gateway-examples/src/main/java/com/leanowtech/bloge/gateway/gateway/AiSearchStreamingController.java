package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.StreamingOperator;
import com.leanowtech.bloge.gateway.operator.streaming.MockCitationStreamingOperator;
import com.leanowtech.bloge.gateway.operator.streaming.MockLlmTokenStreamingOperator;
import com.leanowtech.bloge.gateway.operator.streaming.MockMetaStreamingOperator;
import com.leanowtech.bloge.gateway.streaming.SseStreamingFacade;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * SSE streaming controller for the AI-enriched search graph.
 *
 * <p>Loads the {@code aiEnrichedSearch} graph from {@link GatewayGraphService} and
 * delegates to {@link SseStreamingFacade} for streaming execution. Each streaming
 * node in the graph is wired to a mock operator and mapped to a distinct SSE event
 * name so that the frontend can route chunks to the appropriate UI region.
 *
 * <h3>SSE Event Mapping</h3>
 * <ul>
 *   <li>{@code metaStream} → event name {@code "meta"}</li>
 *   <li>{@code llmStream} → event name {@code "token"}</li>
 *   <li>{@code citationStream} → event name {@code "citation"}</li>
 * </ul>
 *
 * <h3>Endpoint</h3>
 * <p>{@code GET /api/gateway/ai/search/stream?q=...}
 */
@RestController
@RequestMapping("/api/gateway/ai")
public class AiSearchStreamingController {

    private static final Logger log = LoggerFactory.getLogger(AiSearchStreamingController.class);

    private final GatewayGraphService graphService;
    private final SseStreamingFacade sseStreamingFacade;
    private final MockMetaStreamingOperator metaOperator;
    private final MockLlmTokenStreamingOperator llmOperator;
    private final MockCitationStreamingOperator citationOperator;

    /**
     * @param graphService       gateway graph lookup and execution service
     * @param sseStreamingFacade SSE streaming bridge for graph execution
     * @param metaOperator       mock metadata streaming operator
     * @param llmOperator        mock LLM token streaming operator
     * @param citationOperator   mock citation streaming operator
     */
    public AiSearchStreamingController(GatewayGraphService graphService,
                                       SseStreamingFacade sseStreamingFacade,
                                       MockMetaStreamingOperator metaOperator,
                                       MockLlmTokenStreamingOperator llmOperator,
                                       MockCitationStreamingOperator citationOperator) {
        this.graphService = graphService;
        this.sseStreamingFacade = sseStreamingFacade;
        this.metaOperator = metaOperator;
        this.llmOperator = llmOperator;
        this.citationOperator = citationOperator;
    }

    /**
     * Executes the AI-enriched search graph in streaming mode, returning an
     * {@link SseEmitter} that pushes metadata, LLM tokens, and citations as
     * they are produced.
     *
     * @param query the user's search query
     * @return an SSE emitter streaming search results to the client
     */
    @GetMapping("/search/stream")
    public SseEmitter streamSearch(@RequestParam("q") String query) {
        log.info("AI search stream request, query='{}'", query);

        Graph graph = graphService.requireGraph("aiEnrichedSearch");
        GraphContext ctx = new GraphContext(Map.of("query", query));

        // Map each streaming node to its operator
        Map<String, StreamingOperator<?, ?>> streamingOperators = Map.of(
                "metaStream", metaOperator,
                "llmStream", llmOperator,
                "citationStream", citationOperator
        );

        // Map each streaming node to a human-friendly SSE event name
        Map<String, String> eventNames = Map.of(
                "metaStream", "meta",
                "llmStream", "token",
                "citationStream", "citation"
        );

        return sseStreamingFacade.stream(graph, ctx, streamingOperators, eventNames);
    }
}
