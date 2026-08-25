package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.gateway.exception.ResourceCallException;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResourceFixtureRuntimeTest {

    private MapRegistry registry;
    private BlgeExpressionEvaluator evaluator;
    private ResourceFixtureRuntime runtime;

    @BeforeEach
    void setUp() {
        registry = new MapRegistry();
        evaluator = new BlgeExpressionEvaluator();
        runtime = new ResourceFixtureRuntime(registry, evaluator, new ObjectMapper());
    }

    @Test
    void sameRawBodyIsDerivedByActualBodyCodeAndBodyFlagProtocols() throws Exception {
        String raw = """
                {"code":0,"data":{"customerId":"C-1"}}
                """;
        registry.put(descriptor("body-code", new ResponseProtocol.BodyCode(
                "code", Set.of(0), "message"), "data"));
        registry.put(descriptor("body-flag", new ResponseProtocol.BodyFlag("success"), "data"));
        FixtureRule.Behavior fixture = FixtureRule.Behavior.protocolResponse(raw, 200, Map.of(),
                FixtureRule.DoubleBoundary.NODE);

        var success = runtime.execute(fixture,
                new HttpResourceInput("body-code", Map.of()), context());

        assertThat(success.success()).isTrue();
        assertThat(success.payload()).isEqualTo(Map.of("customerId", "C-1"));
        assertThat(success.rawBody()).contains("customerId");
        assertThatThrownBy(() -> runtime.execute(fixture,
                new HttpResourceInput("body-flag", Map.of()), context()))
                .isInstanceOf(ResourceCallException.class);
    }

    @Test
    void explicitStatusCodeProtocolOverridesGenericHttpTwoHundredConvention() throws Exception {
        registry.put(descriptor("accepted", new ResponseProtocol.StatusCodes(Set.of(202)), null));
        FixtureRule.Behavior accepted = FixtureRule.Behavior.protocolResponse("{\"queued\":true}",
                202, Map.of("X-Trace", "t-1"), FixtureRule.DoubleBoundary.TRANSPORT);

        var output = runtime.execute(accepted,
                new HttpResourceInput("accepted", Map.of()), context());

        assertThat(output.statusCode()).isEqualTo(202);
        assertThat(output.payload()).isEqualTo(Map.of("queued", true));
    }

    @Test
    void httpStatusFailureCannotBeOverriddenByFixtureClaim() {
        registry.put(descriptor("http", new ResponseProtocol.HttpStatus(), null));
        FixtureRule.Behavior failed = FixtureRule.Behavior.protocolResponse("{\"ok\":true}",
                503, Map.of(), FixtureRule.DoubleBoundary.NODE);

        assertThatThrownBy(() -> runtime.execute(failed,
                new HttpResourceInput("http", Map.of()), context()))
                .isInstanceOf(ResourceCallException.class)
                .hasMessageContaining("503");
    }

    @Test
    void emptyBodyStillUsesProtocolDerivedPathWhenStatusCodeIsExplicit() throws Exception {
        registry.put(descriptor("no-content", new ResponseProtocol.HttpStatus(), null));
        FixtureRule.Behavior noContent = FixtureRule.Behavior.protocolResponse("", 204, Map.of(),
                FixtureRule.DoubleBoundary.TRANSPORT);

        var output = runtime.execute(noContent,
                new HttpResourceInput("no-content", Map.of()), context());

        assertThat(output.success()).isTrue();
        assertThat(output.statusCode()).isEqualTo(204);
        assertThat(output.payload()).isNull();
    }

    @Test
    void productizedTransportStubObservesRenderedRequestWhileRealOperatorParsesResponse() throws Exception {
        registry.put(new ResourceDescriptor("profile", "https://api.test/customers/{id}", "GET",
                Map.of("Accept", "application/json"), null, Duration.ofSeconds(3),
                new ParameterMapping(Map.of("id", "ctx.params.id"),
                        Map.of("view", "ctx.params.view"), null),
                new ResponseProtocol.HttpStatus(), "data"));
        FixtureRule.Behavior behavior = FixtureRule.Behavior.protocolResponse(
                "{\"data\":{\"name\":\"Ada\"}}", 200, Map.of(),
                FixtureRule.DoubleBoundary.TRANSPORT);

        var result = runtime.executeDescriptorTransportObserved(behavior,
                new HttpResourceInput("profile",
                Map.of("id", "C-42", "view", "full")), context());

        assertThat(result.request().url())
                .isEqualTo("https://api.test/customers/C-42?view=full");
        assertThat(result.request().headers()).containsEntry("Accept", "application/json");
        assertThat(result.output().payload()).isEqualTo(Map.of("name", "Ada"));
    }

    @Test
    void transportMappingUsesDescriptorDefaultsDynamicHeadersOverridesAndBody() throws Exception {
        registry.put(new ResourceDescriptor("customer.update", "https://api.test/customers/{id}", "GET",
                Map.of("Accept", "application/json", "X-Default", "descriptor",
                        "Content-Type", "application/json"), null, Duration.ofSeconds(3),
                new ParameterMapping(
                        Map.of("id", "ctx.params.id"),
                        Map.of("view", "ctx.params.view"),
                        Map.of("X-Dynamic", "ctx.params.dynamic"),
                        Map.of("SESSION", "ctx.params.session"),
                        "ctx.params.body"),
                new ResponseProtocol.HttpStatus(), "data"));
        FixtureRule.Behavior behavior = FixtureRule.Behavior.protocolResponse(
                "{\"data\":{\"updated\":true}}", 200, Map.of(),
                FixtureRule.DoubleBoundary.TRANSPORT);
        HttpResourceInput input = new HttpResourceInput("customer.update",
                Map.of("id", "C-42", "view", "full", "dynamic", "mapped",
                        "session", "a b", "body", Map.of("active", true)),
                Map.of("X-Default", "override", "X-Override", "per-call"),
                null, Duration.ofSeconds(1));

        ResourceFixtureRuntime.DescriptorTransportResult result =
                runtime.executeDescriptorTransportObserved(behavior, input, context());

        assertThat(result.request().url())
                .isEqualTo("https://api.test/customers/C-42?view=full");
        assertThat(result.request().method()).isEqualTo("GET");
        assertThat(result.request().headers())
                .containsEntry("Accept", "application/json")
                .containsEntry("Content-Type", "application/json")
                .containsEntry("X-Default", "override")
                .containsEntry("X-Dynamic", "mapped")
                .containsEntry("X-Override", "per-call")
                .containsEntry("Cookie", "SESSION=a%20b");
        assertThat(result.request().body()).isEqualTo(Map.of("active", true));
        assertThat(result.request().timeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(result.output().payload()).isEqualTo(Map.of("updated", true));
    }

    @Test
    void descriptorProtocolSkipsRequestMappingButRunsRealProtocolAndExtraction() {
        registry.put(new ResourceDescriptor("profile", "https://api.test/customers/{id}", "GET",
                Map.of(), null, Duration.ofSeconds(3),
                new ParameterMapping(Map.of("id", "ctx.params.requiredId"),
                        Map.of(), null),
                new ResponseProtocol.BodyCode("code", Set.of(0), "message"), "data"));
        FixtureRule.Behavior behavior = FixtureRule.Behavior.protocolResponse(
                "{\"code\":0,\"data\":{\"name\":\"Ada\"}}", 200, Map.of(),
                FixtureRule.DoubleBoundary.NODE);

        var output = runtime.executeDescriptorProtocol(
                behavior, new HttpResourceInput("profile", Map.of()), context());

        assertThat(output.payload()).isEqualTo(Map.of("name", "Ada"));
        assertThat(output.statusCode()).isEqualTo(200);
    }

    @Test
    void descriptorProtocolMatchesProductionBlgeExpressionPayloadExtraction() throws Exception {
        registry.put(new ResourceDescriptor("expression-profile",
                "https://api.test/customers/{id}", "GET", Map.of(), null,
                Duration.ofSeconds(3),
                new ParameterMapping(Map.of("id", "ctx.params.requiredId"), Map.of(), null),
                new ResponseProtocol.BlgeExpression(
                        "ctx.statusCode == 200 && ctx.body.result.profile != null",
                        null, "ctx.body.result.profile"),
                "result.fallback"));
        String rawBody = """
                {"result":{"profile":{"tier":"gold"},"fallback":"wrong"}}
                """;
        FixtureRule.Behavior protocol = FixtureRule.Behavior.protocolResponse(
                rawBody, 200, Map.of(), FixtureRule.DoubleBoundary.NODE);
        FixtureRule.Behavior transport = FixtureRule.Behavior.protocolResponse(
                rawBody, 200, Map.of(), FixtureRule.DoubleBoundary.TRANSPORT);

        var protocolOutput = runtime.executeDescriptorProtocol(protocol,
                new HttpResourceInput("expression-profile", Map.of()), context());
        var productionOutput = runtime.executeDescriptorTransport(transport,
                new HttpResourceInput("expression-profile", Map.of("requiredId", "C-7")),
                context());

        assertThat(protocolOutput.payload()).isEqualTo(Map.of("tier", "gold"));
        assertThat(protocolOutput.payload()).isEqualTo(productionOutput.payload());
    }

    @Test
    void explicitDescriptorEntrypointsRejectBoundaryModeMismatch() {
        registry.put(descriptor("profile", new ResponseProtocol.HttpStatus(), null));
        FixtureRule.Behavior protocol = FixtureRule.Behavior.protocolResponse(
                "{}", 200, Map.of(), FixtureRule.DoubleBoundary.NODE);

        assertThatThrownBy(() -> runtime.executeDescriptorTransport(
                protocol, new HttpResourceInput("profile", Map.of()), context()))
                .isInstanceOfSatisfying(TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTROL_PLAN_EXECUTION_MODE_MISMATCH"));
    }

    private static OperatorContext context() {
        return new OperatorContext("resource", "test", new GraphContext(), 0);
    }

    private static ResourceDescriptor descriptor(String id, ResponseProtocol protocol, String payloadPath) {
        return new ResourceDescriptor(id, "https://api.test/" + id, "GET", Map.of(), null,
                Duration.ofSeconds(2), ParameterMapping.empty(), protocol, payloadPath);
    }

    static final class MapRegistry implements ResourceRegistry {
        private final Map<String, ResourceDescriptor> descriptors = new LinkedHashMap<>();

        void put(ResourceDescriptor descriptor) {
            descriptors.put(descriptor.resourceId(), descriptor);
        }

        @Override
        public ResourceDescriptor resolve(String resourceId) {
            ResourceDescriptor descriptor = descriptors.get(resourceId);
            if (descriptor == null) throw new IllegalArgumentException("Unknown resource: " + resourceId);
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
