package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionGuard;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestAssertionEvaluator;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionModeHints;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;
import com.leanowtech.bloge.gateway.testing.planning.SelectorResolver;
import com.leanowtech.bloge.gateway.testing.runtime.IndependentTestEngineFactory;
import com.leanowtech.bloge.gateway.testing.runtime.TestDoubleFactory;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** High-level C2b bridge from a verified pure world compilation to one test run. */
public final class WorldScenarioRunService {
    public static final String GRAPH_CONTRACT_TEST = "GRAPH_CONTRACT_TEST";

    private final OperatorRegistry registry;
    private final ObjectMapper objectMapper;
    private final WorldFragmentTestKit fragmentTestKit;

    public WorldScenarioRunService(OperatorRegistry registry, ObjectMapper objectMapper) {
        this(registry, objectMapper, new WorldFragmentTestKit());
    }

    public WorldScenarioRunService(OperatorRegistry registry, ObjectMapper objectMapper,
                                   WorldFragmentTestKit fragmentTestKit) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.fragmentTestKit = fragmentTestKit;
    }

    /** Rebuilds exact sites and runs only a server-owned world-delegate generation. */
    public TestExecutionResult execute(WorldScenarioCompilation compilation,
                                       TestExecutionRequest request) {
        return execute(compilation, request, ignored -> noAdmissionGuard());
    }

    /**
     * Rebuilds exact sites, acquires capacity only after control compilation, and runs the
     * server-owned world-delegate generation.
     */
    public TestExecutionResult execute(WorldScenarioCompilation compilation,
                                       TestExecutionRequest request,
                                       TestRunService.AdmissionFactory admissionFactory) {
        CompiledExecutionControl compiled = compile(compilation, request);
        TestRunService runtime = new TestRunService(objectMapper, new ExecutionControlCompiler(
                registry, objectMapper), new TestDoubleFactory(objectMapper, null,
                new WorldDelegateRuntime(compilation, fragmentTestKit)),
                new IndependentTestEngineFactory(registry), new TestAssertionEvaluator(objectMapper));
        AdmissionGuard admission = Objects.requireNonNull(
                Objects.requireNonNull(admissionFactory, "admissionFactory").admit(compiled),
                "admission guard");
        try (admission) {
            TestExecutionResult result = runtime.executeCompiled(request, compiled);
            admission.checkpoint();
            return result;
        }
    }

    private CompiledExecutionControl compile(WorldScenarioCompilation compilation,
                                              TestExecutionRequest request) {
        if (compilation == null || request == null
                || !GRAPH_CONTRACT_TEST.equals(request.authorizedPurpose())
                || request.fixtureBundle() == null
                || !request.fixtureBundle().equals(compilation.bundle())
                ) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
        String actualTargetFingerprint;
        try {
            if (request.graph() == null) {
                throw new IllegalArgumentException("graph");
            }
            actualTargetFingerprint = GraphArtifactFingerprint.of(objectMapper, request.graph());
        } catch (RuntimeException failure) {
            throw targetDrift();
        }
        if (!actualTargetFingerprint.equals(compilation.bundle().targetFingerprint())
                || !actualTargetFingerprint.equals(request.targetFingerprint())) {
            throw targetDrift();
        }
        if (fragmentTestKit == null) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
        compilation.verifyFingerprint();
        InvocationInventory inventory = rebuildInventory(request.graph(), request.targetFingerprint());
        Map<String, CompiledExecutionControl.ResolvedControl> resolved = resolve(
                inventory, compilation.bundle().rules());
        ExecutionModeHints hints = exactWorldHints(compilation, resolved);

        ExecutionControlCompiler controlCompiler = new ExecutionControlCompiler(
                registry, objectMapper);
        CompiledExecutionControl compiled = controlCompiler.compileWithExecutionModeHints(
                request.graph(), compilation.bundle(), GRAPH_CONTRACT_TEST,
                request.targetFingerprint(), hints);
        verifyHintedControls(compiled, hints);
        return compiled;
    }

    /** Convenience adapter for callers that already have a context and no request metadata. */
    public TestExecutionResult execute(WorldScenarioCompilation compilation, Graph graph,
                                       GraphContext context) {
        if (compilation == null || graph == null) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
        TestExecutionRequest request = new TestExecutionRequest(
                graph, context, compilation.bundle(), GRAPH_CONTRACT_TEST,
                compilation.bundle().targetFingerprint(), TestExecutionRequest.FixtureSource.INLINE,
                Map.of(), false, null, null);
        return execute(compilation, request);
    }

    private InvocationInventory rebuildInventory(Graph graph, String targetFingerprint) {
        try {
            return new InvocationInventoryBuilder(registry).build(graph, targetFingerprint);
        } catch (RuntimeException failure) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.INVOCATION_INVENTORY);
        }
    }

    private static Map<String, CompiledExecutionControl.ResolvedControl> resolve(
            InvocationInventory inventory, List<FixtureRule> rules) {
        try {
            return new SelectorResolver().resolve(inventory, rules);
        } catch (RuntimeException failure) {
            throw new WorldScenarioCompilationException(
                    WorldScenarioCompilationException.Code.SELECTOR_RESOLUTION);
        }
    }

    private static ExecutionModeHints exactWorldHints(
            WorldScenarioCompilation compilation,
            Map<String, CompiledExecutionControl.ResolvedControl> resolved) {
        Map<String, WorldDelegateBinding> bindings = new LinkedHashMap<>();
        for (WorldDelegateBinding binding : compilation.bindings()) {
            if (bindings.put(binding.ruleId(), binding) != null) {
                throw invalidBinding();
            }
        }
        if (!bindings.keySet().equals(compilation.bundle().rules().stream()
                .map(FixtureRule::ruleId).collect(java.util.stream.Collectors.toSet()))) {
            throw invalidBinding();
        }
        ExecutionModeHints.Builder hints = ExecutionModeHints.builder();
        Set<String> matchedRules = new LinkedHashSet<>();
        for (WorldDelegateBinding binding : bindings.values()) {
            Set<String> expectedSites = expectedInvocationSites(compilation, binding);
            Set<String> actualSites = new TreeSet<>();
            resolved.forEach((siteId, control) -> {
                if (control.rules().stream().anyMatch(rule -> rule.ruleId().equals(binding.ruleId()))) {
                    actualSites.add(siteId);
                }
            });
            if (!actualSites.equals(expectedSites) || actualSites.isEmpty()) {
                throw new WorldScenarioCompilationException(
                        WorldScenarioCompilationException.Code.SELECTOR_RESOLUTION);
            }
            actualSites.forEach(siteId -> {
                hints.worldDelegate(siteId, binding.ruleId());
                matchedRules.add(binding.ruleId());
            });
        }
        if (!matchedRules.equals(bindings.keySet())) {
            throw invalidBinding();
        }
        return hints.build();
    }

    private static Set<String> expectedInvocationSites(
            WorldScenarioCompilation compilation, WorldDelegateBinding binding) {
        String logicalContract = WorldScenarioSourceMap.coordinate("logical-contract",
                binding.logicalContractId() + "@" + binding.contractFingerprint());
        Set<String> result = new TreeSet<>();
        for (String output : compilation.sourceMap().sourceToOutputs(logicalContract)) {
            String prefix = "invocation-site:";
            if (output.startsWith(prefix)) {
                result.add(output.substring(prefix.length()));
            }
        }
        return result;
    }

    private static void verifyHintedControls(CompiledExecutionControl compiled,
                                             ExecutionModeHints hints) {
        hints.entries().forEach((siteId, rules) -> rules.forEach((ruleId, mode) -> {
            CompiledExecutionControl.ResolvedControl control = compiled.controls().get(siteId);
            if (control == null || control.executionMode(
                    control.rules().stream().filter(rule -> rule.ruleId().equals(ruleId))
                            .findFirst().orElseThrow(WorldScenarioRunService::invalidBinding))
                    .orElse(null) != mode) {
                throw new WorldScenarioCompilationException(
                        WorldScenarioCompilationException.Code.SELECTOR_RESOLUTION);
            }
        }));
    }

    private static WorldScenarioCompilationException invalidBinding() {
        return new WorldScenarioCompilationException(
                WorldScenarioCompilationException.Code.INVALID_BINDING);
    }

    private static WorldScenarioCompilationException targetDrift() {
        return new WorldScenarioCompilationException(
                WorldScenarioCompilationException.Code.TARGET_DRIFT);
    }

    private static AdmissionGuard noAdmissionGuard() {
        return new AdmissionGuard() {
            @Override
            public void checkpoint() {
                // Compatibility callers do not install a distributed admission gate.
            }

            @Override
            public void close() {
                // No permit exists for the compatibility path.
            }
        };
    }
}
