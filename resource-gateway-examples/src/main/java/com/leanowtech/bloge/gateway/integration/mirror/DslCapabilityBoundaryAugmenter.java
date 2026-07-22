package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.ast.AstNode;
import com.leanowtech.bloge.dsl.ast.Expression;
import com.leanowtech.bloge.dsl.compiler.DslCompiler;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds nested DSL capability sites that the flat visual draft projection cannot yet represent. */
final class DslCapabilityBoundaryAugmenter {
    private static final int MAXIMUM_NESTED_DEPTH = 64;
    private static final int MAXIMUM_BOUNDARIES = 10_000;

    private DslCapabilityBoundaryAugmenter() {
    }

    static GraphDraft augment(GraphDraft draft, String dsl, VisualOperatorCatalog catalog) {
        AstNode ast;
        try {
            ast = new DslCompiler(new DefaultOperatorRegistry())
                    .withDiscoveredExtensionProviders()
                    .parseAst(dsl);
        } catch (RuntimeException exception) {
            throw failure("RG.MIRROR.BUILTIN_DSL_NOT_PROJECTABLE",
                    "Built-in DSL could not be parsed for nested capability inventory",
                    Map.of("graphName", draft.graphName()));
        }
        if (!(ast instanceof AstNode.GraphDef graph)) {
            throw failure("RG.MIRROR.BUILTIN_DSL_NOT_PROJECTABLE",
                    "Capability inventory requires a graph DSL root", Map.of("graphName", draft.graphName()));
        }
        State state = new State(draft, catalog);
        for (AstNode member : graph.members()) {
            if (member instanceof AstNode.ForEachDef forEach) {
                state.visitForEach(forEach, "", 1);
            } else if (member instanceof AstNode.LoopDef loop) {
                state.visitLoop(loop, "", 1);
            }
        }
        return state.build(dsl);
    }

    private static CapabilityProjectionException.Failure failure(String code,
                                                                  String message,
                                                                  Map<String, Object> details) {
        return new CapabilityProjectionException(code, message, details).failure();
    }

    private static final class State {
        private final GraphDraft source;
        private final VisualOperatorCatalog catalog;
        private final List<GraphDraft.DraftNode> nodes;
        private final List<GraphDraft.DraftEdge> edges;
        private final Map<String, String> fingerprints;
        private final Map<String, OperatorDefinition> snapshots;
        private final Set<String> nodeIds;
        private int nestedBoundaryCount;

        private State(GraphDraft source, VisualOperatorCatalog catalog) {
            this.source = source;
            this.catalog = catalog;
            this.nodes = new ArrayList<>(source.nodes());
            this.edges = new ArrayList<>(source.edges());
            this.fingerprints = new LinkedHashMap<>(source.operatorFingerprints());
            this.snapshots = new LinkedHashMap<>(source.operatorSnapshots());
            this.nodeIds = new LinkedHashSet<>();
            source.nodes().stream().map(GraphDraft.DraftNode::id).forEach(nodeIds::add);
        }

        private void visitForEach(AstNode.ForEachDef forEach, String parentPath, int depth) {
            boundedDepth(depth);
            String path = childPath(parentPath, forEach.id());
            String condition = "foreach:" + path;
            String sourceNode = sourceNode(forEach.itemsExpr());
            visitBody(forEach.body(), path, condition, sourceNode, depth);
        }

        private void visitLoop(AstNode.LoopDef loop, String parentPath, int depth) {
            boundedDepth(depth);
            String path = childPath(parentPath, loop.id());
            String condition = "loop:" + path;
            String sourceNode = loop.dependsOn().isEmpty() ? firstRootNode() : loop.dependsOn().getFirst();
            visitBody(loop.body(), path, condition, sourceNode, depth);
        }

        private void visitBody(List<AstNode> body,
                               String path,
                               String condition,
                               String sourceNode,
                               int depth) {
            for (AstNode member : body) {
                switch (member) {
                    case AstNode.NodeDef node -> addNestedBoundary(node, path, condition, sourceNode);
                    case AstNode.ForEachDef nested -> visitForEach(nested, path, depth + 1);
                    case AstNode.LoopDef nested -> visitLoop(nested, path, depth + 1);
                    default -> {
                    }
                }
            }
        }

