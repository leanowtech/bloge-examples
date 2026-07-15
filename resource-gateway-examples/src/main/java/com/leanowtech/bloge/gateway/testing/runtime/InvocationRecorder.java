package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.exception.OperatorTimeoutException;
import com.leanowtech.bloge.core.model.ConditionalEdge;
import com.leanowtech.bloge.core.model.DirectEdge;
import com.leanowtech.bloge.core.model.Edge;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.model.StreamEdge;
import com.leanowtech.bloge.core.spi.ExecutionListener;
import com.leanowtech.bloge.core.spi.event.NodeEvent;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-run engine listener and fixture-consumption ledger.
 *
 * <p>The recorder is deliberately created for one run and registered only on the independent test
 * engine. It captures execution facts but never mutates graph context or business outputs.</p>
 */
public class InvocationRecorder implements ExecutionListener {

    private final Map<String, StartFact> starts = new ConcurrentHashMap<>();
    private final Map<String, TestRunEvidence.NodeTrace> traces = new ConcurrentHashMap<>();
    private final Map<String, String> fidelityByNode = new ConcurrentHashMap<>();
    private final Map<String, String> controlModeBySite = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> usesByRule = new ConcurrentHashMap<>();

    /** Marks the effective fidelity before a controlled invocation returns or fails. */
    public void markFidelity(InvocationSite site, String fidelity) {
        fidelityByNode.put(site.nodeId(), fidelity);
    }

    /** Records the effective REAL/RETURN/THROW/DENY/SPY mode by structural invocation site. */
    public void markControlMode(InvocationSite site, String controlMode) {
        controlModeBySite.put(site.invocationSiteId(), controlMode);
    }

    /** @return immutable site-to-control-mode facts in stable structural-id order */
    public Map<String, String> controlModes() {
        return Map.copyOf(new TreeMap<>(controlModeBySite));
    }

    /** Records one fixture use atomically. */
    public int consume(String ruleId) {
        return usesByRule.computeIfAbsent(ruleId, ignored -> new AtomicInteger()).incrementAndGet();
    }

    /** @return current use count for a rule */
    public int uses(String ruleId) {
        AtomicInteger value = usesByRule.get(ruleId);
        return value == null ? 0 : value.get();
    }

    @Override
    public void onNodeStart(NodeEvent.NodeStartEvent event) {
        starts.put(event.nodeId(), new StartFact(event.nodeSpec().operatorRef(), event.input()));
    }

    @Override
    public void onNodeComplete(NodeEvent.NodeCompleteEvent event) {
        StartFact start = starts.getOrDefault(event.nodeId(),
                new StartFact(event.nodeSpec().operatorRef(), null));
        String fidelity = fidelityByNode.getOrDefault(event.nodeId(), "REAL");
        traces.put(event.nodeId(), new TestRunEvidence.NodeTrace(
                event.nodeId(), start.operatorRef(), "REAL".equals(fidelity) ? "SUCCESS" : "MOCKED",
                fidelity, start.input(), event.output(), "", millis(event.nodeDuration())));
    }

    @Override
    public void onNodeFailed(NodeEvent.NodeFailedEvent event) {
        StartFact start = starts.getOrDefault(event.nodeId(),
                new StartFact(event.nodeSpec().operatorRef(), null));
        traces.put(event.nodeId(), new TestRunEvidence.NodeTrace(
                event.nodeId(), start.operatorRef(), containsTimeout(event.error()) ? "TIMEOUT" : "FAILED",
                fidelityByNode.getOrDefault(event.nodeId(), "REAL"),
                start.input(), null, errorCode(event.error()), 0));
    }

    @Override
    public void onNodeSkipped(NodeEvent.NodeSkippedEvent event) {
        traces.put(event.nodeId(), new TestRunEvidence.NodeTrace(
                event.nodeId(), "", "SKIPPED", "REAL", null, null, "", 0));
    }

