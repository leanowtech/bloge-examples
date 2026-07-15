package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestAssertionEvaluator;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.ControlPlanRejectedException;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Unified execution data-control kernel for operator, subgraph, and graph tests.
 *
 * <p>Every call performs plan compilation before constructing a fresh test engine. The engine
 * shares immutable operator bindings but none of the application engine's interceptors, listeners,
 * carriers, durable stores, quotas, response cache, or circuit-breaker state.</p>
 */
public class TestRunService {

    private final ObjectMapper objectMapper;
    private final ExecutionControlCompiler compiler;
    private final TestDoubleFactory doubleFactory;
    private final IndependentTestEngineFactory engineFactory;
    private final TestAssertionEvaluator assertionEvaluator;

    /**
     * @param registry operator binding registry used by the isolated engine
     * @param objectMapper protocol mapper
     * @param resourceRuntime optional resource protocol runtime for F2/F3 fixtures
     */
    public TestRunService(OperatorRegistry registry, ObjectMapper objectMapper,
                          ResourceFixtureRuntime resourceRuntime) {
        this(objectMapper,
                new ExecutionControlCompiler(registry, objectMapper),
                new TestDoubleFactory(objectMapper, resourceRuntime),
                new IndependentTestEngineFactory(registry),
                new TestAssertionEvaluator(objectMapper));
    }

