package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.runtime.registry.GraphDefinitionSource;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.dsl.compiler.GraphLoader;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestMutationCasePlan;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestDslMutationPlannerTest {
    private ObjectMapper mapper;
    private DefaultOperatorRegistry operators;
    private TestDslMutationPlanner planner;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        operators = new DefaultOperatorRegistry();
        planner = new TestDslMutationPlanner(mapper, operators);
    }

    @Test
    void plansDeterministicIndependentlyCompiledPureDslMutants() {
        Graph graph = graph(complexDsl());
        GraphExecutionTargetSnapshot snapshot = GraphExecutionTargetSnapshot.capture(
                mapper, graph, null);
        TestExecutionApiRequest.Target target = target(graph, snapshot.fingerprint());

        TestMutationCasePlan first = planner.plan(
                target, graph, snapshot.dependencyFingerprints(), 64);
        TestMutationCasePlan replay = planner.plan(
                target, graph, snapshot.dependencyFingerprints(), 64);

        assertThat(first).isEqualTo(replay);
        assertThat(first.status()).as(first.gaps().toString())
                .isEqualTo(TestMutationCasePlan.Status.GENERATED);
        assertThat(first.planFingerprint()).startsWith("sha256:");
        assertThat(first.sourceFingerprint()).startsWith("sha256:");
        assertThat(first.graphArtifactFingerprint()).startsWith("sha256:");
        assertThat(first.gaps()).isEmpty();
        assertThat(first.mutants()).isNotEmpty();
        assertThat(first.mutants()).extracting(TestMutationCasePlan.PlannedMutant::mutantId)
                .containsExactlyElementsOf(java.util.stream.IntStream
                        .rangeClosed(1, first.mutants().size())
                        .mapToObj(index -> "mutant-%03d".formatted(index)).toList());
        assertThat(first.mutants())
                .allSatisfy(mutant -> {
                    assertThat(mutant.mutantSourceFingerprint())
                            .isNotEqualTo(first.sourceFingerprint());
                    assertThat(mutant.mutantGraphArtifactFingerprint())
                            .isNotEqualTo(first.graphArtifactFingerprint());
                    assertThat(mutant.mutantTargetFingerprint())
                            .isNotEqualTo(first.target().fingerprint());
                    assertThat(mutant.equivalenceClassification())
                            .isEqualTo(TestMutationCasePlan.EquivalenceClassification.UNKNOWN);
                });
        assertThat(first.mutants()).extracting(TestMutationCasePlan.PlannedMutant::kind)
                .contains(TestMutationCasePlan.MutationKind.BRANCH_MODE_TOGGLED,
                        TestMutationCasePlan.MutationKind.BRANCH_CASE_TARGET_REPLACED,
                        TestMutationCasePlan.MutationKind.DECISION_CONDITION_NEGATED,
                        TestMutationCasePlan.MutationKind.DECISION_FIRST_RULE_ORDER_SWAPPED,
                        TestMutationCasePlan.MutationKind.TRANSFORM_BINDINGS_SWAPPED);
    }

    @Test
    void recoverableDslDecoderRejectsArbitraryTaggedClasses() {
        String hostileTag = """
                {"__recordClass__":"java.lang.ProcessBuilder",
                 "__data__":{"command":[]}}
                """;

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RecoverableDslAstDecoder(mapper).decode(hostileTag))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not allowed");
    }

    @Test
    void recoverableDslDecoderRejectsUnknownRecordFields() throws Exception {
        Graph graph = graph(complexDsl());
        com.fasterxml.jackson.databind.node.ObjectNode tagged =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(
                        graph.definitionSource().payloadJson());
        ((com.fasterxml.jackson.databind.node.ObjectNode) tagged.path("__data__"))
                .put("unexpected", true);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new RecoverableDslAstDecoder(mapper).decode(tagged.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fields are inconsistent");
    }

    @Test
    void refusesOperatorKindForGraphMutationPlans() {
        Graph graph = graph(complexDsl());
        GraphExecutionTargetSnapshot snapshot = GraphExecutionTargetSnapshot.capture(
                mapper, graph, null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner.plan(
                        new TestExecutionApiRequest.Target(
                                "OPERATOR", graph.name(), snapshot.fingerprint()),
                        graph, snapshot.dependencyFingerprints(), 64))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("graph target");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TestMutationCasePlan.MutationPolicy(
                                "custom-planner", 64, TestMutationCasePlan.SOURCE_FORMAT,
                                TestMutationCasePlan.VERIFICATION_MODE, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy is inconsistent");
    }

    @Test
    void reportsTruncationInsteadOfClaimingCompleteMutationCoverage() {
        Graph graph = graph(complexDsl());
        GraphExecutionTargetSnapshot snapshot = GraphExecutionTargetSnapshot.capture(
                mapper, graph, null);

        TestMutationCasePlan plan = planner.plan(
                target(graph, snapshot.fingerprint()), graph,
                snapshot.dependencyFingerprints(), 1);

        assertThat(plan.status()).as(plan.gaps().toString())
                .isEqualTo(TestMutationCasePlan.Status.PARTIAL);
        assertThat(plan.mutants()).hasSize(1);
        assertThat(plan.gaps()).extracting(TestMutationCasePlan.PlanningGap::code)
                .containsExactly(TestMutationCasePlan.GapCode.MUTANT_LIMIT_REACHED);
    }

    @Test
    void refusesGraphsWithoutRecoverableDslSource() {
        Graph compiled = graph(complexDsl());
        Graph graph = compiled.withDefinitionSource(null);
        String fingerprint = GraphExecutionTargetSnapshot.capture(mapper, graph, null).fingerprint();

        TestMutationCasePlan plan = planner.plan(
                target(graph, fingerprint), graph, Map.of(), 64);

        assertThat(plan.status()).isEqualTo(TestMutationCasePlan.Status.UNAVAILABLE);
        assertThat(plan.mutants()).isEmpty();
        assertThat(plan.gaps()).extracting(TestMutationCasePlan.PlanningGap::code)
                .containsExactly(TestMutationCasePlan.GapCode.RECOVERABLE_DSL_SOURCE_UNAVAILABLE);
    }

    @Test
    void refusesSourceThatNoLongerRecompilesToTheCurrentGraph() {
        Graph graph = graph(complexDsl());
        Graph other = graph("""
                graph other {
                  transform output { value = "other" }
                }
                """);
        GraphDefinitionSource otherSource = other.definitionSource();
        Graph tampered = graph.withDefinitionSource(new GraphDefinitionSource(
                graph.definitionSource().graphVersion(), otherSource.format(),
                otherSource.payloadJson()));
        String fingerprint = GraphExecutionTargetSnapshot.capture(mapper, tampered, null).fingerprint();

        TestMutationCasePlan plan = planner.plan(
                target(tampered, fingerprint), tampered, Map.of(), 64);

        assertThat(plan.status()).isEqualTo(TestMutationCasePlan.Status.UNAVAILABLE);
        assertThat(plan.gaps()).extracting(TestMutationCasePlan.PlanningGap::code)
                .containsExactly(TestMutationCasePlan.GapCode.BASELINE_RECOMPILATION_MISMATCH);
    }

    @Test
    void neverMutatesExternalOperatorReferencesOrImplementationSource() {
        operators.registerRaw("externalWrite", (Operator<Object, Object>) (input, context) -> input);
        Graph graph = graph("""
                graph externalFlow {
                  node call : externalWrite {
                    input { value = ctx.value }
                    retry = { attempts: 2, backoff: 1ms }
                    fallback = { status: "fallback" }
                  }
                  transform output {
                    first = call.output
                    second = ctx.value
                  }
                }
                """);
        GraphExecutionTargetSnapshot snapshot = GraphExecutionTargetSnapshot.capture(
                mapper, graph, null);

        TestMutationCasePlan plan = planner.plan(target(graph, snapshot.fingerprint()), graph,
                snapshot.dependencyFingerprints(), 64);

        assertThat(plan.status()).as(plan.gaps().toString())
                .isEqualTo(TestMutationCasePlan.Status.GENERATED);
        assertThat(plan.mutants()).extracting(TestMutationCasePlan.PlannedMutant::kind)
                .containsExactlyInAnyOrder(
                        TestMutationCasePlan.MutationKind.FALLBACK_REMOVED,
                        TestMutationCasePlan.MutationKind.RETRY_ATTEMPTS_DECREMENTED,
                        TestMutationCasePlan.MutationKind.TRANSFORM_BINDINGS_SWAPPED);
        assertThat(plan.mutants()).allSatisfy(mutant ->
                assertThat(mutant.astPath()).doesNotContain("operatorRef", "input"));
    }

    private Graph graph(String dsl) {
        return new GraphLoader(operators).load(dsl);
    }

    private static TestExecutionApiRequest.Target target(Graph graph, String fingerprint) {
        return new TestExecutionApiRequest.Target("GRAPH", graph.name(), fingerprint);
    }

    private static String complexDsl() {
        return """
                graph mutationExample {
                  transform classify {
                    kind = ctx.kind
                    value = ctx.value
                  }
                  branch on classify.output.kind {
                    "a" -> first
                    "b" -> second
                    otherwise -> fallback
                  }
                  transform first { result = "A" }
                  transform second { result = "B" }
                  transform fallback { result = "F" }
                  decision_table policy(score = ctx.score) hit=first -> String {
                    rule (score: score >= 80) -> "approved"
                    rule (score: score >= 60) -> "review"
                    otherwise -> "declined"
                  }
                  transform output {
                    branchResult = first.output ?? second.output ?? fallback.output
                    decision = policy.output
                  }
                }
                """;
    }
}
