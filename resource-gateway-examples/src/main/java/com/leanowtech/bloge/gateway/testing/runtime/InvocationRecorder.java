package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
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
import com.leanowtech.bloge.gateway.testing.domain.FixtureConsumptionStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Per-run engine listener and fixture-consumption ledger.
 *
 * <p>The recorder is deliberately created for one run and registered only on the independent test
 * engine. It captures execution facts but never mutates graph context or business outputs.</p>
 */
public class InvocationRecorder implements ExecutionListener {

    private static final String CURSOR_IDENTITY_VERSION = "bloge.fixtureCursorIdentity.v1";
    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock fixtureStateLock = new ReentrantReadWriteLock(true);
    private final Map<String, StartFact> starts = new ConcurrentHashMap<>();
    private final Map<String, TestRunEvidence.NodeTrace> traces = new ConcurrentHashMap<>();
    private final Map<RuntimeSiteKey, String> fidelityByRuntimeSite = new ConcurrentHashMap<>();
    private final Map<String, String> controlModeBySite = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> usesByRule = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> occurrences = new ConcurrentHashMap<>();
    private final Map<InvocationBinding, InvocationFact> invocationFacts = new ConcurrentHashMap<>();
    private final Set<InvocationBinding> pendingInvocations = ConcurrentHashMap.newKeySet();
    private final Set<InvocationBinding> inFlightAttempts = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> graphOccurrences = new ConcurrentHashMap<>();
    private final IdentityHashMap<GraphContext, Map<String, Integer>> graphOccurrencesByContext =
            new IdentityHashMap<>();
    /**
     * Completion correlation is identity based because GraphContext is the engine's execution
     * boundary. A node id alone is not unique once nested graphs or repeated invocations exist.
     */
    private final Object invocationCorrelationLock = new Object();
    private final IdentityHashMap<GraphContext, Map<String, List<InvocationBinding>>>
            invocationBindingsByContext = new IdentityHashMap<>();

