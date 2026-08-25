package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.spi.NestedGraphProvider;
import com.leanowtech.bloge.core.spi.OperatorInvocationSite;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Recursively freezes root, nested, and compensation invocation bindings before a test run. */
public class InvocationInventoryBuilder {

    static final int MAX_NESTED_DEPTH = 64;
    static final int MAX_INVOCATION_SITES = 10_000;
    private static final String ROOT_PATH = "/root";
    private static final String COMPENSATION_NODE_SUFFIX = "__compensate";

    private final OperatorRegistry rootRegistry;

    /**
     * @param rootRegistry root graph operator registry
     */
    public InvocationInventoryBuilder(OperatorRegistry rootRegistry) {
        this.rootRegistry = Objects.requireNonNull(rootRegistry, "rootRegistry");
    }

    /**
     * Builds a bounded, cycle-checked inventory using the same child path segments as BLOGE.
     *
     * @param rootGraph submitted graph
     * @param artifactFingerprint frozen target fingerprint
     * @return immutable reachable invocation inventory
     */
    public InvocationInventory build(Graph rootGraph, String artifactFingerprint) {
        Objects.requireNonNull(rootGraph, "rootGraph");
        BuildState state = new BuildState(artifactFingerprint);
        visitGraph(rootGraph, rootRegistry, ROOT_PATH, 0, new IdentityHashMap<>(), state);
        return state.toInventory();
    }

    private void visitGraph(Graph graph, OperatorRegistry registry, String graphPath, int depth,
                            IdentityHashMap<Graph, String> ancestors, BuildState state) {
        if (depth > MAX_NESTED_DEPTH) {
            reject("CONTROL_PLAN_INVENTORY_LIMIT", "Nested graph depth exceeds "
                    + MAX_NESTED_DEPTH + " at '" + graphPath + "'.");
        }
        String ancestorPath = ancestors.put(graph, graphPath);
        if (ancestorPath != null) {
            reject("CONTROL_PLAN_INVENTORY_CYCLE", "Nested graph cycle from '" + graphPath
                    + "' to ancestor '" + ancestorPath + "'.");
        }
        try {
            for (NodeSpec node : graph.nodes().values()) {
                Object primary = resolvePrimary(graph, registry, node);
                InvocationInventory.Entry primaryEntry = entry(
                        graph, node, graphPath, state.artifactFingerprint,
                        primary, InvocationSite.InvocationKind.PRIMARY);
                state.add(primaryEntry);
                visitOwnedGraphs(primary, node.id(), graphPath, depth, ancestors, state);

                if (node.compensation() != null) {
                    Object compensation = resolveCompensation(
                            graph, registry, node.compensation().operatorRef());
                    NodeSpec compensationNode = node.toBuilder()
                            .operatorRef(node.compensation().operatorRef())
                            .inputSchema(OpaqueSchema.INSTANCE)
                            .outputSchema(OpaqueSchema.INSTANCE)
                            .operatorFingerprint(null)
                            .compensation(null)
                            .build();
                    InvocationInventory.Entry compensationEntry = entry(
                            graph, compensationNode, graphPath, state.artifactFingerprint,
                            compensation, InvocationSite.InvocationKind.COMPENSATION);
                    state.add(compensationEntry);
                    visitOwnedGraphs(compensation, node.id() + COMPENSATION_NODE_SUFFIX,
                            graphPath, depth, ancestors, state);
                }
            }
        } finally {
            ancestors.remove(graph);
        }
    }

    private void visitOwnedGraphs(Object operator, String ownerNodeId, String graphPath, int depth,
                                  IdentityHashMap<Graph, String> ancestors, BuildState state) {
        if (!(operator instanceof NestedGraphProvider provider)) {
            return;
        }
        List<NestedGraphProvider.NestedGraphBinding> bindings = provider.nestedGraphBindings();
        if (bindings == null) {
            reject("CONTROL_PLAN_INVENTORY_INVALID",
                    "NestedGraphProvider returned null at '" + graphPath + "/" + ownerNodeId + "'.");
        }
        for (NestedGraphProvider.NestedGraphBinding binding : bindings) {
            String childPath = graphPath + "/" + escape(ownerNodeId)
                    + "/" + escape(binding.pathSegment());
            visitGraph(binding.graph(), binding.registry(), childPath, depth + 1, ancestors, state);
        }
    }

