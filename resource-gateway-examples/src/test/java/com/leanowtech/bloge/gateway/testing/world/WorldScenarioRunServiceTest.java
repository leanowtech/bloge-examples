package com.leanowtech.bloge.gateway.testing.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.confirmed;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.descriptor;
import static com.leanowtech.bloge.gateway.testing.world.LogicalResourceContractTest.objectSchema;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldScenarioRunServiceTest {
    private static final String DSL = """
            graph customerWorld {
              decision_table response(type = ctx.type) hit=first -> String {
                rule (type: type == "vip") -> "priority"
                otherwise -> "standard"
              }
            }
            """;

    @Test
    void routesOneFragmentToTwoNodesWithoutCallingRealOperatorsAndStaysStable() {
        AtomicInteger realCalls = new AtomicInteger();
        Graph graph = graph(realCalls, "logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract("logical.customer"));
        WorldScenarioCompilation compilation = compile(graph, world);
        WorldScenarioRunService service = new WorldScenarioRunService(
                new DefaultOperatorRegistry(), new ObjectMapper(), new WorldFragmentTestKit());

        List<TestExecutionResult> runs = java.util.stream.IntStream.range(0, 20)
                .mapToObj(ignored -> service.execute(compilation, graph,
                        new GraphContext(Map.of("request", Map.of("type", "vip")))))
                .toList();

        assertThat(runs).allSatisfy(result -> {
            assertThat(result.passed()).isTrue();
            assertThat(result.evidence().evidenceClass())
                    .isEqualTo(com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence.EvidenceClass
                            .EXPLORATORY);
            assertThat(result.evidence().nodeTrace()).hasSize(2)
                    .allSatisfy(trace -> assertThat(trace.fidelity()).isEqualTo("WORLD_DELEGATE"));
        });
        assertThat(realCalls).hasValue(0);
        assertThat(runs).extracting(result -> result.evidence().semanticResultFingerprint())
                .hasSize(20)
                .containsOnly(runs.getFirst().evidence().semanticResultFingerprint());
    }

    @Test
    void missingRuntimeBindingAndWrongPurposeFailBeforeEngine() {
        AtomicInteger realCalls = new AtomicInteger();
        Graph graph = graph(realCalls, "logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract("logical.customer"));
        WorldScenarioCompilation compilation = compile(graph, world);

        assertThatThrownBy(() -> new WorldScenarioRunService(
                new DefaultOperatorRegistry(), new ObjectMapper(), null)
                .execute(compilation, graph,
                        new GraphContext(Map.of("request", Map.of("type", "vip")))))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(WorldScenarioCompilationException.Code.INVALID_INPUT));

        WorldScenarioCompilation missingBinding = new WorldScenarioCompilation(
                compilation.bundle(), List.of(), compilation.sourceMap(),
                WorldScenarioCompilation.fingerprintFor(
                        compilation.bundle(), List.of(), compilation.sourceMap()));
        assertThatThrownBy(() -> new WorldScenarioRunService(
                new DefaultOperatorRegistry(), new ObjectMapper())
                .execute(missingBinding, graph,
                        new GraphContext(Map.of("request", Map.of("type", "vip")))))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(WorldScenarioCompilationException.Code.INVALID_BINDING));

        TestExecutionRequest wrongPurpose = new TestExecutionRequest(
                graph, new GraphContext(Map.of("type", "vip")), compilation.bundle(),
                "NOT_GRAPH_CONTRACT_TEST", compilation.bundle().targetFingerprint(),
                TestExecutionRequest.FixtureSource.INLINE, Map.of(), false, null, null);
        assertThatThrownBy(() -> new WorldScenarioRunService(
                new DefaultOperatorRegistry(), new ObjectMapper())
                .execute(compilation, wrongPurpose))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(WorldScenarioCompilationException.Code.INVALID_INPUT));
        assertThat(realCalls).hasValue(0);
    }

    @Test
    void changedGraphWithOriginalDeclaredTargetFailsBeforeEngine() {
        AtomicInteger realCalls = new AtomicInteger();
        Graph originalGraph = graph(realCalls, "logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract("logical.customer"));
        WorldScenarioCompilation compilation = compile(originalGraph, world);
        Graph changedGraph = graph("changed-graph", realCalls, "logical.customer");
        TestExecutionRequest request = new TestExecutionRequest(
                changedGraph, new GraphContext(Map.of("request", Map.of("type", "vip"))),
                compilation.bundle(), WorldScenarioRunService.GRAPH_CONTRACT_TEST,
                compilation.bundle().targetFingerprint(), TestExecutionRequest.FixtureSource.INLINE,
                Map.of(), false, null, null);

        assertThatThrownBy(() -> new WorldScenarioRunService(
                new DefaultOperatorRegistry(), new ObjectMapper())
                .execute(compilation, request))
                .isInstanceOfSatisfying(WorldScenarioCompilationException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(WorldScenarioCompilationException.Code.TARGET_DRIFT));
        assertThat(realCalls).hasValue(0);
    }

    @Test
    void standaloneTestRunCannotActivateC2aDenyBundle() {
        AtomicInteger realCalls = new AtomicInteger();
        Graph graph = graph(realCalls, "logical.customer");
        ResourceWorldModel world = world("provider-a", "v1", contract("logical.customer"));
        WorldScenarioCompilation compilation = compile(graph, world);
        TestExecutionRequest request = new TestExecutionRequest(
                graph, new GraphContext(Map.of("type", "vip")), compilation.bundle(),
                WorldScenarioRunService.GRAPH_CONTRACT_TEST,
                compilation.bundle().targetFingerprint(), TestExecutionRequest.FixtureSource.INLINE,
                Map.of(), false, null, null);

        TestExecutionResult result = new TestRunService(
                new DefaultOperatorRegistry(), new ObjectMapper(), null).execute(request);

        assertThat(result.passed()).isFalse();
        assertThat(realCalls).hasValue(0);
    }

    private static WorldScenarioCompilation compile(Graph graph, ResourceWorldModel world) {
        LogicalResourceContract contract = world.slices().getFirst().contract();
        WorldScenarioCompiler compiler = new WorldScenarioCompiler();
        Scenario scenario = new Scenario("scenario-a", "tenant-a", 1,
                new Scenario.TargetRef("GRAPH", graph.name(),
                        GraphArtifactFingerprint.of(new ObjectMapper(), graph)), world,
                Map.of("secret", "context-value"), Scenario.WorldStateInit.EMPTY,
                List.of(), List.of(Scenario.ContractDependency.of(contract)));
        return compiler.compile(scenario, world, graph, new DefaultOperatorRegistry(),
                Map.of(contract.contractId(), new WorldSliceSelection(
                        "provider-a", "v1", world.slices().getFirst().fingerprint())));
    }

    private static Graph graph(AtomicInteger realCalls, String contractId) {
        return graph("customer-graph", realCalls, contractId);
    }

    private static Graph graph(String graphName, AtomicInteger realCalls, String contractId) {
        Operator<Object, Object> real = (input, context) -> {
            realCalls.incrementAndGet();
            return input;
        };
        String tag = WorldScenarioCompiler.logicalContractTag(
                contractId, contract(contractId).contractFingerprint());
        GraphBuilder builder = new GraphBuilder(graphName);
        var first = builder.node("first", real).meta("tags", tag)
                .input((results, context) -> context.get("request"));
        return first.node("second", real).meta("tags", tag)
                .input((results, context) -> context.get("request")).build();
    }

    private static LogicalResourceContract contract(String id) {
        return new LogicalResourceContract(id, objectSchema("type", "string", true),
                objectSchema("result", "string", true), confirmed(Map.of("NONE", List.of("N/A"))));
    }

    private static ResourceWorldModel world(String provider, String version,
                                            LogicalResourceContract contract) {
        ResourceDesignContract design = new ResourceDesignContract(contract.contractId(),
                contract.contractId(), "Resource", "", List.of(), contract.inputShape(),
                contract.outputShape(), Map.of(), "ACTIVE");
        LogicalResourceBinding binding = LogicalResourceBinding.bind(provider, version, design,
                descriptor(contract.contractId()), contract);
        WorldSlice slice = WorldSlice.register(new WorldSlice.Registration(
                        "tenant-a", provider, version, contract.contractId(),
                        contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("customer-world.bloge", DSL),
                StateSpec.empty());
        return new ResourceWorldModel("customer-world", "tenant-a", 1, List.of(slice));
    }
}
