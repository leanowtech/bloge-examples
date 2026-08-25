package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.planning.BoundedRegexPolicy;
import com.leanowtech.bloge.gateway.testing.planning.ControlPlanRejectedException;
import com.leanowtech.bloge.gateway.testing.planning.SafetyPreflight;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSimulationPlan;
import com.leanowtech.bloge.gateway.visualadapter.VisualSimulationKernelAdapter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeModel;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class Stage0Exit06SecurityProofTest {

    private static final String TARGET = "sha256:" + "0".repeat(64);

    @Test
    @Timeout(10)
    void descriptorTransportIsZeroNetworkThroughTestRunService() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = countingServer(requests);
        try {
            server.start();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort()
                    + "/customers/{id}";
            ResourceDescriptor descriptor = new ResourceDescriptor(
                    "counted.customer", endpoint, "GET", Map.of(), null,
                    Duration.ofSeconds(2),
                    new ParameterMapping(Map.of("id", "ctx.params.id"), Map.of(), null),
                    new ResponseProtocol.HttpStatus(), "data");
            ResourceFixtureRuntime runtime = new ResourceFixtureRuntime(
                    new SingleDescriptorRegistry(descriptor), new BlgeExpressionEvaluator(),
                    new ObjectMapper());
            FixtureRule transport = transportRule("counted.customer");
            TestExecutionResult result = new TestRunService(
                    new DefaultOperatorRegistry(), new ObjectMapper(), runtime).execute(
                            new TestExecutionRequest(
                                    resourceGraph(),
                                    new GraphContext(Map.of("input", new HttpResourceInput(
                                            "counted.customer", Map.of("id", "C-7")))),
                                    bundle(transport), "GRAPH_CONTRACT_TEST", TARGET,
                                    TestExecutionRequest.FixtureSource.INLINE, Map.of()));

            assertThat(result.passed()).isTrue();
            assertThat(result.plan().resolvedSites()).singleElement().satisfies(site -> {
                assertThat(site.boundary()).isEqualTo(FixtureRule.DoubleBoundary.TRANSPORT);
                assertThat(site.fidelity()).isEqualTo("TRANSPORT_LEVEL");
            });
            assertThat(result.evidence().nodeTrace()).singleElement().satisfies(trace -> {
                assertThat(trace.fidelity()).isEqualTo("TRANSPORT_LEVEL");
                assertThat(trace.status()).isEqualTo("MOCKED");
            });
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void visualHttpResourceStandinIsZeroNetworkOnDefaultKernelPath() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = countingServer(requests);
        try {
            server.start();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/visual";
            VisualDslRunResponse response = new VisualSimulationKernelAdapter(new ObjectMapper())
                    .execute(new VisualSimulationPlan(
                            """
                                    graph visualEgress {
                                      node fetch : httpRequest {
                                        input { endpoint = ctx.endpoint }
                                      }
                                    }
                                    """,
                            Map.of("endpoint", endpoint), "fetch", List.of(
                                    new VisualSimulationPlan.Standin(
                                            "fetch", "httpRequest", Map.of("url", endpoint),
                                            Map.of("endpoint", endpoint)))));

            assertThat(response.compiled())
                    .withFailMessage("compiled=%s diagnostics=%s errors=%s",
                            response.compiled(), response.diagnostics(), response.errors())
                    .isTrue();
            assertThat(response.success()).isTrue();
            assertThat(response.output()).isEqualTo(Map.of("url", endpoint));
            assertThat(response.errors()).isEmpty();
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @Timeout(10)
    void redosMatrixIsRejectedBeforeMatchingAndSafePatternsStillMatch() {
        List<String> rejected = List.of(
                "(a+)+$", "((a|aa)+)+$", "a(?=a)", "(a)\\1",
                "x".repeat(BoundedRegexPolicy.MAX_PATTERN_LENGTH + 1), "[a-");
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int index = 0; index < rejected.size(); index++) {
                FixtureRule rule = regexRule("/value", rejected.get(index));
                assertThat(BoundedRegexPolicy.rejectionReason(rejected.get(index))).isNotBlank();
                assertThatThrownBy(() -> new SafetyPreflight().validate(
                        bundle(rule), "GRAPH_CONTRACT_TEST", TARGET))
                        .isInstanceOf(ControlPlanRejectedException.class)
                        .hasMessageContaining("boundedRegex");
                assertThat(new FixtureMatcher(new ObjectMapper()).matches(
                        rule, Map.of("value", "a".repeat(256)), "", 1, 1)).isFalse();
            }
        });

        FixtureRule safe = regexRule("/value", "^[A-Z][A-Z0-9_]*$");
        assertThat(new FixtureMatcher(new ObjectMapper()).matches(
                safe, Map.of("value", "CUSTOMER_42"), "", 1, 1)).isTrue();

        FixtureRule overlongCandidate = regexRule("/value", "a+");
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThat(
                new FixtureMatcher(new ObjectMapper()).matches(
                        overlongCandidate,
                        Map.of("value", "a".repeat(BoundedRegexPolicy.MAX_INPUT_LENGTH + 1)),
                        "", 1, 1)).isFalse());
    }

    @Test
    void stageZeroSimulationTransportClassesHaveNoProductionNetworkConstruction() throws IOException {
        assertThat(forbiddenNetworkReferences(VisualSimulationKernelAdapter.class)).isEmpty();
        assertThat(forbiddenNetworkReferences(StubHttpRequestOperator.class)).isEmpty();
        assertThat(invocations(StubHttpRequestOperator.class))
                .doesNotContain("com/leanowtech/bloge/operators/http/HttpRequestOperator.<init>");

        List<String> runtimeAllocations = allocations(ResourceFixtureRuntime.class);
        assertThat(runtimeAllocations)
                .contains("com/leanowtech/bloge/gateway/testing/runtime/StubHttpRequestOperator",
                        "com/leanowtech/bloge/gateway/operator/HttpResourceOperator")
                .doesNotContain("com/leanowtech/bloge/operators/http/HttpRequestOperator");
    }

    private static HttpServer countingServer(AtomicInteger requests) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "unexpected-network-request".getBytes();
            exchange.sendResponseHeaders(500, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        return server;
    }

    private static Graph resourceGraph() {
        Graph graph = new GraphBuilder("stage0-resource-transport")
                .node("subject", new NoopExternalOperator())
                .input((results, context) -> context.get("input"))
                .build();
        var node = graph.nodes().get("subject").toBuilder().operatorRef("httpResource").build();
        return new Graph(graph.name(), Map.of("subject", node), graph.edges(), graph.sourceNodes(),
                graph.terminalNodes(), graph.schemaValidationLevel(), Map.of(),
                graph.declaredInputSchema(), graph.declaredOutputSchema(), graph.sagaConfig(),
                graph.definitionSource(), graph.streamingOutputNodeId(), graph.streamingInputs());
    }

    private static FixtureRule transportRule(String resourceId) {
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, "transport",
                FixtureRule.Selector.resource(resourceId),
                FixtureRule.Behavior.protocolResponse("{\"data\":{\"id\":\"C-7\"}}", 200,
                        Map.of(), FixtureRule.DoubleBoundary.TRANSPORT),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static FixtureRule regexRule(String path, String expression) {
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, "regex", FixtureRule.Selector.node("subject")
                .matching(new FixtureRule.Match(null, Map.of(), List.of(), List.of(), Map.of(), "",
                        Map.of(path, expression))), FixtureRule.Behavior.returning("ok"),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static FixtureBundle bundle(FixtureRule... rules) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "stage0-exit-06", 1, TARGET,
                "INTERNAL", null, null, List.of(rules), List.of(), Map.of());
    }

    private static List<String> forbiddenNetworkReferences(Class<?> type) throws IOException {
        return Stream.concat(invocations(type).stream(), allocations(type).stream())
                .filter(reference -> reference.startsWith("java/net/http/HttpClient")
                        || reference.equals("java/net/URL.openConnection")
                        || reference.startsWith("java/net/Socket"))
                .toList();
    }

    private static List<String> invocations(Class<?> type) throws IOException {
        return code(type).flatMap(method -> method.code()
                        .map(CodeModel::elementStream).orElseGet(Stream::empty))
                .filter(InvokeInstruction.class::isInstance)
                .map(InvokeInstruction.class::cast)
                .map(instruction -> instruction.owner().asInternalName()
                        + "." + instruction.name().stringValue())
                .toList();
    }

    private static List<String> allocations(Class<?> type) throws IOException {
        return code(type).flatMap(method -> method.code()
                        .map(CodeModel::elementStream).orElseGet(Stream::empty))
                .filter(NewObjectInstruction.class::isInstance)
                .map(NewObjectInstruction.class::cast)
                .map(instruction -> instruction.className().asInternalName())
                .toList();
    }

    private static Stream<java.lang.classfile.MethodModel> code(Class<?> type) throws IOException {
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return ClassFile.of().parse(input.readAllBytes()).methods().stream();
        }
    }

    private record SingleDescriptorRegistry(ResourceDescriptor descriptor) implements ResourceRegistry {
        @Override
        public ResourceDescriptor resolve(String resourceId) {
            if (descriptor.resourceId().equals(resourceId)) {
                return descriptor;
            }
            throw new IllegalArgumentException("Unknown resource: " + resourceId);
        }

        @Override
        public boolean contains(String resourceId) {
            return descriptor.resourceId().equals(resourceId);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.of(descriptor);
        }
    }

    private static final class NoopExternalOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext context) {
            throw new AssertionError("real resource binding must not execute");
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.EXTERNAL_CALL;
        }
    }
}
