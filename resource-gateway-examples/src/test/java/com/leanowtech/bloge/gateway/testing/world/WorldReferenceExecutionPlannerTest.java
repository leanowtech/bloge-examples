package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.world.access.ResolvedWorldAssetControl;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogKind;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.confirmed;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.descriptor;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.objectSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldReferenceExecutionPlannerTest {
    private static final String DSL = "graph secretWorld { transform secret { value = ctx.secret } }";
    private static final String TENANT = "tenant-a";
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorldReferenceExecutionPlanner planner = new WorldReferenceExecutionPlanner(
            mapper, new DefaultOperatorRegistry());

    @Test
    void scenarioReferencePreservesExactScenarioAndCompilesAgainstCurrentGraph() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world(List.of(slice("provider-a", "v1", contract)));
        Graph graph = graphWithTags(List.of(Map.entry("lookup", tag(contract))));
        Map<String, Object> context = Map.of("customerId", "customer-a", "secret", "payload");
        Scenario scenario = scenario(graph, world, context, List.of(Scenario.ContractDependency.of(contract)));
        GovernedResourceRef ref = ref(GovernedCatalogKind.SCENARIO, scenario.scenarioId(),
                scenario.revision(), scenario.fingerprint());

        WorldReferenceExecutionPlanner.Plan plan = planner.plan(
                ResolvedWorldAssetControl.scenario(ref, scenario, world), graph, context);

        assertThat(plan.primaryRef()).isEqualTo(ref);
        assertThat(plan.effectiveScenario()).isSameAs(scenario);
        assertThat(plan.worldModel()).isSameAs(world);
        assertThat(plan.provenance()).isEqualTo(WorldReferenceExecutionPlanner.ProvenanceKind.SCENARIO);
        assertThat(plan.compilation().bundle().targetFingerprint())
                .isEqualTo(GraphArtifactFingerprint.of(mapper, graph));
    }

    @Test
    void scenarioTargetAndContextDriftFailClosedWithFixedCodes() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world(List.of(slice("provider-a", "v1", contract)));
        Graph graph = graphWithTags(List.of(Map.entry("lookup", tag(contract))));
        Map<String, Object> context = Map.of("customerId", "customer-a");
        Scenario exact = scenario(graph, world, context, List.of(Scenario.ContractDependency.of(contract)));

        Scenario wrongTarget = new Scenario(exact.scenarioId(), exact.tenantId(), exact.revision(),
                new Scenario.TargetRef("GRAPH", "other-graph", exact.target().fingerprint()), world,
                context, Scenario.WorldStateInit.EMPTY, List.of(), exact.contractDependencies());
        Scenario wrongContext = scenario(graph, world, Map.of("customerId", "other-customer"),
                List.of(Scenario.ContractDependency.of(contract)));

        assertCode(() -> planner.plan(ResolvedWorldAssetControl.scenario(
                ref(GovernedCatalogKind.SCENARIO, wrongTarget.scenarioId(), wrongTarget.revision(),
                        wrongTarget.fingerprint()), wrongTarget, world), graph, context),
                WorldScenarioCompilationException.Code.TARGET_DRIFT);
        assertCode(() -> planner.plan(ResolvedWorldAssetControl.scenario(
                ref(GovernedCatalogKind.SCENARIO, wrongContext.scenarioId(), wrongContext.revision(),
                        wrongContext.fingerprint()), wrongContext, world), graph, context),
                WorldScenarioCompilationException.Code.TARGET_DRIFT);
    }

    @Test
    void scenarioTargetFingerprintDriftIsRejectedWhenTargetIdIsExact() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world(List.of(slice("provider-a", "v1", contract)));
        Graph graph = graphWithTags(List.of(Map.entry("lookup", tag(contract))));
        Map<String, Object> context = Map.of("customerId", "customer-a");
        Scenario drifted = new Scenario("scenario-a", TENANT, world.revision(),
                new Scenario.TargetRef("GRAPH", graph.name(), "sha256:" + "f".repeat(64)), world,
                context, Scenario.WorldStateInit.EMPTY, List.of(),
                List.of(Scenario.ContractDependency.of(contract)));

        assertCode(() -> planner.plan(ResolvedWorldAssetControl.scenario(
                ref(GovernedCatalogKind.SCENARIO, drifted.scenarioId(), drifted.revision(),
                        drifted.fingerprint()), drifted, world), graph, context),
                WorldScenarioCompilationException.Code.TARGET_DRIFT);
    }

    @Test
    void worldReferenceDerivesOneContractAndCompiles() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world(List.of(slice("provider-a", "v1", contract)));
        Graph graph = graphWithTags(List.of(Map.entry("lookup", tag(contract))));

        WorldReferenceExecutionPlanner.Plan plan = planner.plan(worldControl(world), graph,
                Map.of("secret", "context-value"));

        assertThat(plan.provenance()).isEqualTo(WorldReferenceExecutionPlanner.ProvenanceKind.RESOURCE_WORLD_MODEL);
        assertThat(plan.effectiveScenario().contractDependencies())
                .extracting(Scenario.ContractDependency::contractId)
                .containsExactly(contract.contractId());
        assertThat(plan.effectiveScenario().target().id()).isEqualTo(graph.name());
        assertThat(plan.compilation().bindings()).hasSize(1);
    }

    @Test
    void worldReferenceDerivesMultipleContractsInCanonicalOrder() {
        LogicalResourceContract customer = contract("logical.customer");
        LogicalResourceContract order = contract("logical.order");
        ResourceWorldModel world = world(List.of(
                slice("provider-b", "v1", order), slice("provider-a", "v1", customer)));
        Graph graph = graphWithTags(List.of(
                Map.entry("order", tag(order)), Map.entry("customer", tag(customer))));

        WorldReferenceExecutionPlanner.Plan plan = planner.plan(worldControl(world), graph, Map.of());

        assertThat(plan.effectiveScenario().contractDependencies())
                .extracting(Scenario.ContractDependency::contractId)
                .containsExactly("logical.customer", "logical.order");
        assertThat(plan.compilation().bindings())
                .extracting(WorldDelegateBinding::logicalContractId)
                .containsExactly("logical.customer", "logical.order");
    }

    @Test
    void worldReferenceCompilationIsStableAcrossEquivalentInputOrderRepeatedly() {
        LogicalResourceContract customer = contract("logical.customer");
        LogicalResourceContract order = contract("logical.order");
        ResourceWorldModel world = world(List.of(
                slice("provider-b", "v1", order), slice("provider-a", "v1", customer)));
        Graph first = graphWithTags(List.of(
                Map.entry("order", tag(order)), Map.entry("customer", tag(customer))));
        Graph second = graphWithTags(List.of(
                Map.entry("customer", tag(customer)), Map.entry("order", tag(order))));

        String expected = planner.plan(worldControl(world), first,
                linkedContext(false)).compilation().fingerprint();
        for (int attempt = 0; attempt < 20; attempt++) {
            Graph graph = attempt % 2 == 0 ? first : second;
            assertThat(planner.plan(worldControl(world), graph, linkedContext(attempt % 2 == 1))
                    .compilation().fingerprint()).isEqualTo(expected);
        }
    }

    @Test
    void worldReferenceRejectsNoTagsMultipleTagsAndContractDrift() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world(List.of(slice("provider-a", "v1", contract)));

        assertCode(() -> planner.plan(worldControl(world), graphWithTags(List.of()), Map.of()),
                WorldScenarioCompilationException.Code.CONTRACT_NOT_DECLARED);
        assertCode(() -> planner.plan(worldControl(world), graphWithTags(List.of(Map.entry(
                        "ambiguous", tag(contract) + "," + tag(contract)))), Map.of()),
                WorldScenarioCompilationException.Code.MULTIPLE_CONTRACT_TAGS);
        String differentFingerprint = "sha256:" + "f".repeat(64);
        assertCode(() -> planner.plan(worldControl(world), graphWithTags(List.of(
                        Map.entry("first", tag(contract)),
                        Map.entry("second", WorldScenarioCompiler.logicalContractTag(
                                contract.contractId(), differentFingerprint)))), Map.of()),
                WorldScenarioCompilationException.Code.CONTRACT_DRIFT);
    }

    @Test
    void worldReferenceRejectsZeroAndMultipleEligibleCandidates() {
        LogicalResourceContract required = contract("logical.customer");
        LogicalResourceContract unrelated = contract("logical.order");
        Graph graph = graphWithTags(List.of(Map.entry("lookup", tag(required))));

        assertCode(() -> planner.plan(worldControl(world(List.of(
                        slice("provider-a", "v1", unrelated)))), graph, Map.of()),
                WorldScenarioCompilationException.Code.SELECTION_MISSING);
        assertCode(() -> planner.plan(worldControl(world(List.of(
                        slice("provider-a", "v1", required),
                        slice("provider-b", "v2", required)))), graph, Map.of()),
                WorldScenarioCompilationException.Code.SELECTION_NOT_UNIQUE);
    }

    @Test
    void planConstructorAndToStringRemainImmutableAndPayloadFree() {
        LogicalResourceContract contract = contract("logical.customer");
        ResourceWorldModel world = world(List.of(slice("provider-a", "v1", contract)));
        Graph graph = graphWithTags(List.of(Map.entry("lookup", tag(contract))));
        Map<String, Object> context = Map.of("secret", "context-secret");
        WorldReferenceExecutionPlanner.Plan plan = planner.plan(worldControl(world), graph, context);

        assertThat(plan.toString())
                .contains("RESOURCE_WORLD_MODEL", "primaryRevision=" + world.revision(),
                        "provenance=RESOURCE_WORLD_MODEL", plan.compilation().fingerprint())
                .doesNotContain(TENANT, world.worldModelId(), world.fingerprint(),
                        plan.effectiveScenario().fingerprint(), "context-secret", DSL, tag(contract));
        assertThatThrownBy(() -> new WorldReferenceExecutionPlanner.Plan(
                plan.primaryRef(), plan.effectiveScenario(), plan.worldModel(), plan.compilation(),
                WorldReferenceExecutionPlanner.ProvenanceKind.SCENARIO))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class, error ->
                        assertThat(error.code()).isEqualTo(WorldScenarioCompilationException.Code.INVALID_INPUT));
    }

    private ResolvedWorldAssetControl worldControl(ResourceWorldModel world) {
        return ResolvedWorldAssetControl.world(ref(GovernedCatalogKind.RESOURCE_WORLD_MODEL,
                world.worldModelId(), world.revision(), world.fingerprint()), world);
    }

    private GovernedResourceRef ref(GovernedCatalogKind kind, String id, long revision, String fingerprint) {
        return new GovernedResourceRef(TENANT, kind, id, revision, fingerprint);
    }

    private Scenario scenario(Graph graph, ResourceWorldModel world, Map<String, Object> context,
                              List<Scenario.ContractDependency> dependencies) {
        return new Scenario("scenario-a", TENANT, world.revision(),
                new Scenario.TargetRef("GRAPH", graph.name(), GraphArtifactFingerprint.of(mapper, graph)),
                world, context, Scenario.WorldStateInit.EMPTY, List.of(), dependencies);
    }

    private static Graph graphWithTags(List<Map.Entry<String, String>> tags) {
        Operator<Object, Object> identity = (input, context) -> input;
        GraphBuilder builder = new GraphBuilder("customer-graph");
        if (tags.isEmpty()) {
            return builder.node("untagged", identity).build();
        }
        var entries = tags.iterator();
        var first = entries.next();
        var last = builder.node(first.getKey(), identity).meta("tags", first.getValue());
        while (entries.hasNext()) {
            var entry = entries.next();
            last = last.node(entry.getKey(), identity).meta("tags", entry.getValue());
        }
        return last.build();
    }

    private static Map<String, Object> linkedContext(boolean reverse) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (reverse) {
            context.put("secret", "same-context");
            context.put("customerId", "customer-a");
        } else {
            context.put("customerId", "customer-a");
            context.put("secret", "same-context");
        }
        return context;
    }

    private static LogicalResourceContract contract(String id) {
        return new LogicalResourceContract(id, objectSchema("id", "string", true),
                objectSchema("result", "string", true), confirmed(Map.of("NONE", List.of("N/A"))));
    }

    private static ResourceWorldModel world(List<WorldSlice> slices) {
        return new ResourceWorldModel("customer-world", TENANT, 1, slices);
    }

    private static WorldSlice slice(String provider, String version, LogicalResourceContract contract) {
        ResourceDesignContract design = new ResourceDesignContract(contract.contractId(),
                contract.contractId(), "Resource", "", List.of(), contract.inputShape(),
                contract.outputShape(), Map.of(), "ACTIVE");
        LogicalResourceBinding binding = LogicalResourceBinding.bind(provider, version, design,
                descriptor(contract.contractId()), contract);
        return WorldSlice.register(new WorldSlice.Registration(TENANT, provider, version,
                contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("customer-world.bloge", DSL), StateSpec.empty());
    }

    private static String tag(LogicalResourceContract contract) {
        return WorldScenarioCompiler.logicalContractTag(contract.contractId(), contract.contractFingerprint());
    }

    private static void assertCode(Runnable operation, WorldScenarioCompilationException.Code code) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(WorldScenarioCompilationException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
