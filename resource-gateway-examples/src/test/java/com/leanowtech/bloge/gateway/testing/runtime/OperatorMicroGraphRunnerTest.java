package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperatorMicroGraphRunnerTest {

    @Test
    void readOnlyBindingEarnsExecutableUnitByRunningItsRealLogic() {
        TestRunService service = new TestRunService(new DefaultOperatorRegistry(),
                new ObjectMapper(), null);
        OperatorMicroGraphRunner runner = new OperatorMicroGraphRunner(service);

        OperatorMicroGraphRunner.Result result = runner.execute(new OperatorMicroGraphRunner.Request(
                "customer.normalize", new UppercaseOperator(), "", "ada", null,
                "OPERATOR_UNIT_TEST", TestExecutionRequest.FixtureSource.INLINE));

        assertThat(result.classification())
                .isEqualTo(OperatorMicroGraphRunner.Classification.EXECUTABLE_UNIT);
        assertThat(result.execution().passed())
                .as("evidence: %s", result.execution().evidence()).isTrue();
        assertThat(result.execution().graphResult().getOutput("subject", String.class)).isEqualTo("ADA");
        assertThat(result.runtimeBindingFingerprint()).startsWith("sha256:");
    }

    @Test
    void undeclaredExternalPortRemainsOpaqueAndFailsClosed() {
        TestRunService service = new TestRunService(new DefaultOperatorRegistry(),
                new ObjectMapper(), null);
        OperatorMicroGraphRunner runner = new OperatorMicroGraphRunner(service);

        OperatorMicroGraphRunner.Result result = runner.execute(new OperatorMicroGraphRunner.Request(
                "legacy.external", new OpaqueExternalOperator(), "", Map.of(), null,
                "OPERATOR_UNIT_TEST", TestExecutionRequest.FixtureSource.INLINE));

        assertThat(result.classification())
                .isEqualTo(OperatorMicroGraphRunner.Classification.OPAQUE_RUNTIME);
        assertThat(result.execution().evidence().status())
                .isEqualTo(TestRunEvidence.Status.FIXTURE_UNMATCHED);
    }

    @Test
    void httpResourceRequiresTransportFixtureAndThenProducesCertifiableProtocolEvidence() {
        ObjectMapper mapper = new ObjectMapper();
        BlgeExpressionEvaluator evaluator = new BlgeExpressionEvaluator();
        OneResourceRegistry registry = new OneResourceRegistry(new ResourceDescriptor(
                "customer.get", "https://api.test/customers/{id}", "GET", Map.of(), null,
                Duration.ofSeconds(2), new ParameterMapping(Map.of("id", "ctx.params.id"),
                Map.of(), null), new ResponseProtocol.BodyCode("code", java.util.Set.of(0), "message"),
                "data"));
        ResourceFixtureRuntime resourceRuntime = new ResourceFixtureRuntime(registry, evaluator, mapper);
        TestRunService service = new TestRunService(new DefaultOperatorRegistry(), mapper, resourceRuntime);
        OperatorMicroGraphRunner runner = new OperatorMicroGraphRunner(service);
        HttpResourceOperator subject = new HttpResourceOperator(new HttpRequestOperator(), registry, evaluator,
                new UrlTemplateRenderer(), new PayloadExtractor(mapper), new ResponseValidator(evaluator));
        String target = "sha256:" + "c".repeat(64);
        FixtureRule rule = new FixtureRule(FixtureRule.SCHEMA_VERSION, "transport-response",
                FixtureRule.Selector.resource("customer.get"),
                FixtureRule.Behavior.protocolResponse(
                        "{\"code\":0,\"data\":{\"id\":\"C-7\"}}", 200, Map.of(),
                        FixtureRule.DoubleBoundary.TRANSPORT),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        FixtureBundle bundle = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "http-fixture", 1,
                target, "INTERNAL", null, null, List.of(rule), List.of(), Map.of());

        OperatorMicroGraphRunner.Result result = runner.execute(new OperatorMicroGraphRunner.Request(
                "httpResource", subject, target,
                new HttpResourceInput("customer.get", Map.of("id", "C-7")), bundle,
                "OPERATOR_UNIT_TEST", TestExecutionRequest.FixtureSource.STORED));

        assertThat(result.classification())
                .isEqualTo(OperatorMicroGraphRunner.Classification.EXECUTABLE_UNIT);
        assertThat(result.execution().passed())
                .as("evidence: %s", result.execution().evidence()).isTrue();
        assertThat(result.execution().evidence().evidenceClass())
                .isEqualTo(TestRunEvidence.EvidenceClass.CERTIFIABLE);
        assertThat(result.execution().evidence().nodeTrace()).singleElement().satisfies(trace -> {
            assertThat(trace.fidelity()).isEqualTo("TRANSPORT_LEVEL");
            assertThat(trace.output()).hasFieldOrPropertyWithValue("payload", Map.of("id", "C-7"));
        });
    }

    private static final class UppercaseOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) {
            return String.valueOf(input).toUpperCase();
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

    private static final class OpaqueExternalOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) {
            throw new AssertionError("fail-closed plan must not execute opaque external binding");
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.EXTERNAL_CALL;
        }
    }

    private record OneResourceRegistry(ResourceDescriptor descriptor) implements
            com.leanowtech.bloge.gateway.resource.ResourceRegistry {
        @Override
        public ResourceDescriptor resolve(String resourceId) {
            if (!descriptor.resourceId().equals(resourceId)) throw new IllegalArgumentException(resourceId);
            return descriptor;
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
}
