package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central service for executing gateway graphs by name.
 *
 * <p>On construction, builds a lookup map from graph name to {@link Graph} so that
 * controllers can locate and execute graphs without scanning the full list on every
 * request. Provides convenience methods for non-streaming graph execution as well as
 * a graph-lookup helper for the streaming controller.
 *
 * <p>Thread-safe: backed by a {@link ConcurrentHashMap} and a stateless
 * {@link GraphEngine}.
 */
@Service
public class GatewayGraphService {

    private static final Logger log = LoggerFactory.getLogger(GatewayGraphService.class);

    private final GraphEngine graphEngine;
    private final Map<String, Graph> graphsByName;

    /**
     * @param graphEngine the bloge graph execution engine
     * @param graphs      all graphs loaded from DSL sources by Spring auto-configuration
     */
    public GatewayGraphService(GraphEngine graphEngine, List<Graph> graphs) {
        this.graphEngine = graphEngine;
        this.graphsByName = new ConcurrentHashMap<>();
        for (Graph g : graphs) {
            graphsByName.put(g.name(), g);
            log.info("Registered gateway graph: {}", g.name());
        }
        log.info("GatewayGraphService initialised with {} graph(s): {}",
                graphsByName.size(), graphsByName.keySet());
    }

    /**
     * Executes a named graph with the given context and returns the full result.
     *
     * @param graphName the graph name as declared in the {@code .bloge} file
     * @param ctx       the graph context with request-scoped parameters
     * @return the graph execution result
     * @throws IllegalArgumentException if no graph with the given name is registered
     */
    public GraphResult execute(String graphName, GraphContext ctx) {
        Graph graph = requireGraph(graphName);
        log.debug("Executing graph '{}' with context keys: {}", graphName, ctx.asMap().keySet());
        GraphResult result = graphEngine.execute(graph, ctx);
        if (result.isSuccess()) {
            log.debug("Graph '{}' completed successfully in {}", graphName, result.elapsed());
        } else {
            log.warn("Graph '{}' completed with {} error(s) in {}",
                    graphName, result.errors().size(), result.elapsed());
        }
        return result;
    }

    /**
     * Looks up a graph by name. Throws {@link IllegalArgumentException} with a
     * descriptive message listing available graph names if the requested graph is
     * not found.
     *
     * @param graphName the graph name to look up
     * @return the graph model
     * @throws IllegalArgumentException if the graph is not registered
     */
    public Graph requireGraph(String graphName) {
        Graph graph = graphsByName.get(graphName);
        if (graph == null) {
            throw new IllegalArgumentException(
                    "Graph '%s' not found. Available graphs: %s"
                            .formatted(graphName, graphsByName.keySet()));
        }
        return graph;
    }

    /**
     * Returns the underlying {@link GraphEngine} for callers that need direct access
     * (e.g. the streaming controller).
     *
     * @return the graph engine instance
     */
    public GraphEngine engine() {
        return graphEngine;
    }
}
