package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetCommand;
import com.leanowtech.bloge.gateway.visual.authoring.fixture.FixtureSetView;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FlowSimulationModuleV2Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "test");
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");
    private static final String FLOW = "sha256:" + "a".repeat(64);
    private static final String PROFILE = "sha256:" + "b".repeat(64);
    private static final String CREDIT = "sha256:" + "c".repeat(64);
    private static final String OPERATOR = "sha256:" + "d".repeat(64);

    @Test
    void executesDifferentFixturesPerDagNodeAfterApplyingMappings() {
        FlowFixturePlanCompilerV2 compiler = mock(FlowFixturePlanCompilerV2.class);
        ApiResourceCommitStore resources = resources("profile", PROFILE, "credit", CREDIT);
        ResolvedFlowSimulationPlanV2 plan = twoApiPlan();
        when(compiler.compile(eq(SCOPE), any())).thenReturn(plan);
        when(compiler.resolveInvocation(eq(SCOPE), eq(plan.nodes().get(List.of("profile"))),
                eq(plan.bindings().get(List.of("profile"))), any()))
                .thenReturn(selection(List.of("profile"), "profile-fixtures", "vip",
                        JSON.createObjectNode().put("score", 420)));
        when(compiler.resolveInvocation(eq(SCOPE), eq(plan.nodes().get(List.of("credit"))),
                eq(plan.bindings().get(List.of("credit"))), any()))
                .thenReturn(selection(List.of("credit"), "credit-fixtures", "low-score",
                        JSON.createObjectNode().put("risk", "high")));
        FlowSimulationModuleV2 module = module(resources, compiler, "sim-flow", "inv-");

        SimulationRunV2 run = module.execute(SCOPE, "flow-key", command(plan), null).run();

        assertThat(run.status()).isEqualTo(SimulationRunV2.Status.SUCCEEDED);
        assertThat(run.output()).isEqualTo(JSON.createObjectNode().put("risk", "high"));
        assertThat(run.invocations()).extracting(SimulationRunV2.Invocation::invocationKey)
                .containsExactly("inv-1", "inv-2");
        assertThat(run.invocations()).extracting(value ->
                        ((SimulationCommandV2.FixtureTarget.NodePath) value.target()).nodePath())
                .containsExactly(List.of("profile"), List.of("credit"));
        ArgumentCaptor<JsonNode> creditInput = ArgumentCaptor.forClass(JsonNode.class);
        verify(compiler).resolveInvocation(eq(SCOPE), eq(plan.nodes().get(List.of("credit"))),
                eq(plan.bindings().get(List.of("credit"))), creditInput.capture());
        assertThat(creditInput.getValue()).isEqualTo(JSON.createObjectNode().put("score", 420));
    }

    @Test
    void wholeFlowFixtureSuppressesEveryDescendantInvocation() {
        FlowFixturePlanCompilerV2 compiler = mock(FlowFixturePlanCompilerV2.class);
        ResolvedFlowSimulationPlanV2 plan = nestedPlan(true);
        when(compiler.compile(eq(SCOPE), any())).thenReturn(plan);
        when(compiler.resolveInvocation(eq(SCOPE), any(), any(), any())).thenReturn(
                selection(List.of("risk"), "risk-fixtures", "approved",
                        JSON.createObjectNode().put("risk", "low")));
        FlowSimulationModuleV2 module = module(mock(ApiResourceCommitStore.class), compiler,
                "sim-whole", "whole-");

        SimulationRunV2 run = module.execute(SCOPE, "whole-key", command(plan), null).run();

        assertThat(run.status()).isEqualTo(SimulationRunV2.Status.SUCCEEDED);
        assertThat(run.invocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.invocationKey()).isEqualTo("whole-1");
            assertThat(invocation.target()).isEqualTo(
                    new SimulationCommandV2.FixtureTarget.NodePath(List.of("risk")));
            assertThat(invocation.execution()).isEqualTo(SimulationRunV2.Execution.MOCKED);
        });
    }

    @Test
    void expandedChildInvocationsCarryExactParentKeysAndHierarchicalPaths() {
        FlowFixturePlanCompilerV2 compiler = mock(FlowFixturePlanCompilerV2.class);
        ApiResourceCommitStore resources = resources("credit", CREDIT);
        ResolvedFlowSimulationPlanV2 plan = nestedPlan(false);
        when(compiler.compile(eq(SCOPE), any())).thenReturn(plan);
        when(compiler.resolveInvocation(eq(SCOPE), any(), any(), any())).thenReturn(
                selection(List.of("risk", "credit"), "credit-fixtures", "approved",
                        JSON.createObjectNode().put("risk", "low")));
        FlowSimulationModuleV2 module = module(resources, compiler, "sim-nested", "nested-");

        SimulationRunV2 run = module.execute(SCOPE, "nested-key", command(plan), null).run();

        assertThat(run.status()).isEqualTo(SimulationRunV2.Status.SUCCEEDED);
        assertThat(run.invocations()).hasSize(2);
        assertThat(run.invocations().getFirst().execution()).isEqualTo(SimulationRunV2.Execution.REAL);
        assertThat(run.invocations().get(1).parentInvocationKey()).isEqualTo("nested-1");
        assertThat(run.invocations().get(1).target()).isEqualTo(
                new SimulationCommandV2.FixtureTarget.NodePath(List.of("risk", "credit")));
    }

    @Test
    void unmatchedApiNodesBlockWithoutNetworkOrFalseMockEvidence() {
        FlowFixturePlanCompilerV2 compiler = mock(FlowFixturePlanCompilerV2.class);
        ResolvedFlowSimulationPlanV2 plan = twoApiPlan();
        plan = new ResolvedFlowSimulationPlanV2(plan.subject(), plan.inputContract(), plan.outputContract(),
                plan.graph(), plan.input(), SimulationCommandV2.Unmatched.BLOCK,
                plan.nodes(), Map.of(), plan.fingerprint());
        when(compiler.compile(eq(SCOPE), any())).thenReturn(plan);
        FlowSimulationModuleV2 module = module(resources("profile", PROFILE, "credit", CREDIT),
                compiler, "sim-blocked", "blocked-");

        SimulationRunV2 run = module.execute(SCOPE, "blocked-key", command(plan), null).run();

        assertThat(run.status()).isEqualTo(SimulationRunV2.Status.BLOCKED);
        assertThat(run.invocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.status()).isEqualTo(SimulationRunV2.InvocationStatus.BLOCKED);
            assertThat(invocation.execution()).isEqualTo(SimulationRunV2.Execution.REAL);
            assertThat(invocation.matchedBy()).isEqualTo(SimulationRunV2.MatchedBy.NONE);
            assertThat(invocation.fixtureCase()).isNull();
        });
        verify(compiler, never()).resolveInvocation(any(), any(), any(), any());
    }

    @Test
    void replacesAnExactOperatorDagNodeWithoutExecutingTheComponent() {
        FlowFixturePlanCompilerV2 compiler = mock(FlowFixturePlanCompilerV2.class);
        ExactFixtureSubjectRefV2.FlowVersion root =
                new ExactFixtureSubjectRefV2.FlowVersion("root", 1, FLOW);
        ExactFixtureSubjectRefV2.OperatorVersion operator =
                new ExactFixtureSubjectRefV2.OperatorVersion(
                        "risk-library", 3, "risk.score", OPERATOR);
        ReusableFlowCommand.Node authored = new ReusableFlowCommand.Node(
                "risk", "Risk score",
                new ReusableFlowCommand.ComposableRef.OperatorVersion(
                        "risk-library", 3, "risk.score", OPERATOR),
                List.of(new ReusableFlowCommand.Input("$.score",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.score"))));
        ReusableFlowCommand.Graph graph = new ReusableFlowCommand.Graph(
                List.of(authored), new ReusableFlowCommand.Output("risk", "$"));
        ResolvedFlowSimulationPlanV2.Node node = new ResolvedFlowSimulationPlanV2.Node(
                List.of("risk"), operator, new ReusableFlowCommand.Contract(schema(), schema()),
                authored, null);
        ResolvedFlowSimulationPlanV2.Binding binding = binding(
                List.of("risk"), "risk-operator-fixtures");
        ResolvedFlowSimulationPlanV2 plan = new ResolvedFlowSimulationPlanV2(
                root, schema(), schema(), graph, JSON.createObjectNode().put("score", 720),
                SimulationCommandV2.Unmatched.BLOCK, Map.of(List.of("risk"), node),
                Map.of(List.of("risk"), binding), "sha256:" + "e".repeat(64));
        when(compiler.compile(eq(SCOPE), any())).thenReturn(plan);
        when(compiler.resolveInvocation(eq(SCOPE), eq(node), eq(binding), any())).thenReturn(
                selection(List.of("risk"), "risk-operator-fixtures", "approved",
                        JSON.createObjectNode().put("risk", "low")));
        FlowSimulationModuleV2 module = module(
                mock(ApiResourceCommitStore.class), compiler, "sim-operator-flow", "operator-");

        SimulationRunV2 run = module.execute(SCOPE, "operator-flow-key", command(plan), null).run();

        assertThat(run.status()).isEqualTo(SimulationRunV2.Status.SUCCEEDED);
        assertThat(run.output()).isEqualTo(JSON.createObjectNode().put("risk", "low"));
        assertThat(run.invocations()).singleElement().satisfies(invocation -> {
            assertThat(invocation.invocationKey()).isEqualTo("operator-1");
            assertThat(invocation.subject()).isEqualTo(operator);
            assertThat(invocation.execution()).isEqualTo(SimulationRunV2.Execution.MOCKED);
            assertThat(invocation.target()).isEqualTo(
                    new SimulationCommandV2.FixtureTarget.NodePath(List.of("risk")));
        });
    }

    @Test
    void twoSameNameFunctionCallSitesUseDifferentFixturesAndInvocationKeys() {
        FlowFixturePlanCompilerV2 compiler = mock(FlowFixturePlanCompilerV2.class);
        ExactFixtureSubjectRefV2.FlowVersion root =
                new ExactFixtureSubjectRefV2.FlowVersion("root", 1, FLOW);
        ExactFixtureSubjectRefV2.OperatorVersion operator =
                new ExactFixtureSubjectRefV2.OperatorVersion(
                        "risk-library", 3, "risk.score", OPERATOR);
        ExactFixtureSubjectRefV2.BuiltinFunctionVersion lookup =
                new ExactFixtureSubjectRefV2.BuiltinFunctionVersion(
                        "bloge", 1, "lookup", "sha256:" + "4".repeat(64),
                        "sha256:" + "5".repeat(64));
        ComponentSimulationAuthorityV2.CallSite customer =
                new ComponentSimulationAuthorityV2.CallSite(
                        "lookup:customer", lookup, schema(), schema(),
                        "sha256:" + "6".repeat(64));
        ComponentSimulationAuthorityV2.CallSite referrer =
                new ComponentSimulationAuthorityV2.CallSite(
                        "lookup:referrer", lookup, schema(), schema(),
                        "sha256:" + "7".repeat(64));
        ReusableFlowCommand.Node authored = new ReusableFlowCommand.Node(
                "risk", "Risk", new ReusableFlowCommand.ComposableRef.OperatorVersion(
                "risk-library", 3, "risk.score", OPERATOR), List.of());
        ReusableFlowCommand.Graph graph = new ReusableFlowCommand.Graph(
                List.of(authored), new ReusableFlowCommand.Output("risk", "$"));
        ResolvedFlowSimulationPlanV2.Node node = new ResolvedFlowSimulationPlanV2.Node(
                List.of("risk"), operator, new ReusableFlowCommand.Contract(schema(), schema()),
                authored, null, List.of(customer, referrer));
        SimulationCommandV2.FixtureTarget.CallSite customerTarget =
                new SimulationCommandV2.FixtureTarget.CallSite(
                        List.of("risk"), customer.callSiteId());
        SimulationCommandV2.FixtureTarget.CallSite referrerTarget =
                new SimulationCommandV2.FixtureTarget.CallSite(
                        List.of("risk"), referrer.callSiteId());
        ResolvedFlowSimulationPlanV2.Binding customerBinding = callSiteBinding(
                customerTarget, "customer-fixtures", "customer-case");
        ResolvedFlowSimulationPlanV2.Binding referrerBinding = callSiteBinding(
                referrerTarget, "referrer-fixtures", "referrer-case");
        ResolvedFlowSimulationPlanV2 plan = new ResolvedFlowSimulationPlanV2(
                root, schema(), schema(), graph, JSON.createObjectNode(),
                SimulationCommandV2.Unmatched.BLOCK, Map.of(List.of("risk"), node), Map.of(),
                Map.of(customerTarget, customerBinding, referrerTarget, referrerBinding),
                "sha256:" + "8".repeat(64));
        when(compiler.compile(eq(SCOPE), any())).thenReturn(plan);
        when(compiler.resolveInvocation(eq(SCOPE), eq(node), eq(customerBinding), any()))
                .thenAnswer(invocation -> {
                    JsonNode actualInput = invocation.getArgument(3);
                    boolean retry = "c-2".equals(actualInput.path("id").asText());
                    return selection(List.of("risk"), "customer-fixtures",
                            retry ? "customer-retry" : "customer-case",
                            JSON.createObjectNode().put("value", retry ? "customer-2" : "customer"),
                            customerTarget);
                });
        when(compiler.resolveInvocation(eq(SCOPE), eq(node), eq(referrerBinding), any())).thenReturn(
                selection(List.of("risk"), "referrer-fixtures", "referrer-case",
                        JSON.createObjectNode().put("value", "referrer"), referrerTarget));
        AtomicInteger realCalls = new AtomicInteger();
        ComponentCallSiteRuntimeV2 runtime = (scope, subject, input, interceptor) -> {
            JsonNode left = interceptor.invoke(customer,
                    JSON.createObjectNode().put("id", "c-1"), () -> {
                        realCalls.incrementAndGet();
                        return JSON.createObjectNode().put("value", "real-customer");
                    });
            JsonNode retried = interceptor.invoke(customer,
                    JSON.createObjectNode().put("id", "c-2"), () -> {
                        realCalls.incrementAndGet();
                        return JSON.createObjectNode().put("value", "real-customer-2");
                    });
            JsonNode right = interceptor.invoke(referrer,
                    JSON.createObjectNode().put("id", "r-1"), () -> {
                        realCalls.incrementAndGet();
                        return JSON.createObjectNode().put("value", "real-referrer");
                    });
            return JSON.createObjectNode().set(
                    "lookups", JSON.createArrayNode().add(left).add(retried).add(right));
        };
        AtomicInteger invocation = new AtomicInteger();
        FlowSimulationModuleV2 module = new FlowSimulationModuleV2(
                mock(ApiResourceCommitStore.class), compiler, null, null, null,
                new InMemorySimulationRunV2Store(), Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "sim-call-sites", () -> "call-inv-" + invocation.incrementAndGet(), runtime);

        SimulationRunV2 run = module.execute(SCOPE, "call-sites", command(plan), null).run();

        assertThat(run.status()).isEqualTo(SimulationRunV2.Status.SUCCEEDED);
        assertThat(realCalls).hasValue(0);
        assertThat(run.invocations()).hasSize(4);
        assertThat(run.invocations()).extracting(SimulationRunV2.Invocation::invocationKey)
                .containsExactly("call-inv-1", "call-inv-2", "call-inv-3", "call-inv-4");
        assertThat(run.invocations().subList(1, 4))
                .extracting(SimulationRunV2.Invocation::target)
                .containsExactly(customerTarget, customerTarget, referrerTarget);
        assertThat(run.invocations().subList(1, 4))
                .extracting(value -> value.fixtureCase().caseId())
                .containsExactly("customer-case", "customer-retry", "referrer-case");
        assertThat(run.invocations().subList(1, 4))
                .allSatisfy(value -> assertThat(value.parentInvocationKey()).isEqualTo("call-inv-1"));
    }

    private static FlowSimulationModuleV2 module(
            ApiResourceCommitStore resources, FlowFixturePlanCompilerV2 compiler,
            String runId, String invocationPrefix) {
        AtomicInteger invocations = new AtomicInteger();
        return new FlowSimulationModuleV2(resources, compiler, null, null, null,
                new InMemorySimulationRunV2Store(), Clock.fixed(NOW, ZoneOffset.UTC),
                () -> runId, () -> invocationPrefix + invocations.incrementAndGet());
    }

    private static ResolvedFlowSimulationPlanV2 twoApiPlan() {
        ExactFixtureSubjectRefV2.FlowVersion subject =
                new ExactFixtureSubjectRefV2.FlowVersion("flow", 1, FLOW);
        ReusableFlowCommand.Node profile = node("profile", "profile", PROFILE,
                List.of(new ReusableFlowCommand.Input("$.customerId",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.customerId"))));
        ReusableFlowCommand.Node credit = node("credit", "credit", CREDIT,
                List.of(new ReusableFlowCommand.Input("$.score",
                        new ReusableFlowCommand.MappingSource.NodeOutput("profile", "$.score"))));
        ReusableFlowCommand.Graph graph = new ReusableFlowCommand.Graph(
                List.of(profile, credit), new ReusableFlowCommand.Output("credit", "$"));
        Map<List<String>, ResolvedFlowSimulationPlanV2.Node> nodes = Map.of(
                List.of("profile"), planNode(List.of("profile"), profile, PROFILE, null),
                List.of("credit"), planNode(List.of("credit"), credit, CREDIT, null));
        Map<List<String>, ResolvedFlowSimulationPlanV2.Binding> bindings = Map.of(
                List.of("profile"), binding(List.of("profile"), "profile-fixtures"),
                List.of("credit"), binding(List.of("credit"), "credit-fixtures"));
        return new ResolvedFlowSimulationPlanV2(subject, schema(), schema(), graph,
                JSON.createObjectNode().put("customerId", "c-1"), SimulationCommandV2.Unmatched.BLOCK,
                nodes, bindings, "sha256:" + "f".repeat(64));
    }

    private static ResolvedFlowSimulationPlanV2 nestedPlan(boolean bindParent) {
        ExactFixtureSubjectRefV2.FlowVersion root =
                new ExactFixtureSubjectRefV2.FlowVersion("root", 1, FLOW);
        ReusableFlowCommand.Node risk = new ReusableFlowCommand.Node("risk", "risk",
                new ReusableFlowCommand.ComposableRef.FlowVersion(
                        "child", 1, "sha256:" + "d".repeat(64)), List.of(
                new ReusableFlowCommand.Input("$.score",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.score"))));
        ReusableFlowCommand.Node credit = node("credit", "credit", CREDIT, List.of(
                new ReusableFlowCommand.Input("$.score",
                        new ReusableFlowCommand.MappingSource.FlowInput("$.score"))));
        ReusableFlowCommand.Graph child = new ReusableFlowCommand.Graph(
                List.of(credit), new ReusableFlowCommand.Output("credit", "$"));
        ReusableFlowCommand.Graph graph = new ReusableFlowCommand.Graph(
                List.of(risk), new ReusableFlowCommand.Output("risk", "$"));
        Map<List<String>, ResolvedFlowSimulationPlanV2.Node> nodes = new LinkedHashMap<>();
        nodes.put(List.of("risk"), new ResolvedFlowSimulationPlanV2.Node(List.of("risk"),
                new ExactFixtureSubjectRefV2.FlowVersion("child", 1, "sha256:" + "d".repeat(64)),
                new ReusableFlowCommand.Contract(schema(), schema()), risk, child));
        nodes.put(List.of("risk", "credit"), planNode(
                List.of("risk", "credit"), credit, CREDIT, null));
        List<String> target = bindParent ? List.of("risk") : List.of("risk", "credit");
        return new ResolvedFlowSimulationPlanV2(root, schema(), schema(), graph,
                JSON.createObjectNode().put("score", 420), SimulationCommandV2.Unmatched.BLOCK,
                nodes, Map.of(target, binding(target, bindParent ? "risk-fixtures" : "credit-fixtures")),
                "sha256:" + "e".repeat(64));
    }

    private static ResolvedFlowSimulationPlanV2.Node planNode(
            List<String> path, ReusableFlowCommand.Node node, String fingerprint,
            ReusableFlowCommand.Graph child) {
        return new ResolvedFlowSimulationPlanV2.Node(path,
                new ExactFixtureSubjectRefV2.ApiResource(node.use().id(), 1, fingerprint),
                null, node, child);
    }

    private static ResolvedFlowSimulationPlanV2.Binding binding(List<String> path, String fixtureId) {
        SimulationCommandV2.FixtureTarget.NodePath target =
                new SimulationCommandV2.FixtureTarget.NodePath(path);
        return new ResolvedFlowSimulationPlanV2.Binding(target,
                new SimulationCommandV2.FixtureSelection.ExactCase(
                        new SimulationCommandV2.ExactFixtureSetRef(
                                fixtureId, 1, "sha256:" + "1".repeat(64)), "approved"), null);
    }

    private static ResolvedFixturePlan.Selection selection(
            List<String> path, String fixtureId, String caseId, JsonNode output) {
        SimulationCommandV2.FixtureTarget.NodePath target =
                new SimulationCommandV2.FixtureTarget.NodePath(path);
        return new ResolvedFixturePlan.Selection(target,
                new SimulationCommandV2.ExactFixtureSetRef(
                        fixtureId, 1, "sha256:" + "1".repeat(64)),
                caseId, ResolvedFixturePlan.MatchedBy.EXACT_CASE,
                FixtureSetView.Status.PRIVATE_DRAFT,
                new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output)),
                        FixtureSetCommand.Fidelity.OUTPUT_LEVEL), null);
    }

    private static ResolvedFixturePlan.Selection selection(
            List<String> path, String fixtureId, String caseId, JsonNode output,
            SimulationCommandV2.FixtureTarget target) {
        return new ResolvedFixturePlan.Selection(target,
                new SimulationCommandV2.ExactFixtureSetRef(
                        fixtureId, 1, "sha256:" + "1".repeat(64)),
                caseId, ResolvedFixturePlan.MatchedBy.EXACT_CASE,
                FixtureSetView.Status.PRIVATE_DRAFT,
                new FixtureSetCommand.Control(FixtureSetCommand.Target.subject(),
                        FixtureSetCommand.Behavior.returned(FixtureSetCommand.Material.inline(output)),
                        FixtureSetCommand.Fidelity.OUTPUT_LEVEL), null);
    }

    private static ResolvedFlowSimulationPlanV2.Binding callSiteBinding(
            SimulationCommandV2.FixtureTarget.CallSite target, String fixtureSetId, String caseId) {
        return new ResolvedFlowSimulationPlanV2.Binding(target,
                new SimulationCommandV2.FixtureSelection.ExactCase(
                        new SimulationCommandV2.ExactFixtureSetRef(
                                fixtureSetId, 1, "sha256:" + "1".repeat(64)), caseId), null);
    }

    private static ReusableFlowCommand.Node node(
            String nodeId, String resourceId, String fingerprint,
            List<ReusableFlowCommand.Input> inputs) {
        return new ReusableFlowCommand.Node(nodeId, nodeId,
                new ReusableFlowCommand.ComposableRef.ApiResource(resourceId, 1, fingerprint), inputs);
    }

    private static SimulationCommandV2 command(ResolvedFlowSimulationPlanV2 plan) {
        return new SimulationCommandV2(SimulationCommandV2.SCHEMA_VERSION, plan.subject(),
                new SimulationCommandV2.Input.Inline(plan.input()),
                new SimulationCommandV2.FixturePlan.None(), SimulationCommandV2.ExecutionPolicy.denyAll());
    }

    private static ApiResourceCommitStore resources(String... idAndFingerprint) {
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        for (int index = 0; index < idAndFingerprint.length; index += 2) {
            String id = idAndFingerprint[index];
            String fingerprint = idAndFingerprint[index + 1];
            StoredApiResource stored = mock(StoredApiResource.class,
                    org.mockito.Answers.RETURNS_DEEP_STUBS);
            when(stored.resource().resourceId()).thenReturn(id);
            when(stored.resource().revision()).thenReturn(1);
            when(stored.resource().fingerprint()).thenReturn(fingerprint);
            when(stored.resource().contract().input()).thenReturn(schema());
            when(stored.resource().contract().output()).thenReturn(schema());
            when(resources.findRevision(SCOPE, id, 1)).thenReturn(Optional.of(stored));
        }
        return resources;
    }

    private static SchemaEnvelope schema() {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object", "additionalProperties", true));
    }
}
