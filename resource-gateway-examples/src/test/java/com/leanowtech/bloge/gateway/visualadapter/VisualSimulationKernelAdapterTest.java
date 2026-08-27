package com.leanowtech.bloge.gateway.visualadapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.testing.runtime.ResourceFixtureRuntime;
import com.leanowtech.bloge.gateway.visual.simulation.NodeFixture;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSimulationPlan;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class VisualSimulationKernelAdapterTest {

    private final VisualSimulationKernelAdapter adapter =
            new VisualSimulationKernelAdapter(new ObjectMapper());

    @Test
    void executesOneSchemaStandin() {
        VisualDslRunResponse response = adapter.execute(plan(
                "graph single { node subject : customer.lookup }",
                Map.of("approved", true), "subject", List.of(
                        new VisualSimulationPlan.Standin(
                                "subject", "customer.lookup", Map.of("approved", true), null))));

        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).as("response=%s", response).isTrue();
        assertThat(response.output()).isEqualTo(Map.of("approved", true));
        assertThat(response.nodeFidelity()).containsEntry("subject", "OUTPUT_LEVEL");
    }

    @Test
    void highFidelityWithoutRuntimeFailsClosed() {
        VisualDslRunResponse response = adapter.execute(plan(
                "graph resource { node subject : httpResource }", Map.of(), "subject", List.of(
                        new VisualSimulationPlan.Standin("subject", "httpResource",
                                Map.of("resourceId", "profile", "rawBody", "{}", "statusCode", 200), null,
                                com.leanowtech.bloge.gateway.visual.simulation.NodeFixture.ResourceFidelity.PROTOCOL_DERIVED))));

        assertThat(response.success()).isFalse();
        assertThat(response.nodeFidelity()).doesNotContainEntry("subject", "PROTOCOL_DERIVED");
    }

    @Test
    void protocolFidelityUsesRealResponseProtocolAndProjectsEvidence() {
        VisualDslRunResponse response = resourceAdapter().execute(resourcePlan(
            NodeFixture.ResourceFidelity.PROTOCOL_DERIVED));

        assertThat(response.success()).as("response=%s", response).isTrue();
        assertThat(response.output()).isInstanceOfSatisfying(HttpResourceOutput.class,
                output -> assertThat(output.payload()).isEqualTo(Map.of("name", "Ada")));
        assertThat(response.nodeFidelity()).containsEntry("subject", "PROTOCOL_DERIVED");
    }

    @Test
    void transportFidelityUsesRealDescriptorMappingAndProjectsEvidence() {
        VisualDslRunResponse response = resourceAdapter().execute(resourcePlan(
            NodeFixture.ResourceFidelity.TRANSPORT_LEVEL));

        assertThat(response.success()).as("response=%s", response).isTrue();
        assertThat(response.output()).isInstanceOfSatisfying(HttpResourceOutput.class,
                output -> assertThat(output.payload()).isEqualTo(Map.of("name", "Ada")));
        assertThat(response.nodeFidelity()).containsEntry("subject", "TRANSPORT_LEVEL");
    }

    @Test
    void invalidRawEvidenceFailsClosedWithoutRequestedFidelity() {
        VisualDslRunResponse response = resourceAdapter().execute(resourcePlan(
                NodeFixture.ResourceFidelity.PROTOCOL_DERIVED,
                Map.of("resourceId", "profile", "statusCode", 99, "rawBody", "{}")));

        assertThat(response.success()).isFalse();
        assertThat(response.nodeFidelity()).doesNotContainEntry("subject", "PROTOCOL_DERIVED");
    }

    @Test
    void missingRawBodyFailsClosedWithoutRequestedFidelity() {
        VisualDslRunResponse response = resourceAdapter().execute(resourcePlan(
                NodeFixture.ResourceFidelity.PROTOCOL_DERIVED,
                Map.of("resourceId", "profile", "statusCode", 200)));

        assertThat(response.success()).isFalse();
        assertThat(response.nodeFidelity()).doesNotContainEntry("subject", "PROTOCOL_DERIVED");
    }

    @Test
    void executesPureBlogeTransformWithNoStandins() {
        VisualDslRunResponse response = adapter.execute(plan(
                """
                        graph primitive {
                          transform projected {
                            greeting = ctx.greeting
                          }
                        }
                        """,
                Map.of("greeting", "hello"), "projected", List.of()));

        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.output()).isEqualTo(Map.of("greeting", "hello"));
    }

    @Test
    void executesMultipleSchemaStandins() {
        VisualDslRunResponse response = adapter.execute(plan(
                "graph multi { node first : customer.lookup node second : order.lookup }",
                Map.of(), "second", List.of(
                        new VisualSimulationPlan.Standin("first", "customer.lookup", "customer", null),
                        new VisualSimulationPlan.Standin("second", "order.lookup", "order", null))));

        assertThat(response.success()).isTrue();
        assertThat(response.results()).containsEntry("first", "customer")
                .containsEntry("second", "order");
        assertThat(response.output()).isEqualTo("order");
        assertThat(response.errors()).doesNotContain("VISUAL_SIMULATION_PLACEHOLDER_INVOKED");
    }

    @Test
    void reusesOnePlaceholderForNodesWithTheSameRewrittenOperatorRef() {
        VisualDslRunResponse response = adapter.execute(plan(
                "graph shared { node first : shared.lookup node second : shared.lookup }",
                Map.of(), "second", List.of(
                        new VisualSimulationPlan.Standin("first", "shared.lookup", "first", null),
                        new VisualSimulationPlan.Standin("second", "shared.lookup", "second", null))));

        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.results()).containsEntry("first", "first")
                .containsEntry("second", "second");
        assertThat(response.errors()).doesNotContain("VISUAL_SIMULATION_PLACEHOLDER_INVOKED");
    }

    @Test
    void explicitNullStandinSucceedsWithoutInvokingPlaceholder() {
        VisualDslRunResponse response = adapter.execute(plan(
                "graph nullable { node subject : nullable.lookup }",
                Map.of(), "subject", List.of(
                        new VisualSimulationPlan.Standin(
                                "subject", "nullable.lookup", null, null))));

        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isTrue();
        assertThat(response.output()).isNull();
        assertThat(response.errors()).doesNotContain("VISUAL_SIMULATION_PLACEHOLDER_INVOKED");
    }

    @Test
    void acceptsExactExpectedInput() {
        VisualDslRunResponse response = adapter.execute(plan(
                """
                        graph expected {
                          node subject : customer.lookup {
                            input { request = ctx.request }
                          }
                        }
                        """,
                Map.of("request", "C-42"), "subject", List.of(
                        new VisualSimulationPlan.Standin(
                                "subject", "customer.lookup", Map.of("ok", true),
                                Map.of("request", "C-42")))));

        assertThat(response.success()).isTrue();
        assertThat(response.output()).isEqualTo(Map.of("ok", true));
    }

    @Test
    void rejectsExpectedInputMismatch() {
        VisualDslRunResponse response = adapter.execute(plan(
                """
                        graph mismatch {
                          node subject : customer.lookup {
                            input { request = ctx.request }
                          }
                        }
                        """,
                Map.of("request", "C-99"), "subject", List.of(
                        new VisualSimulationPlan.Standin(
                                "subject", "customer.lookup", Map.of("ok", true),
                                Map.of("request", "C-42")))));

        assertThat(response.compiled()).isTrue();
        assertThat(response.success()).isFalse();
        assertThat(response.errors()).contains("FIXTURE_UNMATCHED")
                .doesNotContain("VISUAL_SIMULATION_PLACEHOLDER_INVOKED");
    }

    @Test
    void malformedDslReturnsStableSanitizedCompileFailure() {
        VisualSimulationPlan malformed = plan(
                "graph broken { node subject : customer.lookup",
                Map.of(), "subject", List.of());

        VisualDslRunResponse first = adapter.execute(malformed);
        VisualDslRunResponse second = adapter.execute(malformed);

        assertThat(first.compiled()).isFalse();
        assertThat(first.success()).isFalse();
        assertThat(first.errors()).containsExactly("VISUAL_SIMULATION_COMPILE_FAILED");
        assertThat(second.errors()).isEqualTo(first.errors());
        assertThat(second.diagnostics()).isEqualTo(first.diagnostics());
    }

    @Test
    void repeatedExecutionKeepsSemanticOutputAndStatusStable() {
        VisualSimulationPlan plan = plan(
                "graph stable { node subject : customer.lookup }",
                Map.of(), "subject", List.of(
                        new VisualSimulationPlan.Standin(
                                "subject", "customer.lookup", Map.of("ok", true), null)));

        VisualDslRunResponse first = adapter.execute(plan);
        Map<String, Object> semantic = semantic(first);
        for (int attempt = 0; attempt < 9; attempt++) {
            assertThat(semantic(adapter.execute(plan))).isEqualTo(semantic);
        }
    }

    private static Map<String, Object> semantic(VisualDslRunResponse response) {
        return Map.of(
                "compiled", response.compiled(),
                "success", response.success(),
                "graphName", response.graphName(),
                "outputNode", response.outputNode(),
                "output", response.output(),
                "results", response.results(),
                "statusMap", response.statusMap(),
                "diagnostics", response.diagnostics(),
                "errors", response.errors());
    }

    private static VisualSimulationPlan plan(String dsl, Map<String, Object> context,
                                             String outputNode,
                                             List<VisualSimulationPlan.Standin> standins) {
        return new VisualSimulationPlan(dsl, context, outputNode, standins);
    }

    private static VisualSimulationKernelAdapter resourceAdapter() {
        MapRegistry registry = new MapRegistry();
        registry.put(new ResourceDescriptor("profile", "https://api.test/customers/{id}", "GET",
                Map.of("Accept", "application/json"), null, Duration.ofSeconds(3),
                new ParameterMapping(Map.of("id", "ctx.params.id"),
                        Map.of("view", "ctx.params.view"), null),
                new ResponseProtocol.BodyCode("code", Set.of(0), "message"), "data"));
        return new VisualSimulationKernelAdapter(new ObjectMapper(),
                new ResourceFixtureRuntime(registry, new BlgeExpressionEvaluator(), new ObjectMapper()));
    }

    private static VisualSimulationPlan resourcePlan(
            NodeFixture.ResourceFidelity fidelity) {
        return resourcePlan(fidelity, Map.of("resourceId", "profile", "statusCode", 200,
                "rawBody", "{\"code\":0,\"data\":{\"name\":\"Ada\"}}",
                "payload", Map.of("wrong", true), "success", true));
    }

    private static VisualSimulationPlan resourcePlan(
            NodeFixture.ResourceFidelity fidelity, Map<String, Object> output) {
        return plan("""
                        graph resource {
                          node subject : httpResource {
                            input {
                              resourceId = "profile"
                              params = { id: ctx.id, view: ctx.view }
                            }
                          }
                        }
                        """,
                Map.of("id", "C-42", "view", "full"), "subject", List.of(
                        new VisualSimulationPlan.Standin("subject", "httpResource",
                                output, null,
                                fidelity)));
    }

    private static final class MapRegistry implements ResourceRegistry {
        private final Map<String, ResourceDescriptor> descriptors = new LinkedHashMap<>();

        void put(ResourceDescriptor descriptor) {
            descriptors.put(descriptor.resourceId(), descriptor);
        }

        @Override
        public ResourceDescriptor resolve(String resourceId) {
            ResourceDescriptor descriptor = descriptors.get(resourceId);
            if (descriptor == null) {
                throw new IllegalArgumentException("Unknown resource: " + resourceId);
            }
            return descriptor;
        }

        @Override
        public boolean contains(String resourceId) {
            return descriptors.containsKey(resourceId);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return descriptors.values();
        }
    }
}
