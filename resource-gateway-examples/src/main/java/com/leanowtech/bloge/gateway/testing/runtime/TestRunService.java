package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.engine.GraphEngine;
import com.leanowtech.bloge.core.engine.GraphResult;
import com.leanowtech.bloge.core.engine.ExecutionOptions;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionGuard;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestAssertionEvaluator;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;
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
    private final CompiledTestRuntimeOptions runtimeOptions;
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
        this.runtimeOptions = new CompiledTestRuntimeOptions(
                Objects.requireNonNull(doubleFactory, "doubleFactory"));
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
        return execute(request, compiled -> new AdmissionGuard() {
            @Override
            public void checkpoint() {
                // Focused in-process callers do not install a distributed admission gate.
            }

            @Override
            public void close() {
                // No permit exists for the compatibility path.
            }
        });
    }

    /**
     * Compiles first, acquires capacity from the exact recursive invocation closure, then executes.
     *
     * <p>Control-plan rejection never consumes capacity. Once compilation succeeds, the supplied
     * factory must acquire every required quota claim before an engine is created. The permit is
     * checked after execution and before terminal evidence leaves the kernel.</p>
     *
     * @param request frozen target, context, fixture, purpose, and provenance
     * @param admissionFactory permit factory over the exact compiled invocation inventory
     * @return plan, optional graph result, and terminal evidence
     */
    public TestExecutionResult execute(
            TestExecutionRequest request,
            AdmissionFactory admissionFactory) {
        return executeBound(request, request == null ? "" : request.targetFingerprint(),
                admissionFactory, false);
    }

    /**
     * Executes one server-regenerated mutation child with a stored baseline-bound fixture.
     *
     * <p>The separate fixture-binding target is never accepted by the ordinary execution methods.
     * This entry requires the mutation-suite purpose, stored fixture provenance, and a distinct
     * mutant execution target. It exists so child evidence identifies the mutant while fixture
     * lineage continues to identify the immutable reviewed baseline.</p>
     *
     * @param request exact mutant graph request and mutant target identity
     * @param baselineTargetFingerprint fixture's exact reviewed baseline target identity
     * @return plan, optional graph result, and terminal mutant evidence
     * @throws IllegalArgumentException when the mutation-only boundary is misused
     */
    public TestExecutionResult executeMutation(
            TestExecutionRequest request,
            String baselineTargetFingerprint) {
        return executeBound(request, baselineTargetFingerprint, compiled -> new AdmissionGuard() {
            @Override
            public void checkpoint() {
                // The mutation suite parent owns the distributed permit and checkpoint.
            }

            @Override
            public void close() {
                // No child permit exists under the parent mutation-suite permit.
            }
        }, true);
    }

    /**
     * Executes an already compiled immutable control generation without consulting the compiler or
     * mutable operator registry again.
     *
     * <p>This entry is intended for higher-level runtimes such as capability mirror execution. It
     * verifies target, purpose, fixture, rule, and replay identities before constructing the
     * isolated engine. Callers must perform their own plan-level authorization and expiry checks.</p>
     *
     * @param request exact graph, context, fixture, purpose, and evidence provenance
     * @param compiled exact previously compiled execution control
     * @return effective plan, graph result, and terminal evidence
     */
    public TestExecutionResult executeCompiled(
            TestExecutionRequest request,
            CompiledExecutionControl compiled) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(compiled, "compiled");
        validateCompiledBinding(request, compiled);
        String runId = "test-run-" + UUID.randomUUID();
        Instant startedAt = Instant.now();
        try (AdmissionGuard admission = noAdmissionGuard()) {
            return runCompiled(request, runId, startedAt, compiled, admission);
        }
    }

    private TestExecutionResult executeBound(
            TestExecutionRequest request,
            String fixtureBindingTargetFingerprint,
            AdmissionFactory admissionFactory,
            boolean mutationExecution) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(admissionFactory, "admissionFactory");
        if (mutationExecution && (request.fixtureSource() != TestExecutionRequest.FixtureSource.STORED
                || !TestSuiteRunEvidenceV5.EXECUTION_PURPOSE.equals(request.authorizedPurpose())
                || Objects.equals(request.targetFingerprint(), fixtureBindingTargetFingerprint))) {
            throw new IllegalArgumentException(
                    "Mutation execution requires its purpose, stored fixture, and distinct targets");
        }
        String runId = "test-run-" + UUID.randomUUID();
        Instant startedAt = Instant.now();
        CompiledExecutionControl compiled;
        try {
            compiled = mutationExecution
                    ? compiler.compileMutationWithSecrets(request.graph(), request.fixtureBundle(),
                    request.authorizedPurpose(), request.targetFingerprint(),
                    fixtureBindingTargetFingerprint, request.replayPayloads(), request.testSecrets())
                    : compiler.compileWithSecrets(request.graph(), request.fixtureBundle(),
                    request.authorizedPurpose(), request.targetFingerprint(), request.replayPayloads(),
                    request.testSecrets());
        } catch (ControlPlanRejectedException ex) {
            TestRunEvidence evidence = rejectedEvidence(runId, request, startedAt, ex);
            return new TestExecutionResult(null, null, evidence);
        }

        try (AdmissionGuard admission = Objects.requireNonNull(
                admissionFactory.admit(compiled), "admission guard")) {
            return runCompiled(request, runId, startedAt, compiled, admission);
        }
    }

    private TestExecutionResult runCompiled(
            TestExecutionRequest request,
            String runId,
            Instant startedAt,
            CompiledExecutionControl compiled,
            AdmissionGuard admission) {
        InvocationRecorder recorder = new InvocationRecorder(objectMapper);
        GraphResult graphResult = null;
        List<String> diagnostics = new ArrayList<>();
        GovernedExecutionServices executionServices = compiled.executionServices();
        GraphEngine engine = engineFactory.create(recorder, executionServices.services().timeSource());
        GraphContext executionContext = new GraphContext(request.context().asMap());
        executionContext.bindExecutionBudget(request.context().executionBudget());
        try {
            ExecutionOptions options = runtimeOptions.options(compiled, recorder);
            graphResult = engine.execute(request.graph(), executionContext, options);
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
        List<TestRunEvidence.NodeTrace> nodes = recorder.nodeTraces(
                compiled.inventory(), request.graph(), graphResult);
        TestRunEvidence.Status status = terminalStatus(graphResult, nodes, consumptions, assertions, diagnostics);
        graphDiagnostics(graphResult, diagnostics);
        consumptionDiagnostics(consumptions, diagnostics);
        assertionDiagnostics(assertions, diagnostics);

        admission.checkpoint();

        TestRunEvidence evidence = TestSemanticResultFingerprint.attach(objectMapper,
                new TestRunEvidence(
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
                recorder.edgeTraces(compiled.inventory(), nodes),
                consumptions,
                assertions,
                diagnostics,
                evidenceMetadata(request, recorder, executionServices, executionContext)));
        return new TestExecutionResult(compiled.effectivePlan(), graphResult, evidence);
    }

    private void validateCompiledBinding(
            TestExecutionRequest request,
            CompiledExecutionControl compiled) {
        EffectiveExecutionPlan plan = compiled.effectivePlan();
        List<String> diagnostics = new ArrayList<>();
        if (!plan.authorizedPurpose().equals(request.authorizedPurpose())) {
            diagnostics.add("Compiled purpose does not match the execution request.");
        }
        if (!plan.targetFingerprint().equals(request.targetFingerprint())) {
            diagnostics.add("Compiled target does not match the execution request.");
        }
        String fixtureFingerprint = ProtocolFingerprint.of(objectMapper, request.fixtureBundle());
        if (!plan.fixtureBundleFingerprint().equals(fixtureFingerprint)) {
            diagnostics.add("Compiled fixture does not match the execution request.");
        }
        if (!compiled.rules().equals(request.fixtureBundle().rules())) {
            diagnostics.add("Compiled rules do not match the execution fixture.");
        }
        if (!compiled.replayPayloads().planDependencies()
                .equals(request.replayPayloads().planDependencies())) {
            diagnostics.add("Compiled replay closure does not match the execution request.");
        }
        if (!diagnostics.isEmpty()) {
            throw new ControlPlanRejectedException(
                    "CONTROL_PLAN_COMPILED_BINDING_MISMATCH", diagnostics);
        }
    }

    private static AdmissionGuard noAdmissionGuard() {
        return new AdmissionGuard() {
            @Override
            public void checkpoint() {
                // The in-process precompiled caller owns any distributed admission permit.
            }

            @Override
            public void close() {
                // No permit exists for the compatibility path.
            }
        };
    }

    /** Creates a permit from the already validated, recursively frozen control plan. */
    @FunctionalInterface
    public interface AdmissionFactory {
        /**
         * @param compiled exact control plan and recursive invocation inventory
         * @return live all-dimension guard
         */
        AdmissionGuard admit(CompiledExecutionControl compiled);
    }

    /** @return structural engine-isolation facts used by architecture tests and probes */
    public IndependentTestEngineFactory.Configuration engineConfiguration() {
        return engineFactory.configuration();
    }

    private TestRunEvidence rejectedEvidence(String runId, TestExecutionRequest request,
                                             Instant startedAt, ControlPlanRejectedException ex) {
        String fixtureFingerprint;
        try {
            fixtureFingerprint = ProtocolFingerprint.of(objectMapper, request.fixtureBundle());
        } catch (RuntimeException ignored) {
            fixtureFingerprint = "";
        }
        return TestSemanticResultFingerprint.attach(objectMapper,
                new TestRunEvidence(TestRunEvidence.SCHEMA_VERSION, runId,
                TestRunEvidence.Status.CONTROL_PLAN_REJECTED,
                TestRunEvidence.EvidenceClass.EXPLORATORY,
                request.authorizedPurpose(), request.targetFingerprint(), fixtureFingerprint, "",
                startedAt, Instant.now(), List.of(), List.of(), List.of(), List.of(),
                ex.diagnostics(), evidenceMetadata(request, null, null, null)));
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
        if (!request.replayPayloads().certificationEligible()) {
            return TestRunEvidence.EvidenceClass.EXPLORATORY;
        }
        if (!compiled.executionServices().certificationGaps().isEmpty()) {
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
                                                 GovernedExecutionServices executionServices,
                                                 GraphContext executionContext) {
        Map<String, Object> metadata = new LinkedHashMap<>(request.metadata());
        metadata.put("fixtureSource", request.fixtureSource().name());
        metadata.put("engineIsolation", engineFactory.configuration());
        if (!request.replayPayloads().byReference().isEmpty()) {
            metadata.put("replayDependencies", request.replayPayloads().planDependencies());
            metadata.put("replayCertificationGaps", request.replayPayloads().certificationGaps());
        }
        if (recorder != null) {
            metadata.put("nodeControlModes", recorder.controlModes());
            metadata.put("sideEffectIntents", executionContext.sideEffectJournal().snapshots());
        }
        if (executionServices != null) {
            metadata.put("executionServiceBindings", executionServices.bindings());
            metadata.put("executionServiceUsages", executionServices.usageSnapshot());
            metadata.put("executionServiceStateFingerprint", executionServices.stateFingerprint());
            metadata.put("executionServiceCertificationGaps", executionServices.certificationGaps());
        }
        GovernedExecutionServices.LogicalTimeObservation logicalTime = executionServices == null
                ? null : executionServices.logicalTimeObservation();
        if (logicalTime != null) {
            metadata.put("logicalTime", Map.of(
                    "mode", "ADVANCING_ZERO_WALL_CLOCK",
                    "origin", logicalTime.origin().toString(),
                    "current", logicalTime.current().toString(),
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
