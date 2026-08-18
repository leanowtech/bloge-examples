package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioDraftSet;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityStudioScenarioDatasetCompilerTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final CapabilityStudioGoldenDemoPack pack =
            new CapabilityStudioGoldenDemoPackLoader().load(JSON);
    private final CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection dataset =
            new CapabilityStudioScenarioDatasetProjector(pack, JSON).project();
    private final CapabilityStudioScenarioDatasetCompiler compiler =
            new CapabilityStudioScenarioDatasetCompiler(JSON);

    @Test
    void compilesAllNineCasesToTheExistingScenarioDraftSet() throws Exception {
        CapabilityStudioScenarioDatasetCompilation result = compiler.compile(
                dataset, target(), resolver());

        assertThat(result.draftSet().schemaVersion()).isEqualTo(ScenarioDraftSet.SCHEMA_VERSION);
        assertThat(result.draftSet().scenarios()).hasSize(9);
        assertThat(result.draftSet().target()).isEqualTo(target().target());
        assertThat(result.draftSet().contractFingerprint()).isEqualTo(target().contractFingerprint());
        assertThat(result.draftSet().scenarios())
                .extracting(ScenarioDraftSet.ScenarioDraft::caseType)
                .containsExactly(
                        ScenarioDraftSet.CaseType.NEGATIVE,
                        ScenarioDraftSet.CaseType.BOUNDARY,
                        ScenarioDraftSet.CaseType.REGRESSION,
                        ScenarioDraftSet.CaseType.BOUNDARY,
                        ScenarioDraftSet.CaseType.REGRESSION,
                        ScenarioDraftSet.CaseType.REGRESSION,
                        ScenarioDraftSet.CaseType.REGRESSION,
                        ScenarioDraftSet.CaseType.NEGATIVE,
                        ScenarioDraftSet.CaseType.GOLDEN);
        assertThat(result.draftSet().scenarios())
                .allSatisfy(scenario -> {
                    assertThat(scenario.given().input()).isInstanceOf(Map.class);
                    assertThat(scenario.dependencies()).hasSize(4);
                    assertThat(scenario.then().assertions()).hasSize(1);
                });
        assertThat(result.draftSet().scenarios().stream()
                .flatMap(scenario -> scenario.dependencies().stream())
                .map(dependency -> dependency.behavior().kind()))
                .contains(
                        ScenarioDraftSet.BehaviorKind.RETURN,
                        ScenarioDraftSet.BehaviorKind.TIMEOUT);
        assertThat(result.sourceMap().cases()).hasSize(9);
        assertThat(result.sourceMap().cases()).allSatisfy(source -> {
            assertThat(source.originalCategory()).isNotBlank();
            assertThat(source.caseRef().fingerprint()).startsWith("sha256:");
            assertThat(source.sourceRef().fingerprint()).startsWith("sha256:");
            assertThat(source.oracleRef().fingerprint()).startsWith("sha256:");
            assertThat(source.behaviors()).hasSize(4);
        });
        assertThat(result.sourceMap().cases().stream()
                .filter(source -> !source.expectations().isEmpty()).toList())
                .extracting(CapabilityStudioScenarioDatasetSourceMap.CaseSource::scenarioId)
                .containsExactly(
                        "case-duplicate-cancellation",
                        "case-forbidden-write-effect");
        assertThat(result.sourceMap().cases().stream()
                .flatMap(source -> source.expectations().stream())
                .map(CapabilityStudioScenarioDatasetSourceMap.ExpectationSource::behavior))
                .containsExactly("RETURN", "MUST_NOT_CALL");
        assertThat(JSON.writeValueAsString(result.sourceMap()))
                .doesNotContain("given", "expected", "payload", "fixture", "mock");
    }

    @Test
    void preservesOriginalCategoryAndMakesTheSourceMapTraceable() {
        CapabilityStudioScenarioDatasetCompilation result = compiler.compile(
                dataset, target(), resolver());

        for (CapabilityStudioScenarioDatasetSourceMap.CaseSource source : result.sourceMap().cases()) {
            ScenarioDraftSet.ScenarioDraft scenario = result.draftSet().scenarios().stream()
                    .filter(candidate -> candidate.scenarioId().equals(source.scenarioId()))
                    .findFirst().orElseThrow();
            assertThat(scenario.caseType()).isEqualTo(source.compiledCaseType());
            assertThat(source.caseRef().id()).isEqualTo(scenario.scenarioId());
            assertThat(source.assertionIds()).containsExactlyElementsOf(
                    scenario.then().assertions().stream()
                            .map(ScenarioDraftSet.AssertionDraft::assertionId).toList());
        }
        assertThat(result.draftSet().metadata().provenance().keySet())
                .containsExactlyInAnyOrder("datasetRef", "targetRef", "contractFingerprint", "sourceMap");
    }

    @Test
    void compilesAnExactErrorProfileWithoutIntroducingAnotherRuntime() {
        CapabilityStudioScenarioDatasetCompilation result = compiler.compile(
                withFirstBehavior("ERROR"), target(), resolver());

        assertThat(result.draftSet().scenarios().getFirst().dependencies().getFirst().behavior().kind())
                .isEqualTo(ScenarioDraftSet.BehaviorKind.ERROR);
    }

    @Test
    void compilesCanonicalReturnsOnlyAsDescriptorBackedTransportResponses() {
        CapabilityStudioScenarioDatasetCompilation result = compiler.compile(
                dataset, target(), new CapabilityStudioGoldenScenarioMaterialResolver(pack));

        assertThat(result.draftSet().scenarios().stream()
                .flatMap(scenario -> scenario.dependencies().stream())
                .filter(dependency -> dependency.behavior().kind()
                        == ScenarioDraftSet.BehaviorKind.RETURN)
                .toList())
                .isNotEmpty()
                .allSatisfy(dependency -> {
                    assertThat(dependency.selector().operatorRef()).isEqualTo("httpResource");
                    assertThat(dependency.selector().resourceRef()).startsWith("api-");
                    assertThat(dependency.behavior().boundary())
                            .isEqualTo(ScenarioDraftSet.BehaviorBoundary.TRANSPORT);
                    assertThat(dependency.behavior().output()).isNull();
                    assertThat(dependency.behavior().statusCode()).isEqualTo(200);
                    assertThat(dependency.behavior().rawBody()).isNotBlank();
                });
    }

    @Test
    void compilingThreeTimesProducesByteForByteIdenticalOutput() throws Exception {
        CapabilityStudioScenarioDatasetCompilation first = compiler.compile(
                dataset, target(), resolver());
        CapabilityStudioScenarioDatasetCompilation second = compiler.compile(
                dataset, target(), resolver());
        CapabilityStudioScenarioDatasetCompilation third = compiler.compile(
                dataset, target(), resolver());

        assertThat(first.semanticFingerprint()).isEqualTo(second.semanticFingerprint())
                .isEqualTo(third.semanticFingerprint());
        assertThat(JSON.writeValueAsString(first)).isEqualTo(JSON.writeValueAsString(second))
                .isEqualTo(JSON.writeValueAsString(third));
    }

    @Test
    void failsClosedWhenMaterialIsMissing() {
        assertCode(resolverReturning(null), "MATERIAL_MISSING");
    }

    @Test
    void failsClosedWhenOracleHasNoExecutableAssertion() {
        assertCode((ignoredDataset, dataCase) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original =
                    resolver().resolve(dataset, dataCase);
            return new CapabilityStudioScenarioDatasetMaterial.CaseMaterial(
                    original.caseRef(), original.sourceRef(), original.oracleRef(), original.given(),
                    original.dependencies(), List.of());
        }, "ASSERTION_MISSING");
    }

    @Test
    void failsClosedWhenAnExactRefHasNoAuthority() {
        CapabilityStudioScenarioDatasetProjector.DataCase original = dataset.cases().getFirst();
        CapabilityStudioScenarioDatasetProjector.ExactRef source = original.sourceRef();
        CapabilityStudioScenarioDatasetProjector.DataCase invalid =
                new CapabilityStudioScenarioDatasetProjector.DataCase(
                        original.caseRef(), original.name(), original.businessIntent(), original.category(),
                        original.lifecycle(), original.qualityState(), original.owner(),
                        new CapabilityStudioScenarioDatasetProjector.ExactRef(
                                source.kind(), source.id(), source.revision(), source.fingerprint(), "",
                                source.scope()),
                        original.source(), original.oracleRef(), original.oracle(),
                        original.applicableContractRefs(), original.behaviorProfiles());
        List<CapabilityStudioScenarioDatasetProjector.DataCase> cases =
                new ArrayList<>(dataset.cases());
        cases.set(0, invalid);

        assertThatThrownBy(() -> compiler.compile(projectionWithCases(cases), target(), resolver()))
                .isInstanceOf(CapabilityStudioScenarioDatasetCompilationException.class)
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.DATASET_COMPILE.REF_INVALID");
    }

    @Test
    void failsClosedWhenMaterialCrossesScope() {
        assertCode((ignoredDataset, material) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original = resolver().resolve(dataset, material);
            CapabilityStudioScenarioDatasetProjector.Scope other = new CapabilityStudioScenarioDatasetProjector.Scope(
                    "other-tenant", "support", "customer-service", "rehearsal", "sg");
            CapabilityStudioScenarioDatasetProjector.ExactRef source =
                    new CapabilityStudioScenarioDatasetProjector.ExactRef(
                    original.sourceRef().kind(), original.sourceRef().id(), original.sourceRef().revision(),
                    original.sourceRef().fingerprint(), original.sourceRef().authority(), other);
            return new CapabilityStudioScenarioDatasetMaterial.CaseMaterial(
                    original.caseRef(), source, original.oracleRef(), original.given(),
                    original.dependencies(), original.assertions());
        }, "SCOPE_MISMATCH");
    }

    @Test
    void failsClosedWhenProfileBehaviorDoesNotMatchMaterial() {
        assertCode((ignoredDataset, dataCase) -> {
            CapabilityStudioScenarioDatasetProjector.BehaviorProfile profile =
                    dataCase.behaviorProfiles().getFirst();
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original = resolver().resolve(dataset, dataCase);
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency =
                    original.dependencies().getFirst();
            ScenarioDraftSet.DependencyBehavior mismatch = new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.ERROR, ScenarioDraftSet.BehaviorBoundary.NODE,
                    null, null, "", 500, Map.of(), "MISMATCH", "ERROR", "", null, "");
            return new CapabilityStudioScenarioDatasetMaterial.CaseMaterial(
                    original.caseRef(), original.sourceRef(), original.oracleRef(), original.given(),
                    withFirstDependency(original, new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                            dependency.behaviorRef(), dependency.dependencyRef(), dependency.selector(),
                            mismatch, dependency.consumption(), dependency.schemaCheck())),
                    original.assertions());
        }, "BEHAVIOR_MISMATCH");
    }

    @Test
    void failsClosedForRealFallbackAndUnresolvedSelectors() {
        assertCode((ignoredDataset, dataCase) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original = resolver().resolve(dataset, dataCase);
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency = original.dependencies().getFirst();
            return withDependency(original, new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                    dependency.behaviorRef(), dependency.dependencyRef(), dependency.selector(),
                    ScenarioDraftSet.DependencyBehavior.real(), dependency.consumption(), dependency.schemaCheck()));
        }, "REAL_FORBIDDEN");

        assertCode((ignoredDataset, dataCase) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original = resolver().resolve(dataset, dataCase);
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency = original.dependencies().getFirst();
            return withDependency(original, new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                    dependency.behaviorRef(), dependency.dependencyRef(), dependency.selector(),
                    dependency.behavior(), new ScenarioDraftSet.Consumption(true, 1, 1,
                            "FALLBACK_TO_REAL", "FAIL"), dependency.schemaCheck()));
        }, "REAL_FALLBACK_FORBIDDEN");

        assertCode((ignoredDataset, material) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original = resolver().resolve(dataset, material);
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency = original.dependencies().getFirst();
            return withDependency(original, new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                    dependency.behaviorRef(), dependency.dependencyRef(),
                    ScenarioDraftSet.DependencySelector.any(), dependency.behavior(),
                    dependency.consumption(), dependency.schemaCheck()));
        }, "SELECTOR_UNRESOLVED");
    }

    @Test
    void failsClosedForDuplicateAssertionsAndDoesNotLeakPayload() {
        String secret = "customer-payload-secret";
        CapabilityStudioScenarioDatasetMaterialResolver resolver = (ignoredDataset, dataCase) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original = resolver().resolve(dataset, dataCase);
            ScenarioDraftSet.AssertionDraft assertion = new ScenarioDraftSet.AssertionDraft(
                    "duplicate", ScenarioDraftSet.AssertionScope.OUTPUT_PATH, "", "", "",
                    "/result", ScenarioDraftSet.AssertionOperator.EQUALS, secret, null);
            return new CapabilityStudioScenarioDatasetMaterial.CaseMaterial(
                    original.caseRef(), original.sourceRef(), original.oracleRef(), original.given(),
                    original.dependencies(), List.of(assertion, assertion));
        };

        assertThatThrownBy(() -> compiler.compile(dataset, target(), resolver))
                .isInstanceOf(CapabilityStudioScenarioDatasetCompilationException.class)
                .satisfies(error -> {
                    CapabilityStudioScenarioDatasetCompilationException failure =
                            (CapabilityStudioScenarioDatasetCompilationException) error;
                    assertThat(failure.code()).isEqualTo(
                            "RG.CAPABILITY_STUDIO.DATASET_COMPILE.DUPLICATE_ASSERTION");
                    assertThat(failure.getMessage()).doesNotContain(secret, "payload");
                });
    }

    @Test
    void failsClosedForDuplicateCasesAndBehaviorRefs() {
        List<CapabilityStudioScenarioDatasetProjector.DataCase> duplicateCases =
                new ArrayList<>(dataset.cases());
        duplicateCases.add(dataset.cases().getFirst());
        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection duplicateCaseDataset =
                projectionWithCases(duplicateCases);
        assertThatThrownBy(() -> compiler.compile(duplicateCaseDataset, target(), resolver()))
                .isInstanceOf(CapabilityStudioScenarioDatasetCompilationException.class)
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.DATASET_COMPILE.DUPLICATE_CASE");

        CapabilityStudioScenarioDatasetProjector.BehaviorProfile first =
                dataset.cases().getFirst().behaviorProfiles().getFirst();
        assertThatThrownBy(() -> compiler.compile(
                projectionWithFirstProfiles(List.of(first, first)), target(), resolver()))
                .isInstanceOf(CapabilityStudioScenarioDatasetCompilationException.class)
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.DATASET_COMPILE.DUPLICATE_BEHAVIOR");
    }

    @Test
    void allowsTwoReturnBehaviorsForOneDependencyInStableSequenceOrder() throws Exception {
        CapabilityStudioScenarioDatasetCompilation first = compiler.compile(
                sequenceDataset(), target(), sequenceResolver(false));
        CapabilityStudioScenarioDatasetCompilation second = compiler.compile(
                sequenceDataset(), target(), sequenceResolver(false));
        List<ScenarioDraftSet.DependencyBehaviorDraft> dependencies =
                first.draftSet().scenarios().getFirst().dependencies();

        assertThat(dependencies).hasSize(2);
        assertThat(dependencies).extracting(ScenarioDraftSet.DependencyBehaviorDraft::dependencyId)
                .containsExactly("dataset-rule-" + sequenceDataset().cases().getFirst()
                        .behaviorProfiles().getFirst().behaviorRef().id() + "-1",
                        "dataset-rule-" + sequenceDataset().cases().getFirst()
                                .behaviorProfiles().get(1).behaviorRef().id() + "-2");
        assertThat(dependencies).extracting(value -> value.behavior().output())
                .containsExactly(Map.of("sequence", 1), Map.of("sequence", 2));
        assertThat(dependencies).extracting(value -> value.selector().occurrences())
                .containsExactly(List.of(1), List.of(2));
        assertThat(first.sourceMap().cases().getFirst().behaviors())
                .extracting(CapabilityStudioScenarioDatasetSourceMap.BehaviorSource::ruleId)
                .containsExactlyElementsOf(dependencies.stream()
                        .map(ScenarioDraftSet.DependencyBehaviorDraft::dependencyId).toList());
        assertThat(JSON.writeValueAsString(first)).isEqualTo(JSON.writeValueAsString(second));
    }

    @Test
    void sequenceSelectorsDoNotConstrainAnIndependentDependency() {
        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection mixedDataset =
                sequenceDatasetWithIndependentDependency();
        CapabilityStudioScenarioDatasetCompilation result = compiler.compile(
                mixedDataset, target(), mixedSequenceResolver(mixedDataset));

        assertThat(result.draftSet().scenarios().getFirst().dependencies())
                .hasSize(3)
                .satisfies(dependencies -> {
                    assertThat(dependencies.get(0).selector().occurrences()).containsExactly(1);
                    assertThat(dependencies.get(1).selector().occurrences()).containsExactly(2);
                    assertThat(dependencies.get(2).selector().occurrences()).isEmpty();
                });
    }

    @Test
    void rejectsSameSelectorAmbiguityForASequence() {
        assertThatThrownBy(() -> compiler.compile(
                sequenceDataset(), target(), sequenceResolver(true)))
                .isInstanceOf(CapabilityStudioScenarioDatasetCompilationException.class)
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.DATASET_COMPILE.AMBIGUOUS_SELECTOR");
    }

    @Test
    void rejectsStage0UnsupportedReplayFunctionMalformedTransportAndObserveSemantics() {
        assertCode((ignoredDataset, dataCase) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original = resolver().resolve(dataset, dataCase);
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency = original.dependencies().getFirst();
            return withDependency(original, new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                    dependency.behaviorRef(), dependency.dependencyRef(), dependency.selector(),
                    new ScenarioDraftSet.DependencyBehavior(
                            ScenarioDraftSet.BehaviorKind.REPLAY, ScenarioDraftSet.BehaviorBoundary.NODE,
                            null, null, "", null, Map.of(), "", "", "", null, "replay-ref"),
                    dependency.consumption(), dependency.schemaCheck()));
        }, "REPLAY_UNSUPPORTED");

        assertCode((ignoredDataset, dataCase) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original = resolver().resolve(dataset, dataCase);
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency = original.dependencies().getFirst();
            ScenarioDraftSet.DependencySelector selector = new ScenarioDraftSet.DependencySelector(
                    "/root", "", "", "", "builtin.normalize", List.of(), List.of(), "", Map.of());
            return withDependency(original, new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                    dependency.behaviorRef(), dependency.dependencyRef(), selector, dependency.behavior(),
                    dependency.consumption(), dependency.schemaCheck()));
        }, "FUNCTION_SELECTOR_UNSUPPORTED");

        assertCode((ignoredDataset, dataCase) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original = resolver().resolve(dataset, dataCase);
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency = original.dependencies().getFirst();
            ScenarioDraftSet.DependencyBehavior behavior = new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.RETURN, ScenarioDraftSet.BehaviorBoundary.TRANSPORT,
                    Map.of("transport", true), null, "", 200, Map.of(), "", "", "", null, "");
            return withDependency(original, new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                    dependency.behaviorRef(), dependency.dependencyRef(), dependency.selector(), behavior,
                    dependency.consumption(), dependency.schemaCheck()));
        }, "TRANSPORT_BOUNDARY_UNSUPPORTED");

        assertCode((ignoredDataset, dataCase) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original = resolver().resolve(dataset, dataCase);
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency = original.dependencies().getFirst();
            ScenarioDraftSet.DependencyBehavior behavior = new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.OBSERVE, ScenarioDraftSet.BehaviorBoundary.NODE,
                    null, null, "", null, Map.of(), "", "", "", null, "");
            return withDependency(original, new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                    dependency.behaviorRef(), dependency.dependencyRef(), dependency.selector(), behavior,
                    dependency.consumption(), dependency.schemaCheck()));
        }, "OBSERVE_UNSUPPORTED");
    }

    @Test
    void failsClosedWhenTargetOrContractIsNotExact() {
        ContractDraft.Target wrongTarget = new ContractDraft.Target(
                ContractDraft.TargetKind.OPERATOR, "wrong", 1, dataset.targetRef().fingerprint());
        assertThatThrownBy(() -> compiler.compile(dataset,
                new CapabilityStudioScenarioDatasetCompiler.ExactCompilationTarget(
                        wrongTarget, contractFingerprint()), resolver()))
                .isInstanceOf(CapabilityStudioScenarioDatasetCompilationException.class)
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.DATASET_COMPILE.TARGET_MISMATCH");
    }

    private void assertCode(
            CapabilityStudioScenarioDatasetMaterialResolver resolver,
            String expectedSuffix) {
        assertThatThrownBy(() -> compiler.compile(dataset, target(), resolver))
                .isInstanceOf(CapabilityStudioScenarioDatasetCompilationException.class)
                .satisfies(error -> assertThat(((CapabilityStudioScenarioDatasetCompilationException) error).code())
                        .isEqualTo("RG.CAPABILITY_STUDIO.DATASET_COMPILE." + expectedSuffix));
    }

    private CapabilityStudioScenarioDatasetMaterialResolver resolverReturning(
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial material) {
        return (ignoredDataset, ignoredCase) -> material;
    }

    private CapabilityStudioScenarioDatasetMaterial.CaseMaterial withDependency(
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original,
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency) {
        return new CapabilityStudioScenarioDatasetMaterial.CaseMaterial(
                original.caseRef(), original.sourceRef(), original.oracleRef(), original.given(),
                withFirstDependency(original, dependency), original.assertions());
    }

    private List<CapabilityStudioScenarioDatasetMaterial.DependencyMaterial> withFirstDependency(
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial original,
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial dependency) {
        List<CapabilityStudioScenarioDatasetMaterial.DependencyMaterial> dependencies =
                new ArrayList<>(original.dependencies());
        dependencies.set(0, dependency);
        return dependencies;
    }

    private CapabilityStudioScenarioDatasetMaterialResolver resolver() {
        return (ignoredDataset, dataCase) -> {
            List<CapabilityStudioScenarioDatasetMaterial.DependencyMaterial> dependencies =
                    dataCase.behaviorProfiles().stream()
                            .filter(profile -> "RUNTIME_CONTROL".equals(profile.purpose()))
                            .map(profile -> new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                                    profile.behaviorRef(), profile.dependencyRef(),
                                    ScenarioDraftSet.DependencySelector.node(
                                            "node-" + profile.dependencyRef().id()),
                                    testBehavior(profile.behavior(), dataCase.caseRef().id()),
                                    ScenarioDraftSet.Consumption.once(),
                                    ScenarioDraftSet.SchemaCheck.strict()))
                            .toList();
            ScenarioDraftSet.AssertionDraft assertion = new ScenarioDraftSet.AssertionDraft(
                    "assert-" + dataCase.caseRef().id(), ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                    "", "", "", "/result", ScenarioDraftSet.AssertionOperator.EXISTS, true, null);
            return new CapabilityStudioScenarioDatasetMaterial.CaseMaterial(
                    dataCase.caseRef(), dataCase.sourceRef(), dataCase.oracleRef(),
                    new ScenarioDraftSet.Given(Map.of("caseId", dataCase.caseRef().id()),
                            ScenarioDraftSet.ValueProvenance.IMPORTED),
                    dependencies,
                    List.of(assertion));
        };
    }

    private static ScenarioDraftSet.DependencyBehavior testBehavior(
            String behavior,
            String caseId) {
        return switch (behavior) {
            case "RETURN" -> ScenarioDraftSet.DependencyBehavior.returning(
                    Map.of("result", caseId));
            case "ERROR" -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.ERROR, ScenarioDraftSet.BehaviorBoundary.NODE,
                    null, null, "", 422, Map.of(), "CASE_ERROR", "BUSINESS", "", null, "");
            case "TIMEOUT" -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.TIMEOUT, ScenarioDraftSet.BehaviorBoundary.NODE,
                    null, null, "", null, Map.of(), "CASE_TIMEOUT", "TIMEOUT",
                    "Deterministic test timeout", Duration.ofSeconds(1), "");
            case "MUST_NOT_CALL" -> new ScenarioDraftSet.DependencyBehavior(
                    ScenarioDraftSet.BehaviorKind.MUST_NOT_CALL,
                    ScenarioDraftSet.BehaviorBoundary.NODE,
                    null, null, "", null, Map.of(), "FORBIDDEN_CALL", "DENIED", "", null, "");
            default -> throw new IllegalArgumentException("unsupported");
        };
    }

    private CapabilityStudioScenarioDatasetCompiler.ExactCompilationTarget target() {
        CapabilityStudioScenarioDatasetProjector.ExactRef targetRef = dataset.targetRef();
        return new CapabilityStudioScenarioDatasetCompiler.ExactCompilationTarget(
                new ContractDraft.Target(ContractDraft.TargetKind.OPERATOR, targetRef.id(),
                        targetRef.revision(), targetRef.fingerprint()),
                contractFingerprint());
    }

    private String contractFingerprint() {
        return dataset.contractRefs().stream()
                .filter(ref -> ref.id().equals("contract-cancellation-fee-dispute-tool"))
                .findFirst().orElseThrow().fingerprint();
    }

    private CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection withFirstBehavior(
            String behavior) {
        List<CapabilityStudioScenarioDatasetProjector.DataCase> cases = new ArrayList<>(dataset.cases());
        CapabilityStudioScenarioDatasetProjector.DataCase original = cases.getFirst();
        CapabilityStudioScenarioDatasetProjector.BehaviorProfile profile = original.behaviorProfiles().getFirst();
        cases.set(0, new CapabilityStudioScenarioDatasetProjector.DataCase(
                original.caseRef(), original.name(), original.businessIntent(), original.category(),
                original.lifecycle(), original.qualityState(), original.owner(), original.sourceRef(),
                original.source(), original.oracleRef(), original.oracle(), original.applicableContractRefs(),
                List.of(new CapabilityStudioScenarioDatasetProjector.BehaviorProfile(
                        profile.behaviorRef(), profile.dependencyRef(), profile.purpose(), behavior,
                        profile.summary()))));
        return projectionWithCases(cases);
    }

    private CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection projectionWithFirstProfiles(
            List<CapabilityStudioScenarioDatasetProjector.BehaviorProfile> profiles) {
        List<CapabilityStudioScenarioDatasetProjector.DataCase> cases = new ArrayList<>(dataset.cases());
        CapabilityStudioScenarioDatasetProjector.DataCase original = cases.getFirst();
        cases.set(0, new CapabilityStudioScenarioDatasetProjector.DataCase(
                original.caseRef(), original.name(), original.businessIntent(), original.category(),
                original.lifecycle(), original.qualityState(), original.owner(), original.sourceRef(),
                original.source(), original.oracleRef(), original.oracle(), original.applicableContractRefs(),
                profiles));
        return projectionWithCases(cases);
    }

    private CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection sequenceDataset() {
        CapabilityStudioScenarioDatasetProjector.BehaviorProfile first =
                dataset.cases().getFirst().behaviorProfiles().getFirst();
        CapabilityStudioScenarioDatasetProjector.BehaviorProfile second =
                new CapabilityStudioScenarioDatasetProjector.BehaviorProfile(
                        new CapabilityStudioScenarioDatasetProjector.ExactRef(
                                "BEHAVIOR_PROFILE", "sequence-return-profile", 1,
                                "sha256:0000000000000000000000000000000000000000000000000000000000000000",
                                first.behaviorRef().authority(), first.behaviorRef().scope()),
                        first.dependencyRef(), first.purpose(), "RETURN",
                        "Second deterministic return");
        return projectionWithFirstProfiles(List.of(first, second));
    }

    private CapabilityStudioScenarioDatasetMaterialResolver sequenceResolver(boolean ambiguous) {
        return (ignoredDataset, dataCase) -> {
            if (!dataCase.caseRef().id().equals(dataset.cases().getFirst().caseRef().id())) {
                return resolver().resolve(dataset, dataCase);
            }
            List<CapabilityStudioScenarioDatasetMaterial.DependencyMaterial> dependencies = new ArrayList<>();
            for (int index = 0; index < dataCase.behaviorProfiles().size(); index++) {
                CapabilityStudioScenarioDatasetProjector.BehaviorProfile profile =
                        dataCase.behaviorProfiles().get(index);
                ScenarioDraftSet.DependencySelector selector = new ScenarioDraftSet.DependencySelector(
                        "/root", "sequence-node", "", "", "", List.of(),
                        ambiguous ? List.of() : List.of(),
                        ambiguous ? "" : "sequence-" + (index + 1),
                        Map.of());
                if (!ambiguous) {
                    selector = new ScenarioDraftSet.DependencySelector(
                            selector.graphPath(), selector.nodeId(), selector.operatorRef(),
                            selector.resourceRef(), selector.functionRef(), selector.attempts(),
                            List.of(index + 1), selector.correlationKey(), selector.pathEquals());
                }
                dependencies.add(new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                        profile.behaviorRef(), profile.dependencyRef(), selector,
                        ScenarioDraftSet.DependencyBehavior.returning(Map.of("sequence", index + 1)),
                        ScenarioDraftSet.Consumption.once(), ScenarioDraftSet.SchemaCheck.strict()));
            }
            return new CapabilityStudioScenarioDatasetMaterial.CaseMaterial(
                    dataCase.caseRef(), dataCase.sourceRef(), dataCase.oracleRef(),
                    new ScenarioDraftSet.Given(Map.of("caseId", dataCase.caseRef().id()),
                            ScenarioDraftSet.ValueProvenance.IMPORTED),
                    dependencies,
                    List.of(new ScenarioDraftSet.AssertionDraft(
                            "sequence-assertion", ScenarioDraftSet.AssertionScope.OUTPUT_PATH,
                            "", "", "", "/result", ScenarioDraftSet.AssertionOperator.EXISTS,
                            true, null)));
        };
    }

    private CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection
            sequenceDatasetWithIndependentDependency() {
        CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection sequence = sequenceDataset();
        List<CapabilityStudioScenarioDatasetProjector.BehaviorProfile> profiles = new ArrayList<>(
                sequence.cases().getFirst().behaviorProfiles());
        String sequencedDependency = profiles.getFirst().dependencyRef().id();
        profiles.add(dataset.cases().get(1).behaviorProfiles().stream()
                .filter(profile -> !sequencedDependency.equals(profile.dependencyRef().id()))
                .findFirst()
                .orElseThrow());
        return projectionWithFirstProfiles(profiles);
    }

    private CapabilityStudioScenarioDatasetMaterialResolver mixedSequenceResolver(
            CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection mixedDataset) {
        return (ignoredDataset, dataCase) -> {
            CapabilityStudioScenarioDatasetMaterial.CaseMaterial material =
                    sequenceResolver(false).resolve(mixedDataset, dataCase);
            if (!dataCase.caseRef().id().equals(mixedDataset.cases().getFirst().caseRef().id())) {
                return material;
            }
            List<CapabilityStudioScenarioDatasetMaterial.DependencyMaterial> dependencies =
                    new ArrayList<>(material.dependencies());
            CapabilityStudioScenarioDatasetMaterial.DependencyMaterial independent = dependencies.get(2);
            dependencies.set(2, new CapabilityStudioScenarioDatasetMaterial.DependencyMaterial(
                    independent.behaviorRef(), independent.dependencyRef(),
                    ScenarioDraftSet.DependencySelector.node("independent-node"), independent.behavior(),
                    independent.consumption(), independent.schemaCheck()));
            return new CapabilityStudioScenarioDatasetMaterial.CaseMaterial(
                    material.caseRef(), material.sourceRef(), material.oracleRef(), material.given(),
                    dependencies, material.assertions());
        };
    }

    private CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection projectionWithCases(
            List<CapabilityStudioScenarioDatasetProjector.DataCase> cases) {
        return new CapabilityStudioScenarioDatasetProjector.ScenarioDatasetProjection(
                dataset.schemaVersion(), dataset.datasetRef(), dataset.name(), dataset.description(),
                dataset.lifecycle(), dataset.classification(), dataset.owner(), dataset.targetRef(),
                dataset.contractRefs(), cases, dataset.quality());
    }
}
