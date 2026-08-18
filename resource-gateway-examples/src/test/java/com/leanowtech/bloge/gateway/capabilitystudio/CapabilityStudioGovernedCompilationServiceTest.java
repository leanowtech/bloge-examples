package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompilationPlan;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompilationProvenance;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompiler;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedProvenanceMetadataCodec;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioValidationService;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraftProjectionService;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Acceptance tests for the Stage 0 Dataset-to-governed-compiler integration boundary. */
class CapabilityStudioGovernedCompilationServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private final CapabilityStudioGoldenDemoPack pack =
            new CapabilityStudioGoldenDemoPackLoader().load(JSON);
    private final CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset =
            new CapabilityStudioScenarioDatasetProjector(pack, JSON).project();
    private final OperatorDefinition operator = toolOperator();
    private final ContractDraft contract = toolContract();
    private final CapabilityStudioScenarioDatasetCompiler adapter =
            new CapabilityStudioScenarioDatasetCompiler(JSON);
    private final CapabilityStudioGovernedCompilationService service =
            new CapabilityStudioGovernedCompilationService(JSON, new ScenarioGovernedCompiler(
                    new ScenarioValidationService(JSON), JSON));

    @Test
    void compilesCanonicalNineCasesThroughTheExistingGovernedCompiler() {
        CapabilityStudioGovernedCompilation result = service.compile(
                null, operator, contract, runtimeTarget(), adapterCompilation());

        assertThat(result.compiled())
                .as("governed diagnostics: %s", result.plan().diagnostics())
                .isTrue();
        assertThat(result.plan().fixtures()).hasSize(9);
        assertThat(result.plan().suite()).isNotNull();
        assertThat(result.plan().suite().testSuite().cases()).hasSize(9);
        assertThat(result.sourceMap().cases()).hasSize(9);
        assertThat(result.plan().fixtures()).allSatisfy(fixture -> {
            assertThat(fixture.request().fixtureBundle()).isNotNull();
            assertThat(fixture.request().fixtureBundle().rules()).isNotEmpty();
        });
    }

    @Test
    void propagatesTheCompleteExactRefClosureWithTheFixedMetadataContract() {
        CapabilityStudioGovernedCompilation result = service.compile(
                null, operator, contract, runtimeTarget(), adapterCompilation());
        String sourceMapFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                JSON, result.sourceMap(), 16 * 1_048_576);
        Set<ScenarioGovernedCompilationProvenance.ExactRef> expected = expectedRefs(result.sourceMap());

        assertThat(result.plan().fixtures()).allSatisfy(compiled -> {
            Map<String, Object> metadata = compiled.request().fixtureBundle().metadata();
            assertThat(metadata).containsEntry(
                    ScenarioGovernedCompiler.GOVERNED_SOURCE_MAP_FINGERPRINT,
                    sourceMapFingerprint);
            assertThat(metadata).containsKeys(
                    ScenarioGovernedCompiler.GOVERNED_PROVENANCE_SCHEMA_VERSION,
                    ScenarioGovernedCompiler.GOVERNED_PROVENANCE_FINGERPRINT,
                    ScenarioGovernedCompiler.GOVERNED_EXACT_REFS);
            List<ScenarioGovernedCompilationProvenance.ExactRef> refs =
                    ScenarioGovernedProvenanceMetadataCodec.decodeExactRefs(metadata.get(
                            ScenarioGovernedCompiler.GOVERNED_EXACT_REFS));
            assertThat(refs).containsExactlyInAnyOrderElementsOf(expected);
        });

        var suite = result.plan().suite().testSuite();
        assertThat(suite.metadata().get(ScenarioGovernedCompiler.GOVERNED_EXACT_REFS))
                .isEqualTo(result.plan().fixtures().getFirst().request().fixtureBundle().metadata()
                        .get(ScenarioGovernedCompiler.GOVERNED_EXACT_REFS));
        assertThat(suite.cases()).allSatisfy(testCase -> {
            assertThat(testCase.metadata()).containsKeys(
                    ScenarioGovernedCompiler.GOVERNED_PROVENANCE_SCHEMA_VERSION,
                    ScenarioGovernedCompiler.GOVERNED_PROVENANCE_FINGERPRINT,
                    ScenarioGovernedCompiler.GOVERNED_SOURCE_MAP_FINGERPRINT);
            assertThat(testCase.metadata())
                    .doesNotContainKey(ScenarioGovernedCompiler.GOVERNED_EXACT_REFS);
        });
    }

    @Test
    void keepsCanonicalCaseCountInAcceptanceDataInsteadOfTheGenericCompilerBoundary() {
        CapabilityStudioScenarioDatasetCompilation original = adapterCompilation();
        ScenarioDraftSet.ScenarioDraft scenario = original.draftSet().scenarios().getFirst();
        ScenarioDraftSet oneCase = new ScenarioDraftSet(
                original.draftSet().schemaVersion(), original.draftSet().scenarioDraftSetId(),
                original.draftSet().revision(), original.draftSet().scope(), original.draftSet().target(),
                original.draftSet().contractFingerprint(), List.of(scenario), original.draftSet().metadata());
        CapabilityStudioScenarioDatasetSourceMap oneSource = new CapabilityStudioScenarioDatasetSourceMap(
                original.sourceMap().datasetRef(), original.sourceMap().targetRef(),
                original.sourceMap().contractFingerprint(), List.of(original.sourceMap().cases().getFirst()));
        CapabilityStudioScenarioDatasetCompilation oneCaseAdapter =
                new CapabilityStudioScenarioDatasetCompilation(
                        oneCase, oneSource, original.target(), original.contractFingerprint(),
                        original.semanticFingerprint());

        CapabilityStudioGovernedCompilation result = service.compile(
                null, operator, contract, runtimeTarget(), oneCaseAdapter);

        assertThat(result.compiled())
                .as("governed diagnostics: %s", result.plan().diagnostics())
                .isTrue();
        assertThat(result.plan().fixtures()).hasSize(1);
        assertThat(result.plan().suite().testSuite().cases()).hasSize(1);
    }

    @Test
    void keepsTheGovernedPlanAndSourceMapDeterministicAcrossThreeCompilations() throws Exception {
        CapabilityStudioGovernedCompilation first = service.compile(
                null, operator, contract, runtimeTarget(), adapterCompilation());
        CapabilityStudioGovernedCompilation second = service.compile(
                null, operator, contract, runtimeTarget(), adapterCompilation());
        CapabilityStudioGovernedCompilation third = service.compile(
                null, operator, contract, runtimeTarget(), adapterCompilation());

        assertThat(first.semanticFingerprint()).isEqualTo(second.semanticFingerprint())
                .isEqualTo(third.semanticFingerprint());
        assertThat(JSON.writeValueAsString(first.plan())).isEqualTo(JSON.writeValueAsString(second.plan()))
                .isEqualTo(JSON.writeValueAsString(third.plan()));
        assertThat(JSON.writeValueAsString(first.sourceMap()))
                .isEqualTo(JSON.writeValueAsString(second.sourceMap()))
                .isEqualTo(JSON.writeValueAsString(third.sourceMap()));
    }

    @Test
    void keepsCanonicalExactClosureBoundedAndProvidesACompactChildEvidenceBinding()
            throws Exception {
        CapabilityStudioGovernedCompilation result = service.compile(
                null, operator, contract, runtimeTarget(), adapterCompilation());
        Map<String, Object> metadata = result.plan().suite().testSuite().metadata();
        Object exactRefs = metadata.get(ScenarioGovernedCompiler.GOVERNED_EXACT_REFS);
        int exactRefCount = ScenarioGovernedProvenanceMetadataCodec.exactRefCount(exactRefs);

        assertThat(exactRefCount).isGreaterThan(50).isLessThanOrEqualTo(4_096);
        assertThat(JSON.writeValueAsBytes(metadata).length).isLessThanOrEqualTo(16_384);
        Map<String, Object> compactChildBinding = Map.of(
                ScenarioGovernedCompiler.GOVERNED_PROVENANCE_SCHEMA_VERSION,
                metadata.get(ScenarioGovernedCompiler.GOVERNED_PROVENANCE_SCHEMA_VERSION),
                ScenarioGovernedCompiler.GOVERNED_PROVENANCE_FINGERPRINT,
                metadata.get(ScenarioGovernedCompiler.GOVERNED_PROVENANCE_FINGERPRINT),
                ScenarioGovernedCompiler.GOVERNED_SOURCE_MAP_FINGERPRINT,
                metadata.get(ScenarioGovernedCompiler.GOVERNED_SOURCE_MAP_FINGERPRINT),
                "governedExactRefCount", exactRefCount);
        assertThat(JSON.writeValueAsBytes(compactChildBinding).length).isLessThanOrEqualTo(16_384);
    }

    @Test
    void exposesNoRegistrationAssetsWhenTheDelegatedCompilerReportsDiagnostics() {
        CapabilityStudioScenarioDatasetCompilation original = adapterCompilation();
        List<ScenarioDraftSet.ScenarioDraft> scenarios = new ArrayList<>(original.draftSet().scenarios());
        ScenarioDraftSet.ScenarioDraft first = scenarios.getFirst();
        scenarios.set(0, new ScenarioDraftSet.ScenarioDraft(
                first.scenarioId(), first.name(), first.description(), first.caseType(), first.tags(),
                new ScenarioDraftSet.Given(Map.of(), ScenarioDraftSet.ValueProvenance.IMPORTED),
                first.dependencies(), first.then()));
        ScenarioDraftSet invalid = new ScenarioDraftSet(
                original.draftSet().schemaVersion(), original.draftSet().scenarioDraftSetId(),
                original.draftSet().revision(), original.draftSet().scope(), original.draftSet().target(),
                original.draftSet().contractFingerprint(), scenarios, original.draftSet().metadata());
        CapabilityStudioScenarioDatasetCompilation invalidAdapter = new CapabilityStudioScenarioDatasetCompilation(
                invalid, original.sourceMap(), original.target(), original.contractFingerprint(),
                original.semanticFingerprint());

        CapabilityStudioGovernedCompilation result = service.compile(
                null, operator, contract, runtimeTarget(), invalidAdapter);

        assertThat(result.compiled()).isFalse();
        assertThat(result.plan().fixtures()).isEmpty();
        assertThat(result.plan().suite()).isNull();
        assertThat(result.plan().diagnostics()).anyMatch(diagnostic -> diagnostic.error());
    }

    @Test
    void rejectsContractDriftWithoutIncludingScenarioPayloadInTheError() {
        String secret = "customer-payload-secret";
        ContractDraft drifted = new ContractDraft(
                contract.schemaVersion(),
                new ContractDraft.Target(
                        contract.target().kind(), contract.target().id(), contract.target().revision(),
                        fingerprint('f')),
                contract.inputSchema(), contract.outputSchema(), contract.errorContract(),
                contract.executionSemantics(), contract.invariants(), contract.compatibilityPolicy(),
                contract.fieldMetadata(), contract.source(), contract.confidence());

        assertThatThrownBy(() -> service.compile(
                null, operator, drifted, runtimeTarget(), adapterCompilation()))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .satisfies(error -> {
                    CapabilityStudioGovernedCompilationException failure =
                            (CapabilityStudioGovernedCompilationException) error;
                    assertThat(failure.code()).isEqualTo(
                            "RG.CAPABILITY_STUDIO.GOVERNED_COMPILE.CONTRACT_TARGET_DRIFT");
                    assertThat(failure.getMessage()).doesNotContain(secret, "payload");
                });
    }

    @Test
    void rejectsMissingAuthorityInTheDatasetSourceClosure() {
        CapabilityStudioScenarioDatasetSourceMap.CaseSource first =
                adapterCompilation().sourceMap().cases().getFirst();
        CapabilityStudioScenarioDatasetProjector.ExactRef source = first.sourceRef();
        CapabilityStudioScenarioDatasetProjector.ExactRef unauthorized =
                new CapabilityStudioScenarioDatasetProjector.ExactRef(
                        source.kind(), source.id(), source.revision(), source.fingerprint(), "", source.scope());
        CapabilityStudioScenarioDatasetSourceMap.CaseSource replaced = new CapabilityStudioScenarioDatasetSourceMap.CaseSource(
                first.scenarioId(), first.originalCategory(), first.compiledCaseType(), first.caseRef(),
                unauthorized, first.oracleRef(), first.contractRefs(), first.behaviors(),
                first.expectations(), first.assertionIds());
        List<CapabilityStudioScenarioDatasetSourceMap.CaseSource> cases =
                new ArrayList<>(adapterCompilation().sourceMap().cases());
        cases.set(0, replaced);
        CapabilityStudioScenarioDatasetSourceMap sourceMap = new CapabilityStudioScenarioDatasetSourceMap(
                adapterCompilation().sourceMap().datasetRef(), adapterCompilation().sourceMap().targetRef(),
                adapterCompilation().sourceMap().contractFingerprint(), cases);
        CapabilityStudioScenarioDatasetCompilation invalid = new CapabilityStudioScenarioDatasetCompilation(
                adapterCompilation().draftSet(), sourceMap, adapterCompilation().target(),
                adapterCompilation().contractFingerprint(), adapterCompilation().semanticFingerprint());

        assertThatThrownBy(() -> service.compile(null, operator, contract, runtimeTarget(), invalid))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_COMPILE.SOURCE_REF_NOT_EXACT");
    }

    @Test
    void rejectsCompiledProvenanceMetadataDriftBeforePublication() {
        ScenarioGovernedCompiler delegate = new ScenarioGovernedCompiler(
                new ScenarioValidationService(JSON), JSON);
        ScenarioGovernedCompiler driftingCompiler = new ScenarioGovernedCompiler(
                new ScenarioValidationService(JSON), JSON) {
            @Override
            public ScenarioGovernedCompilationPlan compile(
                    com.leanowtech.bloge.gateway.visual.draft.GraphDraft graph,
                    OperatorDefinition operator,
                    ContractDraft contract,
                    ScenarioDraftSet draftSet,
                    TestExecutionApiRequest.Target runtimeTarget,
                    ScenarioGovernedCompilationProvenance provenance) {
                ScenarioGovernedCompilationPlan original = delegate.compile(
                        graph, operator, contract, draftSet, runtimeTarget, provenance);
                var compiled = original.fixtures().getFirst();
                FixtureBundle source = compiled.request().fixtureBundle();
                Map<String, Object> metadata = new java.util.LinkedHashMap<>(source.metadata());
                metadata.put(ScenarioGovernedCompiler.GOVERNED_PROVENANCE_FINGERPRINT,
                        fingerprint('f'));
                FixtureBundle drifted = new FixtureBundle(
                        source.schemaVersion(), source.fixtureBundleId(), source.revision(),
                        source.targetFingerprint(), source.classification(), source.logicalClock(),
                        source.randomSeed(), source.rules(), source.assertions(), metadata);
                FixtureBundleRegistrationRequest request = new FixtureBundleRegistrationRequest(
                        "", runtimeTarget, drifted);
                List<ScenarioGovernedCompilationPlan.CompiledFixture> fixtures = new ArrayList<>();
                for (var candidate : original.fixtures()) {
                    fixtures.add(candidate.scenarioId().equals(compiled.scenarioId())
                            ? new ScenarioGovernedCompilationPlan.CompiledFixture(
                            compiled.scenarioId(), compiled.fingerprint(), request)
                            : candidate);
                }
                return new ScenarioGovernedCompilationPlan(
                        original.schemaVersion(), original.compiled(),
                        original.sourceScenarioDraftSetId(), original.sourceRevision(),
                        original.sourceTargetFingerprint(), original.contractFingerprint(),
                        original.runtimeTarget(),
                        fixtures,
                        original.suite(), original.diagnostics());
            }
        };
        CapabilityStudioGovernedCompilationService driftingService =
                new CapabilityStudioGovernedCompilationService(JSON, driftingCompiler);

        assertThatThrownBy(() -> driftingService.compile(
                null, operator, contract, runtimeTarget(), adapterCompilation()))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_COMPILE.PROVENANCE_FINGERPRINT_DRIFT");
    }

    private CapabilityStudioScenarioDatasetCompilation adapterCompilation() {
        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection exactDataset = retarget(dataset);
        return adapter.compile(
                exactDataset,
                new CapabilityStudioScenarioDatasetCompiler.ExactCompilationTarget(
                        contract.target(), contract.fingerprint(JSON)),
                new CapabilityStudioGoldenScenarioMaterialResolver(pack));
    }

    private Set<ScenarioGovernedCompilationProvenance.ExactRef> expectedRefs(
            CapabilityStudioScenarioDatasetSourceMap sourceMap) {
        Set<ScenarioGovernedCompilationProvenance.ExactRef> refs = new HashSet<>();
        add(refs, sourceMap.datasetRef());
        add(refs, sourceMap.targetRef());
        sourceMap.cases().forEach(source -> {
            add(refs, source.caseRef());
            add(refs, source.sourceRef());
            add(refs, source.oracleRef());
            source.contractRefs().forEach(ref -> add(refs, ref));
            source.behaviors().forEach(behavior -> {
                add(refs, behavior.behaviorRef());
                add(refs, behavior.dependencyRef());
            });
            source.expectations().forEach(expectation -> {
                add(refs, expectation.behaviorRef());
                add(refs, expectation.dependencyRef());
            });
        });
        return refs;
    }

    private void add(
            Set<ScenarioGovernedCompilationProvenance.ExactRef> refs,
            CapabilityStudioScenarioDatasetProjector.ExactRef ref) {
        var scope = ref.scope();
        refs.add(new ScenarioGovernedCompilationProvenance.ExactRef(
                ref.kind(), ref.id(), ref.revision(), ref.fingerprint(),
                new ScenarioGovernedCompilationProvenance.Scope(
                        scope.tenantId(), scope.organizationId(), scope.projectId(),
                        scope.environmentId(), scope.region()),
                ref.authority()));
    }

    private CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection retarget(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection source) {
        CapabilityStudioScenarioDatasetProjector.ExactRef oldTarget = source.targetRef();
        CapabilityStudioScenarioDatasetProjector.ExactRef target = new CapabilityStudioScenarioDatasetProjector.ExactRef(
                oldTarget.kind(), operator.operatorRef(), oldTarget.revision(), operator.fingerprint(),
                oldTarget.authority(), oldTarget.scope());
        String oldContractId = "contract-cancellation-fee-dispute-tool";
        List<CapabilityStudioScenarioDatasetProjector.ExactRef> contracts = source.contractRefs().stream()
                .map(ref -> ref.id().equals(oldContractId)
                        ? new CapabilityStudioScenarioDatasetProjector.ExactRef(
                                ref.kind(), ref.id(), ref.revision(), contract.fingerprint(JSON),
                                ref.authority(), ref.scope())
                        : ref)
                .toList();
        List<CapabilityStudioScenarioDatasetProjector.DataCase> cases = source.cases().stream()
                .map(dataCase -> new CapabilityStudioScenarioDatasetProjector.DataCase(
                        dataCase.caseRef(), dataCase.name(), dataCase.businessIntent(), dataCase.category(),
                        dataCase.lifecycle(), dataCase.qualityState(), dataCase.owner(), dataCase.sourceRef(),
                        dataCase.source(), dataCase.oracleRef(), dataCase.oracle(),
                        dataCase.applicableContractRefs().stream()
                                .map(ref -> ref.id().equals(oldContractId)
                                        ? new CapabilityStudioScenarioDatasetProjector.ExactRef(
                                                ref.kind(), ref.id(), ref.revision(), contract.fingerprint(JSON),
                                                ref.authority(), ref.scope())
                                        : ref)
                                .toList(), dataCase.behaviorProfiles()))
                .toList();
        return new CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection(
                source.schemaVersion(), source.datasetRef(), source.name(), source.description(),
                source.lifecycle(), source.classification(), source.owner(), target, contracts, cases,
                source.quality());
    }

    private CapabilityStudioScenarioDatasetMaterial.CaseMaterial material(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection ignored,
            CapabilityStudioScenarioDatasetProjector.DataCase dataCase) {
        List<CapabilityStudioScenarioDatasetMaterial.DependencyMaterial> dependencies = dataCase.behaviorProfiles()
                .stream()
                .filter(profile -> "RUNTIME_CONTROL".equals(profile.purpose()))
                .map(profile -> new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                        profile.behaviorRef(), profile.dependencyRef(),
                        new ScenarioDraftSet.DependencySelector(
                                "/root", "", profile.dependencyRef().id(), "", "",
                                List.of(), List.of(), "", Map.of()),
                        behavior(profile.behavior()), ScenarioDraftSet.Consumption.once(),
                        ScenarioDraftSet.SchemaCheck.strict()))
                .toList();
        ScenarioDraftSet.AssertionDraft assertion = new ScenarioDraftSet.AssertionDraft(
                "assert-" + dataCase.caseRef().id(), ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                "", "", "", "/result", ScenarioDraftSet.AssertionOperator.EXISTS, true, null);
        return new CapabilityStudioScenarioDatasetMaterial.CaseMaterial(
                dataCase.caseRef(), dataCase.sourceRef(), dataCase.oracleRef(),
                new ScenarioDraftSet.Given(Map.of("caseId", dataCase.caseRef().id()),
                        ScenarioDraftSet.ValueProvenance.IMPORTED), dependencies, List.of(assertion));
    }

    private ScenarioDraftSet.DependencyBehavior behavior(String kind) {
        return switch (kind) {
            case "RETURN", "RETURN_EMPTY", "RETURN_VERSIONED", "IDEMPOTENT" ->
                    ScenarioDraftSet.DependencyBehavior.returning(Map.of("result", "controlled"));
            case "ERROR" -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.ERROR, ScenarioDraftSet.BehaviorBoundary.NODE,
                    null, null, "", 422, Map.of(), "CASE_ERROR", "BUSINESS", "controlled error", null, "");
            case "TIMEOUT" -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.TIMEOUT, ScenarioDraftSet.BehaviorBoundary.NODE,
                    null, null, "", null, Map.of(), "CASE_TIMEOUT", "TIMEOUT", "controlled timeout",
                    Duration.ofSeconds(1), "");
            case "MUST_NOT_CALL", "MUST_NOT_CALL_WRITE" -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.MUST_NOT_CALL, ScenarioDraftSet.BehaviorBoundary.NODE,
                    null, null, "", null, Map.of(), "FORBIDDEN_CALL", "DENIED", "controlled denial", null, "");
            default -> throw new IllegalArgumentException("unsupported demo behavior");
        };
    }

    private TestExecutionApiRequest.Target runtimeTarget() {
        return new TestExecutionApiRequest.Target("OPERATOR", operator.operatorRef(), operator.fingerprint());
    }

    private static OperatorDefinition toolOperator() {
        String ref = "tool-cancellation-fee-dispute-handling";
        return new OperatorDefinition(
                "bloge.visualOperator.v1", ref, "1.0.0",
                new OperatorDefinition.Display("Cancellation dispute handling", "Controlled demo tool", List.of("demo")),
                OperatorDefinition.Source.builtIn("java"),
                new OperatorDefinition.Ports(
                        List.of(
                                new OperatorDefinition.Port("orderId", new SchemaEnvelope(
                                        SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of("type", "string")),
                                        true, "Demo order id"),
                                new OperatorDefinition.Port("caseId", new SchemaEnvelope(
                                        SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of("type", "string")),
                                        true, "Demo case id")),
                        List.of(new OperatorDefinition.Port("result", SchemaEnvelope.opaque(), true, "Demo result"))),
                SchemaEnvelope.object(Map.of(), List.of()),
                new OperatorDefinition.Capabilities("READ", "IDEMPOTENT", false, true, false),
                OperatorDefinition.Policy.unrestricted(),
                new OperatorDefinition.Lowering("native", ref, Map.of()), List.of());
    }

    private static ContractDraft toolContract() {
        ContractDraft projected = new ContractDraftProjectionService().project(toolOperator());
        return new ContractDraft(
                projected.schemaVersion(),
                new ContractDraft.Target(
                        projected.target().kind(), projected.target().id(), 1,
                        projected.target().fingerprint()),
                projected.inputSchema(), projected.outputSchema(), projected.errorContract(),
                projected.executionSemantics(), projected.invariants(), projected.compatibilityPolicy(),
                projected.fieldMetadata(), projected.source(), projected.confidence());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