        private void addNestedBoundary(AstNode.NodeDef node,
                                       String path,
                                       String condition,
                                       String sourceNode) {
            OperatorDefinition operator = catalog.find(node.operatorRef()).orElseThrow(() ->
                    failure("RG.MIRROR.NESTED_OPERATOR_UNRESOLVED",
                            "Nested DSL invocation has no exact operator definition",
                            Map.of("graphName", source.graphName(), "path", path,
                                    "nodeId", node.id(), "operatorRef", node.operatorRef())));
            Map<String, GraphDraft.Binding> inputs = identityBindings(node.input());
            GraphDraft.DraftNode candidate = new GraphDraft.DraftNode(path + "_" + node.id(),
                    node.operatorRef(), node.id(), inputs, Map.of("structuralPath", path),
                    new GraphDraft.Position(0, 0));
            if (!CapabilityBoundaryResolver.isBoundary(operator)) {
                return;
            }
            if (nestedBoundaryCount >= MAXIMUM_BOUNDARIES) {
                throw failure("RG.MIRROR.CAPABILITY_INVENTORY_LIMIT",
                        "Nested capability inventory exceeds its bounded maximum",
                        Map.of("graphName", source.graphName(), "maximum", MAXIMUM_BOUNDARIES));
            }
            if (!nodeIds.add(candidate.id())) {
                throw failure("RG.MIRROR.CAPABILITY_SITE_DUPLICATE",
                        "Nested capability site collides after stable path normalization",
                        Map.of("graphName", source.graphName(), "nodeId", candidate.id()));
            }
            nestedBoundaryCount++;
            nodes.add(candidate);
            snapshots.put(candidate.id(), operator);
            fingerprints.put(candidate.id(), operator.fingerprint());
            String edgeSource = nodeIds.contains(sourceNode) ? sourceNode : firstRootNode();
            edges.add(new GraphDraft.DraftEdge("mirror_" + candidate.id(), "route",
                    new GraphDraft.Endpoint(edgeSource, "", ""),
                    new GraphDraft.Endpoint(candidate.id(), "", ""), condition));
        }

        private GraphDraft build(String dsl) {
            Map<String, Object> layout = new LinkedHashMap<>(source.visualLayout());
            layout.put("capabilityProjection", Map.of(
                    "dslSourceFingerprint", VisualBundleFingerprint.fromMaterial(Map.of("dslSource", dsl)),
                    "nestedBoundaryCount", nestedBoundaryCount));
            return new GraphDraft(source.schemaVersion(), source.draftId(), source.revision(), source.graphName(),
                    source.tenantId(), source.namespace(), source.environment(), source.status(),
                    source.inputSchema(), source.outputSchema(), nodes, edges, layout, source.nodeFixtures(),
                    source.output(), fingerprints, snapshots, source.revisionMetadata());
        }

        private static Map<String, GraphDraft.Binding> identityBindings(AstNode.InputBlock input) {
            if (input == null || input.bindings().isEmpty()) {
                return Map.of();
            }
            Expression resourceId = input.bindings().get("resourceId");
            if (resourceId == null) {
                return Map.of();
            }
            GraphDraft.Binding binding = switch (resourceId) {
                case Expression.StringLiteral literal -> GraphDraft.Binding.constant(literal.value());
                case Expression.ContextPath path -> GraphDraft.Binding.contextPath(
                        String.join(".", path.segmentNames()));
                default -> GraphDraft.Binding.expression(resourceId.toString());
            };
            return Map.of("resourceId", binding);
        }

        private String firstRootNode() {
            return source.nodes().isEmpty() ? "graph" : source.nodes().getFirst().id();
        }

        private static String sourceNode(Expression expression) {
            return expression instanceof Expression.NodeOutputPath path ? path.nodeId() : "";
        }

        private static String childPath(String parent, String child) {
            return parent.isBlank() ? child : parent + "_" + child;
        }

        private void boundedDepth(int depth) {
            if (depth > MAXIMUM_NESTED_DEPTH) {
                throw failure("RG.MIRROR.CAPABILITY_INVENTORY_LIMIT",
                        "Nested capability inventory exceeds its depth limit",
                        Map.of("graphName", source.graphName(), "maximumDepth", MAXIMUM_NESTED_DEPTH));
            }
        }
    }
}