    /**
     * Creates one run-scoped ledger using the application mapper as the canonical protocol baseline.
     *
     * @param objectMapper mapper used to seal and validate fixture-state checkpoints
     */
    public InvocationRecorder(ObjectMapper objectMapper) {
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /** Marks the effective fidelity before a controlled invocation returns or fails. */
    public void markFidelity(InvocationSite site, String fidelity) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            fidelityByRuntimeSite.put(RuntimeSiteKey.from(site), fidelity);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Allocates a stable, one-based occurrence when the run-scoped resolver binds one operator.
     * Retries reuse this binding; a nested graph re-entry allocates the next occurrence. Object
     * identity of the execution-local graph context joins sibling nodes without entering evidence.
     *
     * @param site runtime structural and correlation coordinates
     * @param graphContext context instance shared by nodes in one containing graph execution
     * @return immutable site and containing-graph occurrence binding
     */
    public InvocationBinding bind(InvocationSite site, GraphContext graphContext) {
        if (graphContext == null) {
            throw new IllegalArgumentException("graphContext must not be null");
        }
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            String key = siteCursorKey(site);
            int occurrence = occurrences.computeIfAbsent(key, ignored -> new AtomicInteger())
                    .incrementAndGet();
            int graphOccurrence = bindGraphOccurrence(site, graphContext);
            InvocationBinding binding = new InvocationBinding(site, occurrence, graphOccurrence);
            invocationFacts.put(binding, new InvocationFact(binding));
            synchronized (invocationCorrelationLock) {
                invocationBindingsByContext.computeIfAbsent(graphContext,
                                ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(site.nodeId(), ignored -> new ArrayList<>())
                        .add(binding);
            }
            pendingInvocations.add(binding);
            return binding;
        } finally {
            lock.unlock();
        }
    }

    /** Records one successful delegate attempt under its stable occurrence binding. */
    public void recordSuccess(InvocationBinding binding, NodeSpec node, Object input, Object output,
                              int attempt, long durationMs) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            String fidelity = fidelity(binding);
            fact(binding).record(new TestRunEvidence.AttemptTrace(attempt,
                    "REAL".equals(fidelity) ? "SUCCESS" : "MOCKED",
                    fidelity, input, output, "", durationMs), node);
        } finally {
            lock.unlock();
        }
    }

    /** Records one failed delegate attempt without flattening retry history into the node outcome. */
    public void recordFailure(InvocationBinding binding, NodeSpec node, Object input,
                              Exception failure, int attempt, long durationMs) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            String fidelity = fidelity(binding);
            fact(binding).record(new TestRunEvidence.AttemptTrace(attempt,
                    containsTimeout(failure) ? "TIMEOUT" : "FAILED", fidelity,
                    input, null, errorCode(failure), durationMs), node);
        } finally {
            lock.unlock();
        }
    }

    /** Records the effective REAL/RETURN/THROW/DENY/SPY mode by structural invocation site. */
    public void markControlMode(InvocationSite site, String controlMode) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            controlModeBySite.put(site.invocationSiteId(), controlMode);
        } finally {
            lock.unlock();
        }
    }

    /** @return immutable site-to-control-mode facts in stable structural-id order */
    public Map<String, String> controlModes() {
        return Map.copyOf(new TreeMap<>(controlModeBySite));
    }

    /** Records one unbounded fixture use atomically. */
    public int consume(String ruleId) {
        return consumeIfAvailable(ruleId, 0);
    }

    /**
     * Atomically reserves one fixture use without exceeding its configured upper bound.
     *
     * @param ruleId stable fixture-rule identity
     * @param maxUses maximum uses, or zero for unbounded
     * @return one-based use number, or {@code -1} when the bound was already exhausted
     */
    int consumeIfAvailable(String ruleId, int maxUses) {
        java.util.Objects.requireNonNull(ruleId, "ruleId");
        if (maxUses < 0) {
            throw new IllegalArgumentException("maxUses must be >= 0");
        }
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            AtomicInteger counter = usesByRule.computeIfAbsent(ruleId,
                    ignored -> new AtomicInteger());
            while (true) {
                int current = counter.get();
                if (maxUses > 0 && current >= maxUses) {
                    return -1;
                }
                if (current == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Fixture use counter overflow");
                }
                if (counter.compareAndSet(current, current + 1)) {
                    return current + 1;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** @return current use count for a rule */
    public int uses(String ruleId) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            AtomicInteger value = usesByRule.get(ruleId);
            return value == null ? 0 : value.get();
        } finally {
            lock.unlock();
        }
    }

    /** Opens the attempt boundary that excludes concurrent checkpoint capture. */
    void beginAttempt(InvocationBinding binding) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            fact(binding);
            if (!inFlightAttempts.add(binding)) {
                throw new IllegalStateException("Invocation attempt is already in flight");
            }
            pendingInvocations.remove(binding);
        } finally {
            lock.unlock();
        }
    }

    /** Closes an attempt boundary after its success or failure fact has been recorded. */
    void endAttempt(InvocationBinding binding) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            if (!inFlightAttempts.remove(binding)) {
                throw new IllegalStateException("Invocation attempt is not in flight");
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Atomically captures payload-free fixture use and occurrence cursors for durable recovery.
     *
     * <p>Capture is permitted only when no invocation is pending its first attempt and no attempt is
     * in flight. Runtime structural and correlation coordinates are reduced to versioned SHA-256
     * keys before they enter the ledger, so neither the in-memory cursor maps nor the persisted
     * snapshot retain their raw values. The hashes are pseudonymous identifiers, not a secrecy
     * boundary for low-entropy source values.</p>
     *
     * @return sealed immutable fixture-consumption state
     */
    public FixtureConsumptionStateSnapshot captureFixtureState() {
        var lock = fixtureStateLock.writeLock();
        lock.lock();
        try {
            if (!pendingInvocations.isEmpty() || !inFlightAttempts.isEmpty()) {
                throw new IllegalStateException(
                        "Fixture state requires a quiescent invocation boundary");
            }
            FixtureConsumptionStateSnapshot material = new FixtureConsumptionStateSnapshot(
                    FixtureConsumptionStateSnapshot.SCHEMA_VERSION,
                    counterSnapshot(usesByRule), counterSnapshot(occurrences),
                    counterSnapshot(graphOccurrences), "");
            String fingerprint = ProtocolFingerprint.ofBounded(objectMapper,
                    material.fingerprintMaterial(),
                    FixtureConsumptionStateSnapshot.MAX_CANONICAL_BYTES);
            return material.withStateFingerprint(fingerprint);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Restores a verified fixture ledger before a resumed engine can allocate another occurrence.
     *
     * <p>Restore is deliberately replacement-only. Merging into an active recorder would make it
     * impossible to prove whether local or persisted counters won and could re-consume a fixture.</p>
     *
     * @param snapshot sealed fixture-consumption state from the trusted checkpoint closure
     */
    public void restoreFixtureState(FixtureConsumptionStateSnapshot snapshot) {
        java.util.Objects.requireNonNull(snapshot, "snapshot");
        var lock = fixtureStateLock.writeLock();
        lock.lock();
        try {
            String fingerprint = ProtocolFingerprint.ofBounded(objectMapper,
                    snapshot.fingerprintMaterial(),
                    FixtureConsumptionStateSnapshot.MAX_CANONICAL_BYTES);
            if (!fingerprint.equals(snapshot.stateFingerprint())) {
                throw new IllegalArgumentException("Invalid fixture-consumption state fingerprint");
            }
            if (hasRuntimeState()) {
                throw new IllegalStateException(
                        "Fixture state can only be restored into an empty recorder");
            }
            restoreCounters(snapshot.ruleUses(), usesByRule);
            restoreCounters(snapshot.siteOccurrenceCursors(), occurrences);
            restoreCounters(snapshot.graphOccurrenceCursors(), graphOccurrences);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onNodeStart(NodeEvent.NodeStartEvent event) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            starts.put(event.nodeId(), new StartFact(event.nodeSpec().operatorRef(), event.input()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onNodeComplete(NodeEvent.NodeCompleteEvent event) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            completeInvocationFact(event.ctx(), event.nodeId(), event.nodeSpec(), event.output(),
                    millis(event.nodeDuration()));
            StartFact start = starts.getOrDefault(event.nodeId(),
                    new StartFact(event.nodeSpec().operatorRef(), null));
            String fidelity = "REAL";
            traces.put(event.nodeId(), new TestRunEvidence.NodeTrace(
                    event.nodeId(), start.operatorRef(), "REAL".equals(fidelity) ? "SUCCESS" : "MOCKED",
                    fidelity, start.input(), event.output(), "", millis(event.nodeDuration())));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Reconciles engine completion with the invocation wrapper that recorded the attempts. The
     * engine owns fallback execution, so the wrapper cannot record the fallback as a normal
     * delegate success. Correlation is serialized only for the identity-map lookup and claim;
     * payload construction remains confined to the invocation fact.
     */
    private void completeInvocationFact(GraphContext graphContext, String nodeId, NodeSpec node,
                                        Object output, long durationMs) {
        if (graphContext == null || nodeId == null) {
            return;
        }
        synchronized (invocationCorrelationLock) {
            Map<String, List<InvocationBinding>> byNode = invocationBindingsByContext.get(graphContext);
            if (byNode == null) {
                return;
            }
            List<InvocationBinding> bindings = byNode.get(nodeId);
            if (bindings == null) {
                return;
            }
            for (InvocationBinding binding : bindings) {
                InvocationFact fact = invocationFacts.get(binding);
                if (fact != null && fact.completeFromEngine(node, output, durationMs)) {
                    return;
                }
            }
        }
    }

    @Override
    public void onNodeFailed(NodeEvent.NodeFailedEvent event) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            StartFact start = starts.getOrDefault(event.nodeId(),
                    new StartFact(event.nodeSpec().operatorRef(), null));
            traces.put(event.nodeId(), new TestRunEvidence.NodeTrace(
                    event.nodeId(), start.operatorRef(),
                    containsTimeout(event.error()) ? "TIMEOUT" : "FAILED", "REAL",
                    start.input(), null, errorCode(event.error()), 0));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onNodeSkipped(NodeEvent.NodeSkippedEvent event) {
        var lock = fixtureStateLock.readLock();
        lock.lock();
        try {
            traces.put(event.nodeId(), new TestRunEvidence.NodeTrace(
                    event.nodeId(), "", "SKIPPED", "REAL", null, null, "", 0));
        } finally {
            lock.unlock();
        }
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

    /**
     * Reconstructs occurrence-addressable root and nested edge facts from the frozen inventory and
     * node evidence. Graph-execution occurrence, rather than per-node occurrence, joins source and
     * target facts so conditional skips in an earlier re-entry cannot shift later pairings.
     */
    public List<TestRunEvidence.EdgeTrace> edgeTraces(InvocationInventory inventory,
                                                      List<TestRunEvidence.NodeTrace> nodeTraces) {
        Map<String, GraphEvidenceShape> shapes = evidenceShapes(inventory);
        List<TestRunEvidence.EdgeTrace> edges = new ArrayList<>();
        Map<GraphOccurrenceKey, List<TestRunEvidence.NodeTrace>> groups = new LinkedHashMap<>();
        nodeTraces.stream().filter(trace -> trace.graphOccurrence() > 0)
                .forEach(trace -> groups.computeIfAbsent(GraphOccurrenceKey.from(trace),
                        ignored -> new ArrayList<>()).add(trace));
        groups.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(group -> {
            GraphEvidenceShape shape = shapes.get(group.getKey().graphPath());
            if (shape != null) {
                appendOccurrenceEdges(shape, group.getKey(), group.getValue(), edges);
            }
        });
        return edges.stream().sorted(edgeTraceOrder()).toList();
    }

    private static Map<String, GraphEvidenceShape> evidenceShapes(InvocationInventory inventory) {
        Map<String, GraphEvidenceShapeBuilder> builders = new LinkedHashMap<>();
        for (InvocationInventory.Entry entry : inventory.entries()) {
            if (entry.site().invocationKind() == InvocationSite.InvocationKind.COMPENSATION) {
                continue;
            }
            GraphEvidenceShapeBuilder builder = builders.computeIfAbsent(entry.site().graphPath(),
                    ignored -> new GraphEvidenceShapeBuilder(entry.graph()));
            builder.siteByNode.put(entry.site().nodeId(), entry.site().invocationSiteId());
        }
        Map<String, GraphEvidenceShape> shapes = new LinkedHashMap<>();
        builders.forEach((path, builder) -> shapes.put(path, builder.build()));
        return Map.copyOf(shapes);
    }

    private static void appendOccurrenceEdges(GraphEvidenceShape shape, GraphOccurrenceKey key,
                                              List<TestRunEvidence.NodeTrace> traces,
                                              List<TestRunEvidence.EdgeTrace> output) {
        Map<String, TestRunEvidence.NodeTrace> byNode = new LinkedHashMap<>();
        for (TestRunEvidence.NodeTrace trace : traces) {
            if (trace.invocationSiteId().equals(shape.siteByNode().get(trace.nodeId()))) {
                byNode.put(trace.nodeId(), trace);
            }
        }
        for (Edge edge : shape.graph().edges()) {
            TestRunEvidence.NodeTrace source = byNode.get(edge.from());
            Object value = source == null ? null : source.output();
            switch (edge) {
                case DirectEdge direct -> output.add(occurrenceEdgeTrace(direct.from() + "->" + direct.to(),
                        direct.from(), direct.to(), value, false, key, shape, byNode));
                case StreamEdge stream -> output.add(occurrenceEdgeTrace(stream.from() + "~>" + stream.to(),
                        stream.from(), stream.to(), value, false, key, shape, byNode));
                case ConditionalEdge conditional -> {
                    for (Edge.Branch branch : conditional.branches()) {
                        output.add(occurrenceEdgeTrace(conditional.from() + "?->" + branch.target(),
                                conditional.from(), branch.target(), value, true, key, shape, byNode));
                    }
                    if (conditional.otherwise() != null) {
                        output.add(occurrenceEdgeTrace(conditional.from() + "?:->" + conditional.otherwise(),
                                conditional.from(), conditional.otherwise(), value, true, key, shape, byNode));
                    }
                }
            }
        }
    }

    private static TestRunEvidence.EdgeTrace occurrenceEdgeTrace(
            String localId, String from, String to, Object value, boolean conditional,
            GraphOccurrenceKey key, GraphEvidenceShape shape,
            Map<String, TestRunEvidence.NodeTrace> byNode) {
        TestRunEvidence.NodeTrace source = byNode.get(from);
        TestRunEvidence.NodeTrace target = byNode.get(to);
        boolean sourceSucceeded = source != null
                && ("SUCCESS".equals(source.status()) || "MOCKED".equals(source.status()));
        boolean targetInvoked = target != null && !"SKIPPED".equals(target.status())
                && !"CANCELLED".equals(target.status()) && !"NOT_INVOKED".equals(target.status());
        String status = sourceSucceeded && targetInvoked ? "TRANSFERRED"
                : conditional && sourceSucceeded ? "SKIPPED" : "NOT_TRANSFERRED";
        String edgeId = "/root".equals(key.graphPath()) ? localId
                : key.graphPath() + "/" + escapedEdgeId(localId);
        return addressedEdgeTrace(edgeId, status, value, key, shape, from, to);
    }

    private static TestRunEvidence.EdgeTrace addressedEdgeTrace(
            String edgeId, String status, Object value, GraphOccurrenceKey key,
            GraphEvidenceShape shape, String from, String to) {
        return new TestRunEvidence.EdgeTrace(edgeId, status, value, key.graphPath(),
                key.correlationKey(), key.graphOccurrence(), shape.siteByNode().getOrDefault(from, ""),
                shape.siteByNode().getOrDefault(to, ""));
    }

    private static String escapedEdgeId(String localId) {
        return localId.replace("~", "~0").replace("/", "~1");
    }

    private static Comparator<TestRunEvidence.EdgeTrace> edgeTraceOrder() {
        return Comparator.comparing(TestRunEvidence.EdgeTrace::graphPath)
                .thenComparing(TestRunEvidence.EdgeTrace::correlationKey)
                .thenComparingInt(TestRunEvidence.EdgeTrace::graphOccurrence)
                .thenComparing(TestRunEvidence.EdgeTrace::edgeId);
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

    private synchronized int bindGraphOccurrence(InvocationSite site, GraphContext graphContext) {
        String graphKey = graphCursorKey(site);
        Map<String, Integer> contextBindings = graphOccurrencesByContext
                .computeIfAbsent(graphContext, ignored -> new LinkedHashMap<>());
        return contextBindings.computeIfAbsent(graphKey, ignored -> graphOccurrences
                .computeIfAbsent(graphKey, key -> new AtomicInteger()).incrementAndGet());
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
        Map<String, String> errorCodes = result.errors().stream()
                .collect(java.util.stream.Collectors.toMap(
                        error -> error.nodeId(),
                        error -> errorCode(error.exception()),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
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
                    result.findOutput(site.nodeId(), Object.class).orElse(null),
                    errorCodes.getOrDefault(site.nodeId(), ""),
                    millis(result.nodeTimings().get(site.nodeId())), site.invocationSiteId(),
                    site.graphPath(), "", 1, 1, List.of()));
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
            if (current instanceof TestOutcomeFailure controlled) {
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

    /** Stable site and graph occurrence allocation reused by every retry attempt. */
    public record InvocationBinding(InvocationSite site, int occurrence, int graphOccurrence) {
        /** Rejects malformed bindings before they can create ambiguous evidence. */
        public InvocationBinding {
            if (site == null) {
                throw new IllegalArgumentException("site must not be null");
            }
            if (occurrence < 1) {
                throw new IllegalArgumentException("occurrence must be >= 1");
            }
            if (graphOccurrence < 1) {
                throw new IllegalArgumentException("graphOccurrence must be >= 1");
            }
        }
    }

    private record RuntimeSiteKey(String invocationSiteId, String correlationKey) {
        private static RuntimeSiteKey from(InvocationSite site) {
            return new RuntimeSiteKey(site.invocationSiteId(), site.correlationKey());
        }
    }

    private String siteCursorKey(InvocationSite site) {
        return cursorKey("SITE", site.invocationSiteId(), site.correlationKey());
    }

    private String graphCursorKey(InvocationSite site) {
        return cursorKey("GRAPH", site.graphPath(), site.correlationKey());
    }

    private static String cursorKey(String kind, String structuralCoordinate,
                                    String correlationKey) {
        String material = CURSOR_IDENTITY_VERSION + "|" + kind + "|"
                + encoded(structuralCoordinate) + "|" + encoded(correlationKey);
        return ProtocolFingerprint.ofText(material);
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Long> counterSnapshot(Map<String, AtomicInteger> counters) {
        Map<String, Long> values = new TreeMap<>();
        counters.forEach((key, value) -> values.put(key, (long) value.get()));
        return Map.copyOf(values);
    }

    private static void restoreCounters(Map<String, Long> source,
                                        Map<String, AtomicInteger> target) {
        source.forEach((key, value) -> target.put(key,
                new AtomicInteger(Math.toIntExact(value))));
    }

    private boolean hasRuntimeState() {
        return !usesByRule.isEmpty() || !occurrences.isEmpty() || !graphOccurrences.isEmpty()
                || !graphOccurrencesByContext.isEmpty() || !invocationFacts.isEmpty()
                || !pendingInvocations.isEmpty() || !inFlightAttempts.isEmpty()
                || !starts.isEmpty() || !traces.isEmpty() || !fidelityByRuntimeSite.isEmpty()
                || !controlModeBySite.isEmpty();
    }

    private record GraphOccurrenceKey(String graphPath, String correlationKey, int graphOccurrence)
            implements Comparable<GraphOccurrenceKey> {
        private static GraphOccurrenceKey from(TestRunEvidence.NodeTrace trace) {
            return new GraphOccurrenceKey(trace.graphPath(), trace.correlationKey(),
                    trace.graphOccurrence());
        }

        @Override
        public int compareTo(GraphOccurrenceKey other) {
            int path = graphPath.compareTo(other.graphPath);
            if (path != 0) return path;
            int correlation = correlationKey.compareTo(other.correlationKey);
            if (correlation != 0) return correlation;
            return Integer.compare(graphOccurrence, other.graphOccurrence);
        }
    }

    private record GraphEvidenceShape(Graph graph, Map<String, String> siteByNode) {
        private GraphEvidenceShape {
            siteByNode = Map.copyOf(siteByNode);
        }
    }

    private static final class GraphEvidenceShapeBuilder {
        private final Graph graph;
        private final Map<String, String> siteByNode = new LinkedHashMap<>();

        private GraphEvidenceShapeBuilder(Graph graph) {
            this.graph = graph;
        }

        private GraphEvidenceShape build() {
            return new GraphEvidenceShape(graph, siteByNode);
        }
    }

    private static final class InvocationFact {
        private final InvocationBinding binding;
        private final ConcurrentLinkedQueue<TestRunEvidence.AttemptTrace> attempts =
                new ConcurrentLinkedQueue<>();
        private volatile String operatorRef;
        private volatile Completion completion;

        private InvocationFact(InvocationBinding binding) {
            this.binding = binding;
            this.operatorRef = binding.site().operatorRef();
        }

        private void record(TestRunEvidence.AttemptTrace attempt, NodeSpec node) {
            operatorRef = node.operatorRef();
            attempts.add(attempt);
        }

        /** Claims the single terminal engine completion for this invocation occurrence. */
        private synchronized boolean completeFromEngine(NodeSpec node, Object output,
                                                         long durationMs) {
            if (completion != null) {
                return false;
            }
            operatorRef = node == null ? operatorRef : node.operatorRef();
            completion = new Completion(output, durationMs);
            return true;
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
            Completion terminal = completion;
            if (terminal != null) {
                status = last != null && isMockedTerminal(last.status()) ? "MOCKED"
                        : "SUCCESS";
                output = terminal.output();
                errorCode = "";
            }
            if (rootResult != null
                    && rootResult.statusMap().get(binding.site().nodeId()) == NodeStatus.COMPLETED) {
                status = "REAL".equals(fidelity) ? "SUCCESS" : "MOCKED";
                output = rootResult.findOutput(binding.site().nodeId(), Object.class).orElse(null);
                errorCode = "";
            }
            long duration = terminal == null
                    ? orderedAttempts.stream()
                    .mapToLong(TestRunEvidence.AttemptTrace::durationMs).sum()
                    : terminal.durationMs();
            InvocationSite site = binding.site();
            return new TestRunEvidence.NodeTrace(site.nodeId(), operatorRef, status, fidelity,
                    input, output, errorCode, duration, site.invocationSiteId(), site.graphPath(),
                    site.correlationKey(), binding.occurrence(), binding.graphOccurrence(),
                    orderedAttempts);
        }

        private static boolean isTerminalFailure(String status) {
            return "FAILED".equals(status) || "TIMEOUT".equals(status);
        }

        private static boolean isMockedTerminal(String status) {
            return isTerminalFailure(status) || "MOCKED".equals(status);
        }

        private record Completion(Object output, long durationMs) {
        }
    }
}
