package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateAccessPlanTest {
    private static final String FRAGMENT = "graph state { transform result { value = true } }";

    @Test
    void rejectsParallelSameKeyReadWriteConflict() {
        Graph graph = graph(false, "parallel-conflict");
        InvocationInventory inventory = inventory(graph);
        WorldDelegateBinding binding = binding("rule", "contract", "/balance");

        assertThatThrownBy(() -> StateAccessPlan.compile(graph,
                Map.of("contract", inventory.entries()), List.of(binding), inventory.entries()))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class,
                        error -> assertThat(error.code()).isEqualTo(
                                WorldScenarioCompilationException.Code.WORLD_STATE_ACCESS_ORDER_AMBIGUOUS));
    }

    @Test
    void acceptsSameKeyAccessWhenGraphProvidesReachabilityOrder() {
        Graph graph = graph(true, "ordered-conflict");
        InvocationInventory inventory = inventory(graph);
        WorldDelegateBinding binding = binding("rule", "contract", "/balance");

        StateAccessPlan plan = StateAccessPlan.compile(graph,
                Map.of("contract", inventory.entries()), List.of(binding), inventory.entries());

        assertThat(plan.accesses()).hasSize(2);
    }

    @Test
    void disjointParallelAccessesAndTheirFingerprintIgnoreMapInsertionOrder() {
        Graph graph = graph(false, "parallel-disjoint");
        InvocationInventory inventory = inventory(graph);
        WorldDelegateBinding left = binding("left-rule", "left-contract", "/left");
        WorldDelegateBinding right = binding("right-rule", "right-contract", "/right");
        InvocationInventory.Entry leftEntry = entryForNode(inventory, "left");
        InvocationInventory.Entry rightEntry = entryForNode(inventory, "right");
        Map<String, List<InvocationInventory.Entry>> first = new LinkedHashMap<>();
        first.put("left-contract", List.of(leftEntry));
        first.put("right-contract", List.of(rightEntry));
        Map<String, List<InvocationInventory.Entry>> reversed = new LinkedHashMap<>();
        reversed.put("right-contract", List.of(rightEntry));
        reversed.put("left-contract", List.of(leftEntry));

        StateAccessPlan firstPlan = StateAccessPlan.compile(
                graph, first, List.of(left, right), inventory.entries());
        StateAccessPlan reversedPlan = StateAccessPlan.compile(
                graph, reversed, List.of(right, left), inventory.entries());

        assertThat(firstPlan.accesses()).hasSize(2);
        assertThat(firstPlan.fingerprint()).isEqualTo(reversedPlan.fingerprint());
        assertThat(firstPlan.accesses()).isEqualTo(reversedPlan.accesses());
    }

    @Test
    void rejectsStatefulChildInsideForeachWhenOrderCannotBeProven() {
        DefaultOperatorRegistry nestedRegistry = new DefaultOperatorRegistry();
        Operator<Object, Object> identity = (input, context) -> input;
        nestedRegistry.register("stateful", identity);
        Graph child = new GraphBuilder("foreach-child").node("stateful", identity).build();
        Graph root = new GraphBuilder("foreach-root")
                .node("loop", new ForEachOperator(child, nestedRegistry, true)).build();
        InvocationInventory inventory = inventory(root);
        InvocationInventory.Entry statefulChild = inventory.entries().stream()
                .filter(entry -> entry.node().id().equals("stateful"))
                .findFirst().orElseThrow();
        WorldDelegateBinding binding = binding("rule", "contract", "/balance");

        assertThatThrownBy(() -> StateAccessPlan.compile(root,
                Map.of("contract", List.of(statefulChild)), List.of(binding), inventory.entries()))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class,
                        error -> assertThat(error.code()).isEqualTo(
                                WorldScenarioCompilationException.Code.WORLD_STATE_ACCESS_ORDER_AMBIGUOUS));
    }

    @Test
    void rejectsMissingCompleteInventoryAtTheAuthoritativeCompilerBoundary() {
        Graph graph = graph(true, "missing-inventory");
        InvocationInventory inventory = inventory(graph);
        WorldDelegateBinding binding = binding("rule", "contract", "/balance");

        assertThatThrownBy(() -> StateAccessPlan.compile(graph,
                Map.of("contract", inventory.entries()), List.of(binding), null))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class,
                        error -> assertThat(error.code()).isEqualTo(
                                WorldScenarioCompilationException.Code.INVALID_INPUT));
    }

    private static WorldDelegateBinding binding(String ruleId, String contractId, String key) {
        StateSpecV2 state = StateSpecV2.of(List.of(new StateKeySpec(
                key, StateKeySpec.Access.READ_WRITE, Map.of("type", "integer"), 0)));
        return new WorldDelegateBinding(ruleId, contractId, sha(ruleId),
                BlogeFragmentRef.frozen(ruleId + ".bloge", FRAGMENT), state);
    }

    private static InvocationInventory inventory(Graph graph) {
        return new InvocationInventoryBuilder(new DefaultOperatorRegistry())
                .build(graph, sha("graph"));
    }

    private static Graph graph(boolean ordered, String name) {
        Operator<Object, Object> identity = (input, context) -> input;
        GraphBuilder builder = new GraphBuilder(name);
        var first = builder.node("left", identity).input((results, context) -> null);
        var second = first.node("right", identity);
        if (ordered) {
            second.dependsOn("left");
        }
        return second.input((results, context) -> null).build();
    }

    private static String sha(String seed) {
        return ProtocolFingerprint.ofText(seed);
    }

    private static InvocationInventory.Entry entryForNode(InvocationInventory inventory,
                                                          String nodeId) {
        return inventory.entries().stream()
                .filter(entry -> entry.node().id().equals(nodeId))
                .findFirst().orElseThrow();
    }
}
