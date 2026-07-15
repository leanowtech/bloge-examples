package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.exception.OperatorTimeoutException;
import com.leanowtech.bloge.core.model.ConditionalEdge;
import com.leanowtech.bloge.core.model.DirectEdge;
import com.leanowtech.bloge.core.model.Edge;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeStatus;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.model.StreamEdge;
import com.leanowtech.bloge.core.spi.ExecutionListener;
import com.leanowtech.bloge.core.spi.event.NodeEvent;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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
    private final Map<RuntimeSiteKey, String> fidelityByRuntimeSite = new ConcurrentHashMap<>();
    private final Map<String, String> controlModeBySite = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> usesByRule = new ConcurrentHashMap<>();
    private final Map<RuntimeSiteKey, AtomicInteger> occurrences = new ConcurrentHashMap<>();
    private final Map<InvocationBinding, InvocationFact> invocationFacts = new ConcurrentHashMap<>();

    /** Marks the effective fidelity before a controlled invocation returns or fails. */
    public void markFidelity(InvocationSite site, String fidelity) {
        fidelityByRuntimeSite.put(RuntimeSiteKey.from(site), fidelity);
    }

    /**
     * Allocates a stable, one-based occurrence when the run-scoped resolver binds one operator.
     * Retries reuse this binding; a nested graph re-entry allocates the next occurrence.
     */
    public InvocationBinding bind(InvocationSite site) {
        RuntimeSiteKey key = RuntimeSiteKey.from(site);
        int occurrence = occurrences.computeIfAbsent(key, ignored -> new AtomicInteger())
                .incrementAndGet();
        InvocationBinding binding = new InvocationBinding(site, occurrence);
        invocationFacts.put(binding, new InvocationFact(binding));
        return binding;
    }

    /** Records one successful delegate attempt under its stable occurrence binding. */
    public void recordSuccess(InvocationBinding binding, NodeSpec node, Object input, Object output,
                              int attempt, long durationMs) {
        String fidelity = fidelity(binding);
        fact(binding).record(new TestRunEvidence.AttemptTrace(attempt,
                "REAL".equals(fidelity) ? "SUCCESS" : "MOCKED",
                fidelity, input, output, "", durationMs), node);
    }

    /** Records one failed delegate attempt without flattening retry history into the node outcome. */
    public void recordFailure(InvocationBinding binding, NodeSpec node, Object input,
                              Exception failure, int attempt, long durationMs) {
        String fidelity = fidelity(binding);
        fact(binding).record(new TestRunEvidence.AttemptTrace(attempt,
                containsTimeout(failure) ? "TIMEOUT" : "FAILED", fidelity,
                input, null, errorCode(failure), durationMs), node);
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
        String fidelity = "REAL";
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
                "REAL",
                start.input(), null, errorCode(event.error()), 0));
    }

    @Override
    public void onNodeSkipped(NodeEvent.NodeSkippedEvent event) {
        traces.put(event.nodeId(), new TestRunEvidence.NodeTrace(
                event.nodeId(), "", "SKIPPED", "REAL", null, null, "", 0));
    }

    /**
     * Returns one summary per resolver occurrence with ordered attempt history. Root fallback and
     * skipped outcomes are reconciled from {@link GraphResult}; nested invocations retain their
     * exact graph path and runtime correlation coordinates.
     */
    public List<TestRunEvidence.NodeTrace> nodeTraces(InvocationInventory inventory, Graph graph,
                                                      GraphResult result) {
        Map<String, Integer> siteOrder = new TreeMap<>();
        for (int index = 0; index < inventory.entries().size(); index++) {
            siteOrder.put(inventory.entries().get(index).site().invocationSiteId(), index);
        }
        List<TestRunEvidence.NodeTrace> ordered = invocationFacts.values().stream()
                .map(fact -> fact.toTrace(rootResultFor(fact.binding.site(), result)))
                .sorted(traceOrder(siteOrder))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        addUnobservedRootOutcomes(inventory, graph, result, ordered);
        ordered.sort(traceOrder(siteOrder));
        return List.copyOf(ordered);
    }

    /** Retains the legacy root-only projection for focused callers without a compiled inventory. */
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
                        graph.nodes().get(nodeId).operatorRef(), normalized(status), "REAL", null,
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

    private String fidelity(InvocationBinding binding) {
        return fidelityByRuntimeSite.getOrDefault(RuntimeSiteKey.from(binding.site()), "REAL");
    }

    private InvocationFact fact(InvocationBinding binding) {
        InvocationFact fact = invocationFacts.get(binding);
        if (fact == null) {
            throw new IllegalStateException("Invocation binding was not allocated by this recorder: "
                    + binding.site().invocationSiteId() + " occurrence " + binding.occurrence());
        }
        return fact;
    }

    private static GraphResult rootResultFor(InvocationSite site, GraphResult result) {
        return result != null && "/root".equals(site.graphPath())
                && site.invocationKind() != InvocationSite.InvocationKind.COMPENSATION
                ? result : null;
    }

    private static Comparator<TestRunEvidence.NodeTrace> traceOrder(Map<String, Integer> siteOrder) {
        return Comparator
                .comparingInt((TestRunEvidence.NodeTrace trace) ->
                        siteOrder.getOrDefault(trace.invocationSiteId(), Integer.MAX_VALUE))
                .thenComparing(TestRunEvidence.NodeTrace::correlationKey)
                .thenComparingInt(TestRunEvidence.NodeTrace::occurrence)
                .thenComparing(TestRunEvidence.NodeTrace::nodeId);
    }

    private static void addUnobservedRootOutcomes(InvocationInventory inventory, Graph graph,
                                                   GraphResult result,
                                                   List<TestRunEvidence.NodeTrace> output) {
        if (result == null) {
            return;
        }
        var observed = output.stream().map(TestRunEvidence.NodeTrace::invocationSiteId)
                .collect(java.util.stream.Collectors.toSet());
        for (InvocationInventory.Entry entry : inventory.entries()) {
            InvocationSite site = entry.site();
            if (entry.graph() != graph || !"/root".equals(site.graphPath())
                    || site.invocationKind() == InvocationSite.InvocationKind.COMPENSATION
                    || observed.contains(site.invocationSiteId())) {
                continue;
            }
            NodeStatus status = result.statusMap().get(site.nodeId());
            if (status == null) {
                continue;
            }
            output.add(new TestRunEvidence.NodeTrace(site.nodeId(), site.operatorRef(),
                    normalized(status), "REAL", null,
                    result.findOutput(site.nodeId(), Object.class).orElse(null), "",
                    millis(result.nodeTimings().get(site.nodeId())), site.invocationSiteId(),
                    site.graphPath(), "", 1, List.of()));
        }
    }

    private static String normalized(NodeStatus status) {
        return status == NodeStatus.COMPLETED ? "SUCCESS" : status.name();
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

    /** Stable one-run occurrence allocation reused by every retry attempt. */
    public record InvocationBinding(InvocationSite site, int occurrence) {
        /** Rejects malformed bindings before they can create ambiguous evidence. */
        public InvocationBinding {
            if (site == null) {
                throw new IllegalArgumentException("site must not be null");
            }
            if (occurrence < 1) {
                throw new IllegalArgumentException("occurrence must be >= 1");
            }
        }
    }

    private record RuntimeSiteKey(String invocationSiteId, String correlationKey) {
        private static RuntimeSiteKey from(InvocationSite site) {
            return new RuntimeSiteKey(site.invocationSiteId(), site.correlationKey());
        }
    }

    private static final class InvocationFact {
        private final InvocationBinding binding;
        private final ConcurrentLinkedQueue<TestRunEvidence.AttemptTrace> attempts =
                new ConcurrentLinkedQueue<>();
        private volatile String operatorRef;

        private InvocationFact(InvocationBinding binding) {
            this.binding = binding;
            this.operatorRef = binding.site().operatorRef();
        }

        private void record(TestRunEvidence.AttemptTrace attempt, NodeSpec node) {
            operatorRef = node.operatorRef();
            attempts.add(attempt);
        }

        private TestRunEvidence.NodeTrace toTrace(GraphResult rootResult) {
            List<TestRunEvidence.AttemptTrace> orderedAttempts = attempts.stream()
                    .sorted(Comparator.comparingInt(TestRunEvidence.AttemptTrace::attempt)).toList();
            TestRunEvidence.AttemptTrace first = orderedAttempts.isEmpty() ? null
                    : orderedAttempts.getFirst();
            TestRunEvidence.AttemptTrace last = orderedAttempts.isEmpty() ? null
                    : orderedAttempts.getLast();
            String fidelity = last == null ? "REAL" : last.fidelity();
            String status = last == null ? "NOT_INVOKED" : last.status();
            Object input = first == null ? null : first.input();
            Object output = last == null ? null : last.output();
            String errorCode = last == null ? "" : last.errorCode();
            if (rootResult != null
                    && rootResult.statusMap().get(binding.site().nodeId()) == NodeStatus.COMPLETED) {
                status = "REAL".equals(fidelity) ? "SUCCESS" : "MOCKED";
                output = rootResult.findOutput(binding.site().nodeId(), Object.class).orElse(null);
                errorCode = "";
            }
            long duration = orderedAttempts.stream().mapToLong(TestRunEvidence.AttemptTrace::durationMs)
                    .sum();
            InvocationSite site = binding.site();
            return new TestRunEvidence.NodeTrace(site.nodeId(), operatorRef, status, fidelity,
                    input, output, errorCode, duration, site.invocationSiteId(), site.graphPath(),
                    site.correlationKey(), binding.occurrence(), orderedAttempts);
        }
    }
}
