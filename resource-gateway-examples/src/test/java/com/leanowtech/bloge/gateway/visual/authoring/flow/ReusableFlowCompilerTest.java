package com.leanowtech.bloge.gateway.visual.authoring.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReusableFlowCompilerTest {
    private static final String A = "sha256:" + "a".repeat(64);
    private static final String B = "sha256:" + "b".repeat(64);
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant-a", "project-a", "test");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void compilesTwoApiResourcesIntoOneDeterministicDag() {
        ReusableFlowCompiler compiler = new ReusableFlowCompiler(catalog());

        CompiledReusableFlow compiled = compiler.compile(SCOPE, command(List.of(
                node("profile", ref("customer.profile", 3, A), List.of(
                        input("$.customerId", new ReusableFlowCommand.MappingSource.FlowInput("$.customerId")))),
                node("orders", ref("orders.list", 2, B), List.of(
                        input("$.customerId", new ReusableFlowCommand.MappingSource.FlowInput("$.customerId")),
                        input("$.tier", new ReusableFlowCommand.MappingSource.NodeOutput(
                                "profile", "$.tier")))))));

        assertThat(compiled.topologicalNodeIds()).containsExactly("profile", "orders");
        assertThat(compiled.dependencies()).containsEntry("orders", List.of("profile"));
        assertThat(compiled.nodes().get("profile").reference().id()).isEqualTo("customer.profile");
        assertThat(compiled.command()).isEqualTo(command(List.of(
                node("profile", ref("customer.profile", 3, A), List.of(
                        input("$.customerId", new ReusableFlowCommand.MappingSource.FlowInput("$.customerId")))),
                node("orders", ref("orders.list", 2, B), List.of(
                        input("$.customerId", new ReusableFlowCommand.MappingSource.FlowInput("$.customerId")),
                        input("$.tier", new ReusableFlowCommand.MappingSource.NodeOutput(
                                "profile", "$.tier")))))));
    }

    @Test
    void rejectsCyclesUnknownReferencesAndIncompatibleMappings() {
        ReusableFlowCompiler compiler = new ReusableFlowCompiler(catalog());
        ReusableFlowCommand.Node profile = node("profile", ref("customer.profile", 3, A), List.of(
                input("$.customerId", new ReusableFlowCommand.MappingSource.NodeOutput("orders", "$.id"))));
        ReusableFlowCommand.Node orders = node("orders", ref("orders.list", 2, B), List.of(
                input("$.customerId", new ReusableFlowCommand.MappingSource.NodeOutput("profile", "$.tier")),
                input("$.tier", new ReusableFlowCommand.MappingSource.NodeOutput("profile", "$.tier"))));
        assertThatThrownBy(() -> compiler.compile(SCOPE, command(List.of(profile, orders))))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.CYCLE);

        ReusableFlowCommand unknown = command(List.of(node("profile",
                ref("missing", 1, A), List.of(input("$.customerId",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.customerId"))))));
        assertThatThrownBy(() -> compiler.compile(SCOPE, unknown))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.DEPENDENCY_NOT_FOUND);

        ReusableFlowCommand incompatible = command(List.of(node("profile",
                ref("customer.profile", 3, A), List.of(input("$.customerId",
                        new ReusableFlowCommand.MappingSource.Constant(JSON.valueToTree(42)))))));
        assertThatThrownBy(() -> compiler.compile(SCOPE, incompatible))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.SCHEMA_INCOMPATIBLE);
    }

    @Test
    void rejectsMissingRequiredInputsDuplicateTargetsAndLayoutDrift() {
        ReusableFlowCompiler compiler = new ReusableFlowCompiler(catalog());
        ReusableFlowCommand.Node missing = node("profile", ref("customer.profile", 3, A), List.of());
        assertThatThrownBy(() -> compiler.compile(SCOPE, command(List.of(missing))))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.MAPPING_INVALID);

        ReusableFlowCommand.Node duplicate = node("profile", ref("customer.profile", 3, A), List.of(
                input("$.customerId", new ReusableFlowCommand.MappingSource.FlowInput("$.customerId")),
                input("$.customerId", new ReusableFlowCommand.MappingSource.FlowInput("$.customerId"))));
        assertThatThrownBy(() -> compiler.compile(SCOPE, command(List.of(duplicate))))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.MAPPING_INVALID);

        ReusableFlowCommand drift = command(List.of(node("profile", ref("customer.profile", 3, A), List.of(
                input("$.customerId", new ReusableFlowCommand.MappingSource.FlowInput("$.customerId"))))));
        drift = new ReusableFlowCommand(drift.schemaVersion(), new ReusableFlowCommand.Flow(
                drift.flow().displayName(), drift.flow().kind(), drift.flow().description(),
                drift.flow().contract(), drift.flow().graph(), new ReusableFlowCommand.Layout(Map.of())));
        ReusableFlowCommand finalDrift = drift;
        assertThatThrownBy(() -> compiler.compile(SCOPE, finalDrift))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.LAYOUT_INVALID);
    }

    @Test
    void rejectsCatalogCoordinateDriftAndKeepsResolvedSchemasImmutable() {
        ComposableDefinition wrong = definition(ref("customer.profile", 4, A),
                object(Map.of("customerId", Map.of("type", "string")), List.of("customerId")),
                object(Map.of("tier", Map.of("type", "string")), List.of("tier")));
        ReusableFlowCompiler drifted = new ReusableFlowCompiler((scope, reference) -> Optional.of(wrong));
        ReusableFlowCommand authored = command(List.of(node("profile", ref("customer.profile", 3, A), List.of(
                input("$.customerId", new ReusableFlowCommand.MappingSource.FlowInput("$.customerId"))))));
        assertThatThrownBy(() -> drifted.compile(SCOPE, authored))
                .isInstanceOf(ReusableFlowFailure.class)
                .extracting(value -> ((ReusableFlowFailure) value).code())
                .isEqualTo(ReusableFlowFailure.Code.DEPENDENCY_DRIFT);

        ComposableDefinition definition = catalog().resolve(SCOPE, ref("customer.profile", 3, A)).orElseThrow();
        definition.input().schema().put("type", "string");
        assertThat(definition.input().schema()).containsEntry("type", "object");
    }

    private static ComposableCatalog catalog() {
        Map<String, ComposableDefinition> resources = Map.of(
                "customer.profile", definition(ref("customer.profile", 3, A),
                        object(Map.of("customerId", Map.of("type", "string")), List.of("customerId")),
                        object(Map.of("tier", Map.of("type", "string")), List.of("tier"))),
                "orders.list", definition(ref("orders.list", 2, B),
                        object(Map.of("customerId", Map.of("type", "string"),
                                "tier", Map.of("type", "string")), List.of("customerId", "tier")),
                        object(Map.of("id", Map.of("type", "string")), List.of("id"))));
        return (scope, reference) -> reference instanceof ReusableFlowCommand.ComposableRef.ApiResource resource
                ? Optional.ofNullable(resources.get(resource.resourceId())) : Optional.empty();
    }

    private static ReusableFlowCommand command(List<ReusableFlowCommand.Node> nodes) {
        Map<String, ReusableFlowCommand.Position> positions = new java.util.LinkedHashMap<>();
        for (int index = 0; index < nodes.size(); index++) {
            positions.put(nodes.get(index).nodeId(), new ReusableFlowCommand.Position(index * 200, 120));
        }
        ReusableFlowCommand.Flow flow = new ReusableFlowCommand.Flow("Customer orders",
                ReusableFlowCommand.Kind.TOOL, "Load profile and orders",
                new ReusableFlowCommand.Contract(
                        object(Map.of("customerId", Map.of("type", "string")), List.of("customerId")),
                        object(Map.of("id", Map.of("type", "string")), List.of("id"))),
                new ReusableFlowCommand.Graph(nodes,
                        new ReusableFlowCommand.Output(nodes.getLast().nodeId(), "$")),
                new ReusableFlowCommand.Layout(positions));
        return new ReusableFlowCommand(ReusableFlowCommand.SCHEMA_VERSION, flow);
    }

    private static ReusableFlowCommand.Node node(String nodeId,
                                                  ReusableFlowCommand.ComposableRef reference,
                                                  List<ReusableFlowCommand.Input> inputs) {
        return new ReusableFlowCommand.Node(nodeId, nodeId, reference, inputs);
    }

    private static ReusableFlowCommand.Input input(String target,
                                                    ReusableFlowCommand.MappingSource source) {
        return new ReusableFlowCommand.Input(target, source);
    }

    private static ReusableFlowCommand.ComposableRef.ApiResource ref(
            String resourceId, int revision, String fingerprint) {
        return new ReusableFlowCommand.ComposableRef.ApiResource(resourceId, revision, fingerprint);
    }

    private static ComposableDefinition definition(ReusableFlowCommand.ComposableRef.ApiResource reference,
                                                   SchemaEnvelope input, SchemaEnvelope output) {
        return new ComposableDefinition(reference, input, output);
    }

    private static SchemaEnvelope object(Map<String, Object> properties, List<String> required) {
        return SchemaEnvelope.object(properties, required);
    }
}