    /** Constructor for focused conformance and architecture tests. */
    public TestRunService(ObjectMapper objectMapper, ExecutionControlCompiler compiler,
                          TestDoubleFactory doubleFactory, IndependentTestEngineFactory engineFactory,
                          TestAssertionEvaluator assertionEvaluator) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.doubleFactory = Objects.requireNonNull(doubleFactory, "doubleFactory");
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.assertionEvaluator = Objects.requireNonNull(assertionEvaluator, "assertionEvaluator");
    }

    /**
     * Compiles and executes one deterministic test run.
     *
     * @param request frozen target, context, fixture, purpose, and provenance
     * @return plan, optional graph result, and terminal evidence
     */
    public TestExecutionResult execute(TestExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        String runId = "test-run-" + UUID.randomUUID();
        Instant startedAt = Instant.now();
        CompiledExecutionControl compiled;
        try {
            compiled = compiler.compile(request.graph(), request.fixtureBundle(),
                    request.authorizedPurpose(), request.targetFingerprint());
        } catch (ControlPlanRejectedException ex) {
            TestRunEvidence evidence = rejectedEvidence(runId, request, startedAt, ex);
            return new TestExecutionResult(null, null, evidence);
        }

        InvocationRecorder recorder = new InvocationRecorder();
        Map<String, Object> overrides = overrides(request.graph(), compiled, recorder);
        GraphResult graphResult = null;
        List<String> diagnostics = new ArrayList<>();
        AdvancingLogicalTimeSource logicalTime = request.fixtureBundle().logicalClock() == null
                ? null : new AdvancingLogicalTimeSource(request.fixtureBundle().logicalClock());
        GraphEngine engine = engineFactory.create(recorder, logicalTime);
        try {
            graphResult = engine.executeWithOperators(request.graph(), request.context(), overrides);
        } catch (RuntimeException ex) {
            diagnostics.add(bounded("Test engine failed before producing GraphResult: " + ex.getMessage()));
        } finally {
            engine.shutdown();
        }

        List<TestRunEvidence.FixtureConsumption> consumptions = recorder.consumptions(compiled.rules());
        Map<String, Integer> uses = new LinkedHashMap<>();
        consumptions.forEach(item -> uses.put(item.ruleId(), item.uses()));
        List<TestRunEvidence.AssertionResult> assertions = assertionEvaluator.evaluate(
                request.fixtureBundle().assertions(), request.graph(), graphResult, uses);
        List<TestRunEvidence.NodeTrace> nodes = recorder.nodeTraces(request.graph(), graphResult);
        TestRunEvidence.Status status = terminalStatus(graphResult, nodes, consumptions, assertions, diagnostics);
        graphDiagnostics(graphResult, diagnostics);
        consumptionDiagnostics(consumptions, diagnostics);
        assertionDiagnostics(assertions, diagnostics);

        TestRunEvidence evidence = new TestRunEvidence(
                TestRunEvidence.SCHEMA_VERSION,
                runId,
                status,
                evidenceClass(request, compiled),
                request.authorizedPurpose(),
                request.targetFingerprint(),
                compiled.effectivePlan().fixtureBundleFingerprint(),
                compiled.effectivePlan().planFingerprint(),
                startedAt,
                Instant.now(),
                nodes,
                recorder.edgeTraces(request.graph(), graphResult),
                consumptions,
                assertions,
                diagnostics,
                evidenceMetadata(request, recorder, logicalTime));
        return new TestExecutionResult(compiled.effectivePlan(), graphResult, evidence);
    }

    /** @return structural engine-isolation facts used by architecture tests and probes */
    public IndependentTestEngineFactory.Configuration engineConfiguration() {
        return engineFactory.configuration();
    }

    private Map<String, Object> overrides(Graph graph, CompiledExecutionControl compiled,
                                          InvocationRecorder recorder) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        graph.nodes().forEach((nodeId, node) -> {
            Object real = compiled.frozenOperators().get(nodeId);
            CompiledExecutionControl.ResolvedControl control = compiled.controls().get(nodeId);
            if (control == null) {
                overrides.put(nodeId, real);
                return;
            }
            overrides.put(nodeId, doubleFactory.create(node, control.rules(), real,
                    control.implicitDeny(), recorder));
        });
        return Map.copyOf(overrides);
    }

    private TestRunEvidence rejectedEvidence(String runId, TestExecutionRequest request,
                                             Instant startedAt, ControlPlanRejectedException ex) {
        String fixtureFingerprint;
        try {
            fixtureFingerprint = ProtocolFingerprint.of(objectMapper, request.fixtureBundle());
        } catch (RuntimeException ignored) {
            fixtureFingerprint = "";
        }
        return new TestRunEvidence(TestRunEvidence.SCHEMA_VERSION, runId,
                TestRunEvidence.Status.CONTROL_PLAN_REJECTED,
                TestRunEvidence.EvidenceClass.EXPLORATORY,
                request.authorizedPurpose(), request.targetFingerprint(), fixtureFingerprint, "",
                startedAt, Instant.now(), List.of(), List.of(), List.of(), List.of(),
                ex.diagnostics(), evidenceMetadata(request, null, null));
    }

    private static TestRunEvidence.Status terminalStatus(
            GraphResult result,
            List<TestRunEvidence.NodeTrace> nodes,
            List<TestRunEvidence.FixtureConsumption> consumptions,
            List<TestRunEvidence.AssertionResult> assertions,
            List<String> diagnostics) {
        if (nodes.stream().anyMatch(node -> "FIXTURE_UNMATCHED".equals(node.errorCode()))) {
            return TestRunEvidence.Status.FIXTURE_UNMATCHED;
        }
        if (nodes.stream().anyMatch(node -> "TIMEOUT".equals(node.status()))) {
            return TestRunEvidence.Status.TIMED_OUT;
        }
        if (consumptions.stream().anyMatch(item -> "UNUSED".equals(item.status()))) {
            return TestRunEvidence.Status.FIXTURE_UNUSED;
        }
        if (result == null || !result.isSuccess() || !diagnostics.isEmpty()) {
            return TestRunEvidence.Status.EXECUTION_FAILED;
        }
        if (!assertions.stream().allMatch(TestRunEvidence.AssertionResult::passed)) {
            return TestRunEvidence.Status.ASSERTION_FAILED;
        }
        return TestRunEvidence.Status.PASSED;
    }

    private static TestRunEvidence.EvidenceClass evidenceClass(
            TestExecutionRequest request, CompiledExecutionControl compiled) {
        if (request.fixtureSource() != TestExecutionRequest.FixtureSource.STORED) {
            return TestRunEvidence.EvidenceClass.EXPLORATORY;
        }
        if (!request.certificationEligible()) {
            return TestRunEvidence.EvidenceClass.EXPLORATORY;
        }
        if (compiled.rules().stream().anyMatch(rule -> rule.schemaCheck().mode()
                == FixtureRule.SchemaCheckMode.WAIVED)) {
            return TestRunEvidence.EvidenceClass.EXPLORATORY;
        }
        boolean outputLevelResource = compiled.effectivePlan().resolvedSites().stream().anyMatch(site ->
                site.invocationSiteId().endsWith("#RESOURCE")
                        && site.resolution() == EffectiveExecutionPlan.Resolution.TEST_DOUBLE
                        && (site.behavior() == FixtureRule.BehaviorKind.RETURN
                        || site.behavior() == FixtureRule.BehaviorKind.DELAY)
                        && "OUTPUT_LEVEL".equals(site.fidelity()));
        return outputLevelResource ? TestRunEvidence.EvidenceClass.EXPLORATORY
                : TestRunEvidence.EvidenceClass.CERTIFIABLE;
    }

    private Map<String, Object> evidenceMetadata(TestExecutionRequest request,
                                                 InvocationRecorder recorder,
                                                 AdvancingLogicalTimeSource logicalTime) {
        Map<String, Object> metadata = new LinkedHashMap<>(request.metadata());
        metadata.put("fixtureSource", request.fixtureSource().name());
        metadata.put("engineIsolation", engineFactory.configuration());
        if (recorder != null) {
            metadata.put("nodeControlModes", recorder.controlModes());
            metadata.put("sideEffectIntents", request.context().sideEffectJournal().snapshots());
        }
        if (logicalTime != null) {
            metadata.put("logicalTime", Map.of(
                    "mode", "ADVANCING_ZERO_WALL_CLOCK",
                    "origin", logicalTime.origin().toString(),
                    "current", logicalTime.now().toString(),
                    "elapsedMs", logicalTime.elapsed().toMillis()));
        }
        return Map.copyOf(metadata);
    }

    private static void graphDiagnostics(GraphResult result, List<String> diagnostics) {
        if (result == null) return;
        result.errors().forEach(error -> diagnostics.add(bounded(
                error.nodeId() + ": " + error.exception().getMessage())));
    }

    private static void consumptionDiagnostics(List<TestRunEvidence.FixtureConsumption> consumptions,
                                               List<String> diagnostics) {
        consumptions.stream().filter(item -> !"SATISFIED".equals(item.status()))
                .forEach(item -> diagnostics.add("Fixture '" + item.ruleId() + "' is "
                        + item.status() + " (uses=" + item.uses() + ")."));
    }

    private static void assertionDiagnostics(List<TestRunEvidence.AssertionResult> assertions,
                                             List<String> diagnostics) {
        assertions.stream().filter(item -> !item.passed())
                .forEach(item -> diagnostics.add(bounded(item.diagnostic() + " " + item.path())));
    }

    private static String bounded(String value) {
        String safe = value == null ? "" : value;
        return safe.length() <= 500 ? safe : safe.substring(0, 500);
    }
}
