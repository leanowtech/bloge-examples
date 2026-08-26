package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.confirmed;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.descriptor;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.objectSchema;
import static org.assertj.core.api.Assertions.assertThat;

/** Independent C2c compiler verification; this class never delegates to compiler helpers. */
class WorldScenarioCompilerOracleTest {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final String DSL = "graph customerWorld { transform result { value = ctx.id } }";
    private static final String SENTINEL = "WORLD_DELEGATE_UNBOUND";

    @Test
    void structuralOracleClosesThreeFixedTopologies() {
        for (Case topology : topologies()) {
            WorldScenarioCompilation compilation = compile(topology);
            FixtureBundle bundle = compilation.bundle();
            Map<String, FixtureRule> rules = bundle.rules().stream()
                    .collect(java.util.stream.Collectors.toMap(FixtureRule::ruleId,
                            value -> value, (left, right) -> left, TreeMap::new));
            Map<String, WorldDelegateBinding> bindings = compilation.bindings().stream()
                    .collect(java.util.stream.Collectors.toMap(WorldDelegateBinding::ruleId,
                            value -> value, (left, right) -> left, TreeMap::new));

            assertThat(rules.keySet()).containsExactlyInAnyOrderElementsOf(bindings.keySet());
            assertThat(rules).hasSize(topology.contractIds().size());
            rules.values().forEach(rule -> {
                assertThat(rule.selector().graphPath()).isEmpty();
                assertThat(rule.selector().nodeId()).isEmpty();
                assertThat(rule.selector().operatorRef()).isEmpty();
                assertThat(rule.behavior().kind()).isEqualTo(FixtureRule.BehaviorKind.DENY);
                assertThat(rule.behavior().errorCode()).isEqualTo(SENTINEL);
                assertThat(rule.behavior().errorMessage()).isEqualTo(SENTINEL);
                assertThat(rule.selector().tags()).hasSize(1);
                String id = rule.ruleId().substring("world-delegate:".length());
                String fingerprint = bindings.get(rule.ruleId()).contractFingerprint();
                assertThat(rule.selector().tags().getFirst()).contains(encoded(id), fingerprint)
                        .doesNotContain(id);
            });
            bindings.values().forEach(binding -> {
                String ruleOutput = WorldScenarioSourceMap.coordinate("fixture-rule",
                        binding.ruleId());
                String fragmentSource = WorldScenarioSourceMap.coordinate("fragment",
                        binding.fragment().artifactId() + "@" + binding.fragment().revision()
                                + "@" + binding.fragment().fingerprint());
                assertThat(compilation.sourceMap().sourceToOutputs(fragmentSource))
                        .contains(ruleOutput);
                assertThat(compilation.sourceMap().outputToSources(ruleOutput))
                        .contains(fragmentSource);
                String logicalSource = WorldScenarioSourceMap.coordinate("logical-contract",
                        binding.logicalContractId() + "@" + binding.contractFingerprint());
                assertThat(invocationSites(compilation.sourceMap().sourceToOutputs(logicalSource)))
                        .containsExactlyInAnyOrderElementsOf(
                                topology.sitesByContract().get(binding.logicalContractId()));
            });
            compilation.sourceMap().sourceToOutputs().forEach((source, outputs) ->
                    outputs.forEach(output -> assertThat(compilation.sourceMap()
                            .outputToSources(output)).contains(source)));
            compilation.sourceMap().outputToSources().forEach((output, sources) ->
                    sources.forEach(source -> assertThat(compilation.sourceMap()
                            .sourceToOutputs(source)).contains(output)));

            String metadata = MAPPER.valueToTree(bundle.metadata()).toString();
            assertThat(metadata).doesNotContain("context-value", DSL);
            assertThat(compilation.fingerprint()).doesNotContain("context-value", DSL);
            assertThat(compilation.fingerprint()).isEqualTo(compilation.recomputedFingerprint());
        }
    }

