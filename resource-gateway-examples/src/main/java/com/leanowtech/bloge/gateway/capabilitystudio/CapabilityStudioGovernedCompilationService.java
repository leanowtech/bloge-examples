package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompilationPlan;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompiler;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Capability Studio's Stage 0 governed-compilation boundary.
 *
 * <p>This class is deliberately an adapter, not an execution engine. It validates the exact
 * Dataset projection and its payload-free source map, delegates all lowering to the existing
 * {@link ScenarioGovernedCompiler}, and exposes the resulting FixtureBundle/TestSuite
 * registration requests without registering or executing them.</p>
 */
public final class CapabilityStudioGovernedCompilationService {

    private static final String ERROR_PREFIX = "RG.CAPABILITY_STUDIO.GOVERNED_COMPILE.";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final ObjectMapper mapper;
    private final ScenarioGovernedCompiler compiler;

    public CapabilityStudioGovernedCompilationService(
            ObjectMapper mapper,
            ScenarioGovernedCompiler compiler) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
    }

    /**
     * Validates exact inputs and delegates to the existing governed Scenario compiler.
     *
     * <p>For a graph target, {@code graph} must be present; for an operator target,
     * {@code operator} must be present. The inactive exact asset may be null because the
     * existing compiler has the same graph/operator target split.</p>
     */
    public CapabilityStudioGovernedCompilation compile(
            GraphDraft graph,
            OperatorDefinition operator,
            ContractDraft contract,
            TestExecutionApiRequest.Target runtimeTarget,
            CapabilityStudioScenarioDatasetCompilation adapterCompilation) {
        require(contract != null, "CONTRACT_MISSING", "/contract");
        require(runtimeTarget != null, "RUNTIME_TARGET_MISSING", "/runtimeTarget");
        require(adapterCompilation != null, "ADAPTER_COMPILATION_MISSING", "/adapterCompilation");

        ScenarioDraftSet draftSet = adapterCompilation.draftSet();
        CapabilityStudioScenarioDatasetSourceMap sourceMap = adapterCompilation.sourceMap();
        require(draftSet != null, "DRAFT_SET_MISSING", "/adapterCompilation/draftSet");
        require(sourceMap != null, "SOURCE_MAP_MISSING", "/adapterCompilation/sourceMap");
        validateExactTarget(graph, operator, contract, runtimeTarget, adapterCompilation);
        validateDatasetClosure(draftSet, sourceMap, adapterCompilation);

        ScenarioGovernedCompilationPlan plan = compiler.compile(
                graph, operator, contract, draftSet, runtimeTarget);
        if (!plan.compiled() || plan.diagnostics().stream().anyMatch(diagnostic -> diagnostic.error())) {
            // The delegated compiler already removes all registration outputs when blocked.
            return result(plan, sourceMap);
        }
        require(plan.suite() != null, "SUITE_MISSING", "/plan/suite");
        validateCompiledSourceClosure(draftSet, sourceMap, plan);
        return result(plan, sourceMap);
    }

    private CapabilityStudioGovernedCompilation result(
            ScenarioGovernedCompilationPlan plan,
            CapabilityStudioScenarioDatasetSourceMap sourceMap) {
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                mapper,
                new FingerprintInput(plan, sourceMap),
                16 * 1_048_576);
        return new CapabilityStudioGovernedCompilation(plan, sourceMap, fingerprint);
    }

    private void validateExactTarget(
            GraphDraft graph,
            OperatorDefinition operator,
            ContractDraft contract,
            TestExecutionApiRequest.Target runtimeTarget,
            CapabilityStudioScenarioDatasetCompilation adapter) {
        ContractDraft.Target target = adapter.target();
        require(target != null && target.equals(contract.target()),
                "CONTRACT_TARGET_DRIFT", "/contract/target");
        require(target.equals(adapter.draftSet().target()),
                "ADAPTER_TARGET_DRIFT", "/adapterCompilation/draftSet/target");
        require(adapter.contractFingerprint().equals(contract.fingerprint(mapper)),
                "CONTRACT_FINGERPRINT_DRIFT", "/adapterCompilation/contractFingerprint");
        require(adapter.draftSet().contractFingerprint().equals(contract.fingerprint(mapper)),
                "DRAFT_CONTRACT_DRIFT", "/adapterCompilation/draftSet/contractFingerprint");
        require(adapter.sourceMap().contractFingerprint().equals(contract.fingerprint(mapper)),
                "SOURCE_MAP_CONTRACT_DRIFT", "/adapterCompilation/sourceMap/contractFingerprint");
        require(runtimeTarget.kind().equals(target.kind().name()),
                "RUNTIME_TARGET_KIND_DRIFT", "/runtimeTarget/kind");
        require(!runtimeTarget.id().isBlank() && !runtimeTarget.fingerprint().isBlank(),
                "RUNTIME_TARGET_NOT_EXACT", "/runtimeTarget");

        if (target.kind() == ContractDraft.TargetKind.GRAPH) {
            require(graph != null, "GRAPH_MISSING", "/graph");
            String graphId = graph.draftId().isBlank() ? graph.graphName() : graph.draftId();
            require(target.id().equals(graphId), "GRAPH_TARGET_DRIFT", "/graph");
        } else {
            require(operator != null, "OPERATOR_MISSING", "/operator");
            require(target.id().equals(operator.operatorRef()), "OPERATOR_TARGET_DRIFT", "/operator");
        }
    }

    private void validateDatasetClosure(
            ScenarioDraftSet draftSet,
            CapabilityStudioScenarioDatasetSourceMap sourceMap,
            CapabilityStudioScenarioDatasetCompilation adapter) {
        require(!draftSet.scenarios().isEmpty(),
                "CASE_SET_EMPTY", "/adapterCompilation/draftSet/scenarios");
        require(sourceMap.datasetRef() != null && sourceMap.targetRef() != null,
                "SOURCE_COORDINATE_MISSING", "/adapterCompilation/sourceMap");
        requireExactRef(sourceMap.datasetRef(), draftSet.scope(), "/sourceMap/datasetRef");
        requireExactRef(sourceMap.targetRef(), draftSet.scope(), "/sourceMap/targetRef");
        require(sourceMap.targetRef().id().equals(draftSet.target().id())
                        && sourceMap.targetRef().revision() == draftSet.target().revision()
                        && sourceMap.targetRef().fingerprint().equals(draftSet.target().fingerprint()),
                "SOURCE_TARGET_DRIFT", "/sourceMap/targetRef");

        Set<String> scenarioIds = new HashSet<>();
        require(sourceMap.cases().size() == draftSet.scenarios().size(),
                "SOURCE_CASE_COUNT_MISMATCH", "/sourceMap/cases");
        for (CapabilityStudioScenarioDatasetSourceMap.CaseSource source : sourceMap.cases()) {
            require(source != null && scenarioIds.add(source.scenarioId()),
                    "SOURCE_CASE_CLOSURE", "/sourceMap/cases");
            requireExactRef(source.caseRef(), draftSet.scope(), "/sourceMap/cases/caseRef");
            requireExactRef(source.sourceRef(), draftSet.scope(), "/sourceMap/cases/sourceRef");
            requireExactRef(source.oracleRef(), draftSet.scope(), "/sourceMap/cases/oracleRef");
            require(!source.contractRefs().isEmpty(),
                    "SOURCE_CONTRACT_CLOSURE", "/sourceMap/cases/contractRefs");
            source.contractRefs().forEach(ref -> requireExactRef(
                    ref, draftSet.scope(), "/sourceMap/cases/contractRefs"));
            source.behaviors().forEach(behavior -> {
                require(behavior != null && !behavior.ruleId().isBlank(),
                        "SOURCE_BEHAVIOR_CLOSURE", "/sourceMap/cases/behaviors");
                requireExactRef(behavior.behaviorRef(), draftSet.scope(),
                        "/sourceMap/cases/behaviors/behaviorRef");
                requireExactRef(behavior.dependencyRef(), draftSet.scope(),
                        "/sourceMap/cases/behaviors/dependencyRef");
            });
            source.expectations().forEach(expectation -> {
                require(expectation != null && !expectation.behavior().isBlank(),
                        "SOURCE_EXPECTATION_CLOSURE", "/sourceMap/cases/expectations");
                requireExactRef(expectation.behaviorRef(), draftSet.scope(),
                        "/sourceMap/cases/expectations/behaviorRef");
                requireExactRef(expectation.dependencyRef(), draftSet.scope(),
                        "/sourceMap/cases/expectations/dependencyRef");
            });
        }
        require(scenarioIds.equals(draftSet.scenarios().stream()
                        .map(ScenarioDraftSet.ScenarioDraft::scenarioId).collect(java.util.stream.Collectors.toSet())),
                "SOURCE_SCENARIO_CLOSURE", "/sourceMap/cases");
    }

    private void validateCompiledSourceClosure(
            ScenarioDraftSet draftSet,
            CapabilityStudioScenarioDatasetSourceMap sourceMap,
            ScenarioGovernedCompilationPlan plan) {
        Set<String> sourceIds = sourceMap.cases().stream()
                .map(CapabilityStudioScenarioDatasetSourceMap.CaseSource::scenarioId)
                .collect(java.util.stream.Collectors.toSet());
        require(plan.fixtures().stream().map(ScenarioGovernedCompilationPlan.CompiledFixture::scenarioId)
                        .collect(java.util.stream.Collectors.toSet()).equals(sourceIds),
                "COMPILED_SCENARIO_CLOSURE", "/plan/fixtures");
        for (ScenarioGovernedCompilationPlan.CompiledFixture fixture : plan.fixtures()) {
            require(fixture.request() != null && fixture.request().fixtureBundle() != null,
                    "FIXTURE_REGISTRATION_MISSING", "/plan/fixtures");
            Set<String> ruleIds = fixture.request().fixtureBundle().rules().stream()
                    .map(rule -> rule.ruleId()).collect(java.util.stream.Collectors.toSet());
            CapabilityStudioScenarioDatasetSourceMap.CaseSource source = sourceMap.cases().stream()
                    .filter(candidate -> candidate.scenarioId().equals(fixture.scenarioId()))
                    .findFirst().orElseThrow(() -> failure("COMPILED_SOURCE_MISSING", "/plan/fixtures"));
            require(source.behaviors().stream().map(
                            CapabilityStudioScenarioDatasetSourceMap.BehaviorSource::ruleId)
                            .collect(java.util.stream.Collectors.toSet()).equals(ruleIds),
                    "COMPILED_BEHAVIOR_CLOSURE", "/plan/fixtures/rules");
        }
        require(draftSet.scenarios().size() == plan.fixtures().size(),
                "COMPILED_CASE_COUNT_MISMATCH", "/plan/fixtures");
    }

    private static void requireExactRef(
            CapabilityStudioScenarioDatasetProjector.ExactRef ref,
            ScenarioDraftSet.EnterpriseScope scope,
            String path) {
        require(ref != null && ref.scope() != null && !ref.kind().isBlank() && !ref.id().isBlank()
                        && ref.revision() > 0 && FINGERPRINT.matcher(ref.fingerprint()).matches()
                        && ref.authority() != null && !ref.authority().isBlank()
                        && scope.equals(new ScenarioDraftSet.EnterpriseScope(
                                ref.scope().tenantId(), ref.scope().organizationId(), ref.scope().projectId(),
                                ref.scope().environmentId(), ref.scope().region())),
                "SOURCE_REF_NOT_EXACT", path);
    }

    private static void require(boolean condition, String suffix, String path) {
        if (!condition) {
            throw failure(suffix, path);
        }
    }

    private static CapabilityStudioGovernedCompilationException failure(String suffix, String path) {
        return new CapabilityStudioGovernedCompilationException(ERROR_PREFIX + suffix, path);
    }

    private record FingerprintInput(
            ScenarioGovernedCompilationPlan plan,
            CapabilityStudioScenarioDatasetSourceMap sourceMap) {
    }
}
