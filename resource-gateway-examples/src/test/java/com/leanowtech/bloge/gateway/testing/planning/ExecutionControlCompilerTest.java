package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionControlCompilerTest {

    private static final String TARGET = "sha256:" + "a".repeat(64);
    private final DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
    private final ExecutionControlCompiler compiler = new ExecutionControlCompiler(
            registry, new ObjectMapper());

    @Test
    void exactNodeSelectorProducesFrozenTestDoubleResolution() {
        Graph graph = graph(new ReadOnlyOperator());
        FixtureRule rule = rule("return-result", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(Map.of("approved", true)));

        CompiledExecutionControl result = compiler.compile(graph, bundle(rule),
                "GRAPH_CONTRACT_TEST", TARGET);

        assertThat(result.effectivePlan().targetFingerprint()).isEqualTo(TARGET);
        assertThat(result.effectivePlan().planFingerprint()).startsWith("sha256:");
        assertThat(result.effectivePlan().resolvedSites()).singleElement().satisfies(site -> {
            assertThat(site.invocationSiteId()).isEqualTo("/root/subject#PRIMARY");
            assertThat(site.resolution()).isEqualTo(EffectiveExecutionPlan.Resolution.TEST_DOUBLE);
            assertThat(site.ruleRefs()).containsExactly("return-result");
        });
    }

    @Test
    void selectorWithNoStaticInvocationSiteIsRejected() {
        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                bundle(rule("missing", FixtureRule.Selector.node("does-not-exist"),
                        FixtureRule.Behavior.returning("x"))), "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.code()).isEqualTo("CONTROL_PLAN_ZERO_MATCH"));
    }

    @Test
    void samePrecedenceRulesForOneSiteAreRejectedBeforeExecution() {
        FixtureRule first = rule("first", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("a"));
        FixtureRule second = rule("second", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("b"));

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                bundle(first, second), "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.code()).isEqualTo("CONTROL_PLAN_AMBIGUOUS"));
    }

    @Test
    void reservedAttemptAndOccurrenceCoordinatesAreExplicitlyRejected() {
        FixtureRule.Selector selector = new FixtureRule.Selector("/root", "subject", "", "", "",
                List.of(), List.of(), InvocationSite.InvocationKind.PRIMARY,
                List.of(1), List.of(1), "", FixtureRule.Match.none());

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                bundle(rule("reserved", selector, FixtureRule.Behavior.real())),
                "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("attempt/occurrence")));
    }

    @Test
    void unconfiguredExternalEffectCompilesToImplicitDeny() {
        CompiledExecutionControl result = compiler.compile(graph(new ExternalOperator()),
                bundle(), "GRAPH_CONTRACT_TEST", TARGET);

        assertThat(result.controls()).containsKey("subject");
        assertThat(result.controls().get("subject").implicitDeny()).isTrue();
        assertThat(result.effectivePlan().resolvedSites()).singleElement().satisfies(site ->
                assertThat(site.resolution()).isEqualTo(EffectiveExecutionPlan.Resolution.DENIED));
    }

    @Test
    void explicitRealCannotBypassExternalEffectIsolation() {
        FixtureRule real = rule("unsafe-real", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.real());

        assertThatThrownBy(() -> compiler.compile(graph(new ExternalOperator()), bundle(real),
                "OPERATOR_UNIT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.code()).isEqualTo("CONTROL_PLAN_UNSAFE_EXTERNAL_REAL"));
    }

    @Test
    void unsafeRegexIsRejectedBeforeGraphExecution() {
        FixtureRule unsafe = new FixtureRule(FixtureRule.SCHEMA_VERSION, "unsafe-regex",
                FixtureRule.Selector.node("subject").matching(new FixtureRule.Match(
                        null, Map.of(), List.of(), List.of(), Map.of(), "",
                        Map.of("/value", "(a+)+$"))),
                FixtureRule.Behavior.returning("fixture"), FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()), bundle(unsafe),
                "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOf(ControlPlanRejectedException.class)
                .hasMessageContaining("grouping")
                .hasMessageContaining("boundedRegex");
    }

    @Test
    void compiledPlanRetainsTheBindingThatItsFingerprintDescribes() {
        ReadOnlyOperator first = new ReadOnlyOperator();
        ReadOnlyOperator replacement = new ReadOnlyOperator();
        registry.register("mutable-binding", first);
        Graph graph = registryGraph("mutable-binding");

        CompiledExecutionControl compiled = compiler.compile(
                graph, bundle(), "GRAPH_CONTRACT_TEST", TARGET);
        registry.register("mutable-binding", replacement);

        assertThat(compiled.frozenOperators().get("subject")).isSameAs(first);
        assertThat(compiled.frozenOperators().get("subject")).isNotSameAs(replacement);
    }

    @Test
    void logicalTimeControlsRequireAndAcceptAnExplicitRunClock() {
        FixtureRule delay = rule("delay", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.delayed(Duration.ofSeconds(5), "later"));

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()), bundle(delay),
                "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("logicalClock")));

        CompiledExecutionControl compiled = compiler.compile(graph(new ReadOnlyOperator()),
                logicalBundle(delay), "GRAPH_CONTRACT_TEST", TARGET);
        assertThat(compiled.effectivePlan().resolvedSites()).singleElement().satisfies(site -> {
            assertThat(site.behavior()).isEqualTo(FixtureRule.BehaviorKind.DELAY);
            assertThat(site.resolution()).isEqualTo(EffectiveExecutionPlan.Resolution.TEST_DOUBLE);
        });
    }

    @Test
    void invalidTimePayloadsAndReservedRandomSeedRemainFailClosed() {
        FixtureRule misplacedAfter = rule("bad-after", FixtureRule.Selector.node("subject"),
                new FixtureRule.Behavior(FixtureRule.BehaviorKind.RETURN,
                        FixtureRule.DoubleBoundary.NODE, "value", "", null, Map.of(), "", "", "",
                        Duration.ofSeconds(1), List.of(), ""));
        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                logicalBundle(misplacedAfter), "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("only valid")));

        FixtureBundle seeded = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1,
                TARGET, "INTERNAL", Instant.parse("2026-07-15T00:00:00Z"), 42L,
                List.of(), List.of(), Map.of());
        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()), seeded,
                "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("randomSeed")));
    }

    private static Graph graph(Operator<Object, Object> operator) {
        return new GraphBuilder("subject-graph").node("subject", operator).build();
    }

    private static Graph registryGraph(String operatorRef) {
        Graph embedded = graph(new ReadOnlyOperator());
        var node = embedded.nodes().get("subject").toBuilder().operatorRef(operatorRef).build();
        return new Graph(embedded.name(), Map.of("subject", node), embedded.edges(),
                embedded.sourceNodes(), embedded.terminalNodes(), embedded.schemaValidationLevel(),
                Map.of(), embedded.declaredInputSchema(), embedded.declaredOutputSchema(),
                embedded.sagaConfig(), embedded.definitionSource(), embedded.streamingOutputNodeId(),
                embedded.streamingInputs());
    }

    private static FixtureBundle bundle(FixtureRule... rules) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1, TARGET,
                "INTERNAL", null, null, List.of(rules), List.of(), Map.of());
    }

    private static FixtureBundle logicalBundle(FixtureRule... rules) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "logical-fixture", 1, TARGET,
                "INTERNAL", Instant.parse("2026-07-15T00:00:00Z"), null,
                List.of(rules), List.of(), Map.of());
    }

    private static FixtureRule rule(String id, FixtureRule.Selector selector,
                                    FixtureRule.Behavior behavior) {
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, id, selector, behavior,
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static class ReadOnlyOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) {
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
