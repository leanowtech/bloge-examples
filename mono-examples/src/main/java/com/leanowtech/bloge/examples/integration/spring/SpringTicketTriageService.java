package com.leanowtech.bloge.examples.integration.spring;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.model.Graph;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Small application service that hides BLOGE engine lookup behind a conventional Spring bean.
 */
@Service
public class SpringTicketTriageService {

    private final GraphEngine engine;
    private final Map<String, Graph> graphsByName;

    public SpringTicketTriageService(GraphEngine engine, List<Graph> graphs) {
        this.engine = engine;
        this.graphsByName = graphs.stream().collect(Collectors.toUnmodifiableMap(Graph::name, Function.identity()));
    }

    /**
     * Executes the starter-managed graph and returns the final reply draft.
     */
    public TicketTriageResponse triage(String ticketId, String message, String customerTier) {
        Graph graph = graphsByName.get("springTicketTriage");
        if (graph == null) {
            throw new IllegalStateException("Expected springTicketTriage graph to be loaded from classpath:bloge/integration/spring/");
        }
        var result = engine.execute(graph, new GraphContext(Map.of(
                "ticketId", ticketId,
                "message", message,
                "customerTier", customerTier
        )));
        SpringReplyDraftOperator.ReplyDraft draft = result.getOutput(
                "draftReply",
                SpringReplyDraftOperator.ReplyDraft.class
        );
        return new TicketTriageResponse(
                draft.ticketId(),
                draft.queue(),
                draft.owner(),
                draft.responseTemplate(),
                result.executionId()
        );
    }

    /** JSON payload returned by the demo controller. */
    public record TicketTriageResponse(
            String ticketId,
            String queue,
            String owner,
            String responseTemplate,
            String executionId
    ) {
    }
}
