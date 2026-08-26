package com.leanowtech.bloge.gateway.testing.world;

import com.leanowtech.bloge.core.model.Edge;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.engine.operators.ForEachOperator;
import com.leanowtech.bloge.core.engine.operators.StreamingForEachOperator;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Compiler-frozen state access coordinates and their DAG ordering proof. */
public final class StateAccessPlan {
    public record Access(
            String ruleId,
            String coordinate,
            String nodeId,
            List<String> readKeys,
            List<String> writeKeys
    ) {
        public Access {
            if (ruleId == null || ruleId.isBlank() || coordinate == null || coordinate.isBlank()
                    || nodeId == null || nodeId.isBlank() || readKeys == null || writeKeys == null) {
                throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
            }
            ruleId = ruleId.trim();
            coordinate = coordinate.trim();
            nodeId = nodeId.trim();
            try {
                readKeys = readKeys.stream().map(StatePointer::normalize).sorted().distinct().toList();
                writeKeys = writeKeys.stream().map(StatePointer::normalize).sorted().distinct().toList();
            } catch (RuntimeException invalid) {
                throw new WorldModelException(WorldModelException.Code.STATE_INPUT_INVALID);
            }
        }

        boolean conflictsWith(Access other) {
            Set<String> left = new TreeSet<>(writeKeys);
            left.retainAll(other.readKeys);
            if (!left.isEmpty()) return true;
            left = new TreeSet<>(writeKeys);
            left.retainAll(other.writeKeys);
            if (!left.isEmpty()) return true;
            left = new TreeSet<>(other.writeKeys);
            left.retainAll(readKeys);
            return !left.isEmpty();
        }
    }

    private final List<Access> accesses;
    private final Map<String, Access> byCoordinate;
    private final String fingerprint;

