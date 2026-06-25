package com.leanowtech.bloge.graphengine.service;

import com.leanowtech.bloge.core.model.ConditionalEdge;
import com.leanowtech.bloge.core.model.DirectEdge;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeMetadata;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.model.ResilienceConfig;
import com.leanowtech.bloge.core.model.StreamEdge;
import com.leanowtech.bloge.core.spi.JsonCodec;
import com.leanowtech.bloge.ext.model.PhaseDef;
import com.leanowtech.bloge.ext.model.PhaseTransition;
import com.leanowtech.bloge.graphengine.model.GraphDefinition;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphVersion;
import com.leanowtech.bloge.graphengine.model.VisualLayout;
import com.leanowtech.bloge.state.model.StateDef;
import com.leanowtech.bloge.state.model.StateTransition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Generates and validates the example UI visual-layout projection for graph
 * engine versions.
 *
 * <p>The generator does not infer or mutate execution semantics. It uses the
 * already compiled runtime artifact as source of truth and produces a stable
 * presentation payload only when a stored {@code visualLayout} is absent or no
 * longer covers the compiled node set.</p>
 */
final class VisualLayoutGenerator {

    private static final double NODE_WIDTH = 180;
    private static final double NODE_HEIGHT = 72;
    private static final double X_STEP = 240;
    private static final double Y_STEP = 120;
    private static final double X_START = 80;
    private static final double Y_START = 80;

    private final JsonCodec jsonCodec;

    /**
     * Creates one generator.
     *
     * @param jsonCodec JSON codec used for layout serialization and compatibility checks
     */
    VisualLayoutGenerator(JsonCodec jsonCodec) {
        this.jsonCodec = jsonCodec == null ? JsonCodec.DEFAULT : jsonCodec;
    }