    @Test
    void independentReferenceProjectionMatchesRulesSitesAndBindings() {
        for (Case topology : topologies()) {
            WorldScenarioCompilation actual = compile(topology);
            Map<String, ReferenceRow> expected = new TreeMap<>();
            for (String contractId : topology.contractIds()) {
                WorldSlice slice = topology.world().slices().stream()
                        .filter(candidate -> candidate.logicalContractId().equals(contractId))
                        .findFirst().orElseThrow();
                String ruleId = "world-delegate:" + contractId;
                Set<String> sites = topology.sitesByContract().get(contractId);
                expected.put(ruleId, new ReferenceRow(ruleId, contractId,
                        slice.contractFingerprint(), slice.behavior().artifactId(),
                        slice.behavior().revision(), slice.behavior().fingerprint(), sites));
            }
            Map<String, ReferenceRow> observed = new TreeMap<>();
            for (WorldDelegateBinding binding : actual.bindings()) {
                FixtureRule rule = actual.bundle().rules().stream()
                        .filter(candidate -> candidate.ruleId().equals(binding.ruleId()))
                        .findFirst().orElseThrow();
                Set<String> sites = new TreeSet<>();
                String logicalSource = WorldScenarioSourceMap.coordinate("logical-contract",
                        binding.logicalContractId() + "@" + binding.contractFingerprint());
                sites.addAll(invocationSites(actual.sourceMap().sourceToOutputs(logicalSource)));
                observed.put(binding.ruleId(), new ReferenceRow(binding.ruleId(),
                        binding.logicalContractId(), binding.contractFingerprint(),
                        binding.fragment().artifactId(), binding.fragment().revision(),
                        binding.fragment().fingerprint(), sites));
                assertThat(rule.selector().tags().getFirst())
                        .contains(encoded(binding.logicalContractId()), binding.contractFingerprint());
            }
            assertThat(observed).isEqualTo(expected);
        }
    }

    @Test
    void jsonAndForwardSourceMapRoundtripPreserveCompilationIdentity() throws Exception {
        Case topology = topologies().getLast();
        WorldScenarioCompilation original = compile(topology);
        JsonNode bundleJson = MAPPER.valueToTree(original.bundle());
        FixtureBundle bundle = MAPPER.treeToValue(bundleJson, FixtureBundle.class);

        List<WorldDelegateBinding> bindings = new ArrayList<>();
        List<Map<String, Object>> bindingRows = original.bindings().stream()
                .map(WorldScenarioCompilerOracleTest::bindingRow).toList();
        JsonNode bindingsJson = MAPPER.valueToTree(bindingRows);
        for (JsonNode json : bindingsJson) {
            JsonNode fragment = json.path("fragment");
            BlogeFragmentRef ref = BlogeFragmentRef.frozen(
                    fragment.path("artifactId").asText(), fragment.path("revision").asLong(),
                    fragment.path("source").asText(), fragment.path("outputNodeId").asText());
            bindings.add(new WorldDelegateBinding(json.path("ruleId").asText(),
                    json.path("logicalContractId").asText(),
                    json.path("contractFingerprint").asText(), ref));
        }

        List<WorldScenarioSourceMap.Link> links = new ArrayList<>();
        original.sourceMap().sourceToOutputs().forEach((source, outputs) ->
                outputs.forEach(output -> links.add(WorldScenarioSourceMap.link(source, output))));
        WorldScenarioSourceMap sourceMap = WorldScenarioSourceMap.of(links);
        WorldScenarioCompilation roundtrip = new WorldScenarioCompilation(
                bundle, bindings, sourceMap, original.fingerprint());

        assertThat(roundtrip.bundle()).isEqualTo(original.bundle());
        assertThat(roundtrip.bindings()).isEqualTo(original.bindings());
        assertThat(roundtrip.bindings().stream().map(WorldScenarioCompilerOracleTest::bindingRow).toList())
                .isEqualTo(bindingRows);
        assertThat(roundtrip.sourceMap().sourceToOutputs())
                .isEqualTo(original.sourceMap().sourceToOutputs());
        assertThat(roundtrip.sourceMap().outputToSources())
                .isEqualTo(original.sourceMap().outputToSources());
        assertThat(roundtrip.fingerprint()).isEqualTo(original.fingerprint());
        String duplicateHash = original.sourceMap().sourceToOutputs().keySet().stream()
                .filter(value -> value.startsWith("scenario-expectation:"))
                .map(value -> value.substring("scenario-expectation:".length()).split("@", 2)[1])
                .findFirst().orElseThrow();
        assertThat(original.sourceMap().sourceToOutputs().keySet())
                .contains("scenario-expectation:0@" + duplicateHash,
                        "scenario-expectation:1@" + duplicateHash);
        assertThat(MAPPER.valueToTree(original.bundle().metadata()).toString())
                .doesNotContain("context-value", DSL, "duplicate-expected-value");
        assertThat(original.fingerprint()).doesNotContain("context-value", DSL,
                "duplicate-expected-value");
    }

