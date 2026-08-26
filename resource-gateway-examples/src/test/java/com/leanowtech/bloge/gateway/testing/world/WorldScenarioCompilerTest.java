package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventoryBuilder;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.confirmed;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.descriptor;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.objectSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldScenarioCompilerTest {
    private static final String DSL = "graph customerWorld { transform result { value = ctx.id } }";
    private static final String TARGET_FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void logicalContractTagsRoundTripCanonicalIds() {
        for (String contractId : List.of("customer@north", "customer,orders",
                "customer  orders", "客户@订单, 细节")) {
            String tag = WorldScenarioCompiler.logicalContractTag(contractId, TARGET_FINGERPRINT);

            assertThat(WorldScenarioCompiler.decodeLogicalContractTag(tag).contractId())
                    .isEqualTo(contractId);
            assertThat(WorldScenarioCompiler.decodeLogicalContractTag(tag).contractFingerprint())
                    .isEqualTo(TARGET_FINGERPRINT);
            assertThat(tag).doesNotContain(contractId);
        }
    }

    @Test
    void compilesTwoNodesToOneLogicalRuleAndKeepsDelegateUnbound() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("first", tag(contract), "second", tag(contract)));
        Scenario scenario = scenario(graph, world, List.of(Scenario.ContractDependency.of(contract)),
                List.of(new Scenario.Expectation("NODE_OUTPUT", "first", "/status", "EQUALS", "ok", null)));

        WorldScenarioCompilation compilation = compile(scenario, world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-a", "v1",
                        world.slices().getFirst().fingerprint())));

        assertThat(compilation.bundle().rules()).hasSize(1);
        FixtureRule rule = compilation.bundle().rules().getFirst();
        assertThat(rule.selector().nodeId()).isEmpty();
        assertThat(rule.selector().graphPath()).isEmpty();
        assertThat(rule.selector().operatorRef()).isEmpty();
        assertThat(rule.selector().tags()).containsExactly(tag(contract));
        assertThat(rule.selector().invocationKind()).isEqualTo(
                com.leanowtech.bloge.gateway.testing.domain.InvocationSite.InvocationKind.PRIMARY);
        assertThat(rule.behavior().kind()).isEqualTo(FixtureRule.BehaviorKind.DENY);
        assertThat(rule.behavior().errorCode()).isEqualTo(WorldScenarioCompiler.WORLD_DELEGATE_UNBOUND);
        assertThat(rule.behavior().errorMessage()).isEqualTo(WorldScenarioCompiler.WORLD_DELEGATE_UNBOUND);
        assertThat(rule.consumption()).isEqualTo(new FixtureRule.Consumption(true, 1, 0,
                FixtureRule.ExhaustedAction.FAIL, FixtureRule.UnmatchedAction.FAIL));
        assertThat(rule.schemaCheck().mode()).isEqualTo(FixtureRule.SchemaCheckMode.STRICT);
        assertThat(compilation.bindings()).extracting(WorldDelegateBinding::fragment)
                .containsExactly(world.slices().getFirst().behavior());
    }

    @Test
    void oneContractAcrossPrimaryAndResourceSitesUsesPrimarySelectorSemantics() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithPrimaryAndResourceTag(tag(contract));
        Scenario scenario = scenario(graph, world, List.of(Scenario.ContractDependency.of(contract)),
                List.of());

        WorldScenarioCompilation compilation = compile(scenario, world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-a", "v1",
                        world.slices().getFirst().fingerprint())));

        FixtureRule rule = compilation.bundle().rules().getFirst();
        assertThat(rule.selector().invocationKind()).isEqualTo(
                com.leanowtech.bloge.gateway.testing.domain.InvocationSite.InvocationKind.PRIMARY);
        assertThat(rule.selector().nodeId()).isEmpty();
        String logicalSource = WorldScenarioSourceMap.coordinate("logical-contract",
                contract.contractId() + "@" + contract.contractFingerprint());
        assertThat(compilation.sourceMap().sourceToOutputs(logicalSource)).containsExactly(
                WorldScenarioSourceMap.coordinate("invocation-site", "/root/primary#PRIMARY"),
                WorldScenarioSourceMap.coordinate("invocation-site", "/root/resource#RESOURCE"));
    }

    @Test
    void selectsTheExplicitSliceAndMapsAssertionsWithoutPayloadMetadata() {
        LogicalResourceContract contract = contract("logical.customer");
        WorldSlice first = slice("provider-a", "v1", contract);
        WorldSlice selected = slice("provider-b", "v2", contract);
        ResourceWorldModel world = new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(first, selected));
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        Scenario.Expectation expectation = new Scenario.Expectation(
                "OUTPUT_PATH", "", "/result", "EQUALS", 7, 0.25);
        Scenario scenario = scenario(graph, world, List.of(Scenario.ContractDependency.of(contract)),
                List.of(expectation));

        WorldScenarioCompilation compilation = compile(scenario, world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-b", "v2",
                        selected.fingerprint())));

        assertThat(compilation.bindings().getFirst().fragment()).isEqualTo(selected.behavior());
        assertThat(compilation.bundle().assertions()).containsExactly(expectation.toFixtureAssertion());
        assertThat(compilation.bundle().targetFingerprint()).isEqualTo(scenario.target().fingerprint());
        assertThat(compilation.bundle().metadata()).containsOnlyKeys("compilerVersion", "scenario",
                "world", "fragments");
        assertThat(String.valueOf(compilation.bundle().metadata())).doesNotContain("expected", "secret")
                .doesNotContain(DSL);
        assertThat(compilation.bundle().metadata().get("fragments").toString())
                .doesNotContain("graph customerWorld");
    }

    @Test
    void sourceMapIsBidirectionalAndCompilationFingerprintIgnoresInputOrder() {
        LogicalResourceContract first = contract("logical.customer");
        LogicalResourceContract second = contract("logical.order");
        WorldSlice firstSlice = slice("provider-a", "v1", first);
        WorldSlice secondSlice = slice("provider-b", "v1", second);
        ResourceWorldModel world = new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(secondSlice, firstSlice));
        Graph graph = graphWithTags(Map.of("a", tag(first), "b", tag(second)));
        Scenario scenario = scenario(graph, world, List.of(Scenario.ContractDependency.of(second),
                        Scenario.ContractDependency.of(first)), List.of());
        Map<String, WorldSliceSelection> reversed = new LinkedHashMap<>();
        reversed.put(second.contractId(), new WorldSliceSelection("provider-b", "v1",
                secondSlice.fingerprint()));
        reversed.put(first.contractId(), new WorldSliceSelection("provider-a", "v1",
                firstSlice.fingerprint()));
        WorldScenarioCompilation baseline = compile(scenario, world, graph, reversed);

        for (int attempt = 0; attempt < 20; attempt++) {
            Map<String, WorldSliceSelection> ordered = new LinkedHashMap<>();
            ordered.put(first.contractId(), reversed.get(first.contractId()));
            ordered.put(second.contractId(), reversed.get(second.contractId()));
            WorldScenarioCompilation rebuilt = compile(scenario, world, graph, ordered);
            assertThat(rebuilt.fingerprint()).isEqualTo(baseline.fingerprint());
        }

        baseline.sourceMap().sourceToOutputs().forEach((source, outputs) ->
                outputs.forEach(output -> assertThat(baseline.sourceMap().outputToSources(output))
                        .contains(source)));
        String contractSource = WorldScenarioSourceMap.coordinate("logical-contract",
                first.contractId() + "@" + first.contractFingerprint());
        assertThat(baseline.sourceMap().sourceToOutputs(contractSource)).hasSize(1);
        assertThat(baseline.sourceMap().sourceToOutputs(contractSource).getFirst())
                .startsWith("invocation-site:");
    }

    @Test
    void v1StatelessMaterialRetainsTheHistoricalFourArgumentFingerprint() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        WorldScenarioCompilation compilation = compile(scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of()), world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-a", "v1",
                        world.slices().getFirst().fingerprint())));

        assertThat(WorldScenarioCompilation.fingerprintFor(compilation.bundle(),
                compilation.bindings(), compilation.sourceMap()))
                .isEqualTo("sha256:2f77ddd129edebcf152a9a6f9ac0a31aaa9852c7cb9b62afb15f0b7f7099d24f");
    }

    @Test
    void statePlanAndRunDescriptorSemanticsAreFingerprintBound() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        WorldScenarioCompilation compilation = compile(scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of()), world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-a", "v1",
                        world.slices().getFirst().fingerprint())));
        Scenario scenario = scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of());
        StateAccessPlan balancePlan = statePlan(graph, contract, "/balance");
        StateAccessPlan otherPlan = statePlan(graph, contract, "/other");
        WorldRunStateDescriptor balanceOne = stateDescriptor(scenario, world, graph, "/balance", 1);
        WorldRunStateDescriptor balanceTwo = stateDescriptor(scenario, world, graph, "/balance", 2);
        WorldRunStateDescriptor otherState = stateDescriptor(scenario, world, graph, "/other", 1);

        String balanceOneFingerprint = WorldScenarioCompilation.fingerprintFor(
                compilation.bundle(), compilation.bindings(), compilation.sourceMap(),
                balancePlan, balanceOne);
        assertThat(balanceOneFingerprint).isNotEqualTo(WorldScenarioCompilation.fingerprintFor(
                compilation.bundle(), compilation.bindings(), compilation.sourceMap(),
                otherPlan, balanceOne));
        assertThat(balanceOneFingerprint).isNotEqualTo(WorldScenarioCompilation.fingerprintFor(
                compilation.bundle(), compilation.bindings(), compilation.sourceMap(),
                balancePlan, balanceTwo));
        assertThat(balanceOneFingerprint).isNotEqualTo(WorldScenarioCompilation.fingerprintFor(
                compilation.bundle(), compilation.bindings(), compilation.sourceMap(),
                balancePlan, otherState));
    }

    @Test
    void historicalFingerprintCannotConstructACompilationAfterSourceDrift() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        WorldScenarioCompilation compilation = compile(scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of()), world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-a", "v1",
                        world.slices().getFirst().fingerprint())));

        assertThatThrownBy(() -> new WorldScenarioCompilation(compilation.bundle(),
                compilation.bindings(), WorldScenarioSourceMap.of(List.of()),
                "sha256:2f77ddd129edebcf152a9a6f9ac0a31aaa9852c7cb9b62afb15f0b7f7099d24f"))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class, error ->
                        assertThat(error.code()).isEqualTo(
                                WorldScenarioCompilationException.Code.COMPILATION_FINGERPRINT_MISMATCH));
    }

    @Test
    void compilationRejectsStatelessBindingWithStatefulPlan() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        WorldScenarioCompilation baseline = compile(scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of()), world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-a", "v1",
                        world.slices().getFirst().fingerprint())));
        StateAccessPlan statePlan = statePlan(graph, contract, "/balance");
        String fingerprint = WorldScenarioCompilation.fingerprintFor(baseline.bundle(),
                baseline.bindings(), baseline.sourceMap(), statePlan, WorldRunStateDescriptor.legacy());

        assertThatThrownBy(() -> new WorldScenarioCompilation(baseline.bundle(), baseline.bindings(),
                baseline.sourceMap(), fingerprint, statePlan, WorldRunStateDescriptor.legacy()))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class, error ->
                        assertThat(error.code()).isEqualTo(
                                WorldScenarioCompilationException.Code.INVALID_COMPILATION));
    }

    @Test
    void compilationRejectsPlanKeyDriftAgainstBindingStateSpec() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        WorldScenarioCompilation baseline = compile(scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of()), world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-a", "v1",
                        world.slices().getFirst().fingerprint())));
        WorldDelegateBinding binding = statefulBinding(contract, "/balance");
        StateAccessPlan driftedPlan = statePlan(graph, contract, "/other");
        WorldRunStateDescriptor descriptor = stateDescriptor(
                scenario(graph, world, List.of(Scenario.ContractDependency.of(contract)), List.of()),
                world, graph, "/balance", 1);
        String fingerprint = WorldScenarioCompilation.fingerprintFor(baseline.bundle(),
                List.of(binding), baseline.sourceMap(), driftedPlan, descriptor);

        assertThatThrownBy(() -> new WorldScenarioCompilation(baseline.bundle(), List.of(binding),
                baseline.sourceMap(), fingerprint, driftedPlan, descriptor))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class, error ->
                        assertThat(error.code()).isEqualTo(
                                WorldScenarioCompilationException.Code.INVALID_COMPILATION));
    }

    @Test
    void compilationRejectsDescriptorMissingBindingStateKey() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        WorldScenarioCompilation baseline = compile(scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of()), world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-a", "v1",
                        world.slices().getFirst().fingerprint())));
        WorldDelegateBinding binding = statefulBinding(contract, "/balance");
        StateAccessPlan plan = statePlan(graph, contract, "/balance");
        Scenario scenario = scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of());
        WorldRunStateDescriptor missing = stateDescriptor(scenario, world, graph, "/other", 1);
        String fingerprint = WorldScenarioCompilation.fingerprintFor(baseline.bundle(), List.of(binding),
                baseline.sourceMap(), plan, missing);

        assertThatThrownBy(() -> new WorldScenarioCompilation(baseline.bundle(), List.of(binding),
                baseline.sourceMap(), fingerprint, plan, missing))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class, error ->
                        assertThat(error.code()).isEqualTo(
                                WorldScenarioCompilationException.Code.INVALID_COMPILATION));
    }

    @Test
    void aggregateReadWriteStateCoversASelectedWriteOnlySlice() {
        LogicalResourceContract contract = contract("logical.customer");
        StateSpecV2 writeOnly = StateSpecV2.of(List.of(new StateKeySpec(
                "/balance", StateKeySpec.Access.WRITE, Map.of("type", "integer"), 10)));
        StateSpecV2 readOnly = StateSpecV2.of(List.of(new StateKeySpec(
                "/balance", StateKeySpec.Access.READ, Map.of("type", "integer"), 10)));
        WorldSlice selected = slice("provider-writer", "v1", contract, writeOnly);
        WorldSlice other = slice("provider-reader", "v1", contract, readOnly);
        ResourceWorldModel world = new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(selected, other));
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        Scenario scenario = scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of());

        WorldScenarioCompilation compilation = compile(scenario, world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-writer", "v1",
                        selected.fingerprint())));

        assertThat(compilation.runStateDescriptor().stateSpec().declarations().getFirst().access())
                .isEqualTo(StateKeySpec.Access.READ_WRITE);
        assertThat(compilation.bindings().getFirst().stateSpec().declarations().getFirst().access())
                .isEqualTo(StateKeySpec.Access.WRITE);
        assertThat(compilation.stateAccessPlan().accesses()).singleElement()
                .satisfies(access -> assertThat(access.writeKeys()).containsExactly("/balance"));
    }

    @Test
    void statefulWorldAllowsAStatelessSelectedSliceWithAnEmptySelectedPlan() {
        LogicalResourceContract contract = contract("logical.customer");
        WorldSlice selected = slice("provider-stateless", "v1", contract, StateSpec.empty());
        WorldSlice other = slice("provider-stateful", "v1", contract, StateSpecV2.of(List.of(
                new StateKeySpec("/balance", StateKeySpec.Access.WRITE,
                        Map.of("type", "integer"), 10))));
        ResourceWorldModel world = new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(selected, other));
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        Scenario scenario = scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of());

        WorldScenarioCompilation compilation = compile(scenario, world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-stateless", "v1",
                        selected.fingerprint())));

        assertThat(compilation.runStateDescriptor().stateful()).isTrue();
        assertThat(compilation.stateAccessPlan().accesses()).isEmpty();
        assertThat(compilation.bindings().getFirst().stateSpec().isEmpty()).isTrue();
    }

    @Test
    void compilationRejectsDuplicateLogicalContractBindings() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        WorldScenarioCompilation baseline = compile(scenario(graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of()), world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-a", "v1",
                        world.slices().getFirst().fingerprint())));
        WorldDelegateBinding duplicate = new WorldDelegateBinding("another-rule",
                contract.contractId(), contract.contractFingerprint(),
                BlogeFragmentRef.frozen("another.bloge", DSL));
        List<WorldDelegateBinding> bindings = List.of(baseline.bindings().getFirst(), duplicate);
        String fingerprint = WorldScenarioCompilation.fingerprintFor(baseline.bundle(), bindings,
                baseline.sourceMap());

        assertThatThrownBy(() -> new WorldScenarioCompilation(baseline.bundle(), bindings,
                baseline.sourceMap(), fingerprint))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class, error ->
                        assertThat(error.code()).isEqualTo(
                                WorldScenarioCompilationException.Code.INVALID_COMPILATION));
    }

    @Test
    void rejectsSelectionContractWorldAndTargetDriftWithoutPartialOutput() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        Scenario scenario = scenario(graph, world, List.of(Scenario.ContractDependency.of(contract)), List.of());
        WorldSliceSelection selection = new WorldSliceSelection("provider-a", "v1",
                world.slices().getFirst().fingerprint());

        assertCode(() -> compile(scenario, world, graph, Map.of()),
                WorldScenarioCompilationException.Code.SELECTION_MISSING);
        assertCode(() -> compile(scenario, world, graph, Map.of(contract.contractId(),
                        new WorldSliceSelection("provider-z", "v1", selection.sliceFingerprint()))),
                WorldScenarioCompilationException.Code.CONTRACT_DRIFT);
        ResourceWorldModel otherWorld = world("provider-a", "v9", contract);
        assertCode(() -> compile(scenario, otherWorld, graph, Map.of(contract.contractId(),
                        new WorldSliceSelection("provider-a", "v9", otherWorld.slices().getFirst().fingerprint()))),
                WorldScenarioCompilationException.Code.WORLD_DRIFT);
        Graph changed = graphWithTags(Map.of("lookup", tag(contract), "changed", tag(contract)));
        assertCode(() -> compile(scenario, world, changed, Map.of(contract.contractId(), selection)),
                WorldScenarioCompilationException.Code.TARGET_DRIFT);
    }

    @Test
    void rejectsZeroMultipleAndFingerprintDriftedContractTags() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        WorldSliceSelection selection = new WorldSliceSelection("provider-a", "v1",
                world.slices().getFirst().fingerprint());
        Graph taggedGraph = graphWithTags(Map.of("lookup", tag(contract)));
        Scenario scenario = scenario(taggedGraph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of());

        Graph untaggedGraph = graphWithTags(Map.of());
        Scenario untaggedScenario = scenario(untaggedGraph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of());
        assertCode(() -> compile(untaggedScenario, world, untaggedGraph,
                        Map.of(contract.contractId(), selection)),
                WorldScenarioCompilationException.Code.ZERO_MATCH);
        assertCode(() -> compile(scenario, world, graphWithTags(Map.of("lookup", tag(contract) + ","
                + tag(contract))), Map.of(contract.contractId(), selection)),
                WorldScenarioCompilationException.Code.MULTIPLE_CONTRACT_TAGS);
        String driftedTag = WorldScenarioCompiler.logicalContractTag(contract.contractId(),
                "sha256:" + "f".repeat(64));
        assertCode(() -> compile(scenario, world, graphWithTags(Map.of("lookup", driftedTag)),
                Map.of(contract.contractId(), selection)), WorldScenarioCompilationException.Code.CONTRACT_DRIFT);
    }

    @Test
    void compilerErrorsAreFixedCodesAndNeverCarryDslOrExpectedValues() {
        String secret = "world-compiler-secret";
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        Scenario scenario = scenario(graph, world, List.of(Scenario.ContractDependency.of(contract)),
                List.of(new Scenario.Expectation("OUTPUT_PATH", "", "/x", "EQUALS", secret, null)));

        assertThatThrownBy(() -> compile(scenario, world, graph, Map.of()))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("RG.WORLD.COMPILER.SELECTION_MISSING");
                    assertThat(error.getCause()).isNull();
                    assertThat(error.getMessage()).doesNotContain(secret, DSL);
                });
    }

    @Test
    void nonGraphTargetIsRejectedWithSanitizedUnsupportedCode() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        Scenario scenario = scenario("OPERATOR", graph, world,
                List.of(Scenario.ContractDependency.of(contract)), List.of());

        assertCode(() -> compile(scenario, world, graph, Map.of(contract.contractId(),
                        new WorldSliceSelection("provider-a", "v1",
                                world.slices().getFirst().fingerprint()))),
                WorldScenarioCompilationException.Code.TARGET_KIND_UNSUPPORTED);
    }

    @Test
    void fingerprintAndPayloadFreeProjectionsExcludePayloadButSourceMapKeepsExpectationHash()
            throws Exception {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract);
        Graph graph = graphWithTags(Map.of("lookup", tag(contract)));
        String expectedPayload = "expected-payload-247";
        Scenario.Expectation expectation = new Scenario.Expectation(
                "OUTPUT_PATH", "", "/result", "EQUALS", expectedPayload, null);
        Scenario scenario = scenario(graph, world, List.of(Scenario.ContractDependency.of(contract)),
                List.of(expectation));

        WorldScenarioCompilation compilation = compile(scenario, world, graph,
                Map.of(contract.contractId(), new WorldSliceSelection("provider-a", "v1",
                        world.slices().getFirst().fingerprint())));

        String expectationHash = expectationFingerprint(expectation);
        String expectationSource = WorldScenarioSourceMap.coordinate("scenario-expectation",
                "0@" + expectationHash);
        String assertionOutput = WorldScenarioSourceMap.coordinate("fixture-assertion",
                "0@" + expectationHash);
        assertThat(compilation.sourceMap().sourceToOutputs(expectationSource))
                .containsExactly(assertionOutput);
        assertThat(compilation.sourceMap().outputToSources(assertionOutput))
                .containsExactly(expectationSource);

        String serializedPayloadFree = new ObjectMapper().writeValueAsString(Map.of(
                "metadata", compilation.bundle().metadata(),
                "sourceToOutputs", compilation.sourceMap().sourceToOutputs(),
                "outputToSources", compilation.sourceMap().outputToSources()));
        assertThat(compilation.fingerprint()).doesNotContain("context-value", expectedPayload, DSL);
        assertThat(serializedPayloadFree).doesNotContain("context-value", expectedPayload, DSL);
        assertThat(serializedPayloadFree).contains(expectationHash);
    }

    private static WorldScenarioCompilation compile(Scenario scenario, ResourceWorldModel world,
                                                     Graph graph, Map<String, WorldSliceSelection> selections) {
        return new WorldScenarioCompiler().compile(scenario, world, graph,
                new DefaultOperatorRegistry(), selections);
    }

    private static Scenario scenario(Graph graph, ResourceWorldModel world,
                                     List<Scenario.ContractDependency> dependencies,
                                     List<Scenario.Expectation> expectations) {
        return scenario("GRAPH", graph, world, dependencies, expectations);
    }

    private static Scenario scenario(String targetKind, Graph graph, ResourceWorldModel world,
                                     List<Scenario.ContractDependency> dependencies,
                                     List<Scenario.Expectation> expectations) {
        String targetFingerprint = GraphArtifactFingerprint.of(new com.fasterxml.jackson.databind.ObjectMapper(), graph);
        return new Scenario("scenario-a", "tenant-a", 1,
                new Scenario.TargetRef(targetKind, graph.name(), targetFingerprint), world,
                Map.of("secret", "context-value"), Scenario.WorldStateInit.EMPTY,
                expectations, dependencies);
    }

    private static Graph graphWithTags(Map<String, String> tags) {
        Operator<Object, Object> identity = (input, context) -> input;
        GraphBuilder builder = new GraphBuilder("customer-graph");
        if (tags.isEmpty()) {
            return builder.node("untagged", identity).build();
        }
        var entries = tags.entrySet().iterator();
        var first = entries.next();
        var last = builder.node(first.getKey(), identity).meta("tags", first.getValue());
        while (entries.hasNext()) {
            var entry = entries.next();
            last = last.node(entry.getKey(), identity).meta("tags", entry.getValue());
        }
        return last.build();
    }

    private static Graph graphWithPrimaryAndResourceTag(String contractTag) {
        Graph graph = graphWithTags(Map.of("primary", contractTag, "resource", contractTag));
        NodeSpec resource = graph.nodes().get("resource").toBuilder()
                .operatorRef("httpResource").build();
        Map<String, NodeSpec> nodes = new LinkedHashMap<>(graph.nodes());
        nodes.put("resource", resource);
        return new Graph(graph.name(), nodes, graph.edges(), graph.sourceNodes(), graph.terminalNodes(),
                SchemaValidationLevel.OFF, graph.embeddedOperators());
    }

    private static String expectationFingerprint(Scenario.Expectation expectation) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("scope", expectation.scope());
        material.put("nodeId", expectation.nodeId());
        material.put("path", expectation.path());
        material.put("operator", expectation.operator());
        material.put("expected", expectation.expected());
        material.put("numericTolerance", expectation.numericTolerance());
        return VisualBundleFingerprint.fromMaterial(material);
    }

    private static LogicalResourceContract contract(String id) {
        return new LogicalResourceContract(id, objectSchema("id", "string", true),
                objectSchema("result", "string", true), confirmed(Map.of("NONE", List.of("N/A"))));
    }

    private static ResourceWorldModel world(String provider, String version,
                                            LogicalResourceContract contract) {
        return new ResourceWorldModel("customer-world", "tenant-a", 1,
                List.of(slice(provider, version, contract)));
    }

    private static WorldSlice slice(String provider, String version, LogicalResourceContract contract) {
        return slice(provider, version, contract, StateSpec.empty());
    }

    private static WorldSlice slice(String provider, String version, LogicalResourceContract contract,
                                    WorldStateSpec state) {
        ResourceDesignContract design = new ResourceDesignContract(contract.contractId(),
                contract.contractId(), "Resource", "", List.of(), contract.inputShape(),
                contract.outputShape(), Map.of(), "ACTIVE");
        LogicalResourceBinding binding = LogicalResourceBinding.bind(provider, version, design,
                descriptor(contract.contractId()), contract);
        return WorldSlice.register(new WorldSlice.Registration("tenant-a", provider, version,
                        contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("customer-world.bloge", DSL), state);
    }

    private static String tag(LogicalResourceContract contract) {
        return WorldScenarioCompiler.logicalContractTag(contract.contractId(), contract.contractFingerprint());
    }

    private static StateAccessPlan statePlan(Graph graph, LogicalResourceContract contract, String key) {
        InvocationInventory inventory = new InvocationInventoryBuilder(new DefaultOperatorRegistry())
                .build(graph, GraphArtifactFingerprint.of(new ObjectMapper(), graph));
        StateSpecV2 state = StateSpecV2.of(List.of(new StateKeySpec(key,
                StateKeySpec.Access.READ_WRITE, Map.of("type", "integer"), 0)));
        WorldDelegateBinding binding = statefulBinding(contract, key);
        return StateAccessPlan.compile(graph, Map.of(contract.contractId(), inventory.entries()),
                List.of(binding), inventory.entries());
    }

    private static WorldDelegateBinding statefulBinding(LogicalResourceContract contract, String key) {
        StateSpecV2 state = StateSpecV2.of(List.of(new StateKeySpec(key,
                StateKeySpec.Access.READ_WRITE, Map.of("type", "integer"), 0)));
        return new WorldDelegateBinding("world-delegate:" + contract.contractId(),
                contract.contractId(), contract.contractFingerprint(),
                BlogeFragmentRef.frozen("state.bloge", DSL), state);
    }

    private static WorldRunStateDescriptor stateDescriptor(Scenario scenario, ResourceWorldModel world,
                                                            Graph graph, String key, int value) {
        StateSpecV2 state = StateSpecV2.of(List.of(new StateKeySpec(key,
                StateKeySpec.Access.READ_WRITE, Map.of("type", "integer"), 0)));
        return new WorldRunStateDescriptor(state, Map.of(key, value), scenario.fingerprint(),
                world.fingerprint(), GraphArtifactFingerprint.of(new ObjectMapper(), graph));
    }

    private static void assertCode(Runnable operation, WorldScenarioCompilationException.Code code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(WorldScenarioCompilationException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