    /**
     * Returns the stored layout when it still covers the compiled artifact;
     * otherwise returns a generated {@link VisualLayout#SCHEMA_VERSION} layout.
     *
     * @param definition owning graph definition
     * @param version stored immutable version
     * @param compilation compile result for the version
     * @return stored or generated serialized layout JSON
     */
    String resolveLayout(GraphDefinition definition, GraphVersion version, VersionCompileResult compilation) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(compilation, "compilation");
        Set<String> expectedNodeIds = expectedNodeIds(compilation);
        if (storedLayoutCovers(version.visualLayout(), expectedNodeIds)) {
            return version.visualLayout();
        }
        return jsonCodec.serialize(generate(definition, version, compilation));
    }

    private VisualLayout generate(GraphDefinition definition, GraphVersion version, VersionCompileResult compilation) {
        String rootId = rootId(definition, version, compilation);
        return switch (compilation.executionMode()) {
            case GRAPH -> graphLayout(rootId, compilation.graph());
            case SESSION -> sessionLayout(rootId, compilation.sessionGraph());
            case STATE_MACHINE -> stateMachineLayout(rootId, compilation.stateMachine());
        };
    }

    private VisualLayout graphLayout(String rootId, Graph graph) {
        if (graph == null) {
            return emptyLayout(rootId, GraphExecutionMode.GRAPH);
        }
        List<String> order = graph.topologicalOrder();
        Map<String, Integer> depths = graphDepths(graph, order);
        Map<Integer, Integer> rowByDepth = new LinkedHashMap<>();
        List<VisualLayout.Node> nodes = new ArrayList<>();
        for (String nodeId : order) {
            NodeSpec spec = graph.nodes().get(nodeId);
            int depth = depths.getOrDefault(nodeId, 0);
            int row = rowByDepth.merge(depth, 1, Integer::sum) - 1;
            nodes.add(new VisualLayout.Node(
                    nodeId,
                    graphNodeKind(graph, spec),
                    spec == null ? null : spec.operatorRef(),
                    label(nodeId),
                    new VisualLayout.Position(X_START + depth * X_STEP, Y_START + row * Y_STEP),
                    new VisualLayout.Size(NODE_WIDTH, NODE_HEIGHT),
                    null,
                    graphNodeAnnotations(spec)
            ));
        }
        return new VisualLayout(
                VisualLayout.SCHEMA_VERSION,
                rootId,
                GraphExecutionMode.GRAPH,
                nodes,
                graphEdges(graph),
                List.of(),
                new VisualLayout.Viewport(0, 0, 1)
        );
    }

    private VisualLayout sessionLayout(String rootId, com.leanowtech.bloge.ext.model.SessionGraph sessionGraph) {
        if (sessionGraph == null) {
            return emptyLayout(rootId, GraphExecutionMode.SESSION);
        }
        List<VisualLayout.Node> nodes = new ArrayList<>();
        List<PhaseDef> phases = sessionGraph.phases();
        for (int index = 0; index < phases.size(); index++) {
            PhaseDef phase = phases.get(index);
            nodes.add(new VisualLayout.Node(
                    phase.id(),
                    "phase",
                    null,
                    label(phase.id()),
                    new VisualLayout.Position(X_START + index * X_STEP, Y_START),
                    new VisualLayout.Size(NODE_WIDTH, NODE_HEIGHT),
                    null,
                    Map.of(
                            "phaseType", phase.type().name(),
                            "maxRounds", phase.maxRounds(),
                            "maxVisits", phase.maxVisits()
                    )
            ));
        }
        List<VisualLayout.Edge> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (PhaseDef phase : phases) {
            for (PhaseTransition transition : phase.transitions()) {
                addEdge(edges, seen, phase.id(), transition.targetPhaseId(), transition.expression());
            }
        }
        return new VisualLayout(
                VisualLayout.SCHEMA_VERSION,
                rootId,
                GraphExecutionMode.SESSION,
                nodes,
                edges,
                List.of(),
                new VisualLayout.Viewport(0, 0, 1)
        );
    }

    private VisualLayout stateMachineLayout(String rootId, com.leanowtech.bloge.state.model.StateMachineDef stateMachine) {
        if (stateMachine == null) {
            return emptyLayout(rootId, GraphExecutionMode.STATE_MACHINE);
        }
        List<VisualLayout.Node> nodes = new ArrayList<>();
        List<StateDef> states = new ArrayList<>(stateMachine.states().values());
        for (int index = 0; index < states.size(); index++) {
            StateDef state = states.get(index);
            nodes.add(new VisualLayout.Node(
                    state.id(),
                    "state",
                    null,
                    label(state.id()),
                    new VisualLayout.Position(X_START + index * X_STEP, Y_START),
                    new VisualLayout.Size(NODE_WIDTH, NODE_HEIGHT),
                    null,
                    Map.of(
                            "stateType", state.type().name(),
                            "initial", state.id().equals(stateMachine.initialStateId()),
                            "terminal", stateMachine.terminalStateIds().contains(state.id())
                    )
            ));
        }
        List<VisualLayout.Edge> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (StateDef state : states) {
            for (StateTransition transition : state.transitions()) {
                addEdge(edges, seen, state.id(), transition.targetStateId(), stateTransitionLabel(transition));
            }
        }
        for (StateTransition transition : stateMachine.globalTransitions()) {
            for (StateDef state : states) {
                addEdge(edges, seen, state.id(), transition.targetStateId(), stateTransitionLabel(transition));
            }
        }
        return new VisualLayout(
                VisualLayout.SCHEMA_VERSION,
                rootId,
                GraphExecutionMode.STATE_MACHINE,
                nodes,
                edges,
                List.of(),
                new VisualLayout.Viewport(0, 0, 1)
        );
    }

    private VisualLayout emptyLayout(String rootId, GraphExecutionMode mode) {
        return new VisualLayout(
                VisualLayout.SCHEMA_VERSION,
                rootId,
                mode,
                List.of(),
                List.of(),
                List.of(),
                new VisualLayout.Viewport(0, 0, 1)
        );
    }

    private Map<String, Integer> graphDepths(Graph graph, List<String> order) {
        Map<String, Integer> depths = new LinkedHashMap<>();
        for (String nodeId : order) {
            depths.putIfAbsent(nodeId, 0);
            for (TargetEdge edge : targetEdgesFrom(graph, nodeId)) {
                int targetDepth = depths.getOrDefault(nodeId, 0) + 1;
                depths.merge(edge.target(), targetDepth, Math::max);
            }
        }
        return depths;
    }

    private List<VisualLayout.Edge> graphEdges(Graph graph) {
        List<VisualLayout.Edge> edges = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (com.leanowtech.bloge.core.model.Edge edge : graph.edges()) {
            if (edge instanceof DirectEdge direct) {
                addEdge(edges, seen, direct.from(), direct.to(), "output");
            } else if (edge instanceof StreamEdge stream) {
                addEdge(edges, seen, stream.from(), stream.to(), "stream");
            } else if (edge instanceof ConditionalEdge conditional) {
                for (com.leanowtech.bloge.core.model.Edge.Branch branch : conditional.branches()) {
                    addEdge(edges, seen, conditional.from(), branch.target(), "branch");
                }
                if (conditional.otherwise() != null && !conditional.otherwise().isBlank()) {
                    addEdge(edges, seen, conditional.from(), conditional.otherwise(), "otherwise");
                }
            }
        }
        return edges;
    }

    private List<TargetEdge> targetEdgesFrom(Graph graph, String source) {
        List<TargetEdge> targets = new ArrayList<>();
        for (com.leanowtech.bloge.core.model.Edge edge : graph.edges()) {
            if (!source.equals(edge.from())) {
                continue;
            }
            if (edge instanceof DirectEdge direct) {
                targets.add(new TargetEdge(direct.to()));
            } else if (edge instanceof StreamEdge stream) {
                targets.add(new TargetEdge(stream.to()));
            } else if (edge instanceof ConditionalEdge conditional) {
                for (com.leanowtech.bloge.core.model.Edge.Branch branch : conditional.branches()) {
                    targets.add(new TargetEdge(branch.target()));
                }
                if (conditional.otherwise() != null && !conditional.otherwise().isBlank()) {
                    targets.add(new TargetEdge(conditional.otherwise()));
                }
            }
        }
        return targets;
    }

    private void addEdge(List<VisualLayout.Edge> edges,
                         Set<String> seen,
                         String source,
                         String target,
                         String label) {
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            return;
        }
        String id = source + "->" + target + (label == null || label.isBlank() ? "" : ":" + label);
        if (seen.add(id)) {
            edges.add(new VisualLayout.Edge(id, source, target, label));
        }
    }

    private String graphNodeKind(Graph graph, NodeSpec spec) {
        if (spec == null) {
            return "node";
        }
        NodeMetadata metadata = spec.metadata();
        if (metadata != null && metadata.streaming()) {
            return "stream";
        }
        if (graph.streamNodes().contains(spec.id())) {
            return "stream";
        }
        if (metadata != null && metadata.kind() != null) {
            return metadata.kind().wireValue();
        }
        return "operator";
    }

    private Map<String, Object> graphNodeAnnotations(NodeSpec spec) {
        if (spec == null || spec.resilience() == null) {
            return Map.of();
        }
        ResilienceConfig resilience = spec.resilience();
        Map<String, Object> annotations = new LinkedHashMap<>();
        if (resilience.hasTimeout()) {
            annotations.put("timeout", durationText(resilience.timeout()));
        }
        if (resilience.hasRetry()) {
            annotations.put("retryAttempts", resilience.retryAttempts());
            annotations.put("retryBackoff", durationText(resilience.retryBackoff()));
            annotations.put("backoffStrategy", resilience.backoffStrategy().name());
        }
        if (resilience.hasFallback()) {
            annotations.put("fallback", true);
        }
        return Map.copyOf(annotations);
    }

    private String durationText(Duration duration) {
        return duration == null ? null : duration.toString();
    }

    private boolean storedLayoutCovers(String visualLayout, Set<String> expectedNodeIds) {
        if (visualLayout == null || visualLayout.isBlank()) {
            return false;
        }
        if (expectedNodeIds.isEmpty()) {
            return true;
        }
        try {
            Object decoded = jsonCodec.deserialize(visualLayout);
            if (!(decoded instanceof Map<?, ?> map)) {
                return false;
            }
            Object nodes = map.get("nodes");
            if (!(nodes instanceof List<?> nodeList)) {
                return false;
            }
            Set<String> layoutNodeIds = new LinkedHashSet<>();
            for (Object item : nodeList) {
                if (!(item instanceof Map<?, ?> node)) {
                    return false;
                }
                Object id = node.get("id");
                if (!(id instanceof String nodeId) || nodeId.isBlank()) {
                    return false;
                }
                layoutNodeIds.add(nodeId);
            }
            if (!layoutNodeIds.containsAll(expectedNodeIds)) {
                return false;
            }
            if (!expectedNodeIds.containsAll(layoutNodeIds)) {
                return false;
            }
            Object edges = map.get("edges");
            if (edges instanceof List<?> edgeList) {
                for (Object item : edgeList) {
                    if (!(item instanceof Map<?, ?> edge)) {
                        return false;
                    }
                    Object source = edge.get("source");
                    Object target = edge.get("target");
                    if (source instanceof String sourceId && !expectedNodeIds.contains(sourceId)) {
                        return false;
                    }
                    if (target instanceof String targetId && !expectedNodeIds.contains(targetId)) {
                        return false;
                    }
                }
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Set<String> expectedNodeIds(VersionCompileResult compilation) {
        return switch (compilation.executionMode()) {
            case GRAPH -> {
                if (compilation.graph() == null) {
                    yield Set.of();
                }
                yield new LinkedHashSet<>(compilation.graph().topologicalOrder());
            }
            case SESSION -> {
                if (compilation.sessionGraph() == null) {
                    yield Set.of();
                }
                Set<String> phaseIds = new LinkedHashSet<>();
                for (PhaseDef phase : compilation.sessionGraph().phases()) {
                    phaseIds.add(phase.id());
                }
                yield phaseIds;
            }
            case STATE_MACHINE -> {
                if (compilation.stateMachine() == null) {
                    yield Set.of();
                }
                yield new LinkedHashSet<>(compilation.stateMachine().states().keySet());
            }
        };
    }

    private String rootId(GraphDefinition definition, GraphVersion version, VersionCompileResult compilation) {
        if (compilation.declaredRootName() != null && !compilation.declaredRootName().isBlank()) {
            return compilation.declaredRootName();
        }
        if (definition.definitionKey() != null && !definition.definitionKey().isBlank()) {
            return definition.definitionKey();
        }
        return version.versionId();
    }

    private String label(String id) {
        String spaced = id.replace('-', ' ').replace('_', ' ');
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < spaced.length(); i++) {
            char current = spaced.charAt(i);
            if (i > 0 && Character.isUpperCase(current) && Character.isLowerCase(spaced.charAt(i - 1))) {
                result.append(' ');
            }
            result.append(current);
        }
        String label = result.toString().trim();
        return label.isBlank() ? id : Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private String stateTransitionLabel(StateTransition transition) {
        if (transition.event() != null && !transition.event().isBlank()) {
            return transition.event();
        }
        if (transition.guardExpression() != null && !transition.guardExpression().isBlank()) {
            return transition.guardExpression();
        }
        return "transition";
    }

    private record TargetEdge(String target) {
    }
}