    private static WorldScenarioCompilation compile(Case topology) {
        return new WorldScenarioCompiler().compile(topology.scenario(), topology.world(),
                topology.graph(), new DefaultOperatorRegistry(), topology.selections());
    }

    private static List<Case> topologies() {
        LogicalResourceContract customer = contract("logical.customer");
        LogicalResourceContract payment = contract("logical.payment");
        ResourceWorldModel customerWorld = world(List.of(slice("provider-a", "v1", customer)));
        ResourceWorldModel mixedWorld = world(List.of(slice("provider-a", "v1", customer)));
        ResourceWorldModel twoWorld = world(List.of(slice("provider-z", "v9", payment),
                slice("provider-a", "v1", customer)));
        Case primary = topology(customerWorld, graph(customer, "first", "second"),
                Map.of(customer.contractId(), Set.of("/root/first#PRIMARY", "/root/second#PRIMARY")),
                Map.of(customer.contractId(), selection(customerWorld.slices().getFirst())));
        Case mixed = topology(mixedWorld, mixedGraph(customer),
                Map.of(customer.contractId(), Set.of("/root/primary#PRIMARY", "/root/resource#RESOURCE")),
                Map.of(customer.contractId(), selection(mixedWorld.slices().getFirst())));
        Map<String, WorldSliceSelection> reversedSelections = new LinkedHashMap<>();
        reversedSelections.put(customer.contractId(), selection(twoWorld.slices().stream()
                .filter(slice -> slice.logicalContractId().equals(customer.contractId()))
                .findFirst().orElseThrow()));
        reversedSelections.put(payment.contractId(), selection(twoWorld.slices().stream()
                .filter(slice -> slice.logicalContractId().equals(payment.contractId()))
                .findFirst().orElseThrow()));
        Case two = topology(twoWorld, graph(customer, payment, "customer", "payment"),
                Map.of(customer.contractId(), Set.of("/root/customer#PRIMARY"),
                        payment.contractId(), Set.of("/root/payment#PRIMARY")),
                reversedSelections);
        return List.of(primary, mixed, two);
    }

    private static Case topology(ResourceWorldModel world, Graph graph,
                                 Map<String, Set<String>> sites, Map<String, WorldSliceSelection> selections) {
        List<Scenario.ContractDependency> dependencies = world.slices().stream()
                .map(WorldSlice::contract).map(Scenario.ContractDependency::of).toList();
        String fingerprint = GraphArtifactFingerprint.of(MAPPER, graph);
        Scenario scenario = new Scenario("oracle-scenario", "tenant-a", 1,
                new Scenario.TargetRef("GRAPH", graph.name(), fingerprint), world,
                Map.of("context", "context-value"), Scenario.WorldStateInit.EMPTY,
                List.of(new Scenario.Expectation("OUTPUT_PATH", "", "/result", "EQUALS",
                        "duplicate-expected-value", null),
                        new Scenario.Expectation("OUTPUT_PATH", "", "/result", "EQUALS",
                                "duplicate-expected-value", null)), dependencies);
        return new Case(graph, world, scenario, selections, sites);
    }

