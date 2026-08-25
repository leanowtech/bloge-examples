package com.leanowtech.bloge.gateway.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionMode;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.ControlPlanRejectedException;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionModeHints;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Stage0Exit07FixedProofTest {

    private static final String TARGET = "sha256:" + "7".repeat(64);
    private static final String PURPOSE = "GRAPH_CONTRACT_TEST";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void independentOracleMatchesEveryRepresentativeStageZeroResolution() {
        for (Case testCase : cases()) {
            CompiledExecutionControl compiled = compile(testCase);
            Expected expected = testCase.expected();
            EffectiveExecutionPlan.ResolvedSite site = compiled.effectivePlan().resolvedSites()
                    .stream().filter(candidate -> candidate.invocationSiteId().equals(expected.siteId()))
                    .findFirst().orElseThrow();

            assertThat(site.invocationSiteId()).isEqualTo(expected.siteId());
            assertThat(site.resolution()).isEqualTo(expected.resolution());
            assertThat(site.behavior()).isEqualTo(expected.behavior());
            assertThat(site.boundary()).isEqualTo(expected.boundary());
            assertThat(site.fidelity()).isEqualTo(expected.fidelity());
            assertThat(site.ruleRefs()).isEqualTo(expected.ruleRefs());
            assertThat(mode(compiled, expected.siteId(), expected.ruleId()))
                    .isEqualTo(expected.mode());
        }
    }

    @Test
    void malformedAndConfusedDescriptorFixturesFailClosed() {
        Case malformed = descriptorCase("malformed", new FixtureRule.Behavior(
                FixtureRule.BehaviorKind.RETURN, FixtureRule.DoubleBoundary.TRANSPORT, null,
                "{\"data\":true}", null, Map.of(), "", "", "", null, List.of(), ""),
                ExecutionMode.DESCRIPTOR_TRANSPORT);
        assertThatThrownBy(() -> compile(malformed))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure ->
                        assertThat(failure.code()).isEqualTo("CONTROL_PLAN_REJECTED"));

        Case confused = descriptorCase("confused", new FixtureRule.Behavior(
                FixtureRule.BehaviorKind.RETURN, FixtureRule.DoubleBoundary.TRANSPORT,
                Map.of("fixed", true), "", 200, Map.of(), "", "", "", null,
                List.of(), ""), ExecutionMode.SCHEMA_STANDIN);
        assertThatThrownBy(() -> compile(confused))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure ->
                        assertThat(failure.code()).isEqualTo("CONTROL_PLAN_DESCRIPTOR_TRANSPORT_CONFUSION"));
    }

    @Test
    void twentyCompilationRepeatsKeepPlanAndRuntimeBindingFingerprintsStable() {
        for (Case testCase : cases()) {
            CompiledExecutionControl first = compile(testCase);
            String planFingerprint = first.effectivePlan().planFingerprint();
            Map<String, String> bindings = bindingFingerprints(first);
            Set<String> planIds = new TreeSet<>();
            assertThat(first.effectivePlan().planId()).isNotBlank();
            planIds.add(first.effectivePlan().planId());

            for (int repeat = 0; repeat < 20; repeat++) {
                CompiledExecutionControl repeated = compile(testCase);
                planIds.add(repeated.effectivePlan().planId());
                assertThat(repeated.effectivePlan().planId()).isNotBlank();
                assertThat(repeated.effectivePlan().planFingerprint())
                        .as("plan fingerprint for %s, repeat %s", testCase.name(), repeat)
                        .isEqualTo(planFingerprint);
                assertThat(bindingFingerprints(repeated))
                        .as("runtime binding fingerprints for %s, repeat %s", testCase.name(), repeat)
                        .isEqualTo(bindings);
            }
            assertThat(planIds).as("unique plan ids for %s", testCase.name()).hasSize(21);
        }
    }

    private static CompiledExecutionControl compile(Case testCase) {
        DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
        ExecutionControlCompiler compiler = new ExecutionControlCompiler(registry, JSON);
        return testCase.hint() == null
                ? compiler.compile(testCase.graph(), testCase.bundle(), PURPOSE, TARGET)
                : compiler.compileWithExecutionModeHints(
                        testCase.graph(), testCase.bundle(), PURPOSE, TARGET, testCase.hint());
    }

    private static Optional<ExecutionMode> mode(CompiledExecutionControl compiled,
                                                String siteId, String ruleId) {
        CompiledExecutionControl.ResolvedControl control = compiled.controls().get(siteId);
        if (control == null || ruleId == null) {
            return Optional.empty();
        }
        return control.rules().stream().filter(rule -> rule.ruleId().equals(ruleId)).findFirst()
                .flatMap(control::executionMode);
    }

    private static Map<String, String> bindingFingerprints(CompiledExecutionControl compiled) {
        return compiled.inventory().entries().stream().collect(java.util.stream.Collectors.toMap(
                entry -> entry.site().invocationSiteId(),
                entry -> entry.site().runtimeBindingFingerprint(),
                (left, right) -> left, java.util.TreeMap::new));
    }

    private static List<Case> cases() {
        FixtureRule standin = rule("standin", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(Map.of("approved", true)));
        FixtureRule output = rule("output", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("fixed"));
        FixtureRule protocol = descriptorRule("protocol", FixtureRule.DoubleBoundary.NODE);
        FixtureRule transport = descriptorRule("transport", FixtureRule.DoubleBoundary.TRANSPORT);
        return List.of(
                new Case("pure-read-only-real", readOnlyGraph(), bundle(), null,
                        new Expected("/root/subject#PRIMARY", null,
                                EffectiveExecutionPlan.Resolution.REAL,
                                FixtureRule.BehaviorKind.REAL, FixtureRule.DoubleBoundary.NODE,
                                List.of(), "REAL")),
                new Case("schema-standin", readOnlyGraph(), bundle(standin),
                        ExecutionModeHints.schemaStandin("/root/subject#PRIMARY", "standin"),
                        new Expected("/root/subject#PRIMARY", "standin",
                                EffectiveExecutionPlan.Resolution.TEST_DOUBLE,
                                FixtureRule.BehaviorKind.RETURN, FixtureRule.DoubleBoundary.NODE,
                                List.of("standin"), "SCHEMA_STANDIN", ExecutionMode.SCHEMA_STANDIN)),
                new Case("descriptor-protocol", resourceGraph(), bundle(protocol), null,
                        new Expected("/root/subject#RESOURCE", "protocol",
                                EffectiveExecutionPlan.Resolution.TEST_DOUBLE,
                                FixtureRule.BehaviorKind.RETURN, FixtureRule.DoubleBoundary.NODE,
                                List.of("protocol"), "PROTOCOL_DERIVED", ExecutionMode.DESCRIPTOR_PROTOCOL)),
                new Case("descriptor-transport", resourceGraph(), bundle(transport), null,
                        new Expected("/root/subject#RESOURCE", "transport",
                                EffectiveExecutionPlan.Resolution.TEST_DOUBLE,
                                FixtureRule.BehaviorKind.RETURN, FixtureRule.DoubleBoundary.TRANSPORT,
                                List.of("transport"), "TRANSPORT_LEVEL", ExecutionMode.DESCRIPTOR_TRANSPORT)),
                new Case("output-level-return", readOnlyGraph(), bundle(output), null,
                        new Expected("/root/subject#PRIMARY", "output",
                                EffectiveExecutionPlan.Resolution.TEST_DOUBLE,
                                FixtureRule.BehaviorKind.RETURN, FixtureRule.DoubleBoundary.NODE,
                                List.of("output"), "OUTPUT_LEVEL")),
                new Case("external-unmatched-implicit-deny", resourceGraph(), bundle(), null,
                        new Expected("/root/subject#RESOURCE", "implicit-deny:/root/subject#RESOURCE",
                                EffectiveExecutionPlan.Resolution.DENIED,
                                FixtureRule.BehaviorKind.THROW, FixtureRule.DoubleBoundary.NODE,
                                List.of("implicit-deny:/root/subject#RESOURCE"), "OUTPUT_LEVEL")));
    }

    private static Case descriptorCase(String name, FixtureRule.Behavior behavior,
                                       ExecutionMode mode) {
        FixtureRule rule = descriptorRule(name, behavior);
        ExecutionModeHints hint = mode == ExecutionMode.SCHEMA_STANDIN
                ? ExecutionModeHints.schemaStandin("/root/subject#RESOURCE", name) : null;
        return new Case(name, resourceGraph(), bundle(rule), hint,
                new Expected("/root/subject#RESOURCE", name,
                        EffectiveExecutionPlan.Resolution.TEST_DOUBLE,
                        FixtureRule.BehaviorKind.RETURN, behavior.boundary(), List.of(name),
                        mode == ExecutionMode.DESCRIPTOR_TRANSPORT
                                ? "TRANSPORT_LEVEL" : "OUTPUT_LEVEL", mode));
    }

    private static FixtureRule descriptorRule(String id, FixtureRule.DoubleBoundary boundary) {
        return descriptorRule(id, FixtureRule.Behavior.protocolResponse(
                "{\"data\":{\"ok\":true}}", 200, Map.of(), boundary));
    }

    private static FixtureRule descriptorRule(String id, FixtureRule.Behavior behavior) {
        return rule(id, FixtureRule.Selector.resource("customer.lookup"), behavior);
    }

    private static FixtureRule rule(String id, FixtureRule.Selector selector,
                                    FixtureRule.Behavior behavior) {
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, id, selector, behavior,
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static FixtureBundle bundle(FixtureRule... rules) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "stage0-exit-07", 1,
                TARGET, "INTERNAL", null, null, List.of(rules), List.of(), Map.of());
    }

    private static Graph readOnlyGraph() {
        return new GraphBuilder("stage0-read-only").node("subject", new ReadOnlyOperator()).build();
    }

    private static Graph resourceGraph() {
        Graph graph = new GraphBuilder("stage0-resource").node("subject", new ExternalOperator()).build();
        var node = graph.nodes().get("subject").toBuilder().operatorRef("httpResource").build();
        return new Graph(graph.name(), Map.of("subject", node), graph.edges(), graph.sourceNodes(),
                graph.terminalNodes(), graph.schemaValidationLevel(), graph.embeddedOperators(),
                graph.declaredInputSchema(), graph.declaredOutputSchema(), graph.sagaConfig(),
                graph.definitionSource(), graph.streamingOutputNodeId(), graph.streamingInputs());
    }

    private record Case(String name, Graph graph, FixtureBundle bundle,
                        ExecutionModeHints hint, Expected expected) {
        private Case {
            Objects.requireNonNull(name);
            Objects.requireNonNull(graph);
            Objects.requireNonNull(bundle);
            Objects.requireNonNull(expected);
        }
    }

    private record Expected(String siteId, String ruleId, EffectiveExecutionPlan.Resolution resolution,
                            FixtureRule.BehaviorKind behavior, FixtureRule.DoubleBoundary boundary,
                            List<String> ruleRefs, String fidelity, Optional<ExecutionMode> mode) {
        private Expected(String siteId, String ruleId, EffectiveExecutionPlan.Resolution resolution,
                         FixtureRule.BehaviorKind behavior, FixtureRule.DoubleBoundary boundary,
                         List<String> ruleRefs, String fidelity) {
            this(siteId, ruleId, resolution, behavior, boundary, ruleRefs, fidelity, Optional.empty());
        }

        private Expected(String siteId, String ruleId, EffectiveExecutionPlan.Resolution resolution,
                         FixtureRule.BehaviorKind behavior, FixtureRule.DoubleBoundary boundary,
                         List<String> ruleRefs, String fidelity, ExecutionMode mode) {
            this(siteId, ruleId, resolution, behavior, boundary, ruleRefs, fidelity, Optional.of(mode));
        }
    }

    private static class ReadOnlyOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

    private static final class ExternalOperator extends ReadOnlyOperator {
        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.EXTERNAL_CALL;
        }
    }
}