    private InvocationInventory.Entry entry(Graph graph, NodeSpec node, String graphPath,
                                            String artifactFingerprint, Object operator,
                                            InvocationSite.InvocationKind requestedKind) {
        String bindingFingerprint = node.operatorFingerprint();
        if (bindingFingerprint == null) {
            bindingFingerprint = ProtocolFingerprint.ofText(
                    node.operatorRef() + "|" + operator.getClass().getName() + "|"
                            + node.inputSchema().describe() + "|" + node.outputSchema().describe());
        }
        InvocationSite.InvocationKind governanceKind = requestedKind;
        if (requestedKind == InvocationSite.InvocationKind.PRIMARY
                && "httpResource".equals(node.operatorRef())) {
            governanceKind = InvocationSite.InvocationKind.RESOURCE;
        }
        InvocationSite site = new InvocationSite(
                InvocationSite.SCHEMA_VERSION, artifactFingerprint, graphPath, node.id(),
                node.operatorRef(), "", "", bindingFingerprint, governanceKind,
                null, "", null);
        OperatorInvocationSite.InvocationKind engineKind = requestedKind
                == InvocationSite.InvocationKind.COMPENSATION
                ? OperatorInvocationSite.InvocationKind.COMPENSATION
                : OperatorInvocationSite.InvocationKind.PRIMARY;
        String engineStructuralId = new OperatorInvocationSite(
                graphPath, node.id(), node.operatorRef(), engineKind, null).structuralId();
        return new InvocationInventory.Entry(graph, node, site, engineStructuralId, operator);
    }

    private static Object resolvePrimary(Graph graph, OperatorRegistry registry, NodeSpec node) {
        try {
            if (graph.embeddedOperators().containsKey(node.id())) {
                return graph.embeddedOperators().get(node.id());
            }
            if (graph.embeddedOperators().containsKey(node.operatorRef())) {
                return graph.embeddedOperators().get(node.operatorRef());
            }
            return registry.lookup(node.operatorRef());
        } catch (RuntimeException failure) {
            if ("httpResource".equals(node.operatorRef())
                    && !registry.contains(node.operatorRef())) {
                return new EphemeralHttpResourceOperator();
            }
            throw unresolved(node.id(), node.operatorRef(), failure);
        }
    }

    private static Object resolveCompensation(Graph graph, OperatorRegistry registry,
                                              String operatorRef) {
        try {
            if (graph.embeddedOperators().containsKey(operatorRef)) {
                return graph.embeddedOperators().get(operatorRef);
            }
            return registry.lookup(operatorRef);
        } catch (RuntimeException failure) {
            throw unresolved("compensation", operatorRef, failure);
        }
    }

    private static ControlPlanRejectedException unresolved(String nodeId, String operatorRef,
                                                           RuntimeException failure) {
        return new ControlPlanRejectedException("CONTROL_PLAN_OPERATOR_UNRESOLVED", List.of(
                "Cannot freeze operator '" + operatorRef + "' for node '" + nodeId + "': "
                        + failure.getClass().getSimpleName()));
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static void reject(String code, String diagnostic) {
        throw new ControlPlanRejectedException(code, List.of(diagnostic));
    }

    private static final class BuildState {
        private final String artifactFingerprint;
        private final List<InvocationInventory.Entry> entries = new ArrayList<>();
        private final Map<String, InvocationInventory.Entry> byEngineId = new LinkedHashMap<>();
        private final Map<String, InvocationInventory.Entry> bySiteId = new LinkedHashMap<>();

        private BuildState(String artifactFingerprint) {
            this.artifactFingerprint = artifactFingerprint == null ? "" : artifactFingerprint.trim();
        }

        private void add(InvocationInventory.Entry entry) {
            if (entries.size() >= MAX_INVOCATION_SITES) {
                reject("CONTROL_PLAN_INVENTORY_LIMIT", "Invocation inventory exceeds "
                        + MAX_INVOCATION_SITES + " structural sites.");
            }
            InvocationInventory.Entry engineDuplicate =
                    byEngineId.putIfAbsent(entry.engineStructuralId(), entry);
            InvocationInventory.Entry siteDuplicate =
                    bySiteId.putIfAbsent(entry.site().invocationSiteId(), entry);
            if (engineDuplicate != null || siteDuplicate != null) {
                reject("CONTROL_PLAN_INVENTORY_DUPLICATE", "Duplicate invocation site '"
                        + entry.site().invocationSiteId() + "'.");
            }
            entries.add(entry);
        }

        private InvocationInventory toInventory() {
            return new InvocationInventory(entries, byEngineId, bySiteId);
        }
    }
}