    private static Graph graph(LogicalResourceContract contract, String first, String second) {
        Operator<Object, Object> identity = (input, context) -> input;
                String tag = logicalContractTag(contract.contractId(), contract.contractFingerprint());
        GraphBuilder builder = new GraphBuilder("oracle-graph");
        return builder.node(first, identity).meta("tags", tag)
                .node(second, identity).meta("tags", tag).build();
    }

    private static Graph mixedGraph(LogicalResourceContract contract) {
        Graph graph = graph(contract, "primary", "resource");
        var resource = graph.nodes().get("resource").toBuilder().operatorRef("httpResource").build();
        Map<String, com.leanowtech.bloge.core.model.NodeSpec> nodes = new LinkedHashMap<>(graph.nodes());
        nodes.put("resource", resource);
        return new Graph(graph.name(), nodes, graph.edges(), graph.sourceNodes(), graph.terminalNodes(),
                com.leanowtech.bloge.core.schema.SchemaValidationLevel.OFF, graph.embeddedOperators());
    }

    private static Graph graph(LogicalResourceContract first, LogicalResourceContract second,
                               String firstNode, String secondNode) {
        Operator<Object, Object> identity = (input, context) -> input;
        String firstTag = logicalContractTag(first.contractId(), first.contractFingerprint());
        String secondTag = logicalContractTag(second.contractId(), second.contractFingerprint());
        return new GraphBuilder("oracle-graph").node(firstNode, identity).meta("tags", firstTag)
                .node(secondNode, identity).meta("tags", secondTag).build();
    }

    private static String encoded(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String logicalContractTag(String contractId, String fingerprint) {
        return "bloge.logical-contract:" + encoded(contractId) + "@" + fingerprint;
    }

    private static Set<String> invocationSites(List<String> outputs) {
        return outputs.stream().filter(value -> value.startsWith("invocation-site:"))
                .map(value -> value.substring("invocation-site:".length()))
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    private static Map<String, Object> bindingRow(WorldDelegateBinding binding) {
        BlogeFragmentRef fragment = binding.fragment();
        return Map.of("ruleId", binding.ruleId(),
                "logicalContractId", binding.logicalContractId(),
                "contractFingerprint", binding.contractFingerprint(),
                "fragment", Map.of("artifactId", fragment.artifactId(),
                        "revision", fragment.revision(), "source", fragment.source(),
                        "outputNodeId", fragment.outputNodeId(),
                        "fingerprint", fragment.fingerprint()));
    }

    private static LogicalResourceContract contract(String id) {
        return new LogicalResourceContract(id, objectSchema("id", "string", true),
                objectSchema("result", "string", true), confirmed(Map.of("NONE", List.of("N/A"))));
    }

    private static ResourceWorldModel world(List<WorldSlice> slices) {
        return new ResourceWorldModel("oracle-world", "tenant-a", 1, slices);
    }

    private static WorldSlice slice(String provider, String version,
                                    LogicalResourceContract contract) {
        ResourceDesignContract design = new ResourceDesignContract(contract.contractId(),
                contract.contractId(), "Resource", "", List.of(), contract.inputShape(),
                contract.outputShape(), Map.of(), "ACTIVE");
        LogicalResourceBinding binding = LogicalResourceBinding.bind(provider, version, design,
                descriptor(contract.contractId()), contract);
        return WorldSlice.register(new WorldSlice.Registration("tenant-a", provider, version,
                        contract.contractId(), contract.contractFingerprint(),
                        binding.descriptorFingerprint(), true), contract, binding,
                BlogeFragmentRef.frozen("oracle-world.bloge", DSL), StateSpec.empty());
    }

    private static WorldSliceSelection selection(WorldSlice slice) {
        return new WorldSliceSelection(slice.provider(), slice.apiVersion(), slice.fingerprint());
    }

    private record Case(Graph graph, ResourceWorldModel world, Scenario scenario,
                        Map<String, WorldSliceSelection> selections,
                        Map<String, Set<String>> sitesByContract) {
        Set<String> contractIds() { return sitesByContract.keySet(); }
    }

    private record ReferenceRow(String ruleId, String contractId, String contractFingerprint,
                                String artifactId, long revision, String fragmentFingerprint,
                                Set<String> sites) {
    }
}