    private StateAccessPlan(List<Access> accesses) {
        this.accesses = accesses.stream().sorted(Comparator.comparing(Access::coordinate)).toList();
        Map<String, Access> byCoordinate = new LinkedHashMap<>();
        for (Access access : this.accesses) {
            if (byCoordinate.put(access.coordinate(), access) != null) {
                throw new WorldScenarioCompilationException(
                        WorldScenarioCompilationException.Code.INVALID_COMPILATION);
            }
        }
        this.byCoordinate = Map.copyOf(byCoordinate);
        this.fingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                "accesses", this.accesses.stream().map(access -> Map.of(
                        "ruleId", access.ruleId(), "coordinate", access.coordinate(),
                        "nodeId", access.nodeId(), "readKeys", access.readKeys(),
                        "writeKeys", access.writeKeys())).toList()));
    }

    public static StateAccessPlan empty() {
        return new StateAccessPlan(List.of());
    }

    /**
     * Compiles access by structural site while retaining the complete inventory for control-flow
     * checks. The separate inventory is authoritative: a foreach owner may belong to a stateless
     * contract while its nested child is stateful.
     */
    static StateAccessPlan compile(
            Graph root,
            Map<String, List<InvocationInventory.Entry>> entriesByContract,
            List<WorldDelegateBinding> bindings,
            List<InvocationInventory.Entry> inventoryEntries) {
        if (root == null || entriesByContract == null || bindings == null
                || inventoryEntries == null) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
        Map<String, WorldDelegateBinding> bindingsByContract = new HashMap<>();
        for (WorldDelegateBinding binding : bindings) {
            if (bindingsByContract.put(binding.logicalContractId(), binding) != null) {
                throw new WorldScenarioCompilationException(
                        WorldScenarioCompilationException.Code.INVALID_BINDING);
            }
        }
        List<AccessCandidate> candidates = new ArrayList<>();
        entriesByContract.forEach((contract, entries) -> {
            WorldDelegateBinding binding = bindingsByContract.get(contract);
            if (binding == null) {
                throw new WorldScenarioCompilationException(
                        WorldScenarioCompilationException.Code.INVALID_BINDING);
            }
            List<String> reads = binding.stateSpec().declarations().stream()
                    .filter(declaration -> declaration.access() != StateKeySpec.Access.WRITE)
                    .map(StateKeySpec::key).toList();
            List<String> writes = binding.stateSpec().declarations().stream()
                    .filter(StateKeySpec::writes).map(StateKeySpec::key).toList();
            if (reads.isEmpty() && writes.isEmpty()) return;
            for (InvocationInventory.Entry entry : entries) {
                rejectParallelForeachStateAccess(entry, inventoryEntries);
                candidates.add(new AccessCandidate(entry.graph(), entry.node().id(),
                        new Access(binding.ruleId(), entry.site().invocationSiteId(),
                                entry.node().id(), reads, writes)));
            }
        });
        for (int left = 0; left < candidates.size(); left++) {
            for (int right = left + 1; right < candidates.size(); right++) {
                AccessCandidate first = candidates.get(left);
                AccessCandidate second = candidates.get(right);
                if (!first.access().conflictsWith(second.access())) continue;
                boolean ordered = first.graph() == second.graph()
                        && (reachable(first.graph(), first.nodeId(), second.nodeId())
                        || reachable(first.graph(), second.nodeId(), first.nodeId()));
                if (!ordered) {
                    throw new WorldScenarioCompilationException(
                            WorldScenarioCompilationException.Code.WORLD_STATE_ACCESS_ORDER_AMBIGUOUS);
                }
            }
        }
        return new StateAccessPlan(candidates.stream().map(AccessCandidate::access).toList());
    }

    public List<Access> accesses() { return accesses; }
    public String fingerprint() { return fingerprint; }
    public Access access(String coordinate) {
        return coordinate == null ? null : byCoordinate.get(coordinate);
    }

    private static void rejectParallelForeachStateAccess(
            InvocationInventory.Entry candidate,
            List<InvocationInventory.Entry> inventoryEntries) {
        String candidatePath = candidate.site().graphPath();
        for (InvocationInventory.Entry owner : inventoryEntries) {
            if (!isParallelForeach(owner.frozenOperator())) continue;
            String ownerPrefix = owner.site().graphPath() + "/"
                    + escape(owner.node().id()) + "/";
            if (candidatePath.startsWith(ownerPrefix)) {
                throw new WorldScenarioCompilationException(
                        WorldScenarioCompilationException.Code.WORLD_STATE_ACCESS_ORDER_AMBIGUOUS);
            }
        }
    }

    private static boolean isParallelForeach(Object operator) {
        if (!(operator instanceof ForEachOperator)
                && !(operator instanceof StreamingForEachOperator)) {
            return false;
        }
        // BLOGE does not expose a stable execution-mode fact in S2-C. Treat every foreach owner
        // as unsupported while it has stateful children; S2-D can reopen sequential loops after
        // BLOGE supplies an explicit coordinate/order contract.
        return true;
    }

    private static String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static boolean reachable(Graph graph, String from, String target) {
        if (from.equals(target)) return false;
        Map<String, Set<String>> edges = new HashMap<>();
        for (String node : graph.nodes().keySet()) edges.put(node, new HashSet<>());
        for (Edge edge : graph.edges()) {
            switch (edge) {
                case com.leanowtech.bloge.core.model.DirectEdge direct ->
                        edges.get(direct.from()).add(direct.to());
                case com.leanowtech.bloge.core.model.StreamEdge stream ->
                        edges.get(stream.from()).add(stream.to());
                case com.leanowtech.bloge.core.model.ConditionalEdge conditional -> {
                    for (Edge.Branch branch : conditional.branches()) {
                        edges.get(conditional.from()).add(branch.target());
                    }
                    if (conditional.otherwise() != null) {
                        edges.get(conditional.from()).add(conditional.otherwise());
                    }
                }
            }
        }
        ArrayDeque<String> queue = new ArrayDeque<>(edges.getOrDefault(from, Set.of()));
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) continue;
            if (target.equals(current)) return true;
            queue.addAll(edges.getOrDefault(current, Set.of()));
        }
        return false;
    }

    private record AccessCandidate(Graph graph, String nodeId, Access access) { }
}
