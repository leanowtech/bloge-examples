package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionMode;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionModeHints;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorMicroGraphRunnerTest {

    @Test
    void schemaStandinReturnsFrozenOutput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Operator<Object, Object> real = new OpaqueExternalOperator();
        Graph graph = new GraphBuilder("schema-standin").node("subject", real).build();
        NodeSpec node = graph.nodes().get("subject").toBuilder()
                .operatorRef("fixture.operator").build();
        FixtureRule rule = new FixtureRule(FixtureRule.SCHEMA_VERSION, "standin-output",
                FixtureRule.Selector.node("subject"), FixtureRule.Behavior.returning("standin"),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        InvocationSite site = new InvocationSite(InvocationSite.SCHEMA_VERSION, "target",
                "/root", "subject", "fixture.operator", "", "", "binding",
                InvocationSite.InvocationKind.PRIMARY, null, "", null);
        CompiledExecutionControl.ResolvedControl control =
                new CompiledExecutionControl.ResolvedControl(site, List.of(rule), false,
                        CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                                .SELECTOR_SPECIFICITY,
                        List.of(), Map.of(rule.ruleId(), ExecutionMode.SCHEMA_STANDIN));
        InvocationRecorder recorder = new InvocationRecorder(mapper);
        InvocationRecorder.InvocationBinding binding = recorder.bind(site, new GraphContext());
        Operator<Object, Object> standin = new TestDoubleFactory(mapper, null).create(
                node, binding, control, real, recorder, ResolvedReplayPayloads.empty(),
                MirrorResolutionObserver.noop());

        Object output = standin.execute("input", new OperatorContext(
                "subject", "test", new GraphContext(), 0));

        assertThat(output).isEqualTo("standin");
    }

    @Test
    void schemaStandinNeverCallsRealOperator() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger realCalls = new AtomicInteger();
        Operator<Object, Object> real = new CountingExternalOperator(realCalls);
        Graph graph = new GraphBuilder("schema-standin").node("subject", real).build();
        NodeSpec node = graph.nodes().get("subject").toBuilder()
                .operatorRef("fixture.operator").build();
        FixtureRule rule = new FixtureRule(FixtureRule.SCHEMA_VERSION, "standin-output",
                FixtureRule.Selector.node("subject"), FixtureRule.Behavior.returning(null),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        InvocationSite site = new InvocationSite(InvocationSite.SCHEMA_VERSION, "target",
                "/root", "subject", "fixture.operator", "", "", "binding",
                InvocationSite.InvocationKind.PRIMARY, null, "", null);
        CompiledExecutionControl.ResolvedControl control =
                new CompiledExecutionControl.ResolvedControl(site, List.of(rule), false,
                        CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                                .SELECTOR_SPECIFICITY,
                        List.of(), Map.of(rule.ruleId(), ExecutionMode.SCHEMA_STANDIN));
        InvocationRecorder recorder = new InvocationRecorder(mapper);
        Operator<Object, Object> standin = new TestDoubleFactory(mapper, null).create(
                node, recorder.bind(site, new GraphContext()), control, real, recorder,
                ResolvedReplayPayloads.empty(), MirrorResolutionObserver.noop());

        Object output = standin.execute("input", new OperatorContext(
                "subject", "test", new GraphContext(), 0));

        assertThat(output).isNull();
        assertThat(realCalls).hasValue(0);
    }

    @Test
    void explicitSchemaStandinEvidenceIsExploratoryAndSchemaDerived() {
        ObjectMapper mapper = new ObjectMapper();
        TestRunService service = new TestRunService(new DefaultOperatorRegistry(), mapper, null);
        Graph graph = new GraphBuilder("schema-standin")
                .node("subject", new OpaqueExternalOperator()).build();
        FixtureRule rule = new FixtureRule(FixtureRule.SCHEMA_VERSION, "standin-output",
                FixtureRule.Selector.node("subject"), FixtureRule.Behavior.returning("standin"),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        String target = "sha256:" + "e".repeat(64);
        FixtureBundle bundle = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "schema-standin", 1,
                target, "INTERNAL", null, null, List.of(rule), List.of(), Map.of());
        CompiledExecutionControl compiled = new ExecutionControlCompiler(
                new DefaultOperatorRegistry(), mapper).compileWithExecutionModeHints(
                graph, bundle, "GRAPH_CONTRACT_TEST", target,
                ExecutionModeHints.schemaStandin("/root/subject#PRIMARY", rule.ruleId()));
        TestExecutionRequest request = new TestExecutionRequest(
                graph, new GraphContext(), bundle, "GRAPH_CONTRACT_TEST", target,
                TestExecutionRequest.FixtureSource.STORED, Map.of(), true,
                ResolvedReplayPayloads.empty());

        TestExecutionResult result = service.executeCompiled(request, compiled);

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence().evidenceClass())
                .isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
        assertThat(result.evidence().nodeTrace()).singleElement().satisfies(trace ->
                assertThat(trace.fidelity()).isEqualTo("SCHEMA_STANDIN"));
        assertThat(result.evidence().metadata().get("nodeControlModes").toString())
                .contains("SCHEMA_STANDIN");
    }

    @Test
    void schemaStandinRecorderCarriesModeIntoNodeTrace() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Operator<Object, Object> real = new OpaqueExternalOperator();
        Graph graph = new GraphBuilder("schema-standin").node("subject", real).build();
        NodeSpec node = graph.nodes().get("subject").toBuilder()
                .operatorRef("fixture.operator").build();
        FixtureRule rule = new FixtureRule(FixtureRule.SCHEMA_VERSION, "standin-output",
                FixtureRule.Selector.node("subject"), FixtureRule.Behavior.returning("standin"),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        InvocationSite site = new InvocationSite(InvocationSite.SCHEMA_VERSION, "target",
                "/root", "subject", "fixture.operator", "", "", "binding",
                InvocationSite.InvocationKind.PRIMARY, null, "", null);
        CompiledExecutionControl.ResolvedControl control =
                new CompiledExecutionControl.ResolvedControl(site, List.of(rule), false,
                        CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                                .SELECTOR_SPECIFICITY,
                        List.of(), Map.of(rule.ruleId(), ExecutionMode.SCHEMA_STANDIN));
        InvocationRecorder recorder = new InvocationRecorder(mapper);
        InvocationRecorder.InvocationBinding binding = recorder.bind(site, new GraphContext());
        Operator<Object, Object> standin = new TestDoubleFactory(mapper, null).create(
                node, binding, control, real, recorder, ResolvedReplayPayloads.empty(),
                MirrorResolutionObserver.noop());

        standin.execute("input", new OperatorContext(
                "subject", "test", new GraphContext(), 0));

        assertThat(recorder.controlModes()).containsEntry(site.invocationSiteId(), "SCHEMA_STANDIN");
        InvocationInventory inventory = new InvocationInventory(
                List.of(new InvocationInventory.Entry(graph, node, site, "subject", real)),
                Map.of("subject", new InvocationInventory.Entry(graph, node, site, "subject", real)),
                Map.of(site.invocationSiteId(), new InvocationInventory.Entry(
                        graph, node, site, "subject", real)));
        assertThat(recorder.nodeTraces(inventory, graph, null)).singleElement()
                .satisfies(trace -> assertThat(trace.fidelity()).isEqualTo("SCHEMA_STANDIN"));
    }

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
    void storedOutputDoubleCannotMakeAnOpaqueBindingCertifiable() {
        TestRunService service = new TestRunService(new DefaultOperatorRegistry(),
                new ObjectMapper(), null);
        OperatorMicroGraphRunner runner = new OperatorMicroGraphRunner(service);
        String target = "sha256:" + "d".repeat(64);
        FixtureRule rule = new FixtureRule(FixtureRule.SCHEMA_VERSION, "controlled-output",
                FixtureRule.Selector.operator("legacy.external"),
                FixtureRule.Behavior.returning(Map.of("status", "controlled")),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        FixtureBundle bundle = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "opaque", 1,
                target, "INTERNAL", null, null, List.of(rule), List.of(), Map.of());

        OperatorMicroGraphRunner.Result result = runner.execute(new OperatorMicroGraphRunner.Request(
                "legacy.external", new OpaqueExternalOperator(), target, Map.of(), bundle,
                "OPERATOR_UNIT_TEST", TestExecutionRequest.FixtureSource.STORED));

        assertThat(result.execution().evidence().status()).isEqualTo(TestRunEvidence.Status.PASSED);
        assertThat(result.execution().evidence().evidenceClass())
                .isEqualTo(TestRunEvidence.EvidenceClass.EXPLORATORY);
        assertThat(result.execution().evidence().metadata())
                .containsEntry("targetCertificationEligible", false);
        assertThat(result.execution().evidence().nodeTrace()).singleElement()
                .satisfies(trace -> assertThat(trace.fidelity()).isEqualTo("OUTPUT_LEVEL"));
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

    @Test
    void factoryRejectsMismatchedAndUnsupportedCompiledModes() {
        ObjectMapper mapper = new ObjectMapper();
        FixtureRule protocol = new FixtureRule(FixtureRule.SCHEMA_VERSION, "protocol-response",
                FixtureRule.Selector.resource("customer.get"),
                FixtureRule.Behavior.protocolResponse("{}", 200, Map.of(),
                        FixtureRule.DoubleBoundary.NODE),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        Graph graph = new GraphBuilder("mode-mismatch")
                .node("subject", new OpaqueExternalOperator()).build();
        NodeSpec node = graph.nodes().get("subject").toBuilder()
                .operatorRef("httpResource").build();
        InvocationSite site = new InvocationSite(InvocationSite.SCHEMA_VERSION, "target",
                "/root", "subject", "httpResource", "", "", "binding",
                InvocationSite.InvocationKind.RESOURCE, null, "", null);
        CompiledExecutionControl.ResolvedControl control =
                new CompiledExecutionControl.ResolvedControl(site, List.of(protocol), false,
                        CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                                .SELECTOR_SPECIFICITY,
                        List.of(), Map.of("protocol-response",
                        ExecutionMode.DESCRIPTOR_TRANSPORT));
        InvocationRecorder recorder = new InvocationRecorder(mapper);

        assertThatThrownBy(() -> new TestDoubleFactory(mapper, null).create(
                node, recorder.bind(site, new GraphContext()), control,
                new OpaqueExternalOperator(), recorder, ResolvedReplayPayloads.empty(),
                MirrorResolutionObserver.noop()))
                .isInstanceOfSatisfying(TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTROL_PLAN_EXECUTION_MODE_MISMATCH"));

        FixtureRule wrongStandin = new FixtureRule(
                FixtureRule.SCHEMA_VERSION, "wrong-standin",
                FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.throwing("INJECTED", "TEST", "injected"),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        NodeSpec standinNode = graph.nodes().get("subject").toBuilder()
                .operatorRef("fixture.operator").build();
        InvocationSite standinSite = new InvocationSite(
                InvocationSite.SCHEMA_VERSION, "target", "/root", "subject",
                "fixture.operator", "", "", "binding",
                InvocationSite.InvocationKind.PRIMARY, null, "", null);
        CompiledExecutionControl.ResolvedControl invalidStandin =
                new CompiledExecutionControl.ResolvedControl(
                        standinSite, List.of(wrongStandin), false,
                        CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                                .SELECTOR_SPECIFICITY,
                        List.of(), Map.of(wrongStandin.ruleId(), ExecutionMode.SCHEMA_STANDIN));
        InvocationRecorder standinRecorder = new InvocationRecorder(mapper);
        assertThatThrownBy(() -> new TestDoubleFactory(mapper, null).create(
                standinNode, standinRecorder.bind(standinSite, new GraphContext()),
                invalidStandin, new OpaqueExternalOperator(), standinRecorder,
                ResolvedReplayPayloads.empty(), MirrorResolutionObserver.noop()))
                .isInstanceOfSatisfying(TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTROL_PLAN_EXECUTION_MODE_MISMATCH"));

        CompiledExecutionControl.ResolvedControl unsupported =
                new CompiledExecutionControl.ResolvedControl(site, List.of(protocol), false,
                        CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                                .SELECTOR_SPECIFICITY,
                        List.of(), Map.of("protocol-response", ExecutionMode.WORLD_DELEGATE));
        InvocationRecorder unsupportedRecorder = new InvocationRecorder(mapper);
        assertThatThrownBy(() -> new TestDoubleFactory(mapper, null).create(
                node, unsupportedRecorder.bind(site, new GraphContext()), unsupported,
                new OpaqueExternalOperator(), unsupportedRecorder,
                ResolvedReplayPayloads.empty(), MirrorResolutionObserver.noop()))
                .isInstanceOfSatisfying(TestControlException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CONTROL_PLAN_EXECUTION_MODE_UNSUPPORTED"));
    }

    @Test
    void oneCompiledControlExecutesProtocolAndTransportRulesWithoutFirstRuleLeakage()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BlgeExpressionEvaluator evaluator = new BlgeExpressionEvaluator();
        TwoResourceRegistry registry = new TwoResourceRegistry(
                new ResourceDescriptor("customer.protocol", "https://api.test/protocol/{id}",
                        "GET", Map.of(), null, Duration.ofSeconds(2),
                        new ParameterMapping(Map.of("id", "ctx.params.requiredId"),
                                Map.of(), null),
                        new ResponseProtocol.BodyCode("code", java.util.Set.of(0), "message"),
                        "data"),
                new ResourceDescriptor("customer.transport", "https://api.test/customers/{id}",
                        "GET", Map.of("Accept", "application/json"), null,
                        Duration.ofSeconds(2),
                        new ParameterMapping(Map.of("id", "ctx.params.id"),
                                Map.of("view", "ctx.params.view"), null),
                        new ResponseProtocol.HttpStatus(), "data"));
        ObservingResourceFixtureRuntime resourceRuntime =
                new ObservingResourceFixtureRuntime(registry, evaluator, mapper);
        TestDoubleFactory factory = new TestDoubleFactory(mapper, resourceRuntime);
        HttpResourceOperator real = new HttpResourceOperator(
                new HttpRequestOperator() {
                    @Override
                    public com.leanowtech.bloge.operators.http.HttpResponseOutput execute(
                            HttpRequestInput input, OperatorContext context) {
                        throw new AssertionError("descriptor fixture must prevent network access");
                    }
                }, registry, evaluator, new UrlTemplateRenderer(),
                new PayloadExtractor(mapper), new ResponseValidator(evaluator));
        Graph embedded = new GraphBuilder("dynamic-modes")
                .node("subject", new OpaqueExternalOperator()).build();
        NodeSpec node = embedded.nodes().get("subject").toBuilder()
                .operatorRef("httpResource").build();
        Graph graph = new Graph(embedded.name(), Map.of("subject", node), embedded.edges(),
                embedded.sourceNodes(), embedded.terminalNodes(), embedded.schemaValidationLevel(),
                Map.of(), embedded.declaredInputSchema(), embedded.declaredOutputSchema(),
                embedded.sagaConfig(), embedded.definitionSource(), embedded.streamingOutputNodeId(),
                embedded.streamingInputs());
        FixtureRule protocol = new FixtureRule(FixtureRule.SCHEMA_VERSION, "protocol-response",
                FixtureRule.Selector.resource("customer.protocol"),
                FixtureRule.Behavior.protocolResponse(
                        "{\"code\":0,\"data\":{\"name\":\"Ada\"}}", 200, Map.of(),
                        FixtureRule.DoubleBoundary.NODE),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        FixtureRule transport = new FixtureRule(FixtureRule.SCHEMA_VERSION, "transport-response",
                FixtureRule.Selector.resource("customer.transport"),
                FixtureRule.Behavior.protocolResponse(
                        "{\"data\":{\"name\":\"Grace\"}}", 200, Map.of(),
                        FixtureRule.DoubleBoundary.TRANSPORT),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
        String target = "sha256:" + "f".repeat(64);
        FixtureBundle bundle = new FixtureBundle(FixtureBundle.SCHEMA_VERSION,
                "dynamic-modes", 1, target, "INTERNAL", null, null,
                List.of(protocol, transport), List.of(), Map.of());
        DefaultOperatorRegistry operatorRegistry = new DefaultOperatorRegistry();
        operatorRegistry.register("httpResource", real);
        CompiledExecutionControl compiled = new ExecutionControlCompiler(operatorRegistry, mapper)
                .compile(graph, bundle, "GRAPH_CONTRACT_TEST", target);
        CompiledExecutionControl.ResolvedControl compiledControl =
                compiled.controls().get("/root/subject#RESOURCE");
        InvocationSite site = compiledControl.site();
        assertThat(compiledControl.executionModesByRuleId()).containsExactlyInAnyOrderEntriesOf(
                Map.of(protocol.ruleId(), ExecutionMode.DESCRIPTOR_PROTOCOL,
                        transport.ruleId(), ExecutionMode.DESCRIPTOR_TRANSPORT));
        InvocationRecorder recorder = new InvocationRecorder(mapper);
        GraphContext graphContext = new GraphContext();
        var binding = recorder.bind(site, graphContext);
        Operator<Object, Object> controlled = factory.create(node, binding, compiledControl, real,
                recorder, ResolvedReplayPayloads.empty(), MirrorResolutionObserver.noop());

        Object protocolOutput = controlled.execute(
                new HttpResourceInput("customer.protocol", Map.of()),
                new OperatorContext("subject", "test", graphContext, 0));
        Object transportOutput = controlled.execute(
                new HttpResourceInput("customer.transport",
                        Map.of("id", "C-42", "view", "full")),
                new OperatorContext("subject", "test", graphContext, 0));

        assertThat(((HttpResourceOutput) protocolOutput).payload())
                .isEqualTo(Map.of("name", "Ada"));
        assertThat(((HttpResourceOutput) transportOutput).payload())
                .isEqualTo(Map.of("name", "Grace"));
        assertThat(resourceRuntime.lastRequest.url())
                .isEqualTo("https://api.test/customers/C-42?view=full");
        assertThat(resourceRuntime.lastRequest.headers())
                .containsEntry("Accept", "application/json");
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

    private record CountingExternalOperator(AtomicInteger calls)
            implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) {
            calls.incrementAndGet();
            return "real";
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

    private record TwoResourceRegistry(ResourceDescriptor protocol,
                                       ResourceDescriptor transport) implements
            com.leanowtech.bloge.gateway.resource.ResourceRegistry {
        @Override
        public ResourceDescriptor resolve(String resourceId) {
            if (protocol.resourceId().equals(resourceId)) {
                return protocol;
            }
            if (transport.resourceId().equals(resourceId)) {
                return transport;
            }
            throw new IllegalArgumentException(resourceId);
        }

        @Override
        public boolean contains(String resourceId) {
            return protocol.resourceId().equals(resourceId)
                    || transport.resourceId().equals(resourceId);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.of(protocol, transport);
        }
    }

    private static final class ObservingResourceFixtureRuntime extends ResourceFixtureRuntime {
        private HttpRequestInput lastRequest;

        private ObservingResourceFixtureRuntime(com.leanowtech.bloge.gateway.resource.ResourceRegistry registry,
                                                BlgeExpressionEvaluator evaluator,
                                                ObjectMapper objectMapper) {
            super(registry, evaluator, objectMapper);
        }

        @Override
        public HttpResourceOutput executeDescriptorTransport(
                FixtureRule.Behavior behavior, Object input, OperatorContext context) throws Exception {
            DescriptorTransportResult result = executeDescriptorTransportObserved(
                    behavior, input, context);
            lastRequest = result.request();
            return result.output();
        }
    }
}