    /** Returns traces ordered by deterministic graph topology and then node id. */
    public List<TestRunEvidence.NodeTrace> nodeTraces(Graph graph, GraphResult result) {
        List<String> order = new ArrayList<>(graph.topologicalOrder());
        graph.nodes().keySet().stream().filter(node -> !order.contains(node)).sorted().forEach(order::add);
        List<TestRunEvidence.NodeTrace> ordered = new ArrayList<>();
        for (String nodeId : order) {
            TestRunEvidence.NodeTrace trace = traces.get(nodeId);
            if (trace != null) {
                ordered.add(trace);
                continue;
            }
            NodeStatus status = result == null ? null : result.statusMap().get(nodeId);
            if (status != null) {
                ordered.add(new TestRunEvidence.NodeTrace(nodeId,
                        graph.nodes().get(nodeId).operatorRef(), status.name(), "REAL", null,
                        result.findOutput(nodeId, Object.class).orElse(null), "",
                        millis(result.nodeTimings().get(nodeId))));
            }
        }
        return List.copyOf(ordered);
    }

    /** Derives bounded edge-transfer evidence from graph structure and node results. */
    public List<TestRunEvidence.EdgeTrace> edgeTraces(Graph graph, GraphResult result) {
        if (result == null) {
            return List.of();
        }
        List<TestRunEvidence.EdgeTrace> edges = new ArrayList<>();
        for (Edge edge : graph.edges()) {
            Object value = result.findOutput(edge.from(), Object.class).orElse(null);
            switch (edge) {
                case DirectEdge direct -> edges.add(edgeTrace(
                        direct.from() + "->" + direct.to(), direct.from(), direct.to(), value, result));
                case StreamEdge stream -> edges.add(edgeTrace(
                        stream.from() + "~>" + stream.to(), stream.from(), stream.to(), value, result));
                case ConditionalEdge conditional -> conditionalEdges(conditional, value, result, edges);
            }
        }
        return edges.stream().sorted(Comparator.comparing(TestRunEvidence.EdgeTrace::edgeId)).toList();
    }

    /** Produces immutable per-rule consumption facts. */
    public List<TestRunEvidence.FixtureConsumption> consumptions(List<FixtureRule> rules) {
        return rules.stream().map(rule -> {
            int uses = uses(rule.ruleId());
            boolean under = uses < rule.consumption().minUses()
                    || (rule.consumption().required() && uses == 0);
            boolean over = rule.consumption().maxUses() > 0 && uses > rule.consumption().maxUses();
            return new TestRunEvidence.FixtureConsumption(rule.ruleId(), uses,
                    rule.consumption().required(), under ? "UNUSED" : over ? "OVERUSED" : "SATISFIED");
        }).toList();
    }

    private static void conditionalEdges(ConditionalEdge edge, Object value, GraphResult result,
                                         List<TestRunEvidence.EdgeTrace> output) {
        for (Edge.Branch branch : edge.branches()) {
            output.add(edgeTrace(edge.from() + "?->" + branch.target(), edge.from(),
                    branch.target(), value, result));
        }
        if (edge.otherwise() != null) {
            output.add(edgeTrace(edge.from() + "?:->" + edge.otherwise(), edge.from(),
                    edge.otherwise(), value, result));
        }
    }

    private static TestRunEvidence.EdgeTrace edgeTrace(String id, String from, String to,
                                                       Object value, GraphResult result) {
        NodeStatus fromStatus = result.statusMap().get(from);
        NodeStatus toStatus = result.statusMap().get(to);
        String status = fromStatus == NodeStatus.COMPLETED && toStatus == NodeStatus.COMPLETED
                ? "TRANSFERRED" : toStatus == NodeStatus.SKIPPED ? "SKIPPED" : "NOT_TRANSFERRED";
        return new TestRunEvidence.EdgeTrace(id, status, value);
    }

    private static long millis(Duration duration) {
        return duration == null ? 0 : Math.max(0, duration.toMillis());
    }

    private static String errorCode(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof TestControlException controlled) {
                return controlled.code();
            }
            current = current.getCause();
        }
        return error == null ? "EXECUTION_FAILED" : error.getClass().getSimpleName();
    }

    private static boolean containsTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof OperatorTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private record StartFact(String operatorRef, Object input) {
    }
}
