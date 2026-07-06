package com.leanowtech.bloge.gateway.gateway;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaValidator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
    private final GatewayGraphContractCatalog graphContracts;

    /**
     * @param graphEngine the bloge graph execution engine
     * @param graphs      all graphs loaded from DSL sources by Spring auto-configuration
     */
    public GatewayGraphService(GraphEngine graphEngine, List<Graph> graphs) {
        this(graphEngine, graphs, GatewayGraphContractCatalog.builtIn());
    }

    /**
     * @param graphEngine    the bloge graph execution engine
     * @param graphs         all graphs loaded from DSL sources by Spring auto-configuration
     * @param graphContracts graph-level input/output schema contracts
     */
    @Autowired
    public GatewayGraphService(GraphEngine graphEngine,
                               List<Graph> graphs,
                               GatewayGraphContractCatalog graphContracts) {
        this.graphEngine = graphEngine;
        this.graphContracts = graphContracts == null ? GatewayGraphContractCatalog.builtIn() : graphContracts;
        this.graphsByName = new ConcurrentHashMap<>();
        for (Graph g : graphs) {
            graphsByName.put(g.name(), g);
            log.info("Registered gateway graph: {}", g.name());
        }
        assertEveryGraphHasContract();
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
        validateInputContext(graphName, ctx);
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
     * @return formal contracts for registered graphs
     */
    public Collection<GatewayGraphContract> graphContracts() {
        return graphContracts.all().stream()
                .filter(contract -> graphsByName.containsKey(contract.graphName()))
                .toList();
    }

    /**
     * @param graphName graph name
     * @return formal graph contract
     */
    public GatewayGraphContract requireContract(String graphName) {
        requireGraph(graphName);
        return graphContracts.require(graphName);
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

    private void assertEveryGraphHasContract() {
        List<String> missing = graphsByName.keySet().stream()
                .filter(graphName -> !graphContracts.contains(graphName))
                .sorted()
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Gateway graph contract(s) missing for: " + missing);
        }
    }

    private void validateInputContext(String graphName, GraphContext ctx) {
        GatewayGraphContract contract = graphContracts.require(graphName);
        List<VisualDiagnostic> diagnostics = VisualSchemaValidator.validateValue(
                contract.inputSchema(),
                ctx == null ? Map.of() : ctx.asMap(),
                "/context");
        if (!diagnostics.isEmpty()) {
            String details = diagnostics.stream()
                    .map(diagnostic -> "%s at %s".formatted(diagnostic.message(), diagnostic.target()))
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(
                    "Graph '%s' input context does not satisfy inputSchema: %s".formatted(graphName, details));
        }
    }
}
