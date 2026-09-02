package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable static topology and pinned Fixture selections for one Flow simulation command.
 *
 * <p>Conditions are deliberately unresolved here. A Flow node may receive values from earlier
 * nodes, so the runtime resolves each selection only after its mapping has produced the actual
 * invocation input. The topology contains exact immutable component coordinates and never stores
 * Fixture material or business payloads.</p>
 */
public record ResolvedFlowSimulationPlanV2(
        ExactFixtureSubjectRefV2 subject, SchemaEnvelope inputContract, SchemaEnvelope outputContract,
        ReusableFlowCommand.Graph graph, JsonNode input, SimulationCommandV2.Unmatched unmatched,
        Map<List<String>, Node> nodes, Map<List<String>, Binding> bindings,
        Map<SimulationCommandV2.FixtureTarget.CallSite, Binding> callSiteBindings,
        String fingerprint) {
    public ResolvedFlowSimulationPlanV2 {
        input = input.deepCopy();
        nodes = immutable(nodes);
        bindings = immutable(bindings);
        callSiteBindings = callSiteBindings == null ? Map.of() : Map.copyOf(callSiteBindings);
    }

    /** Backward-compatible constructor for plans without compiled Function Call Site bindings. */
    public ResolvedFlowSimulationPlanV2(
            ExactFixtureSubjectRefV2 subject, SchemaEnvelope inputContract, SchemaEnvelope outputContract,
            ReusableFlowCommand.Graph graph, JsonNode input, SimulationCommandV2.Unmatched unmatched,
            Map<List<String>, Node> nodes, Map<List<String>, Binding> bindings, String fingerprint) {
        this(subject, inputContract, outputContract, graph, input, unmatched, nodes, bindings,
                Map.of(), fingerprint);
    }

    @Override public JsonNode input() { return input.deepCopy(); }
    @Override public Map<List<String>, Node> nodes() { return immutable(nodes); }
    @Override public Map<List<String>, Binding> bindings() { return immutable(bindings); }
    @Override public Map<SimulationCommandV2.FixtureTarget.CallSite, Binding> callSiteBindings() {
        return Map.copyOf(callSiteBindings);
    }

    /** One exact static DAG node, including a child Flow graph only when it may be expanded. */
    public record Node(List<String> path, ExactFixtureSubjectRefV2 subject,
                       ReusableFlowCommand.Contract contract, ReusableFlowCommand.Node authoredNode,
                       ReusableFlowCommand.Graph childGraph,
                       List<ComponentSimulationAuthorityV2.CallSite> callSites) {
        public Node {
            path = List.copyOf(path);
            callSites = callSites == null ? List.of() : List.copyOf(callSites);
        }
        public Node(List<String> path, ExactFixtureSubjectRefV2 subject,
                    ReusableFlowCommand.Contract contract, ReusableFlowCommand.Node authoredNode,
                    ReusableFlowCommand.Graph childGraph) {
            this(path, subject, contract, authoredNode, childGraph, List.of());
        }
        @Override public List<String> path() { return List.copyOf(path); }
        @Override public List<ComponentSimulationAuthorityV2.CallSite> callSites() {
            return List.copyOf(callSites);
        }
    }

    /** One caller-pinned selection or an already compiled CASE_CONTROLS selection. */
    public record Binding(SimulationCommandV2.FixtureTarget target,
                          SimulationCommandV2.FixtureSelection selection,
                          ResolvedFixturePlan.Selection fixedSelection) {
        public Binding {
            if (target == null || selection == null && fixedSelection == null
                    || selection != null && fixedSelection != null) {
                throw new IllegalArgumentException("Flow Fixture binding is invalid");
            }
        }
    }

    private static <T> Map<List<String>, T> immutable(Map<List<String>, T> source) {
        LinkedHashMap<List<String>, T> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> copied.put(List.copyOf(key), value));
        return Map.copyOf(copied);
    }
}
